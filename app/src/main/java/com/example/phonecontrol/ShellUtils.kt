package com.example.phonecontrol

import java.io.DataOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import android.util.Log

object ShellUtils {
    private var persistentProcess: Process? = null
    private var os: DataOutputStream? = null
    
    // Simple way to run a command and get output without persistent overhead for one-offs
    @Synchronized
    fun runAsRoot(command: String): ShellResult {
        var process: Process? = null
        var reader: BufferedReader? = null
        val output = StringBuilder()

        try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            return ShellResult(process.exitValue(), output.toString().trim())
        } catch (e: Exception) {
            Log.e("ShellUtils", "Error: $command", e)
            return ShellResult(-1, e.message ?: "Error")
        } finally {
            try {
                reader?.close()
                process?.destroy()
            } catch (e: Exception) {}
        }
    }

    // Fast command execution for background service (no output reading to save battery)
    @Synchronized
    fun fastCmd(command: String) {
        try {
            if (persistentProcess == null) {
                persistentProcess = Runtime.getRuntime().exec("su")
                os = DataOutputStream(persistentProcess!!.outputStream)
            }
            os?.writeBytes("$command\n")
            os?.flush()
        } catch (e: Exception) {
            closePersistentShell()
        }
    }

    fun closePersistentShell() {
        try {
            os?.writeBytes("exit\n")
            os?.flush()
            os?.close()
            persistentProcess?.destroy()
        } catch (e: Exception) {}
        os = null
        persistentProcess = null
    }

    fun runCommandsAsRoot(commands: List<String>): ShellResult {
        return runAsRoot(commands.joinToString(" && "))
    }

    data class ShellResult(val exitCode: Int, val output: String)
}
