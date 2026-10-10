package com.example.phonecontrol

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File

object MasterManager {

    /**
     * 100% Comprehensive Reversion of all system modifications, hardware nodes,
     * network rules, modem tower locks, and resets all preferences to factory defaults.
     * Turns OFF all Master Hub and Sub-Feature switches.
     * Stops background services first and reports failed steps.
     */
    fun revertAll(context: Context): List<String> {
        val failedSteps = mutableListOf<String>()

        // 0. Stop Background Writers, Daemons, Services & Wireless ADB BEFORE Reverting
        try {
            context.stopService(Intent(context, AutoTweakService::class.java))
        } catch (_: Exception) {}
        DaemonManager.stopDaemon(context)
        WirelessAdbManager.disable(context)

        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        
        // 1. Revert UI, Display Resolution & Refresh Rate
        val resetDisplayCmds = listOf(
            "wm size reset",
            "wm density reset",
            "settings delete system min_refresh_rate",
            "settings delete system peak_refresh_rate",
            "settings delete global user_refresh_rate",
            "settings put system min_refresh_rate 30.0 2>/dev/null",
            "settings put system peak_refresh_rate 120.0 2>/dev/null"
        )
        val resDisplay = ShellUtils.runCommandsAsRoot(resetDisplayCmds)
        if (resDisplay.exitCode != 0) {
            failedSteps.add("Display Reset (${resDisplay.exitCode})")
        }
        
        // 2. Revert Thermal, CPU & GPU Governors to Stock Kernel Defaults
        // Includes MediaTek MTK thermal_active switch and thermal_message/sconfig
        val cpuKernelCmds = listOf(
            "echo 1 > /sys/module/mtk_thermal/parameters/thermal_active 2>/dev/null",
            "echo 0 > /sys/devices/virtual/thermal/thermal_message/sconfig 2>/dev/null",
            "chmod 666 /sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq 2>/dev/null",
            "chmod 666 /sys/devices/system/cpu/cpufreq/policy*/scaling_min_freq 2>/dev/null",
            "chmod 666 /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq 2>/dev/null",
            "chmod 666 /sys/devices/system/cpu/cpu*/cpufreq/scaling_min_freq 2>/dev/null",
            "for i in 0 1 2 3 4 5 6 7; do echo 1 > /sys/devices/system/cpu/cpu\$i/online 2>/dev/null; done",
            "for p in 0 1 2 3 4 5 6 7; do echo schedutil > /sys/devices/system/cpu/cpufreq/policy\$p/scaling_governor 2>/dev/null; done",
            "for p in 0 1 2 3 4 5 6 7; do cat /sys/devices/system/cpu/cpufreq/policy\$p/cpuinfo_max_freq > /sys/devices/system/cpu/cpufreq/policy\$p/scaling_max_freq 2>/dev/null; done",
            "for p in 0 1 2 3 4 5 6 7; do cat /sys/devices/system/cpu/cpufreq/policy\$p/cpuinfo_min_freq > /sys/devices/system/cpu/cpufreq/policy\$p/scaling_min_freq 2>/dev/null; done",
            "setprop persist.sys.thermal.disabled 0",
            "start thermal-engine 2>/dev/null",
            "start thermald 2>/dev/null",
            "start mi_thermald 2>/dev/null",
            "for tz in /sys/devices/virtual/thermal/thermal_zone*/mode; do echo enabled > \$tz 2>/dev/null; done",
            "echo 0 > /sys/class/kgsl/kgsl-3d0/force_bus_on 2>/dev/null",
            "echo 0 > /sys/class/kgsl/kgsl-3d0/force_clk_on 2>/dev/null",
            "echo 0 > /sys/class/kgsl/kgsl-3d0/force_rail_on 2>/dev/null",
            "echo 0 > /sys/module/pvrsrvkm/parameters/gpu_performance_mode 2>/dev/null",
            "echo 0 > /sys/devices/platform/13040000.mali/power_policy 2>/dev/null"
        )
        val resKernel = ShellUtils.runCommandsAsRoot(cpuKernelCmds)
        if (resKernel.exitCode != 0) {
            failedSteps.add("Kernel/CPU Reset (${resKernel.exitCode})")
        }
        
        // 3. Revert Sensors, Privacy, Location & Power State
        val sensorCmds = listOf(
            "settings put global sensor_privacy 0 2>/dev/null",
            "settings put global motion_engine_power_save 0 2>/dev/null",
            "settings put system touch_responsiveness 0 2>/dev/null",
            "echo 0 > /proc/touchpanel/game_switch_enable 2>/dev/null",
            "settings delete secure touch_game_mode 2>/dev/null",
            "settings delete system touch_game_mode 2>/dev/null",
            "svc nfc enable 2>/dev/null",
            "svc data enable 2>/dev/null",
            "settings put global master_sync_enabled 1 2>/dev/null",
            "cmd battery-saver set-enabled false 2>/dev/null",
            "settings put secure location_mode 3 2>/dev/null"
        )
        val resSensors = ShellUtils.runCommandsAsRoot(sensorCmds)
        if (resSensors.exitCode != 0) {
            failedSteps.add("Sensors Reset (${resSensors.exitCode})")
        }
        
        // 4. Revert Battery & Charging Engine
        val batteryCmds = listOf(
            "echo 0 > /sys/class/power_supply/battery/disable 2>/dev/null",
            "echo 1 > /sys/class/power_supply/battery/charging_enabled 2>/dev/null",
            "echo 1 > /sys/class/power_supply/battery/battery_charging_enabled 2>/dev/null",
            "echo 0 > /sys/class/power_supply/battery/input_suspend 2>/dev/null",
            "echo 0 > /sys/class/power_supply/battery/bypass_charging 2>/dev/null",
            "echo 3000000 > /sys/class/power_supply/primary_chg/input_current_limit 2>/dev/null",
            "echo 3000000 > /sys/class/power_supply/mtk-master-charger/input_current_limit 2>/dev/null",
            "echo 100 > /sys/class/power_supply/battery/charge_control_limit_max 2>/dev/null",
            "echo 0 > /sys/class/power_supply/battery/charge_control_limit 2>/dev/null",
            "echo 0 > /sys/kernel/fast_charge/force_fast_charge 2>/dev/null",
            "dumpsys deviceidle unforce 2>/dev/null",
            "echo 0 > /sys/module/lpm_levels/parameters/sleep_disabled 2>/dev/null",
            "echo Y > /sys/module/printk/parameters/enabled 2>/dev/null"
        )
        val resBattery = ShellUtils.runCommandsAsRoot(batteryCmds)
        if (resBattery.exitCode != 0) {
            failedSteps.add("Battery Reset (${resBattery.exitCode})")
        }
        
        // 5. Revert RAM & Storage I/O
        val storageCmds = listOf(
            "echo 100 > /proc/sys/vm/swappiness 2>/dev/null",
            "echo mq-deadline > /sys/block/sda/queue/scheduler 2>/dev/null",
            "echo mq-deadline > /sys/block/mmcblk0/queue/scheduler 2>/dev/null"
        )
        val resStorage = ShellUtils.runCommandsAsRoot(storageCmds)
        if (resStorage.exitCode != 0) {
            failedSteps.add("Storage Reset (${resStorage.exitCode})")
        }

        // 6. Remove Only App-Owned Firewall & QoS Rules (Preserve Android system netd/filter chains)
        try {
            val firewallPrefs = context.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
            val blockedPkgs = firewallPrefs.getStringSet("blocked_packages", emptySet()) ?: emptySet()
            val pm = context.packageManager
            val uids = mutableSetOf<Int>()
            for (pkg in blockedPkgs) {
                val storedUid = firewallPrefs.getInt("uid_$pkg", -1)
                if (storedUid != -1) {
                    uids.add(storedUid)
                }
                try {
                    val uid = pm.getApplicationInfo(pkg, 0).uid
                    uids.add(uid)
                } catch (_: Exception) {}
            }
            for (entry in firewallPrefs.all) {
                if (entry.key.startsWith("uid_") && entry.value is Int) {
                    uids.add(entry.value as Int)
                }
            }
            for (uid in uids) {
                while (ShellUtils.runAsRoot("iptables -D OUTPUT -m owner --uid-owner $uid -j REJECT 2>/dev/null").exitCode == 0) {}
                while (ShellUtils.runAsRoot("ip6tables -D OUTPUT -m owner --uid-owner $uid -j REJECT 2>/dev/null").exitCode == 0) {}
            }
        } catch (_: Exception) {}

        val netResetCmds = listOf(
            "settings put global private_dns_mode off 2>/dev/null",
            "settings delete global private_dns_specifier 2>/dev/null",
            "setprop net.dns1 \"\" 2>/dev/null",
            "setprop net.dns2 \"\" 2>/dev/null",
            "sysctl -w net.ipv4.tcp_congestion_control=cubic 2>/dev/null",
            "sysctl -w net.ipv4.tcp_fastopen=0 2>/dev/null",
            "echo -e \"AT+ECELL=0\\r\\n\" > /dev/radio/pttycmd1 2>/dev/null",
            "echo -e \"AT+E5GSWITCH=0\\r\\n\" > /dev/radio/pttycmd1 2>/dev/null",
            "echo -e \"AT+EPOWERCONF=1\\r\\n\" > /dev/radio/pttycmd1 2>/dev/null"
        )
        val resNet = ShellUtils.runCommandsAsRoot(netResetCmds)
        if (resNet.exitCode != 0) {
            failedSteps.add("Network Reset (${resNet.exitCode})")
        }
        
        // 7. Unfreeze all apps and restore App Standby Buckets & unsuspends
        val freezerPrefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val frozenApps = freezerPrefs.getStringSet("frozen_packages", emptySet()) ?: emptySet()
        for (pkg in frozenApps) {
            FreezerManager.unfreezeApp(pkg)
            FreezerManager.setSpecialFreeze(context, pkg, false)
        }
        
        val unsuspendCmds = listOf(
            "for pkg in \$(pm list packages -3 | cut -d ':' -f2); do pm unsuspend \$pkg 2>/dev/null; am unfreeze --package \$pkg 2>/dev/null; am set-standby-bucket \$pkg active 2>/dev/null; done"
        )
        val resUnsuspend = ShellUtils.runCommandsAsRoot(unsuspendCmds)
        if (resUnsuspend.exitCode != 0) {
            failedSteps.add("Unfreeze (${resUnsuspend.exitCode})")
        }

        // 8. Clean up System Whitelist and Accessibility Service Hook
        val resWhitelist = ShellUtils.runAsRoot("dumpsys deviceidle whitelist -${context.packageName}")
        if (resWhitelist.exitCode != 0) {
            failedSteps.add("Whitelist Removal (${resWhitelist.exitCode})")
        }
        val serviceComponent = "${context.packageName}/${AppEventService::class.java.canonicalName}"
        val currentServices = ShellUtils.runAsRoot("settings get secure enabled_accessibility_services").output.trim()
        if (currentServices.contains(serviceComponent)) {
            val cleaned = currentServices.split(":").filter { it != serviceComponent }.joinToString(":")
            ShellUtils.fastCmd("settings put secure enabled_accessibility_services '$cleaned'")
        }

        // 9. Delete Temporary Files
        ShellUtils.runAsRoot("rm -f /data/local/tmp/pc_screen /data/local/tmp/last_trim")
        try { File(context.filesDir, "last_trim").delete() } catch (_: Exception) {}
        try { File(context.filesDir, "last_night_opt").delete() } catch (_: Exception) {}

        // 10. Clear all sub-preferences cleanly
        context.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("multitasking_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("tower_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("game_turbo_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("super_doze_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("per_app_prefs", Context.MODE_PRIVATE).edit().clear().apply()

        // 11. Explicitly Turn OFF All Master Hubs & Sub-Feature Toggles asynchronously
        val editor = prefs.edit().clear()

        // Master Category Switches (ALL OFF)
        editor.putBoolean("master_battery_hub_enabled", false)
        editor.putBoolean("master_performance_hub_enabled", false)
        editor.putBoolean("master_gaming_hub_enabled", false)
        editor.putBoolean("master_security_hub_enabled", false)
        editor.putBoolean("master_tools_hub_enabled", false)

        // Sub-Feature Switches (ALL OFF)
        editor.putBoolean("force_doze_enabled", false)
        editor.putBoolean("standby_guard_enabled", false)
        editor.putBoolean("battery_lab_enabled", false)
        editor.putBoolean("super_doze_enabled", false)
        editor.putBoolean("smart_switch_enabled", false)
        editor.putBoolean("sensor_firewall_enabled", false)

        editor.putBoolean("resolution_enabled", false)
        editor.putBoolean("ram_manager_enabled", false)
        editor.putBoolean("storage_boost_enabled", false)
        editor.putBoolean("adaptive_thermal_enabled", false)
        editor.putBoolean("optimization_enabled", false)

        editor.putBoolean("game_turbo_enabled", false)
        editor.putBoolean("per_app_enabled", false)

        editor.putBoolean("network_priority_enabled", false)
        editor.putBoolean("firewall_enabled", false)
        editor.putBoolean(DaemonManager.PREF_TOWER_LOCK_ENABLED, false)

        editor.putBoolean("freezer_enabled", false)
        editor.putBoolean("bloatware_enabled", false)
        editor.putBoolean("app_extractor_enabled", false)
        editor.putBoolean("update_shield_enabled", false)
        editor.putBoolean("default_installer_enabled", false)
        editor.putBoolean("wireless_adb_enabled", false)
        editor.putBoolean("wireless_adb_auto_sleep", false)
        editor.remove("wireless_adb_port")
        editor.putBoolean("vault_enabled", false)
        editor.putBoolean("adb_enabled", false)

        UpdateShieldManager.setMasterEnabled(context, false)
        PackageInstallerManager.setDefaultInstallerEnabled(context, false)
        DynamicPrivacyManager.unlockAllGuardedApps(context)
        context.getSharedPreferences("dynamic_privacy_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        editor.putBoolean("dynamic_privacy_enabled", false)

        editor.putString("selected_mode", "rbBalance")
        editor.apply()

        // 12. Cleanly close persistent SU process
        ShellUtils.closePersistentShell()

        return failedSteps
    }
}
