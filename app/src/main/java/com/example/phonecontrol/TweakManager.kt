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

    fun setSilentSystem(enabled: Boolean) {
        if (enabled) {
            // Stop logging daemons
            ShellUtils.fastCmd("stop logd")
            ShellUtils.fastCmd("stop logd-reinit")
            ShellUtils.fastCmd("setprop ctl.stop logd")
        } else {
            // Start logging daemons
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

        // 1. Apply Kernel VM Profiles (Swappiness, Pressure, Dirty Ratio)
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
        // 1. DNS Tweak
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

        // 2. TCP & Congestion Tweaks
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

        // 3. Low Latency (Gaming) Tweaks
        if (lowLatencyEnabled) {
            ShellUtils.fastCmd("settings put global wifi_scan_throttle_enabled 1")
            ShellUtils.fastCmd("cmd wifi set-scan-throttle enabled")
            ShellUtils.fastCmd("sysctl -w net.ipv6.conf.all.disable_ipv6=1")
        } else {
            ShellUtils.fastCmd("sysctl -w net.ipv6.conf.all.disable_ipv6=0")
        }
    }
}
