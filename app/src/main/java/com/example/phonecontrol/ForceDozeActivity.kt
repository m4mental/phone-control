package com.example.phonecontrol

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

class ForceDozeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_force_doze)

        findViewById<MaterialToolbar>(R.id.toolbarForceDoze).setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val swForceDoze = findViewById<SwitchMaterial>(R.id.switchForceDoze)
        val swSkipLight = findViewById<SwitchMaterial>(R.id.switchSkipLightDoze)

        swForceDoze.isChecked = prefs.getBoolean("force_doze_enabled", false)
        swSkipLight.isChecked = prefs.getBoolean("force_doze_skip_light", false)

        swForceDoze.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("force_doze_enabled", isChecked).apply()
            prefs.edit().putBoolean("batt_force_doze_enabled", isChecked).apply()
            Toast.makeText(this, if (isChecked) "Instant Force Doze Enabled" else "Force Doze Disabled", Toast.LENGTH_SHORT).show()
        }

        swSkipLight.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("force_doze_skip_light", isChecked).apply()
            Toast.makeText(this, if (isChecked) "Light Doze Skip Enabled" else "Light Doze Skip Disabled", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.cardWhitelistForceDoze).setOnClickListener {
            startActivity(Intent(this, DozeWhitelistActivity::class.java))
        }

        findViewById<Button>(R.id.btnTestForceDozeNow).setOnClickListener {
            thread {
                ShellUtils.fastCmd("dumpsys deviceidle force-idle")
                runOnUiThread {
                    Toast.makeText(this, "OS Force-Doze Activated", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
