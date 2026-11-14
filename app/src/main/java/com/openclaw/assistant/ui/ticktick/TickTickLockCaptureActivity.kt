package com.openclaw.assistant.ui.ticktick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openclaw.assistant.R
import com.openclaw.assistant.service.TickTickVoiceCaptureService
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal lock-screen-safe capture surface launched when hardware volume-hold triggers
 * while the screen is off on Android 14+. Having a resumed lock-screen activity
 * satisfies OS while-in-use microphone FGS rules without unlocking the device.
 */
class TickTickLockCaptureActivity : ComponentActivity() {

    private var startedCapture = false

    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isFinishing && !isDestroyed) {
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ContextCompat.registerReceiver(
            this,
            finishReceiver,
            IntentFilter(TickTickVoiceCaptureService.ACTION_CAPTURE_FINISHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent {
            OpenClawAssistantTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xE60E1116)),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        tonalElevation = 6.dp,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(44.dp),
                            )
                            Text(
                                text = "TickTick Voice Capture",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Listening on-device… Release volume key to save to Inbox.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        holdStillActive.set(false)
                                        TickTickVoiceCaptureService.stopCapture(this@TickTickLockCaptureActivity, cancel = true)
                                        finishAndRemoveTask()
                                    },
                                ) {
                                    Text("Cancel")
                                }
                                Button(
                                    onClick = {
                                        holdStillActive.set(false)
                                        TickTickVoiceCaptureService.stopCapture(this@TickTickLockCaptureActivity, cancel = false)
                                        finishAndRemoveTask()
                                    },
                                ) {
                                    Text("Save Now")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        cancelNotification(this)
        if (!startedCapture) {
            val requireHold = intent.getBooleanExtra(EXTRA_REQUIRE_ACTIVE_HOLD, true)
            if (requireHold && !holdStillActive.get()) {
                finishAndRemoveTask()
                return
            }
            startedCapture = true
            TickTickVoiceCaptureService.startCapture(this, fromLockscreenActivity = true)
        }
    }

    override fun onDestroy() {
        cancelNotification(this)
        runCatching { unregisterReceiver(finishReceiver) }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REQUIRE_ACTIVE_HOLD = "require_active_hold"
        private const val FSI_CHANNEL_ID = "ticktick_lockscreen_trigger"
        private const val FSI_NOTIFICATION_ID = 2409
        private val holdStillActive = AtomicBoolean(false)

        fun setHoldActive(active: Boolean) {
            holdStillActive.set(active)
        }

        fun isHoldActive(): Boolean = holdStillActive.get()

        fun canUseFullScreenIntent(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            return nm.canUseFullScreenIntent()
        }

        fun launchViaFullScreenIntent(context: Context, requireActiveHold: Boolean = true): Boolean {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val existing = nm.getNotificationChannel(FSI_CHANNEL_ID)
                if (existing == null || existing.importance < NotificationManager.IMPORTANCE_HIGH) {
                    val channel = NotificationChannel(
                        FSI_CHANNEL_ID,
                        "TickTick Lock-Screen Trigger",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        setSound(null, null)
                        enableVibration(false)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
                    nm.createNotificationChannel(channel)
                }
            }

            val intent = Intent(context, TickTickLockCaptureActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
                putExtra(EXTRA_REQUIRE_ACTIVE_HOLD, requireActiveHold)
            }
            val pending = PendingIntent.getActivity(
                context,
                FSI_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, FSI_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("TickTick Voice Capture")
                .setContentText("Starting lock-screen dictation…")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setTimeoutAfter(4_000L)
                .setFullScreenIntent(pending, true)
                .build()

            nm.notify(FSI_NOTIFICATION_ID, notification)
            return true
        }

        fun cancelNotification(context: Context) {
            runCatching {
                context.getSystemService(NotificationManager::class.java)?.cancel(FSI_NOTIFICATION_ID)
            }
        }

        fun launchFromOffScreen(context: Context, requireActiveHold: Boolean = true) {
            val intent = Intent(context, TickTickLockCaptureActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
                putExtra(EXTRA_REQUIRE_ACTIVE_HOLD, requireActiveHold)
            }
            context.startActivity(intent)
        }
    }
}
