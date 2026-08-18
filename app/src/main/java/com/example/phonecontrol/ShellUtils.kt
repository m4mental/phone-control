package com.example.phonecontrol

import java.io.DataOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import android.util.Log

object ShellUtils {
    private var persistentProcess: Process? = null
    private var os: DataOutputStream? = null
    private var reader: BufferedReader? = null
    
    private const val DONE_TOKEN = "---CMD_DONE---"

    /**
     * Runs a command as root and returns the output.
     * Reuses the persistent shell session to avoid spawning new processes.
     */
    @Synchronized
    fun runAsRoot(command: String): ShellResult {
        try {
            ensureShell()
            
            // Send the command followed by a unique token
            os?.writeBytes("$command\n")
            os?.writeBytes("echo $DONE_TOKEN\n")
            os?.flush()

            val output = StringBuilder()
            var line: String?
            while (true) {
                line = reader?.readLine()
                if (line == null || line == DONE_TOKEN) break
                output.append(line).append("\n")
            }
            
            return ShellResult(0, output.toString().trim())
        } catch (e: Exception) {
            Log.e("ShellUtils", "Error running command: $command", e)
            closePersistentShell()
            return ShellResult(-1, e.message ?: "Error")
        }
    }

    /**
     * Fast command execution (no output). Reuses the same shell.
     */
    @Synchronized
    fun fastCmd(command: String) {
        try {
            ensureShell()
            os?.writeBytes("$command\n")
            os?.flush()
        } catch (e: Exception) {
            Log.e("ShellUtils", "Error in fastCmd", e)
            closePersistentShell()
        }
    }

    @Synchronized
    private fun ensureShell() {
        if (persistentProcess == null || !isProcessAlive(persistentProcess)) {
            closePersistentShell()
            persistentProcess = Runtime.getRuntime().exec("su")
            os = DataOutputStream(persistentProcess!!.outputStream)
            reader = BufferedReader(InputStreamReader(persistentProcess!!.inputStream))
        }
    }

    private fun isProcessAlive(p: Process?): Boolean {
        return try {
            p?.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    @Synchronized
    fun closePersistentShell() {
        try {
            os?.writeBytes("exit\n")
            os?.flush()
        } catch (e: Exception) {}
        
        try { os?.close() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        try { persistentProcess?.destroy() } catch (e: Exception) {}
        
        os = null
        reader = null
        persistentProcess = null
    }

    fun runCommandsAsRoot(commands: List<String>): ShellResult {
        return runAsRoot(commands.joinToString(" && "))
    }

    data class ShellResult(val exitCode: Int, val output: String)
}
