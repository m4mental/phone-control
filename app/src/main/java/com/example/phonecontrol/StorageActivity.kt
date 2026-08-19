package com.example.phonecontrol

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

class StorageActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage)

        findViewById<MaterialToolbar>(R.id.toolbarStorage).setNavigationOnClickListener { finish() }

        tvStatus = findViewById(R.id.tvStorageStatus)
        tvLog = findViewById(R.id.tvStorageLog)
        
        val swBoost = findViewById<SwitchMaterial>(R.id.switchStorageBoost)
        val btnTrim = findViewById<Button>(R.id.btnRunFsTrim)
        val btnVacuum = findViewById<Button>(R.id.btnVacuumDbs)

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("storage_boost_enabled", false)
        swBoost.isChecked = isEnabled

        swBoost.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("storage_boost_enabled", isChecked).apply()
            thread {
                StorageManager.applyStorageBoost(isChecked)
                runOnUiThread {
                    Toast.makeText(this, if (isChecked) "Storage Boost Enabled" else "Storage Boost Disabled", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnTrim.setOnClickListener {
            tvLog.text = "Running FSTRIM... Please wait."
            thread {
                val output = StorageManager.runFsTrim()
                runOnUiThread {
                    tvLog.text = "FSTRIM Output:\n$output"
                    Toast.makeText(this, "FSTRIM Completed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnVacuum.setOnClickListener {
            tvLog.text = "Starting Database Optimization..."
            thread {
                val count = StorageManager.vacuumDatabases { msg ->
                    runOnUiThread { tvLog.append("\n$msg") }
                }
                runOnUiThread {
                    tvLog.append("\n\nDone! Optimized $count databases.")
                    Toast.makeText(this, "Database Optimization Completed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
