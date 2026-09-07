package com.example.phonecontrol

import android.content.Context
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
        val conflictPackage: String? = null
    )

    /**
     * Universal Root Force Package & Bundle Installer.
     * Supports: .apk, .apks, .apkm, .xapk, .aab, and split .zip bundles.
     * When forceReinstall = true, uninstalls conflicting old signature build upon user confirmation.
     */
    fun installPackage(
        context: Context,
        uri: Uri,
        fileName: String,
        forceReinstall: Boolean = false,
        onProgress: (String) -> Unit
    ): InstallResult {
        val stagingDir = File("/data/local/tmp/pc_install_staging")
        ShellUtils.runAsRoot("rm -rf ${stagingDir.absolutePath} && mkdir -p ${stagingDir.absolutePath} && chmod 777 ${stagingDir.absolutePath}", 10000)

        val lowerName = fileName.lowercase()
        val tempInput = File(context.cacheDir, "temp_installer_input")

        try {
            onProgress("📥 Reading package stream: $fileName...")
            context.contentResolver.openInputStream(uri)?.use { input ->
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

            val isVersionLower = if (parsedPkgInfo != null && installedPkgInfo != null) {
                val incomingCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    parsedPkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    parsedPkgInfo.versionCode.toLong()
                }
                val currentCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    installedPkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    installedPkgInfo.versionCode.toLong()
                }
                incomingCode < currentCode
            } else false

            tempInput.delete()

            // 1. Single APK Direct Install
            if (lowerName.endsWith(".apk")) {
                onProgress("⚡ Executing root force install (APK)...")
                val cmd = "pm install -r -d --bypass-low-target-sdk-block '${stagedInput.absolutePath}'"
                var result = ShellUtils.runAsRoot(cmd, 60000)

                // Signature Conflict or Version Downgrade Check: Avoid silent data loss unless user confirmed forceReinstall
                val isSigConflict = result.output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                    result.output.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE", ignoreCase = true)
                val isDowngrade = result.output.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true) || (isSigConflict && isVersionLower)

                if (isSigConflict || isDowngrade) {
                    val pkgName = detectedPkg ?: "existing package"
                    if (forceReinstall && !detectedPkg.isNullOrBlank()) {
                        val reason = if (isSigConflict && isDowngrade) "signature conflict & downgrade" else if (isDowngrade) "downgrade" else "signature conflict"
                        onProgress("⚠️ Confirmed: Auto-uninstalling previous build ($detectedPkg) for $reason...")
                        ShellUtils.runAsRoot("pm uninstall $detectedPkg", 30000)
                        onProgress("🔄 Cleanly re-installing requested package...")
                        result = ShellUtils.runAsRoot(cmd, 60000)
                    } else {
                        return InstallResult(
                            success = false,
                            message = if (isSigConflict && isDowngrade) {
                                "Combined Conflict: $pkgName has a different signature AND is an older version than currently installed."
                            } else if (isDowngrade) {
                                "Version Downgrade Blocked: $pkgName is newer on your phone. Downgrading requires uninstalling the newer version."
                            } else {
                                "Signature Conflict: $pkgName has a different signature. Installing will erase previous app data."
                            },
                            rawOutput = result.output,
                            isSignatureConflict = isSigConflict,
                            isDowngradeConflict = isDowngrade,
                            conflictPackage = detectedPkg
                        )
                    }
                }

                return handleInstallOutput(result, stagedInput.absolutePath, isSplit = false)
            }

            // 2. Split APKs / Bundles (.apks, .apkm, .xapk, .aab, .zip)
            onProgress("📦 Extracting bundle components (.apks / .xapk / .aab)...")
            val extractDir = File(stagingDir, "extracted")
            ShellUtils.runAsRoot("mkdir -p ${extractDir.absolutePath} && chmod 777 ${extractDir.absolutePath}", 10000)

            var extractedCount = 0
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        if (!entry.isDirectory && (entryName.endsWith(".apk") || entryName.endsWith(".obb"))) {
                            val outFile = File(extractDir, File(entryName).name)
                            FileOutputStream(outFile).use { fos ->
                                zis.copyTo(fos)
                            }
                            extractedCount++
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            // Also set permissions
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

                // Signature Conflict or Version Downgrade Check: Avoid silent data loss
                val isSigConflict = result.output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                    result.output.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE", ignoreCase = true)
                val isDowngrade = result.output.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true) || (isSigConflict && isVersionLower)

                if (isSigConflict || isDowngrade) {
                    val singlePkg = try {
                        context.packageManager.getPackageArchiveInfo(apkFiles[0], 0)?.packageName
                    } catch (e: Exception) { detectedPkg } ?: "existing package"

                    if (forceReinstall && singlePkg != "existing package") {
                        val reason = if (isSigConflict && isDowngrade) "signature conflict & downgrade" else if (isDowngrade) "downgrade" else "signature conflict"
                        onProgress("⚠️ Confirmed: Auto-uninstalling previous build ($singlePkg) for $reason...")
                        ShellUtils.runAsRoot("pm uninstall $singlePkg", 30000)
                        onProgress("🔄 Cleanly re-installing package...")
                        result = ShellUtils.runAsRoot(cmd, 60000)
                    } else {
                        return InstallResult(
                            success = false,
                            message = if (isSigConflict && isDowngrade) {
                                "Combined Conflict: $singlePkg has a different signature AND is an older version than currently installed."
                            } else if (isDowngrade) {
                                "Version Downgrade Blocked: $singlePkg is newer on your phone. Downgrading requires uninstalling the newer version."
                            } else {
                                "Signature Conflict: $singlePkg has a different signature. Installing will erase previous app data."
                            },
                            rawOutput = result.output,
                            isSignatureConflict = isSigConflict,
                            isDowngradeConflict = isDowngrade,
                            conflictPackage = if (singlePkg != "existing package") singlePkg else detectedPkg
                        )
                    }
                }

                return handleInstallOutput(result, apkFiles[0], isSplit = false)
            }

            // Multiple Split APKs -> Use pm install-create session API
            onProgress("🔄 Creating Android package install session for ${apkFiles.size} splits...")
            val createSessionResult = ShellUtils.runAsRoot("pm install-create -r -d --bypass-low-target-sdk-block --user 0", 20000)
            val sessionOutput = createSessionResult.output.trim()

            // Session ID format: "Success: created install session [12345678]"
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

            // Handle split signature conflict or version downgrade
            val isSplitSigConflict = commitResult.output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                commitResult.output.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE", ignoreCase = true)
            val isSplitDowngrade = commitResult.output.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true) || (isSplitSigConflict && isVersionLower)

            if (isSplitSigConflict || isSplitDowngrade) {
                val splitPkg = try {
                    context.packageManager.getPackageArchiveInfo(apkFiles[0], 0)?.packageName
                } catch (e: Exception) { detectedPkg }

                if (forceReinstall && !splitPkg.isNullOrBlank()) {
                    val reason = if (isSplitSigConflict && isSplitDowngrade) "signature conflict & downgrade" else if (isSplitDowngrade) "downgrade" else "signature conflict"
                    onProgress("⚠️ Confirmed: Auto-uninstalling previous build ($splitPkg) for $reason...")
                    ShellUtils.runAsRoot("pm uninstall $splitPkg", 30000)
                    onProgress("🔄 Re-creating session for clean installation...")
                    // Re-run session create and write
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
                    val pkgName = splitPkg ?: "existing package"
                    return InstallResult(
                        success = false,
                        message = if (isSplitSigConflict && isSplitDowngrade) {
                            "Combined Conflict: $pkgName has a different signature AND is an older version than currently installed."
                        } else if (isSplitDowngrade) {
                            "Version Downgrade Blocked: $pkgName is newer on your phone. Downgrading requires uninstalling the newer version."
                        } else {
                            "Signature Conflict: $pkgName has a different signature. Installing will erase previous app data."
                        },
                        rawOutput = commitResult.output,
                        isSignatureConflict = isSplitSigConflict,
                        isDowngradeConflict = isSplitDowngrade,
                        conflictPackage = splitPkg
                    )
                }
            }

            // Check for OBB files to move if present
            val obbFilesOutput = ShellUtils.runAsRoot("find '${extractDir.absolutePath}' -type f -name '*.obb'", 15000).output
            val obbFiles = obbFilesOutput.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            if (obbFiles.isNotEmpty()) {
                onProgress("🎮 Copying ${obbFiles.size} OBB game files...")
                for (obb in obbFiles) {
                    val obbName = File(obb).name
                    val pkgFromObb = obbName.substringAfter("main.").substringAfter("patch.").substringBefore(".obb").substringAfter(".")
                    val targetObbDir = "/sdcard/Android/obb/$pkgFromObb"
                    ShellUtils.runAsRoot("mkdir -p '$targetObbDir' && cp '$obb' '$targetObbDir/' && chmod 777 '$targetObbDir/$obbName'", 30000)
                }
            }

            return handleInstallOutput(commitResult, "", isSplit = true)
        } catch (e: Exception) {
            Log.e("PackageInstaller", "Install failed", e)
            return InstallResult(false, "Installation exception: ${e.message}", e.stackTraceToString())
        } finally {
            ShellUtils.runAsRoot("rm -rf ${stagingDir.absolutePath}", 10000)
        }
    }

    private fun handleInstallOutput(result: ShellUtils.ShellResult, path: String, isSplit: Boolean): InstallResult {
        val output = result.output.trim()
        val isSuccess = result.exitCode == 0 && (output.contains("Success", ignoreCase = true) || output.isBlank())

        if (isSuccess) {
            return InstallResult(true, "Application installed successfully!", output)
        }

        // Handle signature / ghost conflict
        if (output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ||
            output.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE", ignoreCase = true)) {
            return InstallResult(
                false,
                "Signature Conflict: A residual version with a conflicting signature was present.",
                output,
                isSignatureConflict = true,
                conflictPackage = null
            )
        }

        if (output.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true)) {
            return InstallResult(
                false,
                "Downgrade Blocked: Target version is older than existing installation.",
                output,
                isDowngradeConflict = true,
                conflictPackage = null
            )
        }

        return InstallResult(false, "Installation failed: $output", output)
    }
}
