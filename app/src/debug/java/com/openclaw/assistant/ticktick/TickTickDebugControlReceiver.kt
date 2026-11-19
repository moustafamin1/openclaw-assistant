package com.openclaw.assistant.ticktick

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.service.TickTickVoiceCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug-only ADB control receiver (compiled strictly in `src/debug/`).
 * Allows headless switching of the TickTick voice engine and verifying on-device
 * Gemma 4 E2B audio inference directly against WAV fixtures without tapping UI.
 */
class TickTickDebugControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenClaw::TickTickDebugProbe")?.apply {
            setReferenceCounted(false)
            acquire(240_000L)
        }
        val pendingResult = goAsync()
        var pendingFinished = false
        fun finishBroadcastOnce() {
            if (!pendingFinished) {
                pendingFinished = true
                runCatching { pendingResult.finish() }
            }
        }

        probeScope.launch {
            try {
                when (action) {
                    ACTION_DEBUG_SET_ENGINE -> {
                        val engine = intent.getStringExtra(EXTRA_ENGINE)?.trim().orEmpty()
                        if (engine.isNotEmpty()) {
                            SettingsRepository.getInstance(appContext).tickTickSpeechEngine = engine
                            val ready = Gemma4ModelManager.isModelReady(appContext)
                            val msg = "DEBUG set_engine=$engine gemma4Ready=$ready"
                            Log.i(TAG, msg)
                            TickTickVoiceCaptureService.appendDebugLog(appContext, msg)
                        } else {
                            TickTickVoiceCaptureService.appendDebugLog(appContext, "DEBUG set_engine missing 'engine' extra")
                        }
                    }

                    ACTION_DEBUG_TRANSCRIBE_WAV -> {
                        val wavPath = intent.getStringExtra(EXTRA_WAV_PATH)?.trim().orEmpty()
                        val saveToTickTick = intent.getBooleanExtra(EXTRA_SAVE, false)
                        val backendOverride = intent.getStringExtra(EXTRA_BACKEND)?.trim()
                        val wavFile = File(wavPath)
                        if (wavPath.isEmpty() || !wavFile.exists()) {
                            val msg = "DEBUG wav_error missing file path=\"$wavPath\""
                            Log.w(TAG, msg)
                            TickTickVoiceCaptureService.appendDebugLog(appContext, msg)
                            return@launch
                        }

                        // Release BroadcastQueue slot immediately so cold GPU cache compilation (>60s) never ANRs.
                        finishBroadcastOnce()

                        TickTickVoiceCaptureService.appendDebugLog(appContext, "DEBUG wav_start file=${wavFile.name} reqBackend=${backendOverride ?: "auto"}")
                        Log.i(TAG, "DEBUG wav_start file=${wavFile.name} reqBackend=${backendOverride ?: "auto"}")

                        val wavBytes = wavFile.readBytes()
                        val pcmBytes = AudioPcmWavHelper.wavToPcm(wavBytes)
                        val settings = SettingsRepository.getInstance(appContext)
                        val vocab = settings.effectiveTickTickVocabulary()
                        val contextIndex = ContextIndexManager.getIndex(appContext)

                        val res = Gemma4AudioTranscriber.transcribePcm(
                            context = appContext,
                            pcmBytes = pcmBytes,
                            customVocabularyRaw = vocab,
                            contextIndex = contextIndex,
                            backendOverride = backendOverride,
                        )
                        val normalized = TranscriptNormalizer.normalize(
                            res.text,
                            vocab,
                            skipContextRewrite = true,
                        )
                        val msg = buildString {
                            append("DEBUG wav_result file=${wavFile.name}")
                            append(" backend=${res.backendUsed}")
                            append(" loadMs=${res.loadMs}")
                            append(" inferMs=${res.inferMs}")
                            append(" durMs=${res.audioDurationMs}")
                            append(" chunks=${res.chunkCount}")
                            append(" text=\"$normalized\"")
                            if (res.text != normalized) {
                                append(" gemmaRaw=\"${res.text}\"")
                            }
                            if (res.rawOutput != res.text) {
                                append(" raw=\"${res.rawOutput}\"")
                            }
                            if (res.benchmarkSummary.isNotEmpty()) {
                                append(" bench=\"${res.benchmarkSummary}\"")
                            }
                        }
                        Log.i(TAG, msg)
                        TickTickVoiceCaptureService.appendDebugLog(appContext, msg)

                        if (saveToTickTick && normalized.isNotBlank()) {
                            val outcome = TickTickCaptureRepository.getInstance(appContext)
                                .enqueueAndSync(res.text, skipContextRewrite = true)
                            TickTickVoiceCaptureService.appendDebugLog(appContext, "DEBUG wav_save=$outcome")
                        }
                    }
                }
            } catch (t: Throwable) {
                val err = "DEBUG error action=$action msg=${t.message ?: t.javaClass.simpleName}"
                Log.e(TAG, err, t)
                TickTickVoiceCaptureService.appendDebugLog(appContext, err)
            } finally {
                finishBroadcastOnce()
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
            }
        }
    }

    companion object {
        private const val TAG = "TickTickDebugCtrl"
        private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        const val ACTION_DEBUG_SET_ENGINE = "com.openclaw.assistant.ticktick.ACTION_DEBUG_SET_ENGINE"
        const val ACTION_DEBUG_TRANSCRIBE_WAV = "com.openclaw.assistant.ticktick.ACTION_DEBUG_TRANSCRIBE_WAV"
        private const val EXTRA_ENGINE = "engine"
        private const val EXTRA_WAV_PATH = "wav_path"
        private const val EXTRA_SAVE = "save"
        private const val EXTRA_BACKEND = "backend"
    }
}
