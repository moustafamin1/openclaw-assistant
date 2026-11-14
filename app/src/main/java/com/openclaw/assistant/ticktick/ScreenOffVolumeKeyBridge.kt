package com.openclaw.assistant.ticktick

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openclaw.assistant.bridge.accessibility.AgentVoiceAccessibilityService
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.service.TickTickVoiceCaptureService
import com.openclaw.assistant.ui.ticktick.TickTickLockCaptureActivity
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Pure key-event state tracker for Android's MediaSessionManager OnVolumeKeyLongPressListener.
 * Handles only off-screen volume long-presses so on-screen AccessibilityService capture keeps
 * its customizable hold threshold and short-tap volume replay intact.
 */
class ScreenOffVolumeLongPressTracker(
    private val onStartCapture: (keyCode: Int) -> Unit,
    private val onStopCapture: (cancel: Boolean) -> Unit,
) {
    private var capturingKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN

    fun isCapturing(): Boolean = capturingKeyCode != KeyEvent.KEYCODE_UNKNOWN

    fun handleKeyEvent(
        keyCode: Int,
        action: Int,
        isCanceled: Boolean,
        isInteractive: Boolean,
        isDeviceLocked: Boolean = false,
        enabled: Boolean,
        triggerMode: VolumeTriggerMode,
        isInCallOrRinging: Boolean,
    ): Boolean {
        if (!enabled || !triggerMode.matchesKeyCode(keyCode)) {
            if (isCapturing()) reset(cancel = true)
            return false
        }

        // While the screen is awake AND unlocked and we haven't started an off-screen capture,
        // let AgentVoiceAccessibilityService.onKeyEvent handle it with user holdThresholdMs.
        if (isInteractive && !isDeviceLocked && !isCapturing()) {
            return false
        }

        if (isInCallOrRinging && !isCapturing()) {
            return false
        }

        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (!isCapturing()) {
                    capturingKeyCode = keyCode
                    onStartCapture(keyCode)
                }
                true
            }

            KeyEvent.ACTION_UP -> {
                if (!isCapturing() || keyCode != capturingKeyCode) {
                    false
                } else {
                    reset(cancel = isCanceled)
                    true
                }
            }

            else -> isCapturing()
        }
    }

    fun reset(cancel: Boolean = true) {
        if (capturingKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            capturingKeyCode = KeyEvent.KEYCODE_UNKNOWN
            onStopCapture(cancel)
        }
    }
}

/**
 * Registers MediaSessionManager.setOnVolumeKeyLongPressListener (via hidden-API reflection)
 * when android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER has been granted via ADB/Shizuku.
 * This is required on Pixel/AOSP Android 14+ because PhoneWindowManager does not forward
 * hardware volume keys to AccessibilityService.onKeyEvent while the screen is off.
 */
object ScreenOffVolumeKeyBridge {
    private const val TAG = "ScreenOffVolumeBridge"
    private const val MAX_CAPTURE_MS = VolumeCaptureKeyHandler.DEFAULT_MAX_CAPTURE_MS
    const val PERMISSION_SET_VOLUME_KEY_LONG_PRESS_LISTENER =
        "android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER"

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var maxDurationRunnable: Runnable? = null

