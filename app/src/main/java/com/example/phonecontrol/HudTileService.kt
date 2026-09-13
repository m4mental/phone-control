package com.example.phonecontrol

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Quick Settings (QS) Tile Service to toggle the Floating Performance HUD with 1 tap.
 */
class HudTileService : TileService() {

    companion object {
        private const val TAG = "HudTileService"

        fun updateTile(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, HudTileService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to requestListeningState: ${e.message}")
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    private fun refreshTileState() {
        val tile = qsTile ?: return
        val active = FloatingHudService.isRunning

        tile.label = "Perf HUD"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (active) "Active (On-Screen)" else "Tap to Show"
        }
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        try {
            tile.icon = Icon.createWithResource(this, R.drawable.ic_launcher_foreground)
        } catch (e: Exception) {}
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        FloatingHudService.toggle(this)
        refreshTileState()
    }
}
