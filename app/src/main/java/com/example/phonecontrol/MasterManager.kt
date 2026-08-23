package com.example.phonecontrol

import android.content.Context
import android.content.Intent

object MasterManager {

    /**
     * Reverts all system modifications and resets all preferences to default.
     */
    fun revertAll(context: Context) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        
        // 1. Revert UI/Display Changes
        TweakManager.setSystemResolution(false)
        TweakManager.setRefreshRate("Default")
        
        // 2. Revert Thermal & CPU Changes
        TweakManager.limitCpuFrequency(100)
        TweakManager.setClusterParking(false)
        ThermalManager.setThrottlingEnabled(true)
        
        // 3. Revert Log & System Suppression
        TweakManager.setSilentSystem(false)
        TweakManager.setLocationEnabled(true)
        SensorManager.setSensorsEnabled(true)
        ShellUtils.fastCmd("svc nfc enable")
        
        // 4. Revert RAM & I/O
        TweakManager.applyRamSettings("rbZram4G", "rbProfileBalance")
        StorageManager.applyStorageBoost(false)

        // 4.1 Flush Firewall rules
        ShellUtils.runAsRoot("iptables -F OUTPUT")
        
        // 5. Unfreeze all apps and clear freezer
        val freezerPrefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val frozenApps = freezerPrefs.getStringSet("frozen_packages", emptySet()) ?: emptySet()
        for (pkg in frozenApps) {
            FreezerManager.unfreezeApp(pkg)
        }
        freezerPrefs.edit().clear().apply()

        // 6. Reset all Category toggles (Hides dashboard cards)
        prefs.edit().apply {
            putBoolean("adaptive_thermal_enabled", false)
            putBoolean("network_priority_enabled", false)
            putBoolean("storage_boost_enabled", false)
            putBoolean("optimization_enabled", false)
            putBoolean("resolution_enabled", false)
            putBoolean("ram_manager_enabled", false)
            putBoolean("bloatware_enabled", false)
            putBoolean("adb_enabled", false)
            putBoolean("vault_enabled", false)
            putBoolean("tower_lock_enabled", false)
            putBoolean("automation_enabled", false)
            
            // Sub-features
            putBoolean("silent_system_enabled", false)
            putBoolean("daily_deep_opt_enabled", false)
            putBoolean("standby_guard_enabled", false)
            putBoolean("gps_auto_saver_enabled", false)
            putBoolean("batt_power_save_screen_off", false)
            putBoolean("core_parking_enabled", false)
            
            // Sensor Shield
            putBoolean("block_gyro", false)
            putBoolean("block_mag", false)
            putBoolean("block_light", false)
            putBoolean("block_motion", false)
            putBoolean("block_nfc", false)

            putString("selected_mode", "rbBalance")
            putInt("active_cpu_cap", 100)
            apply()
        }

        // 6.1 Clear Vault/Firewall Prefs
        context.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("multitasking_prefs", Context.MODE_PRIVATE).edit().clear().apply()

        // 7. Stop Background Logic
        DaemonManager.stopDaemon()
        context.stopService(Intent(context, AutoTweakService::class.java))
    }
}
