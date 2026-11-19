package com.openclaw.assistant.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openclaw.assistant.BuildConfig
import com.openclaw.assistant.R
import com.openclaw.assistant.SettingsActivity
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.VoskModelLoader
import com.openclaw.assistant.ticktick.AudioPcmWavHelper
import com.openclaw.assistant.ticktick.CaptureSaveOutcome
import com.openclaw.assistant.ticktick.ContextIndexManager
import com.openclaw.assistant.ticktick.Gemma4AudioTranscriber
import com.openclaw.assistant.ticktick.Gemma4ModelManager
import com.openclaw.assistant.ticktick.OnDeviceLanguageSupport
import com.openclaw.assistant.ticktick.TickTickCaptureRepository
import com.openclaw.assistant.ticktick.TranscriptNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless foreground microphone service for instant screen-off TickTick voice capture.
 * Runs completely on-device for STT and posts directly to TickTick Inbox with zero LLM calls.
 */
class TickTickVoiceCaptureService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var settings: SettingsRepository
    private lateinit var repository: TickTickCaptureRepository

    private var wakeLock: PowerManager.WakeLock? = null
    private var captureJob: Job? = null
    private var startCueJob: Job? = null
    private var pausedHotword = false

    private val isRecording = AtomicBoolean(false)
    private val audioStopRequested = AtomicBoolean(false)
    private val micForegroundReleased = AtomicBoolean(false)
    @Volatile private var captureCancelled = false
    private val audioRecordLock = Any()
    @Volatile private var activeAudioRecord: AudioRecord? = null
    @Volatile private var lastCapturedGemma4Pcm: ByteArray? = null
    private var androidRecognizer: SpeechRecognizer? = null
    @Volatile private var activeSegmentDeferred: CompletableDeferred<Pair<String, Int?>>? = null
    @Volatile private var latestAndroidPartial: String = ""
    @Volatile private var lastStopHapticAtMs: Long = 0L
    @Volatile private var stopListeningDispatched: CompletableDeferred<Unit>? = null
    private val captureFinishedUnlocked = AtomicBoolean(false)

    private fun setCaptureDiagnostic(value: String) {
        lastCaptureDiagnostic = value
        runCatching { settings.tickTickLastCaptureDiagnostic = value }
        appendDebugCaptureLog(value)
    }

    private fun appendDebugCaptureLog(line: String) {
        if (!BuildConfig.DEBUG) return
        scope.launch(Dispatchers.IO + NonCancellable) {
            appendDebugLog(applicationContext, line)
        }
    }

    internal fun isPartialBufferReset(prev: String, curr: String): Boolean {
        if (prev.length < 12 || curr.isBlank()) return false
        val prevWords = prev.lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .map { it.trim('.', ',', '!', '?', ':', ';', '"') }
            .filter { it.length >= 2 }
        val currWords = curr.lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .map { it.trim('.', ',', '!', '?', ':', ';', '"') }
            .filter { it.length >= 2 }
        if (prevWords.size < 3 || curr.length >= (prev.length * 0.45).toInt()) return false
        val hasOverlap = prevWords.any { pw ->
            pw.length >= 3 && currWords.any { cw ->
                cw.startsWith(pw.take(3)) || pw.startsWith(cw.take(3))
            }
        }
        return !hasOverlap
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository.getInstance(this)
        repository = TickTickCaptureRepository.getInstance(this)
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START_CAPTURE -> startCaptureFlow(
                fromLockscreenActivity = intent.getBooleanExtra(EXTRA_FROM_LOCKSCREEN_ACTIVITY, false),
            )
            ACTION_STOP_CAPTURE -> finishCaptureFlow(cancel = false)
            ACTION_CANCEL_CAPTURE -> finishCaptureFlow(cancel = true)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRecording.set(false)
        activeRecording.set(false)
        startCueJob?.cancel()
        startCueJob = null
        stopListeningDispatched = null
        stopActiveAudioRecord()
        cleanupAndroidRecognizer()
        resumeHotwordIfNeeded()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun startCaptureFlow(fromLockscreenActivity: Boolean = false) {
        if (isRecording.get()) return

        cancelStaleStatusNotification()
        acquireWakeLock()
        if (!promoteForeground(allowLockscreenFallback = !fromLockscreenActivity)) {
            releaseWakeLock()
            stopSelf()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showStatusNotificationIfAllowed(
                title = getString(R.string.ticktick_notif_error_title),
                body = getString(R.string.ticktick_notif_mic_permission),
                openSettings = true,
                force = true,
            )
            releaseMicAndForegroundUi()
            releaseWakeLock()
            stopSelf()
            return
        }

        if (settings.hotwordEnabled) {
            pausedHotword = true
            sendBroadcast(Intent(HotwordService.ACTION_PAUSE_HOTWORD).setPackage(packageName))
        }

        captureCancelled = false
        audioStopRequested.set(false)
        lastCapturedGemma4Pcm = null
        micForegroundReleased.set(false)
        captureFinishedUnlocked.set(false)
        lastStopHapticAtMs = 0L
        isRecording.set(true)
        activeRecording.set(true)

        val enginePref = settings.tickTickSpeechEngine
        val hasAndroidStt = SpeechRecognizer.isRecognitionAvailable(this) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
        val useGemma4 = enginePref == SettingsRepository.TICKTICK_ENGINE_GEMMA4_AUDIO
        val useAndroidOnDevice = enginePref == SettingsRepository.TICKTICK_ENGINE_ANDROID_ON_DEVICE && hasAndroidStt

        stopListeningDispatched = CompletableDeferred()
        captureJob = scope.launch {
            try {
                var skipContextRewrite = false
                val transcript = when {
                    useGemma4 -> {
                        if (Gemma4ModelManager.isModelReady(this@TickTickVoiceCaptureService)) {
                            val gemmaText = captureWithGemma4Audio()
                            if (gemmaText != null) {
                                skipContextRewrite = true
                                gemmaText
                            } else if (!captureCancelled) {
                                val fallbackPcm = lastCapturedGemma4Pcm
                                lastCapturedGemma4Pcm = null
                                if (fallbackPcm != null) {
                                    val voskText = decodePcmWithVosk(fallbackPcm)
                                    setCaptureDiagnostic("$lastCaptureDiagnostic -> Vosk(pcm-fallback) raw=\"$voskText\"")
                                    voskText
                                } else {
                                    ""
                                }
                            } else {
                                ""
                            }
                        } else {
                            appendDebugCaptureLog("Gemma4 model not downloaded -> fallback")
                            if (hasAndroidStt) {
                                val googleText = captureWithAndroidOnDeviceStt()
                                if (googleText != null) {
                                    googleText
                                } else if (isRecording.get() && !captureCancelled) {
                                    val voskText = captureWithVosk()
                                    setCaptureDiagnostic("$lastCaptureDiagnostic -> Vosk(fallback) raw=\"$voskText\"")
                                    voskText
                                } else {
                                    ""
                                }
                            } else {
                                val voskText = captureWithVosk()
                                setCaptureDiagnostic("Gemma4(missing) -> Vosk(fallback) raw=\"$voskText\"")
                                voskText
                            }
                        }
                    }
                    useAndroidOnDevice -> {
                        val googleText = captureWithAndroidOnDeviceStt()
                        if (googleText != null) {
                            googleText
                        } else if (isRecording.get() && !captureCancelled) {
                            val voskText = captureWithVosk()
                            setCaptureDiagnostic("$lastCaptureDiagnostic -> Vosk(fallback) raw=\"$voskText\"")
                            voskText
                        } else {
                            ""
                        }
                    }
                    else -> {
                        val voskText = captureWithVosk()
                        setCaptureDiagnostic("Vosk(selected) raw=\"$voskText\"")
                        voskText
                    }
                }
                handleTranscriptResult(
                    transcript = transcript,
                    cancelled = captureCancelled,
                    skipContextRewrite = skipContextRewrite,
                )
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    releaseMicAndForegroundUi()
                    releaseWakeLock()
                    stopSelf()
                }
            }
        }
    }

    private fun finishCaptureFlow(cancel: Boolean) {
        if (cancel) {
            captureCancelled = true
        }
        if (!isRecording.getAndSet(false)) {
            activeRecording.set(false)
            if (captureJob?.isActive != true) {
                releaseMicAndForegroundUi()
                releaseWakeLock()
                stopSelf()
            }
            return
        }
        activeRecording.set(false)

        if (cancel) {
            audioStopRequested.set(true)
            startCueJob?.cancel()
            stopActiveAudioRecord()
            stopListeningDispatched?.complete(Unit)
            captureJob?.cancel()
            cleanupAndroidRecognizer()
            triggerHaptic(CANCEL_HAPTIC_TIMINGS, CANCEL_HAPTIC_AMPLITUDES)
            releaseMicAndForegroundUi()
            releaseWakeLock()
            stopSelf()
            return
        }

        startCueJob?.cancel()

        // Flush 380ms of trailing audio before stopping STT/AudioRecord and playing ACK beep
        // so final syllables aren't clipped and the stop beep never leaks into the open mic.
        scope.launch(Dispatchers.Main) {
            delay(380L)
            if (!captureCancelled) {
                audioStopRequested.set(true)
                stopActiveAudioRecord()
                runCatching { androidRecognizer?.stopListening() }
                Log.i(TAG, "stopListening dispatched")
                stopListeningDispatched?.complete(Unit)
                lastStopHapticAtMs = SystemClock.elapsedRealtime()
                triggerHaptic(STOP_HAPTIC_TIMINGS, STOP_HAPTIC_AMPLITUDES)
                if (settings.tickTickAudioCue) {
                    delay(100L)
                    if (!captureCancelled) {
                        playBeep(ToneGenerator.TONE_PROP_ACK)
                    }
                }
            }
        }
    }

    private fun notifyMicStarted(playTone: Boolean = true) {
        triggerHaptic(START_HAPTIC_TIMINGS, START_HAPTIC_AMPLITUDES)
        if (playTone && settings.tickTickAudioCue) {
            scope.launch(Dispatchers.Main) {
                playBeep(ToneGenerator.TONE_PROP_BEEP)
            }
        }
    }

    private suspend fun captureWithGemma4Audio(): String? = withContext(Dispatchers.IO) {
        val maxBytes = AudioPcmWavHelper.MAX_TOTAL_SECONDS * AudioPcmWavHelper.BYTES_PER_SECOND
        val readBuffer = ByteArray(READ_CHUNK_BYTES)
        val pcmOut = ByteArrayOutputStream(SAMPLE_RATE * 2 * 6)
        val vocab = settings.effectiveTickTickVocabulary()
        val ctxIndex = ContextIndexManager.getIndex(applicationContext)
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(READ_CHUNK_BYTES * 2)

        val audioRecord = openAudioRecordWithRetry(bufferSize)
        if (audioRecord == null) {
            Log.e(TAG, "Unable to open AudioRecord for Gemma 4 capture")
            setCaptureDiagnostic("Gemma4(mic-open-failed)")
            withContext(NonCancellable + Dispatchers.Main) {
                notifyCaptureUiUnlocked()
            }
            return@withContext null
        }

        try {
            synchronized(audioRecordLock) {
                activeAudioRecord = audioRecord
            }
            audioRecord.startRecording()
            notifyMicStarted(playTone = true)

            // Overlap CPU engine reload + system-prompt prefill while the user speaks.
            scope.launch(Dispatchers.IO) {
                runCatching {
                    Gemma4AudioTranscriber.prewarm(
                        context = applicationContext,
                        customVocabularyRaw = vocab,
                        contextIndex = ctxIndex,
                    )
                }.onFailure { err ->
                    Log.w(TAG, "Gemma 4 prewarm failed: ${err.message}")
                }
            }

            while (!audioStopRequested.get() && !captureCancelled && pcmOut.size() < maxBytes) {
                val read = audioRecord.read(readBuffer, 0, readBuffer.size)
                if (read > 0) {
                    pcmOut.write(readBuffer, 0, read)
                } else if (read < 0) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemma 4 audio capture error", e)
        } finally {
            synchronized(audioRecordLock) {
                if (activeAudioRecord === audioRecord) {
                    activeAudioRecord = null
                }
            }
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
        }

        // Immediately unlock hardware volume keys while WakeLock + FGS stay alive for inference & haptic.
        withContext(NonCancellable + Dispatchers.Main) {
            notifyCaptureUiUnlocked()
        }

        if (captureCancelled) return@withContext ""
        val pcmBytes = pcmOut.toByteArray()
        val durMs = AudioPcmWavHelper.durationMs(pcmBytes)
        if (!AudioPcmWavHelper.hasAudibleSpeech(pcmBytes)) {
            setCaptureDiagnostic("Gemma4(silence dur=${durMs}ms)")
            return@withContext ""
        }

        return@withContext try {
            val result = Gemma4AudioTranscriber.transcribePcm(
                context = applicationContext,
                pcmBytes = pcmBytes,
                customVocabularyRaw = vocab,
                contextIndex = ctxIndex,
            )
            setCaptureDiagnostic(
                "Gemma4(backend=${result.backendUsed}, load=${result.loadMs}ms, infer=${result.inferMs}ms, dur=${result.audioDurationMs}ms, chunks=${result.chunkCount}) out=\"${result.text}\"",
            )
            result.text
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "Gemma 4 transcription failed: ${t.message}", t)
            setCaptureDiagnostic("Gemma4 FAILED(${t.message ?: t.javaClass.simpleName})")
            lastCapturedGemma4Pcm = pcmBytes
            null
        }
    }

    private suspend fun decodePcmWithVosk(pcmBytes: ByteArray): String = withContext(Dispatchers.IO) {
        val model = VoskModelLoader.getOrLoadModel(this@TickTickVoiceCaptureService) ?: return@withContext ""
        var recognizer: Recognizer? = null
        try {
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            var offset = 0
            while (offset < pcmBytes.size) {
                val len = (pcmBytes.size - offset).coerceAtMost(4096)
                val slice = pcmBytes.copyOfRange(offset, offset + len)
                recognizer.acceptWaveForm(slice, len)
                offset += len
            }
            JSONObject(recognizer.finalResult).optString("text", "")
        } catch (e: Exception) {
            Log.e(TAG, "Vosk PCM fallback failed", e)
            ""
        } finally {
            runCatching { recognizer?.close() }
        }
    }

    private suspend fun captureWithVosk(): String = withContext(Dispatchers.IO) {
        val modelDeferred = async(Dispatchers.IO) {
            VoskModelLoader.getOrLoadModel(this@TickTickVoiceCaptureService)
        }
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(READ_CHUNK_BYTES * 2)

        val audioRecord = openAudioRecordWithRetry(bufferSize)
        if (audioRecord == null) {
            Log.e(TAG, "Unable to open AudioRecord")
            modelDeferred.cancel()
            withContext(NonCancellable + Dispatchers.Main) {
                releaseMicAndForegroundUi()
            }
            return@withContext ""
        }

        val pendingPreModelChunks = mutableListOf<ByteArray>()
        var recognizer: Recognizer? = null
        val readBuffer = ByteArray(READ_CHUNK_BYTES)
        var capturedAnyAudio = false

        try {
            synchronized(audioRecordLock) {
                activeAudioRecord = audioRecord
            }
            audioRecord.startRecording()
            notifyMicStarted()

            while (isRecording.get()) {
                val read = audioRecord.read(readBuffer, 0, readBuffer.size)
                if (read > 0) {
                    capturedAnyAudio = true
                    if (recognizer == null && modelDeferred.isCompleted) {
                        val model = runCatching { modelDeferred.await() }.getOrNull()
                        if (model != null) {
                            recognizer = runCatching { Recognizer(model, SAMPLE_RATE.toFloat()) }.getOrNull()
                            if (recognizer != null) {
                                for (pending in pendingPreModelChunks) {
                                    recognizer?.acceptWaveForm(pending, pending.size)
                                }
                                pendingPreModelChunks.clear()
                            }
                        }
                    }
                    val activeRec = recognizer
                    if (activeRec != null) {
                        activeRec.acceptWaveForm(readBuffer, read)
                    } else if (pendingPreModelChunks.size < MAX_PRE_MODEL_CHUNKS) {
                        pendingPreModelChunks.add(readBuffer.copyOf(read))
                    }
                } else if (read < 0) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vosk streaming capture error", e)
        } finally {
            synchronized(audioRecordLock) {
                if (activeAudioRecord === audioRecord) {
                    activeAudioRecord = null
                }
            }
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
        }

        // Unlock volume keys & dismiss overlay immediately while staying foreground until save + haptic finish.
        withContext(NonCancellable + Dispatchers.Main) {
            notifyCaptureUiUnlocked()
        }

        return@withContext try {
            if (!capturedAnyAudio || captureCancelled) {
                modelDeferred.cancel()
                ""
            } else {
                var finalRecognizer = recognizer
                if (finalRecognizer == null) {
                    val model = modelDeferred.await()
                    if (model != null) {
                        finalRecognizer = Recognizer(model, SAMPLE_RATE.toFloat()).also { rec ->
                            recognizer = rec
                            for (pending in pendingPreModelChunks) {
                                rec.acceptWaveForm(pending, pending.size)
                            }
                            pendingPreModelChunks.clear()
                        }
                    }
                }
                if (finalRecognizer == null) {
                    ""
                } else {
                    val json = JSONObject(finalRecognizer.finalResult)
                    json.optString("text", "")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vosk decoding failed", e)
            ""
        } finally {
            runCatching { recognizer?.close() }
        }
    }

    private fun stopActiveAudioRecord() {
        synchronized(audioRecordLock) {
            runCatching { activeAudioRecord?.stop() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openAudioRecordWithRetry(bufferSize: Int): AudioRecord? {
        val sources = listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        repeat(6) { attempt ->
            for (source in sources) {
                val recorder = runCatching {
                    AudioRecord(
                        source,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize * 2,
                    )
                }.getOrNull()
                if (recorder != null && recorder.state == AudioRecord.STATE_INITIALIZED) {
                    return recorder
                }
                recorder?.release()
            }
            if (attempt < 5) Thread.sleep(60L)
        }
        return null
    }

    private suspend fun captureWithAndroidOnDeviceStt(): String? {
        val useSystemSpeech = SpeechRecognizer.isRecognitionAvailable(this)
        val hasOnDeviceFallback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        if (!useSystemSpeech && !hasOnDeviceFallback) return null

        val engineLabel = if (useSystemSpeech) "Google-Speech" else "Google-OnDevice"
        val multiLangEnabled = settings.tickTickMultiLangSwitch
        val sessionSegments = java.util.Collections.synchronizedList(mutableListOf<String>())
        val startedNotified = AtomicBoolean(false)
        var shouldFallbackToVosk = false
        var lastResultMode = "none"
        var lastRawCandidates: List<String> = emptyList()
        var lastErrCode: Int? = null
        var retriedEnUs = false
        var sawBeginningOfSpeech = false
        var partialCount = 0
        var peakRms = -100f
        var readyAtMs = 0L
        var completedCleanly = false

        var language = OnDeviceLanguageSupport.resolvePrimaryLanguage(settings.speechLanguage)
        val switchTags = if (multiLangEnabled) {
            OnDeviceLanguageSupport.allowedSwitchTags(language)
        } else {
            emptyList()
        }
        val (effectiveVocab, contextIndex) = withContext(Dispatchers.IO) {
            val vocab = settings.effectiveTickTickVocabulary()
            val ctx = ContextIndexManager.getIndex(applicationContext)
            vocab to ctx
        }

        val biasingStrings = TranscriptNormalizer.biasingTerms(
            customVocabularyRaw = effectiveVocab,
            maxTerms = 60,
            contextIndex = contextIndex,
        )

        fun buildRecognizerIntent(targetLang: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(
                    RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY,
                )
                if (biasingStrings.isNotEmpty()) {
                    putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, biasingStrings)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && switchTags.size >= 2) {
                putExtra(
                    RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                    RecognizerIntent.LANGUAGE_SWITCH_HIGH_PRECISION,
                )
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                    ArrayList(switchTags),
                )
            }
        }

        Log.i(TAG, "Starting $engineLabel STT primary=$language multiLang=$multiLangEnabled switchTags=$switchTags bias=${biasingStrings.size}")

        val recognizer = withContext(Dispatchers.Main) {
            val created = if (useSystemSpeech) {
                SpeechRecognizer.createSpeechRecognizer(this@TickTickVoiceCaptureService)
            } else {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this@TickTickVoiceCaptureService)
            }
            created.also { rec ->
                androidRecognizer = rec
                rec.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        readyAtMs = SystemClock.elapsedRealtime()
                        Log.i(TAG, "onReadyForSpeech")
                        if (startedNotified.compareAndSet(false, true)) {
                            startCueJob?.cancel()
                            startCueJob = scope.launch(Dispatchers.Main) {
                                if (useSystemSpeech) {
                                    // Wait for GoogleTTS's open chime & SODA pusher init before vibrating ready
                                    delay(420L)
                                }
                                if (isRecording.get() && !captureCancelled) {
                                    notifyMicStarted(playTone = !useSystemSpeech)
                                }
                            }
                        }
                    }

                    override fun onBeginningOfSpeech() {
                        sawBeginningOfSpeech = true
                        val delta = SystemClock.elapsedRealtime() - readyAtMs
                        Log.i(TAG, "onBeginningOfSpeech (+${delta}ms)")
                        appendDebugCaptureLog("onBeginningOfSpeech +${delta}ms")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        if (rmsdB > peakRms) peakRms = rmsdB
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.i(TAG, "onEndOfSpeech partials=$partialCount peakRms=$peakRms")
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onPartialResults(partialResults: Bundle?) {
                        val stable = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        val unstable = partialResults
                            ?.getStringArrayList("android.speech.extra.UNSTABLE_TEXT")
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        val combined = when {
                            stable.isNotEmpty() && unstable.isNotEmpty() && !stable.endsWith(unstable, ignoreCase = true) ->
                                "$stable $unstable".trim()
                            stable.isNotEmpty() -> stable
                            else -> unstable
                        }
                        if (combined.isNotBlank()) {
                            partialCount++
                            if (partialCount <= 3 || partialCount % 8 == 0) {
                                Log.i(TAG, "onPartialResults #$partialCount=\"$combined\"")
                                appendDebugCaptureLog("partial #$partialCount=\"$combined\"")
                            }
                            val prev = latestAndroidPartial
                            if (isPartialBufferReset(prev, combined)) {
                                val committed = TranscriptNormalizer.selectBestHypothesis(
                                    listOf(prev),
                                    effectiveVocab,
                                    contextIndex,
                                )
                                if (committed.isNotBlank() && sessionSegments.lastOrNull() != committed) {
                                    sessionSegments.add(committed)
                                    Log.i(TAG, "Committed reset partial segment=\"$committed\"")
                                    appendDebugCaptureLog("partialReset committed=\"$committed\" next=\"$combined\"")
                                }
                            }
                            latestAndroidPartial = combined
                        }
                    }

                    override fun onSegmentResults(segmentResults: Bundle) {
                        val candidates = segmentResults
                            .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.filter { it.isNotBlank() }
                            .orEmpty()
                        val keys = segmentResults.keySet()
                        Log.i(TAG, "onSegmentResults candidates=$candidates keys=$keys")
                        appendDebugCaptureLog("onSegmentResults candidates=$candidates keys=$keys")
                        if (candidates.isNotEmpty()) {
                            lastRawCandidates = candidates
                            lastResultMode = "onSegmentResults"
                        }
                        val segText = TranscriptNormalizer.selectBestHypothesis(candidates, effectiveVocab, contextIndex)
                        if (segText.isNotEmpty()) {
                            sessionSegments.add(segText)
                            latestAndroidPartial = ""
                        }
                    }

                    override fun onEndOfSegmentedSession() {
                        completedCleanly = true
                        val trailing = latestAndroidPartial.trim()
                        if (trailing.isNotEmpty()) lastResultMode = "partial-end-seg"
                        latestAndroidPartial = ""
                        activeSegmentDeferred?.complete(trailing to null)
                    }

                    override fun onResults(results: Bundle?) {
                        val candidates = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.filter { it.isNotBlank() }
                            .orEmpty()
                        @Suppress("DEPRECATION")
                        val bundleDebug = results?.keySet()?.joinToString(", ") { k ->
                            "$k=${results.get(k)?.toString()?.take(80)}"
                        } ?: "null"
                        val msg = "onResults candidates=$candidates bundle={$bundleDebug} lastPartial=\"$latestAndroidPartial\""
                        Log.i(TAG, msg)
                        appendDebugCaptureLog(msg)
                        completedCleanly = true
                        if (candidates.isNotEmpty()) {
                            lastRawCandidates = candidates
                            lastResultMode = "onResults(Pass2)"
                        } else if (sessionSegments.isEmpty()) {
                            lastResultMode = "partial-empty-res"
                        }
                        val text = TranscriptNormalizer.selectBestHypothesis(candidates, effectiveVocab, contextIndex)
                        val trailing = text.ifEmpty { latestAndroidPartial.trim() }
                        latestAndroidPartial = ""
                        activeSegmentDeferred?.complete(trailing to null)
                    }

                    override fun onError(error: Int) {
                        val msg = "Android on-device STT error: $error lastPartial=\"$latestAndroidPartial\""
                        Log.w(TAG, msg)
                        appendDebugCaptureLog(msg)
                        completedCleanly = true
                        lastErrCode = error
                        val trailing = latestAndroidPartial.trim()
                        if (trailing.isNotEmpty() && lastResultMode == "none") {
                            lastResultMode = "partial-err-$error"
                        }
                        latestAndroidPartial = ""
                        activeSegmentDeferred?.complete(trailing to error)
                    }
                })
            }
        }

        try {
            while (isRecording.get() && !captureCancelled) {
                latestAndroidPartial = ""
                completedCleanly = false
                val segDone = CompletableDeferred<Pair<String, Int?>>()
                activeSegmentDeferred = segDone
                val intent = buildRecognizerIntent(language)

                withContext(Dispatchers.Main) {
                    recognizer.startListening(intent)
                }

                while (isRecording.get() && !segDone.isCompleted) {
                    delay(50L)
                }

                if (!isRecording.get() && !captureCancelled && !segDone.isCompleted) {
                    withTimeoutOrNull(700L) { stopListeningDispatched?.await() }
                    if (stopListeningDispatched?.isCompleted != true) {
                        withContext(Dispatchers.Main) {
                            runCatching { recognizer.stopListening() }
                        }
                        Log.i(TAG, "stopListening dispatched (fallback)")
                        stopListeningDispatched?.complete(Unit)
                    }
                }

                val awaited = withTimeoutOrNull(6_500L) { segDone.await() }
                val (tailText, segErr) = if (awaited != null) {
                    awaited
                } else {
                    lastResultMode = "partial-timeout"
                    latestAndroidPartial.trim() to null
                }
                activeSegmentDeferred = null

                if (tailText.isNotBlank() && sessionSegments.lastOrNull() != tailText) {
                    sessionSegments.add(tailText)
                }

                if (!isRecording.get() || captureCancelled) {
                    break
                }

                // If chosen locale tag was rejected (e.g. en-DE), retry once with en-US before Vosk fallback.
                if ((segErr == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        segErr == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) &&
                    !retriedEnUs &&
                    !language.equals("en-US", ignoreCase = true)
                ) {
                    retriedEnUs = true
                    language = "en-US"
                    delay(40L)
                    continue
                }

                val isFatalStartupError = segErr != null && (
                    segErr == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                        segErr == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        segErr == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                        (segErr != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                            segErr != SpeechRecognizer.ERROR_NO_MATCH &&
                            segErr != SpeechRecognizer.ERROR_RECOGNIZER_BUSY &&
                            !startedNotified.get() &&
                            sessionSegments.isEmpty())
                    )
                if (isFatalStartupError) {
                    shouldFallbackToVosk = true
                    break
                }

                if (segErr == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    withContext(Dispatchers.Main) {
                        runCatching { recognizer.cancel() }
                    }
                    delay(120L)
                } else {
                    delay(40L)
                }
            }
        } finally {
            activeSegmentDeferred = null
            withContext(NonCancellable + Dispatchers.Main) {
                cleanupAndroidRecognizer(alreadyCompleted = completedCleanly)
                if (!shouldFallbackToVosk) {
                    notifyCaptureUiUnlocked()
                }
            }
        }

        val finalRaw = sessionSegments.joinToString(" ").trim()
        val nBestPreview = lastRawCandidates.take(3).joinToString(" | ") { "\"$it\"" }
        val errSuffix = lastErrCode?.let { " err=$it" }.orEmpty()
        val micStats = "bos=$sawBeginningOfSpeech partials=$partialCount rms=${String.format(Locale.US, "%.1f", peakRms)}"
        val diag = if (shouldFallbackToVosk) {
            "$engineLabel FAILED($language$errSuffix $micStats)"
        } else {
            "$engineLabel($language, $lastResultMode, $micStats$errSuffix) nBest=[$nBestPreview] selected=\"$finalRaw\""
        }
        setCaptureDiagnostic(diag)
        Log.i(TAG, "Capture diagnostic: $lastCaptureDiagnostic")

        return if (shouldFallbackToVosk) {
            null
        } else {
            finalRaw
        }
    }

    private suspend fun triggerSavedHapticCleanly() {
        val sinceStop = SystemClock.elapsedRealtime() - lastStopHapticAtMs
        if (lastStopHapticAtMs > 0L && sinceStop in 0L..220L) {
            delay(220L - sinceStop)
        }
        triggerHaptic(SAVED_HAPTIC_TIMINGS, SAVED_HAPTIC_AMPLITUDES)
        // Keep foreground service alive until the 255ms double-pulse waveform finishes.
        delay(320L)
    }

    private suspend fun handleTranscriptResult(
        transcript: String,
        cancelled: Boolean,
        skipContextRewrite: Boolean = false,
    ) {
        if (cancelled) return

        when (val outcome = repository.enqueueAndSync(transcript, skipContextRewrite = skipContextRewrite)) {
            is CaptureSaveOutcome.Synced -> {
                setCaptureDiagnostic("$lastCaptureDiagnostic -> saved=\"${outcome.title}\"")
                if (outcome.taskId.isNotBlank()) {
                    triggerSavedHapticCleanly()
                }
                showStatusNotificationIfAllowed(
                    title = getString(R.string.ticktick_notif_saved_title),
                    body = outcome.title,
                    openSettings = false,
                )
            }
            is CaptureSaveOutcome.QueuedOffline -> {
                setCaptureDiagnostic("$lastCaptureDiagnostic -> saved=\"${outcome.title}\" (offline)")
                triggerSavedHapticCleanly()
                showStatusNotificationIfAllowed(
                    title = getString(R.string.ticktick_notif_queued_title),
                    body = outcome.title,
                    openSettings = true,
                )
            }
            is CaptureSaveOutcome.AuthRequired -> {
                setCaptureDiagnostic("$lastCaptureDiagnostic -> auth-error")
                triggerHaptic(ERROR_HAPTIC_TIMINGS, ERROR_HAPTIC_AMPLITUDES)
                delay(360L)
                showStatusNotificationIfAllowed(
                    title = getString(R.string.ticktick_notif_auth_title),
                    body = outcome.title,
                    openSettings = true,
                )
            }
            CaptureSaveOutcome.IgnoredBlank -> {
                setCaptureDiagnostic("$lastCaptureDiagnostic -> blank")
                showStatusNotificationIfAllowed(
                    title = getString(R.string.ticktick_notif_blank_title),
                    body = getString(R.string.ticktick_notif_blank_body),
                    openSettings = false,
                )
            }
        }
    }

    private fun notifyCaptureUiUnlocked() {
        activeRecording.set(false)
        if (!captureFinishedUnlocked.compareAndSet(false, true)) return
        com.openclaw.assistant.ticktick.TickTickOverlayAnchor.hide(this)
        com.openclaw.assistant.ui.ticktick.TickTickLockCaptureActivity.cancelNotification(this)
        sendBroadcast(Intent(ACTION_CAPTURE_FINISHED).setPackage(packageName))
        resumeHotwordIfNeeded()
    }

    private fun releaseMicAndForegroundUi() {
        notifyCaptureUiUnlocked()
        if (!micForegroundReleased.compareAndSet(false, true)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_CAPTURE_ID)
        }
    }

    private fun resumeHotwordIfNeeded() {
        if (!pausedHotword) return
        pausedHotword = false
        sendBroadcast(Intent(HotwordService.ACTION_RESUME_HOTWORD).setPackage(packageName))
    }

    private fun cleanupAndroidRecognizer(alreadyCompleted: Boolean = false) {
        val rec = androidRecognizer ?: return
        androidRecognizer = null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!alreadyCompleted) runCatching { rec.cancel() }
            runCatching { rec.destroy() }
        } else {
            scope.launch(Dispatchers.Main) {
                if (!alreadyCompleted) runCatching { rec.cancel() }
                runCatching { rec.destroy() }
            }
        }
    }

    private fun promoteForeground(allowLockscreenFallback: Boolean): Boolean {
        val notification = buildRecordingNotification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_CAPTURE_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_CAPTURE_ID, notification)
            }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone foreground service blocked by OS in current state", e)
            if (allowLockscreenFallback && com.openclaw.assistant.ui.ticktick.TickTickLockCaptureActivity.isHoldActive()) {
                runCatching {
                    com.openclaw.assistant.ui.ticktick.TickTickLockCaptureActivity.launchFromOffScreen(
                        this,
                        requireActiveHold = true,
                    )
                }.onFailure { launchErr ->
                    Log.e(TAG, "Lockscreen trampoline launch failed", launchErr)
                    showStatusNotificationIfAllowed(
                        title = getString(R.string.ticktick_notif_error_title),
                        body = getString(R.string.ticktick_notif_bg_mic_blocked),
                        openSettings = true,
                    )
                }
            } else {
                showStatusNotificationIfAllowed(
                    title = getString(R.string.ticktick_notif_error_title),
                    body = getString(R.string.ticktick_notif_bg_mic_blocked),
                    openSettings = true,
                )
            }
            false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Foreground service start not allowed", e)
            showStatusNotificationIfAllowed(
                title = getString(R.string.ticktick_notif_error_title),
                body = getString(R.string.ticktick_notif_bg_mic_blocked),
                openSettings = true,
            )
            false
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun triggerHaptic(timings: LongArray, amplitudes: IntArray) {
        if (!settings.tickTickHapticFeedback) return
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (!vibrator.hasVibrator()) return
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            } else {
                VibrationEffect.createWaveform(timings, -1)
            }
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    val attrs = VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_ACCESSIBILITY)
                        .build()
                    vibrator.vibrate(effect, attrs)
                }
                else -> {
                    @Suppress("DEPRECATION")
                    val audioAttrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(effect, audioAttrs)
                }
            }
        }.onFailure { err ->
            Log.w(TAG, "Haptic playback skipped: ${err.message}")
        }
    }

    private fun playBeep(tone: Int) {
        runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60).apply {
                startTone(tone, 120)
                scope.launch {
                    delay(200L)
                    runCatching { release() }
                }
            }
        }
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.deleteNotificationChannel(LEGACY_CHANNEL_CAPTURE) }
        val captureChannel = NotificationChannel(
            CHANNEL_CAPTURE,
            getString(R.string.ticktick_channel_capture_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        val statusChannel = NotificationChannel(
            CHANNEL_STATUS,
            getString(R.string.ticktick_channel_status_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        nm.createNotificationChannel(captureChannel)
        nm.createNotificationChannel(statusChannel)
    }

    private fun buildRecordingNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.ticktick_notif_listening_title))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setSilent(true)
            .setLocalOnly(true)
            .setOngoing(true)
            .build()
    }

    private fun cancelStaleStatusNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_RESULT_ID)
        }
    }

    private fun showStatusNotificationIfAllowed(
        title: String,
        body: String,
        openSettings: Boolean,
        force: Boolean = false,
    ) {
        if (!force && !settings.tickTickStatusNotifications) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val builder = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setTimeoutAfter(7000L)

        if (openSettings) {
            val intent = Intent(this, SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                this,
                2,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(pending)
        }

        nm.notify(NOTIFICATION_RESULT_ID, builder.build())
    }

    companion object {
        private const val TAG = "TickTickVoiceCapture"
        private const val SAMPLE_RATE = 16000
        private const val READ_CHUNK_BYTES = 1600 // 50ms of 16kHz 16-bit mono PCM
        private const val MAX_PRE_MODEL_CHUNKS = 200 // ~10s safety cap while Vosk warms up
        private const val WAKE_LOCK_TIMEOUT_MS = 330_000L // >5 min capture cap
        private const val LEGACY_CHANNEL_CAPTURE = "ticktick_voice_capture"
        private const val CHANNEL_CAPTURE = "ticktick_voice_capture_silent"
        private const val CHANNEL_STATUS = "ticktick_voice_status"
        private const val NOTIFICATION_CAPTURE_ID = 2401
        private const val NOTIFICATION_RESULT_ID = 2402
        private const val WAKE_LOCK_TAG = "WakeHermesClaw::TickTickVoiceCapture"
        private const val DEBUG_CAPTURE_LOG_FILE = "ticktick_capture_debug.log"
        private const val MAX_DEBUG_CAPTURE_LOG_LINES = 100
        private val fileLogLock = Any()

        fun appendDebugLog(context: Context, line: String) {
            if (!BuildConfig.DEBUG) return
            val entry = "${java.time.Instant.now()} | $line"
            runCatching {
                synchronized(fileLogLock) {
                    val logFile = File(context.applicationContext.filesDir, DEBUG_CAPTURE_LOG_FILE)
                    val existing = if (logFile.exists()) logFile.readLines() else emptyList()
                    val updated = (existing + entry).takeLast(MAX_DEBUG_CAPTURE_LOG_LINES)
                    logFile.writeText(updated.joinToString("\n", postfix = "\n"))
                }
            }
        }
        private val START_HAPTIC_TIMINGS = longArrayOf(0L, 75L)
        private val START_HAPTIC_AMPLITUDES = intArrayOf(0, 255)
        private val STOP_HAPTIC_TIMINGS = longArrayOf(0L, 30L, 45L, 45L)
        private val STOP_HAPTIC_AMPLITUDES = intArrayOf(0, 255, 0, 255)
        private val SAVED_HAPTIC_TIMINGS = longArrayOf(0L, 45L, 70L, 140L)
        private val SAVED_HAPTIC_AMPLITUDES = intArrayOf(0, 180, 0, 255)
        private val CANCEL_HAPTIC_TIMINGS = longArrayOf(0L, 25L, 50L, 25L)
        private val CANCEL_HAPTIC_AMPLITUDES = intArrayOf(0, 200, 0, 200)
        private val ERROR_HAPTIC_TIMINGS = longArrayOf(0L, 60L, 60L, 60L, 60L, 60L)
        private val ERROR_HAPTIC_AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255)
        private val activeRecording = AtomicBoolean(false)
        @Volatile
        var lastCaptureDiagnostic: String = "No capture yet"

        fun isCapturing(): Boolean = activeRecording.get()

        const val ACTION_START_CAPTURE = "com.openclaw.assistant.ticktick.ACTION_START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.openclaw.assistant.ticktick.ACTION_STOP_CAPTURE"
        const val ACTION_CANCEL_CAPTURE = "com.openclaw.assistant.ticktick.ACTION_CANCEL_CAPTURE"
        const val ACTION_CAPTURE_FINISHED = "com.openclaw.assistant.ticktick.ACTION_CAPTURE_FINISHED"
        private const val EXTRA_FROM_LOCKSCREEN_ACTIVITY = "from_lockscreen_activity"

        fun startCapture(context: Context, fromLockscreenActivity: Boolean = false) {
            val intent = Intent(context, TickTickVoiceCaptureService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(EXTRA_FROM_LOCKSCREEN_ACTIVITY, fromLockscreenActivity)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopCapture(context: Context, cancel: Boolean = false) {
            val intent = Intent(context, TickTickVoiceCaptureService::class.java).apply {
                action = if (cancel) ACTION_CANCEL_CAPTURE else ACTION_STOP_CAPTURE
            }
            runCatching { context.startService(intent) }
        }
    }
}
