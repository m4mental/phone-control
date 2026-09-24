package com.example.phonecontrol

import android.content.Context
import android.util.Log

object PrivateDnsManager {
    private const val TAG = "PrivateDnsManager"

    enum class DnsProvider(
        val key: String,
        val displayName: String,
        val shortLabel: String,
        val mode: String,
        val specifier: String,
        val description: String
    ) {
        OFF(
            key = "off",
            displayName = "Off (ISP / Automatic)",
            shortLabel = "Off",
            mode = "off",
            specifier = "",
            description = "Default standard DNS provided by your cellular carrier or Wi-Fi router."
        ),
        ADGUARD(
            key = "adguard",
            displayName = "AdGuard (System-Wide AdBlock)",
            shortLabel = "AdGuard",
            mode = "hostname",
            specifier = "dns.adguard-dns.com",
            description = "Blocks ads, telemetry, trackers, and malicious domains across all apps and games."
        ),
        CLOUDFLARE(
            key = "cloudflare",
            displayName = "Cloudflare 1.1.1.1 (Low Latency)",
            shortLabel = "1.1.1.1",
            mode = "hostname",
            specifier = "1dot1dot1dot1.cloudflare-dns.com",
            description = "Ultra-fast privacy-first DNS resolution optimized for low gaming ping."
        ),
        GOOGLE(
            key = "google",
            displayName = "Google Public DNS",
            shortLabel = "Google",
            mode = "hostname",
            specifier = "dns.google",
            description = "Highly reliable, resilient global DNS resolution backed by Google infrastructure."
        ),
        QUAD9(
            key = "quad9",
            displayName = "Quad9 (Malware & Phishing Protection)",
            shortLabel = "Quad9",
            mode = "hostname",
            specifier = "dns.quad9.net",
            description = "Enterprise-grade threat intelligence blocking malware and phishing."
        ),
        MULLVAD(
            key = "mullvad",
            displayName = "Mullvad AdBlock DoH",
            shortLabel = "Mullvad",
            mode = "hostname",
            specifier = "base.dns.mullvad.net",
            description = "High-security European zero-log DNS blocking ads and tracking."
        ),
        CONTROLD(
            key = "controld",
            displayName = "ControlD Free AdBlock DNS",
            shortLabel = "ControlD",
            mode = "hostname",
            specifier = "p2.freedns.controld.com",
            description = "Ultra-lightweight modern resolver with integrated malware and ad filtering."
        ),
        CUSTOM(
            key = "custom",
            displayName = "Custom DoH / NextDNS / Pi-hole",
            shortLabel = "Custom",
            mode = "hostname",
            specifier = "",
            description = "User-defined private DNS hostname (e.g., your NextDNS ID or Pi-hole server)."
        )
    }

    /**
     * Reads current active private DNS configuration from Android Global Settings.
     * Uses ContentResolver for 0ms instantaneous read, falling back to root shell if needed.
     */
    fun getCurrentProvider(context: Context? = null): DnsProvider {
        val mode = if (context != null) {
            try {
                android.provider.Settings.Global.getString(context.contentResolver, "private_dns_mode") ?: ""
            } catch (e: Exception) {
                ShellUtils.runAsRoot("settings get global private_dns_mode", 3000).output
            }
        } else {
            ShellUtils.runAsRoot("settings get global private_dns_mode", 3000).output
        }.trim().lowercase()

        if (mode == "off" || mode == "null" || mode.isBlank()) {
            return DnsProvider.OFF
        }

        val spec = if (context != null) {
            try {
                android.provider.Settings.Global.getString(context.contentResolver, "private_dns_specifier") ?: ""
            } catch (e: Exception) {
                ShellUtils.runAsRoot("settings get global private_dns_specifier", 3000).output
            }
        } else {
            ShellUtils.runAsRoot("settings get global private_dns_specifier", 3000).output
        }.trim().lowercase()

        return when {
            spec.contains("adguard") -> DnsProvider.ADGUARD
            spec.contains("cloudflare") -> DnsProvider.CLOUDFLARE
            spec.contains("google") -> DnsProvider.GOOGLE
            spec.contains("quad9") -> DnsProvider.QUAD9
            spec.contains("mullvad") -> DnsProvider.MULLVAD
            spec.contains("controld") -> DnsProvider.CONTROLD
            mode == "hostname" && spec.isNotBlank() && spec != "null" -> DnsProvider.CUSTOM
            else -> if (mode == "hostname") DnsProvider.ADGUARD else DnsProvider.OFF
        }
    }

