package com.example.phonecontrol

import android.content.Context
import java.util.Locale

object BatteryManager {

    private const val BATT_PATH = "/sys/class/power_supply/battery/"

    data class BatteryInfo(
        val voltage: String,
        val temp: String,
        val health: String,
        val cycles: String,
        val wattage: String,
        val wear: String
    )

    data class BatteryAnalytics(
        val voltageMv: Int,
        val tempDeciC: Int,
        val currentWatts: Double,
        val cycles: Int,
        val health: String,
        val wearPercent: Int
    )

    fun getBatteryStats(): BatteryInfo {
        val files = listOf("voltage_now", "temp", "current_now", "cycle_count", "health", "charge_full", "charge_full_design")
        val cmd = files.joinToString(" && ") { "cat ${BATT_PATH}$it" }
        val result = ShellUtils.runAsRoot(cmd)
        val lines = result.output.split("\n")

        val voltRaw = lines.getOrNull(0) ?: "0"
        val tempRaw = lines.getOrNull(1) ?: "0"
        val currRaw = lines.getOrNull(2) ?: "0"
        val cycles = lines.getOrNull(3) ?: "0"
        val health = lines.getOrNull(4) ?: "Good"
        var full = lines.getOrNull(5)?.toDoubleOrNull() ?: 5000000.0
        var design = lines.getOrNull(6)?.toDoubleOrNull() ?: 5000000.0
        if (design in 1.0..999999.0) design *= 10.0
        if (full in 1.0..999999.0) full *= 10.0
        
        val wearLevel = if (design > 0) ((full / design) * 100).toInt().coerceIn(1, 100) else 100

        val vV = voltRaw.toDoubleOrNull() ?: 0.0
        val aA = currRaw.toDoubleOrNull() ?: 0.0
        val watt = (vV / 1000000.0) * (aA / 1000000.0)
        
        return BatteryInfo(
            voltage = String.format(Locale.US, "%.2fV", vV / 1000000.0),
            temp = try { "${tempRaw.toInt() / 10}°C" } catch (e: Exception) { "0°C" },
            health = health.ifEmpty { "Good" },
            cycles = cycles.ifEmpty { "0" },
            wattage = String.format(Locale.US, "%.1fW", if (watt < 0) -watt else watt),
            wear = "$wearLevel%"
        )
    }

    fun getBatteryAnalytics(context: Context): BatteryAnalytics {
        val stats = getBatteryStats()
        val voltMv = (stats.voltage.replace("V", "").toDoubleOrNull() ?: 4.0 * 1000).toInt()
        val tempDeciC = (stats.temp.replace("°C", "").toDoubleOrNull() ?: 30.0 * 10).toInt()
        val currentWatts = stats.wattage.replace("W", "").toDoubleOrNull() ?: 0.0
        val cycles = stats.cycles.toIntOrNull() ?: 0
        val wearPercent = stats.wear.replace("%", "").toIntOrNull() ?: 100

        return BatteryAnalytics(
            voltageMv = voltMv,
            tempDeciC = tempDeciC,
            currentWatts = currentWatts,
            cycles = cycles,
            health = stats.health,
            wearPercent = wearPercent
        )
    }

    fun setChargingEnabled(enabled: Boolean) {
        val value = if (enabled) "1" else "0"
        val commands = listOf(
            "echo 0 > ${BATT_PATH}disable 2>/dev/null",
            "echo $value > ${BATT_PATH}charging_enabled 2>/dev/null",
            "echo $value > ${BATT_PATH}battery_charging_enabled 2>/dev/null",
            "echo ${if (enabled) "0" else "1"} > ${BATT_PATH}input_suspend 2>/dev/null"
        )
        ShellUtils.runCommandsAsRoot(commands)
    }

