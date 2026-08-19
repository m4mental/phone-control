package com.example.phonecontrol

import android.content.Context
import android.util.Log

object StorageManager {

    /**
     * Executes FSTRIM on /data partition to refresh UFS/EMMC speed.
     */
    fun runFsTrim(): String {
        val result = ShellUtils.runAsRoot("fstrim -v /data")
        return if (result.exitCode == 0) result.output else "Failed to run FSTRIM"
    }

    /**
     * Optimizes SQLite databases for key system and user apps.
     */
    fun vacuumDatabases(onProgress: (String) -> Unit): Int {
        var optimizedCount = 0
        val dbPaths = listOf(
            "/data/user/0/com.android.providers.settings/databases/settings.db",
            "/data/user/0/com.android.providers.contacts/databases/contacts2.db",
            "/data/user/0/com.whatsapp/databases/msgstore.db",
            "/data/user/0/com.whatsapp/databases/wa.db",
            "/data/user/0/com.instagram.android/databases/direct.db"
        )

        for (path in dbPaths) {
            onProgress("Optimizing: ${path.substringAfterLast("/")}")
            val result = ShellUtils.runAsRoot("sqlite3 $path 'VACUUM;'")
            if (result.exitCode == 0) optimizedCount++
        }
        return optimizedCount
    }

    /**
     * Boosts Read-Ahead cache and sets I/O scheduler to mq-deadline for better multitasking.
     */
    fun applyStorageBoost(enabled: Boolean) {
        val readAhead = if (enabled) "2048" else "512"
        val scheduler = if (enabled) "mq-deadline" else "none" // 'none' or 'cfq' usually default
        
        val storageBlocks = listOf("sda", "sdb", "sdc", "mmcblk0", "dm-0")
        for (block in storageBlocks) {
            ShellUtils.fastCmd("echo $readAhead > /sys/block/$block/queue/read_ahead_kb 2>/dev/null")
            ShellUtils.fastCmd("echo $scheduler > /sys/block/$block/queue/scheduler 2>/dev/null")
            
            if (enabled) {
                ShellUtils.fastCmd("echo 0 > /sys/block/$block/queue/add_random 2>/dev/null")
                ShellUtils.fastCmd("echo 1 > /sys/block/$block/queue/nomerges 2>/dev/null")
            }
        }
    }
}
