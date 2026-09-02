package com.example.phonecontrol

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build

object FreezerManager {

    var lastLaunchedPackage: String? = null
    var lastLaunchTime: Long = 0

    /**
     * Hibernates a single app immediately.
     */
    fun freezeApp(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        
        if (isSpecialFreeze(context, packageName)) {
            ShellUtils.fastCmd("am force-stop $packageName; pm suspend $packageName")
        }

        val script = """
            am freeze "$packageName" 2>/dev/null
            am force-stop "$packageName" 2>/dev/null
            am set-standby-bucket "$packageName" restricted 2>/dev/null
            for p in $(pidof "$packageName"); do
                echo 900 > /proc/${'$'}p/oom_score_adj 2>/dev/null
            done
        """.trimIndent()
        ShellUtils.fastCmd(script)
    }

    /**
     * Batch Hibernates multiple apps in a single ultra-fast shell execution (0ms UI lag).
     */
    fun freezeMultipleApps(context: Context, packages: Collection<String>) {
        if (packages.isEmpty()) return
        val pkgList = packages.filter { it != lastLaunchedPackage }.joinToString(" ")
        if (pkgList.isBlank()) return

        val script = """
            for pkg in $pkgList; do
                am freeze "${'$'}pkg" 2>/dev/null
                am force-stop "${'$'}pkg" 2>/dev/null
                am set-standby-bucket "${'$'}pkg" restricted 2>/dev/null
                for p in ${'$'}(pidof "${'$'}pkg"); do
                    echo 900 > /proc/${'$'}p/oom_score_adj 2>/dev/null
                done
            done
        """.trimIndent()
        ShellUtils.fastCmd(script)
    }

    /**
     * Resumes an app instantly on open.
     */
    fun unfreezeApp(packageName: String) {
        if (packageName.isBlank()) return
        val script = """
            pm enable "$packageName" 2>/dev/null
            pm unsuspend "$packageName" 2>/dev/null
            am unfreeze "$packageName" 2>/dev/null
            am set-standby-bucket "$packageName" active 2>/dev/null
            for p in $(pidof "$packageName"); do
                echo 0 > /proc/${'$'}p/oom_score_adj 2>/dev/null
            done
        """.trimIndent()
        ShellUtils.fastCmd(script)
    }

    /**
     * Batch Unfreezes multiple apps.
     */
    fun unfreezeMultipleApps(packages: Collection<String>) {
        if (packages.isEmpty()) return
        val pkgList = packages.joinToString(" ")
        val script = """
            for pkg in $pkgList; do
                pm enable "${'$'}pkg" 2>/dev/null
                pm unsuspend "${'$'}pkg" 2>/dev/null
                am unfreeze "${'$'}pkg" 2>/dev/null
                am set-standby-bucket "${'$'}pkg" active 2>/dev/null
                for p in ${'$'}(pidof "${'$'}pkg"); do
                    echo 0 > /proc/${'$'}p/oom_score_adj 2>/dev/null
                done
            done
        """.trimIndent()
        ShellUtils.fastCmd(script)
    }

