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
 * Quick Settings (QS) Tile Service to switch system-wide Private DNS / Ad-Blocker:
 * 1-Tap Cycle: AdGuard (Ad-Block) -> Cloudflare 1.1.1.1 -> Google DNS -> Off -> AdGuard
 */
class PrivateDnsTileService : TileService() {

    companion object {
        private const val TAG = "PrivateDnsTile"

        fun updateTile(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, PrivateDnsTileService::class.java))
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
        val provider = PrivateDnsManager.getCurrentProvider(this)
        val spec = PrivateDnsManager.getCurrentSpecifier(this)
        val tile = qsTile ?: return

        try {
            tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_private_dns)
        } catch (e: Exception) {}

        when (provider) {
            PrivateDnsManager.DnsProvider.OFF -> {
                tile.label = "DNS: Off"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "ISP Default"
                }
                tile.state = Tile.STATE_INACTIVE
            }
            PrivateDnsManager.DnsProvider.ADGUARD -> {
                tile.label = "DNS: AdGuard"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Ad-Blocker Active"
                }
                tile.state = Tile.STATE_ACTIVE
            }
            PrivateDnsManager.DnsProvider.CLOUDFLARE -> {
                tile.label = "DNS: 1.1.1.1"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Low Ping Gaming"
                }
                tile.state = Tile.STATE_ACTIVE
            }
            PrivateDnsManager.DnsProvider.GOOGLE -> {
                tile.label = "DNS: Google"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Public DNS"
                }
                tile.state = Tile.STATE_ACTIVE
            }
            PrivateDnsManager.DnsProvider.QUAD9 -> {
                tile.label = "DNS: Quad9"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Malware Guard"
                }
                tile.state = Tile.STATE_ACTIVE
            }
            PrivateDnsManager.DnsProvider.MULLVAD -> {
                tile.label = "DNS: Mullvad"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Privacy AdBlock"
                }
                tile.state = Tile.STATE_ACTIVE
            }
            PrivateDnsManager.DnsProvider.CONTROLD -> {
                tile.label = "DNS: ControlD"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Free DNS"
                }
                tile.state = Tile.STATE_ACTIVE
            }
            PrivateDnsManager.DnsProvider.CUSTOM -> {
                tile.label = "DNS: Custom"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = if (spec.isNotBlank()) spec else "Custom DoH"
                }
                tile.state = Tile.STATE_ACTIVE
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        thread {
            val nextProvider = PrivateDnsManager.cycleNext(this)
            Handler(Looper.getMainLooper()).post {
                val message = when (nextProvider) {
                    PrivateDnsManager.DnsProvider.ADGUARD -> "🛡️ AdGuard DNS: Ads & Trackers Blocked System-Wide"
                    PrivateDnsManager.DnsProvider.CLOUDFLARE -> "⚡ Cloudflare 1.1.1.1: Fast Low-Ping Gaming DNS"
                    PrivateDnsManager.DnsProvider.GOOGLE -> "🌐 Google DNS: Fast CDN Resolution Active"
                    PrivateDnsManager.DnsProvider.QUAD9 -> "🛡️ Quad9 DNS: Malware Protection Active"
                    PrivateDnsManager.DnsProvider.MULLVAD -> "🔒 Mullvad DNS: Privacy & AdBlock Active"
                    PrivateDnsManager.DnsProvider.CONTROLD -> "⚡ ControlD DNS: Filter Active"
                    PrivateDnsManager.DnsProvider.CUSTOM -> "🔧 Custom DNS Hostname Active"
                    PrivateDnsManager.DnsProvider.OFF -> "⚪ DNS: Default ISP / Automatic"
                }
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                refreshTileState()
            }
        }
    }
}
