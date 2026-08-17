package com.example.phonecontrol

import android.content.Context

object PerAppManager {
    private const val PREFS_NAME = "per_app_prefs"

    fun saveConfig(context: Context, packageName: String, mode: String, fps: String, thermal: String, touch: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(packageName, "$mode|$fps|$thermal|$touch").apply()
    }

    fun removeConfig(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(packageName).apply()
    }

    data class AppConfig(val mode: String, val fps: String, val thermal: String, val touch: String)

    fun getConfig(context: Context, packageName: String): AppConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val data = prefs.getString(packageName, null) ?: return null
        val parts = data.split("|")
        return if (parts.size >= 2) {
            AppConfig(
                parts[0], 
                parts[1], 
                parts.getOrNull(2) ?: "Default",
                parts.getOrNull(3) ?: "Off"
            )
        } else null
    }

    fun getAllConfigs(context: Context): Map<String, *> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
    }
}
