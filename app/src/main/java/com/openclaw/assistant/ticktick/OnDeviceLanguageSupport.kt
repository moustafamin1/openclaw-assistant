package com.openclaw.assistant.ticktick

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class OnDeviceLanguageStatus(
    val installedDesired: List<String>,
    val pendingDesired: List<String>,
    val missingSupported: List<String>,
    val unsupportedFamilies: List<String>,
    val allInstalled: List<String>,
    val allSupported: List<String>,
) {
    companion object {
        val EMPTY = OnDeviceLanguageStatus(
            installedDesired = emptyList(),
            pendingDesired = emptyList(),
            missingSupported = emptyList(),
            unsupportedFamilies = emptyList(),
            allInstalled = emptyList(),
            allSupported = emptyList(),
        )
    }
}

/**
 * Inspects installed/supported on-device speech recognition language packs (API 33+)
 * and triggers background pack downloads for English, German, and Arabic when available.
 */
object OnDeviceLanguageSupport {
    private const val TAG = "OnDeviceLangSupport"
    private const val SYSTEM_WARMUP_COOLDOWN_MS = 10 * 60 * 1000L
    private val lastSystemWarmupAtMs = AtomicLong(0L)

    private val TARGET_FAMILIES = listOf(
        "en" to listOf("en-US", "en-GB", "en"),
        "de" to listOf("de-DE", "de-AT", "de-CH", "de"),
        "ar" to listOf("ar-EG", "ar-SA", "ar-001", "ar-XA", "ar-AE", "ar"),
    )

    @Volatile
    var cachedStatus: OnDeviceLanguageStatus? = null
        private set

    fun allowedSwitchTags(primaryLanguage: String): List<String> {
        val cached = cachedStatus
        if (cached != null && cached.installedDesired.isNotEmpty()) {
            return cached.installedDesired
        }
        val normalizedPrimary = primaryLanguage.ifBlank { "en-US" }
        return listOf(normalizedPrimary).distinct()
    }

    /**
     * Resolves a BCP-47 tag guaranteed to match an installed on-device English pack whenever
     * possible, avoiding unsupported locale tags like "en-DE" that force silent Vosk fallback.
     */
    fun resolvePrimaryLanguage(
        configuredLanguage: String,
        installedPacks: List<String> = cachedStatus?.allInstalled.orEmpty(),
        systemTag: String = Locale.getDefault().toLanguageTag(),
    ): String {
        val cleaned = configuredLanguage.trim()
        if (cleaned.isNotEmpty()) {
            if (installedPacks.isEmpty()) return cleaned
            val exact = installedPacks.firstOrNull { it.equals(cleaned, ignoreCase = true) }
            if (exact != null) return exact
            val family = cleaned.substringBefore('-').lowercase(Locale.ROOT)
            val familyMatch = pickBestTag(
                installedPacks,
                family,
                listOf(cleaned, "en-US", "en-GB", "de-DE", "ar-EG", "ar-SA")
                    .filter { it.substringBefore('-').equals(family, ignoreCase = true) },
            )
            if (familyMatch != null) return familyMatch
            return cleaned
        }

        if (installedPacks.isNotEmpty()) {
            // Prefer exact installed English system tag (e.g. en-US / en-GB), never non-installed en-DE.
            if (systemTag.lowercase(Locale.ROOT).startsWith("en")) {
                val exactSys = installedPacks.firstOrNull { it.equals(systemTag, ignoreCase = true) }
                if (exactSys != null) return exactSys
            }
            val bestEn = pickBestTag(installedPacks, "en", listOf("en-US", "en-GB", "en"))
            if (bestEn != null) return bestEn
        }

        return when {
            systemTag.equals("en-GB", ignoreCase = true) -> "en-GB"
            systemTag.equals("en-AU", ignoreCase = true) -> "en-AU"
            else -> "en-US"
        }
    }

    suspend fun warmSystemSpeechService(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        val now = SystemClock.elapsedRealtime()
        val prev = lastSystemWarmupAtMs.get()
        if (prev != 0L && now - prev < SYSTEM_WARMUP_COOLDOWN_MS) return
        lastSystemWarmupAtMs.set(now)
        runCatching { queryAndPrepare(context.applicationContext, triggerDownloads = false) }
    }

