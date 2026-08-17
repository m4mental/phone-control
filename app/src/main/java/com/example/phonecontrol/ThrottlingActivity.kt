package com.example.phonecontrol

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial

class ThrottlingActivity : AppCompatActivity() {

    private lateinit var btnCooldown: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_throttling)

        findViewById<MaterialToolbar>(R.id.toolbarThrottling).setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val switchDisable = findViewById<SwitchMaterial>(R.id.switchDisableThrottling)
        val switchIgnoreCharging = findViewById<SwitchMaterial>(R.id.switchIgnoreCharging)
        val seekbarFuse = findViewById<SeekBar>(R.id.seekbarTempFuse)
        val tvFuseValue = findViewById<TextView>(R.id.tvFuseValue)
        
        val switchAuto = findViewById<SwitchMaterial>(R.id.switchAutoCooldown)
        val layoutAutoSeek = findViewById<View>(R.id.layoutAutoTriggerSeek)
        val seekbarAuto = findViewById<SeekBar>(R.id.seekbarAutoTrigger)
        val tvAutoValue = findViewById<TextView>(R.id.tvAutoTriggerValue)
        
        btnCooldown = findViewById(R.id.btnCooldown)

        // Load Throttling Prefs
        switchDisable.isChecked = prefs.getBoolean("disable_throttling", false)
        switchIgnoreCharging.isChecked = prefs.getBoolean("ignore_charging", false)
        val savedFuse = prefs.getInt("temp_fuse", 45)
        seekbarFuse.progress = savedFuse - 40
        tvFuseValue.text = "${savedFuse}°C"

        // Load Auto Trigger Prefs
        switchAuto.isChecked = prefs.getBoolean("auto_cooldown_enabled", false)
        layoutAutoSeek.visibility = if (switchAuto.isChecked) View.VISIBLE else View.GONE
        val savedAutoTrigger = prefs.getInt("auto_cooldown_threshold", 50)
        seekbarAuto.progress = savedAutoTrigger - 40
        tvAutoValue.text = "${savedAutoTrigger}°C"

        switchDisable.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                showRiskWarning {
                    prefs.edit().putBoolean("disable_throttling", true).apply()
                    ThermalManager.setThrottlingEnabled(false)
                }
            } else {
                prefs.edit().putBoolean("disable_throttling", false).apply()
                ThermalManager.setThrottlingEnabled(true)
            }
        }

        switchAuto.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_cooldown_enabled", isChecked).apply()
            layoutAutoSeek.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        switchIgnoreCharging.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("ignore_charging", isChecked).apply()
        }

        seekbarFuse.setOnSeekBarChangeListener(createTempListener(tvFuseValue, "temp_fuse", 40))
        seekbarAuto.setOnSeekBarChangeListener(createTempListener(tvAutoValue, "auto_cooldown_threshold", 40))

        btnCooldown.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Activate Cooldown?")
                .setMessage("Device will cool down for 120 seconds.")
                .setPositiveButton("Activate") { _, _ ->
                    startManualCooldownUI()
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun createTempListener(tv: TextView, prefKey: String, min: Int) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
            val t = min + p
            tv.text = "${t}°C"
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putInt(prefKey, t).apply()
        }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun startManualCooldownUI() {
        btnCooldown.isEnabled = false
        btnCooldown.text = "COOLDOWN ACTIVE (120s)"
        ThermalManager.startEmergencyCooldown(this) {
            btnCooldown.isEnabled = true
            btnCooldown.text = "ACTIVATE COOLDOWN"
            Toast.makeText(this, "Cooldown Finished.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRiskWarning(onAccept: () -> Unit) {
        AlertDialog.Builder(this).setTitle("High Risk").setMessage("Disabling throttling can damage hardware.")
            .setPositiveButton("I Understand", { _, _ -> onAccept() })
            .setNegativeButton("Cancel", { _, _ -> findViewById<SwitchMaterial>(R.id.switchDisableThrottling).isChecked = false })
            .setCancelable(false).show()
    }
}
