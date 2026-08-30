package com.example.phonecontrol

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

class ChargingProtectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_charging_protection)

        findViewById<MaterialToolbar>(R.id.toolbarCharging).setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val switchLimit = findViewById<SwitchMaterial>(R.id.switchLimitCharge)
        val layoutLimitSeek = findViewById<View>(R.id.layoutLimitSeek)
        val seekbarLimit = findViewById<SeekBar>(R.id.seekbarLimit)
        val tvLimitValue = findViewById<TextView>(R.id.tvLimitValue)
        val switchBypass = findViewById<SwitchMaterial>(R.id.switchBypassCharging)
        val switchFast = findViewById<SwitchMaterial>(R.id.switchFastCharge)

        val isLimitEnabled = prefs.getBoolean("battery_limit_enabled", false)
        val limitPercent = prefs.getInt("battery_limit_percent", 80)
        switchLimit.isChecked = isLimitEnabled
        layoutLimitSeek.visibility = if (isLimitEnabled) View.VISIBLE else View.GONE
        seekbarLimit.progress = limitPercent - 70
        tvLimitValue.text = "$limitPercent%"

        switchLimit.setOnCheckedChangeListener { _, isChecked ->
            layoutLimitSeek.visibility = if (isChecked) View.VISIBLE else View.GONE
            prefs.edit().putBoolean("battery_limit_enabled", isChecked).apply()
            val target = seekbarLimit.progress + 70
            thread { BatteryManager.setChargingLimit(this, if (isChecked) target else 100) }
            Toast.makeText(this, if (isChecked) "Limit active at $target%" else "Charging limit disabled", Toast.LENGTH_SHORT).show()
        }

        seekbarLimit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val target = progress + 70
                tvLimitValue.text = "$target%"
                if (fromUser && switchLimit.isChecked) {
                    prefs.edit().putInt("battery_limit_percent", target).apply()
                    thread { BatteryManager.setChargingLimit(this@ChargingProtectionActivity, target) }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        switchBypass.isChecked = prefs.getBoolean("battery_bypass_charging", false)
        switchBypass.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("battery_bypass_charging", isChecked).apply()
            thread { BatteryManager.setBypassCharging(this, isChecked) }
            Toast.makeText(this, if (isChecked) "Bypass Charging Enabled" else "Bypass Charging Disabled", Toast.LENGTH_SHORT).show()
        }

        switchFast.isChecked = prefs.getBoolean("battery_fast_charge_boost", false)
        switchFast.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("battery_fast_charge_boost", isChecked).apply()
            thread { BatteryManager.setFastChargeBoost(this, isChecked) }
            Toast.makeText(this, if (isChecked) "Fast Charging Boost Enabled" else "Fast Charging Boost Disabled", Toast.LENGTH_SHORT).show()
        }
    }
}
