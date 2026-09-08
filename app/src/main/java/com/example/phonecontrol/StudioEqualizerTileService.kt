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
 * Quick Settings (QS) Tile Service for Phone Control Studio Equalizer & DSP Engine:
 * 1-Tap Toggle: On (Active DSP Preset) <-> Off (Bit-perfect Bypass).
 * Subtitle displays current active preset and audio routing target (Speaker, BT, Wired).
 */
class StudioEqualizerTileService : TileService() {

    companion object {
        private const val TAG = "StudioEqTile"

        fun updateTile(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, StudioEqualizerTileService::class.java))
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
            val presetName = PowerampPresetManager.getActivePresetName(this)
            val outputType = StudioDspManager.getCurrentAudioOutputType(this)
            val outputName = when (outputType) {
                PowerampPresetManager.AudioOutputType.BLUETOOTH -> "BT"
                PowerampPresetManager.AudioOutputType.WIRED -> "DAC/Wired"
                PowerampPresetManager.AudioOutputType.SPEAKER -> "Speaker"
            }

            Handler(Looper.getMainLooper()).post {
                val tile = qsTile ?: return@post
                try {
                    tile.icon = Icon.createWithResource(this@StudioEqualizerTileService, R.drawable.ic_qs_equalizer)
                } catch (e: Exception) {}

                if (isEnabled) {
                    tile.label = "Studio EQ"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "$presetName ($outputName)"
                    }
                    tile.state = Tile.STATE_ACTIVE
                } else {
                    tile.label = "Studio EQ: Off"
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
            val currentlyEnabled = PowerampPresetManager.isMasterEnabled(this)
            val targetState = !currentlyEnabled
            StudioDspManager.ensureInitialized(this)
            StudioDspManager.setMasterEnabled(this, targetState)
            val presetName = PowerampPresetManager.getActivePresetName(this)

            Handler(Looper.getMainLooper()).post {
                val message = if (targetState) {
                    "🎛️ Studio EQ: ON ($presetName)"
                } else {
                    "⚪ Studio EQ: OFF (Flat Bypass)"
                }
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                refreshTileState()
            }
        }
    }
}
