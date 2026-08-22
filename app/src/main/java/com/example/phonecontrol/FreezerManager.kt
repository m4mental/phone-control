package com.example.phonecontrol

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.os.Build

object FreezerManager {

    // Track the last launched app to prevent immediate re-freezing
    var lastLaunchedPackage: String? = null
    var lastLaunchTime: Long = 0

    /**
     * Hibernates an app using Kernel-level pausing (am freeze).
     */
    fun freezeApp(context: Context, packageName: String) {
        if (packageName == lastLaunchedPackage && (System.currentTimeMillis() - lastLaunchTime) < 10000) {
            return
        }

        if (isSpecialFreeze(context, packageName)) {
            // Special Freeze: Force Stop + Suspend
            ShellUtils.fastCmd("am force-stop $packageName")
            ShellUtils.fastCmd("pm suspend $packageName")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ShellUtils.fastCmd("am freeze $packageName")
        } else {
            val pids = getPids(packageName)
            for (pid in pids) {
                ShellUtils.fastCmd("kill -STOP $pid")
            }
        }

        setOomScore(packageName, 900)
        ShellUtils.fastCmd("am set-standby-bucket $packageName restricted")
    }

    /**
     * Resumes an app instantly.
     */
    fun unfreezeApp(packageName: String) {
        ShellUtils.fastCmd("pm enable $packageName")
        ShellUtils.fastCmd("pm unsuspend $packageName")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ShellUtils.fastCmd("am unfreeze $packageName")
        } else {
            val pids = getPids(packageName)
            for (pid in pids) {
                ShellUtils.fastCmd("kill -CONT $pid")
            }
        }
        
        setOomScore(packageName, 0)
        ShellUtils.fastCmd("am set-standby-bucket $packageName active")
    }

    fun launchApp(context: Context, packageName: String) {
        lastLaunchedPackage = packageName
        lastLaunchTime = System.currentTimeMillis()

        // Reverting to the previous launch logic as requested
        unfreezeApp(packageName)
        Thread.sleep(300)
        // Trigger Turbo Launch Boost
        TweakManager.triggerTurboBoost()
        
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        intent?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    }

    private fun setOomScore(packageName: String, score: Int) {
        val pids = getPids(packageName)
        for (pid in pids) {
            ShellUtils.fastCmd("echo $score > /proc/$pid/oom_score_adj 2>/dev/null")
        }
    }

    private fun getPids(packageName: String): List<String> {
        val result = ShellUtils.runAsRoot("pidof $packageName")
        return result.output.split(" ").filter { it.isNotBlank() }
    }

    /**
     * Corrected status logic:
     * If no process -> HIBERNATING (Safe)
     * If process + frozen -> HIBERNATING
     * If process + NOT frozen -> ACTIVE
     */
    fun isAppTrulyActive(packageName: String): Boolean {
        val pids = getPids(packageName)
        if (pids.isEmpty()) return false
        
        val result = ShellUtils.runAsRoot("dumpsys activity process $packageName | grep 'frozen='")
        return !result.output.contains("true")
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
