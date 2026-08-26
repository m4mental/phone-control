package com.example.phonecontrol

import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.appcompat.app.AlertDialog
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
                etInput.append(pkg)
                etInput.requestFocus()
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
        
        findViewById<View>(R.id.btnShowTips).setOnClickListener {
            showCommandTips()
        }

        findViewById<View>(R.id.btnAppList).setOnClickListener {
            appInspectorLauncher.launch(Intent(this, AppInspectorActivity::class.java))
        }

        findViewById<View>(R.id.btnProcessMonitor).setOnClickListener {
            appendColoredText("\n[ANALYZING] Top 10 Power-Hungry Apps...\n", Color.YELLOW)
            // Filter dumpsys to show only % and package names clearly
            executeCommand("dumpsys cpuinfo | grep -E '[0-9]+% [0-9]+/' | head -n 10")
        }

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

        // Quick Chips
        findViewById<Chip>(R.id.chipGetProp).setOnClickListener { executeCommand("getprop | grep model") }
        findViewById<Chip>(R.id.chipLs).setOnClickListener { executeCommand("ls -l /data") }
        findViewById<Chip>(R.id.chipUptime).setOnClickListener { executeCommand("uptime") }
        
        findViewById<Chip>(R.id.chipUfs).setOnClickListener { 
            executeCommand("cat /sys/block/sd*/device/model; echo 'Health:'; cat /sys/class/scsi_host/host*/health_index 2>/dev/null || echo 'Not Supported'") 
        }
        findViewById<Chip>(R.id.chipNet).setOnClickListener { 
            executeCommand("dumpsys telephony.registry | grep -E 'mServiceState|mSignalStrength|mMessage'") 
        }

        findViewById<Chip>(R.id.chipEnableApp).setOnClickListener {
            showEnableAppDialog()
        }

        findViewById<Chip>(R.id.chipDisableApp).setOnClickListener {
            showDisableAppDialog()
        }

        findViewById<Chip>(R.id.chipDeleteApp).setOnClickListener {
            showDeleteAppDialog()
        }

        findViewById<Chip>(R.id.chipClear).setOnClickListener { 
            tvOutput.text = ""
            appendColoredText("localhost:~# ", Color.GREEN)
        }
        
        // Initial prompt
        tvOutput.text = ""
        appendColoredText("localhost:~# ", Color.GREEN)
    }

    private fun showEnableAppDialog() {
        val et = EditText(this)
        et.hint = "com.package.name"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Force Enable App")
            .setMessage("Enter the package name of the app to enable/unhide.")
            .setView(et)
            .setPositiveButton("ENABLE") { _, _ ->
                val pkg = et.text.toString().trim()
                if (pkg.isNotEmpty()) {
                    executeCommand("pm enable $pkg")
                    executeCommand("pm enable --user 0 $pkg")
                    executeCommand("pm unhide $pkg")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDisableAppDialog() {
        val et = EditText(this)
        et.hint = "com.package.name"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Force Disable/Freeze App")
            .setMessage("Enter the package name to completely freeze/hide the app.")
            .setView(et)
            .setPositiveButton("DISABLE") { _, _ ->
                val pkg = et.text.toString().trim()
                if (pkg.isNotEmpty()) {
                    executeCommand("pm disable-user --user 0 $pkg")
                    executeCommand("pm hide $pkg")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteAppDialog() {
        val et = EditText(this)
        et.hint = "com.package.name"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Force Delete (Uninstall)")
            .setMessage("WARNING: This will uninstall the app for the current user. This cannot be undone easily!")
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
            "dumpsys window | grep mCurrentFocus" to "Shows the current foreground activity.",
            "wm density <dpi>" to "Changes screen DPI (e.g. 400).",
            "wm size <width>x<height>" to "Changes screen resolution.",
            "df -h" to "Shows storage space usage for all partitions.",
            "logcat -d" to "Displays the latest system log buffer.",
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
                    Toast.makeText(this@AdbShellActivity, "Authenticated", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Toast.makeText(this@AdbShellActivity, "Authentication failed: $errString", Toast.LENGTH_LONG).show()
                finish() // Close activity if auth fails
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Terminal Security")
            .setSubtitle("Authenticate to access ADB Shell")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun handleCommandSubmission(cmd: String) {
        if (cmd.isBlank()) return
        
        // Dangerous Command Safeguard
        val lowerCmd = cmd.lowercase()
        if (lowerCmd.contains("rm ") || lowerCmd.contains("reboot") || lowerCmd.contains("pm uninstall")) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ DANGEROUS COMMAND")
                .setMessage("You are about to run a system-altering command: \"$cmd\"\n\nProceeding may cause data loss or bootloops. Are you sure?")
                .setPositiveButton("RUN ANYWAY") { _, _ -> executeCommand(cmd) }
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
        if (!commandHistory.contains(cmd)) {
            commandHistory.add(0, cmd)
            if (commandHistory.size > 20) commandHistory.removeAt(commandHistory.size - 1)
            val prefs = getSharedPreferences("adb_history", MODE_PRIVATE)
            prefs.edit().putStringSet("history", commandHistory.toSet()).apply()
        }
    }

    private fun showHistoryDialog() {
        if (commandHistory.isEmpty()) {
            Toast.makeText(this, "No history available", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Command History")
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

        saveToHistory(cmd) // Save successful or attempted command
        etInput.setText("")
        appendColoredText("\nlocalhost:~# $commandToRun\n", Color.GREEN)
        
        thread {
            val result = ShellUtils.runAsRoot(commandToRun)
            runOnUiThread {
                if (result.output.isNotBlank()) {
                    appendColoredText(result.output + "\n", Color.LTGRAY)
                } else {
                    // Feedback for commands that produce no output
                    if (result.exitCode == 0) {
                        appendColoredText("[Command executed successfully]\n", Color.GRAY)
                    }
                }

                if (result.exitCode != 0 && result.exitCode != -1) {
                    appendColoredText("[Error: Exit code ${result.exitCode}]\n", Color.RED)
                }
                
                appendColoredText("localhost:~# ", Color.GREEN)
                
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
