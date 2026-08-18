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
        // Read multiple files at once to reduce shell overhead
        val files = listOf("voltage_now", "temp", "current_now", "cycle_count", "health", "charge_full", "charge_full_design")
        val cmd = files.joinToString(" && ") { "cat ${BATT_PATH}$it" }
        val result = ShellUtils.runAsRoot(cmd)
        val lines = result.output.split("\n")

        val voltRaw = lines.getOrNull(0) ?: "0"
        val tempRaw = lines.getOrNull(1) ?: "0"
        val currRaw = lines.getOrNull(2) ?: "0"
        val cycles = lines.getOrNull(3) ?: "0"
        val health = lines.getOrNull(4) ?: "Good"
        val full = lines.getOrNull(5)?.toDoubleOrNull() ?: 5000000.0
        val design = lines.getOrNull(6)?.toDoubleOrNull() ?: 5000000.0
        
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
        val commands = listOf(
            "echo $value > ${BATT_PATH}charging_enabled",
            "echo $value > ${BATT_PATH}battery_charging_enabled",
            "echo ${if (enabled) "0" else "1"} > ${BATT_PATH}input_suspend"
        )
        ShellUtils.runCommandsAsRoot(commands)
    }

    fun setBypassEnabled(enabled: Boolean) {
        val value = if (enabled) "1" else "0"
        ShellUtils.fastCmd("echo $value > ${BATT_PATH}bypass_charging")
    }

    fun setForceDoze(enabled: Boolean) {
        if (enabled) {
            ShellUtils.fastCmd("dumpsys deviceidle force-idle deep")
        } else {
            ShellUtils.fastCmd("dumpsys deviceidle unforce")
        }
    }
}
