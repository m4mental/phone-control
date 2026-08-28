package com.example.phonecontrol

import android.content.Context

object DpiManager {
    private const val PREFS_NAME = "dpi_prefs"

    fun saveAppDpi(context: Context, packageName: String, dpi: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(packageName, dpi).apply()
    }

    fun removeAppDpi(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(packageName).apply()
    }

    fun getAppDpi(context: Context, packageName: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(packageName, -1)
    }

    fun getAllConfigs(context: Context): Map<String, *> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
    }
}
