package com.example.phonecontrol

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import kotlin.concurrent.thread

class AdbShellActivity : AppCompatActivity() {

    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText
    private lateinit var scrollOutput: NestedScrollView
    
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    private val appInspectorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val pkg = result.data?.getStringExtra("package_name")
            if (pkg != null) {
                insertTextAtCursor(" $pkg ")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adb_shell)

        tvOutput = findViewById(R.id.tvAdbOutput)
        etInput = findViewById(R.id.etAdbInput)
        scrollOutput = findViewById(R.id.scrollOutput)
        
        // Hide UI until authenticated
        findViewById<View>(android.R.id.content).visibility = View.GONE
        showSecurityCheck()

        findViewById<MaterialToolbar>(R.id.toolbarAdb).setNavigationOnClickListener { finish() }

        // Copy & Share
        findViewById<ImageButton>(R.id.btnCopyOutput).setOnClickListener { copyOutputToClipboard() }
        findViewById<ImageButton>(R.id.btnShareLog).setOnClickListener { shareOutputLog() }

        findViewById<View>(R.id.btnShowTips).setOnClickListener { showCommandTips() }
        findViewById<View>(R.id.btnAppList).setOnClickListener {
            appInspectorLauncher.launch(Intent(this, AppInspectorActivity::class.java))
        }

        findViewById<ImageButton>(R.id.btnProcessMonitor).setOnClickListener { runSystemSnapshot() }
        findViewById<Chip>(R.id.chipLiveStats).setOnClickListener { runSystemSnapshot() }

        findViewById<ImageButton>(R.id.btnSendAdb).setOnClickListener {
            handleCommandSubmission(etInput.text.toString())
        }

