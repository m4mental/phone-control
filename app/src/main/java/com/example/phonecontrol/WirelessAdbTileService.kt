package com.example.phonecontrol

import android.content.ClipData
import android.content.ClipboardManager
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
 * Quick Settings (QS) Tile Service for Wireless ADB:
 * 1-Tap Toggle: On (Port 5555 + auto-copies connect command) <-> Off (Port -1 / USB only).
 * Subtitle displays active Hotspot / Wi-Fi IP and Port 5555.
 */
class WirelessAdbTileService : TileService() {

    companion object {
        private const val TAG = "WirelessAdbTile"

        fun updateTile(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, WirelessAdbTileService::class.java))
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
        thread {
            val isEnabled = WirelessAdbManager.isEnabled(this) && WirelessAdbManager.isPortOpen()
            val ip = if (isEnabled) WirelessAdbManager.getDeviceIpAddress() else ""

            Handler(Looper.getMainLooper()).post {
                val tile = qsTile ?: return@post
                try {
                    tile.icon = Icon.createWithResource(this@WirelessAdbTileService, R.drawable.ic_qs_wireless_adb)
                } catch (e: Exception) {
                    Log.w(TAG, "Icon load failed: ${e.message}")
                }

                if (isEnabled) {
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = "Wireless ADB"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = if (ip.isNotEmpty()) "$ip:5555" else "Port 5555 Active"
                    }
                } else {
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = "Wireless ADB"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "Off"
                    }
                }
                tile.updateTile()
            }
        }
    }

    override fun onClick() {
        super.onClick()
        thread {
            val currentlyEnabled = WirelessAdbManager.isEnabled(this) && WirelessAdbManager.isPortOpen()

            if (currentlyEnabled) {
                WirelessAdbManager.disable(this)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, "🛑 Wireless ADB: Disabled (Port 5555 Closed)", Toast.LENGTH_SHORT).show()
                    refreshTileState()
                }
            } else {
                val cmd = WirelessAdbManager.enable(this)
                val isSuccess = WirelessAdbManager.isPortOpen()

                Handler(Looper.getMainLooper()).post {
                    if (isSuccess) {
                        try {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("ADB Command", cmd))
                            Toast.makeText(applicationContext, "🚀 Wireless ADB: Port 5555 Active!\nCopied: $cmd", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(applicationContext, "🚀 Wireless ADB: Port 5555 Active!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(applicationContext, "⚠️ Failed to open Port 5555 (Root required)", Toast.LENGTH_SHORT).show()
                    }
                    refreshTileState()
                }
            }
        }
    }
}
