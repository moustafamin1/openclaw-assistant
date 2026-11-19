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
import com.openclaw.assistant.ticktick.CaptureSaveOutcome
import com.openclaw.assistant.ticktick.ContextIndexManager
import com.openclaw.assistant.ticktick.OnDeviceLanguageSupport
import com.openclaw.assistant.ticktick.TickTickCaptureRepository
import com.openclaw.assistant.ticktick.TranscriptNormalizer
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
    private var pausedHotword = false

    private val isRecording = AtomicBoolean(false)
    private val micForegroundReleased = AtomicBoolean(false)
    @Volatile private var captureCancelled = false
    private val audioRecordLock = Any()
    @Volatile private var activeAudioRecord: AudioRecord? = null
    private var androidRecognizer: SpeechRecognizer? = null
    @Volatile private var activeSegmentDeferred: CompletableDeferred<Pair<String, Int?>>? = null
    @Volatile private var latestAndroidPartial: String = ""
    @Volatile private var lastStopHapticAtMs: Long = 0L
    @Volatile private var stopListeningDispatched: CompletableDeferred<Unit>? = null
    private val captureFinishedUnlocked = AtomicBoolean(false)
    private val captureLogLock = Any()

    private fun setCaptureDiagnostic(value: String) {
        lastCaptureDiagnostic = value
        runCatching { settings.tickTickLastCaptureDiagnostic = value }
        appendDebugCaptureLog(value)
    }

    private fun appendDebugCaptureLog(line: String) {
        if (!BuildConfig.DEBUG) return
        val entry = "${java.time.Instant.now()} | $line"
        scope.launch(Dispatchers.IO + NonCancellable) {
            runCatching {
                synchronized(captureLogLock) {
                    val logFile = File(filesDir, DEBUG_CAPTURE_LOG_FILE)
                    val existing = if (logFile.exists()) logFile.readLines() else emptyList()
                    val updated = (existing + entry).takeLast(MAX_DEBUG_CAPTURE_LOG_LINES)
                    logFile.writeText(updated.joinToString("\n", postfix = "\n"))
                }
            }
        }
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
        micForegroundReleased.set(false)
        captureFinishedUnlocked.set(false)
        lastStopHapticAtMs = 0L
        isRecording.set(true)
        activeRecording.set(true)

        val useAndroidOnDevice = settings.tickTickSpeechEngine == SettingsRepository.TICKTICK_ENGINE_ANDROID_ON_DEVICE &&
            (SpeechRecognizer.isRecognitionAvailable(this) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(this)))

        stopListeningDispatched = CompletableDeferred()
        captureJob = scope.launch {
            try {
                val transcript = when {
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
                handleTranscriptResult(transcript, cancelled = captureCancelled)
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

        // Flush 380ms of trailing audio before stopping STT/AudioRecord and playing ACK beep
        // so final syllables aren't clipped and the stop beep never leaks into the open mic.
        scope.launch(Dispatchers.Main) {
            delay(380L)
            if (!captureCancelled) {
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

    private fun notifyMicStarted() {
        triggerHaptic(START_HAPTIC_TIMINGS, START_HAPTIC_AMPLITUDES)
        if (settings.tickTickAudioCue) {
            scope.launch(Dispatchers.Main) {
                playBeep(ToneGenerator.TONE_PROP_BEEP)
            }
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

        fun buildRecognizerIntent(targetLang: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 15_000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 15_000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(
                    RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY,
                )
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

        Log.i(TAG, "Starting $engineLabel STT primary=$language multiLang=$multiLangEnabled switchTags=$switchTags")

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
                            notifyMicStarted()
                        }
                    }

                    override fun onBeginningOfSpeech() {
                        sawBeginningOfSpeech = true
                        Log.i(TAG, "onBeginningOfSpeech (+${SystemClock.elapsedRealtime() - readyAtMs}ms)")
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
                        val text = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                        if (text.isNotBlank()) {
                            partialCount++
                            if (partialCount <= 5) {
                                Log.i(TAG, "onPartialResults #$partialCount=\"$text\"")
                            }
                            latestAndroidPartial = text
                        }
                    }

                    override fun onSegmentResults(segmentResults: Bundle) {
                        val candidates = segmentResults
                            .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.filter { it.isNotBlank() }
                            .orEmpty()
                        Log.i(TAG, "onSegmentResults candidates=$candidates keys=${segmentResults.keySet()}")
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
                        Log.i(TAG, "onResults candidates=$candidates bundle={$bundleDebug} lastPartial=\"$latestAndroidPartial\"")
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
                        Log.w(TAG, "Android on-device STT error: $error lastPartial=\"$latestAndroidPartial\"")
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

    private suspend fun handleTranscriptResult(transcript: String, cancelled: Boolean) {
        if (cancelled) return

        when (val outcome = repository.enqueueAndSync(transcript)) {
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
        private const val MAX_DEBUG_CAPTURE_LOG_LINES = 40
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
