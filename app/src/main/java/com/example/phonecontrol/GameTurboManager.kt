package com.example.phonecontrol

import android.content.Context

object GameTurboManager {

    private const val PREFS_NAME = "game_turbo_prefs"
    private const val KEY_GAMES = "game_packages"

    fun getTurboGames(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_GAMES, emptySet()) ?: emptySet()
    }

    fun saveTurboGames(context: Context, games: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_GAMES, games).apply()
    }

    fun applyTouchSampling(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("game_turbo_touch_sampling", enabled).apply()
        if (enabled) {
            ShellUtils.fastCmd("settings put secure touch_game_mode 1 2>/dev/null")
            ShellUtils.fastCmd("settings put system touch_game_mode 1 2>/dev/null")
        } else {
            ShellUtils.fastCmd("settings put secure touch_game_mode 0 2>/dev/null")
            ShellUtils.fastCmd("settings put system touch_game_mode 0 2>/dev/null")
        }
    }
}
