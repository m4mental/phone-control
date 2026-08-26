package com.example.phonecontrol

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class ModeControlActivity : AppCompatActivity() {

    private lateinit var rgModes: RadioGroup
    private lateinit var rgFocus: RadioGroup
    private lateinit var rgGlobalFps: RadioGroup
    private lateinit var layoutFocusSettings: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode_control)

        rgModes = findViewById(R.id.rgModes)
        rgFocus = findViewById(R.id.rgFocus)
        rgGlobalFps = findViewById(R.id.rgGlobalFps)
        layoutFocusSettings = findViewById(R.id.layoutFocusSettings)

        findViewById<MaterialToolbar>(R.id.toolbarModeControl).setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val savedMode = prefs.getString("selected_mode", "rbBalance")
        val savedFocus = prefs.getString("selected_focus", "rbFocusDaily")
        val savedFps = prefs.getString("selected_global_fps", "rbGlobalFpsAuto")

        // Setup Global Modes
        when (savedMode) {
            "rbPowerSaver" -> findViewById<RadioButton>(R.id.rbPowerSaver).isChecked = true
            "rbBalance" -> findViewById<RadioButton>(R.id.rbBalance).isChecked = true
            "rbPerformance" -> findViewById<RadioButton>(R.id.rbPerformance).isChecked = true
            "rbAutomatic" -> findViewById<RadioButton>(R.id.rbAutomatic).isChecked = true
        }
        layoutFocusSettings.visibility = if (savedMode == "rbAutomatic") View.VISIBLE else View.GONE

        // Setup Focus
        when (savedFocus) {
            "rbFocusBattery" -> findViewById<RadioButton>(R.id.rbFocusBattery).isChecked = true
            "rbFocusMultitasking" -> findViewById<RadioButton>(R.id.rbFocusMultitasking).isChecked = true
            else -> findViewById<RadioButton>(R.id.rbFocusDaily).isChecked = true
        }

        // Setup Global FPS
        when (savedFps) {
            "rbGlobalFps30" -> findViewById<RadioButton>(R.id.rbGlobalFps30).isChecked = true
            "rbGlobalFps60" -> findViewById<RadioButton>(R.id.rbGlobalFps60).isChecked = true
            "rbGlobalFps90" -> findViewById<RadioButton>(R.id.rbGlobalFps90).isChecked = true
            "rbGlobalFps120" -> findViewById<RadioButton>(R.id.rbGlobalFps120).isChecked = true
            else -> findViewById<RadioButton>(R.id.rbGlobalFpsAuto).isChecked = true
        }

        rgModes.setOnCheckedChangeListener { _, checkedId ->
            val modeKey = when (checkedId) {
                R.id.rbPowerSaver -> "rbPowerSaver"
                R.id.rbPerformance -> "rbPerformance"
                R.id.rbAutomatic -> "rbAutomatic"
                else -> "rbBalance"
            }
            layoutFocusSettings.visibility = if (modeKey == "rbAutomatic") View.VISIBLE else View.GONE
            prefs.edit().putString("selected_mode", modeKey).apply()
            
            if (modeKey == "rbAutomatic") {
                // Trigger AI logic immediately
                startService(Intent(this, AutoTweakService::class.java))
            } else {
                // Clear AI label when switching to manual
                prefs.edit().remove("active_ai_label").apply()
                
                // Immediate Application
                val displayMode = when(modeKey) {
                    "rbPowerSaver" -> "Power Saver"
                    "rbPerformance" -> "Performance"
                    else -> "Balance"
                }
                kotlin.concurrent.thread {
                    TweakManager.applyGlobalMode(displayMode)
                }
            }
        }

        rgFocus.setOnCheckedChangeListener { _, checkedId ->
            val focusKey = when (checkedId) {
                R.id.rbFocusBattery -> "rbFocusBattery"
                R.id.rbFocusMultitasking -> "rbFocusMultitasking"
                else -> "rbFocusDaily"
            }
            prefs.edit().putString("selected_focus", focusKey).apply()
        }

        rgGlobalFps.setOnCheckedChangeListener { _, checkedId ->
            val fpsKey = when (checkedId) {
                R.id.rbGlobalFps30 -> "rbGlobalFps30"
                R.id.rbGlobalFps60 -> "rbGlobalFps60"
                R.id.rbGlobalFps90 -> "rbGlobalFps90"
                R.id.rbGlobalFps120 -> "rbGlobalFps120"
                else -> "rbGlobalFpsAuto"
            }
            prefs.edit().putString("selected_global_fps", fpsKey).apply()
        }
    }
}
