package com.example.phonecontrol

import java.io.DataOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import android.os.Build
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
    private val shellLock = Any()

    @Volatile var isRootGrantedCached: Boolean? = null

    private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z0-9._]+$")
    private val HOSTNAME_LABEL_REGEX = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

    /**
     * Safely quotes a value for POSIX shell execution using single-quote wrapping
     * and embedded-apostrophe escaping: 'foo'\''bar'
     */
    fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    /**
     * Validates an Android package name against `^[A-Za-z0-9._]+$` and segment checks.
     * Rejects invalid input without repairing.
     */
    fun isValidPackageName(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        if (!PACKAGE_NAME_REGEX.matches(packageName)) return false
        val segments = packageName.split('.')
        if (segments.size < 2) return false
        return segments.all { segment ->
            segment.isNotEmpty() && (segment[0].isLetter() || segment[0] == '_')
        }
    }

    /**
     * Validates a DNS hostname against RFC 1035 / RFC 1123 label constraints.
     * Rejects invalid input without repairing.
     */
    fun isValidHostname(hostname: String?): Boolean {
        if (hostname.isNullOrBlank() || hostname.length > 253) return false
        val labels = hostname.split('.')
        if (labels.isEmpty()) return false
        return labels.all { label ->
            label.isNotEmpty() && label.length <= 63 && HOSTNAME_LABEL_REGEX.matches(label)
        }
    }

    /**
     * Validates that target path is strictly contained within baseDir, preventing path traversal.
     */
    fun isPathContained(path: String?, baseDir: String?): Boolean {
        if (path.isNullOrBlank() || baseDir.isNullOrBlank()) return false
        return try {
            val target = java.io.File(path).canonicalFile
            val base = java.io.File(baseDir).canonicalFile
            val basePath = base.path
            val targetPath = target.path
            targetPath == basePath || targetPath.startsWith(basePath + java.io.File.separator)
        } catch (e: Exception) {
            false
        }
    }

    fun validatePathInBaseDir(path: String?, baseDir: String?): Boolean {
        return isPathContained(path, baseDir)
    }

    /**
     * Validates that a string is a numeric positive PID.
     */
    fun isValidPid(pid: String?): Boolean {
        if (pid.isNullOrBlank()) return false
        val numeric = pid.toLongOrNull() ?: return false
        return numeric > 0
    }

    private fun waitForProcess(process: Process, timeoutMs: Long): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } else {
            val t = kotlin.concurrent.thread {
                try { process.waitFor() } catch (_: InterruptedException) {}
            }
            t.join(timeoutMs)
            if (t.isAlive) {
                t.interrupt()
                false
            } else {
                true
            }
        }
    }

    private fun destroyProcess(process: Process?) {
        if (process == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.destroyForcibly()
            } else {
                process.destroy()
            }
        } catch (_: Exception) {}
    }

    /**
     * Executes a fast root command and returns output string.
     * Distinguishes errors and "Shell Busy" from successful output and does not return empty string on failures.
     */
    fun fastCmdResult(command: String, timeoutMs: Long = 3000): String {
        return try {
            val res = runAsRoot(command, timeoutMs)
            if (res.exitCode == 0) {
                res.output
            } else {
                "ERROR: ${res.output.ifBlank { "Exit code ${res.exitCode}" }}"
            }
        } catch (e: Exception) {
            "ERROR: ${e.message ?: "Execution failed"}"
        }
    }

    /**
     * Executes a fast background root command without blocking the main UI thread.
     */
    var isBusy = false
        private set

    /**
     * Standalone, isolated root checker.
     * Executes directly on an independent process so it never gets blocked by the single-thread shellExecutor queue.
     * Reads output on a worker thread, enforces timeout, destroys timed-out processes, and closes streams.
     */
    fun checkRootStandalone(timeoutMs: Long = 4000, forceCheck: Boolean = false): Boolean {
        if (!forceCheck && isRootGrantedCached == true) return true

        var proc: Process? = null
        var inReader: BufferedReader? = null
        var errReader: BufferedReader? = null

        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            proc = p

            var out = ""
            val readThread = kotlin.concurrent.thread {
                try {
                    val r = BufferedReader(InputStreamReader(p.inputStream))
                    inReader = r
                    out = r.readLine() ?: ""
                } catch (_: Exception) {}
            }

            kotlin.concurrent.thread(isDaemon = true) {
                try {
                    val er = BufferedReader(InputStreamReader(p.errorStream))
                    errReader = er
                    while (er.readLine() != null) {}
                } catch (_: Exception) {}
            }

            val exited = waitForProcess(p, timeoutMs)
            if (!exited) {
                Log.w("ShellUtils", "checkRootStandalone timed out after ${timeoutMs}ms, destroying process")
                destroyProcess(p)
                readThread.interrupt()
                isRootGrantedCached = false
                return false
            }

            readThread.join(minOf(timeoutMs, 1000L))

            val isRoot = (out.contains("uid=0") || p.exitValue() == 0)
            isRootGrantedCached = isRoot
            isRoot
        } catch (e: Exception) {
            Log.e("ShellUtils", "checkRootStandalone direct exec error: ${e.message}")
            isRootGrantedCached = false
            false
        } finally {
            try { inReader?.close() } catch (_: Exception) {}
            try { errReader?.close() } catch (_: Exception) {}
            try { proc?.inputStream?.close() } catch (_: Exception) {}
            try { proc?.errorStream?.close() } catch (_: Exception) {}
            try { proc?.outputStream?.close() } catch (_: Exception) {}
            if (proc != null && isProcessAlive(proc)) {
                destroyProcess(proc)
            }
        }
    }

    /**
     * Runs a command as root and returns the output safely with an effective watchdog.
     * Prevents pipe buffer deadlock, ANRs, and OutOfMemoryError.
     */
    fun runAsRoot(command: String, timeoutMs: Long = 4000): ShellResult {
        val isMainThread = (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        if (isBusy && isMainThread) {
            return ShellResult(-1, "Shell Busy")
        }

        // Strict fail-safe: Never block UI thread for more than 2000ms to prevent Android ANR
        val effectiveTimeout = if (isMainThread) minOf(timeoutMs, 2000L) else timeoutMs
        
        var submittedFuture: java.util.concurrent.Future<ShellResult>? = null
        return try {
            val future = shellExecutor.submit<ShellResult> {
                synchronized(shellLock) {
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
                        var reachedDoneToken = false

                        while (true) {
                            val line = reader?.readLine()
                            if (line == null) {
                                break
                            }
                            if (line == DONE_TOKEN) {
                                reachedDoneToken = true
                                break
                            }
                            
                            if (line.startsWith("_EXIT_CODE_:")) {
                                exitCode = line.substringAfter(":").toIntOrNull() ?: 0
                            } else {
                                if (lineCount < MAX_OUTPUT_LINES) {
                                    output.append(line).append("\n")
                                    lineCount++
                                }
                            }
                        }
                        
                        if (!reachedDoneToken) {
                            Log.e("ShellUtils", "Persistent shell stream reached EOF before DONE_TOKEN")
                            synchronized(shellLock) {
                                closePersistentShell()
                            }
                            ShellResult(-2, "Root shell process terminated unexpectedly before command completion (EOF)")
                        } else {
                            ShellResult(exitCode, output.toString().trim())
                        }
                    } finally {
                        isBusy = false
                    }
                }
            }
            submittedFuture = future
            future.get(effectiveTimeout, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Log.e("ShellUtils", "runAsRoot timed out (${effectiveTimeout}ms) on command: $command")
            submittedFuture?.cancel(true)
            kotlin.concurrent.thread { synchronized(shellLock) { closePersistentShell() } }
            isBusy = false
            ShellResult(-1, "Command Timed Out")
        } catch (e: Exception) {
            Log.e("ShellUtils", "Error running command: $command", e)
            submittedFuture?.cancel(true)
            kotlin.concurrent.thread { synchronized(shellLock) { closePersistentShell() } }
            isBusy = false
            ShellResult(-1, e.message ?: "Error")
        }
    }

    /**
     * Fast command execution (no output). 
     * Directs stdout/stderr to /dev/null to prevent 64KB Linux pipe buffer overflow.
     * Synchronized on shellLock to ensure zero interleaved pipe writes.
     */
    fun fastCmd(command: String) {
        shellExecutor.execute {
            synchronized(shellLock) {
                try {
                    ensureShell()
                    os?.writeBytes("($command) >/dev/null 2>&1\n")
                    os?.flush()
                } catch (e: Exception) {
                    Log.e("ShellUtils", "Error in fastCmd", e)
                    synchronized(shellLock) {
                        closePersistentShell()
                    }
                }
            }
        }
    }

    /**
     * Fast atomic batch execution of multiple commands in a single write.
     */
    fun fastBatchCmd(commands: List<String>) {
        if (commands.isEmpty()) return
        val joined = commands.joinToString("; ")
        fastCmd(joined)
    }

    /**
     * Executes command directly with su -mm (Mount Master / Global Namespace).
     * Drains stdout and stderr concurrently, enforces timeout, destroys timed-out processes,
     * and returns explicit failure instead of silently falling back to runAsRoot.
     */
    fun runAsRootMm(command: String, timeoutMs: Long = 5000): ShellResult {
        var proc: Process? = null
        var inReader: BufferedReader? = null
        var errReader: BufferedReader? = null
        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()

        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", command))
            proc = p

            val stdoutThread = kotlin.concurrent.thread {
                try {
                    val r = BufferedReader(InputStreamReader(p.inputStream))
                    inReader = r
                    var l: String?
                    while (r.readLine().also { l = it } != null) {
                        stdoutBuilder.append(l).append("\n")
                    }
                } catch (_: Exception) {}
            }

            val stderrThread = kotlin.concurrent.thread {
                try {
                    val er = BufferedReader(InputStreamReader(p.errorStream))
                    errReader = er
                    var l: String?
                    while (er.readLine().also { l = it } != null) {
                        stderrBuilder.append(l).append("\n")
                    }
                } catch (_: Exception) {}
            }

            val exited = waitForProcess(p, timeoutMs)
            if (!exited) {
                Log.w("ShellUtils", "runAsRootMm timed out after ${timeoutMs}ms: $command")
                destroyProcess(p)
                stdoutThread.interrupt()
                stderrThread.interrupt()
                return ShellResult(-1, "Command Timed Out (su -mm)")
            }

            stdoutThread.join(1000)
            stderrThread.join(1000)

            val code = p.exitValue()
            val combined = (stdoutBuilder.toString() + stderrBuilder.toString()).trim()
            ShellResult(code, combined)
        } catch (e: Exception) {
            Log.e("ShellUtils", "runAsRootMm execution failed: ${e.message}", e)
            ShellResult(-1, "su -mm failed: ${e.message ?: "Execution error"}")
        } finally {
            try { inReader?.close() } catch (_: Exception) {}
            try { errReader?.close() } catch (_: Exception) {}
            try { proc?.inputStream?.close() } catch (_: Exception) {}
            try { proc?.errorStream?.close() } catch (_: Exception) {}
            try { proc?.outputStream?.close() } catch (_: Exception) {}
            if (proc != null && isProcessAlive(proc)) {
                destroyProcess(proc)
            }
        }
    }

    private fun ensureShell() {
        if (persistentProcess == null || !isProcessAlive(persistentProcess)) {
            synchronized(shellLock) {
                closePersistentShell()
            }
            val proc = try {
                Runtime.getRuntime().exec(arrayOf("su", "-mm"))
            } catch (e: Exception) {
                Runtime.getRuntime().exec("su")
            }
            persistentProcess = proc
            os = DataOutputStream(proc.outputStream)
            reader = BufferedReader(InputStreamReader(proc.inputStream))
            
            // Continuous background stderr drainer to eliminate pipe buffer deadlocks
            kotlin.concurrent.thread(isDaemon = true) {
                try {
                    val errReader = BufferedReader(InputStreamReader(proc.errorStream))
                    while (errReader.readLine() != null) {}
                } catch (_: Exception) {}
            }
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

    fun closePersistentShell() {
        synchronized(shellLock) {
            try {
                os?.writeBytes("exit\n")
                os?.flush()
            } catch (_: Exception) {}
            
            try { os?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            try {
                destroyProcess(persistentProcess)
            } catch (_: Exception) {}
            
            os = null
            reader = null
            persistentProcess = null
        }
    }

    fun runCommandsAsRoot(commands: List<String>): ShellResult {
        return runAsRoot(commands.joinToString(";\n"))
    }

    data class ShellResult(val exitCode: Int, val output: String) {
        val isSuccess: Boolean get() = exitCode == 0
    }
}