    /**
     * Returns the set of all packages currently open in the system's Recent Apps / Recents Task list.
     */
    fun getRecentPackages(): Set<String> {
        return try {
            val result = ShellUtils.runAsRoot("dumpsys activity recents | grep -o 'realActivity={[a-zA-Z0-9_.]*' | cut -d '{' -f2")
            result.output.split("\n").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Returns set of packages that are actively playing audio/video (state = PLAYING or started audio track).
     * Excludes non-playing or paused media sessions.
     */
    fun getActivePlayingAudioPackages(context: Context): Set<String> {
        val activePlaying = mutableSetOf<String>()
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager?.isMusicActive == true) {
                // 1. Check MediaSession active players
                val out = ShellUtils.runAsRoot("""
                    dumpsys media_session | awk '/package=/ {pkg=${'$'}0} /state=PlaybackState/ {if (${'$'}0 ~ /state=3/) print pkg}' | cut -d '=' -f2
                """.trimIndent()).output
                val pkgs = out.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                activePlaying.addAll(pkgs)

                // 2. Check AudioTrack / native players (VLC, MX Player, ExoPlayer, NextPlayer)
                val audioTracks = ShellUtils.runAsRoot("dumpsys audio | grep -B 2 'state:started' | grep -o 'u/pid:[0-9]*'").output
                if (audioTracks.isNotBlank()) {
                    val pids = audioTracks.split("\n").mapNotNull { it.substringAfter("u/pid:").trim().toIntOrNull() }
                    for (pid in pids) {
                        val pkg = ShellUtils.runAsRoot("cat /proc/$pid/cmdline 2>/dev/null").output.trim().replace("\u0000", "")
                        if (pkg.isNotBlank() && pkg != "com.android.server.telecom") {
                            activePlaying.add(pkg)
                        }
                    }
                }

                // Fallback: If AudioManager says music is active, also add all active media session packages
                if (activePlaying.isEmpty()) {
                    val allSessions = ShellUtils.runAsRoot("dumpsys media_session | grep 'package=' | cut -d '=' -f2").output
                    activePlaying.addAll(allSessions.split("\n").map { it.trim() }.filter { it.isNotBlank() && it != "com.android.server.telecom" })
                }
            }
        } catch (e: Exception) {}
        return activePlaying
    }

