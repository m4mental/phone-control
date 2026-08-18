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
        val swDataSaver = findViewById<SwitchMaterial>(R.id.switchDataSaver)
        val swLimit = findViewById<SwitchMaterial>(R.id.switchLimitCharge)
        val swBypass = findViewById<SwitchMaterial>(R.id.switchBypass)
        val swLowBatt = findViewById<SwitchMaterial>(R.id.switchAutoLowBatt)
        val swSensorFirewall = findViewById<SwitchMaterial>(R.id.switchSensorFirewall)
        
        val layoutLimit = findViewById<View>(R.id.layoutLimitSeek)
        val seekbarLimit = findViewById<SeekBar>(R.id.seekbarLimit)
        val tvLimitValue = findViewById<TextView>(R.id.tvLimitValue)
        
        val layoutLowBatt = findViewById<View>(R.id.layoutLowBattSeek)
        val seekbarLowBatt = findViewById<SeekBar>(R.id.seekbarLowBatt)
        val tvLowBattValue = findViewById<TextView>(R.id.tvLowBattValue)

        // Load Prefs
        swPowerSave.isChecked = prefs.getBoolean("batt_power_save_screen_off", false)
        swDataSaver.isChecked = prefs.getBoolean("batt_data_saver_screen_off", false)
        swLimit.isChecked = prefs.getBoolean("batt_limit_enabled", false)
        swBypass.isChecked = prefs.getBoolean("batt_bypass_enabled", false)
        swLowBatt.isChecked = prefs.getBoolean("batt_low_trigger_enabled", false)
        swSensorFirewall.isChecked = prefs.getBoolean("sensor_firewall_enabled", false)
        
        val savedLimit = prefs.getInt("batt_limit_value", 80)
        seekbarLimit.progress = savedLimit - 70
        tvLimitValue.text = "$savedLimit%"
        layoutLimit.visibility = if (swLimit.isChecked) View.VISIBLE else View.GONE

        val savedLowTrigger = prefs.getInt("batt_low_trigger_value", 20)
        seekbarLowBatt.progress = savedLowTrigger - 10
        tvLowBattValue.text = "$savedLowTrigger%"
        layoutLowBatt.visibility = if (swLowBatt.isChecked) View.VISIBLE else View.GONE

        // Listeners
        swPowerSave.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("batt_power_save_screen_off", isChecked).apply() }
        swDataSaver.setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("batt_data_saver_screen_off", isChecked).apply() }
        
        findViewById<View>(R.id.layoutForceDoze).setOnClickListener {
            startActivity(Intent(this, DozeWhitelistActivity::class.java))
        }

        swLimit.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("batt_limit_enabled", isChecked).apply()
            layoutLimit.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        swBypass.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("batt_bypass_enabled", isChecked).apply()
            BatteryManager.setBypassEnabled(isChecked)
        }

        swLowBatt.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("batt_low_trigger_enabled", isChecked).apply()
            layoutLowBatt.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        swSensorFirewall.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("sensor_firewall_enabled", isChecked).apply()
            if (!isChecked) SensorManager.setSensorsEnabled(true) // Ensure on if disabled
        }

        seekbarLimit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                val v = 70 + p
                tvLimitValue.text = "$v%"
                prefs.edit().putInt("batt_limit_value", v).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekbarLowBatt.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                val v = 10 + p
                tvLowBattValue.text = "$v%"
                prefs.edit().putInt("batt_low_trigger_value", v).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        startStatsUpdate()
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
