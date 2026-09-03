package com.example.phonecontrol

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlin.concurrent.thread

/**
 * Quick Settings (QS) Tile Service to cycle between operating modes directly from Notification Shade:
 * 1-Tap Cycle: AI Auto -> Balanced -> Power Saver -> Performance -> AI Auto
 */
class ModeControlTileService : TileService() {

    companion object {
        private const val TAG = "ModeControlTile"

        fun updateTile(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, ModeControlTileService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to requestListeningState: ${e.message}")
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val manualStage = prefs.getInt("manual_stage_override", 0)

        // If Test Lab stage override is locked, 1st tap cleanly releases it back to Balanced mode
        if (manualStage != 0) {
            prefs.edit().putInt("manual_stage_override", 0).apply()
            TweakManager.manualStageOverride = 0
            prefs.edit().putString("selected_mode", "rbBalance").apply()

            thread {
                TweakManager.applyGlobalMode("Balance")
                val updateIntent = Intent("com.example.phonecontrol.UPDATE_UI").apply {
                    setPackage(packageName)
                }
                sendBroadcast(updateIntent)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(applicationContext, "🔓 Test Lab Released ➔ ⚡ Balanced Mode", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            refreshTileState("rbBalance")
            return
        }

        val currentMode = prefs.getString("selected_mode", "rbBalance")

        // Cycle: AI Auto -> Balanced -> Power Saver -> Performance -> AI Auto
        val nextMode = when (currentMode) {
            "rbAutomatic" -> "rbBalance"
            "rbBalance" -> "rbPowerSaver"
            "rbPowerSaver" -> "rbPerformance"
            "rbPerformance" -> "rbAutomatic"
            else -> "rbAutomatic"
        }

        prefs.edit().putString("selected_mode", nextMode).apply()

        // Apply Mode in background thread
        thread {
            if (nextMode == "rbAutomatic") {
                startService(Intent(this, AutoTweakService::class.java))
            } else {
                prefs.edit().remove("active_ai_label").apply()
                val displayMode = when (nextMode) {
                    "rbPowerSaver" -> "Power Saver"
                    "rbPerformance" -> "Performance"
                    else -> "Balance"
                }
                TweakManager.applyGlobalMode(displayMode)
            }

            // Broadcast UI update so open activities update
            val updateIntent = Intent("com.example.phonecontrol.UPDATE_UI").apply {
                setPackage(packageName)
            }
            sendBroadcast(updateIntent)

            val toastMsg = when (nextMode) {
                "rbAutomatic" -> "🤖 AI Dynamic Mode Active"
                "rbPowerSaver" -> "🔋 Power Saver Mode (650MHz Eco)"
                "rbPerformance" -> "🚀 Performance Mode (Turbo 2.8GHz)"
                else -> "⚡ Balanced Mode (Fluid 120Hz)"
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(applicationContext, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // Immediately update visual tile
        refreshTileState(nextMode)
    }

    private fun refreshTileState(forcedMode: String? = null) {
        val tile = qsTile ?: return
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val manualStage = prefs.getInt("manual_stage_override", 0)

        // If Test Lab is manually overriding frequencies, show it clearly on the tile!
        if (forcedMode == null && manualStage != 0) {
            val stageName = when (manualStage) {
                13 -> "S1 (480M Floor)"
                12 -> "S1 (550M Deep)"
                11 -> "S1 (650M Ultra)"
                10 -> "S1 (850M Ext)"
                1 -> "S1 (950M Bal)"
                2 -> "S2 Fluid"
                3 -> "S3 Compute"
                4 -> "S4 Turbo"
                else -> "Stage $manualStage"
            }
            tile.label = "Mode: Test Lab"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "$stageName (Tap to Reset)"
            }
            tile.state = Tile.STATE_ACTIVE
            try {
                tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_mode_control)
            } catch (e: Exception) {}
            tile.updateTile()
            return
        }

        val activeMode = forcedMode ?: prefs.getString("selected_mode", "rbBalance")
        val activeAiLabel = prefs.getString("active_ai_label", "AI: Active")

        when (activeMode) {
            "rbAutomatic" -> {
                tile.label = "Mode: AI Auto"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = activeAiLabel ?: "Dynamic 4-Stage EAS"
                }
                tile.state = Tile.STATE_ACTIVE
            }
            "rbBalance" -> {
                tile.label = "Mode: Balanced"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Pure 6-Core Fluid"
                }
                tile.state = Tile.STATE_ACTIVE
            }
            "rbPowerSaver" -> {
                tile.label = "Mode: Power Saver"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "650MHz Ice Eco"
                }
                tile.state = Tile.STATE_ACTIVE
            }
            "rbPerformance" -> {
                tile.label = "Mode: Performance"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Turbo 2.8GHz Unleashed"
                }
                tile.state = Tile.STATE_ACTIVE
            }
            else -> {
                tile.label = "Mode Control"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Tap to Switch"
                }
                tile.state = Tile.STATE_INACTIVE
            }
        }

        try {
            tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_mode_control)
        } catch (e: Exception) {}

        tile.updateTile()
    }
}
