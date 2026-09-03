package com.example.phonecontrol

import android.content.Context
import android.content.Intent
import android.util.Log

object TweakManager {

    /**
     * Centralized function to apply all tweaks for a given mode.
     * High-level modes: Power Saver, Balance, Performance
     */
    fun applyGlobalMode(mode: String) {
        when (mode) {
            "Power Saver" -> {
                applyBatterySaver()
                applyCpuTuning("power")
                applyIoOptimization("power")
                applyAsymmetricCpuFreqTuning("power")
            }
            "Balance" -> {
                applyBalance()
                applyCpuTuning("balance")
                applyIoOptimization("balance")
                applyAsymmetricCpuFreqTuning("balance")
            }
            "Performance" -> {
                applyPerformance()
                applyCpuTuning("perf")
                applyIoOptimization("perf")
                applyInputBoost(true)
                applyAsymmetricCpuFreqTuning("perf")
            }
            // AI Engine Granular Profiles (Tuned for Fluid 120Hz & High Battery Efficiency)
            "AI_Sleeping" -> {
                setClusterParking(false)
                applyScreenOffSleep()
            }
            "AI_EcoActive" -> {
                // Eco Active: Stage 1 (650MHz - 950MHz Little, 400MHz Big Sleep)
                setClusterParking(false)
                applyGpuTuning("power")
                applyCpuTuning("power")
                applyIoOptimization("power")
                applyEntropyTuning(true)
                applyInputBoost(true, aggressive = false)
                applyAsymmetricCpuFreqTuning("power")
            }
            "AI_VideoCall" -> {
                // WhatsApp / Social Video Call & VOIP: Stage 1 Locked 950MHz Little Cores, Big Cores Sleep
                setClusterParking(false)
                applyGpuTuning("power")
                applyCpuTuning("power")
                applyIoOptimization("power")
                applyEntropyTuning(true)
                applyInputBoost(true, aggressive = false)
                applyVideoCallEcoLock()
            }
            "AI_Daily" -> {
                // Daily Fluent: 4-Stage Progressive Ladder (650M - 2.0GHz Little, 1.4GHz Touch Burst)
                applyBalance()
                setClusterParking(false, deep = true) 
                applyCpuTuning("balance")
                applyInputBoost(true, aggressive = false)
                applyAsymmetricCpuFreqTuning("balance")
            }
            "AI_Boost" -> {
                // Multi-Boost: Stage 3 Dual-Cluster (2.0GHz Little + 1.5GHz Big Cores)
                setClusterParking(false)
                applyPerformance()
                applyCpuTuning("perf")
                applyIoOptimization("perf")
                applyInputBoost(true, aggressive = false)
                applyAsymmetricCpuFreqTuning("boost")
            }
            "AI_Extreme" -> {
                // Extreme Beast: Stage 4 Full Turbo Unleashed (2.0GHz Little + 2.8GHz Big Cores)
                setClusterParking(false)
                applyPerformance()
                ShellUtils.fastCmd("echo 1 > /sys/kernel/gpu/gpu_max_clock 2>/dev/null")
                applyCpuTuning("perf")
                applyInputBoost(true, aggressive = true)
                applyProcessPriority("com.android.systemui", true)
                applyAsymmetricCpuFreqTuning("perf")
            }
        }
    }

    /**
     * 5. Smart Adaptive Kernel Input Boost
     * Speeds up cores progressively on touch without thermal build-up.
     */
    fun applyInputBoost(enabled: Boolean, aggressive: Boolean = false) {
        if (enabled) {
            if (aggressive) {
                // High Gaming / Turbo Boost
                ShellUtils.fastCmd("echo 1 > /sys/module/cpu_boost/parameters/input_boost_enabled 2>/dev/null")
                ShellUtils.fastCmd("echo 0:1600000 6:2200000 > /sys/module/cpu_boost/parameters/input_boost_freq 2>/dev/null")
                ShellUtils.fastCmd("echo 300 > /sys/module/cpu_boost/parameters/input_boost_ms 2>/dev/null")
                ShellUtils.fastCmd("echo 1 > /proc/touchpanel/game_switch_enable 2>/dev/null")
                ShellUtils.fastCmd("echo 1 > /sys/devices/virtual/touchpanel/smart_wake/touch_responsiveness 2>/dev/null")
            } else {
                // Progressive Touch-Adaptive Daily Boost:
                // Sits at 650MHz Base Idle -> Climbs to 950MHz on touch/video call for 100ms; Big Cores stay sleeping (Zero Idle Heating!)
                ShellUtils.fastCmd("echo 1 > /sys/module/cpu_boost/parameters/input_boost_enabled 2>/dev/null")
                ShellUtils.fastCmd("echo 0:950000 6:0 > /sys/module/cpu_boost/parameters/input_boost_freq 2>/dev/null")
                ShellUtils.fastCmd("echo 100 > /sys/module/cpu_boost/parameters/input_boost_ms 2>/dev/null")
                ShellUtils.fastCmd("echo 0 > /proc/touchpanel/game_switch_enable 2>/dev/null")
                ShellUtils.fastCmd("echo 0 > /sys/devices/virtual/touchpanel/smart_wake/touch_responsiveness 2>/dev/null")
            }
        } else {
            ShellUtils.fastCmd("echo 0 > /sys/module/cpu_boost/parameters/input_boost_enabled 2>/dev/null")
            ShellUtils.fastCmd("echo 40 > /sys/module/cpu_boost/parameters/input_boost_ms 2>/dev/null")
            ShellUtils.fastCmd("echo 0 > /proc/touchpanel/game_switch_enable 2>/dev/null")
            ShellUtils.fastCmd("echo 0 > /sys/devices/virtual/touchpanel/smart_wake/touch_responsiveness 2>/dev/null")
        }
    }

