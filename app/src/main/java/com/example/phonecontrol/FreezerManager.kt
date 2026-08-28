package com.example.phonecontrol

import android.content.Context
import android.content.Intent
import android.os.Build

object FreezerManager {

    var lastLaunchedPackage: String? = null
    var lastLaunchTime: Long = 0

    /**
     * Hibernates a single app.
     */
    fun freezeApp(context: Context, packageName: String) {
        if (packageName == lastLaunchedPackage && (System.currentTimeMillis() - lastLaunchTime) < 10000) {
            return
        }

        if (isSpecialFreeze(context, packageName)) {
            ShellUtils.fastCmd("am force-stop $packageName; pm suspend $packageName")
        }

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
     * Batch Hibernates multiple apps in a single ultra-fast shell execution (0ms UI lag).
     */
    fun freezeMultipleApps(context: Context, packages: Collection<String>) {
        if (packages.isEmpty()) return
        val pkgList = packages.filter { it != lastLaunchedPackage }.joinToString(" ")
        if (pkgList.isBlank()) return

        val script = """
            for pkg in $pkgList; do
                am freeze "${'$'}pkg" 2>/dev/null
                am set-standby-bucket "${'$'}pkg" restricted 2>/dev/null
                for p in ${'$'}(pidof "${'$'}pkg"); do
                    echo 900 > /proc/${'$'}p/oom_score_adj 2>/dev/null
                done
            done
        """.trimIndent()
        ShellUtils.fastCmd(script)
    }

    /**
     * Resumes an app instantly.
     */
    fun unfreezeApp(packageName: String) {
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

    /**
     * Fast check: returns active packages among a set in 1 single command.
     */
    fun getActivePackages(packages: Set<String>): Set<String> {
        if (packages.isEmpty()) return emptySet()
        val runningOutput = ShellUtils.runAsRoot("ps -A -o NAME").output
        return packages.filter { runningOutput.contains(it) }.toSet()
    }

    fun getFrozenApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("frozen_packages", emptySet()) ?: emptySet()
    }

    fun saveFrozenApps(context: Context, packages: Set<String>) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("frozen_packages", packages).apply()
    }

    fun isSpecialFreeze(context: Context, packageName: String): Boolean {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("special_$packageName", false)
    }

    fun setSpecialFreeze(context: Context, packageName: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("special_$packageName", enabled).apply()
    }
}
