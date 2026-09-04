package com.example.phonecontrol

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

object DeepOptManager {

    /**
     * Performs a deep system-level maintenance.
     * @param onProgress Callback to update UI with current task description.
     * @param onComplete Callback with the timestamp of completion.
     */
    fun runFullOptimization(context: Context, onProgress: (String) -> Unit = {}, onComplete: (String) -> Unit = {}) {
        
        // 1. Purge Kernel Caches
        onProgress("Purging Kernel Caches (Recovering RAM)...")
        ShellUtils.fastCmd("sync") // Flush dirty buffers first
        ShellUtils.fastCmd("echo 3 > /proc/sys/vm/drop_caches")
        Thread.sleep(1000)

        // 2. Junk Cleaning
        onProgress("Cleaning System Junk & Logs...")
        val cleanCmds = listOf(
            "rm -rf /data/tombstones/*",
            "rm -rf /data/anr/*",
            "rm -rf /data/system/dropbox/*",
            "rm -rf /cache/*",
            "rm -rf /data/system/usagestats/*",
            "rm -rf /data/system/package_cache/*"
        )
        ShellUtils.runCommandsAsRoot(cleanCmds)

        // 3. SQLite Database Vacuuming (Compacting System DBs)
        onProgress("Optimizing System Databases...")
        // Only vacuum the most important ones manually to save time
        val dbPaths = listOf(
            "/data/system/notification_policy.db",
            "/data/user/0/com.android.providers.settings/databases/settings.db",
            "/data/user/0/com.android.providers.contacts/databases/contacts2.db"
        )
        for (path in dbPaths) {
            ShellUtils.runAsRoot("sqlite3 $path 'VACUUM;' 2>/dev/null")
        }

        // 4. File System Trim (UFS/EMMC Refresh)
        onProgress("Refreshing Storage (FSTRIM)...")
        ShellUtils.runAsRoot("fstrim -v /data")

        // 5. Aggressive ART Cache Compilation
        val mode = if (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) == 3) "everything" else "speed-profile"
        onProgress("Optimizing App Execution ($mode)...")
        ShellUtils.runAsRoot("cmd package compile -m $mode -a")

        // 6. Final Sync
        onProgress("Finalizing Maintenance...")
        ShellUtils.fastCmd("sync")

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
