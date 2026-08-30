package com.example.phonecontrol

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class BatteryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_battery)

        findViewById<MaterialToolbar>(R.id.toolbarBattery).setNavigationOnClickListener { finish() }

        // Sub-feature Card 1: Force Doze Mode (OS Level)
        findViewById<View>(R.id.cardForceDoze).setOnClickListener {
            startActivity(Intent(this, ForceDozeActivity::class.java))
        }

        // Sub-feature Card 2: App Standby Buckets Guard (Process Level)
        findViewById<View>(R.id.cardStandbyGuard).setOnClickListener {
            startActivity(Intent(this, StandbyGuardActivity::class.java))
        }

        // Sub-feature Card 3: Super Doze Deep Sleep (Kernel Level)
        findViewById<View>(R.id.cardSuperDozeShortcut).setOnClickListener {
            startActivity(Intent(this, SuperDozeActivity::class.java))
        }

        // Sub-feature Card 4: Charging Protection & Bypass
        findViewById<View>(R.id.cardChargingProtection).setOnClickListener {
            startActivity(Intent(this, ChargingProtectionActivity::class.java))
        }

        // Sub-feature Card 5: Hardware Sensor Firewall
        findViewById<View>(R.id.cardSensorFirewall).setOnClickListener {
            startActivity(Intent(this, SensorFirewallActivity::class.java))
        }

        updateVisibility()
    }

    override fun onResume() {
        super.onResume()
        updateVisibility()
    }

    private fun updateVisibility() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        findViewById<View>(R.id.cardForceDoze).visibility =
            if (prefs.getBoolean("force_doze_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardStandbyGuard).visibility =
            if (prefs.getBoolean("standby_guard_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardSuperDozeShortcut).visibility =
            if (prefs.getBoolean("super_doze_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardChargingProtection).visibility =
            if (prefs.getBoolean("battery_lab_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardSensorFirewall).visibility =
            if (prefs.getBoolean("sensor_firewall_enabled", true)) View.VISIBLE else View.GONE
    }
}
