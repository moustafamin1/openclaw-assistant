package com.openclaw.assistant.ticktick

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Attaches a tiny visible TYPE_APPLICATION_OVERLAY indicator pill while off-screen
 * TickTick dictation starts and runs. On Android 15-17, having an active visible
 * overlay satisfies OS background-to-foreground microphone FGS eligibility without
 * triggering Background Activity Launch (BAL) blocks.
 */
object TickTickOverlayAnchor {
    private const val TAG = "TickTickOverlay"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var attachedView: View? = null

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun showAndRun(context: Context, afterAttached: () -> Unit) {
        val appContext = context.applicationContext
        if (!canDrawOverlays(appContext)) {
            afterAttached()
            return
        }
        mainHandler.post {
            runCatching {
                if (attachedView == null) {
                    val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                        ?: run {
                            afterAttached()
                            return@post
                        }
                    val density = appContext.resources.displayMetrics.density
                    val padH = (12 * density).toInt()
                    val padV = (6 * density).toInt()
                    val pill = TextView(appContext).apply {
                        text = "🎙 TickTick"
                        textSize = 12f
                        setTextColor(0xFFFFFFFF.toInt())
                        setPadding(padH, padV, padH, padV)
                        background = GradientDrawable().apply {
                            setColor(0xDD1A1F29.toInt())
                            cornerRadius = 18 * density
                            setStroke((1 * density).toInt(), 0xFF4CAF50.toInt())
                        }
                    }
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT,
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        y = (44 * density).toInt()
                    }
                    wm.addView(pill, params)
                    attachedView = pill
                }
            }.onFailure { err ->
                Log.w(TAG, "Unable to attach overlay anchor: ${err.message}")
            }
            // Give WindowManager one frame (~50ms) to mark the overlay visible before FGS start
            mainHandler.postDelayed({ afterAttached() }, 50L)
        }
    }

    fun hide(context: Context) {
        val appContext = context.applicationContext
        mainHandler.post {
            val view = attachedView ?: return@post
            attachedView = null
            runCatching {
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                wm?.removeViewImmediate(view)
            }.onFailure { err ->
                Log.w(TAG, "Unable to remove overlay anchor: ${err.message}")
            }
        }
    }
}
