package com.example.phonecontrol

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
abstract class BaseAppFirewallTileService(val slot: Int) : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val appKey = FirewallManager.getAppForSlot(this, slot)

        if (appKey == null) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = "Firewall Slot $slot"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Unassigned"
            }
            try {
                tile.icon = Icon.createWithResource(this, R.drawable.ic_sub_firewall)
            } catch (_: Exception) {}
            tile.updateTile()
            return
        }

        val label = FirewallManager.getAppLabelFormatted(this, appKey)
        val isBlocked = FirewallManager.isAppBlocked(this, appKey)
        val isPaused = FirewallManager.isFirewallPaused(this)

        tile.label = label
        tile.state = if (isBlocked) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                isPaused -> "Paused (Net Allowed)"
                isBlocked -> "Net Blocked"
                else -> "Net Allowed"
            }
        }
        tile.icon = FirewallManager.getAppTileIcon(this, appKey)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val appKey = FirewallManager.getAppForSlot(this, slot)
        if (appKey == null) {
            Toast.makeText(this, "Slot $slot is unassigned! Open Firewall to configure.", Toast.LENGTH_SHORT).show()
            try {
                val intent = Intent(this, FirewallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivityAndCollapse(intent)
            } catch (_: Exception) {}
            return
        }

        val tile = qsTile ?: return
        val label = FirewallManager.getAppLabelFormatted(this, appKey)

        // 1. Instantly toggle preference and trigger background iptables execution
        val newBlocked = FirewallManager.toggleAppBlocked(this, appKey)

        // 2. CRITICAL: Immediately update tile visual state in 0 milliseconds!
        tile.label = label
        tile.state = if (newBlocked) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (newBlocked) "Net Blocked" else "Net Allowed"
        }
        tile.updateTile()

        // 3. Instant user feedback
        if (newBlocked) {
            Toast.makeText(this, "$label: Internet Blocked 🚫", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "$label: Internet Allowed ✅", Toast.LENGTH_SHORT).show()
        }

        // 4. Update master tile
        FirewallManager.updateMasterTile(this)
    }
}

class AppFirewallTile1Service : BaseAppFirewallTileService(1)
class AppFirewallTile2Service : BaseAppFirewallTileService(2)
class AppFirewallTile3Service : BaseAppFirewallTileService(3)
class AppFirewallTile4Service : BaseAppFirewallTileService(4)
class AppFirewallTile5Service : BaseAppFirewallTileService(5)
