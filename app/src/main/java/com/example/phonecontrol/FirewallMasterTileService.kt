package com.example.phonecontrol

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class FirewallMasterTileService : TileService() {

    companion object {
        private const val TAG = "FirewallMasterTile"

        fun updateTile(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, FirewallMasterTileService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to requestListeningState: ${e.message}")
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return

        try {
            tile.icon = Icon.createWithResource(this, R.drawable.ic_sub_firewall)
        } catch (_: Exception) {}

        val blockedApps = FirewallManager.getBlockedPackages(this)
        val isPaused = FirewallManager.isFirewallPaused(this)

        if (blockedApps.isEmpty()) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Data Firewall"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "0 Apps Configured"
            }
        } else if (isPaused) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Data Firewall"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Paused (Net Allowed)"
            }
        } else {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Data Firewall"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "${blockedApps.size} Apps Blocked"
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val blockedApps = FirewallManager.getBlockedPackages(this)
        if (blockedApps.isEmpty()) {
            Toast.makeText(this, "No apps in Data Firewall! Open app to configure.", Toast.LENGTH_SHORT).show()
            try {
                val intent = Intent(this, FirewallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivityAndCollapse(intent)
            } catch (_: Exception) {}
            return
        }

        val wasPaused = FirewallManager.isFirewallPaused(this)
        val willPause = !wasPaused
        FirewallManager.setFirewallPaused(this, willPause)

        val tile = qsTile
        if (tile != null) {
            tile.state = if (willPause) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.label = "Data Firewall"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (willPause) "Paused (Net Allowed)" else "${blockedApps.size} Apps Blocked"
            }
            tile.updateTile()
        }

        if (willPause) {
            Toast.makeText(this, "Data Firewall Paused: Internet restored for all apps", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Data Firewall Active: ${blockedApps.size} apps blocked", Toast.LENGTH_SHORT).show()
        }
    }
}
