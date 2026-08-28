package com.example.phonecontrol

import android.content.Context
import android.content.Intent
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
     * Adaptive Thermal Engine: Stepped throttling based on temperature.
     */
    fun applyAdaptiveThrottling(context: Context, temp: Int) {
        if (isCooldownActive) return
        
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("adaptive_thermal_enabled", false)) {
            applyPreventiveThrottling(temp)
            return
        }

        val cap = when {
            temp <= 44 -> 100
            temp == 45 -> 95
            temp == 46 -> 90
            temp == 47 -> 85
            temp == 48 -> 80
            temp == 49 -> 75
            temp == 50 -> 70
            temp == 51 -> 65
            else -> 60 // 52°C and above
        }

        TweakManager.limitCpuFrequency(cap)
        val configVal = if (cap == 60) "2" else if (cap == 80) "1" else "0"
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
        // Restore Networks
        ShellUtils.fastCmd("settings put global airplane_mode_on 0")
        ShellUtils.fastCmd("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false")
        ShellUtils.fastCmd("svc wifi enable")
        ShellUtils.fastCmd("svc data enable")
        
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_cooldown_active", false).apply()
        
        val isThrottlingDisabled = prefs.getBoolean("disable_throttling", false)
        setThrottlingEnabled(!isThrottlingDisabled)
        
        isCooldownActive = false
        val intent = Intent("com.example.phonecontrol.ACTION_COOLDOWN_END")
        context.sendBroadcast(intent)
    }
}
