package com.openclaw.assistant.ticktick

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOffVolumeKeyBridgeTest {

    @Test
    fun startsAndStopsCaptureWhenScreenIsOffAndKeyMatches() {
        val events = mutableListOf<String>()
        val tracker = ScreenOffVolumeLongPressTracker(
            onStartCapture = { events += "start:$it" },
            onStopCapture = { events += "stop:$it" },
        )

        val downHandled = tracker.handleKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            action = KeyEvent.ACTION_DOWN,
            isCanceled = false,
            isInteractive = false,
            enabled = true,
            triggerMode = VolumeTriggerMode.VOLUME_DOWN,
            isInCallOrRinging = false,
        )
        // OS repeats KEY_DOWN while held — must not start twice
        val repeatHandled = tracker.handleKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            action = KeyEvent.ACTION_DOWN,
            isCanceled = false,
            isInteractive = false,
            enabled = true,
            triggerMode = VolumeTriggerMode.VOLUME_DOWN,
            isInCallOrRinging = false,
        )
        val upHandled = tracker.handleKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            action = KeyEvent.ACTION_UP,
            isCanceled = false,
            isInteractive = false,
            enabled = true,
            triggerMode = VolumeTriggerMode.VOLUME_DOWN,
            isInCallOrRinging = false,
        )

        assertTrue(downHandled)
        assertTrue(repeatHandled)
        assertTrue(upHandled)
        assertFalse(tracker.isCapturing())
        assertEquals(listOf("start:${KeyEvent.KEYCODE_VOLUME_DOWN}", "stop:false"), events)
    }

    @Test
    fun ignoresEventsWhileScreenIsInteractiveSoAccessibilityHandlesHoldThreshold() {
        val events = mutableListOf<String>()
        val tracker = ScreenOffVolumeLongPressTracker(
            onStartCapture = { events += "start:$it" },
            onStopCapture = { events += "stop:$it" },
        )

        val handled = tracker.handleKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            action = KeyEvent.ACTION_DOWN,
            isCanceled = false,
            isInteractive = true,
            enabled = true,
            triggerMode = VolumeTriggerMode.VOLUME_DOWN,
            isInCallOrRinging = false,
        )

        assertFalse(handled)
        assertTrue(events.isEmpty())
    }

    @Test
    fun finishesActiveOffScreenCaptureEvenIfScreenWakesBeforeRelease() {
        val events = mutableListOf<String>()
        val tracker = ScreenOffVolumeLongPressTracker(
            onStartCapture = { events += "start:$it" },
            onStopCapture = { events += "stop:$it" },
        )

        tracker.handleKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_UP,
            action = KeyEvent.ACTION_DOWN,
            isCanceled = false,
            isInteractive = false,
            enabled = true,
            triggerMode = VolumeTriggerMode.EITHER,
            isInCallOrRinging = false,
        )
        val releaseWhileAwake = tracker.handleKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_UP,
            action = KeyEvent.ACTION_UP,
            isCanceled = true,
            isInteractive = true,
            enabled = true,
            triggerMode = VolumeTriggerMode.EITHER,
            isInCallOrRinging = false,
        )

        assertTrue(releaseWhileAwake)
        assertFalse(tracker.isCapturing())
        assertEquals(listOf("start:${KeyEvent.KEYCODE_VOLUME_UP}", "stop:true"), events)
    }

    @Test
    fun capturesWhenScreenIsAwakeButKeyguardIsLocked() {
        val events = mutableListOf<String>()
        val tracker = ScreenOffVolumeLongPressTracker(
            onStartCapture = { events += "start:$it" },
            onStopCapture = { events += "stop:$it" },
        )

        val downHandled = tracker.handleKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            action = KeyEvent.ACTION_DOWN,
            isCanceled = false,
            isInteractive = true,
            isDeviceLocked = true,
            enabled = true,
            triggerMode = VolumeTriggerMode.VOLUME_DOWN,
            isInCallOrRinging = false,
        )
        val upHandled = tracker.handleKeyEvent(
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN,
            action = KeyEvent.ACTION_UP,
            isCanceled = false,
            isInteractive = true,
            isDeviceLocked = true,
            enabled = true,
            triggerMode = VolumeTriggerMode.VOLUME_DOWN,
            isInCallOrRinging = false,
        )

        assertTrue(downHandled)
        assertTrue(upHandled)
        assertFalse(tracker.isCapturing())
        assertEquals(listOf("start:${KeyEvent.KEYCODE_VOLUME_DOWN}", "stop:false"), events)
    }
}
