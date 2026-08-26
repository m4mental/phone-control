package com.example.phonecontrol

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial

class SuperDozeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_super_doze)

        findViewById<MaterialToolbar>(R.id.toolbarSuperDoze).setNavigationOnClickListener { finish() }

        setupToggles()

        findViewById<Button>(R.id.btnForceDozeNow).setOnClickListener {
            ShellUtils.fastCmd("dumpsys deviceidle force-idle deep")
            Toast.makeText(this, "Device forced into Deep Sleep", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupToggles() {
        val prefs = getSharedPreferences("super_doze_prefs", MODE_PRIVATE)
        val swParking = findViewById<SwitchMaterial>(R.id.switchDeepParking)
        val swRadio = findViewById<SwitchMaterial>(R.id.switchRadioOff)
        val swSync = findViewById<SwitchMaterial>(R.id.switchSyncOff)

        swParking.isChecked = prefs.getBoolean("deep_parking_enabled", true)
        swRadio.isChecked = prefs.getBoolean("radio_off_enabled", false)
        swSync.isChecked = prefs.getBoolean("sync_off_enabled", true)

        swParking.setOnCheckedChangeListener { _, isC -> prefs.edit().putBoolean("deep_parking_enabled", isC).apply() }
        swRadio.setOnCheckedChangeListener { _, isC -> prefs.edit().putBoolean("radio_off_enabled", isC).apply() }
        swSync.setOnCheckedChangeListener { _, isC -> prefs.edit().putBoolean("sync_off_enabled", isC).apply() }
    }
}
