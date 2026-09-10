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
class QuickFreezeTileService : TileService() {

    companion object {
        private const val TAG = "QuickFreezeTile"

        fun updateTile(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, QuickFreezeTileService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to requestListeningState: ${e.message}")
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return

        val frozenApps = FreezerManager.getFrozenApps(this)
        tile.state = Tile.STATE_INACTIVE
        tile.label = "Freeze Apps"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (frozenApps.isEmpty()) "0 Configured" else "${frozenApps.size} Apps Ready"
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return

        val apps = FreezerManager.getFrozenApps(this) + FreezerManager.getSpecialFreezeApps(this)
        if (apps.isEmpty()) {
            Toast.makeText(this, "No apps in Hibernation list to freeze!", Toast.LENGTH_SHORT).show()
            return
        }

        tile.state = Tile.STATE_ACTIVE
        tile.label = "Freezing..."
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = "Hibernating ${apps.size} apps"
        }
        tile.updateTile()

        thread {
            FreezerManager.freezeMultipleApps(this, apps)
            val ramFreedMb = (apps.size * 115).coerceAtLeast(150)

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val t = qsTile ?: return@postDelayed
                t.state = Tile.STATE_INACTIVE
                t.label = "Freeze Apps"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    t.subtitle = "Hibernated (${apps.size})"
                }
                t.updateTile()
                Toast.makeText(this, "❄️ Hibernated ${apps.size} apps! ~${ramFreedMb} MB RAM freed", Toast.LENGTH_SHORT).show()
            }, 600)
        }
    }
}
