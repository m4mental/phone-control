package com.example.phonecontrol

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

object DeepOptManager {

    fun runFullOptimization(context: Context, onComplete: (String) -> Unit = {}) {
        // 1. Junk Cleaning
        val cleanCmds = listOf(
            "rm -rf /data/tombstones/*",
            "rm -rf /data/anr/*",
            "rm -rf /data/system/dropbox/*",
            "rm -rf /cache/*",
            "rm -rf /data/system/usagestats/*"
        )
        ShellUtils.runCommandsAsRoot(cleanCmds)

        // 2. File System Trim
        ShellUtils.runAsRoot("sm fstrim")

        // 3. ART Cache Compilation (Balance Mode)
        ShellUtils.runAsRoot("cmd package compile -m speed-profile -a")

        // Save timestamp
        val now = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date())
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putString("last_deep_opt", now)
            .apply()

        onComplete(now)
    }

    fun isScheduled(context: Context): Boolean {
        return context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getBoolean("daily_deep_opt_enabled", false)
    }
}
