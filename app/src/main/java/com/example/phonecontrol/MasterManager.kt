package com.example.phonecontrol

import android.content.Context
import android.content.Intent

object MasterManager {

    /**
     * 100% Comprehensive Reversion of all system modifications, hardware nodes,
     * network rules, modem tower locks, and resets all preferences to factory defaults.
     */
    fun revertAll(context: Context) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        
        // 1. Revert UI/Display Changes
        TweakManager.setSystemResolution(false)
        TweakManager.setRefreshRate("Default")
        
        // 2. Revert Thermal, CPU & GPU Changes
        TweakManager.limitCpuFrequency(100)
        TweakManager.setClusterParking(false, deep = true)
        ThermalManager.setThrottlingEnabled(true)
        TweakManager.applyInputBoost(false)
        TweakManager.applyEntropyTuning(false)
        TweakManager.applyGlobalMode("Balance")
        
        // 3. Revert Log, Sensors & System Power State
        TweakManager.setSilentSystem(false)
        TweakManager.setLocationEnabled(true)
        SensorManager.setSensorsEnabled(true)
        TweakManager.applyWakelockBlocker(false)
        ShellUtils.fastCmd("svc nfc enable")
        ShellUtils.fastCmd("svc data enable")
        ShellUtils.fastCmd("settings put global master_sync_enabled 1")
        ShellUtils.fastCmd("cmd battery-saver set-enabled false")
        
        // 4. Revert Battery & Charging Engine
        BatteryManager.setBypassEnabled(false)
        BatteryManager.setChargingEnabled(true)
        BatteryManager.setChargeCurrent(3000)
        BatteryManager.setUsbFastCharge(false)
        BatteryManager.setForceDoze(false)
        
        // 5. Revert RAM & Storage I/O
        TweakManager.applyRamSettings("rbZram4G", "rbProfileBalance")
        StorageManager.applyStorageBoost(false)

        // 6. Flush All Firewall & QoS Mangle Rules
        ShellUtils.runAsRoot("iptables -F OUTPUT")
        ShellUtils.runAsRoot("iptables -t mangle -F OUTPUT")
        
        // 7. Reset Network & Private DNS
        ShellUtils.fastCmd("settings put global private_dns_mode off")
        ShellUtils.fastCmd("settings delete global private_dns_specifier")
        ShellUtils.fastCmd("sysctl -w net.ipv4.tcp_congestion_control=cubic 2>/dev/null")
        ShellUtils.fastCmd("sysctl -w net.ipv6.conf.all.disable_ipv6=0 2>/dev/null")

        // 8. Release Cellular Modem Hard-Lock (AT+ECELL=0) & 5G Anti-Sleep
        ShellUtils.runAsRoot("echo -e \"AT+ECELL=0\\r\\n\" > /dev/radio/pttycmd1")
        ShellUtils.runAsRoot("echo -e \"AT+E5GSWITCH=0\\r\\n\" > /dev/radio/pttycmd1")
        ShellUtils.runAsRoot("echo -e \"AT+EPOWERCONF=1\\r\\n\" > /dev/radio/pttycmd1")
        
        // 9. Unfreeze all apps and restore App Standby Buckets
        val freezerPrefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val frozenApps = freezerPrefs.getStringSet("frozen_packages", emptySet()) ?: emptySet()
        for (pkg in frozenApps) {
            FreezerManager.unfreezeApp(pkg)
        }
        
        // Restore all 3rd-party apps from Standby restricted mode
        val packagesResult = ShellUtils.runAsRoot("pm list packages -3 | cut -d ':' -f2")
        val packages = packagesResult.output.split("\n").filter { it.isNotBlank() }
        for (pkg in packages) {
            ShellUtils.fastCmd("am set-standby-bucket $pkg active")
        }

        // 10. Clean up System Whitelist and Accessibility Service Hook
        ShellUtils.runAsRoot("dumpsys deviceidle whitelist -${context.packageName}")
        val serviceComponent = "${context.packageName}/${AppEventService::class.java.canonicalName}"
        val currentServices = ShellUtils.runAsRoot("settings get secure enabled_accessibility_services").output.trim()
        if (currentServices.contains(serviceComponent)) {
            val cleaned = currentServices.split(":").filter { it != serviceComponent }.joinToString(":")
            ShellUtils.fastCmd("settings put secure enabled_accessibility_services '$cleaned'")
        }

        // 11. Delete Temporary Files
        ShellUtils.runAsRoot("rm -f /data/local/tmp/pc_screen /data/local/tmp/last_trim")

        // 12. Clear all sub-preferences cleanly
        context.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("multitasking_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("tower_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("game_turbo_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("super_doze_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("per_app_prefs", Context.MODE_PRIVATE).edit().clear().apply()

        // 13. Reset Main Settings
        prefs.edit().clear().apply()

        // 14. Stop Background Daemons & Services
        DaemonManager.stopDaemon()
        context.stopService(Intent(context, AutoTweakService::class.java))
        
        // 15. Cleanly close persistent SU process
        ShellUtils.closePersistentShell()
    }
}
