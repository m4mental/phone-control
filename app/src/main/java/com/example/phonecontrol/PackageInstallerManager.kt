package com.example.phonecontrol

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object PackageInstallerManager {

    data class InstallResult(
        val success: Boolean,
        val message: String,
        val rawOutput: String,
        val isSignatureConflict: Boolean = false,
        val isDowngradeConflict: Boolean = false,
        val conflictPackage: String? = null,
        val installedPackage: String? = null,
        val backupPath: String? = null,
        val failureTitle: String? = null,
        val failureExplanation: String? = null
    )

    data class ApkInspection(
        val appName: String,
        val packageName: String,
        val sharedUserId: String?,
        val splitNames: List<String>,
        val incomingVersionName: String,
        val incomingVersionCode: Long,
        val installedVersionName: String?,
        val installedVersionCode: Long?,
        val minSdk: Int,
        val minSdkLabel: String,
        val targetSdk: Int,
        val targetSdkLabel: String,
        val maxSdk: Int?,
        val maxSdkLabel: String,
        val packageTypeLabel: String,
        val fileSizeFormatted: String,
        val icon: Drawable?,
        val sensitivePermissions: List<String>,
        val trackers: List<String>,
        val isInstalled: Boolean,
        val isDowngrade: Boolean,
        val isSameVersion: Boolean
    )

    fun createFallbackInspection(fileName: String): ApkInspection {
        val cleanName = fileName.substringBeforeLast(".")
        return ApkInspection(
            appName = cleanName,
            packageName = cleanName,
            sharedUserId = null,
            splitNames = emptyList(),
            incomingVersionName = "1.0",
            incomingVersionCode = 1L,
            installedVersionName = null,
            installedVersionCode = null,
            minSdk = 21,
            minSdkLabel = "API 21+",
            targetSdk = 34,
            targetSdkLabel = "API 34",
            maxSdk = null,
            maxSdkLabel = "No Limit",
            packageTypeLabel = "Direct Package",
            fileSizeFormatted = "--",
            icon = null,
            sensitivePermissions = emptyList(),
            trackers = emptyList(),
            isInstalled = false,
            isDowngrade = false,
            isSameVersion = false
        )
    }

    fun getAndroidCodename(sdk: Int): String {
        return when (sdk) {
            36 -> "Android 16"
            35 -> "Android 15"
            34 -> "Android 14"
            33 -> "Android 13"
            32 -> "Android 12L"
            31 -> "Android 12"
            30 -> "Android 11"
            29 -> "Android 10"
            28 -> "Android 9.0 Pie"
            27 -> "Android 8.1 Oreo"
            26 -> "Android 8.0 Oreo"
            25 -> "Android 7.1 Nougat"
            24 -> "Android 7.0 Nougat"
            23 -> "Android 6.0 Marshmallow"
            22 -> "Android 5.1 Lollipop"
            21 -> "Android 5.0 Lollipop"
            else -> if (sdk > 36) "Android API $sdk" else "API $sdk"
        }
    }

    private val TRACKER_PATTERNS by lazy {
        mapOf(
            "Google AdMob" to listOf("Y29tL2dvb2dsZS9hbmRyb2lkL2dtcy9hZHM=", "Y29tLmdvb2dsZS5hbmRyb2lkLmdtcy5hZHM="),
            "Firebase Analytics" to listOf("Y29tL2dvb2dsZS9maXJlYmFzZS9hbmFseXRpY3M=", "Y29tLmdvb2dsZS5maXJlYmFzZS5hbmFseXRpY3M="),
            "Meta / Facebook Ads" to listOf("Y29tL2ZhY2Vib29rL2Fkcw==", "Y29tL2ZhY2Vib29rL2FwcGV2ZW50cw=="),
            "AppsFlyer" to listOf("Y29tL2FwcHNmbHllcg=="),
            "Adjust" to listOf("Y29tL2FkanVzdC9zZGs="),
            "Unity Ads" to listOf("Y29tL3VuaXR5M2QvYWRz", "Y29tL3VuaXR5M2Qvc2VydmljZXM="),
            "AppLovin" to listOf("Y29tL2FwcGxvdmlu"),
            "IronSource" to listOf("Y29tL2lyb25zb3VyY2U="),
            "ByteDance / Pangle" to listOf("Y29tL2J5dGVkYW5jZS9zZGsvb3BlbmFkc2Rr", "Y29tL2J5dGVkYW5jZS9zZGs="),
            "InMobi" to listOf("Y29tL2lubW9iaQ=="),
            "Vungle" to listOf("Y29tL3Z1bmdsZQ=="),
            "Branch Metrics" to listOf("aW8vYnJhbmNo"),
            "Flurry" to listOf("Y29tL2ZsdXJyeQ=="),
            "Chartboost" to listOf("Y29tL2NoYXJ0Ym9vc3Q=")
        ).mapValues { (_, b64List) ->
            b64List.map { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }
        }
    }

    private val SENSITIVE_PERMISSION_MAP = mapOf(
        "android.permission.CAMERA" to "📷 Camera",
        "android.permission.RECORD_AUDIO" to "🎙️ Microphone",
        "android.permission.ACCESS_FINE_LOCATION" to "📍 Precise Location",
        "android.permission.ACCESS_COARSE_LOCATION" to "📍 Coarse Location",
        "android.permission.ACCESS_BACKGROUND_LOCATION" to "📍 Background Location",
        "android.permission.READ_CONTACTS" to "👥 Contacts (Read)",
        "android.permission.WRITE_CONTACTS" to "👥 Contacts (Write)",
        "android.permission.READ_CALL_LOG" to "📞 Call Log (Read)",
        "android.permission.WRITE_CALL_LOG" to "📞 Call Log (Write)",
        "android.permission.READ_SMS" to "💬 SMS (Read)",
        "android.permission.SEND_SMS" to "💬 SMS (Send)",
        "android.permission.RECEIVE_SMS" to "💬 SMS (Receive)",
        "android.permission.READ_EXTERNAL_STORAGE" to "📁 Storage (Read)",
        "android.permission.WRITE_EXTERNAL_STORAGE" to "📁 Storage (Write)",
        "android.permission.MANAGE_EXTERNAL_STORAGE" to "📁 Full Storage Access",
        "android.permission.READ_MEDIA_IMAGES" to "🖼️ Photos & Images",
        "android.permission.READ_MEDIA_VIDEO" to "🎬 Videos",
        "android.permission.READ_MEDIA_AUDIO" to "🎵 Audio Files",
        "android.permission.SYSTEM_ALERT_WINDOW" to "🪟 Draw Over Apps",
        "android.permission.PACKAGE_USAGE_STATS" to "📊 Usage Statistics",
        "android.permission.BIND_ACCESSIBILITY_SERVICE" to "♿ Accessibility Service",
        "android.permission.QUERY_ALL_PACKAGES" to "🔍 Query All Apps"
    )

    data class StoppageDiagnostic(
        val title: String,
        val explanation: String,
        val isSignatureConflict: Boolean = false,
        val isDowngradeConflict: Boolean = false
    )

    fun diagnoseStoppage(rawOutput: String, incomingCode: Long, installedCode: Long): StoppageDiagnostic {
        val out = rawOutput.lowercase()
        return when {
            out.contains("install_failed_update_incompatible") -> {
                StoppageDiagnostic(
                    title = "Signature Mismatch Conflict",
                    explanation = "The APK signature does not match the app currently installed on your device (e.g. Play Store vs Modded/Debug build). Android's package manager blocks overwriting differing certificates.",
                    isSignatureConflict = true
                )
            }
            out.contains("install_failed_shared_user_incompatible") -> {
                StoppageDiagnostic(
                    title = "Shared User ID Incompatibility",
                    explanation = "This app declares a sharedUserId that conflicts with another application already installed using a different signing certificate.",
                    isSignatureConflict = true
                )
            }
            out.contains("install_failed_version_downgrade") || (incomingCode > 0 && installedCode > 0 && incomingCode < installedCode) -> {
                StoppageDiagnostic(
                    title = "Version Downgrade Blocked",
                    explanation = "The incoming package (Build $incomingCode) is an older version than currently installed (Build $installedCode). Android strictly blocks downgrades to prevent internal database corruption.",
                    isDowngradeConflict = true
                )
            }
            out.contains("install_failed_deprecated_sdk_version") || out.contains("requires higher sdk") -> {
                StoppageDiagnostic(
                    title = "Target SDK Too Low (Blocked by Android 14+)",
                    explanation = "This application targets an obsolete Android version. Modern Android blocks installing apps with targetSdk below minimum platform security requirements.",
                    isDowngradeConflict = false
                )
            }
            out.contains("install_failed_no_matching_abis") -> {
                StoppageDiagnostic(
                    title = "CPU Architecture Incompatible",
                    explanation = "The native C/C++ libraries inside this APK were compiled for an incompatible CPU architecture (e.g. 32-bit ARM on a 64-bit device).",
                    isDowngradeConflict = false
                )
            }
            out.contains("install_failed_conflicting_provider") -> {
                StoppageDiagnostic(
                    title = "Content Provider Authority Conflict",
                    explanation = "Another app installed on your phone already owns the ContentProvider authority declared in this APK's manifest.",
                    isDowngradeConflict = false
                )
            }
            out.contains("install_parse_failed") -> {
                StoppageDiagnostic(
                    title = "Corrupt or Malformed APK",
                    explanation = "Android was unable to parse the AndroidManifest.xml. The APK file may be incomplete, corrupted during download, or encrypted.",
                    isDowngradeConflict = false
                )
            }
            else -> {
                StoppageDiagnostic(
                    title = "Installation Stoppage Detected",
                    explanation = if (rawOutput.isNotBlank()) rawOutput.trim() else "The package installer encountered an unexpected system rejection.",
                    isSignatureConflict = false,
                    isDowngradeConflict = false
                )
            }
        }
    }

    fun openPackageStream(context: Context, uri: Uri): java.io.InputStream? {
        return try {
            if (uri.scheme == "file" || uri.scheme == null) {
                val filePath = uri.path ?: uri.toString().removePrefix("file://")
                val f = File(filePath)
                if (f.exists() && f.canRead()) {
                    java.io.FileInputStream(f)
                } else {
                    context.contentResolver.openInputStream(uri)
                        ?: if (f.exists()) java.io.FileInputStream(f) else null
                }
            } else {
                context.contentResolver.openInputStream(uri)
                    ?: if (uri.path != null) java.io.FileInputStream(File(uri.path!!)) else null
            }
        } catch (e: Exception) {
            Log.e("PackageInstaller", "Error in openPackageStream: ${e.message}")
            if (uri.path != null) {
                try { java.io.FileInputStream(File(uri.path!!)) } catch (ex: Exception) { null }
            } else null
        }
    }

    /**
     * Inspects an APK or bundle before installation.
     * Extracts package identity, sharedUserId, split modules, SDK levels, dangerous permissions, and scans for tracking SDKs.
     */
    fun inspectApk(context: Context, uri: Uri, fileName: String): ApkInspection? {
        val lowerName = fileName.lowercase()
        val tempInspect = File(context.cacheDir, "temp_inspect.apk")
        val detectedSplits = mutableListOf<String>()

        try {
            if (lowerName.endsWith(".apk")) {
                openPackageStream(context, uri)?.use { input ->
                    FileOutputStream(tempInspect).use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                // Zip bundle (.apks, .xapk, .apkm, .aab, etc.): extract base APK and detect all split modules
                openPackageStream(context, uri)?.use { stream ->
                    ZipInputStream(stream).use { zis ->
                        var entry = zis.nextEntry
                        var foundBase = false
                        while (entry != null) {
                            val name = entry.name.lowercase()
                            if (!entry.isDirectory && name.endsWith(".apk")) {
                                val cleanName = File(entry.name).nameWithoutExtension
                                detectedSplits.add(cleanName)

                                if (name.endsWith("base.apk") || (!foundBase && name.endsWith(".apk"))) {
                                    FileOutputStream(tempInspect).use { fos ->
                                        zis.copyTo(fos)
                                    }
                                    foundBase = true
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
            }

            Log.d("PackageInstaller", "inspectApk: file=${fileName}, tempLen=${tempInspect.length()}")
            if (!tempInspect.exists() || tempInspect.length() == 0L) {
                Log.e("PackageInstaller", "inspectApk: tempInspect is empty or missing")
                return null
            }

            val pm = context.packageManager
            var pkgInfo = try {
                pm.getPackageArchiveInfo(tempInspect.absolutePath, PackageManager.GET_PERMISSIONS)
            } catch (e: Exception) { null }
            if (pkgInfo == null) {
                pkgInfo = try {
                    pm.getPackageArchiveInfo(tempInspect.absolutePath, 0)
                } catch (e: Exception) { null }
            }
            if (pkgInfo == null) {
                Log.e("PackageInstaller", "inspectApk: getPackageArchiveInfo returned null for ${tempInspect.absolutePath}")
                return null
            }
            pkgInfo.applicationInfo?.sourceDir = tempInspect.absolutePath
            pkgInfo.applicationInfo?.publicSourceDir = tempInspect.absolutePath

            val appName = try {
                pkgInfo.applicationInfo?.loadLabel(pm)?.toString() ?: pkgInfo.packageName
            } catch (e: Exception) {
                pkgInfo.packageName
            }
            val icon = try {
                pkgInfo.applicationInfo?.loadIcon(pm)
            } catch (e: Exception) {
                null
            }

            val packageName = pkgInfo.packageName
            val sharedUserId = pkgInfo.sharedUserId

            // Also check splitNames inside pkgInfo
            val manifestSplits = pkgInfo.splitNames?.filterNotNull() ?: emptyList()
            for (s in manifestSplits) {
                if (!detectedSplits.contains(s)) detectedSplits.add(s)
            }

            val incomingVersionName = pkgInfo.versionName ?: "1.0"
            val incomingVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }

            val minSdk = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                pkgInfo.applicationInfo?.minSdkVersion ?: 21
            } else 21
            val targetSdk = pkgInfo.applicationInfo?.targetSdkVersion ?: 33

            val minSdkLabel = "API $minSdk\n(${getAndroidCodename(minSdk)})"
            val targetSdkLabel = "API $targetSdk\n(${getAndroidCodename(targetSdk)})"

            val sizeBytes = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: tempInspect.length()
            } catch (e: Exception) {
                tempInspect.length()
            }
            val sizeFormatted = String.format(java.util.Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0))

            // Check if installed on device
            val installedPkgInfo = try {
                pm.getPackageInfo(packageName, 0)
            } catch (e: Exception) {
                null
            }

            val isInstalled = installedPkgInfo != null
            val installedVersionName = installedPkgInfo?.versionName
            val installedVersionCode = if (installedPkgInfo != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    installedPkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    installedPkgInfo.versionCode.toLong()
                }
            } else null

            val isDowngrade = isInstalled && (installedVersionCode != null && incomingVersionCode < installedVersionCode)
            val isSameVersion = isInstalled && (installedVersionCode != null && incomingVersionCode == installedVersionCode)

            // Permissions
            val requestedPerms = pkgInfo.requestedPermissions ?: emptyArray()
            val sensitiveList = mutableListOf<String>()
            for (perm in requestedPerms) {
                val label = SENSITIVE_PERMISSION_MAP[perm]
                if (label != null && !sensitiveList.contains(label)) {
                    sensitiveList.add(label)
                }
            }

            // Max SDK extraction from AndroidManifest.xml
            val maxSdk = extractMaxSdkVersion(tempInspect.absolutePath)
            val maxSdkLabel = if (maxSdk != null && maxSdk > 0) {
                "API $maxSdk\n(${getAndroidCodename(maxSdk)})"
            } else {
                "No Limit\n(All Versions)"
            }
            val packageTypeLabel = if (detectedSplits.isNotEmpty()) {
                "Split Bundle (${detectedSplits.size} Splits)"
            } else {
                "Single APK"
            }

            // Trackers detection from classes*.dex (PhoneControl itself has zero ads/trackers)
            val detectedTrackers = if (packageName == context.packageName) {
                emptyList()
            } else {
                scanApkForTrackers(tempInspect)
            }

            return ApkInspection(
                appName = appName,
                packageName = packageName,
                sharedUserId = sharedUserId,
                splitNames = detectedSplits,
                incomingVersionName = incomingVersionName,
                incomingVersionCode = incomingVersionCode,
                installedVersionName = installedVersionName,
                installedVersionCode = installedVersionCode,
                minSdk = minSdk,
                minSdkLabel = minSdkLabel,
                targetSdk = targetSdk,
                targetSdkLabel = targetSdkLabel,
                maxSdk = maxSdk,
                maxSdkLabel = maxSdkLabel,
                packageTypeLabel = packageTypeLabel,
                fileSizeFormatted = sizeFormatted,
                icon = icon,
                sensitivePermissions = sensitiveList,
                trackers = detectedTrackers,
                isInstalled = isInstalled,
                isDowngrade = isDowngrade,
                isSameVersion = isSameVersion
            )
        } catch (e: Exception) {
            Log.e("PackageInstaller", "Error inspecting APK", e)
            return null
        } finally {
            tempInspect.delete()
        }
    }

    private fun extractMaxSdkVersion(apkPath: String): Int? {
        return try {
            val assetManagerClass = android.content.res.AssetManager::class.java
            val assetManager = assetManagerClass.getDeclaredConstructor().newInstance()
            val addAssetPathMethod = assetManagerClass.getMethod("addAssetPath", String::class.java)
            val cookie = addAssetPathMethod.invoke(assetManager, apkPath) as? Int ?: 0
            if (cookie == 0) return null

            val parser = assetManager.openXmlResourceParser(cookie, "AndroidManifest.xml")
            var eventType = parser.eventType
            var maxSdk: Int? = null
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "uses-sdk") {
                    for (i in 0 until parser.attributeCount) {
                        val name = parser.getAttributeName(i)
                        if (name == "maxSdkVersion") {
                            val value = parser.getAttributeIntValue(i, -1)
                            if (value > 0) {
                                maxSdk = value
                            } else {
                                val strVal = parser.getAttributeValue(i)
                                maxSdk = strVal?.toIntOrNull()
                            }
                            break
                        }
                    }
                    break
                }
                eventType = parser.next()
            }
            parser.close()
            if (maxSdk != null && maxSdk > 0) maxSdk else null
        } catch (e: Exception) {
            Log.w("PackageInstaller", "extractMaxSdkVersion note: ${e.message}")
            null
        }
    }

    private fun scanApkForTrackers(apkFile: File): List<String> {
        val foundTrackers = mutableSetOf<String>()
        try {
            val trackerBytePatterns = TRACKER_PATTERNS.mapValues { (_, patterns) ->
                patterns.map { it.toByteArray(Charsets.UTF_8) }
            }

            java.util.zip.ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("classes") && entry.name.endsWith(".dex")) {
                        zip.getInputStream(entry).use { stream ->
                            val buffer = ByteArray(65536)
                            var overlap = ByteArray(0)
                            var bytesRead = stream.read(buffer)
                            var totalRead = 0
                            while (bytesRead != -1 && totalRead < 8 * 1024 * 1024) {
                                totalRead += bytesRead
                                val combined = if (overlap.isNotEmpty()) {
                                    val merged = ByteArray(overlap.size + bytesRead)
                                    System.arraycopy(overlap, 0, merged, 0, overlap.size)
                                    System.arraycopy(buffer, 0, merged, overlap.size, bytesRead)
                                    merged
                                } else {
                                    buffer.copyOf(bytesRead)
                                }

                                for ((trackerName, patternList) in trackerBytePatterns) {
                                    if (foundTrackers.contains(trackerName)) continue
                                    for (pat in patternList) {
                                        if (containsSubarray(combined, pat)) {
                                            foundTrackers.add(trackerName)
                                            break
                                        }
                                    }
                                }

                                val maxPatLen = 64
                                val keep = Math.min(combined.size, maxPatLen)
                                overlap = combined.copyOfRange(combined.size - keep, combined.size)
                                bytesRead = stream.read(buffer)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("PackageInstaller", "Tracker scan error", e)
        }
        return foundTrackers.toList()
    }

    private fun containsSubarray(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        val max = haystack.size - needle.size
        for (i in 0..max) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    /**
     * Backs up app data (/data/data/<pkg>) into /sdcard/PHONE_CONTROL/installer_backups/<pkg>/ before clean re-install.
     * Returns the created archive path if successful, or null.
     */
    fun backupAppData(packageName: String): String? {
        try {
            val backupDir = "/sdcard/PHONE_CONTROL/installer_backups/$packageName"
            ShellUtils.runAsRoot("mkdir -p '$backupDir'", 10000)
            ShellUtils.runAsRoot("am force-stop $packageName && sync", 10000)

            val dataPath = "/data/data/$packageName"
            val checkData = ShellUtils.runAsRoot("ls -d '$dataPath' 2>/dev/null", 10000)
            if (checkData.exitCode != 0) {
                return null
            }

            val dataOutput = "$backupDir/data_${System.currentTimeMillis()}.tar.gz"
            val tarCmd = "tar -czf '$dataOutput' -C '$dataPath' . --exclude='cache' --exclude='code_cache'"
            val res = ShellUtils.runAsRoot(tarCmd, 60000)
            if (res.exitCode == 0) {
                ShellUtils.runAsRoot("cp '$dataOutput' '$backupDir/data_latest.tar.gz' && chmod 777 '$backupDir'/*", 15000)
                return dataOutput
            }
        } catch (e: Exception) {
            Log.e("PackageInstaller", "Backup app data failed for $packageName", e)
        }
        return null
    }

    /**
     * Restores backed up app data from archive back into /data/data/<pkg> and fixes UID and SELinux contexts.
     */
    fun restoreAppData(context: Context, packageName: String, backupPath: String): Boolean {
        try {
            val dataPath = "/data/data/$packageName"
            ShellUtils.runAsRoot("am force-stop $packageName && sync", 10000)
            val checkData = ShellUtils.runAsRoot("ls -d '$dataPath' 2>/dev/null", 10000)
            if (checkData.exitCode != 0) {
                ShellUtils.runAsRoot("mkdir -p '$dataPath'", 10000)
            }

            // Extract backup
            val extractCmd = "tar -xzf '$backupPath' -C '$dataPath'"
            val res = ShellUtils.runAsRoot(extractCmd, 60000)
            if (res.exitCode == 0) {
                // Fix UID & SELinux Context
                try {
                    val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
                    ShellUtils.runAsRoot("chown -R $uid:$uid '$dataPath'", 15000)
                    ShellUtils.runAsRoot("restorecon -R '$dataPath'", 15000)
                } catch (e: Exception) {
                    Log.e("PackageInstaller", "UID/SELinux fix failed", e)
                }
                return true
            }
        } catch (e: Exception) {
            Log.e("PackageInstaller", "Restore app data failed for $packageName", e)
        }
        return false
    }

    /**
     * Universal Root Force Package & Bundle Installer.
     * Supports: .apk, .apks, .apkm, .xapk, .aab, and split .zip bundles.
     * When forceReinstall = true, automatically backs up previous app data before pm uninstall.
     */
    fun installPackage(
        context: Context,
        uri: Uri,
        fileName: String,
        forceReinstall: Boolean = false,
        autoBackup: Boolean = true,
        onProgress: (String) -> Unit
    ): InstallResult {
        val stagingDir = File("/data/local/tmp/pc_install_staging")
        ShellUtils.runAsRoot("rm -rf ${stagingDir.absolutePath} && mkdir -p ${stagingDir.absolutePath} && chmod 777 ${stagingDir.absolutePath}", 10000)

        val lowerName = fileName.lowercase()
        val tempInput = File(context.cacheDir, "temp_installer_input")
        var autoBackupPath: String? = null

        try {
            onProgress("📥 Reading package stream: $fileName...")
            openPackageStream(context, uri)?.use { input ->
                FileOutputStream(tempInput).use { output ->
                    input.copyTo(output)
                }
            } ?: return InstallResult(false, "Could not open file stream", "")

            val stagedInput = File(stagingDir, "package_payload")
            onProgress("📦 Staging package in root partition...")
            ShellUtils.runAsRoot("cp '${tempInput.absolutePath}' '${stagedInput.absolutePath}' && chmod 777 '${stagedInput.absolutePath}'", 30000)

            // Extract package name and version info for conflict auto-recovery
            val parsedPkgInfo = try {
                context.packageManager.getPackageArchiveInfo(tempInput.absolutePath, 0)
            } catch (e: Exception) { null }
            val detectedPkg = parsedPkgInfo?.packageName

            val installedPkgInfo = if (!detectedPkg.isNullOrBlank()) {
                try {
                    context.packageManager.getPackageInfo(detectedPkg, 0)
                } catch (e: Exception) { null }
            } else null

            val incomingCode = if (parsedPkgInfo != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    parsedPkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    parsedPkgInfo.versionCode.toLong()
                }
            } else 0L

            val currentCode = if (installedPkgInfo != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    installedPkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    installedPkgInfo.versionCode.toLong()
                }
            } else 0L

            val isVersionLower = incomingCode > 0 && currentCode > 0 && incomingCode < currentCode

            tempInput.delete()

            // 1. Single APK Direct Install
            if (lowerName.endsWith(".apk")) {
                onProgress("⚡ Executing root force install (APK)...")
                val cmd = "pm install -r -d --bypass-low-target-sdk-block '${stagedInput.absolutePath}'"
                var result = ShellUtils.runAsRoot(cmd, 60000)

                val isSigConflict = result.output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                    result.output.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE", ignoreCase = true)
                val isDowngrade = result.output.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true) || (isSigConflict && isVersionLower)

                if (isSigConflict || isDowngrade) {
                    if (forceReinstall && !detectedPkg.isNullOrBlank()) {
                        val reason = if (isSigConflict && isDowngrade) "signature conflict & downgrade" else if (isDowngrade) "downgrade" else "signature conflict"
                        
                        if (autoBackup) {
                            onProgress("🛡️ Auto-backing up app data before clean reinstall...")
                            autoBackupPath = backupAppData(detectedPkg)
                            if (autoBackupPath != null) {
                                onProgress("✅ App data safely backed up: $autoBackupPath")
                            } else {
                                onProgress("ℹ️ No previous app data folder found to back up.")
                            }
                        } else {
                            onProgress("⚡ Skipping backup (Clean install requested)...")
                        }

                        onProgress("⚠️ Auto-uninstalling previous build ($detectedPkg) for $reason...")
                        ShellUtils.runAsRoot("pm uninstall $detectedPkg", 30000)
                        onProgress("🔄 Cleanly re-installing requested package...")
                        result = ShellUtils.runAsRoot(cmd, 60000)
                    } else {
                        val diag = diagnoseStoppage(result.output, incomingCode, currentCode)
                        return InstallResult(
                            success = false,
                            message = "${diag.title}: ${diag.explanation}",
                            rawOutput = result.output,
                            isSignatureConflict = isSigConflict,
                            isDowngradeConflict = isDowngrade,
                            conflictPackage = detectedPkg,
                            installedPackage = detectedPkg,
                            failureTitle = diag.title,
                            failureExplanation = diag.explanation
                        )
                    }
                }

                return handleInstallOutput(result, stagedInput.absolutePath, isSplit = false, installedPackage = detectedPkg, backupPath = autoBackupPath, incomingCode = incomingCode, installedCode = currentCode)
            }

            // 2. Split APKs / Bundles (.apks, .apkm, .xapk, .aab, .zip)
            onProgress("📦 Extracting bundle components (.apks / .xapk / .aab)...")
            val extractDir = File(stagingDir, "extracted")
            ShellUtils.runAsRoot("mkdir -p ${extractDir.absolutePath} && chmod 777 ${extractDir.absolutePath}", 10000)

            openPackageStream(context, uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        if (!entry.isDirectory && (entryName.endsWith(".apk") || entryName.endsWith(".obb"))) {
                            val outFile = File(extractDir, File(entryName).name)
                            FileOutputStream(outFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            ShellUtils.runAsRoot("chmod -R 777 ${extractDir.absolutePath}", 15000)

            val apkFilesOutput = ShellUtils.runAsRoot("find '${extractDir.absolutePath}' -type f -name '*.apk'", 15000).output
            val apkFiles = apkFilesOutput.split("\n").map { it.trim() }.filter { it.isNotBlank() }

            if (apkFiles.isEmpty()) {
                return InstallResult(false, "No APK files found inside the package bundle.", apkFilesOutput)
            }

            // If only 1 APK inside the bundle
            if (apkFiles.size == 1) {
                onProgress("⚡ Installing single APK from bundle...")
                val cmd = "pm install -r -d --bypass-low-target-sdk-block '${apkFiles[0]}'"
                var result = ShellUtils.runAsRoot(cmd, 60000)

                val singlePkg = try {
                    context.packageManager.getPackageArchiveInfo(apkFiles[0], 0)?.packageName
                } catch (e: Exception) { detectedPkg } ?: "existing package"

                val isSigConflict = result.output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                    result.output.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE", ignoreCase = true)
                val isDowngrade = result.output.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true) || (isSigConflict && isVersionLower)

                if (isSigConflict || isDowngrade) {
                    if (forceReinstall && singlePkg != "existing package") {
                        val reason = if (isSigConflict && isDowngrade) "signature conflict & downgrade" else if (isDowngrade) "downgrade" else "signature conflict"
                        
                        if (autoBackup) {
                            onProgress("🛡️ Auto-backing up app data before clean reinstall...")
                            autoBackupPath = backupAppData(singlePkg)
                            if (autoBackupPath != null) {
                                onProgress("✅ App data safely backed up: $autoBackupPath")
                            }
                        } else {
                            onProgress("⚡ Skipping backup (Clean install requested)...")
                        }

                        onProgress("⚠️ Auto-uninstalling previous build ($singlePkg) for $reason...")
                        ShellUtils.runAsRoot("pm uninstall $singlePkg", 30000)
                        onProgress("🔄 Cleanly re-installing package...")
                        result = ShellUtils.runAsRoot(cmd, 60000)
                    } else {
                        val diag = diagnoseStoppage(result.output, incomingCode, currentCode)
                        return InstallResult(
                            success = false,
                            message = "${diag.title}: ${diag.explanation}",
                            rawOutput = result.output,
                            isSignatureConflict = isSigConflict,
                            isDowngradeConflict = isDowngrade,
                            conflictPackage = if (singlePkg != "existing package") singlePkg else detectedPkg,
                            installedPackage = if (singlePkg != "existing package") singlePkg else detectedPkg,
                            failureTitle = diag.title,
                            failureExplanation = diag.explanation
                        )
                    }
                }

                val finalSinglePkg = if (singlePkg != "existing package") singlePkg else detectedPkg
                if (result.output.contains("Success", ignoreCase = true)) {
                    setupObbFiles(extractDir, finalSinglePkg, onProgress)
                }
                return handleInstallOutput(result, apkFiles[0], isSplit = false, installedPackage = finalSinglePkg, backupPath = autoBackupPath, incomingCode = incomingCode, installedCode = currentCode)
            }

            // Multiple Split APKs -> Use pm install-create session API
            onProgress("🔄 Creating Android package install session for ${apkFiles.size} splits...")
            val createSessionResult = ShellUtils.runAsRoot("pm install-create -r -d --bypass-low-target-sdk-block --user 0", 20000)
            val sessionOutput = createSessionResult.output.trim()

            val sessionRegex = "\\[(\\d+)\\]".toRegex()
            val match = sessionRegex.find(sessionOutput)
            val sessionId = match?.groupValues?.get(1)

            if (sessionId == null) {
                return InstallResult(false, "Failed to create install session: $sessionOutput", sessionOutput)
            }

            onProgress("📤 Streaming ${apkFiles.size} split APKs into session [$sessionId]...")
            for ((index, apkPath) in apkFiles.withIndex()) {
                val splitFile = File(apkPath)
                val splitName = splitFile.name
                val sizeResult = ShellUtils.runAsRoot("stat -c%s '$apkPath' 2>/dev/null || wc -c < '$apkPath'", 10000).output.trim()
                val size = sizeResult.toLongOrNull() ?: splitFile.length()

                onProgress("Writing split ${index + 1}/${apkFiles.size}: $splitName (${size / 1024} KB)...")
                val writeCmd = "pm install-write -S $size $sessionId '$splitName' '$apkPath'"
                val writeResult = ShellUtils.runAsRoot(writeCmd, 60000)

                if (writeResult.exitCode != 0 || writeResult.output.contains("Failure", ignoreCase = true)) {
                    ShellUtils.runAsRoot("pm install-abandon $sessionId 2>/dev/null", 10000)
                    return InstallResult(false, "Failed writing split $splitName: ${writeResult.output}", writeResult.output)
                }
            }

            onProgress("✅ Committing split session [$sessionId]...")
            var commitResult = ShellUtils.runAsRoot("pm install-commit $sessionId", 60000)

            val splitPkg = try {
                context.packageManager.getPackageArchiveInfo(apkFiles[0], 0)?.packageName
            } catch (e: Exception) { detectedPkg }

            val isSplitSigConflict = commitResult.output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                commitResult.output.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE", ignoreCase = true)
            val isSplitDowngrade = commitResult.output.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true) || (isSplitSigConflict && isVersionLower)

            if (isSplitSigConflict || isSplitDowngrade) {
                if (forceReinstall && !splitPkg.isNullOrBlank()) {
                    val reason = if (isSplitSigConflict && isSplitDowngrade) "signature conflict & downgrade" else if (isSplitDowngrade) "downgrade" else "signature conflict"
                    
                    if (autoBackup) {
                        onProgress("🛡️ Auto-backing up app data before clean reinstall...")
                        autoBackupPath = backupAppData(splitPkg)
                        if (autoBackupPath != null) {
                            onProgress("✅ App data safely backed up: $autoBackupPath")
                        }
                    } else {
                        onProgress("⚡ Skipping backup (Clean install requested)...")
                    }

                    onProgress("⚠️ Auto-uninstalling previous build ($splitPkg) for $reason...")
                    ShellUtils.runAsRoot("pm uninstall $splitPkg", 30000)
                    onProgress("🔄 Re-creating session for clean installation...")
                    val retrySession = ShellUtils.runAsRoot("pm install-create -r -d --bypass-low-target-sdk-block --user 0", 20000).output.trim()
                    val retrySessionId = sessionRegex.find(retrySession)?.groupValues?.get(1)
                    if (retrySessionId != null) {
                        for (apkPath in apkFiles) {
                            val sFile = File(apkPath)
                            val sName = sFile.name
                            val sSize = sFile.length()
                            ShellUtils.runAsRoot("pm install-write -S $sSize $retrySessionId '$sName' '$apkPath'", 60000)
                        }
                        commitResult = ShellUtils.runAsRoot("pm install-commit $retrySessionId", 60000)
                    }
                } else {
                    val diag = diagnoseStoppage(commitResult.output, incomingCode, currentCode)
                    return InstallResult(
                        success = false,
                        message = "${diag.title}: ${diag.explanation}",
                        rawOutput = commitResult.output,
                        isSignatureConflict = isSplitSigConflict,
                        isDowngradeConflict = isSplitDowngrade,
                        conflictPackage = splitPkg,
                        installedPackage = splitPkg,
                        failureTitle = diag.title,
                        failureExplanation = diag.explanation
                    )
                }
            }

            val finalSplitPkg = splitPkg ?: detectedPkg
            if (commitResult.output.contains("Success", ignoreCase = true)) {
                setupObbFiles(extractDir, finalSplitPkg, onProgress)
            }

            return handleInstallOutput(commitResult, "", isSplit = true, installedPackage = finalSplitPkg, backupPath = autoBackupPath, incomingCode = incomingCode, installedCode = currentCode)
        } catch (e: Exception) {
            Log.e("PackageInstaller", "Install failed", e)
            return InstallResult(false, "Installation exception: ${e.message}", e.stackTraceToString())
        } finally {
            ShellUtils.runAsRoot("rm -rf ${stagingDir.absolutePath}", 10000)
        }
    }

    /**
     * Automatically deploys extracted game OBB data files to /sdcard/Android/obb/<pkg>/ with proper permissions.
     */
    private fun setupObbFiles(extractDir: File, targetPkg: String?, onProgress: (String) -> Unit) {
        try {
            val obbFilesOutput = ShellUtils.runAsRoot("find '${extractDir.absolutePath}' -type f -name '*.obb'", 15000).output
            val obbFiles = obbFilesOutput.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            if (obbFiles.isEmpty()) return

            val pkg = if (!targetPkg.isNullOrBlank() && targetPkg != "existing package") {
                targetPkg
            } else {
                val firstName = File(obbFiles[0]).name
                val regex = Regex("(?:main|patch)\\.[0-9]+\\.([a-zA-Z0-9_.]+)\\.obb")
                regex.find(firstName)?.groupValues?.get(1) ?: ""
            }

            if (pkg.isNotBlank()) {
                onProgress("🎮 Setting up ${obbFiles.size} game OBB file(s) for $pkg...")
                val targetObbDir = "/sdcard/Android/obb/$pkg"
                ShellUtils.runAsRoot("mkdir -p '$targetObbDir'", 10000)
                for (obb in obbFiles) {
                    val obbName = File(obb).name
                    onProgress("📦 Copying $obbName to /sdcard/Android/obb/$pkg/...")
                    ShellUtils.runAsRoot("cp '$obb' '$targetObbDir/' && chmod 666 '$targetObbDir/$obbName'", 60000)
                }
                ShellUtils.runAsRoot("chown -R media_rw:media_rw '$targetObbDir' 2>/dev/null; chmod 775 '$targetObbDir'", 15000)
                onProgress("✅ Game OBB setup complete!")
            }
        } catch (e: Exception) {
            Log.w("PackageInstaller", "Error in setupObbFiles: ${e.message}")
        }
    }

    private fun handleInstallOutput(
        result: ShellUtils.ShellResult,
        path: String,
        isSplit: Boolean,
        installedPackage: String? = null,
        backupPath: String? = null,
        incomingCode: Long = 0L,
        installedCode: Long = 0L
    ): InstallResult {
        val output = result.output.trim()
        val isSuccess = result.exitCode == 0 && (output.contains("Success", ignoreCase = true) || output.isBlank())

        if (isSuccess) {
            return InstallResult(
                success = true,
                message = "Application installed successfully!",
                rawOutput = output,
                installedPackage = installedPackage,
                backupPath = backupPath
            )
        }

        val diag = diagnoseStoppage(output, incomingCode, installedCode)

        return InstallResult(
            success = false,
            message = "${diag.title}: ${diag.explanation}",
            rawOutput = output,
            isSignatureConflict = diag.isSignatureConflict,
            isDowngradeConflict = diag.isDowngradeConflict,
            conflictPackage = installedPackage,
            installedPackage = installedPackage,
            backupPath = backupPath,
            failureTitle = diag.title,
            failureExplanation = diag.explanation
        )
    }
}
