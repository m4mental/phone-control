package com.example.phonecontrol

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager as AndroidBatteryManager
import android.os.Handler
import android.os.Looper

object ThermalManager {

    private const val MTK_THERMAL_SWITCH = "/sys/module/mtk_thermal/parameters/thermal_active"
    private const val THERMAL_CONFIG_PATH = "/sys/devices/virtual/thermal/thermal_message/sconfig"
    
    var isCooldownActive = false

    fun setThrottlingEnabled(enabled: Boolean) {
        val value = if (enabled) "1" else "0"
        ShellUtils.fastCmd("echo $value > $MTK_THERMAL_SWITCH")
        if (!enabled) ShellUtils.fastCmd("echo 16 > $THERMAL_CONFIG_PATH")
        else ShellUtils.fastCmd("echo 0 > $THERMAL_CONFIG_PATH")
    }

    fun getTemperature(): Int {
        val result = ShellUtils.runAsRoot("cat /sys/class/power_supply/battery/temp")
        return try {
            val raw = result.output.trim().toInt()
            if (raw > 1000) raw / 1000 else if (raw > 100) raw / 10 else raw
        } catch (e: Exception) {
            35
        }
    }

    /**
     * Adaptive Thermal Engine: Stepped throttling based on user-defined Temp Fuse & Charging state.
     */
    fun applyAdaptiveThrottling(context: Context, temp: Int) {
        if (isCooldownActive) return
        
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val manualStage = prefs.getInt("manual_stage_override", 0)
        if (manualStage != 0 || TweakManager.manualStageOverride != 0) {
            // Strictly protect user's manual test lock in Test Lab
            return
        }

        // 1. Auto Emergency Cooldown Trigger Check
        val isAutoCooldownEnabled = prefs.getBoolean("auto_cooldown_enabled", false)
        val autoCooldownThreshold = prefs.getInt("auto_cooldown_threshold", 50)
        if (isAutoCooldownEnabled && temp >= autoCooldownThreshold) {
            startEmergencyCooldown(context) {}
            return
        }

        // 2. Ignore While Charging Check
        if (prefs.getBoolean("ignore_charging", false)) {
            val battStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = battStatus?.getIntExtra(AndroidBatteryManager.EXTRA_PLUGGED, -1) ?: 0
            val isCharging = plugged == AndroidBatteryManager.BATTERY_PLUGGED_AC ||
                             plugged == AndroidBatteryManager.BATTERY_PLUGGED_USB ||
                             plugged == AndroidBatteryManager.BATTERY_PLUGGED_WIRELESS
            if (isCharging && temp < 48) {
                // Keep CPU 100% uncapped during fast charging unless safety danger threshold (> 48°C) is crossed
                prefs.edit().putInt("active_cpu_cap", 100).apply()
                TweakManager.limitCpuFrequency(100)
                return
            }
        }

        if (!prefs.getBoolean("adaptive_thermal_enabled", false)) {
            applyPreventiveThrottling(temp)
            return
        }

        // 3. Dynamic Temp Fuse Calculation
        val tempFuse = prefs.getInt("temp_fuse", 45)
        val diff = temp - tempFuse

        val cap = when {
            diff < 0 -> 100
            diff == 0 -> 95
            diff == 1 -> 90
            diff == 2 -> 85
            diff == 3 -> 80
            diff == 4 -> 75
            diff == 5 -> 70
            diff == 6 -> 65
            else -> 60 // Fuse + 7°C or higher
        }

        TweakManager.limitCpuFrequency(cap)
        val configVal = if (cap == 60) "2" else if (cap <= 80) "1" else "0"
        ShellUtils.fastCmd("echo $configVal > /sys/devices/virtual/thermal/thermal_message/sconfig 2>/dev/null")
        
        prefs.edit().putInt("active_cpu_cap", cap).apply()
    }

    /**
     * Preventive Thermal Guard: Slower throttling before emergency.
     */
    fun applyPreventiveThrottling(temp: Int) {
        if (isCooldownActive) return
        
        if (temp >= 48) {
            ShellUtils.fastCmd("echo 1 > /sys/devices/virtual/thermal/thermal_message/sconfig 2>/dev/null")
            ShellUtils.fastCmd("echo 50 > /proc/sys/kernel/sched_upmigrate 2>/dev/null")
        } else if (temp < 45) {
            ShellUtils.fastCmd("echo 0 > /sys/devices/virtual/thermal/thermal_message/sconfig 2>/dev/null")
        }
    }

    fun startEmergencyCooldown(context: Context, onComplete: () -> Unit) {
        if (isCooldownActive) return
        isCooldownActive = true

        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("is_cooldown_active", true)
            .putLong("cooldown_start_timestamp", System.currentTimeMillis())
            .apply()

        // 1. Force Maximum Throttling and Battery Saver
        setThrottlingEnabled(true)
        TweakManager.applyBatterySaver()
        
        // 2. Kill Heavy Background Apps
        ShellUtils.fastCmd("am kill-all")

        // 3. Kill Networks Brutally (Root)
        ShellUtils.fastCmd("settings put global airplane_mode_on 1")
        ShellUtils.fastCmd("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true")
        ShellUtils.fastCmd("svc wifi disable")
        ShellUtils.fastCmd("svc data disable")

        // 4. Notify Service to start 2-minute timer notification
        val intent = Intent("com.example.phonecontrol.ACTION_COOLDOWN_START")
        context.sendBroadcast(intent)

        Handler(Looper.getMainLooper()).postDelayed({
            revertCooldown(context)
            isCooldownActive = false
            onComplete()
        }, 120000)
    }

    /**
     * Recovery check on boot / service launch to ensure airplane mode is never stuck.
     */
    fun checkAndRecoverCooldown(context: Context) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val wasActive = prefs.getBoolean("is_cooldown_active", false)
        val startTime = prefs.getLong("cooldown_start_timestamp", 0L)
        
        if (wasActive) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= 120000) {
                revertCooldown(context)
            } else {
                val remaining = 120000 - elapsed
                Handler(Looper.getMainLooper()).postDelayed({
                    revertCooldown(context)
                }, remaining)
            }
        }
    }

    private fun revertCooldown(context: Context) {
        // 1. Restore Networks
        ShellUtils.fastCmd("settings put global airplane_mode_on 0")
        ShellUtils.fastCmd("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false")
        ShellUtils.fastCmd("svc wifi enable")
        ShellUtils.fastCmd("svc data enable")
        
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_cooldown_active", false).apply()
        
        val isThrottlingDisabled = prefs.getBoolean("disable_throttling", false)
        setThrottlingEnabled(!isThrottlingDisabled)
        
        // 2. Restore User's Active System Mode (No longer stuck in Battery Saver)
        val savedModeKey = prefs.getString("selected_mode", "rbBalance") ?: "rbBalance"
        when (savedModeKey) {
            "rbPowerSaver" -> TweakManager.applyGlobalMode("Power Saver")
            "rbPerformance" -> TweakManager.applyGlobalMode("Performance")
            "rbAutomatic" -> {
                val focus = prefs.getString("selected_focus", "rbFocusDaily") ?: "rbFocusDaily"
                TweakManager.applyGlobalMode("AI_Daily")
            }
            else -> TweakManager.applyGlobalMode("Balance")
        }
        TweakManager.limitCpuFrequency(100)
        prefs.edit().putInt("active_cpu_cap", 100).apply()

        isCooldownActive = false
        val intent = Intent("com.example.phonecontrol.ACTION_COOLDOWN_END")
        context.sendBroadcast(intent)
    }
}
