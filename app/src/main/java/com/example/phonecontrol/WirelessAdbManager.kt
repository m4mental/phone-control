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
    private const val PREF_AUTO_SLEEP = "wireless_adb_auto_sleep"
    private const val PREF_SUSPENDED = "wireless_adb_suspended"
    private const val PREF_PORT = "wireless_adb_port"
    const val DEFAULT_ADB_PORT = 5555

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean(PREF_KEY, false)
    }

    fun isAutoSleepEnabled(context: Context): Boolean {
        return context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean(PREF_AUTO_SLEEP, false)
    }

    fun setAutoSleepEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putBoolean(PREF_AUTO_SLEEP, enabled).apply()
    }

    fun isSuspended(context: Context): Boolean {
        return context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean(PREF_SUSPENDED, false)
    }

    fun getPort(context: Context): Int {
        return context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getInt(PREF_PORT, DEFAULT_ADB_PORT)
    }

    @Synchronized
    fun setPort(context: Context, port: Int): Boolean {
        if (port !in 1024..65535) return false
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putInt(PREF_PORT, port)
            .apply()

        // If currently running and not suspended, re-apply port to adbd immediately
        if (isEnabled(context) && !isSuspended(context)) {
            ShellUtils.fastCmd("setprop service.adb.tcp.port $port; stop adbd; start adbd")
        }
        WirelessAdbTileService.updateTile(context)
        return true
    }

    fun getSystemProperty(key: String, defValue: String = ""): String {
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java, String::class.java)
            val result = getMethod.invoke(null, key, defValue) as? String
            if (!result.isNullOrBlank()) return result.trim()
        } catch (e: Exception) {}

        try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            val out = process.inputStream.bufferedReader().use { it.readText().trim() }
            if (out.isNotBlank()) return out
        } catch (e: Exception) {}

        return ShellUtils.fastCmdResult("getprop $key", 1000).trim().takeIf { !it.startsWith("ERROR") } ?: defValue
    }

    fun isPortOpen(context: Context? = null): Boolean {
        val currentPort = getSystemProperty("service.adb.tcp.port").trim()
        val expectedPort = if (context != null) getPort(context).toString() else (currentPort.takeIf { it != "-1" && it.isNotEmpty() } ?: DEFAULT_ADB_PORT.toString())
        return currentPort == expectedPort && currentPort != "-1" && currentPort.isNotEmpty()
    }

    /**
     * Checks if any local networking interface (Wi-Fi, Mobile Hotspot, USB Tethering, Ethernet) is actively up with a valid IPv4 address.
     */
    fun isLocalNetworkActive(context: Context): Boolean {
        // 1. Check active Wi-Fi or Ethernet connection via ConnectivityManager
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val active = cm?.activeNetwork
            if (active != null) {
                val caps = cm.getNetworkCapabilities(active)
                if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true) {
                    return true
                }
            }
            cm?.allNetworks?.forEach { network ->
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true) {
                    return true
                }
            }
        } catch (e: Exception) {}

        // 2. Check WifiManager directly
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (wm?.isWifiEnabled == true) {
                val info = wm.connectionInfo
                if (info != null && info.networkId != -1 && info.ipAddress != 0) {
                    return true
                }
            }
        } catch (e: Exception) {}

        // 3. Check for active Mobile Hotspot (ap0, swlan, softap), RNDIS, or Wi-Fi (wlan0) with valid IPv4
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (intf in interfaces) {
                    try {
                        val name = intf.name.lowercase()
                        val isTarget = name.contains("ap") || name.contains("swlan") || name.contains("softap") ||
                                       name.contains("rndis") || name.contains("wlan") || name.contains("eth") || name.contains("usb")
                        if (!isTarget) continue
                        if (intf.isLoopback) continue

                        for (addr in intf.inetAddresses) {
                            if (!addr.isLoopbackAddress && addr is Inet4Address) {
                                val host = addr.hostAddress ?: continue
                                if (!host.startsWith("127.") && !host.startsWith("169.254.") && host != "0.0.0.0") {
                                    return true
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {}

        // 4. Fallback: Check if device IP resolves to a valid local IPv4
        try {
            val ip = getDeviceIpAddress()
            if (ip.isNotBlank() && ip != "127.0.0.1" && !ip.startsWith("169.254.")) {
                return true
            }
        } catch (e: Exception) {}

        return false
    }

    /**
     * Enables ADB TCP/IP on configured port via root and saves state.
     * Returns the exact connect command to run on computer.
     */
    @Synchronized
    fun enable(context: Context): String {
        val port = getPort(context)
        ShellUtils.fastCmd("setprop service.adb.tcp.port $port; stop adbd; start adbd")
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_KEY, true)
            .putBoolean(PREF_SUSPENDED, false)
            .apply()
        WirelessAdbTileService.updateTile(context)
        return getConnectCommand(context)
    }

    /**
     * Disables ADB TCP/IP by resetting port to -1 (USB-only).
     */
    @Synchronized
    fun disable(context: Context): Boolean {
        ShellUtils.fastCmd("setprop service.adb.tcp.port -1; stop adbd; start adbd")
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_KEY, false)
            .putBoolean(PREF_SUSPENDED, false)
            .apply()
        WirelessAdbTileService.updateTile(context)
        return true
    }

    /**
     * Suspends ADB port to stop idle battery drain while retaining user's enabled configuration.
     */
    @Synchronized
    fun suspendPort(context: Context) {
        if (!isEnabled(context)) return
        ShellUtils.fastCmd("setprop service.adb.tcp.port -1; stop adbd; start adbd")
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_SUSPENDED, true)
            .apply()
        WirelessAdbTileService.updateTile(context)
    }

    /**
     * Reopens configured ADB port when Wi-Fi or Hotspot is connected.
     * Non-destructive: Does NOT restart adbd if it is already listening on the configured port.
     */
    @Synchronized
    fun reopenPort(context: Context) {
        if (!isEnabled(context)) return
        val port = getPort(context)
        val currentProp = getSystemProperty("service.adb.tcp.port")
        if (currentProp != port.toString()) {
            ShellUtils.fastCmd("setprop service.adb.tcp.port $port; stop adbd; start adbd")
        }
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_SUSPENDED, false)
            .apply()
        WirelessAdbTileService.updateTile(context)
    }

    /**
     * Re-applies configured ADB port on device boot if enabled by user.
     * Respects Smart Auto-Sleep if offline at boot time.
     */
    @Synchronized
    fun applyBootPersistence(context: Context) {
        if (isEnabled(context)) {
            val port = getPort(context)
            if (isAutoSleepEnabled(context) && !isLocalNetworkActive(context)) {
                suspendPort(context)
            } else {
                ShellUtils.fastCmd("setprop service.adb.tcp.port $port; stop adbd; start adbd")
                context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
                    .putBoolean(PREF_SUSPENDED, false)
                    .apply()
                WirelessAdbTileService.updateTile(context)
            }
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
            var ethernetIp: String? = null

            for (intf in interfaces) {
                try {
                    val name = intf.name.lowercase()
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: continue
                            if (host.startsWith("127.") || host.startsWith("169.254.") || host == "0.0.0.0") continue
                            if (name.contains("ap") || name.contains("swlan") || name.contains("softap") || name.contains("rndis")) {
                                hotspotIp = host
                            } else if (name.contains("wlan")) {
                                wifiIp = host
                            } else if (name.contains("eth") || name.contains("usb")) {
                                ethernetIp = host
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
            if (!hotspotIp.isNullOrBlank()) return hotspotIp
            if (!wifiIp.isNullOrBlank()) return wifiIp
            if (!ethernetIp.isNullOrBlank()) return ethernetIp
        } catch (e: Exception) {}

        return getFallbackIp()
    }

    private fun getFallbackIp(): String {
        try {
            val out = ShellUtils.fastCmdResult("ip route get 1.1.1.1 | tr ' ' '\\n' | grep -A 1 src | tail -n 1", 500).trim()
            if (out.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) return out
            val out2 = ShellUtils.fastCmdResult("ip -4 addr show wlan0", 500)
            val match = Regex("inet\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(out2)
            if (match != null) return match.groupValues[1]
        } catch (e: Exception) {}
        return "127.0.0.1"
    }

    fun getConnectCommand(context: Context? = null): String {
        val ip = getDeviceIpAddress()
        val port = if (context != null) getPort(context) else DEFAULT_ADB_PORT
        return "adb connect $ip:$port"
    }
}
