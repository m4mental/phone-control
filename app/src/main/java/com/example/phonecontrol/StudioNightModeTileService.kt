package com.example.phonecontrol

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Quick Settings (QS) Tile Service for Phone Control Movie & Night Mode (Dynamics MBC):
 * 1-Tap Toggle: On (Dynamics Multiband Compressor) <-> Off (Bypass).
 * Subtitle displays active compression profile and dialogue boost level.
 * Long-press launches Movie & Night Mode profile picker.
 */
class StudioNightModeTileService : TileService() {

    companion object {
        private const val TAG = "StudioNightModeTile"

        fun updateTile(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, StudioNightModeTileService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to requestListeningState: ${e.message}")
            }
        }

        fun updateTileState(context: Context) = updateTile(context)
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    private fun refreshTileState() {
        thread {
            val isEnabled = PowerampPresetManager.isNightModeEnabled(this)
            val profile = PowerampPresetManager.getNightModeProfile(this)
            val boost = PowerampPresetManager.getDialogueBoostLevel(this)

            val profileName = when (profile) {
                2 -> "Night Max"
                3 -> "Speech"
                else -> "Cinema"
            }

            Handler(Looper.getMainLooper()).post {
                val tile = qsTile ?: return@post
                try {
                    tile.icon = Icon.createWithResource(this@StudioNightModeTileService, R.drawable.ic_qs_night_mode)
                } catch (e: Exception) {}

                if (isEnabled) {
                    tile.label = "Night Mode"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = String.format(Locale.US, "%s (+%.0fdB)", profileName, boost)
                    }
                    tile.state = Tile.STATE_ACTIVE
                } else {
                    tile.label = "Night Mode: Off"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "Tap to Enable"
                    }
                    tile.state = Tile.STATE_INACTIVE
                }
                tile.updateTile()
            }
        }
    }

    override fun onClick() {
        super.onClick()
        thread {
            val currentlyEnabled = PowerampPresetManager.isNightModeEnabled(this)
            val targetState = !currentlyEnabled
            val profile = PowerampPresetManager.getNightModeProfile(this)
            val boost = PowerampPresetManager.getDialogueBoostLevel(this)

            StudioDspManager.ensureInitialized(this)
            StudioDspManager.setNightMode(this, targetState, profile, boost)

            val profileName = when (profile) {
                2 -> "Night Max"
                3 -> "Speech Focus"
                else -> "Cinema Balanced"
            }

            Handler(Looper.getMainLooper()).post {
                val message = if (targetState) {
                    "🎬 Movie & Night Mode: ON ($profileName)"
                } else {
                    "⚪ Movie & Night Mode: OFF"
                }
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                refreshTileState()
            }
        }
    }
}
