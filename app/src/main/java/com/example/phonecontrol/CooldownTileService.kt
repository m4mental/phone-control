package com.example.phonecontrol

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import kotlin.concurrent.thread

@RequiresApi(Build.VERSION_CODES.N)
class CooldownTileService : TileService() {

    companion object {
        private const val TAG = "CooldownTile"

        fun updateTile(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, CooldownTileService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to requestListeningState: ${e.message}")
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return

        if (ShellUtils.isRootGrantedCached == false) {
            showNoRootTile()
            return
        }

        tile.state = Tile.STATE_INACTIVE
        tile.label = "Emergency Cooldown"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = "Tap to Cool (120s)"
        }
        tile.updateTile()

        thread {
            val hasRoot = ShellUtils.checkRootStandalone(1500, forceCheck = true)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!hasRoot) {
                    showNoRootTile()
                } else if (qsTile?.state == Tile.STATE_UNAVAILABLE) {
                    val t = qsTile ?: return@post
                    t.state = Tile.STATE_INACTIVE
                    t.label = "Emergency Cooldown"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        t.subtitle = "Tap to Cool (120s)"
                    }
                    t.updateTile()
                }
            }
        }
    }

    private fun showNoRootTile() {
        val tile = qsTile ?: return
        tile.label = "Cooldown: No Root"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = "Root Required (OTA)"
        }
        tile.state = Tile.STATE_UNAVAILABLE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return

        if (ShellUtils.isRootGrantedCached == false) {
            showNoRootTile()
            Toast.makeText(this, "⚠️ Root Access Missing! Please re-root after OTA update.", Toast.LENGTH_LONG).show()
            return
        }

        if (tile.state == Tile.STATE_INACTIVE) {
            // Activate Cooldown
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Cooldown: 120s"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Eco Throttle Active"
            }
            tile.updateTile()

            ThermalManager.startEmergencyCooldown(this) {
                // Revert Tile
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Emergency Cooldown"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Tap to Cool (120s)"
                }
                tile.updateTile()
                Toast.makeText(this, "Cooldown Finished", Toast.LENGTH_SHORT).show()
            }

            Toast.makeText(this, "Emergency Cooldown Activated!", Toast.LENGTH_SHORT).show()
        }
    }
}
