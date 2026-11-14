package com.openclaw.assistant.ticktick

import android.view.KeyEvent

enum class VolumeTriggerMode(val prefValue: String) {
    OFF("off"),
    VOLUME_DOWN("volume_down"),
    VOLUME_UP("volume_up"),
    EITHER("either");

    fun matchesKeyCode(keyCode: Int): Boolean = when (this) {
        OFF -> false
        VOLUME_DOWN -> keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        VOLUME_UP -> keyCode == KeyEvent.KEYCODE_VOLUME_UP
        EITHER -> keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
    }

    companion object {
        fun fromPref(value: String?): VolumeTriggerMode =
            entries.firstOrNull { it.prefValue == value } ?: VOLUME_DOWN
    }
}

/**
 * Pure state machine separating short volume taps from long-press voice capture.
 *
 * Short taps (< holdThresholdMs) replay a single volume adjustment so regular
 * volume control keeps working; holding >= holdThresholdMs enters capture until
 * the key is released.
 */
class VolumeCaptureKeyHandler(
    private val scheduleHoldTimeout: (delayMs: Long, action: () -> Unit) -> CancellableTimer,
    private val onReplayVolumeTap: (keyCode: Int) -> Unit,
    private val onStartCapture: (keyCode: Int) -> Unit,
    private val onStopCapture: (cancel: Boolean) -> Unit,
) {
    fun interface CancellableTimer {
        fun cancel()
    }

    enum class State {
        IDLE,
        PENDING_HOLD,
        CAPTURING,
    }

    private var state: State = State.IDLE
    private var activeKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN
    private var holdTimer: CancellableTimer? = null
    private var maxDurationTimer: CancellableTimer? = null

    fun currentState(): State = state

    fun handleKeyEvent(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        isCanceled: Boolean,
        enabled: Boolean,
        triggerMode: VolumeTriggerMode,
        holdThresholdMs: Long,
        isInCallOrRinging: Boolean,
        maxCaptureMs: Long = DEFAULT_MAX_CAPTURE_MS,
    ): Boolean {
        if (!enabled || !triggerMode.matchesKeyCode(keyCode)) {
            if (state != State.IDLE) resetInternal(cancelCapture = true)
            return false
        }

        if (isInCallOrRinging && state == State.IDLE) {
            return false
        }

        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (repeatCount == 0 && state == State.CAPTURING) {
                    // Recover cleanly if OS dropped a prior ACTION_UP (e.g., screen sleep mid-hold)
                    resetInternal(cancelCapture = false)
                } else if (repeatCount == 0 && state == State.PENDING_HOLD) {
                    holdTimer?.cancel()
                    holdTimer = null
                    state = State.IDLE
                    activeKeyCode = KeyEvent.KEYCODE_UNKNOWN
                }
                if (state == State.IDLE && repeatCount == 0) {
                    state = State.PENDING_HOLD
                    activeKeyCode = keyCode
                    holdTimer?.cancel()
                    holdTimer = scheduleHoldTimeout(holdThresholdMs) {
                        if (state == State.PENDING_HOLD && activeKeyCode == keyCode) {
                            state = State.CAPTURING
                            maxDurationTimer?.cancel()
                            maxDurationTimer = scheduleHoldTimeout(maxCaptureMs) {
                                if (state == State.CAPTURING) {
                                    resetInternal(cancelCapture = false)
                                }
                            }
                            onStartCapture(keyCode)
                        }
                    }
                }
                true
            }

            KeyEvent.ACTION_UP -> {
                if (keyCode != activeKeyCode && state != State.IDLE) {
                    return false
                }
                when (state) {
                    State.IDLE -> false
                    State.PENDING_HOLD -> {
                        val tappedKey = activeKeyCode
                        holdTimer?.cancel()
                        holdTimer = null
                        activeKeyCode = KeyEvent.KEYCODE_UNKNOWN
                        state = State.IDLE
                        if (!isCanceled) {
                            onReplayVolumeTap(tappedKey)
                        }
                        true
                    }
                    State.CAPTURING -> {
                        resetInternal(cancelCapture = isCanceled)
                        true
                    }
                }
            }

            else -> state != State.IDLE
        }
    }

    fun reset() {
        resetInternal(cancelCapture = true)
    }

    /** Clears active hold state without firing onStopCapture when capture stopped externally. */
    fun syncExternalStop() {
        holdTimer?.cancel()
        holdTimer = null
        maxDurationTimer?.cancel()
        maxDurationTimer = null
        state = State.IDLE
        activeKeyCode = KeyEvent.KEYCODE_UNKNOWN
    }

    private fun resetInternal(cancelCapture: Boolean) {
        holdTimer?.cancel()
        holdTimer = null
        maxDurationTimer?.cancel()
        maxDurationTimer = null
        val wasCapturing = state == State.CAPTURING
        state = State.IDLE
        activeKeyCode = KeyEvent.KEYCODE_UNKNOWN
        if (wasCapturing) {
            onStopCapture(cancelCapture)
        }
    }

    companion object {
        const val DEFAULT_MAX_CAPTURE_MS = 300_000L
    }
}
