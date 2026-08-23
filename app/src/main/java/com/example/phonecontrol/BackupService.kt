package com.example.phonecontrol

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class BackupService : Service() {

    private val CHANNEL_ID = "backup_service_channel"
    private val NOTIFICATION_ID = 501

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val appName = intent?.getStringExtra("app_name") ?: "Task"
        
        // Immediate startForeground to prevent system crash
        val notification = createNotification("Initializing $appName...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val packageName = intent?.getStringExtra("package_name") ?: ""
        val masterPath = intent?.getStringExtra("master_path") ?: BackupManager.getAutoVaultPath()
        val backupPath = intent?.getStringExtra("backup_path") ?: ""
        val notes = intent?.getStringExtra("notes") ?: ""
        val includeApk = intent?.getBooleanExtra("include_apk", true) ?: true
        val includeData = intent?.getBooleanExtra("include_data", true) ?: true
        val includeObb = intent?.getBooleanExtra("include_obb", false) ?: false

        if (action == "ACTION_BACKUP") {
            startBackup(packageName, appName, masterPath, notes, includeApk, includeData, includeObb)
        } else if (action == "ACTION_RESTORE") {
            startRestore(backupPath, appName)
        } else {
            // Safety: Always call startForeground if service was started as foreground
            val notification = createNotification("Service running...")
            startForeground(NOTIFICATION_ID, notification)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startBackup(pkg: String, name: String, path: String, notes: String, includeApk: Boolean, includeData: Boolean, includeObb: Boolean) {
        thread {
            try {
                updateNotification("Preparing Backup", name, 0)
                val info = AppBackupManager.getAppInfo(this, pkg)?.copy(notes = notes)
                if (info != null) {
                    val success = AppBackupManager.performBackup(this, info, path, includeApk, includeData, includeObb) { progress, status ->
                        updateNotification("Backing up $name", status, progress)
                    }
                    finishService(if (success) "Backup Successful: $name" else "Backup Failed: $name")
                } else {
                    finishService("Failed to read app info for $pkg")
                }
            } catch (e: Exception) {
                Log.e("BackupService", "Backup crashed", e)
                finishService("Critical Error: ${e.message}")
            }
        }
    }

    private fun startRestore(path: String, name: String) {
        thread {
            try {
                updateNotification("Preparing Restore", name, 0)
                val success = AppBackupManager.performRestore(this, path) { progress, status ->
                    updateNotification("Restoring $name", status, progress)
                }
                finishService(if (success) "Restore Successful: $name" else "Restore Failed: $name")
            } catch (e: Exception) {
                Log.e("BackupService", "Restore crashed", e)
                finishService("Critical Error: ${e.message}")
            }
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("App Vault")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()
    }

    private fun updateNotification(title: String, text: String, progress: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun finishService(result: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Task Finished")
            .setContentText(result)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        stopForeground(true)
        manager.notify(NOTIFICATION_ID + 1, notification)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Backup Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