    suspend fun queryAndPrepare(
        context: Context,
        triggerDownloads: Boolean = false,
    ): OnDeviceLanguageStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return OnDeviceLanguageStatus.EMPTY
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context) &&
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            return OnDeviceLanguageStatus.EMPTY
        }
        return queryApi33Plus(context.applicationContext, triggerDownloads).also { status ->
            if (status.allInstalled.isNotEmpty() || status.allSupported.isNotEmpty()) {
                cachedStatus = status
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun queryApi33Plus(
        appContext: Context,
        triggerDownloads: Boolean,
    ): OnDeviceLanguageStatus {
        val supportDeferred = CompletableDeferred<RecognitionSupport?>()
        val useSystemSpeech = SpeechRecognizer.isRecognitionAvailable(appContext)
        val recognizer = withContext(Dispatchers.Main) {
            if (useSystemSpeech) {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            } else {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            }
        }

        try {
            val baseIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            withContext(Dispatchers.Main) {
                recognizer.checkRecognitionSupport(
                    baseIntent,
                    ContextCompat.getMainExecutor(appContext),
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            supportDeferred.complete(recognitionSupport)
                        }

                        override fun onError(error: Int) {
                            Log.w(TAG, "checkRecognitionSupport failed: error=$error")
                            supportDeferred.complete(null)
                        }
                    },
                )
            }

            val support = withTimeoutOrNull(2500L) { supportDeferred.await() }
                ?: return OnDeviceLanguageStatus.EMPTY

            val installed = support.installedOnDeviceLanguages
            val pending = support.pendingOnDeviceLanguages
            val supported = support.supportedOnDeviceLanguages

            Log.i(
                TAG,
                "Speech on-device languages (system=$useSystemSpeech): installed=$installed pending=$pending supported=$supported online=${support.onlineLanguages.size}",
            )

            val installedDesired = mutableListOf<String>()
            val pendingDesired = mutableListOf<String>()
            val missingSupported = mutableListOf<String>()
            val unsupportedFamilies = mutableListOf<String>()

            for ((prefix, preferredOrder) in TARGET_FAMILIES) {
                val installedMatch = pickBestTag(installed, prefix, preferredOrder)
                if (installedMatch != null) {
                    installedDesired.add(installedMatch)
                    continue
                }
                val pendingMatch = pickBestTag(pending, prefix, preferredOrder)
                if (pendingMatch != null) {
                    pendingDesired.add(pendingMatch)
                    continue
                }
                val supportedMatch = pickBestTag(supported, prefix, preferredOrder)
                if (supportedMatch != null) {
                    missingSupported.add(supportedMatch)
                } else {
                    unsupportedFamilies.add(prefix)
                }
            }

            if (triggerDownloads && missingSupported.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    for (tag in missingSupported) {
                        triggerDownloadForTag(appContext, recognizer, tag)
                    }
                }
            }

            return OnDeviceLanguageStatus(
                installedDesired = installedDesired,
                pendingDesired = pendingDesired,
                missingSupported = missingSupported,
                unsupportedFamilies = unsupportedFamilies,
                allInstalled = installed,
                allSupported = supported,
            )
        } finally {
            withContext(Dispatchers.Main) {
                runCatching { recognizer.destroy() }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun triggerDownloadForTag(
        appContext: Context,
        recognizer: SpeechRecognizer,
        languageTag: String,
    ) {
        val dlIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        }
        Log.i(TAG, "Triggering on-device model download for $languageTag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                recognizer.triggerModelDownload(
                    dlIntent,
                    ContextCompat.getMainExecutor(appContext),
                    object : ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) {
                            Log.i(TAG, "Model download [$languageTag]: $completedPercent%")
                        }

                        override fun onSuccess() {
                            Log.i(TAG, "Model download [$languageTag] succeeded")
                        }

                        override fun onScheduled() {
                            Log.i(TAG, "Model download [$languageTag] scheduled")
                        }

                        override fun onError(error: Int) {
                            Log.w(TAG, "Model download [$languageTag] error=$error")
                        }
                    },
                )
            }
        } else {
            runCatching { recognizer.triggerModelDownload(dlIntent) }
        }
    }

    internal fun pickBestTag(
        available: List<String>,
        familyPrefix: String,
        preferredOrder: List<String>,
    ): String? {
        if (available.isEmpty()) return null
        val lowerMap = available.associateBy { it.lowercase(Locale.ROOT) }
        for (preferred in preferredOrder) {
            val exact = lowerMap[preferred.lowercase(Locale.ROOT)]
            if (exact != null) return exact
        }
        return available.firstOrNull {
            val lower = it.lowercase(Locale.ROOT)
            lower == familyPrefix || lower.startsWith("$familyPrefix-")
        }
    }
}
