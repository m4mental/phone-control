package com.example.phonecontrol

import android.content.Context
import android.content.Intent

object FreezerManager {

    // Track the last launched app to prevent immediate re-freezing by the service
    var lastLaunchedPackage: String? = null
    var lastLaunchTime: Long = 0

    fun freezeApp(packageName: String) {
        // Don't freeze if it was just launched (give it 10 seconds grace period)
        if (packageName == lastLaunchedPackage && (System.currentTimeMillis() - lastLaunchTime) < 10000) {
            return
        }
        ShellUtils.fastCmd("pm disable-user --user 0 $packageName")
    }

    fun unfreezeApp(packageName: String) {
        ShellUtils.fastCmd("pm enable $packageName")
        ShellUtils.fastCmd("am set-standby-bucket $packageName active")
    }

    fun launchApp(context: Context, packageName: String) {
        lastLaunchedPackage = packageName
        lastLaunchTime = System.currentTimeMillis()

        // Synchronous enable to ensure package manager sees it before we request intent
        ShellUtils.runAsRoot("pm enable $packageName")
        ShellUtils.runAsRoot("am set-standby-bucket $packageName active")
        
        // Give system a moment to register the change
        Thread.sleep(500)
        
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            // Fallback: try to find any activity if main launcher intent is missing
            val altIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            altIntent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
        }
    }

    fun getFrozenApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("frozen_packages", emptySet()) ?: emptySet()
    }

    fun saveFrozenApps(context: Context, packages: Set<String>) {
        val prefs = context.getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("frozen_packages", packages).apply()
    }
}
