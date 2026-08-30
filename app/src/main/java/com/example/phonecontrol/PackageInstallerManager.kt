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
        ShellUtils.runAsRoot("rm -rf ${stagingDir.absolutePath} && mkdir -p ${stagingDir.absolutePath} && chmod 777 ${stagingDir.absolutePath}")

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
            ShellUtils.runAsRoot("cp '${tempInput.absolutePath}' '${stagedInput.absolutePath}' && chmod 777 '${stagedInput.absolutePath}'")
            tempInput.delete()

            // 1. Single APK Direct Install
            if (lowerName.endsWith(".apk")) {
                onProgress("⚡ Executing root force install (APK)...")
                val cmd = "pm install -r -d --bypass-low-target-sdk-block '${stagedInput.absolutePath}'"
                val result = ShellUtils.runAsRoot(cmd)

                return handleInstallOutput(result, stagedInput.absolutePath, isSplit = false)
            }

            // 2. Split APKs / Bundles (.apks, .apkm, .xapk, .aab, .zip)
            onProgress("📦 Extracting bundle components (.apks / .xapk / .aab)...")
            val extractDir = File(stagingDir, "extracted")
            ShellUtils.runAsRoot("mkdir -p ${extractDir.absolutePath} && chmod 777 ${extractDir.absolutePath}")

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
            ShellUtils.runAsRoot("chmod -R 777 ${extractDir.absolutePath}")

            val apkFilesOutput = ShellUtils.runAsRoot("find '${extractDir.absolutePath}' -type f -name '*.apk'").output
            val apkFiles = apkFilesOutput.split("\n").map { it.trim() }.filter { it.isNotBlank() }

            if (apkFiles.isEmpty()) {
                return InstallResult(false, "No APK files found inside the package bundle.", apkFilesOutput)
            }

            // If only 1 APK inside the bundle
            if (apkFiles.size == 1) {
                onProgress("⚡ Installing single APK from bundle...")
                val result = ShellUtils.runAsRoot("pm install -r -d --bypass-low-target-sdk-block '${apkFiles[0]}'")
                return handleInstallOutput(result, apkFiles[0], isSplit = false)
            }

            // Multiple Split APKs -> Use pm install-create session API
            onProgress("🔄 Creating Android package install session for ${apkFiles.size} splits...")
            val createSessionResult = ShellUtils.runAsRoot("pm install-create -r -d --bypass-low-target-sdk-block --user 0")
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
                val sizeResult = ShellUtils.runAsRoot("stat -c%s '$apkPath' 2>/dev/null || wc -c < '$apkPath'").output.trim()
                val size = sizeResult.toLongOrNull() ?: splitFile.length()

                onProgress("Writing split ${index + 1}/${apkFiles.size}: $splitName (${size / 1024} KB)...")
                val writeCmd = "pm install-write -S $size $sessionId '$splitName' '$apkPath'"
                val writeResult = ShellUtils.runAsRoot(writeCmd)

                if (writeResult.exitCode != 0 || writeResult.output.contains("Failure", ignoreCase = true)) {
                    ShellUtils.runAsRoot("pm install-abandon $sessionId 2>/dev/null")
                    return InstallResult(false, "Failed writing split $splitName: ${writeResult.output}", writeResult.output)
                }
            }

            onProgress("✅ Committing split session [$sessionId]...")
            val commitResult = ShellUtils.runAsRoot("pm install-commit $sessionId")

            // Check for OBB files to move if present
            val obbFilesOutput = ShellUtils.runAsRoot("find '${extractDir.absolutePath}' -type f -name '*.obb'").output
            val obbFiles = obbFilesOutput.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            if (obbFiles.isNotEmpty()) {
                onProgress("🎮 Copying ${obbFiles.size} OBB game files...")
                for (obb in obbFiles) {
                    val obbName = File(obb).name
                    val pkgFromObb = obbName.substringAfter("main.").substringAfter("patch.").substringBefore(".obb").substringAfter(".")
                    val targetObbDir = "/sdcard/Android/obb/$pkgFromObb"
                    ShellUtils.runAsRoot("mkdir -p '$targetObbDir' && cp '$obb' '$targetObbDir/' && chmod 777 '$targetObbDir/$obbName'")
                }
            }

            return handleInstallOutput(commitResult, "", isSplit = true)
        } catch (e: Exception) {
            Log.e("PackageInstaller", "Install failed", e)
            return InstallResult(false, "Installation exception: ${e.message}", e.stackTraceToString())
        } finally {
            ShellUtils.runAsRoot("rm -rf ${stagingDir.absolutePath}")
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
                "Signature Conflict: A residual version of this app with a conflicting signature is already installed. Use 'Delete App' in App Inspector first, then reinstall.",
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
