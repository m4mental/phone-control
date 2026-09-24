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

    /**
     * Fetches an HTTPS URL via Root Shell.
     * Returns Triple(isSuccess, responseBody, errorMessage).
     */
    fun fetchHttps(context: Context, url: String, timeoutSec: Int = 10): Triple<Boolean, String, String> {
        val apkPath = context.applicationInfo.sourceDir

        val script = """
            if command -v curl >/dev/null 2>&1; then
                curl -s -L --connect-timeout $timeoutSec -H "User-Agent: PhoneControl-Root" -H "Accept: application/vnd.github.v3+json" "$url"
            else
                export CLASSPATH="$apkPath"
                /system/bin/app_process /system/bin com.example.phonecontrol.RootNetCli get "$url"
            fi
        """.trimIndent()

        val res = ShellUtils.runAsRoot(script, (timeoutSec + 6) * 1000L)
        val output = res.output.trim()

        if (output.startsWith("ERR:")) {
            val err = output.removePrefix("ERR:").trim()
            Log.e(TAG, "Root fetch error: $err")
            return Triple(false, "", err)
        }

        if (res.exitCode != 0 || output.isBlank()) {
            return Triple(false, "", "Root network request failed or timed out")
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
        timeoutSec: Int = 90
    ): Pair<Boolean, String> {
        destination.parentFile?.mkdirs()
        val apkPath = context.applicationInfo.sourceDir
        val destPath = destination.absolutePath

        val script = """
            rm -f "$destPath"
            if command -v curl >/dev/null 2>&1; then
                curl -s -L --connect-timeout 10 -m $timeoutSec -o "$destPath" -H "User-Agent: PhoneControl-Root" "$url"
            else
                export CLASSPATH="$apkPath"
                /system/bin/app_process /system/bin com.example.phonecontrol.RootNetCli download "$url" "$destPath"
            fi
            chmod 666 "$destPath"
        """.trimIndent()

        val res = ShellUtils.runAsRoot(script, (timeoutSec + 10) * 1000L)

        if (destination.exists() && destination.length() > 10000) {
            return Pair(true, "")
        }

        val err = if (res.output.contains("ERR:")) res.output.substringAfter("ERR:").trim() else "Download incomplete (Size: ${destination.length()} bytes)"
        return Pair(false, err)
    }
}