    @Volatile private var registered = false
    @Volatile private var registeredAppContext: Context? = null
    @Volatile private var proxyListener: Any? = null
    @Volatile private var tracker: ScreenOffVolumeLongPressTracker? = null

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context.applicationContext,
            PERMISSION_SET_VOLUME_KEY_LONG_PRESS_LISTENER,
        ) == PackageManager.PERMISSION_GRANTED

    fun adbGrantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} $PERMISSION_SET_VOLUME_KEY_LONG_PRESS_LISTENER"

    fun shizukuGrantCommand(context: Context): String =
        "pm grant ${context.packageName} $PERMISSION_SET_VOLUME_KEY_LONG_PRESS_LISTENER"

    @SuppressLint("PrivateApi")
    fun syncRegistration(context: Context): Boolean {
        val appContext = context.applicationContext
        val settings = SettingsRepository.getInstance(appContext)
        if (!settings.tickTickCaptureEnabled || !hasPermission(appContext)) {
            unregister()
            return false
        }

        synchronized(lock) {
            if (registered && registeredAppContext === appContext) {
                return true
            }
            return runCatching {
                val manager = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                    ?: return@runCatching false
                val listenerClass = Class.forName(
                    "android.media.session.MediaSessionManager\$OnVolumeKeyLongPressListener",
                )
                val activeTracker = ScreenOffVolumeLongPressTracker(
                    onStartCapture = {
                        TickTickLockCaptureActivity.setHoldActive(true)
                        scheduleMaxDurationStop(appContext)
                        startOffScreenCapture(appContext)
                    },
                    onStopCapture = { cancel ->
                        cancelMaxDurationStop()
                        Log.i(TAG, "Screen-off volume capture stop (cancel=$cancel)")
                        TickTickLockCaptureActivity.setHoldActive(false)
                        TickTickVoiceCaptureService.stopCapture(appContext, cancel)
                        TickTickOverlayAnchor.hide(appContext)
                        TickTickLockCaptureActivity.cancelNotification(appContext)
                    },
                )
                val proxy = Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass),
                ) { _, method, args ->
                    if (method.name == "onVolumeKeyLongPress") {
                        val event = args?.firstOrNull() as? KeyEvent
                        if (event != null) {
                            handleIncomingEvent(appContext, activeTracker, event)
                        }
                    }
                    null
                }
                invokeSetListener(manager, listenerClass, proxy)
                tracker = activeTracker
                proxyListener = proxy
                registeredAppContext = appContext
                registered = true
                Log.i(TAG, "Registered screen-off MediaSessionManager volume long-press listener")
                true
            }.getOrElse { err ->
                Log.w(TAG, "Unable to register screen-off volume listener: ${err.message}")
                false
            }
        }
    }

    @SuppressLint("PrivateApi")
    fun unregister() {
        synchronized(lock) {
            cancelMaxDurationStop()
            tracker?.reset(cancel = true)
            tracker = null
            val appContext = registeredAppContext
            if (registered && appContext != null) {
                runCatching {
                    val manager = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                    val listenerClass = Class.forName(
                        "android.media.session.MediaSessionManager\$OnVolumeKeyLongPressListener",
                    )
                    if (manager != null) {
                        invokeSetListener(manager, listenerClass, null)
                    }
                }
            }
            proxyListener = null
            registeredAppContext = null
            registered = false
        }
    }

    fun isRegistered(): Boolean = registered

    fun isCapturing(): Boolean = tracker?.isCapturing() == true

    fun stopActiveCapture(cancel: Boolean = false) {
        cancelMaxDurationStop()
        val activeTracker = tracker
        if (activeTracker?.isCapturing() == true) {
            activeTracker.reset(cancel = cancel)
            return
        }
        val appContext = registeredAppContext ?: return
        if (TickTickVoiceCaptureService.isCapturing()) {
            TickTickLockCaptureActivity.setHoldActive(false)
            TickTickVoiceCaptureService.stopCapture(appContext, cancel)
        }
    }

    private fun scheduleMaxDurationStop(appContext: Context) {
        cancelMaxDurationStop()
        val runnable = Runnable {
            Log.i(TAG, "Max capture duration (${MAX_CAPTURE_MS}ms) reached; stopping capture")
            val activeTracker = tracker
            if (activeTracker?.isCapturing() == true) {
                activeTracker.reset(cancel = false)
            } else if (TickTickVoiceCaptureService.isCapturing()) {
                TickTickVoiceCaptureService.stopCapture(appContext, cancel = false)
            }
        }
        maxDurationRunnable = runnable
        mainHandler.postDelayed(runnable, MAX_CAPTURE_MS)
    }

    private fun cancelMaxDurationStop() {
        maxDurationRunnable?.let { mainHandler.removeCallbacks(it) }
        maxDurationRunnable = null
    }

    private fun handleIncomingEvent(
        appContext: Context,
        activeTracker: ScreenOffVolumeLongPressTracker,
        event: KeyEvent,
    ) {
        val settings = SettingsRepository.getInstance(appContext)
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isInteractive = pm?.isInteractive ?: true
        val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isDeviceLocked = km?.isKeyguardLocked == true
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val mode = am?.mode ?: AudioManager.MODE_NORMAL
        val inCall = mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION

        val triggerMode = VolumeTriggerMode.fromPref(settings.tickTickVolumeTrigger)
        val handled = activeTracker.handleKeyEvent(
            keyCode = event.keyCode,
            action = event.action,
            isCanceled = (event.flags and KeyEvent.FLAG_CANCELED) != 0,
            isInteractive = isInteractive,
            isDeviceLocked = isDeviceLocked,
            enabled = settings.tickTickCaptureEnabled,
            triggerMode = triggerMode,
            isInCallOrRinging = inCall,
        )
        if (!handled) {
            if (event.action == KeyEvent.ACTION_UP &&
                triggerMode.matchesKeyCode(event.keyCode) &&
                TickTickVoiceCaptureService.isCapturing()
            ) {
                Log.i(TAG, "Stopping active capture on off-screen ACTION_UP handoff")
                TickTickVoiceCaptureService.stopCapture(appContext, cancel = false)
                return
            }
        }
    }

    private fun startOffScreenCapture(appContext: Context) {
        val a11yService = AgentVoiceAccessibilityService.get()
        if (a11yService != null) {
            // Bound AccessibilityService holds SYSTEM_ALLOW_LISTED WIU permission on Android 14+;
            // start capture headlessly with zero lockscreen popup or screen wake.
            Log.i(TAG, "Screen-off capture started directly via AccessibilityService allowance")
            TickTickVoiceCaptureService.startCapture(appContext)
            return
        }

        when {
            TickTickOverlayAnchor.canDrawOverlays(appContext) -> {
                Log.i(TAG, "Screen-off capture started via overlay anchor")
                wakeScreenBriefly(appContext)
                TickTickOverlayAnchor.showAndRun(appContext) {
                    if (TickTickLockCaptureActivity.isHoldActive()) {
                        TickTickVoiceCaptureService.startCapture(appContext)
                    } else {
                        TickTickOverlayAnchor.hide(appContext)
                    }
                }
            }
            canPostFullScreenNotification(appContext) -> {
                Log.i(TAG, "Screen-off capture started via full-screen intent notification")
                TickTickLockCaptureActivity.launchViaFullScreenIntent(appContext, requireActiveHold = true)
            }
            else -> {
                Log.i(TAG, "Screen-off capture fallback to direct service start")
                wakeScreenBriefly(appContext)
                TickTickVoiceCaptureService.startCapture(appContext)
            }
        }
    }

    private fun canPostFullScreenNotification(context: Context): Boolean {
        if (!TickTickLockCaptureActivity.canUseFullScreenIntent(context)) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun invokeSetListener(
        manager: MediaSessionManager,
        listenerClass: Class<*>,
        listener: Any?,
    ) {
        val withHandler: Method? = runCatching {
            MediaSessionManager::class.java.getMethod(
                "setOnVolumeKeyLongPressListener",
                listenerClass,
                Handler::class.java,
            )
        }.getOrNull()
        if (withHandler != null) {
            withHandler.invoke(manager, listener, if (listener != null) mainHandler else null)
            return
        }
        val singleArg = MediaSessionManager::class.java.getMethod(
            "setOnVolumeKeyLongPressListener",
            listenerClass,
        )
        singleArg.invoke(manager, listener)
    }

    @Suppress("DEPRECATION")
    private fun wakeScreenBriefly(context: Context) {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "WakeHermesClaw::VolumeKeyTrigger",
            )
            wl.setReferenceCounted(false)
            wl.acquire(5_000L)
        }
    }
}
