package com.example.phonecontrol

import android.content.Context

object PerAppManager {
    private const val PREFS_NAME = "per_app_prefs"

    data class AppConfig(
        val mode: String,
        val fps: String,
        val thermal: String = "Default",
        val touch: String = "Off",
        val bypassCharging: Boolean = false,
        val autoDnd: Boolean = false
    )

    fun saveConfig(
        context: Context,
        packageName: String,
        mode: String,
        fps: String,
        thermal: String = "Default",
        touch: String = "Off",
        bypassCharging: Boolean = false,
        autoDnd: Boolean = false
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(packageName, "$mode|$fps|$thermal|$touch|$bypassCharging|$autoDnd").apply()
    }

    fun saveConfig(context: Context, packageName: String, config: AppConfig) {
        saveConfig(
            context,
            packageName,
            config.mode,
            config.fps,
            config.thermal,
            config.touch,
            config.bypassCharging,
            config.autoDnd
        )
    }

    fun removeConfig(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(packageName).apply()
    }

    fun getConfig(context: Context, packageName: String): AppConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val data = prefs.getString(packageName, null) ?: return null
        val parts = data.split("|")
        return if (parts.size >= 2) {
            AppConfig(
                mode = parts[0],
                fps = parts[1],
                thermal = parts.getOrNull(2) ?: "Default",
                touch = parts.getOrNull(3) ?: "Off",
                bypassCharging = parts.getOrNull(4)?.toBooleanStrictOrNull() ?: false,
                autoDnd = parts.getOrNull(5)?.toBooleanStrictOrNull() ?: false
            )
        } else null
    }

    fun getAllConfigs(context: Context): Map<String, *> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
    }

    fun getModePriority(mode: String): Int {
        return when (mode.trim()) {
            "Performance" -> 3
            "Balance" -> 2
            "Power Saver" -> 1
            else -> 0
        }
    }

    fun getFpsPriority(fps: String): Int {
        return when (fps.trim()) {
            "120Hz" -> 3
            "90Hz" -> 2
            "60Hz" -> 1
            else -> 0
        }
    }

    /**
     * Resolves multi-app conflicts by selecting the highest required performance tier
     * and aggregating feature flags (Bypass Charging, DND, Thermal Bypass, Touch Boost).
     */
    fun mergeConfigs(configs: List<AppConfig>): AppConfig? {
        val activeRules = configs.filter {
            it.mode != "Auto" || it.fps != "Auto Switch" || it.thermal == "Disabled" || it.touch == "On" || it.bypassCharging || it.autoDnd
        }
        if (activeRules.isEmpty()) return null

        val highestMode = activeRules.maxByOrNull { getModePriority(it.mode) }?.mode ?: "Auto"
        val highestFps = activeRules.maxByOrNull { getFpsPriority(it.fps) }?.fps ?: "Auto Switch"
        val thermal = if (activeRules.any { it.thermal == "Disabled" }) "Disabled" else "Default"
        val touch = if (activeRules.any { it.touch == "On" }) "On" else "Off"
        val bypass = activeRules.any { it.bypassCharging }
        val dnd = activeRules.any { it.autoDnd }

        return AppConfig(
            mode = highestMode,
            fps = highestFps,
            thermal = thermal,
            touch = touch,
            bypassCharging = bypass,
            autoDnd = dnd
        )
    }
}
