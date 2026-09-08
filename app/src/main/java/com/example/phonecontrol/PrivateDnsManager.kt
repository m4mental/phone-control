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
        )
    }

    /**
     * Reads current active private DNS configuration from Android Global Settings.
     */
    fun getCurrentProvider(): DnsProvider {
        val modeRes = ShellUtils.runAsRoot("settings get global private_dns_mode", 5000)
        val mode = modeRes.output.trim().lowercase()

        if (mode == "off" || mode == "null" || mode.isBlank()) {
            return DnsProvider.OFF
        }

        val specRes = ShellUtils.runAsRoot("settings get global private_dns_specifier", 5000)
        val spec = specRes.output.trim().lowercase()

        return when {
            spec.contains("adguard") -> DnsProvider.ADGUARD
            spec.contains("cloudflare") -> DnsProvider.CLOUDFLARE
            spec.contains("google") -> DnsProvider.GOOGLE
            else -> if (mode == "hostname") DnsProvider.ADGUARD else DnsProvider.OFF
        }
    }

    /**
     * Applies the selected DNS profile system-wide.
     */
    fun setProvider(provider: DnsProvider): Boolean {
        val cmd = if (provider == DnsProvider.OFF) {
            "settings put global private_dns_mode off && settings delete global private_dns_specifier"
        } else {
            "settings put global private_dns_mode ${provider.mode} && settings put global private_dns_specifier ${provider.specifier}"
        }

        val res = ShellUtils.runAsRoot(cmd, 8000)
        Log.d(TAG, "Applied DNS provider ${provider.name}: exitCode=${res.exitCode}")
        return res.exitCode == 0
    }

    /**
     * Cycles to the next DNS profile in sequence:
     * OFF -> ADGUARD -> CLOUDFLARE -> GOOGLE -> OFF
     */
    fun cycleNext(): DnsProvider {
        val current = getCurrentProvider()
        val all = DnsProvider.values()
        val nextIndex = (current.ordinal + 1) % all.size
        val next = all[nextIndex]
        setProvider(next)
        return next
    }
}
