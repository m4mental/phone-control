package com.example.phonecontrol

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object PackageInstallerManager {

    data class InstallResult(val success: Boolean, val message: String, val rawOutput: String)

    /**
     * Universal Root Force Package & Bundle Installer.
     * Supports: .apk, .apks, .apkm, .xapk, .aab, and split .zip bundles.
     */
    fun installPackage(
        context: Context,
        uri: Uri,
        fileName: String,
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

            // Extract package name for signature conflict auto-recovery
            val parsedPkgInfo = try {
                context.packageManager.getPackageArchiveInfo(tempInput.absolutePath, 0)
            } catch (e: Exception) { null }
            val detectedPkg = parsedPkgInfo?.packageName

            tempInput.delete()

            // 1. Single APK Direct Install
            if (lowerName.endsWith(".apk")) {
                onProgress("⚡ Executing root force install (APK)...")
                val cmd = "pm install -r -d --bypass-low-target-sdk-block '${stagedInput.absolutePath}'"
                var result = ShellUtils.runAsRoot(cmd, 60000)

                // Signature Conflict Auto-Recovery
                if (result.output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                    result.output.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE", ignoreCase = true)) {
                    if (!detectedPkg.isNullOrBlank()) {
                        onProgress("⚠️ Signature conflict detected! Auto-uninstalling previous build ($detectedPkg)...")
                        ShellUtils.runAsRoot("pm uninstall $detectedPkg", 30000)
                        onProgress("🔄 Re-installing package with new signature...")
                        result = ShellUtils.runAsRoot(cmd, 60000)
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

                // Signature Conflict Auto-Recovery
                if (result.output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                    result.output.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE", ignoreCase = true)) {
                    val singlePkg = try {
                        context.packageManager.getPackageArchiveInfo(apkFiles[0], 0)?.packageName
                    } catch (e: Exception) { detectedPkg }

                    if (!singlePkg.isNullOrBlank()) {
                        onProgress("⚠️ Signature conflict detected! Auto-uninstalling previous build ($singlePkg)...")
                        ShellUtils.runAsRoot("pm uninstall $singlePkg", 30000)
                        onProgress("🔄 Re-installing package with new signature...")
                        result = ShellUtils.runAsRoot(cmd, 60000)
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

            // Handle split signature conflict
            if (commitResult.output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                commitResult.output.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE", ignoreCase = true)) {
                val splitPkg = try {
                    context.packageManager.getPackageArchiveInfo(apkFiles[0], 0)?.packageName
                } catch (e: Exception) { detectedPkg }

                if (!splitPkg.isNullOrBlank()) {
                    onProgress("⚠️ Signature conflict in bundle! Auto-uninstalling previous ($splitPkg)...")
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
                "Signature Conflict: A residual version with a conflicting signature was present. Please tap Force Install again to complete fresh installation.",
                output
            )
        }

        if (output.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true)) {
            return InstallResult(
                false,
                "Downgrade Blocked: Target version is older than existing installation.",
                output
            )
        }

        return InstallResult(false, "Installation failed: $output", output)
    }
}
