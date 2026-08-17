package com.example.phonecontrol

object TweakManager {

    fun setRefreshRate(rate: String) {
        when (rate) {
            "30Hz" -> {
                ShellUtils.fastCmd("settings put system min_refresh_rate 30.0")
                ShellUtils.fastCmd("settings put system peak_refresh_rate 30.0")
            }
            "60Hz" -> {
                ShellUtils.fastCmd("settings put system min_refresh_rate 60.0")
                ShellUtils.fastCmd("settings put system peak_refresh_rate 60.0")
            }
            "90Hz" -> {
                ShellUtils.fastCmd("settings put system min_refresh_rate 90.0")
                ShellUtils.fastCmd("settings put system peak_refresh_rate 90.0")
            }
            "120Hz" -> {
                ShellUtils.fastCmd("settings put system min_refresh_rate 120.0")
                ShellUtils.fastCmd("settings put system peak_refresh_rate 120.0")
            }
            else -> {
                ShellUtils.fastCmd("settings put system min_refresh_rate 30.0")
                ShellUtils.fastCmd("settings put system peak_refresh_rate 120.0")
            }
        }
    }

    fun setTouchBoost(enabled: Boolean) {
        if (enabled) {
            ShellUtils.fastCmd("settings put system touch_responsiveness 1")
            ShellUtils.fastCmd("echo 1 > /proc/touchpanel/game_switch_enable 2>/dev/null")
            ShellUtils.fastCmd("service call input 5 i32 1")
        } else {
            ShellUtils.fastCmd("settings put system touch_responsiveness 0")
        }
    }

    fun applyBatterySaver() {
        // Optimization: Use a static list of policies if possible, or only detect once
        // For now, let's just apply to common ones to save battery on detection
        for (i in 0..7) {
            ShellUtils.fastCmd("echo powersave > /sys/devices/system/cpu/cpufreq/policy$i/scaling_governor 2>/dev/null")
        }
        ShellUtils.fastCmd("echo 0 > /sys/kernel/gpu/gpu_max_clock 2>/dev/null")
    }

    fun applyBalance() {
        for (i in 0..7) {
            ShellUtils.fastCmd("echo schedutil > /sys/devices/system/cpu/cpufreq/policy$i/scaling_governor 2>/dev/null")
        }
    }

    fun applyPerformance() {
        for (i in 0..7) {
            ShellUtils.fastCmd("echo performance > /sys/devices/system/cpu/cpufreq/policy$i/scaling_governor 2>/dev/null")
        }
        ShellUtils.fastCmd("echo 1 > /sys/kernel/gpu/gpu_max_clock 2>/dev/null")
    }
}
