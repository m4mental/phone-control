package com.example.phonecontrol

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

    fun getBatteryStats(): BatteryInfo {
        val voltRaw = ShellUtils.runAsRoot("cat ${BATT_PATH}voltage_now").output
        val tempRaw = ShellUtils.runAsRoot("cat ${BATT_PATH}temp").output
        val currRaw = ShellUtils.runAsRoot("cat ${BATT_PATH}current_now").output
        val cycles = ShellUtils.runAsRoot("cat ${BATT_PATH}cycle_count").output
        val health = ShellUtils.runAsRoot("cat ${BATT_PATH}health").output
        
        // Health/Wear estimation (many MTK devices have charge_full and charge_full_design)
        val full = ShellUtils.runAsRoot("cat ${BATT_PATH}charge_full").output.toDoubleOrNull() ?: 5000000.0
        val design = ShellUtils.runAsRoot("cat ${BATT_PATH}charge_full_design").output.toDoubleOrNull() ?: 5000000.0
        val wearLevel = (full / design * 100).toInt().coerceIn(0, 100)

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

    fun setChargingEnabled(enabled: Boolean) {
        val value = if (enabled) "1" else "0"
        ShellUtils.runAsRoot("echo $value > ${BATT_PATH}charging_enabled")
        ShellUtils.runAsRoot("echo $value > ${BATT_PATH}battery_charging_enabled")
        ShellUtils.runAsRoot("echo ${if (enabled) "0" else "1"} > ${BATT_PATH}input_suspend")
    }

    fun setBypassEnabled(enabled: Boolean) {
        val value = if (enabled) "1" else "0"
        ShellUtils.runAsRoot("echo $value > ${BATT_PATH}bypass_charging")
    }

    fun setForceDoze(enabled: Boolean) {
        if (enabled) {
            ShellUtils.runAsRoot("dumpsys deviceidle force-idle deep")
        } else {
            ShellUtils.runAsRoot("dumpsys deviceidle unforce")
        }
    }
}