    fun setChargingLimit(context: Context, percent: Int) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("battery_limit_percent", percent).apply()
        // Hardware control limit if supported
        val paths = listOf(
            "/sys/class/power_supply/battery/charge_control_limit_max",
            "/sys/class/power_supply/battery/charge_control_limit"
        )
        for (path in paths) {
            ShellUtils.fastCmd("echo $percent > $path 2>/dev/null")
        }
    }

    fun setBypassEnabled(enabled: Boolean) {
        val cmds = if (enabled) {
            """
            echo 0 > ${BATT_PATH}disable 2>/dev/null
            echo 0 > /sys/class/power_supply/primary_chg/input_current_limit 2>/dev/null
            echo 0 > /sys/class/power_supply/mtk-master-charger/input_current_limit 2>/dev/null
            echo 1 > ${BATT_PATH}bypass_charging 2>/dev/null
            echo 0 > ${BATT_PATH}charging_enabled 2>/dev/null
            echo 0 > ${BATT_PATH}battery_charging_enabled 2>/dev/null
            echo 1 > ${BATT_PATH}input_suspend 2>/dev/null
            """.trimIndent()
        } else {
            """
            echo 0 > ${BATT_PATH}disable 2>/dev/null
            echo 3000000 > /sys/class/power_supply/primary_chg/input_current_limit 2>/dev/null
            echo 3000000 > /sys/class/power_supply/mtk-master-charger/input_current_limit 2>/dev/null
            echo 0 > ${BATT_PATH}bypass_charging 2>/dev/null
            echo 1 > ${BATT_PATH}charging_enabled 2>/dev/null
            echo 1 > ${BATT_PATH}battery_charging_enabled 2>/dev/null
            echo 0 > ${BATT_PATH}input_suspend 2>/dev/null
            """.trimIndent()
        }
        ShellUtils.fastCmd(cmds)
    }

    fun setBypassCharging(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("battery_bypass_charging", enabled).apply()
        setBypassEnabled(enabled)
    }

    fun setFastChargeBoost(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("battery_fast_charge_boost", enabled).apply()
        val throttleVal = if (enabled) "0" else "1"
        val hvVal = if (enabled) "1" else "0"
        val cmds = listOf(
            "echo $throttleVal > /sys/devices/platform/charger/Thermal_throttle 2>/dev/null",
            "echo $hvVal > /sys/devices/platform/charger/High_voltage_chg_enable 2>/dev/null",
            "echo ${if (enabled) "4500" else "2050"} > /sys/devices/platform/charger/sc_ibat_limit 2>/dev/null",
            "echo ${if (enabled) "4500000" else "2050000"} > /sys/class/power_supply/mtk-master-charger/constant_charge_current_max 2>/dev/null"
        )
        ShellUtils.runCommandsAsRoot(cmds)
    }

    fun setUsbPcCharge(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("battery_usb_pc_charge", enabled).apply()
        val currentLimit = if (enabled) "1500" else "500"
        val currentLimitUa = if (enabled) "1500000" else "500000"
        val termCurrent = if (enabled) "150" else "800"
        val fastVal = if (enabled) "1" else "0"
        
        val cmds = listOf(
            "echo $currentLimit > /sys/class/power_supply/primary_chg/input_current_limit 2>/dev/null",
            "echo $currentLimitUa > /sys/class/power_supply/mtk-master-charger/input_current_limit 2>/dev/null",
            "echo $currentLimitUa > /sys/devices/platform/charger/input_current 2>/dev/null",
            "echo $termCurrent > /sys/class/power_supply/primary_chg/charge_term_current 2>/dev/null",
            "echo $fastVal > /sys/kernel/fast_charge/force_fast_charge 2>/dev/null",
            "echo $fastVal > /sys/class/power_supply/battery/allow_fast_chg 2>/dev/null"
        )
        ShellUtils.runCommandsAsRoot(cmds)
    }

    fun setKillSensorsScreenOff(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("battery_kill_sensors", enabled).apply()
        if (enabled) {
            ShellUtils.fastCmd("settings put global motion_engine_power_save 1 2>/dev/null")
        } else {
            ShellUtils.fastCmd("settings put global motion_engine_power_save 0 2>/dev/null")
        }
    }

    fun setPrivacySensorsShield(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("battery_privacy_sensors", enabled).apply()
        if (enabled) {
            ShellUtils.fastCmd("cmd sensor_privacy enable 0 all 2>/dev/null")
        } else {
            ShellUtils.fastCmd("cmd sensor_privacy disable 0 all 2>/dev/null")
        }
    }

    fun setForceDoze(enabled: Boolean) {
        if (enabled) {
            ShellUtils.fastCmd("dumpsys deviceidle force-idle deep")
            ShellUtils.fastCmd("echo 0 > /sys/module/lpm_levels/parameters/sleep_disabled 2>/dev/null")
            ShellUtils.fastCmd("echo N > /sys/module/printk/parameters/enabled 2>/dev/null")
        } else {
            ShellUtils.fastCmd("dumpsys deviceidle unforce")
            ShellUtils.fastCmd("echo 0 > /sys/module/lpm_levels/parameters/sleep_disabled 2>/dev/null")
            ShellUtils.fastCmd("echo Y > /sys/module/printk/parameters/enabled 2>/dev/null")
        }
    }

    fun setUsbFastCharge(enabled: Boolean) {
        val value = if (enabled) "1" else "0"
        val paths = listOf(
            "/sys/kernel/fast_charge/force_fast_charge",
            "/sys/module/msm_otg/parameters/fast_chg",
            "/sys/class/power_supply/battery/allow_fast_chg",
            "/sys/module/qpnp_smbcharger/parameters/fast_charge_force"
        )
        for (path in paths) {
            ShellUtils.fastCmd("echo $value > $path 2>/dev/null")
        }
    }

    fun setChargeCurrent(mA: Int) {
        val uA = mA * 1000
        val paths = listOf(
            "/sys/class/power_supply/battery/constant_charge_current_max",
            "/sys/class/power_supply/battery/input_current_limit",
            "/sys/class/power_supply/battery/charge_control_limit",
            "/sys/class/power_supply/main/constant_charge_current_max"
        )
        for (path in paths) {
            ShellUtils.fastCmd("echo $uA > $path 2>/dev/null")
        }
    }
}
