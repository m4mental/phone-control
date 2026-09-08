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
import kotlin.concurrent.thread

/**
 * Quick Settings (QS) Tile Service for Phone Control Equalizer Preset Switcher:
 * 1-Tap Cycle: Steps through user and factory presets instantly.
 * Subtitle displays active preset name.
 * Long-press launches the full Studio Preset Picker dialog.
 */
class StudioPresetTileService : TileService() {

    companion object {
        private const val TAG = "StudioPresetTile"

        fun updateTile(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, StudioPresetTileService::class.java))
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
            val isEnabled = PowerampPresetManager.isMasterEnabled(this)
            val currentPresetName = PowerampPresetManager.getActivePresetName(this)

            Handler(Looper.getMainLooper()).post {
                val tile = qsTile ?: return@post
                try {
                    tile.icon = Icon.createWithResource(this@StudioPresetTileService, R.drawable.ic_qs_preset)
                } catch (e: Exception) {}

                tile.label = "EQ Preset"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = if (isEnabled) currentPresetName else "$currentPresetName (DSP Off)"
                }
                tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.updateTile()
            }
        }
    }

    override fun onClick() {
        super.onClick()
        thread {
            val allPresets = PowerampPresetManager.getAllPresets(this)
            if (allPresets.isEmpty()) return@thread

            val currentName = PowerampPresetManager.getActivePresetName(this)
            val currentIndex = allPresets.indexOfFirst { it.name.equals(currentName, ignoreCase = true) }
            val nextIndex = if (currentIndex >= 0) (currentIndex + 1) % allPresets.size else 0
            val nextPreset = allPresets[nextIndex]

            StudioDspManager.ensureInitialized(this)
            StudioDspManager.applyPreset(this, nextPreset)

            // Keep StudioEqualizerTile in sync
            StudioEqualizerTileService.updateTile(this)

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "🎵 Preset: ${nextPreset.name}", Toast.LENGTH_SHORT).show()
                refreshTileState()
            }
        }
    }
}
