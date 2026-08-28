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
    private const val MAX_OUTPUT_LINES = 400

    /**
     * Check if the shell is currently busy with a long-running task.
     */
    var isBusy = false
        private set

    /**
     * Runs a command as root and returns the output safely.
     * Prevents pipe buffer deadlock and OutOfMemoryError.
     */
    fun runAsRoot(command: String): ShellResult {
        if (isBusy && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return ShellResult(-1, "Shell Busy")
        }
        
        synchronized(this) {
            try {
                isBusy = true
                ensureShell()
                
                val wrappedCommand = "($command) 2>&1; echo \"_EXIT_CODE_:\$?\"\n"
                
                os?.writeBytes(wrappedCommand)
                os?.writeBytes("echo $DONE_TOKEN\n")
                os?.flush()

                val output = StringBuilder()
                var exitCode = 0
                var lineCount = 0

                while (true) {
                    val line = reader?.readLine() ?: break
                    if (line == DONE_TOKEN) break
                    
                    if (line.startsWith("_EXIT_CODE_:")) {
                        exitCode = line.substringAfter(":").toIntOrNull() ?: 0
                    } else {
                        if (lineCount < MAX_OUTPUT_LINES) {
                            output.append(line).append("\n")
                            lineCount++
                        }
                    }
                }
                
                return ShellResult(exitCode, output.toString().trim())
            } catch (e: Exception) {
                Log.e("ShellUtils", "Error running command: $command", e)
                closePersistentShell()
                return ShellResult(-1, e.message ?: "Error")
            } finally {
                isBusy = false
            }
        }
    }

    /**
     * Fast command execution (no output). 
     * Directs stdout/stderr to /dev/null to prevent 64KB Linux pipe buffer overflow.
     */
    @Synchronized
    fun fastCmd(command: String) {
        try {
            ensureShell()
            os?.writeBytes("($command) >/dev/null 2>&1\n")
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
