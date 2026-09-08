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
        val isEnabled = prefs.getBoolean("storage_boost_active", false)
        swBoost.isChecked = isEnabled

        swBoost.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("storage_boost_active", isChecked).apply()
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

        val btnCleanOrphaned = findViewById<Button>(R.id.btnCleanOrphanedResidue)
        btnCleanOrphaned.setOnClickListener {
            tvLog.text = "🔍 Scanning for uninstalled app residue in /sdcard/Android/obb and /sdcard/Android/data..."
            btnCleanOrphaned.isEnabled = false
            thread {
                val scanResult = StorageManager.scanOrphanedResidue(this) { progress ->
                    runOnUiThread { tvLog.append("\n$progress") }
                }

                if (scanResult.items.isEmpty()) {
                    runOnUiThread {
                        btnCleanOrphaned.isEnabled = true
                        tvLog.append("\n\n✅ Storage Clean! No ghost residue from uninstalled apps found.")
                        Toast.makeText(this, "No ghost residue found!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        tvLog.append("\n\n⚠️ Found ${scanResult.items.size} ghost folder(s) taking ${scanResult.totalReadable}!")
                        tvLog.append("\n🧹 Starting deep cleanup...")
                    }

                    val freed = StorageManager.cleanOrphanedResidue(scanResult.items) { progress ->
                        runOnUiThread { tvLog.append("\n$progress") }
                    }

                    runOnUiThread {
                        btnCleanOrphaned.isEnabled = true
                        tvLog.append("\n\n🎉 Successfully deleted ${scanResult.items.size} ghost folders! Recovered ${StorageManager.formatSize(freed)} of storage.")
                        Toast.makeText(this, "Cleaned ${StorageManager.formatSize(freed)} of ghost data!", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        val btnCleanChatJunk = findViewById<Button>(R.id.btnCleanChatJunk)
        btnCleanChatJunk.setOnClickListener {
            tvLog.text = "🔍 Scanning WhatsApp & Telegram for duplicate 'Sent' files & cache..."
            btnCleanChatJunk.isEnabled = false
            thread {
                val scanResult = StorageManager.scanChatJunk { progress ->
                    runOnUiThread { tvLog.append("\n$progress") }
                }

                if (scanResult.items.isEmpty()) {
                    runOnUiThread {
                        btnCleanChatJunk.isEnabled = true
                        tvLog.append("\n\n✅ Clean! No duplicate sent media or chat cache found.")
                        Toast.makeText(this, "Chat media is already clean!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        tvLog.append("\n\n⚠️ Found ${scanResult.totalFiles} redundant files taking ${scanResult.totalSizeReadable} across ${scanResult.items.size} categories!")
                        tvLog.append("\n🧹 Starting safe cleanup (received personal media is safe)...")
                    }

                    val freed = StorageManager.cleanChatJunk(scanResult.items) { progress ->
                        runOnUiThread { tvLog.append("\n$progress") }
                    }

                    runOnUiThread {
                        btnCleanChatJunk.isEnabled = true
                        tvLog.append("\n\n🎉 Cleanup Complete! Safely recovered ${StorageManager.formatSize(freed)} of storage.")
                        Toast.makeText(this, "Cleaned ${StorageManager.formatSize(freed)} of chat junk!", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