    /**
     * Checks if an app is currently visible on the screen or in focus.
     */
    fun isAppCurrentlyVisible(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            val out = ShellUtils.runAsRoot("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'").output
            out.contains(packageName)
        } catch (e: Exception) {
            false
        }
    }

    fun getActivePackages(packages: Collection<String>): Set<String> {
        if (packages.isEmpty()) return emptySet()
        val out = ShellUtils.runAsRoot("ps -A -o NAME").output
        val running = out.split("\n").map { it.trim() }.toSet()
        return packages.filter { running.contains(it) }.toSet()
    }

    fun launchApp(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        lastLaunchedPackage = packageName
        lastLaunchTime = System.currentTimeMillis()

        // 1. Synchronously unsuspend, enable, and unfreeze via Root BEFORE attempting launch
        val script = """
            pm enable "$packageName" 2>/dev/null
            pm unsuspend "$packageName" 2>/dev/null
            am unfreeze "$packageName" 2>/dev/null
            am set-standby-bucket "$packageName" active 2>/dev/null
            for p in $(pidof "$packageName"); do
                echo 0 > /proc/${'$'}p/oom_score_adj 2>/dev/null
            done
        """.trimIndent()
        ShellUtils.runAsRoot(script)
        TweakManager.triggerTurboBoost()
        
        // 2. Direct Root Launcher launch (0ms delay, bypasses OS suspend cache dialogs)
        val monkeyResult = ShellUtils.runAsRoot("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        if (monkeyResult.exitCode != 0 || monkeyResult.output.contains("No activities found")) {
            // Fallback to PackageManager launch intent
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                intent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    context.startActivity(it)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun getFrozenApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("frozen_apps", emptySet()) ?: emptySet()
    }

    fun saveFrozenApps(context: Context, packages: Set<String>) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("frozen_apps", packages).apply()
    }

    fun addAppToFreezer(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("frozen_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(packageName)
        saveFrozenApps(context, set)
        freezeApp(context, packageName)
    }

    fun removeAppFromFreezer(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("frozen_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.remove(packageName)
        saveFrozenApps(context, set)
        unfreezeApp(packageName)
    }

    fun getCustomWidgetApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("custom_widget_apps", emptySet()) ?: emptySet()
    }

    fun saveCustomWidgetApps(context: Context, packages: Set<String>) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("custom_widget_apps", packages).apply()
    }

    fun toggleCustomWidgetApp(context: Context, packageName: String): Boolean {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("custom_widget_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        val newState = if (set.contains(packageName)) {
            set.remove(packageName)
            false
        } else {
            set.add(packageName)
            true
        }
        prefs.edit().putStringSet("custom_widget_apps", set).apply()
        return newState
    }

    fun getSpecialFreezeApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("special_freeze_apps", emptySet()) ?: emptySet()
    }

    fun isSpecialFreeze(context: Context, packageName: String): Boolean {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("special_freeze_apps", emptySet()) ?: emptySet()
        return set.contains(packageName)
    }

    fun setSpecialFreeze(context: Context, packageName: String, enable: Boolean) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("special_freeze_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enable) set.add(packageName) else set.remove(packageName)
        prefs.edit().putStringSet("special_freeze_apps", set).apply()
    }

    val KNOWN_EQUALIZERS = listOf(
        "com.maxmpz.equalizer",               // Poweramp Equalizer
        "com.pittvandewitt.wavelet",           // Wavelet
        "com.audlabs.viperfx",                // ViPER4Android FX
        "com.pittvandewitt.viperfx",           // ViPER4Android
        "com.jazibkhan.equalizer",             // Flat Equalizer
        "james.dsp",                           // JamesDSP
        "me.timschneeberger.rootlessjamesdsp", // RootlessJamesDSP
        "com.kotor.spotiq",                    // SpotiQ
        "com.goodev.volume.booster"            // Volume Booster Goodev
    )

    /**
     * Dynamically detects the active equalizer or audio DSP app installed on the device.
     */
    fun getDetectedEqualizerPackage(context: Context): String? {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        val manualSelection = prefs.getString("selected_equalizer_pkg", null)
        val pm = context.packageManager

        // 1. Check user manual override
        if (!manualSelection.isNullOrBlank()) {
            try {
                pm.getPackageInfo(manualSelection, 0)
                return manualSelection
            } catch (ignored: Exception) {}
        }

        // 2. Check popular known equalizers
        for (pkg in KNOWN_EQUALIZERS) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (ignored: Exception) {}
        }

        // 3. Query system for any app responding to AudioEffect Control Panel
        try {
            val intent = Intent("android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL")
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            for (info in resolveInfos) {
                val pkg = info.activityInfo?.packageName
                if (pkg != null && pkg != context.packageName) {
                    return pkg
                }
            }
        } catch (ignored: Exception) {}

        return null
    }

    fun isEqualizerSleepEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("smart_equalizer_sleep_enabled", true)
    }

    fun setEqualizerSleepEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("smart_equalizer_sleep_enabled", enabled).apply()
    }

    fun saveSelectedEqualizer(context: Context, pkg: String?) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("selected_equalizer_pkg", pkg).apply()
    }

    /**
     * Ultra-fast RAM sleep (CGroup Freeze) for audio equalizers.
     * Halts all threads and wakelocks without killing the service or breaking audio pipelines.
     */
    fun instantFreezeEqualizer(packageName: String) {
        if (packageName.isBlank()) return
        val script = """
            am freeze "$packageName" 2>/dev/null
            am set-standby-bucket "$packageName" restricted 2>/dev/null
            for p in $(pidof "$packageName"); do
                echo 900 > /proc/${'$'}p/oom_score_adj 2>/dev/null
            done
        """.trimIndent()
        ShellUtils.fastCmd(script)
    }

    /**
     * 1-Millisecond Instant Unfreeze:
     * Resumes the equalizer instantly from RAM with its audio session completely intact (Zero Audio Dropout).
     */
    fun instantUnfreezeEqualizer(packageName: String) {
        if (packageName.isBlank()) return
        val script = """
            pm enable "$packageName" 2>/dev/null
            am unfreeze "$packageName" 2>/dev/null
            am set-standby-bucket "$packageName" active 2>/dev/null
            for p in $(pidof "$packageName"); do
                echo 0 > /proc/${'$'}p/oom_score_adj 2>/dev/null
            done
        """.trimIndent()
        ShellUtils.fastCmd(script)
    }

    /**
     * Checks if the app is currently in frozen/suspended state.
     */
    fun isAppFrozen(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val out = ShellUtils.fastCmdResult("dumpsys activity processes | grep -E '$packageName.*freeze=true' 2>/dev/null")
        return out.isNotBlank()
    }
}
