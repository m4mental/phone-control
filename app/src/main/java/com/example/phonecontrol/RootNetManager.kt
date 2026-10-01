package com.example.phonecontrol

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Root-Powered Network Dispatcher.
 * Executes on-demand network operations (GitHub update check, APK downloads) strictly through
 * the Root Shell (UID 0) using either system curl or Android's native app_process runtime.
 * Allows the application to function with 0% Android Manifest INTERNET permissions for 100% offline privacy.
 */
object RootNetManager {

    private const val TAG = "RootNetManager"

    val ALLOWED_GITHUB_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "raw.githubusercontent.com",
        "github-releases.githubusercontent.com",
        "codeload.github.com"
    )

    fun isAllowedGitHubHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return ALLOWED_GITHUB_HOSTS.contains(h) ||
                h.endsWith(".githubusercontent.com") ||
                h.endsWith(".github.com")
    }

    fun isAllowedGitHubUrl(urlStr: String): Boolean {
        return try {
            val u = java.net.URL(urlStr)
            u.protocol.equals("https", ignoreCase = true) && isAllowedGitHubHost(u.host)
        } catch (_: Exception) {
            false
        }
    }

    private fun buildNetScript(
        apkPath: String,
        mode: String,
        url: String,
        targetFile: File? = null,
        timeoutSec: Int = 10
    ): String {
        val qUrl = ShellUtils.shellQuote(url)
        val qApkPath = ShellUtils.shellQuote(apkPath)

        val curlCmd = if (mode == "get") {
            "curl -s -f -L --proto =https --connect-timeout $timeoutSec -H \"User-Agent: PhoneControl-Root\" -H \"Accept: application/vnd.github.v3+json\" $qUrl"
        } else {
            val qTarget = ShellUtils.shellQuote(targetFile!!.absolutePath)
            "curl -s -f -L --proto =https --connect-timeout 10 -m $timeoutSec -o $qTarget -H \"User-Agent: PhoneControl-Root\" $qUrl"
        }

        val appProcessCmd = if (mode == "get") {
            "/system/bin/app_process /system/bin com.example.phonecontrol.RootNetCli get $qUrl"
        } else {
            val qTarget = ShellUtils.shellQuote(targetFile!!.absolutePath)
            "/system/bin/app_process /system/bin com.example.phonecontrol.RootNetCli download $qUrl $qTarget"
        }

        return """
            if command -v curl >/dev/null 2>&1; then
                $curlCmd
            else
                export CLASSPATH=$qApkPath
                $appProcessCmd
            fi
        """.trimIndent()
    }

    /**
     * Fetches an HTTPS URL via Root Shell.
     * Returns Triple(isSuccess, responseBody, errorMessage).
     */
    fun fetchHttps(context: Context, url: String, timeoutSec: Int = 10): Triple<Boolean, String, String> {
        if (!isAllowedGitHubUrl(url)) {
            return Triple(false, "", "Disallowed URL: Request must use HTTPS and an authorized GitHub host")
        }

        val apkPath = context.applicationInfo.sourceDir
        val script = buildNetScript(apkPath, "get", url, timeoutSec = timeoutSec)

        val res = ShellUtils.runAsRoot(script, (timeoutSec + 6) * 1000L)
        val output = res.output.trim()

        if (output.startsWith("ERR:")) {
            val err = output.removePrefix("ERR:").trim()
            Log.e(TAG, "Root fetch error: $err")
            return Triple(false, "", err)
        }

        if (res.exitCode != 0 || output.isBlank()) {
            return Triple(false, "", "Root network request failed (exit code ${res.exitCode}) or returned empty response")
        }

        return Triple(true, output, "")
    }

    /**
     * Downloads a remote file to a destination path via Root Shell.
     * Returns Pair(isSuccess, errorMessage).
     */
    fun downloadFile(
        context: Context,
        url: String,
        destination: File,
        expectedSizeBytes: Long? = null,
        timeoutSec: Int = 90
    ): Pair<Boolean, String> {
        if (!isAllowedGitHubUrl(url)) {
            return Pair(false, "Disallowed URL: Download must use HTTPS and an authorized GitHub host")
        }

        destination.parentFile?.mkdirs()
        val apkPath = context.applicationInfo.sourceDir

        // Download to a temporary file
        val tempFile = File(destination.parentFile, "${destination.name}.tmp_${System.currentTimeMillis()}")
        val script = buildNetScript(apkPath, "download", url, tempFile, timeoutSec)

        val res = ShellUtils.runAsRoot(script, (timeoutSec + 10) * 1000L)

        val qTemp = ShellUtils.shellQuote(tempFile.absolutePath)
        val qDest = ShellUtils.shellQuote(destination.absolutePath)

        // Require zero exit status
        if (res.exitCode != 0 || !tempFile.exists()) {
            ShellUtils.runAsRoot("rm -f $qTemp", 5000)
            val err = if (res.output.contains("ERR:")) res.output.substringAfter("ERR:").trim() else "Download failed with exit code ${res.exitCode}"
            return Pair(false, err)
        }

        val actualSize = tempFile.length()

        // When supplied by the release API, require an exact asset-size match
        if (expectedSizeBytes != null && expectedSizeBytes > 0L) {
            if (actualSize != expectedSizeBytes) {
                ShellUtils.runAsRoot("rm -f $qTemp", 5000)
                return Pair(false, "Asset size mismatch: expected $expectedSizeBytes bytes, but received $actualSize bytes")
            }
        } else if (actualSize <= 0L) {
            ShellUtils.runAsRoot("rm -f $qTemp", 5000)
            return Pair(false, "Downloaded file is empty (0 bytes)")
        }

        // Set minimum installer-required mode (644 instead of 666) and rename only after success
        val finalizeRes = ShellUtils.runAsRoot("chmod 644 $qTemp && mv -f $qTemp $qDest", 10000)
        if (finalizeRes.exitCode != 0 || !destination.exists()) {
            ShellUtils.runAsRoot("rm -f $qTemp", 5000)
            return Pair(false, "Failed to finalize downloaded package (exit code ${finalizeRes.exitCode})")
        }

        return Pair(true, "")
    }
}
