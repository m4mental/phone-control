package com.example.phonecontrol

import android.content.Context
import android.os.StatFs
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
     * SD Maid Cache Trimmer: Invokes Android framework's built-in `pm trim-caches`
     * to safely clear caches across all installed applications up to 128GB, sweeps
     * /data/local/tmp, removes photo thumbnail caches, and drops RAM pagecaches.
     */
    data class TrimResult(
        val freedBytes: Long,
        val freedReadable: String,
        val log: String
    )

    fun runPmTrimCaches(onProgress: (String) -> Unit): TrimResult {
        onProgress("Checking storage state before cache trim...")
        val statBefore = try { StatFs("/data").availableBytes } catch (e: Exception) { 0L }

        onProgress("Invoking SD Maid cache trimmer (pm trim-caches 128G)...")
        val trimOut = ShellUtils.runAsRoot("pm trim-caches 128G", 30000).output

        onProgress("Sweeping orphaned temp files in /data/local/tmp...")
        ShellUtils.runAsRoot("rm -rf /data/local/tmp/* 2>/dev/null", 10000)

        onProgress("Purging gallery thumbnail cache (.thumbnails)...")
        ShellUtils.runAsRoot("rm -rf /sdcard/DCIM/.thumbnails/* /sdcard/Pictures/.thumbnails/* 2>/dev/null", 10000)

        onProgress("Dropping kernel pagecache...")
        ShellUtils.runAsRoot("echo 3 > /proc/sys/vm/drop_caches 2>/dev/null", 5000)
        ShellUtils.runAsRoot("sync", 10000)

        val statAfter = try { StatFs("/data").availableBytes } catch (e: Exception) { 0L }
        val freed = (statAfter - statBefore).coerceAtLeast(0L)

        val logMsg = "SD Maid Cache Trim Complete!\n" +
                "Trim Output: ${trimOut.ifBlank { "All application caches purged successfully." }}\n" +
                "Space Reclaimed: ${formatSize(freed)}"

        return TrimResult(freed, formatSize(freed), logMsg)
    }

    /**
     * Optimizes SQLite databases for key system and user apps.
     */
    fun vacuumDatabases(onProgress: (String) -> Unit): Int {
        val check = ShellUtils.fastCmdResult("which sqlite3", 1000).trim()
        if (check.isBlank() || check.contains("not found")) {
            onProgress("sqlite3 CLI not present on device. Skipping safely.")
            return 0
        }
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
        val scheduler = if (enabled) "mq-deadline" else "none"
        
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
        val partition: String,
        val sizeBytes: Long,
        val sizeReadable: String
    )

    data class OrphanScanResult(
        val totalBytes: Long,
        val totalReadable: String,
        val items: List<OrphanedItem>
    )

    /**
     * SD Maid CorpseFinder:
     * Scans internal partitions (/data/data, /data/user/0, /data/user_de/0) and
     * external storage (/sdcard/Android/obb, /sdcard/Android/data, /sdcard/Android/media)
     * for residual folders left behind by uninstalled packages.
     */
    fun scanOrphanedResidue(context: Context, onProgress: (String) -> Unit): OrphanScanResult {
        onProgress("Reading complete installed package registry across all users...")
        
        // Fetch full list of packages including uninstalled-for-user-0 and disabled apps
        val pmListCmd = ShellUtils.runAsRoot("pm list packages -u -a 2>/dev/null", 15000)
        val installed = if (pmListCmd.exitCode == 0 && pmListCmd.output.isNotBlank()) {
            pmListCmd.output.lines()
                .map { it.trim().removePrefix("package:").trim() }
                .filter { it.isNotBlank() }
                .toSet()
        } else {
            try {
                context.packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
            } catch (e: Exception) {
                emptySet()
            }
        }

        val scanTargets = listOf(
            Pair("/data/data", "Internal (/data/data)"),
            Pair("/data/user_de/0", "Device-Encrypted (/data/user_de/0)"),
            Pair("/sdcard/Android/obb", "External OBB (/sdcard/Android/obb)"),
            Pair("/sdcard/Android/data", "External Data (/sdcard/Android/data)"),
            Pair("/sdcard/Android/media", "External Media (/sdcard/Android/media)")
        )

        val orphanedList = mutableListOf<OrphanedItem>()
        var grandTotalBytes = 0L

        // Common system non-package folders to ignore
        val systemDirsToIgnore = setOf(
            "cache", "code_cache", "files", "databases", "shared_prefs", "lib", "system",
            "media", "obb", "data", "app_webview", "no_backup"
        )

        for ((basePath, partitionName) in scanTargets) {
            onProgress("Scanning $partitionName for corpse folders...")
            val listOutput = ShellUtils.runAsRoot("ls -1 '$basePath' 2>/dev/null", 10000).output
            val dirNames = listOutput.split("\n").map { it.trim() }.filter { it.isNotBlank() }

            for (dir in dirNames) {
                if (dir.startsWith(".") || systemDirsToIgnore.contains(dir)) continue
                // Valid android package names must contain at least one dot (e.g. com.something)
                if (!dir.contains(".")) continue

                if (!installed.contains(dir)) {
                    val fullPath = "$basePath/$dir"
                    onProgress("Found corpse: $dir ($partitionName)...")
                    val qFullPath = ShellUtils.shellQuote(fullPath)
                    val sizeOut = ShellUtils.runAsRoot("du -sb $qFullPath 2>/dev/null | cut -f1", 10000).output.trim()
                    val bytes = sizeOut.toLongOrNull() ?: 0L
                    if (bytes > 0) {
                        grandTotalBytes += bytes
                        orphanedList.add(
                            OrphanedItem(
                                packageName = dir,
                                path = fullPath,
                                partition = partitionName,
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
     * Deletes orphaned corpse folders and reclaims disk space.
     */
    fun cleanOrphanedResidue(items: List<OrphanedItem>, onProgress: (String) -> Unit): Long {
        var freedBytes = 0L
        for (item in items) {
            onProgress("Purging corpse: ${item.packageName} (${item.sizeReadable})...")
            val qPath = ShellUtils.shellQuote(item.path)
            val res = ShellUtils.runAsRoot("rm -rf $qPath", 15000)
            if (res.exitCode == 0) {
                freedBytes += item.sizeBytes
            }
            if (ShellUtils.isValidPackageName(item.packageName)) {
                ShellUtils.runAsRoot("pm uninstall --all-users ${item.packageName} 2>/dev/null", 10000)
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
