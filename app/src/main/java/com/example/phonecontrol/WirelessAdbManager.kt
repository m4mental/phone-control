package com.example.phonecontrol

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Wireless ADB Manager (Port 5555).
 * Enables persistent root-level TCP/IP debugging over Mobile Hotspot and Wi-Fi.
 * Persists across phone reboots so developer testing works seamlessly without wires or Developer Options toggling.
 */
object WirelessAdbManager {

    private const val PREF_KEY = "wireless_adb_enabled"
    const val ADB_PORT = 5555

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean(PREF_KEY, false)
    }

    fun isPortOpen(): Boolean {
        val port = ShellUtils.fastCmdResult("getprop service.adb.tcp.port", 500).trim()
        return port == ADB_PORT.toString()
    }

    /**
     * Enables ADB TCP/IP on port 5555 via root and saves state.
     * Returns the exact connect command to run on computer.
     */
    fun enable(context: Context): String {
        ShellUtils.fastCmd("setprop service.adb.tcp.port $ADB_PORT; stop adbd; start adbd")
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putBoolean(PREF_KEY, true).commit()
        WirelessAdbTileService.updateTile(context)
        return getConnectCommand()
    }

    /**
     * Disables ADB TCP/IP by resetting port to -1 (USB-only).
     */
    fun disable(context: Context): Boolean {
        ShellUtils.fastCmd("setprop service.adb.tcp.port -1; stop adbd; start adbd")
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putBoolean(PREF_KEY, false).commit()
        WirelessAdbTileService.updateTile(context)
        return true
    }

    /**
     * Re-applies port 5555 on device boot if enabled by user.
     */
    fun applyBootPersistence(context: Context) {
        if (isEnabled(context)) {
            ShellUtils.fastCmd("setprop service.adb.tcp.port $ADB_PORT; stop adbd; start adbd")
            WirelessAdbTileService.updateTile(context)
        }
    }

    /**
     * Intelligently detects device IP address, prioritizing Hotspot interfaces (ap0, swlan0, softap0)
     * followed by standard Wi-Fi (wlan0, wlan1).
     */
    fun getDeviceIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return getFallbackIp()
            var hotspotIp: String? = null
            var wifiIp: String? = null

            for (intf in interfaces) {
                val name = intf.name.lowercase()
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (name.contains("ap") || name.contains("swlan") || name.contains("softap") || name.contains("rndis")) {
                            hotspotIp = host
                        } else if (name.contains("wlan")) {
                            wifiIp = host
                        }
                    }
                }
            }
            if (!hotspotIp.isNullOrBlank()) return hotspotIp
            if (!wifiIp.isNullOrBlank()) return wifiIp
        } catch (e: Exception) {}

        return getFallbackIp()
    }

    private fun getFallbackIp(): String {
        val out = ShellUtils.fastCmdResult("ip -4 addr show | grep -oP '(?<=inet\\s)\\d+(\\.\\d+){3}' | grep -v '127.0.0.1' | head -n 1", 1000).trim()
        return if (out.isNotBlank()) out else "127.0.0.1"
    }

    fun getConnectCommand(): String {
        val ip = getDeviceIpAddress()
        return "adb connect $ip:$ADB_PORT"
    }
}
