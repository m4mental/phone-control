package com.example.phonecontrol

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class CooldownTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile
        
        if (tile.state == Tile.STATE_INACTIVE) {
            // Activate Cooldown
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Cooldown: 120s"
            tile.updateTile()
            
            ThermalManager.startEmergencyCooldown(this) {
                // Revert Tile
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Emergency Cooldown"
                tile.updateTile()
                Toast.makeText(this, "Cooldown Finished", Toast.LENGTH_SHORT).show()
            }
            
            Toast.makeText(this, "Emergency Cooldown Activated!", Toast.LENGTH_SHORT).show()
        }
    }
}
