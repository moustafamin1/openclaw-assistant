package com.openclaw.assistant

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.openclaw.assistant.backend.BackendMigration
import com.openclaw.assistant.backend.AgentDiagnostics
import com.openclaw.assistant.bridge.BridgeActivityLog
import com.openclaw.assistant.bridge.WakeLockManager
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.node.NodeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.Security

class OpenClawApplication : Application() {

    @Volatile private var _nodeRuntime: NodeRuntime? = null

    /**
     * Returns the runtime, initializing it if needed.
     * Call this from Activities/ViewModels to ensure the runtime exists.
     */
    fun ensureRuntime(): NodeRuntime {
        _nodeRuntime?.let { return it }
        return synchronized(this) {
            _nodeRuntime ?: NodeRuntime(this).also { _nodeRuntime = it }
        }
    }

    /**
     * Returns the runtime if already initialized, or null if it has not been created yet.
     * Use this in places that must NOT force initialization (e.g. foreground service).
     */
    fun peekRuntime(): NodeRuntime? = _nodeRuntime

    /**
     * Retained for backwards compatibility with existing call sites.
     * Equivalent to [ensureRuntime].
     */
    val nodeRuntime: NodeRuntime get() = ensureRuntime()

    override fun onCreate() {
        super.onCreate()
        applySavedAppLocale()
        // In debug builds, FirebaseInitProvider is removed from the manifest so that fork PRs
        // (which lack a real API key) do not crash on launch. Initialize Firebase manually here
        // when the build flag indicates a real key is present.
        if (BuildConfig.DEBUG && BuildConfig.FIREBASE_ENABLED) {
            FirebaseApp.initializeApp(this)
        }
        // Register Bouncy Castle as highest-priority provider for Ed25519 support
        try {
            val bcProvider = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                .getDeclaredConstructor().newInstance() as java.security.Provider
            Security.removeProvider("BC")
            Security.insertProviderAt(bcProvider, 1)
        } catch (e: Throwable) {
            Log.e("OpenClawApp", "Failed to register Bouncy Castle provider", e)
            if (BuildConfig.FIREBASE_ENABLED) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }

        // One-shot migration of legacy OpenClaw single-backend settings into the
        // multi-backend repository. Safe to call on every cold start: it no-ops
        // once any backend is registered.
        try {
            BackendMigration.runIfNeeded(this)
        } catch (e: Throwable) {
            Log.w("OpenClawApp", "Backend migration skipped: ${e.message}")
        }
        AgentDiagnostics.initialize(this)
        BridgeActivityLog.initialize(this)
        WakeLockManager.initialize(this)
        try {
            val appSettings = com.openclaw.assistant.data.SettingsRepository.getInstance(this)
            if (appSettings.tickTickGlossaryWeeklyEnabled) {
                com.openclaw.assistant.ticktick.GlossarySyncScheduler.ensureScheduled(this)
                com.openclaw.assistant.ticktick.GlossarySyncScheduler.enqueueImmediateCatchUpIfStale(this)
            }
            com.openclaw.assistant.ticktick.ScreenOffVolumeKeyBridge.syncRegistration(this)
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    com.openclaw.assistant.ticktick.ContextIndexManager.getIndex(this@OpenClawApplication)
                }
                runCatching {
                    com.openclaw.assistant.ticktick.OnDeviceLanguageSupport.warmSystemSpeechService(this@OpenClawApplication)
                }
                if (appSettings.tickTickSpeechEngine == com.openclaw.assistant.data.SettingsRepository.TICKTICK_ENGINE_GEMMA4_AUDIO &&
                    com.openclaw.assistant.ticktick.Gemma4ModelManager.isModelReady(this@OpenClawApplication)
                ) {
                    runCatching {
                        com.openclaw.assistant.ticktick.Gemma4AudioTranscriber.prewarm(this@OpenClawApplication)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w("OpenClawApp", "Glossary/volume bridge init skipped: ${e.message}")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Skip UI_HIDDEN (20) & mild BACKGROUND (40) so Gemma 4 stays warm between captures;
        // unload only under genuine system memory pressure (RUNNING_CRITICAL or >= MODERATE).
        @Suppress("DEPRECATION")
        val highMemoryPressure =
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
        if (highMemoryPressure && com.openclaw.assistant.ticktick.Gemma4AudioTranscriber.isLoaded()) {
            Log.i("OpenClawApp", "Unloading Gemma 4 engine on onTrimMemory(level=$level)")
            com.openclaw.assistant.ticktick.Gemma4AudioTranscriber.unloadNow()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (com.openclaw.assistant.ticktick.Gemma4AudioTranscriber.isLoaded()) {
            Log.i("OpenClawApp", "Unloading Gemma 4 engine on onLowMemory")
            com.openclaw.assistant.ticktick.Gemma4AudioTranscriber.unloadNow()
        }
    }

    private fun applySavedAppLocale() {
        val tag = SettingsRepository.getInstance(this).appLanguage.trim()
        val locales = if (tag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
