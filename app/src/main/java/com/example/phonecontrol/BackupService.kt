package com.example.phonecontrol

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class BackupService : Service() {

    companion object {
        const val ACTION_BACKUP = "ACTION_BACKUP"
        const val ACTION_RESTORE = "ACTION_RESTORE"
        const val ACTION_BATCH_BACKUP = "ACTION_BATCH_BACKUP"
        const val ACTION_BATCH_RESTORE = "ACTION_BATCH_RESTORE"
        const val ACTION_VAULT_UPDATED = "com.example.phonecontrol.VAULT_UPDATED"

        private const val PROGRESS_CHANNEL_ID = "vault_progress_channel"
        private const val COMPLETE_CHANNEL_ID = "vault_complete_channel"
        private const val NOTIFICATION_ID_PROGRESS = 501
        private const val NOTIFICATION_ID_COMPLETE = 502
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        val initialNotif = buildProgressNotification("Vault Task Started", "Preparing queue...", 0, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID_PROGRESS, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID_PROGRESS, initialNotif)
        }

        val masterPath = intent?.getStringExtra("master_path") ?: BackupManager.getAutoVaultPath()
        val notes = intent?.getStringExtra("notes") ?: ""
        val includeApk = intent?.getBooleanExtra("include_apk", true) ?: true
        val includeData = intent?.getBooleanExtra("include_data", true) ?: true
        val includeObb = intent?.getBooleanExtra("include_obb", false) ?: false

        val restoreApk = intent?.getBooleanExtra("restore_apk", true) ?: true
        val restoreData = intent?.getBooleanExtra("restore_data", true) ?: true
        val restoreObb = intent?.getBooleanExtra("restore_obb", true) ?: true

        when (action) {
            ACTION_BATCH_BACKUP -> {
                val packages = intent.getStringArrayListExtra("package_list") ?: arrayListOf()
                startBatchBackup(packages, masterPath, notes, includeApk, includeData, includeObb)
            }
            ACTION_BATCH_RESTORE -> {
                val backupPaths = intent.getStringArrayListExtra("backup_paths") ?: arrayListOf()
                startBatchRestore(backupPaths, restoreApk, restoreData, restoreObb)
            }
            ACTION_BACKUP -> {
                val pkg = intent.getStringExtra("package_name") ?: ""
                startBatchBackup(arrayListOf(pkg), masterPath, notes, includeApk, includeData, includeObb)
            }
            ACTION_RESTORE -> {
                val backupPath = intent.getStringExtra("backup_path") ?: ""
                startBatchRestore(arrayListOf(backupPath), restoreApk, restoreData, restoreObb)
            }
            else -> {
                stopForeground(true)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startBatchBackup(
        packages: List<String>,
        path: String,
        notes: String,
        includeApk: Boolean,
        includeData: Boolean,
        includeObb: Boolean
    ) {
        thread {
            var successCount = 0
            var failCount = 0
            val total = packages.size

            try {
                for ((index, pkg) in packages.withIndex()) {
                    val appNum = index + 1
                    val info = AppBackupManager.getAppInfo(this, pkg)?.copy(notes = notes)
                    val appName = info?.appName ?: pkg

                    updateProgress("📦 Backing up ($appNum/$total)", "Processing $appName...", ((appNum - 1) * 100) / total)

                    if (info != null) {
                        val success = AppBackupManager.performBackup(this, info, path, includeApk, includeData, includeObb) { progress, status ->
                            val overall = (((appNum - 1) * 100) + progress) / total
                            updateProgress("📦 Backing up ($appNum/$total): $appName", "$status ($progress%)", overall)
                        }
                        if (success) successCount++ else failCount++
                    } else {
                        failCount++
                    }
                }

                val sizeOutput = ShellUtils.runAsRoot("du -sh $path 2>/dev/null | tail -n 1 | cut -f1").output.trim()
                val sizeText = if (sizeOutput.isNotBlank()) " • Total Vault: $sizeOutput" else ""

                if (successCount > 0) {
                    showCompletionNotification(
                        title = "🎉 Batch Backup Completed!",
                        text = "$successCount of $total apps backed up successfully.$sizeText${if (failCount > 0) " ($failCount failed)" else ""}"
                    )
                } else {
                    showFailureNotification("❌ Backup Failed", "Could not backup selected applications.")
                }

                sendBroadcast(Intent(ACTION_VAULT_UPDATED).setPackage(packageName))
            } catch (e: Exception) {
                Log.e("BackupService", "Batch Backup error", e)
                showFailureNotification("❌ Backup Error", e.localizedMessage ?: "Unknown error occurred.")
            } finally {
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private fun startBatchRestore(
        backupPaths: List<String>,
        restoreApk: Boolean,
        restoreData: Boolean,
        restoreObb: Boolean
    ) {
        thread {
            var successCount = 0
            var failCount = 0
            val total = backupPaths.size

            try {
                for ((index, bPath) in backupPaths.withIndex()) {
                    val appNum = index + 1
                    val infoFile = ShellUtils.runAsRoot("cat $bPath/info.json").output
                    val appName = try {
                        org.json.JSONObject(infoFile).optString("app_name", "App")
                    } catch (e: Exception) { "App" }

                    updateProgress("🔄 Restoring ($appNum/$total)", "Verifying $appName...", ((appNum - 1) * 100) / total)

                    val success = AppBackupManager.performRestore(this, bPath, restoreApk, restoreData, restoreObb) { progress, status ->
                        val overall = (((appNum - 1) * 100) + progress) / total
                        updateProgress("🔄 Restoring ($appNum/$total): $appName", "$status ($progress%)", overall)
                    }

                    if (success) successCount++ else failCount++
                }

                if (successCount > 0) {
                    showCompletionNotification(
                        title = "🎉 Batch Restore Completed!",
                        text = "$successCount of $total apps restored and permissions synced.${if (failCount > 0) " ($failCount failed)" else ""}"
                    )
                } else {
                    showFailureNotification("❌ Restore Failed", "Could not restore selected backups.")
                }

                sendBroadcast(Intent(ACTION_VAULT_UPDATED).setPackage(packageName))
            } catch (e: Exception) {
                Log.e("BackupService", "Batch Restore error", e)
                showFailureNotification("❌ Restore Error", e.localizedMessage ?: "Unknown error occurred.")
            } finally {
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private fun buildProgressNotification(title: String, text: String, progress: Int, indeterminate: Boolean): Notification {
        val openIntent = Intent(this, VaultActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, PROGRESS_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setProgress(100, progress, indeterminate)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun updateProgress(title: String, text: String, progress: Int) {
        val notification = buildProgressNotification(title, text, progress, false)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_PROGRESS, notification)
    }

    private fun showCompletionNotification(title: String, text: String) {
        val openIntent = Intent(this, AppRestoreListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 1, openIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, COMPLETE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_COMPLETE, notification)
    }

    private fun showFailureNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, COMPLETE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_COMPLETE + 1, notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val progressChannel = NotificationChannel(
                PROGRESS_CHANNEL_ID,
                "Vault Task Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live real-time progress for Backup and Restore operations"
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(progressChannel)

            val completeChannel = NotificationChannel(
                COMPLETE_CHANNEL_ID,
                "Vault Task Completion",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when Backup or Restore tasks finish successfully"
                enableVibration(true)
            }
            manager.createNotificationChannel(completeChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
