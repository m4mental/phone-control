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
     * Returns set of packages that are actively playing audio (state = PLAYING).
     * Excludes non-playing or paused media sessions.
     */
    fun getActivePlayingAudioPackages(context: Context): Set<String> {
        val activePlaying = mutableSetOf<String>()
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager?.isMusicActive == true) {
                val out = ShellUtils.runAsRoot("""
                    dumpsys media_session | awk '/package=/ {pkg=${'$'}0} /state=PlaybackState/ {if (${'$'}0 ~ /state=3/) print pkg}' | cut -d '=' -f2
                """.trimIndent()).output
                val pkgs = out.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                if (pkgs.isNotEmpty()) {
                    activePlaying.addAll(pkgs)
                } else {
                    val allSessions = ShellUtils.runAsRoot("dumpsys media_session | grep 'package=' | cut -d '=' -f2").output
                    activePlaying.addAll(allSessions.split("\n").map { it.trim() }.filter { it.isNotBlank() && it != "com.android.server.telecom" })
                }
            }
        } catch (e: Exception) {}
        return activePlaying
    }

    fun getActivePackages(packages: Collection<String>): Set<String> {
        if (packages.isEmpty()) return emptySet()
        val out = ShellUtils.runAsRoot("ps -A -o NAME").output
        val running = out.split("\n").map { it.trim() }.toSet()
        return packages.filter { running.contains(it) }.toSet()
    }

    fun launchApp(context: Context, packageName: String) {
        lastLaunchedPackage = packageName
        lastLaunchTime = System.currentTimeMillis()

        unfreezeApp(packageName)
        TweakManager.triggerTurboBoost()
        
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        intent?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
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
}
