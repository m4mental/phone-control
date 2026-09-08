package com.example.phonecontrol

import android.content.Context
import android.util.Log

object StorageManager {

    /**
     * Executes FSTRIM on /data partition to refresh UFS/EMMC speed.
     */
    fun runFsTrim(): String {
        val result = ShellUtils.runAsRoot("sm fstrim")
        if (result.exitCode == 0) {
            return "UFS Storage Trim completed successfully via Android StorageManager service."
        }
        val fallback = ShellUtils.runAsRoot("fstrim -v /data")
        return if (fallback.exitCode == 0) fallback.output else "Failed to run FSTRIM: ${result.output.ifBlank { fallback.output }}"
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

    data class OrphanedItem(
        val packageName: String,
        val path: String,
        val sizeBytes: Long,
        val sizeReadable: String
    )

    data class OrphanScanResult(
        val totalBytes: Long,
        val totalReadable: String,
        val items: List<OrphanedItem>
    )

    /**
     * Scans /sdcard/Android/obb/ and /sdcard/Android/data/ for residual directories
     * belonging to uninstalled packages.
     */
    fun scanOrphanedResidue(context: Context, onProgress: (String) -> Unit): OrphanScanResult {
        onProgress("Reading installed package registry...")
        val installed = try {
            context.packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }

        val scanPaths = listOf("/sdcard/Android/obb", "/sdcard/Android/data")
        val orphanedList = mutableListOf<OrphanedItem>()
        var grandTotalBytes = 0L

        for (basePath in scanPaths) {
            onProgress("Scanning $basePath for uninstalled ghosts...")
            val listOutput = ShellUtils.runAsRoot("ls -1 '$basePath' 2>/dev/null").output
            val dirNames = listOutput.split("\n").map { it.trim() }.filter { it.isNotBlank() }

            for (dir in dirNames) {
                if (dir.startsWith(".") || !dir.contains(".")) continue

                if (!installed.contains(dir)) {
                    val fullPath = "$basePath/$dir"
                    onProgress("Inspecting leftover: $dir...")
                    val sizeOut = ShellUtils.runAsRoot("du -sb '$fullPath' 2>/dev/null | cut -f1").output.trim()
                    val bytes = sizeOut.toLongOrNull() ?: 0L
                    if (bytes > 0) {
                        grandTotalBytes += bytes
                        orphanedList.add(
                            OrphanedItem(
                                packageName = dir,
                                path = fullPath,
                                sizeBytes = bytes,
                                sizeReadable = formatSize(bytes)
                            )
                        )
                    }
                }
            }
        }

        return OrphanScanResult(
            totalBytes = grandTotalBytes,
            totalReadable = formatSize(grandTotalBytes),
            items = orphanedList
        )
    }

    /**
     * Deletes orphaned ghost residue folders and reclaims disk space.
     */
    fun cleanOrphanedResidue(items: List<OrphanedItem>, onProgress: (String) -> Unit): Long {
        var freedBytes = 0L
        for (item in items) {
            onProgress("Deleting ghost residue: ${item.packageName} (${item.sizeReadable})...")
            val res = ShellUtils.runAsRoot("rm -rf '${item.path}'", 15000)
            if (res.exitCode == 0) {
                freedBytes += item.sizeBytes
            }
        }
        ShellUtils.runAsRoot("sync", 10000)
        return freedBytes
    }

    data class ChatJunkItem(
        val name: String,
        val path: String,
        val appName: String,
        val fileCount: Int,
        val sizeBytes: Long,
        val sizeReadable: String
    )

    data class ChatScanResult(
        val totalFiles: Int,
        val totalBytes: Long,
        val totalSizeReadable: String,
        val items: List<ChatJunkItem>
    )

    /**
     * Scans WhatsApp and Telegram for redundant duplicate 'Sent' files, sticker cache,
     * and voice/status temporary cache without touching personal received media.
     */
    fun scanChatJunk(onProgress: (String) -> Unit): ChatScanResult {
        val targets = listOf(
            Triple("WhatsApp Sent Videos", "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/Sent", "WhatsApp"),
            Triple("WhatsApp Sent Images", "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent", "WhatsApp"),
            Triple("WhatsApp Sent Audio", "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio/Sent", "WhatsApp"),
            Triple("WhatsApp Sent Documents", "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/Sent", "WhatsApp"),
            Triple("WhatsApp Sent GIFs", "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Animated Gifs/Sent", "WhatsApp"),
            Triple("WhatsApp Sticker Cache", "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers", "WhatsApp"),
            Triple("WhatsApp Status Cache", "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/.Statuses", "WhatsApp"),
            Triple("WhatsApp Business Sent Media", "/sdcard/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Video/Sent", "WhatsApp Business"),
            Triple("Telegram Cache", "/sdcard/Android/data/org.telegram.messenger/cache", "Telegram"),
            Triple("Telegram Web Cache", "/sdcard/Android/data/org.telegram.messenger.web/cache", "Telegram"),
            Triple("Telegram Plus Cache", "/sdcard/Android/data/org.telegram.plus/cache", "Telegram Plus"),
            Triple("Telegram X Cache", "/sdcard/Android/data/org.thunderdog.challegram/cache", "Telegram X"),
            Triple("Legacy WhatsApp Sent Videos", "/sdcard/WhatsApp/Media/WhatsApp Video/Sent", "WhatsApp"),
            Triple("Legacy WhatsApp Sent Images", "/sdcard/WhatsApp/Media/WhatsApp Images/Sent", "WhatsApp")
        )

        val junkList = mutableListOf<ChatJunkItem>()
        var totalBytes = 0L
        var totalFiles = 0

        for ((name, path, app) in targets) {
            onProgress("Scanning $name...")
            val checkRes = ShellUtils.runAsRoot("test -d '$path' && echo exists", 5000)
            if (checkRes.output.contains("exists")) {
                val countRes = ShellUtils.runAsRoot("find '$path' -type f 2>/dev/null | wc -l", 10000)
                val count = countRes.output.trim().toIntOrNull() ?: 0
                if (count > 0) {
                    val duRes = ShellUtils.runAsRoot("du -sb '$path' 2>/dev/null | cut -f1", 10000)
                    val size = duRes.output.trim().toLongOrNull() ?: 0L
                    if (size > 0) {
                        junkList.add(
                            ChatJunkItem(
                                name = name,
                                path = path,
                                appName = app,
                                fileCount = count,
                                sizeBytes = size,
                                sizeReadable = formatSize(size)
                            )
                        )
                        totalBytes += size
                        totalFiles += count
                    }
                }
            }
        }

        return ChatScanResult(
            totalFiles = totalFiles,
            totalBytes = totalBytes,
            totalSizeReadable = formatSize(totalBytes),
            items = junkList
        )
    }

    /**
     * Safely purges redundant duplicate Sent files and temporary chat caches.
     */
    fun cleanChatJunk(items: List<ChatJunkItem>, onProgress: (String) -> Unit): Long {
        var freed = 0L
        for (item in items) {
            onProgress("Cleaning ${item.name} (${item.sizeReadable})...")
            val res = ShellUtils.runAsRoot("find '${item.path}' -mindepth 1 -delete", 25000)
            if (res.exitCode == 0) {
                freed += item.sizeBytes
            }
        }
        ShellUtils.runAsRoot("sync", 10000)
        return freed
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format(java.util.Locale.US, "%d KB", bytes / 1024)
            else -> "$bytes B"
        }
    }
}
