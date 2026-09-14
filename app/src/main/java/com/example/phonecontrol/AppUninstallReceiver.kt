package com.example.phonecontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

/**
 * Automatically detects when an application is uninstalled from the device.
 * Checks for leftover residual directories in /sdcard/Android/obb and /sdcard/Android/data
 * and safely removes ghost data to prevent storage bloat.
 */
class AppUninstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppUninstallReceiver"
        private const val CHANNEL_ID = "phone_control_storage_channel"
        private const val NOTIFICATION_ID = 8842
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return

        val pkgName = intent.data?.schemeSpecificPart ?: return
        if (pkgName.isBlank() || pkgName == context.packageName) return

        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val isAutoCleanEnabled = prefs.getBoolean("auto_clean_ghost_residue", true)
        if (!isAutoCleanEnabled) {
            Log.d(TAG, "📦 App uninstalled ($pkgName), but auto-clean ghost residue is disabled.")
            return
        }

        thread {
            try {
                Log.d(TAG, "🔍 App uninstalled: $pkgName. Checking for residual ghost folders...")
                val obbPath = "/sdcard/Android/obb/$pkgName"
                val dataPath = "/sdcard/Android/data/$pkgName"

                var totalFreedBytes = 0L
                val pathsToClean = mutableListOf<String>()

                for (path in listOf(obbPath, dataPath)) {
                    val checkRes = ShellUtils.runAsRoot("if [ -d '$path' ]; then du -sb '$path' 2>/dev/null | cut -f1; fi")
                    val bytes = checkRes.output.trim().toLongOrNull() ?: 0L
                    if (bytes > 0) {
                        totalFreedBytes += bytes
                        pathsToClean.add(path)
                    }
                }

                if (pathsToClean.isNotEmpty() && totalFreedBytes > 0) {
                    val sizeReadable = StorageManager.formatSize(totalFreedBytes)
                    Log.d(TAG, "🧹 Found ${pathsToClean.size} leftover ghost folders ($sizeReadable) for $pkgName. Cleaning...")

                    for (p in pathsToClean) {
                        ShellUtils.runAsRoot("rm -rf '$p'")
                    }
                    ShellUtils.runAsRoot("sync")

                    Log.d(TAG, "✅ Cleaned $sizeReadable ghost residue for uninstalled app: $pkgName")
                    showCleanedNotification(context, pkgName, sizeReadable)
                } else {
                    Log.d(TAG, "✨ No residual folders found for $pkgName.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking/cleaning residue for $pkgName: ${e.message}")
            }
        }
    }

    private fun showCleanedNotification(context: Context, pkgName: String, freedSize: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Storage & Residue Cleaner",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Alerts when uninstalled app residue is automatically cleaned"
                }
                nm.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_delete)
                .setContentTitle("Ghost App Residue Cleaned")
                .setContentText("Cleaned $freedSize leftover data for uninstalled app ($pkgName)")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Cleaned $freedSize leftover data for uninstalled app ($pkgName) to keep your UFS storage clean."))
                .setAutoCancel(true)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show residue clean notification: ${e.message}")
        }
    }
}
