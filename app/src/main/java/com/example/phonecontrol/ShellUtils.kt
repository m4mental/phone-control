package com.example.phonecontrol

import java.io.DataOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object ShellUtils {
    private var persistentProcess: Process? = null
    private var os: DataOutputStream? = null
    private var reader: BufferedReader? = null
    
    private const val DONE_TOKEN = "---CMD_DONE---"
    private const val MAX_OUTPUT_LINES = 400
    private val shellExecutor = Executors.newSingleThreadExecutor()

    /**
     * Check if the shell is currently busy with a long-running task.
     */
    var isBusy = false
        private set

    /**
     * Standalone, timeout-guaranteed root checker.
     * Uses a direct, isolated process so it never hangs or conflicts with the persistent shell.
     */
    fun checkRootStandalone(timeoutMs: Long = 3000): Boolean {
        return try {
            val future = shellExecutor.submit<Boolean> {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val r = BufferedReader(InputStreamReader(p.inputStream))
                val out = r.readLine() ?: ""
                p.waitFor()
                out.contains("uid=0") || p.exitValue() == 0
            }
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e("ShellUtils", "checkRootStandalone error or timeout: ${e.message}")
            false
        }
    }

    /**
     * Runs a command as root and returns the output safely with a strict 4.0s timeout watchdog.
     * Prevents pipe buffer deadlock, ANRs, and OutOfMemoryError.
     */
    fun runAsRoot(command: String, timeoutMs: Long = 4000): ShellResult {
        if (isBusy && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return ShellResult(-1, "Shell Busy")
        }
        
        return try {
            val future = shellExecutor.submit<ShellResult> {
                synchronized(this@ShellUtils) {
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
                        
                        ShellResult(exitCode, output.toString().trim())
                    } finally {
                        isBusy = false
                    }
                }
            }
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Log.e("ShellUtils", "runAsRoot timed out on command: $command")
            closePersistentShell()
            isBusy = false
            ShellResult(-1, "Command Timed Out")
        } catch (e: Exception) {
            Log.e("ShellUtils", "Error running command: $command", e)
            closePersistentShell()
            isBusy = false
            ShellResult(-1, e.message ?: "Error")
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

    /**
     * Fast atomic batch execution of multiple commands in a single write.
     */
    @Synchronized
    fun fastBatchCmd(commands: List<String>) {
        if (commands.isEmpty()) return
        val joined = commands.joinToString("; ")
        fastCmd(joined)
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
