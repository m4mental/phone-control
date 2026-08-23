package com.example.phonecontrol

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread
import androidx.activity.result.contract.ActivityResultContracts
import java.io.OutputStream
import java.io.InputStream
import android.net.Uri

class SettingsActivity : AppCompatActivity() {

    private lateinit var layoutToggleContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Ensure storage structure is ready
        thread { BackupManager.ensureStorageStructure() }

        findViewById<MaterialToolbar>(R.id.toolbarSettings).setNavigationOnClickListener { finish() }
        layoutToggleContainer = findViewById(R.id.layoutToggleContainer)

        findViewById<Button>(R.id.btnKillSwitch).setOnClickListener {
            showKillSwitchDialog()
        }

        findViewById<Button>(R.id.btnSafeUninstall).setOnClickListener {
            showSafeUninstallDialog()
        }

        findViewById<Button>(R.id.btnBackup).setOnClickListener {
            thread {
                val success = BackupManager.saveBackupAuto(this)
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, "Backup Saved to /sdcard/PHONE_CONTROL/", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Backup Failed!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnRestore).setOnClickListener {
            thread {
                val success = BackupManager.restoreLatestAuto(this)
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, "Latest Backup Restored!", Toast.LENGTH_SHORT).show()
                        refreshToggles()
                    } else {
                        Toast.makeText(this, "No Backup Found!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        refreshToggles()
    }

    private fun refreshToggles() {
        layoutToggleContainer.removeAllViews()

        // Grouped Categories for Dashboard
        val featureList = listOf(
            Triple("Thermal Engine", "adaptive_thermal_enabled", "Adaptive Throttling and Cooling dashboard."),
            Triple("Network Booster", "network_priority_enabled", "Packet Guard and Gaming Network dashboard."),
            Triple("Storage Boost", "storage_boost_enabled", "UFS Refresh and I/O Tuning dashboard."),
            Triple("Display & Resolution", "resolution_enabled", "DPI, Resolution and Scaling dashboard."),
            Triple("Memory Manager", "ram_manager_enabled", "ZRAM and LMK tuning dashboard."),
            Triple("System Optimization", "optimization_enabled", "Deep Maintenance and Silent Mode dashboard."),
            Triple("Bloatware Remover", "bloatware_enabled", "Disable system junk apps card."),
            Triple("ADB Shell Terminal", "adb_enabled", "Root shell terminal access card."),
            Triple("App & Data Vault", "vault_enabled", "Advanced APK + Data backup dashboard."),
            Triple("Home Tower Lock", "tower_lock_enabled", "Indoor 5G stability for MediaTek devices."),
            Triple("Automation Service", "automation_enabled", "Standby Guard and GPS Auto-Saver logic.")
        )

        for (feature in featureList) {
            addToggleView(feature.first, feature.second, feature.third)
        }
    }

    private fun addToggleView(title: String, prefKey: String, summary: String) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val view = layoutInflater.inflate(R.layout.item_setting_toggle, layoutToggleContainer, false)
        
        val tvTitle = view.findViewById<TextView>(R.id.tvToggleTitle)
        val tvSummary = view.findViewById<TextView>(R.id.tvToggleSummary)
        val sw = view.findViewById<SwitchMaterial>(R.id.switchFeature)

        tvTitle.text = title
        tvSummary.text = summary
        sw.isChecked = prefs.getBoolean(prefKey, false)

        sw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(prefKey, isChecked).apply()
            
            // Immediate Logic & Cleanup for Category Toggles
            when (prefKey) {
                "storage_boost_enabled" -> thread { StorageManager.applyStorageBoost(isChecked) }
                
                "network_priority_enabled" -> {
                    if (!isChecked) thread { ShellUtils.runAsRoot("iptables -t mangle -F OUTPUT") }
                }
                
                "adaptive_thermal_enabled" -> {
                    if (!isChecked) {
                        prefs.edit().putInt("active_cpu_cap", 100).apply()
                        thread { TweakManager.limitCpuFrequency(100); ThermalManager.setThrottlingEnabled(true) }
                    }
                }
                
                "optimization_enabled" -> {
                    if (!isChecked) {
                        prefs.edit().putBoolean("silent_system_enabled", false).apply()
                        TweakManager.setSilentSystem(false)
                    }
                }

                "resolution_enabled" -> {
                    if (!isChecked) {
                        prefs.edit().putString("screen_res", "rbRes1080").apply()
                        TweakManager.setSystemResolution(false)
                    }
                }
            }
        }

        layoutToggleContainer.addView(view)
    }

    private fun showKillSwitchDialog() {
        AlertDialog.Builder(this)
            .setTitle("WARNING")
            .setMessage("This will revert ALL modifications and reset your settings. Phone might take a moment to adjust. Proceed?")
            .setPositiveButton("REVERT") { _, _ ->
                thread {
                    MasterManager.revertAll(this)
                    runOnUiThread {
                        Toast.makeText(this, "All modifications reverted!", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSafeUninstallDialog() {
        AlertDialog.Builder(this)
            .setTitle("Safe Uninstall")
            .setMessage("This will revert all system changes first and then uninstall the app. Recommended to avoid grey icons. Proceed?")
            .setPositiveButton("UNINSTALL") { _, _ ->
                thread {
                    MasterManager.revertAll(this)
                    runOnUiThread {
                        val intent = android.content.Intent(android.content.Intent.ACTION_DELETE)
                        intent.data = android.net.Uri.parse("package:${packageName}")
                        startActivity(intent)
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }
}
