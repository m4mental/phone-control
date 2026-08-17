package com.example.phonecontrol

import android.content.Context
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

    fun startEmergencyCooldown(context: Context, onComplete: () -> Unit) {
        if (isCooldownActive) return
        isCooldownActive = true

        setThrottlingEnabled(true)
        ShellUtils.fastCmd("am kill-all")
        ShellUtils.fastCmd("settings put global airplane_mode_on 1")
        ShellUtils.fastCmd("am broadcast -a android.intent.action.AIRPLANE_MODE")
        TweakManager.applyBatterySaver()

        Handler(Looper.getMainLooper()).postDelayed({
            revertCooldown(context)
            isCooldownActive = false
            onComplete()
        }, 120000)
    }

    private fun revertCooldown(context: Context) {
        ShellUtils.fastCmd("settings put global airplane_mode_on 0")
        ShellUtils.fastCmd("am broadcast -a android.intent.action.AIRPLANE_MODE")
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val isThrottlingDisabled = prefs.getBoolean("disable_throttling", false)
        setThrottlingEnabled(!isThrottlingDisabled)
    }
}
