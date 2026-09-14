package com.example.phonecontrol

import android.content.Context
import android.util.Log

object GameTurboManager {

    private const val PREFS_NAME = "game_turbo_prefs"
    private const val KEY_GAMES = "game_packages"

    @Volatile var isGameTurboActive: Boolean = false
    @Volatile private var wasHudAutoStarted: Boolean = false
    @Volatile private var wasDndAutoStarted: Boolean = false

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

    /**
     * Beast Engine Dynamic Scaling:
     * - Big Cores (6-7): 1.2 GHz min to 2.8 GHz max (schedutil)
     * - Little Cores (0-5): 950 MHz min to 2.0 GHz max (schedutil)
     * - 0ms scale-up response on combat encounter (up_rate_limit_us = 0, rate_limit_us = 0)
     * - 20ms hold down to prevent stuttering/jitter (down_rate_limit_us = 20000)
     * - MediaTek FPSGO & GED frame scheduler boost
     * - Optional Auto 120Hz, Auto Floating HUD, Gaming DND
     */
    fun applyGameTurbo(context: Context, activate: Boolean) {
        val turboPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isMasterEnabled = turboPrefs.getBoolean("game_turbo_enabled", false)

        if (activate) {
            if (!isMasterEnabled) return
            if (isGameTurboActive) return
            isGameTurboActive = true

            Log.d("GameTurbo", "⚡ Beast Mode ACTIVATING: 1.2G-2.8G Big, 950M-2.0G Little, 0ms latency, FPSGO/GED")

            // 1. Dynamic Frequency Range (Dimensity 7200 Pro)
            TweakManager.applyCustomAppProfile(
                littleMin = 950000,
                littleMax = 2000000,
                bigMin = 1200000,
                bigMax = 2800000,
                governor = "schedutil"
            )

            // 2. Ultra-low Latency Governor Tuning (0ms Scale-Up Burst, 20ms Hold-Down) + FPSGO/GED Boost
            val kernelBoostCmds = """
                for p in /sys/devices/system/cpu/cpufreq/policy* /sys/devices/system/cpu/cpufreq; do
                    chmod 666 ${'$'}p/schedutil/rate_limit_us 2>/dev/null
                    chmod 666 ${'$'}p/schedutil/up_rate_limit_us 2>/dev/null
                    chmod 666 ${'$'}p/schedutil/down_rate_limit_us 2>/dev/null
                    echo 0 > ${'$'}p/schedutil/rate_limit_us 2>/dev/null
                    echo 0 > ${'$'}p/schedutil/up_rate_limit_us 2>/dev/null
                    echo 20000 > ${'$'}p/schedutil/down_rate_limit_us 2>/dev/null
                done
                echo 1 > /sys/module/ged/parameters/boost_gpu_enable 2>/dev/null
                echo 1 > /sys/module/ged/parameters/ged_boost_enable 2>/dev/null
                echo 1 > /sys/module/ged/parameters/ged_smart_boost 2>/dev/null
                echo 1 > /sys/module/ged/parameters/is_GED_KPI_enabled 2>/dev/null
                echo 1 > /sys/module/fbt_cpu/parameters/bsp_fbt_opt 2>/dev/null
            """.trimIndent()
            ShellUtils.fastCmd(kernelBoostCmds)

            // 3. Touch Sampling Boost
            if (turboPrefs.getBoolean("game_turbo_touch_sampling", false)) {
                applyTouchSampling(context, true)
            }

            // 4. Auto 120Hz Refresh Rate
            if (turboPrefs.getBoolean("game_turbo_auto_120hz", true)) {
                TweakManager.setRefreshRate("120Hz")
            }

            // 5. Auto Floating HUD
            if (turboPrefs.getBoolean("game_turbo_auto_hud", false)) {
                if (!FloatingHudService.isRunning) {
                    wasHudAutoStarted = true
                    FloatingHudService.start(context)
                }
            }

            // 6. Gaming DND (Block Heads-up banner notifications)
            if (turboPrefs.getBoolean("game_turbo_dnd", false)) {
                ShellUtils.fastCmd("settings put global heads_up_notifications_enabled 0 2>/dev/null")
                wasDndAutoStarted = true
            }

            // 7. Auto Gaming RAM Engine (Instant Compaction + Swappiness 30 for Zero Combat Stutters)
            TweakManager.compactZram()
            ShellUtils.fastCmd("sysctl -w vm.swappiness=30 2>/dev/null")
            ShellUtils.fastCmd("sysctl -w vm.vfs_cache_pressure=150 2>/dev/null")
            TweakManager.applyLmkTuning("rbProfilePerformance")
            if (!isGameTurboActive) return
            isGameTurboActive = false

            Log.d("GameTurbo", "⚡ Beast Mode DEACTIVATING -> Restoring previous state")

            // 1. Reset Governor Rate Limits to normal
            val kernelResetCmds = """
                for p in /sys/devices/system/cpu/cpufreq/policy* /sys/devices/system/cpu/cpufreq; do
                    echo 1000 > ${'$'}p/schedutil/rate_limit_us 2>/dev/null
                    echo 500 > ${'$'}p/schedutil/up_rate_limit_us 2>/dev/null
                    echo 1000 > ${'$'}p/schedutil/down_rate_limit_us 2>/dev/null
                done
            """.trimIndent()
            ShellUtils.fastCmd(kernelResetCmds)

            // 2. Restore Gaming DND
            if (wasDndAutoStarted) {
                ShellUtils.fastCmd("settings put global heads_up_notifications_enabled 1 2>/dev/null")
                wasDndAutoStarted = false
            }

            // 3. Auto Stop HUD if it was started by Game Turbo
            if (wasHudAutoStarted) {
                FloatingHudService.stop(context)
                wasHudAutoStarted = false
            }

            // 4. Restore Refresh Rate
            val mainPrefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            val savedHzKey = mainPrefs.getString("screen_refresh", "rbHzDynamic")
            val targetRate = when (savedHzKey) {
                "rbHz120" -> "120Hz"
                "rbHz90" -> "90Hz"
                "rbHz60" -> "60Hz"
                else -> "Default"
            }
            TweakManager.setRefreshRate(targetRate)

            // 5. Restore Touch Sampling
            if (turboPrefs.getBoolean("game_turbo_touch_sampling", false)) {
                applyTouchSampling(context, false)
            }

            // 6. Restore RAM & VM Swappiness according to user's saved profile
            val ramProfile = mainPrefs.getString("ram_profile", "rbProfileBalance") ?: "rbProfileBalance"
            when (ramProfile) {
                "rbProfileMultitasking" -> {
                    ShellUtils.fastCmd("sysctl -w vm.swappiness=160 2>/dev/null")
                    ShellUtils.fastCmd("sysctl -w vm.vfs_cache_pressure=50 2>/dev/null")
                    TweakManager.applyLmkTuning("rbProfileMultitasking")
                }
                "rbProfilePerformance" -> {
                    ShellUtils.fastCmd("sysctl -w vm.swappiness=30 2>/dev/null")
                    ShellUtils.fastCmd("sysctl -w vm.vfs_cache_pressure=150 2>/dev/null")
                    TweakManager.applyLmkTuning("rbProfilePerformance")
                }
                else -> {
                    ShellUtils.fastCmd("sysctl -w vm.swappiness=100 2>/dev/null")
                    ShellUtils.fastCmd("sysctl -w vm.vfs_cache_pressure=100 2>/dev/null")
                    TweakManager.applyLmkTuning("rbProfileBalance")
                }
            }
        }
    }
}
