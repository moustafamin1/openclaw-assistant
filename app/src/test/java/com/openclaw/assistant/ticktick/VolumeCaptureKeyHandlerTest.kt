package com.openclaw.assistant.ticktick

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeCaptureKeyHandlerTest {

    private class FakeTimer(val onFire: () -> Unit) : VolumeCaptureKeyHandler.CancellableTimer {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
        fun fire() {
            if (!cancelled) onFire()
        }
    }

    @Test
    fun shortTapReplaysVolumeAndDoesNotCapture() {
        val timers = mutableListOf<Pair<Long, FakeTimer>>()
        val replayed = mutableListOf<Int>()
        var starts = 0
        var stops = 0

        val handler = VolumeCaptureKeyHandler(
            scheduleHoldTimeout = { delay, action ->
                FakeTimer(action).also { timers += delay to it }
            },
            onReplayVolumeTap = { replayed += it },
            onStartCapture = { starts++ },
            onStopCapture = { stops++ },
        )

        val downHandled = handler.handleKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            action = KeyEvent.ACTION_DOWN,
            repeatCount = 0,
            isCanceled = false,
            enabled = true,
            triggerMode = VolumeTriggerMode.VOLUME_DOWN,
            holdThresholdMs = 400L,
            isInCallOrRinging = false,
        )
        assertTrue(downHandled)
        assertEquals(VolumeCaptureKeyHandler.State.PENDING_HOLD, handler.currentState())

        val upHandled = handler.handleKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            action = KeyEvent.ACTION_UP,
            repeatCount = 0,
            isCanceled = false,
            enabled = true,
            triggerMode = VolumeTriggerMode.VOLUME_DOWN,
            holdThresholdMs = 400L,
            isInCallOrRinging = false,
        )
        assertTrue(upHandled)
        assertTrue(timers.single().second.cancelled)
        assertEquals(listOf(KeyEvent.KEYCODE_VOLUME_DOWN), replayed)
        assertEquals(0, starts)
        assertEquals(0, stops)
        assertEquals(VolumeCaptureKeyHandler.State.IDLE, handler.currentState())
    }

    @Test
    fun longHoldStartsAndStopsCaptureOnRelease() {
        val timers = mutableListOf<Pair<Long, FakeTimer>>()
        val replayed = mutableListOf<Int>()
        val startedKeys = mutableListOf<Int>()
        val stopCancels = mutableListOf<Boolean>()

        val handler = VolumeCaptureKeyHandler(
            scheduleHoldTimeout = { delay, action ->
                FakeTimer(action).also { timers += delay to it }
            },
            onReplayVolumeTap = { replayed += it },
            onStartCapture = { startedKeys += it },
            onStopCapture = { stopCancels += it },
        )

        assertTrue(
            handler.handleKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isCanceled = false,
                enabled = true,
                triggerMode = VolumeTriggerMode.EITHER,
                holdThresholdMs = 400L,
                isInCallOrRinging = false,
            ),
        )

        // Fire hold threshold timer
        timers[0].second.fire()
        assertEquals(VolumeCaptureKeyHandler.State.CAPTURING, handler.currentState())
        assertEquals(listOf(KeyEvent.KEYCODE_VOLUME_UP), startedKeys)

        // Repeat ACTION_DOWN is consumed silently
        assertTrue(
            handler.handleKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 3,
                isCanceled = false,
                enabled = true,
                triggerMode = VolumeTriggerMode.EITHER,
                holdThresholdMs = 400L,
                isInCallOrRinging = false,
            ),
        )

        // Release finishes capture without cancel
        assertTrue(
            handler.handleKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_UP,
                repeatCount = 0,
                isCanceled = false,
                enabled = true,
                triggerMode = VolumeTriggerMode.EITHER,
                holdThresholdMs = 400L,
                isInCallOrRinging = false,
            ),
        )
        assertTrue(replayed.isEmpty())
        assertEquals(listOf(false), stopCancels)
        assertEquals(VolumeCaptureKeyHandler.State.IDLE, handler.currentState())
    }

    @Test
    fun passesThroughWhenDisabledWrongKeyOrInCall() {
        val handler = VolumeCaptureKeyHandler(
            scheduleHoldTimeout = { _, _ -> VolumeCaptureKeyHandler.CancellableTimer {} },
            onReplayVolumeTap = {},
            onStartCapture = {},
            onStopCapture = {},
        )

        assertFalse(
            handler.handleKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isCanceled = false,
                enabled = false,
                triggerMode = VolumeTriggerMode.VOLUME_DOWN,
                holdThresholdMs = 400L,
                isInCallOrRinging = false,
            ),
        )
        assertFalse(
            handler.handleKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isCanceled = false,
                enabled = true,
                triggerMode = VolumeTriggerMode.VOLUME_DOWN,
                holdThresholdMs = 400L,
                isInCallOrRinging = false,
            ),
        )
        assertFalse(
            handler.handleKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isCanceled = false,
                enabled = true,
                triggerMode = VolumeTriggerMode.VOLUME_DOWN,
                holdThresholdMs = 400L,
                isInCallOrRinging = true,
            ),
        )
    }

    @Test
    fun canceledKeyUpDuringCapturePassesCancelFlag() {
        val timers = mutableListOf<FakeTimer>()
        val stopCancels = mutableListOf<Boolean>()
        val handler = VolumeCaptureKeyHandler(
            scheduleHoldTimeout = { _, action -> FakeTimer(action).also { timers += it } },
            onReplayVolumeTap = {},
            onStartCapture = {},
            onStopCapture = { stopCancels += it },
        )

        handler.handleKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            action = KeyEvent.ACTION_DOWN,
            repeatCount = 0,
            isCanceled = false,
            enabled = true,
            triggerMode = VolumeTriggerMode.VOLUME_DOWN,
            holdThresholdMs = 300L,
            isInCallOrRinging = false,
        )
        timers[0].fire()

        handler.handleKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            action = KeyEvent.ACTION_UP,
            repeatCount = 0,
            isCanceled = true,
            enabled = true,
            triggerMode = VolumeTriggerMode.VOLUME_DOWN,
            holdThresholdMs = 300L,
            isInCallOrRinging = false,
        )
        assertEquals(listOf(true), stopCancels)
    }

    @Test
    fun defaultMaxCaptureAllowsFiveMinutesBeforeAutoStopping() {
        val scheduledDelays = mutableListOf<Long>()
        val timers = mutableListOf<FakeTimer>()
        val stopCancels = mutableListOf<Boolean>()
        val handler = VolumeCaptureKeyHandler(
            scheduleHoldTimeout = { delay, action ->
                scheduledDelays += delay
                FakeTimer(action).also { timers += it }
            },
            onReplayVolumeTap = {},
            onStartCapture = {},
            onStopCapture = { stopCancels += it },
        )

        handler.handleKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            action = KeyEvent.ACTION_DOWN,
            repeatCount = 0,
            isCanceled = false,
            enabled = true,
            triggerMode = VolumeTriggerMode.VOLUME_DOWN,
            holdThresholdMs = 400L,
            isInCallOrRinging = false,
        )
        // Hold threshold fires -> max duration timer scheduled for 5 minutes (300_000L)
        timers[0].fire()
        assertEquals(listOf(400L, 300_000L), scheduledDelays)
        assertEquals(VolumeCaptureKeyHandler.State.CAPTURING, handler.currentState())

        // Auto-stop at 5 minutes saves (cancel = false)
        timers[1].fire()
        assertEquals(listOf(false), stopCancels)
        assertEquals(VolumeCaptureKeyHandler.State.IDLE, handler.currentState())
    }

    @Test
    fun freshActionDownRecoversFromStuckCapturingStateWithoutLosingCapture() {
        val timers = mutableListOf<FakeTimer>()
        val startedKeys = mutableListOf<Int>()
        val stopCancels = mutableListOf<Boolean>()
        val handler = VolumeCaptureKeyHandler(
            scheduleHoldTimeout = { _, action -> FakeTimer(action).also { timers += it } },
            onReplayVolumeTap = {},
            onStartCapture = { startedKeys += it },
            onStopCapture = { stopCancels += it },
        )

        handler.handleKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            action = KeyEvent.ACTION_DOWN,
            repeatCount = 0,
            isCanceled = false,
            enabled = true,
            triggerMode = VolumeTriggerMode.VOLUME_DOWN,
            holdThresholdMs = 350L,
            isInCallOrRinging = false,
        )
        timers[0].fire()
        assertEquals(VolumeCaptureKeyHandler.State.CAPTURING, handler.currentState())

        // OS dropped ACTION_UP when screen slept; a fresh ACTION_DOWN saves prior capture and starts new pending hold
        handler.handleKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            action = KeyEvent.ACTION_DOWN,
            repeatCount = 0,
            isCanceled = false,
            enabled = true,
            triggerMode = VolumeTriggerMode.VOLUME_DOWN,
            holdThresholdMs = 350L,
            isInCallOrRinging = false,
        )
        assertEquals(listOf(false), stopCancels)
        assertEquals(VolumeCaptureKeyHandler.State.PENDING_HOLD, handler.currentState())

        timers[2].fire()
        assertEquals(VolumeCaptureKeyHandler.State.CAPTURING, handler.currentState())
        assertEquals(2, startedKeys.size)
    }

    @Test
    fun syncExternalStopClearsCapturingWithoutFiringOnStopCapture() {
        val timers = mutableListOf<FakeTimer>()
        val stopCancels = mutableListOf<Boolean>()
        val handler = VolumeCaptureKeyHandler(
            scheduleHoldTimeout = { _, action -> FakeTimer(action).also { timers += it } },
            onReplayVolumeTap = {},
            onStartCapture = {},
            onStopCapture = { stopCancels += it },
        )

        handler.handleKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            action = KeyEvent.ACTION_DOWN,
            repeatCount = 0,
            isCanceled = false,
            enabled = true,
            triggerMode = VolumeTriggerMode.VOLUME_DOWN,
            holdThresholdMs = 350L,
            isInCallOrRinging = false,
        )
        timers[0].fire()
        assertEquals(VolumeCaptureKeyHandler.State.CAPTURING, handler.currentState())

        handler.syncExternalStop()
        assertEquals(VolumeCaptureKeyHandler.State.IDLE, handler.currentState())
        assertEquals(emptyList<Boolean>(), stopCancels)
    }

    @Test
    fun nonZeroRepeatCountWhileIdleStillStartsHoldCapture() {
        val timers = mutableListOf<FakeTimer>()
        val startedKeys = mutableListOf<Int>()
        val handler = VolumeCaptureKeyHandler(
            scheduleHoldTimeout = { _, action -> FakeTimer(action).also { timers += it } },
            onReplayVolumeTap = {},
            onStartCapture = { startedKeys += it },
            onStopCapture = {},
        )

        assertTrue(
            handler.handleKeyEvent(
                keyCode = KeyEvent.KEYCODE_VOLUME_UP,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 1,
                isCanceled = false,
                enabled = true,
                triggerMode = VolumeTriggerMode.VOLUME_UP,
                holdThresholdMs = 400L,
                isInCallOrRinging = false,
            ),
        )
        assertEquals(VolumeCaptureKeyHandler.State.PENDING_HOLD, handler.currentState())
        timers[0].fire()
        assertEquals(VolumeCaptureKeyHandler.State.CAPTURING, handler.currentState())
        assertEquals(listOf(KeyEvent.KEYCODE_VOLUME_UP), startedKeys)
    }
}
