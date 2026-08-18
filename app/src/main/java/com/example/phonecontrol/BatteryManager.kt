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
            // App-level doze
            ShellUtils.fastCmd("dumpsys deviceidle force-idle deep")
            // Kernel-level deep idle/sleep (Device specific nodes)
            ShellUtils.fastCmd("echo 1 > /sys/module/lpm_levels/parameters/sleep_disabled 2>/dev/null")
            ShellUtils.fastCmd("echo N > /sys/module/printk/parameters/enabled 2>/dev/null") // Stop logs to sleep better
        } else {
            ShellUtils.fastCmd("dumpsys deviceidle unforce")
            ShellUtils.fastCmd("echo 0 > /sys/module/lpm_levels/parameters/sleep_disabled 2>/dev/null")
            ShellUtils.fastCmd("echo Y > /sys/module/printk/parameters/enabled 2>/dev/null")
        }
    }

    /**
     * Force fast charging on USB ports (PC/Car).
     */
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

    /**
     * Limits the charging current to reduce heat and prolong battery health.
     * @param mA Current in milliAmperes (e.g., 500, 1500, 3000)
     */
    fun setChargeCurrent(mA: Int) {
        val uA = mA * 1000 // Kernel usually takes microAmperes
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
