package com.example.phonecontrol

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import kotlin.concurrent.thread

class AdbShellActivity : AppCompatActivity() {

    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText
    private lateinit var scrollOutput: NestedScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adb_shell)

        tvOutput = findViewById(R.id.tvAdbOutput)
        etInput = findViewById(R.id.etAdbInput)
        scrollOutput = findViewById(R.id.scrollOutput)

        findViewById<MaterialToolbar>(R.id.toolbarAdb).setNavigationOnClickListener { finish() }
        
        findViewById<View>(R.id.btnShowTips).setOnClickListener {
            showCommandTips()
        }

        findViewById<ImageButton>(R.id.btnSendAdb).setOnClickListener {
            executeCommand(etInput.text.toString())
        }

        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                executeCommand(etInput.text.toString())
                true
            } else false
        }

        // Quick Chips
        findViewById<Chip>(R.id.chipGetProp).setOnClickListener { executeCommand("getprop | grep model") }
        findViewById<Chip>(R.id.chipLs).setOnClickListener { executeCommand("ls -l /data") }
        findViewById<Chip>(R.id.chipUptime).setOnClickListener { executeCommand("uptime") }
        
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
            tvOutput.text = "localhost:~# "
        }
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
            "pm enable <pkg>" to "Enables a disabled app.",
            "pm disable-user --user 0 <pkg>" to "Completely freezes/disables an app.",
            "pm list packages -d" to "Lists all currently disabled apps.",
            "wm density <dpi>" to "Changes screen DPI (e.g. 400). Use 'reset' to revert.",
            "wm size <width>x<height>" to "Changes screen resolution. Use 'reset' to revert.",
            "dumpsys battery" to "Shows detailed live battery and charging status.",
            "top -n 1 -m 5" to "Shows top 5 apps consuming CPU currently.",
            "settings put global window_animation_scale 0.5" to "Sets window animations to 0.5x speed.",
            "reboot recovery" to "Restarts the phone into Recovery mode.",
            "input keyevent 26" to "Simulates pressing the Power button."
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

    private fun executeCommand(cmd: String) {
        if (cmd.isBlank()) return
        
        var commandToRun = cmd.trim()
        
        // Auto-fix: Remove 'adb shell' prefix if user typed it
        if (commandToRun.startsWith("adb shell ")) {
            commandToRun = commandToRun.substring("adb shell ".length)
        } else if (commandToRun.startsWith("adb ")) {
            commandToRun = commandToRun.substring("adb ".length)
        }

        etInput.setText("")
        tvOutput.append("\nlocalhost:~# $commandToRun\n")
        
        thread {
            val result = ShellUtils.runAsRoot(commandToRun)
            runOnUiThread {
                if (result.output.isNotBlank()) {
                    tvOutput.append(result.output + "\n")
                } else if (result.exitCode != 0) {
                    tvOutput.append("[Error: Exit code ${result.exitCode}]\n")
                }
                
                // Scroll to bottom
                scrollOutput.post {
                    scrollOutput.fullScroll(NestedScrollView.FOCUS_DOWN)
                }
            }
        }
    }
}
