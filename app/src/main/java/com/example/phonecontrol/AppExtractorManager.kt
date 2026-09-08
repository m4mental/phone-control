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
            try {
                val stagingFiles = staging.listFiles()?.filter { it.isFile } ?: emptyList()
                if (stagingFiles.isNotEmpty()) {
                    ZipOutputStream(java.io.BufferedOutputStream(FileOutputStream(outApks))).use { zos ->
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
                    ShellUtils.runAsRoot("chmod 775 '$outApks'", 5000)
                    zipSuccess = File(outApks).exists() && File(outApks).length() > 0
                }
            } catch (ze: Exception) {
                Log.e(TAG, "Zip creation failed", ze)
            } finally {
                // Cleanup staging
                ShellUtils.runAsRoot("rm -rf '${staging.absolutePath}'", 10000)
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
