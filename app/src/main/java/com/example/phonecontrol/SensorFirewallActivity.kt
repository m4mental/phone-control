package com.example.phonecontrol

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

class SensorFirewallActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sensor_firewall)

        findViewById<MaterialToolbar>(R.id.toolbarSensorFirewall).setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        // 5 Permanent Sensor Blockers
        val swNfc = findViewById<SwitchMaterial>(R.id.switchBlockNfc)
        val swGyro = findViewById<SwitchMaterial>(R.id.switchBlockGyro)
        val swMag = findViewById<SwitchMaterial>(R.id.switchBlockMag)
        val swMotion = findViewById<SwitchMaterial>(R.id.switchBlockMotion)
        val swLight = findViewById<SwitchMaterial>(R.id.switchBlockLight)

        // Smart Triggers
        val swKillSensors = findViewById<SwitchMaterial>(R.id.switchKillSensors)
        val swPrivacy = findViewById<SwitchMaterial>(R.id.switchPrivacySensors)

        // Bind initial states
        swNfc.isChecked = prefs.getBoolean("block_nfc", false)
        swGyro.isChecked = prefs.getBoolean("block_gyro", false)
        swMag.isChecked = prefs.getBoolean("block_mag", false)
        swMotion.isChecked = prefs.getBoolean("block_motion", false)
        swLight.isChecked = prefs.getBoolean("block_light", false)

        swKillSensors.isChecked = prefs.getBoolean("battery_kill_sensors", false)
        swPrivacy.isChecked = prefs.getBoolean("battery_privacy_sensors", false)

        // Listeners for 5 Permanent Sensor Blockers
        swNfc.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("block_nfc", isChecked).apply()
            thread { SensorManager.applySensorShield(this) }
            Toast.makeText(this, if (isChecked) "NFC Radio Chip Blocked" else "NFC Radio Enabled", Toast.LENGTH_SHORT).show()
        }

        swGyro.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("block_gyro", isChecked).apply()
            thread { SensorManager.applySensorShield(this) }
            Toast.makeText(this, if (isChecked) "Gyroscope Blocked (OFF)" else "Gyroscope Enabled", Toast.LENGTH_SHORT).show()
        }

        swMag.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("block_mag", isChecked).apply()
            thread { SensorManager.applySensorShield(this) }
            Toast.makeText(this, if (isChecked) "Compass/Magnetometer Blocked (OFF)" else "Compass Enabled", Toast.LENGTH_SHORT).show()
        }

        swMotion.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("block_motion", isChecked).apply()
            thread { SensorManager.applySensorShield(this) }
            Toast.makeText(this, if (isChecked) "Motion/Accelerometer Blocked (OFF)" else "Motion Sensor Enabled", Toast.LENGTH_SHORT).show()
        }

        swLight.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("block_light", isChecked).apply()
            thread { SensorManager.applySensorShield(this) }
            Toast.makeText(this, if (isChecked) "Light/Proximity Blocked (OFF)" else "Light Sensor Enabled", Toast.LENGTH_SHORT).show()
        }

        // Listeners for Smart Triggers
        swKillSensors.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("battery_kill_sensors", isChecked).apply()
            thread { BatteryManager.setKillSensorsScreenOff(this, isChecked) }
            Toast.makeText(this, if (isChecked) "Motion Sensors Kill on Screen Off Enabled" else "Motion Sensors Restored", Toast.LENGTH_SHORT).show()
        }

        swPrivacy.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("battery_privacy_sensors", isChecked).apply()
            thread { BatteryManager.setPrivacySensorsShield(this, isChecked) }
            Toast.makeText(this, if (isChecked) "Sensor Privacy Shield Active" else "Sensor Privacy Shield Disabled", Toast.LENGTH_SHORT).show()
        }
    }
}
