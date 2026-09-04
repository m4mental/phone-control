package com.example.phonecontrol

import android.content.Context
import android.content.Intent

object MasterManager {

    /**
     * 100% Comprehensive Reversion of all system modifications, hardware nodes,
     * network rules, modem tower locks, and resets all preferences to factory defaults.
     * Turns OFF all Master Hub and Sub-Feature switches.
     */
    fun revertAll(context: Context) {
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
        ShellUtils.runCommandsAsRoot(resetDisplayCmds)
        
        // 2. Revert Thermal, CPU & GPU Governors to Stock Kernel Defaults
        val cpuKernelCmds = listOf(
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
        ShellUtils.runCommandsAsRoot(cpuKernelCmds)
        
        // 3. Revert Sensors, Privacy, Location & Power State
        val sensorCmds = listOf(
            "cmd sensor_privacy disable 0 all 2>/dev/null",
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
        ShellUtils.runCommandsAsRoot(sensorCmds)
        
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
        ShellUtils.runCommandsAsRoot(batteryCmds)
        
        // 5. Revert RAM & Storage I/O
        val storageCmds = listOf(
            "echo 100 > /proc/sys/vm/swappiness 2>/dev/null",
            "echo mq-deadline > /sys/block/sda/queue/scheduler 2>/dev/null",
            "echo mq-deadline > /sys/block/mmcblk0/queue/scheduler 2>/dev/null"
        )
        ShellUtils.runCommandsAsRoot(storageCmds)

        // 6. Flush All Firewall & QoS Mangle Rules
        val netFlushCmds = listOf(
            "iptables -F 2>/dev/null",
            "iptables -X 2>/dev/null",
            "iptables -t nat -F 2>/dev/null",
            "iptables -t mangle -F 2>/dev/null",
            "ip6tables -F 2>/dev/null",
            "ip6tables -t mangle -F 2>/dev/null",
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
        ShellUtils.runCommandsAsRoot(netFlushCmds)
        
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
        ShellUtils.runCommandsAsRoot(unsuspendCmds)

        // 8. Clean up System Whitelist and Accessibility Service Hook
        ShellUtils.runAsRoot("dumpsys deviceidle whitelist -${context.packageName}")
        val serviceComponent = "${context.packageName}/${AppEventService::class.java.canonicalName}"
        val currentServices = ShellUtils.runAsRoot("settings get secure enabled_accessibility_services").output.trim()
        if (currentServices.contains(serviceComponent)) {
            val cleaned = currentServices.split(":").filter { it != serviceComponent }.joinToString(":")
            ShellUtils.fastCmd("settings put secure enabled_accessibility_services '$cleaned'")
        }

        // 9. Delete Temporary Files
        ShellUtils.runAsRoot("rm -f /data/local/tmp/pc_screen /data/local/tmp/last_trim")

        // 10. Clear all sub-preferences cleanly
        context.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("multitasking_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("tower_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("game_turbo_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("super_doze_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("per_app_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        // 11. Explicitly Turn OFF All Master Hubs & Sub-Feature Toggles synchronously
        val editor = prefs.edit().clear()

        // Master Category Switches (ALL OFF)
        editor.putBoolean("master_battery_hub_enabled", false)
        editor.putBoolean("master_performance_hub_enabled", false)
        editor.putBoolean("master_gaming_hub_enabled", false)
        editor.putBoolean("master_security_hub_enabled", false)
        editor.putBoolean("master_tools_hub_enabled", false)

        // Sub-Feature Switches (ALL OFF)
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
        editor.putBoolean("tower_lock_enabled", false)

        editor.putBoolean("freezer_enabled", false)
        editor.putBoolean("bloatware_enabled", false)
        editor.putBoolean("vault_enabled", false)
        editor.putBoolean("adb_enabled", false)

        editor.putString("selected_mode", "rbBalance")
        editor.commit()

        // 12. Stop Background Daemons & Services
        DaemonManager.stopDaemon()
        context.stopService(Intent(context, AutoTweakService::class.java))
        
        // 13. Cleanly close persistent SU process
        ShellUtils.closePersistentShell()
    }
}
