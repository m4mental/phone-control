package com.example.phonecontrol

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.*

class BatteryActivity : AppCompatActivity() {

    private lateinit var tvVolt: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvHealth: TextView
    private lateinit var tvCycles: TextView
    private lateinit var tvWattage: TextView
    private lateinit var tvWear: TextView
    private var timer: Timer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_battery)

        tvVolt = findViewById(R.id.tvVolt)
        tvTemp = findViewById(R.id.tvTemp)
        tvHealth = findViewById(R.id.tvHealth)
        tvCycles = findViewById(R.id.tvCycles)
        tvWattage = findViewById(R.id.tvWattage)
        tvWear = findViewById(R.id.tvWear)
        
        findViewById<MaterialToolbar>(R.id.toolbarBattery).setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        
        val swPowerSave = findViewById<SwitchMaterial>(R.id.switchPowerSave)
        val swLimit = findViewById<SwitchMaterial>(R.id.switchLimitCharge)
        val swBypass = findViewById<SwitchMaterial>(R.id.switchBypass)
        val swSensorFirewall = findViewById<SwitchMaterial>(R.id.switchSensorFirewall)
        val swUsbFastCharge = findViewById<SwitchMaterial>(R.id.switchUsbFastCharge)

        val swBlockGyro = findViewById<SwitchMaterial>(R.id.switchBlockGyro)
        val swBlockMag = findViewById<SwitchMaterial>(R.id.switchBlockMag)
        
        val layoutLimit = findViewById<View>(R.id.layoutLimitSeek)
        val seekbarLimit = findViewById<SeekBar>(R.id.seekbarLimit)
        val tvLimitValue = findViewById<TextView>(R.id.tvLimitValue)

        // Super Doze Shortcut
        findViewById<View>(R.id.cardSuperDozeShortcut).setOnClickListener {
            startActivity(Intent(this, SuperDozeActivity::class.java))
        }

        // Load Prefs
        swPowerSave.isChecked = prefs.getBoolean("batt_power_save_screen_off", false)
        swLimit.isChecked = prefs.getBoolean("batt_limit_enabled", false)
        swBypass.isChecked = prefs.getBoolean("batt_bypass_enabled", false)
        swSensorFirewall.isChecked = prefs.getBoolean("sensor_firewall_enabled", false)
        swUsbFastCharge.isChecked = prefs.getBoolean("batt_usb_fast_charge", false)

        swBlockGyro.isChecked = prefs.getBoolean("block_gyro", false)
        swBlockMag.isChecked = prefs.getBoolean("block_mag", false)

        val savedLimit = prefs.getInt("batt_limit_value", 80)
        seekbarLimit.progress = savedLimit - 70
        tvLimitValue.text = "$savedLimit%"
        layoutLimit.visibility = if (swLimit.isChecked) View.VISIBLE else View.GONE

        // Listeners
        swPowerSave.setOnCheckedChangeListener { _, isC -> prefs.edit().putBoolean("batt_power_save_screen_off", isC).apply() }
        
        swLimit.setOnCheckedChangeListener { _, isC -> 
            prefs.edit().putBoolean("batt_limit_enabled", isC).apply()
            layoutLimit.visibility = if (isC) View.VISIBLE else View.GONE
            if (!isC) BatteryManager.setChargingEnabled(true)
        }
        
        swBypass.setOnCheckedChangeListener { _, isC -> 
            prefs.edit().putBoolean("batt_bypass_enabled", isC).apply()
            BatteryManager.setBypassEnabled(isC)
        }
        
        swSensorFirewall.setOnCheckedChangeListener { _, isC -> 
            prefs.edit().putBoolean("sensor_firewall_enabled", isC).apply()
        }
        
        swUsbFastCharge.setOnCheckedChangeListener { _, isC -> 
            prefs.edit().putBoolean("batt_usb_fast_charge", isC).apply()
            BatteryManager.setUsbFastCharge(isC)
        }

        swBlockGyro.setOnCheckedChangeListener { _, isC -> prefs.edit().putBoolean("block_gyro", isC).apply() }
        swBlockMag.setOnCheckedChangeListener { _, isC -> prefs.edit().putBoolean("block_mag", isC).apply() }

        seekbarLimit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                val v = 70 + p
                tvLimitValue.text = "$v%"
                prefs.edit().putInt("batt_limit_value", v).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        updateSubCardVisibility()
        startStatsUpdate()
    }

    override fun onResume() {
        super.onResume()
        updateSubCardVisibility()
    }

    private fun updateSubCardVisibility() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        findViewById<View>(R.id.cardSuperDozeShortcut).visibility = 
            if (prefs.getBoolean("super_doze_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardChargingProtection).visibility = 
            if (prefs.getBoolean("battery_lab_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tvChargingProtectionHeader).visibility = 
            if (prefs.getBoolean("battery_lab_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardSensorFirewall).visibility = 
            if (prefs.getBoolean("battery_lab_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tvSensorFirewallHeader).visibility = 
            if (prefs.getBoolean("battery_lab_enabled", true)) View.VISIBLE else View.GONE
    }

    private fun startStatsUpdate() {
        timer = Timer()
        timer?.schedule(object : TimerTask() {
            override fun run() {
                val info = BatteryManager.getBatteryStats()
                runOnUiThread {
                    tvVolt.text = info.voltage
                    tvTemp.text = info.temp
                    tvHealth.text = info.health
                    tvCycles.text = info.cycles
                    tvWattage.text = info.wattage
                    tvWear.text = info.wear
                }
            }
        }, 0, 3000)
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}
