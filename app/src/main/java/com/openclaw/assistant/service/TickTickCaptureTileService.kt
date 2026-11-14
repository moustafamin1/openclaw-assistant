package com.openclaw.assistant.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.openclaw.assistant.R

/**
 * Quick Settings tile for one-tap TickTick dictation without needing ADB volume-key grants.
 * Works directly from the lock screen Quick Settings shade (using unlockAndRun on Android 14+
 * when the screen is locked so microphone foreground service permissions are satisfied).
 */
class TickTickCaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        if (TickTickVoiceCaptureService.isCapturing()) {
            TickTickVoiceCaptureService.stopCapture(this, cancel = false)
            refreshTileState(active = false)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && isLocked) {
            unlockAndRun {
                TickTickVoiceCaptureService.startCapture(this)
                refreshTileState(active = true)
            }
        } else {
            TickTickVoiceCaptureService.startCapture(this)
            refreshTileState(active = true)
        }
    }

    private fun refreshTileState(active: Boolean = TickTickVoiceCaptureService.isCapturing()) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(
            if (active) R.string.ticktick_tile_label_recording else R.string.ticktick_tile_label_idle,
        )
        tile.updateTile()
    }
}
