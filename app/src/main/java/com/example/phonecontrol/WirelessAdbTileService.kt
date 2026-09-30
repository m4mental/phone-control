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
            val isConfigured = WirelessAdbManager.isEnabled(this)
            val isSuspended = WirelessAdbManager.isSuspended(this)
            val isPortOpen = WirelessAdbManager.isPortOpen(this)
            val isOnline = isConfigured && isPortOpen
            val port = WirelessAdbManager.getPort(this)
            val ip = if (isOnline) WirelessAdbManager.getDeviceIpAddress() else ""

            Handler(Looper.getMainLooper()).post {
                val tile = qsTile ?: return@post
                try {
                    tile.icon = Icon.createWithResource(this@WirelessAdbTileService, R.drawable.ic_qs_wireless_adb)
                } catch (e: Exception) {
                    Log.w(TAG, "Icon load failed: ${e.message}")
                }

                if (isOnline) {
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = "Wireless ADB"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = if (ip.isNotEmpty()) "$ip:$port" else "Port $port Active"
                    }
                } else if (isConfigured && isSuspended) {
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = "Wireless ADB"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "Auto-Sleep (Offline)"
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
            val currentlyConfigured = WirelessAdbManager.isEnabled(this)
            val port = WirelessAdbManager.getPort(this)

            if (currentlyConfigured) {
                WirelessAdbManager.disable(this)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, "🛑 Wireless ADB: Disabled (Port $port Closed)", Toast.LENGTH_SHORT).show()
                    refreshTileState()
                }
            } else {
                val isAutoSleep = WirelessAdbManager.isAutoSleepEnabled(this)
                val isNetActive = WirelessAdbManager.isLocalNetworkActive(this)

                if (isAutoSleep && !isNetActive) {
                    WirelessAdbManager.suspendPort(this)
                    getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("wireless_adb_enabled", true).apply()
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(applicationContext, "🌙 Wireless ADB: Standby (Offline, Port $port)", Toast.LENGTH_LONG).show()
                        refreshTileState()
                    }
                } else {
                    val cmd = WirelessAdbManager.enable(this)
                    val isSuccess = WirelessAdbManager.isPortOpen(this)

                    Handler(Looper.getMainLooper()).post {
                        if (isSuccess) {
                            try {
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("ADB Command", cmd))
                                Toast.makeText(applicationContext, "🚀 Wireless ADB: Port $port Active!\nCopied: $cmd", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(applicationContext, "🚀 Wireless ADB: Port $port Active!", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(applicationContext, "⚠️ Failed to open Port $port (Root required)", Toast.LENGTH_SHORT).show()
                        }
                        refreshTileState()
                    }
                }
            }
        }
    }
}
