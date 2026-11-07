package com.example.phonecontrol

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AppExtractorManager {
    private const val TAG = "AppExtractorManager"
    const val EXTRACT_DIR = "/sdcard/PHONE_CONTROL/extracted_apks"
    const val BACKUP_DIR = "/sdcard/PHONE_CONTROL/backups"

    data class AppItem(
        val appName: String,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val icon: Drawable?,
        val apkPaths: List<String>,
        val totalSize: Long,
        val isSystemApp: Boolean,
        val isSplit: Boolean
    )

    data class ExtractResult(
        val success: Boolean,
        val outputPath: String?,
        val message: String
    )

    /**
     * Lists all installed apps with APK paths, split detection, and size calculation.
     */
    fun getInstalledApps(context: Context, includeSystem: Boolean = false): List<AppItem> {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        val list = mutableListOf<AppItem>()

        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            if (!includeSystem && isSystem) {
                // Skip core system apps unless requested
                continue
            }

            val appName = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                pkg.packageName
            }

            val apkPaths = mutableListOf<String>()
            val sourceDir = appInfo.sourceDir
            if (!sourceDir.isNullOrBlank()) {
                apkPaths.add(sourceDir)
            }
            appInfo.splitSourceDirs?.forEach { splitPath ->
                if (!splitPath.isNullOrBlank() && !apkPaths.contains(splitPath)) {
                    apkPaths.add(splitPath)
                }
            }

            // Fallback to pm path via root if split dirs not accessible
            if (apkPaths.isEmpty()) {
                val res = ShellUtils.runAsRoot("pm path ${pkg.packageName}", 5000)
                if (res.exitCode == 0) {
                    res.output.split("\n").forEach { line ->
                        val clean = line.trim().removePrefix("package:").trim()
                        if (clean.isNotBlank()) apkPaths.add(clean)
                    }
                }
            }

            var totalSize = 0L
            for (p in apkPaths) {
                val f = File(p)
                if (f.exists()) {
                    totalSize += f.length()
                }
            }

            val icon = try {
                pm.getApplicationIcon(appInfo)
            } catch (e: Exception) {
                null
            }

            list.add(
                AppItem(
                    appName = appName,
                    packageName = pkg.packageName,
                    versionName = pkg.versionName ?: "1.0",
                    versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        pkg.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        pkg.versionCode.toLong()
                    },
                    icon = icon,
                    apkPaths = apkPaths,
                    totalSize = totalSize,
                    isSystemApp = isSystem,
                    isSplit = apkPaths.size > 1
                )
            )
        }

        return list.sortedBy { it.appName.lowercase() }
    }

    /**
     * Extracts an installed app into a standalone .apk or bundle .apks archive in /sdcard/PHONE_CONTROL/extracted_apks/.
     */
    fun extractApp(context: Context, app: AppItem, onProgress: (String) -> Unit): ExtractResult {
        try {
            onProgress("📁 Preparing extraction destination...")
            ShellUtils.runAsRoot("mkdir -p '$EXTRACT_DIR' && chmod 777 '$EXTRACT_DIR'", 5000)

            val safeName = app.appName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val safeVersion = app.versionName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val baseFileName = "${safeName}_v${safeVersion}_${app.packageName}"

            // 1. Single APK extraction
            if (!app.isSplit && app.apkPaths.isNotEmpty()) {
                val outPath = "$EXTRACT_DIR/$baseFileName.apk"
                onProgress("📤 Copying base APK (${formatSize(app.totalSize)})...")
                val src = app.apkPaths[0]
                val copyCmd = "cp '$src' '$outPath' && chmod 775 '$outPath'"
                val res = ShellUtils.runAsRoot(copyCmd, 30000)

                if (res.exitCode == 0 && File(outPath).exists()) {
                    return ExtractResult(true, outPath, "Exported successfully as standalone APK.")
                } else {
                    return ExtractResult(false, null, "Failed to copy APK: ${res.output}")
                }
            }

            // 2. Multi-Split APK bundle extraction (.apks)
            val outApks = "$EXTRACT_DIR/$baseFileName.apks"
            onProgress("📦 Packaging ${app.apkPaths.size} split APKs into .apks bundle...")

            // Stage in app cache directory then zip natively using Java ZipOutputStream
            val staging = File(context.cacheDir, "extractor_staging_${System.currentTimeMillis()}")
            ShellUtils.runAsRoot("mkdir -p '${staging.absolutePath}' && chmod 777 '${staging.absolutePath}'", 5000)

            for ((i, apkPath) in app.apkPaths.withIndex()) {
                val fileName = File(apkPath).name
                onProgress("Adding split [${i + 1}/${app.apkPaths.size}]: $fileName...")
                ShellUtils.runAsRoot("cp '$apkPath' '${staging.absolutePath}/$fileName' && chmod 666 '${staging.absolutePath}/$fileName'", 15000)
            }

            onProgress("🗜️ Compressing splits into standard .apks bundle...")
            var zipSuccess = false
            val localZip = File(context.cacheDir, "temp_bundle_${System.currentTimeMillis()}.apks")
            try {
                val stagingFiles = staging.listFiles()?.filter { it.isFile } ?: emptyList()
                if (stagingFiles.isNotEmpty()) {
                    ZipOutputStream(java.io.BufferedOutputStream(FileOutputStream(localZip))).use { zos ->
                        val buffer = ByteArray(64 * 1024)
                        for (f in stagingFiles) {
                            val entry = ZipEntry(f.name)
                            zos.putNextEntry(entry)
                            FileInputStream(f).use { fis ->
                                var len: Int
                                while (fis.read(buffer).also { len = it } > 0) {
                                    zos.write(buffer, 0, len)
                                }
                            }
                            zos.closeEntry()
                        }
                    }
                    if (localZip.exists() && localZip.length() > 0) {
                        val qLocal = ShellUtils.shellQuote(localZip.absolutePath)
                        val qOut = ShellUtils.shellQuote(outApks)
                        val copyRes = ShellUtils.runAsRoot("cp $qLocal $qOut && chmod 775 $qOut", 15000)
                        zipSuccess = copyRes.exitCode == 0
                    }
                }
            } catch (ze: Exception) {
                Log.e(TAG, "Zip creation failed", ze)
            } finally {
                try { if (localZip.exists()) localZip.delete() } catch (_: Exception) {}
                // Cleanup staging
                ShellUtils.runAsRoot("rm -rf " + ShellUtils.shellQuote(staging.absolutePath), 10000)
            }

            if (zipSuccess) {
                return ExtractResult(true, outApks, "Successfully bundled ${app.apkPaths.size} splits into .apks.")
            } else {
                // Fallback: copy individual splits to a folder
                val outFolder = "$EXTRACT_DIR/$baseFileName"
                ShellUtils.runAsRoot("mkdir -p '$outFolder'", 5000)
                for (p in app.apkPaths) {
                    ShellUtils.runAsRoot("cp '$p' '$outFolder/'", 15000)
                }
                ShellUtils.runAsRoot("chmod -R 775 '$outFolder'", 5000)
                return ExtractResult(true, outFolder, "Exported ${app.apkPaths.size} splits to directory.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed for ${app.packageName}", e)
            return ExtractResult(false, null, "Extraction error: ${e.message}")
        }
    }

    /**
     * Backs up complete app APKs (including all splits) and /data/data app data into .backup.tar.gz
     */
    fun backupAppWithData(context: Context, app: AppItem, onProgress: (String) -> Unit): ExtractResult {
        try {
            onProgress("📁 Preparing backup directory...")
            ShellUtils.runAsRoot("mkdir -p '$BACKUP_DIR' && chmod 777 '$BACKUP_DIR'", 5000)

            val safeName = app.appName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val safeVersion = app.versionName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val baseFileName = "${safeName}_v${safeVersion}_${app.packageName}"
            val outArchive = "$BACKUP_DIR/$baseFileName.backup.tar.gz"

            onProgress("⏸️ Pausing application session...")
            ShellUtils.runAsRoot("am force-stop ${app.packageName}", 5000)

            val staging = File(context.cacheDir, "backup_staging_${System.currentTimeMillis()}")
            ShellUtils.runAsRoot("mkdir -p '${staging.absolutePath}/apks' '${staging.absolutePath}/data'", 5000)

            // 1. Copy APKs
            onProgress("📤 Archiving application APKs (${app.apkPaths.size} files)...")
            for (p in app.apkPaths) {
                val fName = File(p).name
                ShellUtils.runAsRoot("cp '$p' '${staging.absolutePath}/apks/$fName'", 15000)
            }

            // 2. Archive data directory excluding caches
            onProgress("💾 Archiving application data (/data/data/${app.packageName})...")
            val dataPath = "/data/data/${app.packageName}"
            val copyDataCmd = "if [ -d '$dataPath' ]; then cp -r '$dataPath' '${staging.absolutePath}/data/'; rm -rf '${staging.absolutePath}/data/${app.packageName}/cache' '${staging.absolutePath}/data/${app.packageName}/code_cache'; fi"
            ShellUtils.runAsRoot(copyDataCmd, 30000)

            // 3. Compress into .backup.tar.gz
            onProgress("🗜️ Compressing complete application bundle...")
            val tarCmd = "cd '${staging.absolutePath}' && tar -czf '$outArchive' apks data && chmod 775 '$outArchive'"
            val res = ShellUtils.runAsRoot(tarCmd, 60000)

            // Cleanup staging
            ShellUtils.runAsRoot("rm -rf '${staging.absolutePath}'", 10000)

            if (res.exitCode == 0 && File(outArchive).exists()) {
                val bSize = File(outArchive).length()
                return ExtractResult(true, outArchive, "Complete backup created (${formatSize(bSize)}).")
            } else {
                return ExtractResult(false, null, "Failed to create backup archive: ${res.output}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed for ${app.packageName}", e)
            return ExtractResult(false, null, "Backup error: ${e.message}")
        }
    }

    /**
     * Restores application APKs and /data/data state from a .backup.tar.gz archive.
     */
    fun restoreAppWithData(context: Context, packageName: String, backupFilePath: String, onProgress: (String) -> Unit): Boolean {
        try {
            onProgress("📦 Unpacking backup archive...")
            val staging = File(context.cacheDir, "restore_staging_${System.currentTimeMillis()}")
            ShellUtils.runAsRoot("mkdir -p '${staging.absolutePath}'", 5000)

            val untarCmd = "tar -xzf '$backupFilePath' -C '${staging.absolutePath}'"
            val res = ShellUtils.runAsRoot(untarCmd, 60000)
            if (res.exitCode != 0) {
                ShellUtils.runAsRoot("rm -rf '${staging.absolutePath}'", 5000)
                return false
            }

            // 1. Install APKs if present
            onProgress("📲 Installing application APKs...")
            val apksFolder = "${staging.absolutePath}/apks"
            val installCmd = "pm install-create -r -t -d -g"
            val sessionRes = ShellUtils.runAsRoot(installCmd, 10000)
            val sessionId = Regex("\\[(\\d+)\\]").find(sessionRes.output)?.groupValues?.get(1)

            if (sessionId != null) {
                val writeCmd = "for f in '$apksFolder'/*.apk; do pm install-write -S \$(stat -c %s \"\$f\") $sessionId \$(basename \"\$f\") \"\$f\"; done"
                ShellUtils.runAsRoot(writeCmd, 30000)
                ShellUtils.runAsRoot("pm install-commit $sessionId", 30000)
            } else {
                ShellUtils.runAsRoot("pm install -r -d -g '$apksFolder'/*.apk", 30000)
            }

            // 2. Restore app data
            onProgress("📥 Restoring app data & fixing permissions...")
            ShellUtils.runAsRoot("am force-stop $packageName", 5000)

            val uidRes = ShellUtils.fastCmdResult("dumpsys package $packageName | grep userId= | head -n 1", 3000)
            val uid = Regex("userId=(\\d+)").find(uidRes)?.groupValues?.get(1) ?: "10000"

            val restoreDataCmd = """
                if [ -d '${staging.absolutePath}/data/$packageName' ]; then
                    cp -r -p '${staging.absolutePath}/data/$packageName'/* '/data/data/$packageName/' 2>/dev/null
                    chown -R $uid:$uid '/data/data/$packageName'
                    chmod -R 771 '/data/data/$packageName'
                    restorecon -R '/data/data/$packageName'
                fi
            """.trimIndent()
            ShellUtils.runAsRoot(restoreDataCmd, 30000)

            ShellUtils.runAsRoot("rm -rf '${staging.absolutePath}'", 5000)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed for $packageName", e)
            return false
        }
    }

    fun getFolderSize(dir: File): Long {
        var size: Long = 0
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return size
    }

    fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            String.format("%.2f GB", mb / 1024.0)
        } else {
            String.format("%.1f MB", mb)
        }
    }
}