        findViewById<ImageButton>(R.id.btnHistory).setOnClickListener {
            showHistoryDialog()
        }

        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                handleCommandSubmission(etInput.text.toString())
                true
            } else false
        }
        
        loadHistory()
        setupHackerBar()
        setupQuickChips()

        // Initial prompt
        tvOutput.text = ""
        appendColoredText("root@phonecontrol:~# ", Color.parseColor("#00E676"))
    }

    private fun setupHackerBar() {
        findViewById<Button>(R.id.btnKeyHistoryUp).setOnClickListener {
            if (commandHistory.isNotEmpty()) {
                if (historyIndex < commandHistory.size - 1) {
                    historyIndex++
                    etInput.setText(commandHistory[historyIndex])
                    etInput.setSelection(etInput.text.length)
                }
            }
        }

        findViewById<Button>(R.id.btnKeyHistoryDown).setOnClickListener {
            if (commandHistory.isNotEmpty()) {
                if (historyIndex > 0) {
                    historyIndex--
                    etInput.setText(commandHistory[historyIndex])
                    etInput.setSelection(etInput.text.length)
                } else if (historyIndex == 0) {
                    historyIndex = -1
                    etInput.setText("")
                }
            }
        }

        findViewById<Button>(R.id.btnKeyPipe).setOnClickListener { insertTextAtCursor(" | ") }
        findViewById<Button>(R.id.btnKeySlash).setOnClickListener { insertTextAtCursor("/") }
        findViewById<Button>(R.id.btnKeyGrep).setOnClickListener { insertTextAtCursor("grep ") }
        findViewById<Button>(R.id.btnKeyPs).setOnClickListener { insertTextAtCursor("ps -A ") }
        findViewById<Button>(R.id.btnKeyDumpsys).setOnClickListener { insertTextAtCursor("dumpsys ") }
        findViewById<Button>(R.id.btnKeyLogcat).setOnClickListener { executeCommand("logcat -d | tail -n 50") }
        findViewById<Button>(R.id.btnKeyClear).setOnClickListener {
            tvOutput.text = ""
            appendColoredText("root@phonecontrol:~# ", Color.parseColor("#00E676"))
        }
    }

    private fun setupQuickChips() {
        findViewById<Chip>(R.id.chipGetProp).setOnClickListener { executeCommand("getprop ro.product.model; getprop ro.build.version.release") }
        findViewById<Chip>(R.id.chipLs).setOnClickListener { executeCommand("ls -la /data/data | head -n 30") }
        findViewById<Chip>(R.id.chipUptime).setOnClickListener { executeCommand("uptime; cat /proc/loadavg") }
        
        findViewById<Chip>(R.id.chipUfs).setOnClickListener { 
            executeCommand("cat /sys/block/sd*/device/model 2>/dev/null; echo 'Storage Health:'; cat /sys/class/scsi_host/host*/health_index 2>/dev/null || df -h /data") 
        }
        findViewById<Chip>(R.id.chipNet).setOnClickListener { 
            executeCommand("ip -br addr; echo 'Routing Table:'; ip route | head -n 10") 
        }

        findViewById<Chip>(R.id.chipEnableApp).setOnClickListener { showEnableAppDialog() }
        findViewById<Chip>(R.id.chipDisableApp).setOnClickListener { showDisableAppDialog() }
        findViewById<Chip>(R.id.chipDeleteApp).setOnClickListener { showDeleteAppDialog() }
    }

    private fun insertTextAtCursor(text: String) {
        val start = etInput.selectionStart.coerceAtLeast(0)
        val end = etInput.selectionEnd.coerceAtLeast(0)
        etInput.text.replace(start.coerceAtMost(end), start.coerceAtLeast(end), text, 0, text.length)
        etInput.setSelection(start + text.length)
        etInput.requestFocus()
    }

    private fun copyOutputToClipboard() {
        val text = tvOutput.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Terminal is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Terminal Output", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "📋 Terminal output copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun shareOutputLog() {
        val text = tvOutput.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Terminal is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Share Terminal Log"))
    }

    private fun runSystemSnapshot() {
        appendColoredText("\n📊 [GATHERING LIVE 8-CORE SYSTEM SNAPSHOT...]\n", Color.parseColor("#00E5FF"))
        thread {
            val cpuFreq = ShellUtils.runAsRoot("cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq 2>/dev/null").output.trim()
            val memInfo = ShellUtils.runAsRoot("free -m").output.trim()
            val topApps = ShellUtils.runAsRoot("dumpsys cpuinfo | grep -E '[0-9]+% [0-9]+/' | head -n 5").output.trim()
            val temp = ShellUtils.runAsRoot("cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null").output.trim()

            runOnUiThread {
                appendColoredText("----------------------------------------\n", Color.DKGRAY)
                val tempC = temp.toIntOrNull()?.let { it / 1000 } ?: 0
                appendColoredText("🌡️ SoC Temperature: ${tempC}°C\n", if (tempC > 45) Color.RED else Color.GREEN)
                
                appendColoredText("⚡ CPU Frequencies (Cores 0-7):\n", Color.YELLOW)
                val freqs = cpuFreq.split("\n").map { (it.trim().toIntOrNull() ?: 0) / 1000 }
                freqs.forEachIndexed { index, mhz ->
                    val coreType = if (index >= 6) "Big Cortex-A715" else "Little Cortex-A510"
                    appendColoredText("  Core $index ($coreType): ${mhz} MHz\n", Color.CYAN)
                }

                appendColoredText("\n🧠 Memory State:\n$memInfo\n", Color.LTGRAY)
                appendColoredText("\n🔥 Top 5 CPU Hungry Apps:\n$topApps\n", Color.parseColor("#FFD700"))
                appendColoredText("----------------------------------------\n", Color.DKGRAY)
                appendColoredText("root@phonecontrol:~# ", Color.parseColor("#00E676"))

                scrollOutput.post { scrollOutput.fullScroll(NestedScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun showEnableAppDialog() {
        val et = EditText(this).apply { hint = "com.package.name" }
        AlertDialog.Builder(this)
            .setTitle("Force Enable App")
            .setMessage("Enter the package name of the app to enable/unhide.")
            .setView(et)
            .setPositiveButton("ENABLE") { _, _ ->
                val pkg = et.text.toString().trim()
                if (pkg.isNotEmpty()) {
                    executeCommand("pm enable $pkg; pm unhide $pkg")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDisableAppDialog() {
        val et = EditText(this).apply { hint = "com.package.name" }
        AlertDialog.Builder(this)
            .setTitle("Force Disable/Freeze App")
            .setMessage("Enter package name to completely freeze and hide.")
            .setView(et)
            .setPositiveButton("DISABLE") { _, _ ->
                val pkg = et.text.toString().trim()
                if (pkg.isNotEmpty()) {
                    executeCommand("pm disable-user --user 0 $pkg; pm hide $pkg")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteAppDialog() {
        val et = EditText(this).apply { hint = "com.package.name" }
        AlertDialog.Builder(this)
            .setTitle("Force Delete (Uninstall)")
            .setMessage("WARNING: This will uninstall the app for the current user!")
            .setView(et)
            .setPositiveButton("DELETE") { _, _ ->
                val pkg = et.text.toString().trim()
                if (pkg.isNotEmpty()) {
                    executeCommand("pm uninstall -k --user 0 $pkg")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCommandTips() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_adb_tips, findViewById(android.R.id.content), false)
        val container = view.findViewById<LinearLayout>(R.id.layoutTipsContainer)

        val tips = listOf(
            "dumpsys cpuinfo | head -n 10" to "Power Monitor: Top apps by CPU/Battery usage.",
            "pm enable <pkg>" to "Enables a disabled/frozen app.",
            "pm disable-user --user 0 <pkg>" to "Completely freezes/disables an app.",
            "pm hide <pkg>" to "Hides app from launcher (remains installed).",
            "pm unhide <pkg>" to "Makes a hidden app visible again.",
            "am force-stop <pkg>" to "Kills all app processes and services.",
            "pm clear <pkg>" to "Resets all app data (Login, settings, etc).",
            "pm list packages -3" to "Lists all installed user applications.",
            "dumpsys window | grep mCurrentFocus" to "Shows current foreground activity.",
            "wm density <dpi>" to "Changes screen DPI (e.g. 400).",
            "wm size <width>x<height>" to "Changes screen resolution.",
            "df -h" to "Shows storage space usage for all partitions.",
            "logcat -d" to "Displays latest system log buffer.",
            "reboot recovery" to "Restarts the phone into Recovery mode."
        )

        for (tip in tips) {
            val tipView = layoutInflater.inflate(R.layout.item_adb_tip, container, false)
            tipView.findViewById<TextView>(R.id.tvTipCommand).text = tip.first
            tipView.findViewById<TextView>(R.id.tvTipDesc).text = tip.second
            tipView.setOnClickListener {
                etInput.setText(tip.first.substringBefore(" <"))
                dialog.dismiss()
                etInput.requestFocus()
            }
            container.addView(tipView)
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun showSecurityCheck() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                runOnUiThread {
                    findViewById<View>(android.R.id.content).visibility = View.VISIBLE
                    Toast.makeText(this@AdbShellActivity, "Terminal Access Granted", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Toast.makeText(this@AdbShellActivity, "Authentication failed: $errString", Toast.LENGTH_LONG).show()
                finish()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Terminal Security")
            .setSubtitle("Authenticate fingerprint/PIN to open Root Shell")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun handleCommandSubmission(cmd: String) {
        if (cmd.isBlank()) return
        
        val lowerCmd = cmd.lowercase()
        if (lowerCmd.contains("rm -rf /") || lowerCmd.contains("reboot bootloader") || lowerCmd.contains("dd if=")) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ CRITICAL SAFEGUARD")
                .setMessage("You are about to execute a high-risk destructive command: \"$cmd\"\n\nAre you absolutely certain?")
                .setPositiveButton("EXECUTE") { _, _ -> executeCommand(cmd) }
                .setNegativeButton("CANCEL", null)
                .show()
        } else {
            executeCommand(cmd)
        }
    }

    private fun loadHistory() {
        val prefs = getSharedPreferences("adb_history", MODE_PRIVATE)
        val historySet = prefs.getStringSet("history", emptySet())
        if (historySet != null) {
            commandHistory.clear()
            commandHistory.addAll(historySet.toList())
        }
    }

    private fun saveToHistory(cmd: String) {
        commandHistory.remove(cmd)
        commandHistory.add(0, cmd)
        if (commandHistory.size > 50) commandHistory.removeAt(commandHistory.size - 1)
        val prefs = getSharedPreferences("adb_history", MODE_PRIVATE)
        prefs.edit().putStringSet("history", commandHistory.toSet()).apply()
        historyIndex = -1
    }

    private fun showHistoryDialog() {
        if (commandHistory.isEmpty()) {
            Toast.makeText(this, "No history available", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Command History (${commandHistory.size})")
            .setItems(commandHistory.toTypedArray()) { _, which ->
                etInput.setText(commandHistory[which])
                etInput.setSelection(etInput.text.length)
                etInput.requestFocus()
            }
            .setNeutralButton("Clear All") { _, _ ->
                commandHistory.clear()
                getSharedPreferences("adb_history", MODE_PRIVATE).edit().clear().apply()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun executeCommand(cmd: String) {
        if (cmd.isBlank()) return
        
        var commandToRun = cmd.trim()
        if (commandToRun.startsWith("adb shell ")) {
            commandToRun = commandToRun.substring("adb shell ".length)
        } else if (commandToRun.startsWith("adb ")) {
            commandToRun = commandToRun.substring("adb ".length)
        }

        saveToHistory(cmd)
        etInput.setText("")
        appendColoredText("\nroot@phonecontrol:~# $commandToRun\n", Color.parseColor("#00E676"))
        
        thread {
            val result = ShellUtils.runAsRoot(commandToRun)
            runOnUiThread {
                if (result.output.isNotBlank()) {
                    appendColoredText(result.output + "\n", Color.LTGRAY)
                } else {
                    if (result.exitCode == 0) {
                        appendColoredText("[✓ Command executed with exit code 0]\n", Color.parseColor("#81C784"))
                    }
                }

                if (result.exitCode != 0 && result.exitCode != -1) {
                    appendColoredText("[✗ Error: Exit code ${result.exitCode}]\n", Color.parseColor("#FF5252"))
                }
                
                appendColoredText("root@phonecontrol:~# ", Color.parseColor("#00E676"))
                
                scrollOutput.post {
                    scrollOutput.fullScroll(NestedScrollView.FOCUS_DOWN)
                }
            }
        }
    }

    private fun appendColoredText(text: String, color: Int) {
        val builder = SpannableStringBuilder(text)
        builder.setSpan(ForegroundColorSpan(color), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvOutput.append(builder)
    }
}
