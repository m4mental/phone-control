package com.example.phonecontrol

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
            }
            "Balance" -> {
                applyBalance()
                applyCpuTuning("balance")
                applyIoOptimization("balance")
            }
            "Performance" -> {
                applyPerformance()
                applyCpuTuning("perf")
                applyIoOptimization("perf")
                applyInputBoost(true)
            }
        }
    }

    /**
     * 5. Kernel Input Boost
     * Speeds up cores instantly on touch.
     */
    fun applyInputBoost(enabled: Boolean) {
        if (enabled) {
            // Boost frequencies for 500ms on touch
            ShellUtils.fastCmd("echo 1 > /sys/module/cpu_boost/parameters/input_boost_enabled 2>/dev/null")
            ShellUtils.fastCmd("echo 0:1200000 1:1200000 2:1200000 3:1200000 4:1800000 5:1800000 6:1800000 7:1800000 > /sys/module/cpu_boost/parameters/input_boost_freq 2>/dev/null")
            ShellUtils.fastCmd("echo 500 > /sys/module/cpu_boost/parameters/input_boost_ms 2>/dev/null")
            
            // MTK Touch Sampling / Response Boost
            ShellUtils.fastCmd("echo 1 > /proc/touchpanel/game_switch_enable 2>/dev/null")
            ShellUtils.fastCmd("echo 1 > /sys/devices/virtual/touchpanel/smart_wake/touch_responsiveness 2>/dev/null")
        } else {
            ShellUtils.fastCmd("echo 0 > /sys/module/cpu_boost/parameters/input_boost_enabled 2>/dev/null")
            ShellUtils.fastCmd("echo 40 > /sys/module/cpu_boost/parameters/input_boost_ms 2>/dev/null")
            ShellUtils.fastCmd("echo 0 > /proc/touchpanel/game_switch_enable 2>/dev/null")
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
        for (i in 0..7) {
            ShellUtils.fastCmd("echo powersave > /sys/devices/system/cpu/cpufreq/policy$i/scaling_governor 2>/dev/null")
        }
        ShellUtils.fastCmd("echo 0 > /sys/kernel/gpu/gpu_max_clock 2>/dev/null")
        applyGpuTuning("power")
        applyEntropyTuning(false)
    }

    fun applyBalance() {
        for (i in 0..7) {
            ShellUtils.fastCmd("echo schedutil > /sys/devices/system/cpu/cpufreq/policy$i/scaling_governor 2>/dev/null")
        }
        applyGpuTuning("balance")
        applyEntropyTuning(true)
    }

    fun applyPerformance() {
        for (i in 0..7) {
            ShellUtils.fastCmd("echo performance > /sys/devices/system/cpu/cpufreq/policy$i/scaling_governor 2>/dev/null")
        }
        ShellUtils.fastCmd("echo 1 > /sys/kernel/gpu/gpu_max_clock 2>/dev/null")
        applyGpuTuning("perf")
        applyEntropyTuning(true)
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
                ShellUtils.fastCmd("echo 0 > /sys/kernel/gpu/gpu_max_clock 2>/dev/null")
                ShellUtils.fastCmd("echo powersave > /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null")
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
     * 1. CPU & EAS Tuning
     * Adjusts scheduling latencies and migration thresholds.
     */
    fun applyCpuTuning(mode: String) {
        val latency = when(mode) {
            "perf" -> "4000000" // 4ms
            "power" -> "20000000" // 20ms
            else -> "10000000" // 10ms (default)
        }
        
        ShellUtils.fastCmd("echo $latency > /proc/sys/kernel/sched_latency_ns 2>/dev/null")
        ShellUtils.fastCmd("echo 1 > /proc/sys/kernel/sched_autogroup_enabled 2>/dev/null")
        
        if (mode == "perf") {
            ShellUtils.fastCmd("echo 95 > /proc/sys/kernel/sched_upmigrate 2>/dev/null")
            ShellUtils.fastCmd("echo 85 > /proc/sys/kernel/sched_downmigrate 2>/dev/null")
        } else {
            ShellUtils.fastCmd("echo 85 > /proc/sys/kernel/sched_upmigrate 2>/dev/null")
            ShellUtils.fastCmd("echo 75 > /proc/sys/kernel/sched_downmigrate 2>/dev/null")
        }
    }

    /**
     * 2. I/O Storage & Latency Optimizer
     * Adjusts read-ahead and I/O scheduler properties.
     */
    fun applyIoOptimization(mode: String) {
        val readAhead = when(mode) {
            "perf" -> "2048"
            "power" -> "128"
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
        } else {
            ShellUtils.fastCmd("start logd")
            ShellUtils.fastCmd("setprop ctl.start logd")
        }
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

}
