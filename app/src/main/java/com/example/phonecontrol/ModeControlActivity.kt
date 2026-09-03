package com.example.phonecontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import kotlin.concurrent.thread

class ModeControlActivity : AppCompatActivity() {

    private lateinit var rgModes: RadioGroup
    private lateinit var rgFocus: RadioGroup
    private lateinit var layoutFocusSettings: LinearLayout
    private lateinit var tvCurrentRefreshSummary: TextView

    private val uiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateRadioButtonsFromPrefs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode_control)

        rgModes = findViewById(R.id.rgModes)
        rgFocus = findViewById(R.id.rgFocus)
        layoutFocusSettings = findViewById(R.id.layoutFocusSettings)
        tvCurrentRefreshSummary = findViewById(R.id.tvCurrentRefreshSummary)

        findViewById<MaterialToolbar>(R.id.toolbarModeControl).setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val savedFocus = prefs.getString("selected_focus", "rbFocusDaily")

        updateRadioButtonsFromPrefs()

        // Setup Focus
        when (savedFocus) {
            "rbFocusBattery" -> findViewById<RadioButton>(R.id.rbFocusBattery).isChecked = true
            "rbFocusMultitasking" -> findViewById<RadioButton>(R.id.rbFocusMultitasking).isChecked = true
            else -> findViewById<RadioButton>(R.id.rbFocusDaily).isChecked = true
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

            // Reset manual stage lock so selected mode takes full effect
            prefs.edit().putInt("manual_stage_override", 0).apply()
            TweakManager.manualStageOverride = 0

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
                thread {
                    TweakManager.applyGlobalMode(displayMode)
                    runOnUiThread { updateRefreshSummary() }
                }
            }

            // Sync with Notification Shade Quick Settings Tile
            ModeControlTileService.updateTile(this)
        }

        rgFocus.setOnCheckedChangeListener { _, checkedId ->
            val focusKey = when (checkedId) {
                R.id.rbFocusBattery -> "rbFocusBattery"
                R.id.rbFocusMultitasking -> "rbFocusMultitasking"
                else -> "rbFocusDaily"
            }
            prefs.edit().putString("selected_focus", focusKey).apply()
        }

        // Explicit Apply Mode Button
        findViewById<Button>(R.id.btnApplyMode).setOnClickListener {
            val checkedModeId = rgModes.checkedRadioButtonId
            val modeKey = when (checkedModeId) {
                R.id.rbPowerSaver -> "rbPowerSaver"
                R.id.rbPerformance -> "rbPerformance"
                R.id.rbAutomatic -> "rbAutomatic"
                else -> "rbBalance"
            }
            prefs.edit().putString("selected_mode", modeKey).apply()

            // Reset manual stage lock so full governor takes effect
            prefs.edit().putInt("manual_stage_override", 0).apply()
            TweakManager.manualStageOverride = 0

            thread {
                if (modeKey == "rbAutomatic") {
                    startService(Intent(this, AutoTweakService::class.java))
                } else {
                    prefs.edit().remove("active_ai_label").apply()
                    val displayMode = when (modeKey) {
                        "rbPowerSaver" -> "Power Saver"
                        "rbPerformance" -> "Performance"
                        else -> "Balance"
                    }
                    TweakManager.applyGlobalMode(displayMode)
                }

                runOnUiThread {
                    Toast.makeText(this, "⚡ Mode Applied Successfully", Toast.LENGTH_SHORT).show()
                    updateRefreshSummary()
                }
            }

            // Sync with Quick Settings Tile
            ModeControlTileService.updateTile(this)
        }

        // Clickable Shortcut directly to Custom Display Refresh Rate Activity
        findViewById<View>(R.id.cardDisplayRefreshShortcut).setOnClickListener {
            startActivity(Intent(this, RefreshRateActivity::class.java))
        }

        updateRefreshSummary()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.example.phonecontrol.UPDATE_UI")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(uiReceiver, filter)
        }
        updateRadioButtonsFromPrefs()
        updateRefreshSummary()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(uiReceiver)
        } catch (e: Exception) {}
    }

    private fun updateRadioButtonsFromPrefs() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val savedMode = prefs.getString("selected_mode", "rbBalance")

        when (savedMode) {
            "rbPowerSaver" -> findViewById<RadioButton>(R.id.rbPowerSaver).isChecked = true
            "rbBalance" -> findViewById<RadioButton>(R.id.rbBalance).isChecked = true
            "rbPerformance" -> findViewById<RadioButton>(R.id.rbPerformance).isChecked = true
            "rbAutomatic" -> findViewById<RadioButton>(R.id.rbAutomatic).isChecked = true
        }
        layoutFocusSettings.visibility = if (savedMode == "rbAutomatic") View.VISIBLE else View.GONE
    }

    private fun updateRefreshSummary() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val savedHz = prefs.getString("screen_refresh", "rbHzDynamic")
        val label = when (savedHz) {
            "rbHz120" -> "Locked at 120Hz (Ultra Smooth)"
            "rbHz90" -> "Locked at 90Hz (Balanced Smooth)"
            "rbHz60" -> "Locked at 60Hz (Battery Saver)"
            else -> "Dynamic / LTPO (Adaptive)"
        }
        tvCurrentRefreshSummary.text = "Current: $label"
    }
}