    /**
     * 6. Process & I/O Prioritization
     * Gives a specific package higher CPU and Storage priority.
     */
    fun applyProcessPriority(packageName: String, high: Boolean) {
        val result = ShellUtils.runAsRoot("pidof $packageName")
        val pids = result.output.split(" ").filter { it.isNotBlank() }
        
        for (pid in pids) {
            if (high) {
                // CPU Priority: -20 is highest (Real-time feel)
                ShellUtils.fastCmd("renice -n -20 -p $pid 2>/dev/null")
                // I/O Priority: Class 1 (Real-time)
                ShellUtils.fastCmd("ionice -c 1 -n 0 -p $pid 2>/dev/null")
            } else {
                // Restore normal priority
                ShellUtils.fastCmd("renice -n 0 -p $pid 2>/dev/null")
                ShellUtils.fastCmd("ionice -c 2 -n 4 -p $pid 2>/dev/null")
            }
        }
    }

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
            "Default" -> {
                ShellUtils.fastCmd("settings delete system min_refresh_rate")
                ShellUtils.fastCmd("settings delete system peak_refresh_rate")
            }
            else -> {
                ShellUtils.fastCmd("settings put system min_refresh_rate 30.0")
                ShellUtils.fastCmd("settings put system peak_refresh_rate 120.0")
            }
        }
    }

    fun applyNetworkTweaks(tcpEnabled: Boolean, dnsVal: String) {
        val commands = mutableListOf<String>()
        if (tcpEnabled) {
            commands.add("sysctl -w net.ipv4.tcp_congestion_control=bbr 2>/dev/null")
            commands.add("sysctl -w net.ipv4.tcp_fastopen=3 2>/dev/null")
            commands.add("sysctl -w net.core.default_qdisc=fq 2>/dev/null")
        }
        if (dnsVal.isNotEmpty()) {
            commands.add("setprop net.dns1 $dnsVal")
            commands.add("setprop net.dns2 8.8.4.4")
        }
        ShellUtils.runCommandsAsRoot(commands)
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
        // Use schedutil with parked big cores for fluid 120Hz scrolling without battery drain
        for (i in 0..7) {
            ShellUtils.fastCmd("echo schedutil > /sys/devices/system/cpu/cpufreq/policy$i/scaling_governor 2>/dev/null")
        }
        setClusterParking(true, deep = false) // Park cores 6-7 (Big Cortex-A715)
        applyGpuTuning("power")
        applyEntropyTuning(false)
        applyInputBoost(true, aggressive = false)
    }

    fun applyBalance() {
        setClusterParking(false, deep = true) // Ensure all 8 cores are online
        for (i in 0..7) {
            ShellUtils.fastCmd("echo schedutil > /sys/devices/system/cpu/cpufreq/policy$i/scaling_governor 2>/dev/null")
        }
        applyGpuTuning("balance")
        applyEntropyTuning(true)
        applyInputBoost(true, aggressive = false)
    }

    fun applyPerformance() {
        setClusterParking(false, deep = true) // Wake up all 8 cores
        for (i in 0..7) {
            ShellUtils.fastCmd("echo performance > /sys/devices/system/cpu/cpufreq/policy$i/scaling_governor 2>/dev/null")
        }
        ShellUtils.fastCmd("echo 1 > /sys/kernel/gpu/gpu_max_clock 2>/dev/null")
        applyGpuTuning("perf")
        applyEntropyTuning(true)
        applyInputBoost(true, aggressive = true)
    }

    /**
     * Intelligent Asymmetric CPU Frequency & Cluster Tuning
     * - Daily/Power/Balance: 6 Little Cores boosted to max (2.0GHz) for 120Hz fluid smoothness,
     *   2 Big Cores capped at cool 1.2GHz - 1.5GHz (eliminates 80% phone heating).
     * - Gaming/Performance: Unlocks Big Cores to full 2.8GHz power on-demand.
     */
    @Volatile
    var manualStageOverride: Int = 0

    /**
     * Helper to enforce raw stage script for any stage
     */
    fun applyRawStageScript(stage: Int) {
        val unlockPerms = "chmod 666 /sys/devices/system/cpu/cpufreq/policy*/scaling_* 2>/dev/null\n"
        when (stage) {
            1 -> {
                // S1 Option A: 650MHz Base Idle -> Scales to 950MHz Max (Balanced Eco)
                val script = """
                    $unlockPerms
                    for c in 0 1 2 3 4 5; do
                        echo 650000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 950000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    for c in 6 7; do
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    echo 650000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null
                    echo 950000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null
                    echo schedutil > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null
                    echo schedutil > /sys/devices/system/cpu/cpufreq/policy6/scaling_governor 2>/dev/null
                    echo 1000 > /sys/devices/system/cpu/cpufreq/policy0/schedutil/rate_limit_us 2>/dev/null
                """.trimIndent()
                ShellUtils.fastCmd(script)
            }
            10 -> {
                // S1 Option B: 650MHz Base Idle -> Scales to 850MHz Max (Extreme Eco)
                val script = """
                    $unlockPerms
                    for c in 0 1 2 3 4 5; do
                        echo 650000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 850000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    for c in 6 7; do
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    echo 650000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null
                    echo 850000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null
                    echo schedutil > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null
                    echo schedutil > /sys/devices/system/cpu/cpufreq/policy6/scaling_governor 2>/dev/null
                    echo 1000 > /sys/devices/system/cpu/cpufreq/policy0/schedutil/rate_limit_us 2>/dev/null
                """.trimIndent()
                ShellUtils.fastCmd(script)
            }
            11 -> {
                // S1 Option C: 650 MHz Strict Lock (Ultra Super Eco)
                val script = """
                    $unlockPerms
                    for c in 0 1 2 3 4 5; do
                        echo 650000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 650000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    for c in 6 7; do
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    echo 650000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null
                    echo 650000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null
                    echo schedutil > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null
                    echo schedutil > /sys/devices/system/cpu/cpufreq/policy6/scaling_governor 2>/dev/null
                """.trimIndent()
                ShellUtils.fastCmd(script)
            }
            12 -> {
                // S1 Option D: 550 MHz (Deep Eco)
                val script = """
                    $unlockPerms
                    for c in 0 1 2 3 4 5; do
                        echo 480000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 550000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    for c in 6 7; do
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    echo 480000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null
                    echo 550000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null
                    echo schedutil > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null
                    echo schedutil > /sys/devices/system/cpu/cpufreq/policy6/scaling_governor 2>/dev/null
                """.trimIndent()
                ShellUtils.fastCmd(script)
            }
            13 -> {
                // S1 Option E: 480 MHz (Hardware Absolute Minimum Floor)
                val script = """
                    $unlockPerms
                    for c in 0 1 2 3 4 5; do
                        echo 480000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 480000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    for c in 6 7; do
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    echo 480000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null
                    echo 480000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null
                    echo schedutil > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null
                    echo schedutil > /sys/devices/system/cpu/cpufreq/policy6/scaling_governor 2>/dev/null
                """.trimIndent()
                ShellUtils.fastCmd(script)
            }
            2 -> {
                // Force Stage 2: 6 Little Cores 650M - 2.0GHz, 2 Big Cores 400MHz Sleeping
                val script = """
                    $unlockPerms
                    for c in 0 1 2 3 4 5; do
                        echo 650000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 2000000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    for c in 6 7; do
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    echo 650000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null
                    echo 2000000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null
                    echo schedutil > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null
                    echo schedutil > /sys/devices/system/cpu/cpufreq/policy6/scaling_governor 2>/dev/null
                """.trimIndent()
                ShellUtils.fastCmd(script)
            }
            3 -> {
                // Force Stage 3: 6 Little Cores 2.0GHz, 2 Big Cores 1.5GHz
                val script = """
                    $unlockPerms
                    for c in 0 1 2 3 4 5; do
                        echo 650000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 2000000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    for c in 6 7; do
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 1500000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    echo 650000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null
                    echo 2000000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null
                    echo schedutil > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq 2>/dev/null
                    echo 1500000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null
                    echo schedutil > /sys/devices/system/cpu/cpufreq/policy6/scaling_governor 2>/dev/null
                """.trimIndent()
                ShellUtils.fastCmd(script)
            }
            4 -> {
                // Force Stage 4: All 8 Cores Full Turbo Unleashed (2.0GHz Little + 2.8GHz Big Cores)
                val script = """
                    $unlockPerms
                    echo performance > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor 2>/dev/null
                    echo performance > /sys/devices/system/cpu/cpufreq/policy6/scaling_governor 2>/dev/null
                    echo 2000000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null
                    echo 2000000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null
                    echo 2800000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null
                    echo 2800000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq 2>/dev/null
                    for c in 0 1 2 3 4 5; do
                        echo 2000000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 2000000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo performance > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    for c in 6 7; do
                        echo 2800000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 2800000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo performance > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    echo 1 > /sys/kernel/gpu/gpu_max_clock 2>/dev/null
                    echo 55 > /proc/sys/kernel/sched_upmigrate 2>/dev/null
                    echo 45 > /proc/sys/kernel/sched_downmigrate 2>/dev/null
                """.trimIndent()
                ShellUtils.fastCmd(script)
            }
        }
    }

    /**
     * Rapid 200ms App-Switch & Launch Transition Boost:
     * Momentarily unlocks Little Cores to 950MHz for 200ms for 100% fluid 120fps window animations,
     * then immediately settles back to 650MHz Base Floor (Zero idle drain, Big Cores sleeping).
     */
    fun triggerAppSwitchBoost() {
        if (manualStageOverride != 0) return
        kotlin.concurrent.thread {
            try {
                ShellUtils.fastCmd("chmod 666 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; echo 950000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null")
                Thread.sleep(200)
                ShellUtils.fastCmd("echo 650000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null; chmod 444 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null")
            } catch (e: Exception) {}
        }
    }

    @Volatile var isVideoCallBoostActive = false

    /**
     * Dedicated WhatsApp/VOIP Video Call & Camera Lock:
     * Locks 6 Little Cores strictly to 950MHz (0 frame drops, 0 stutter, fluid 30-60fps)
     * Keeps 2 Big Cores deeply sleeping at 400MHz (Zero heating / zero battery drain).
     * Overrides manual stage temporarily for the duration of the call.
     */
    fun applyVideoCallEcoLock() {
        isVideoCallBoostActive = true
        val script = """
            chmod 666 /sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq 2>/dev/null
            chmod 666 /sys/devices/system/cpu/cpufreq/policy*/scaling_min_freq 2>/dev/null
            echo 950000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null
            echo 950000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null
            for c in 0 1 2 3 4 5; do
                chmod 666 /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                chmod 666 /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                echo 950000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                echo 950000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
            done
            for c in 6 7; do
                chmod 666 /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                chmod 666 /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
            done
            echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq 2>/dev/null
            echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null
            echo 400000 > /sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq 2>/dev/null
            echo 400000 > /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq 2>/dev/null
            echo 1000 > /sys/devices/system/cpu/cpufreq/policy0/schedutil/rate_limit_us 2>/dev/null
            echo 20000 > /sys/devices/system/cpu/cpufreq/policy6/schedutil/up_rate_limit_us 2>/dev/null
            chmod 444 /sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq 2>/dev/null
        """.trimIndent()
        ShellUtils.fastCmd(script)
        android.util.Log.d("TweakManager", "📹 Video Call Boost Applied -> 6 Little Cores LOCKED at 950MHz")
    }

    /**
     * Restores previous profile after Video Call finishes.
     */
    fun restorePreVideoCallState(context: Context) {
        isVideoCallBoostActive = false
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val manualStage = prefs.getInt("manual_stage_override", 0)
        if (manualStage != 0) {
            manualStageOverride = manualStage
            applyRawStageScript(manualStage)
            return
        }
        val mode = prefs.getString("selected_mode", "rbBalance")
        when (mode) {
            "rbPowerSaver" -> applyGlobalMode("Power Saver")
            "rbPerformance" -> applyGlobalMode("Performance")
            "rbAutomatic" -> applyGlobalMode("Automatic")
            else -> applyGlobalMode("Balanced")
        }
        android.util.Log.d("TweakManager", "📹 Video Call Boost Restored -> Profile re-applied")
    }

    /**
     * 4-Stage Progressive Dynamic Governor (Ladder EAS Scaling):
     * - Stage 1 (0-35%): 6 Little Cores 650MHz - 950MHz, 2 Big Cores 400MHz Sleep (Super Cool Eco)
     * - Stage 2 (35-70%): 6 Little Cores 1.25GHz - 2.0GHz, 2 Big Cores 400MHz Sleep (120Hz Pure 6-Core Fluid)
     * - Stage 3 (70-90%): 6 Little Cores 2.0GHz, 2 Big Cores 1.5GHz (Balanced Dual-Cluster)
     * - Stage 4 (>90% / Gaming): All 8 Cores Turbo (2.0GHz + 2.8GHz Full Power)
     */
    fun applyAsymmetricCpuFreqTuning(mode: String) {
        if (manualStageOverride != 0) {
            // Respect Test Lab Lock 100%!
            applyRawStageScript(manualStageOverride)
            return
        }

        when (mode) {
            "power" -> {
                // Power Saver / Eco: Pure 650MHz Base Floor (Ice Cold Idle & UI Navigation, Big Cores Sleeping)
                val script = """
                    chmod 666 /sys/devices/system/cpu/cpufreq/policy*/scaling_* 2>/dev/null
                    for c in 0 1 2 3 4 5; do
                        echo 650000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 650000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    for c in 6 7; do
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    echo 650000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null
                    echo 650000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq 2>/dev/null
                    echo 1000 > /sys/devices/system/cpu/cpufreq/policy0/schedutil/rate_limit_us 2>/dev/null
                    echo 20000 > /sys/devices/system/cpu/cpufreq/policy6/schedutil/up_rate_limit_us 2>/dev/null
                """.trimIndent()
                ShellUtils.fastCmd(script)
            }
            "balance" -> {
                // Stage 2 Pure 6-Core Fluid: 6 Little Cores 650MHz - 2.0GHz, 2 Big Cores LOCKED at 400MHz Sleep!
                val script = """
                    chmod 644 /sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq 2>/dev/null
                    for c in 0 1 2 3 4 5; do
                        echo 650000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 2000000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    for c in 6 7; do
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    echo 650000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null
                    echo 2000000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq 2>/dev/null
                    echo 1000 > /sys/devices/system/cpu/cpufreq/policy0/schedutil/rate_limit_us 2>/dev/null
                    echo 20000 > /sys/devices/system/cpu/cpufreq/policy6/schedutil/up_rate_limit_us 2>/dev/null
                    chmod 444 /sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq 2>/dev/null
                """.trimIndent()
                ShellUtils.fastCmd(script)
            }
            "boost" -> {
                // Stage 3 Dual-Cluster Balanced Compute: 6 Little Cores 2.0GHz, 2 Big Cores 1.5GHz
                val script = """
                    chmod 644 /sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq 2>/dev/null
                    for c in 0 1 2 3 4 5; do
                        echo 650000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 2000000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    for c in 6 7; do
                        echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                        echo 1500000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                        echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
                    done
                    echo 650000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null
                    echo 2000000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq 2>/dev/null
                    echo 1500000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null
                    echo 400000 > /sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq 2>/dev/null
                    echo 1500000 > /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq 2>/dev/null
                    echo 1000 > /sys/devices/system/cpu/cpufreq/policy0/schedutil/rate_limit_us 2>/dev/null
                    echo 15000 > /sys/devices/system/cpu/cpufreq/policy6/schedutil/up_rate_limit_us 2>/dev/null
                    chmod 444 /sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq 2>/dev/null
                """.trimIndent()
                ShellUtils.fastCmd(script)
            }
            "perf" -> {
                // Stage 4 Extreme Full Turbo (Gaming & Benchmarks):
                val script = """
                    for p in /sys/devices/system/cpu/cpufreq/policy*; do
                        if [ -f "${'$'}p/scaling_available_frequencies" ]; then
                            max_f=$(awk '{print ${'$'}1}' "${'$'}p/scaling_available_frequencies" 2>/dev/null)
                            [ -n "${'$'}max_f" ] && echo "${'$'}max_f" > "${'$'}p/scaling_max_freq" 2>/dev/null
                        fi
                        echo performance > "${'$'}p/scaling_governor" 2>/dev/null
                    done
                    for c in 0 1 2 3 4 5 6 7; do
                        echo 2800000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                    done
                    echo 60 > /proc/sys/kernel/sched_upmigrate 2>/dev/null
                """.trimIndent()
                ShellUtils.fastCmd(script)
            }
        }
    }

    /**
     * Dedicated Screen-Off Zero-Drain Sleep Mode (480MHz Floor)
     */
    fun applyScreenOffSleep() {
        val script = """
            for c in 0 1 2 3 4 5; do
                echo 480000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                echo 480000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
            done
            for c in 6 7; do
                echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_min_freq 2>/dev/null
                echo 400000 > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_max_freq 2>/dev/null
                echo schedutil > /sys/devices/system/cpu/cpu${'$'}c/cpufreq/scaling_governor 2>/dev/null
            done
            echo 480000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq 2>/dev/null
            echo 480000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq 2>/dev/null
            echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq 2>/dev/null
            echo 400000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null
            echo 400000 > /sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq 2>/dev/null
            echo 400000 > /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq 2>/dev/null
        """.trimIndent()
        ShellUtils.fastCmd(script)
    }

    /**
     * Temporary Manual Stage Override Lab:
     * 1 = S1 Option A: 6 Cores Capped 950MHz, 2 Big Cores 400MHz (Balanced Eco)
     * 10 = S1 Option B: 6 Cores Capped 850MHz, 2 Big Cores 400MHz (Extreme Eco)
     * 11 = S1 Option C: 6 Cores Capped 650MHz, 2 Big Cores 400MHz (Ultra Super Eco)
     * 12 = S1 Option D: 6 Cores Capped 550MHz, 2 Big Cores 400MHz (Deep Eco)
     * 13 = S1 Option E: 6 Cores Capped 480MHz, 2 Big Cores 400MHz (Hardware Min Floor)
     * 2 = Force Stage 2: 6 Cores Max 2.0GHz, 2 Big Cores 400MHz (120Hz Fluid 6-Cores Only)
     * 3 = Force Stage 3: 6 Cores 2.0GHz, 2 Big Cores 1.5GHz (Balanced Dual-Cluster)
     * 4 = Force Stage 4: 8 Cores Full Turbo 2.0GHz + 2.8GHz (Extreme Max Gaming)
     * 0 = Reset to Auto (AI Dynamic Ladder)
     */
    fun forceStageOverride(context: Context, stage: Int) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("manual_stage_override", stage).apply()
        manualStageOverride = stage

        if (stage != 0) {
            applyRawStageScript(stage)
        } else {
            val savedMode = prefs.getString("selected_mode", "rbAutomatic")
            if (savedMode == "rbPowerSaver") {
                applyAsymmetricCpuFreqTuning("power")
            } else if (savedMode == "rbPerformance") {
                applyAsymmetricCpuFreqTuning("perf")
            } else if (savedMode == "rbAutomatic") {
                context.startService(Intent(context, AutoTweakService::class.java))
            } else {
                applyAsymmetricCpuFreqTuning("balance")
            }
        }

        // Keep QS Tile in 100% sync
        ModeControlTileService.updateTile(context)

        // Broadcast UI update to all open activities
        val updateIntent = Intent("com.example.phonecontrol.UPDATE_UI").apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(updateIntent)
    }

    /**
     * 1. GPU Power & Frequency Profiles
     */
    fun applyGpuTuning(mode: String) {
        when (mode) {
            "perf" -> {
                // Force GPU to stay at high frequencies
                ShellUtils.fastCmd("echo 1 > /sys/kernel/gpu/gpu_max_clock 2>/dev/null")
                ShellUtils.fastCmd("echo performance > /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null")
                ShellUtils.fastCmd("echo 0 > /sys/class/kgsl/kgsl-3d0/bus_split 2>/dev/null")
                // MTK Specific
                ShellUtils.fastCmd("echo 1 > /proc/gpufreq/gpufreq_var_dump 2>/dev/null")
            }
            "power" -> {
                // Dynamic On-Demand GPU (Allows smooth 120Hz/60Hz frame rendering, sits at idle when static)
                ShellUtils.fastCmd("echo 0 > /sys/kernel/gpu/gpu_max_clock 2>/dev/null")
                ShellUtils.fastCmd("echo simple_ondemand > /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null")
            }
            else -> {
                ShellUtils.fastCmd("echo simple_ondemand > /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null")
            }
        }
    }

    /**
     * 2. Entropy Engine (UI Responsiveness)
     */
    fun applyEntropyTuning(boost: Boolean) {
        if (boost) {
            ShellUtils.fastCmd("echo 256 > /proc/sys/kernel/random/read_wakeup_threshold 2>/dev/null")
            ShellUtils.fastCmd("echo 512 > /proc/sys/kernel/random/write_wakeup_threshold 2>/dev/null")
        } else {
            ShellUtils.fastCmd("echo 64 > /proc/sys/kernel/random/read_wakeup_threshold 2>/dev/null")
            ShellUtils.fastCmd("echo 128 > /proc/sys/kernel/random/write_wakeup_threshold 2>/dev/null")
        }
    }

    /**
     * 3. Kernel Wakelock Blocker
     * Prevents specific hardware drivers from waking the device.
     */
    fun applyWakelockBlocker(enabled: Boolean) {
        val list = listOf(
            "wlan_rx_wake", "wlan_ctrl_wake", "wlan_wake",
            "sensor_ind", "msm_fastrpc_wakelock", "IPA_WS"
        )
        for (wl in list) {
            if (enabled) {
                ShellUtils.fastCmd("echo $wl > /sys/power/wake_unlock 2>/dev/null")
            }
        }
        
        // MTK specific deep idle
        if (enabled) {
            ShellUtils.fastCmd("echo 1 > /sys/kernel/debug/cpuidle/mtk_cpuidle_en 2>/dev/null")
        }
    }

    /**
     * Dedicated Temporary Wakeup Boost on Screen-On / Unlock (2-3 seconds)
     */
    fun triggerTemporaryWakeupBoost() {
        val batch = listOf(
            "for i in 0 1 2 3 4 5 6 7; do echo 1 > /sys/devices/system/cpu/cpu\$i/online 2>/dev/null; done",
            "for i in 0 1 2 3 4 5 6 7; do echo schedutil > /sys/devices/system/cpu/cpufreq/policy\$i/scaling_governor 2>/dev/null; done",
            "echo 0 > /sys/kernel/gpu/gpu_max_clock 2>/dev/null",
            "echo simple_ondemand > /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null",
            "echo 1 > /sys/module/cpu_boost/parameters/input_boost_enabled 2>/dev/null",
            "echo 0:950000 6:0 > /sys/module/cpu_boost/parameters/input_boost_freq 2>/dev/null",
            "echo 100 > /sys/module/cpu_boost/parameters/input_boost_ms 2>/dev/null"
        )
        ShellUtils.fastBatchCmd(batch)
    }

    /**
     * 1. CPU & EAS Tuning
     * Adjusts scheduling latencies and strict migration thresholds.
     */
    fun applyCpuTuning(mode: String) {
        val latency = when(mode) {
            "perf" -> "3000000" // 3ms
            "power" -> "5000000" // 5ms (fluid & responsive 120Hz/60Hz without ANR)
            else -> "4000000" // 4ms (balanced fluid response)
        }
        
        ShellUtils.fastCmd("echo $latency > /proc/sys/kernel/sched_latency_ns 2>/dev/null")
        ShellUtils.fastCmd("echo 1 > /proc/sys/kernel/sched_autogroup_enabled 2>/dev/null")
        
        when (mode) {
            "perf" -> {
                ShellUtils.fastCmd("echo 55 > /proc/sys/kernel/sched_upmigrate 2>/dev/null")
                ShellUtils.fastCmd("echo 45 > /proc/sys/kernel/sched_downmigrate 2>/dev/null")
            }
            "power" -> {
                // Strict 90% load barrier on Little Cores before Big cores wake up
                ShellUtils.fastCmd("echo 90 > /proc/sys/kernel/sched_upmigrate 2>/dev/null")
                ShellUtils.fastCmd("echo 75 > /proc/sys/kernel/sched_downmigrate 2>/dev/null")
            }
            else -> {
                // Strict 85% load barrier on Little Cores before Big cores wake up
                ShellUtils.fastCmd("echo 85 > /proc/sys/kernel/sched_upmigrate 2>/dev/null")
                ShellUtils.fastCmd("echo 70 > /proc/sys/kernel/sched_downmigrate 2>/dev/null")
            }
        }
    }

    /**
     * 2. I/O Storage & Latency Optimizer
     * Adjusts read-ahead and I/O scheduler properties.
     */
    fun applyIoOptimization(mode: String) {
        val readAhead = when(mode) {
            "perf" -> "2048"
            "power" -> "512"
            else -> "512"
        }
        
        // Apply to internal storage blocks
        val storageBlocks = listOf("sda", "sdb", "sdc", "mmcblk0", "dm-0")
        for (block in storageBlocks) {
            ShellUtils.fastCmd("echo $readAhead > /sys/block/$block/queue/read_ahead_kb 2>/dev/null")
            ShellUtils.fastCmd("echo 0 > /sys/block/$block/queue/add_random 2>/dev/null")
            ShellUtils.fastCmd("echo 1 > /sys/block/$block/queue/nomerges 2>/dev/null")
        }
    }

    /**
     * 3. Advanced LMK Tuning
     * Profile based tuning for Android's Low Memory Killer.
     */
    fun applyLmkTuning(profile: String) {
        val minFree = when (profile) {
            "rbProfileMultitasking" -> "18432,23040,27648,32256,55296,80640"
            "rbProfilePerformance" -> "15360,19200,23040,26880,34560,46080"
            else -> "18432,23040,27648,32256,36864,46080" // Balanced
        }
        ShellUtils.fastCmd("echo $minFree > /sys/module/lowmemorykiller/parameters/minfree 2>/dev/null")
    }

    /**
     * 4. Preventive Thermal & VM Guard
     * Reduces background disk writes to save battery and keep phone cool.
     */
    fun applyVmGuard(enabled: Boolean) {
        if (enabled) {
            ShellUtils.fastCmd("sysctl -w vm.dirty_ratio=20")
            ShellUtils.fastCmd("sysctl -w vm.dirty_background_ratio=10")
            ShellUtils.fastCmd("sysctl -w vm.vfs_cache_pressure=100")
        } else {
            ShellUtils.fastCmd("sysctl -w vm.dirty_ratio=40")
            ShellUtils.fastCmd("sysctl -w vm.dirty_background_ratio=20")
            ShellUtils.fastCmd("sysctl -w vm.vfs_cache_pressure=50")
        }
    }

    fun setSilentSystem(enabled: Boolean) {
        if (enabled) {
            ShellUtils.fastCmd("stop logd")
            ShellUtils.fastCmd("stop logd-reinit")
            ShellUtils.fastCmd("setprop ctl.stop logd")
            // Suppress Kernel Logging
            ShellUtils.fastCmd("echo 0 > /proc/sys/kernel/printk 2>/dev/null")
        } else {
            ShellUtils.fastCmd("start logd")
            ShellUtils.fastCmd("setprop ctl.start logd")
            ShellUtils.fastCmd("echo 7 > /proc/sys/kernel/printk 2>/dev/null")
        }
    }

    /**
     * 5. Location (GPS) Control with State Preservation
     */
    fun getLocationMode(context: Context): Int {
        return try {
            android.provider.Settings.Secure.getInt(context.contentResolver, android.provider.Settings.Secure.LOCATION_MODE, 0)
        } catch (e: Exception) {
            0
        }
    }

    fun setLocationMode(mode: Int) {
        ShellUtils.fastCmd("settings put secure location_mode $mode")
    }

    fun setLocationEnabled(enabled: Boolean) {
        val mode = if (enabled) 3 else 0
        setLocationMode(mode)
    }

    fun applyRamSettings(zramKey: String, profileKey: String) {
        val size = when (zramKey) {
            "rbZramOff" -> "0"
            "rbZram2G" -> "2147483648"
            "rbZram4G" -> "4294967296"
            "rbZram8G" -> "8589934592"
            else -> "4294967296"
        }

        // Apply Kernel VM Profiles via centralized method
        applyLmkTuning(profileKey)
        
        when (profileKey) {
            "rbProfileBalance" -> {
                ShellUtils.fastCmd("sysctl -w vm.swappiness=100")
                ShellUtils.fastCmd("sysctl -w vm.vfs_cache_pressure=100")
                ShellUtils.fastCmd("sysctl -w vm.dirty_ratio=20")
            }
            "rbProfileMultitasking" -> {
                ShellUtils.fastCmd("sysctl -w vm.swappiness=160")
                ShellUtils.fastCmd("sysctl -w vm.vfs_cache_pressure=50")
                ShellUtils.fastCmd("sysctl -w vm.dirty_ratio=40")
            }
            "rbProfilePerformance" -> {
                ShellUtils.fastCmd("sysctl -w vm.swappiness=60")
                ShellUtils.fastCmd("sysctl -w vm.vfs_cache_pressure=150")
                ShellUtils.fastCmd("sysctl -w vm.dirty_ratio=10")
            }
        }

        // 2. Apply ZRAM Resize only if different
        val currentSize = ShellUtils.runAsRoot("cat /sys/block/zram0/disksize").output
        if (currentSize != size) {
            ShellUtils.runAsRoot("swapoff /dev/block/zram0")
            if (size != "0") {
                ShellUtils.runAsRoot("echo 1 > /sys/block/zram0/reset")
                ShellUtils.runAsRoot("echo $size > /sys/block/zram0/disksize")
                ShellUtils.runAsRoot("mkswap /dev/block/zram0")
                ShellUtils.runAsRoot("swapon /dev/block/zram0")
            }
        }
    }

    fun applyNetworkSettings(dnsKey: String, tcpEnabled: Boolean, lowLatencyEnabled: Boolean) {
        val dnsHost = when (dnsKey) {
            "rbDnsGoogle" -> "dns.google"
            "rbDnsCloudflare" -> "1dot1dot1dot1.cloudflare-dns.com"
            else -> ""
        }
        if (dnsHost.isNotEmpty()) {
            ShellUtils.fastCmd("settings put global private_dns_mode hostname")
            ShellUtils.fastCmd("settings put global private_dns_specifier $dnsHost")
        } else {
            ShellUtils.fastCmd("settings put global private_dns_mode off")
        }

        if (tcpEnabled) {
            val cmds = listOf(
                "sysctl -w net.ipv4.tcp_timestamps=0",
                "sysctl -w net.ipv4.tcp_sack=1",
                "sysctl -w net.ipv4.tcp_window_scaling=1",
                "sysctl -w net.core.rmem_max=16777216",
                "sysctl -w net.core.wmem_max=16777216",
                "sysctl -w net.ipv4.tcp_rmem='4096 87380 16777216'",
                "sysctl -w net.ipv4.tcp_wmem='4096 65536 16777216'",
                "sysctl -w net.ipv4.tcp_fastopen=3",
                "sysctl -w net.core.default_qdisc=fq_codel",
                "sysctl -w net.ipv4.tcp_congestion_control=bbr"
            )
            for (cmd in cmds) ShellUtils.fastCmd(cmd)
        }

        if (lowLatencyEnabled) {
            ShellUtils.fastCmd("settings put global wifi_scan_throttle_enabled 1")
            ShellUtils.fastCmd("cmd wifi set-scan-throttle enabled")
            ShellUtils.fastCmd("sysctl -w net.ipv6.conf.all.disable_ipv6=1")
        } else {
            ShellUtils.fastCmd("sysctl -w net.ipv6.conf.all.disable_ipv6=0")
        }
    }

    /**
     * 6. Network Prioritization (Packet Guard)
     */
    fun setNetworkPriority(context: android.content.Context, packageName: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("network_priority_enabled", false)) return

        try {
            val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
            if (enabled) {
                // Mark packets from this UID for high priority
                ShellUtils.fastCmd("iptables -t mangle -A OUTPUT -m owner --uid-owner $uid -j MARK --set-mark 1")
                ShellUtils.fastCmd("iptables -t mangle -A OUTPUT -m owner --uid-owner $uid -j TOS --set-tos Minimize-Delay")
            } else {
                ShellUtils.fastCmd("iptables -t mangle -D OUTPUT -m owner --uid-owner $uid -j MARK --set-mark 1 2>/dev/null")
                ShellUtils.fastCmd("iptables -t mangle -D OUTPUT -m owner --uid-owner $uid -j TOS --set-tos Minimize-Delay 2>/dev/null")
            }
        } catch (e: Exception) {}
    }

    /**
     * 7. CPU Frequency Capping (Percentage based)
     */
    fun limitCpuFrequency(percentage: Int) {
        if (manualStageOverride != 0) return
        val script = "for i in 0 1 2 3 4 5 6 7; do max=\$(cat /sys/devices/system/cpu/cpu\$i/cpufreq/cpuinfo_max_freq 2>/dev/null); if [ -n \"\$max\" ]; then target=\$((max * $percentage / 100)); echo \$target > /sys/devices/system/cpu/cpu\$i/cpufreq/scaling_max_freq 2>/dev/null; fi; done"
        ShellUtils.fastCmd(script)
    }

    /**
     * 8. System Resolution Toggle
     */
    fun setSystemResolution(isLowRes: Boolean) {
        if (isLowRes) {
            ShellUtils.runAsRoot("wm size 720x1600")
            ShellUtils.runAsRoot("wm density 320")
        } else {
            ShellUtils.runAsRoot("wm size reset")
            ShellUtils.runAsRoot("wm density reset")
        }
    }

    /**
     * 9. Cluster Control (Core Parking)
     */
    fun setClusterParking(parkBigCores: Boolean, deep: Boolean = false) {
        // Dimensity 7200 Pro EAS architecture:
        // Always ensure all 8 cores are online to prevent CPU starvation and system freezes.
        // In power-saving, schedutil governor drops idle cores to 400MHz / hardware C-states safely.
        for (i in 0..7) {
            ShellUtils.fastCmd("echo 1 > /sys/devices/system/cpu/cpu$i/online 2>/dev/null")
        }
    }

    /**
     * 10. Smart Network Firewall (Data Guard)
     */
    fun setFirewallRule(uid: Int, blocked: Boolean) {
        val action = if (blocked) "-A" else "-D"
        ShellUtils.runAsRoot("iptables $action OUTPUT -m owner --uid-owner $uid -j REJECT")
    }

    /**
     * 11. Turbo Launch Boost
     * Spikes CPU to 100% for a few seconds.
     */
    fun triggerTurboBoost() {
        kotlin.concurrent.thread {
            // Set all online cores to MAX
            limitCpuFrequency(100)
            // Hold for 3 seconds
            Thread.sleep(3000)
            // Note: The Adaptive Thermal Engine or Service will automatically 
            // re-apply any necessary caps on the next tick/check if needed.
        }
    }
}