    /**
     * Returns current raw DNS hostname specifier if set.
     */
    fun getCurrentSpecifier(context: Context? = null): String {
        val spec = if (context != null) {
            try {
                android.provider.Settings.Global.getString(context.contentResolver, "private_dns_specifier") ?: ""
            } catch (e: Exception) {
                ShellUtils.runAsRoot("settings get global private_dns_specifier", 3000).output
            }
        } else {
            ShellUtils.runAsRoot("settings get global private_dns_specifier", 3000).output
        }.trim()
        return if (spec == "null") "" else spec
    }

    /**
     * Applies the selected DNS profile system-wide and notifies QS Tile.
     */
    fun setProvider(provider: DnsProvider, context: Context? = null): Boolean {
        val cmd = if (provider == DnsProvider.OFF) {
            "settings put global private_dns_mode off && settings delete global private_dns_specifier"
        } else {
            "settings put global private_dns_mode ${provider.mode} && settings put global private_dns_specifier ${provider.specifier}"
        }

        val res = ShellUtils.runAsRoot(cmd, 8000)
        Log.d(TAG, "Applied DNS provider ${provider.name}: exitCode=${res.exitCode}")
        if (context != null) {
            PrivateDnsTileService.updateTile(context)
        }
        return res.exitCode == 0
    }

    /**
     * Sets a custom private DNS hostname (e.g. NextDNS ID, custom Pi-hole, AdGuard Home).
     */
    fun setCustomHostname(hostname: String, context: Context? = null): Boolean {
        val cleanHost = hostname.trim()
        if (cleanHost.isBlank()) {
            return setProvider(DnsProvider.OFF, context)
        }
        val cmd = "settings put global private_dns_mode hostname && settings put global private_dns_specifier $cleanHost"
        val res = ShellUtils.runAsRoot(cmd, 8000)
        Log.d(TAG, "Applied Custom DNS Hostname ($cleanHost): exitCode=${res.exitCode}")
        if (context != null) {
            PrivateDnsTileService.updateTile(context)
        }
        return res.exitCode == 0
    }

    /**
     * Cycles to the next primary DNS profile in sequence:
     * OFF -> ADGUARD -> CLOUDFLARE -> GOOGLE -> QUAD9 -> MULLVAD -> CONTROLD -> OFF
     */
    fun cycleNext(context: Context? = null): DnsProvider {
        val cycleSequence = listOf(
            DnsProvider.OFF,
            DnsProvider.ADGUARD,
            DnsProvider.CLOUDFLARE,
            DnsProvider.GOOGLE,
            DnsProvider.QUAD9,
            DnsProvider.MULLVAD,
            DnsProvider.CONTROLD
        )
        val current = getCurrentProvider(context)
        val currentIndex = cycleSequence.indexOf(current)
        val nextIndex = if (currentIndex != -1) (currentIndex + 1) % cycleSequence.size else 1
        val next = cycleSequence[nextIndex]
        setProvider(next, context)
        return next
    }

    /**
     * Measures DNS latency in milliseconds using system root ping command.
     * Operates with 0% Android manifest INTERNET permission by executing via root shell (UID 0).
     */
    fun measureDnsLatencyRoot(target: String? = null): Long {
        val host = when {
            !target.isNullOrBlank() -> target
            else -> {
                val provider = getCurrentProvider()
                when (provider) {
                    DnsProvider.CLOUDFLARE -> "1.1.1.1"
                    DnsProvider.GOOGLE -> "8.8.8.8"
                    DnsProvider.ADGUARD -> "94.140.14.14"
                    DnsProvider.QUAD9 -> "9.9.9.9"
                    DnsProvider.MULLVAD -> "194.242.2.2"
                    DnsProvider.CONTROLD -> "76.76.2.0"
                    DnsProvider.CUSTOM -> {
                        val spec = getCurrentSpecifier()
                        if (spec.isNotBlank()) spec else "1.1.1.1"
                    }
                    DnsProvider.OFF -> "1.1.1.1"
                }
            }
        }

        val res = ShellUtils.runAsRoot("ping -c 1 -W 2 $host", 3500)
        if (res.exitCode != 0 || res.output.isBlank()) return -1L

        val timeMatch = Regex("time=([0-9.]+)\\s*ms").find(res.output)
        if (timeMatch != null) {
            return timeMatch.groupValues[1].toDoubleOrNull()?.toLong() ?: -1L
        }

        val rttMatch = Regex("min/avg/max[^=]*=\\s*[0-9.]+/([0-9.]+)/").find(res.output)
        if (rttMatch != null) {
            return rttMatch.groupValues[1].toDoubleOrNull()?.toLong() ?: -1L
        }

        return -1L
    }
}
