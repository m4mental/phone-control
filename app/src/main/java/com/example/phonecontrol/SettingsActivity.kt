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
import android.content.Intent

class SettingsActivity : AppCompatActivity() {

    private lateinit var layoutToggleContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        thread { BackupManager.ensureStorageStructure() }

        findViewById<MaterialToolbar>(R.id.toolbarSettings).setNavigationOnClickListener { finish() }
        layoutToggleContainer = findViewById(R.id.layoutToggleContainer)

        findViewById<Button>(R.id.btnKillSwitch).setOnClickListener { showKillSwitchDialog() }
        findViewById<Button>(R.id.btnSafeUninstall).setOnClickListener { showSafeUninstallDialog() }
        findViewById<Button>(R.id.btnBackup).setOnClickListener {
            thread {
                val success = BackupManager.saveBackupAuto(this)
                runOnUiThread { Toast.makeText(this, if (success) "Backup Saved!" else "Backup Failed!", Toast.LENGTH_SHORT).show() }
            }
        }
        findViewById<Button>(R.id.btnRestore).setOnClickListener {
            thread {
                val success = BackupManager.restoreLatestAuto(this)
                runOnUiThread {
                    if (success) { Toast.makeText(this, "Restored!", Toast.LENGTH_SHORT).show(); refreshToggles() }
                }
            }
        }

        refreshToggles()
    }

    private fun refreshToggles() {
        layoutToggleContainer.removeAllViews()

        // Grouped Categories for Dashboard with detailed technical notes
        val featureList = listOf(
            Triple("Thermal Engine", "adaptive_thermal_enabled", "Dynamic cooling: Caps CPU and Parks Big Cores at 45°C+ to prevent overheating."),
            Triple("Network Booster", "network_priority_enabled", "Internet speed: Forces TCP BBR and prioritizing gaming packets for stable ping."),
            Triple("Storage Boost", "storage_boost_enabled", "UFS Refresh: Weekly automated FSTRIM and mq-deadline tuning for zero-lag storage."),
            Triple("Display & Resolution", "resolution_enabled", "Visual tuning: Modify DPI and native resolution to boost GPU performance."),
            Triple("Memory Manager", "ram_manager_enabled", "ZRAM Power: Compressed Physical RAM for zero-lag switching (Better than slow Virtual RAM)."),
            Triple("System Optimization", "optimization_enabled", "Silence logs: Suppresses logd and background maintenance for peak CPU focus."),
            Triple("Bloatware Remover", "bloatware_enabled", "Free storage: Force-disable factory junk and carrier-preinstalled system apps."),
            Triple("ADB Shell Terminal", "adb_enabled", "Expert console: Directly execute root shell commands inside a secured terminal."),
            Triple("App & Data Vault", "vault_enabled", "⚠️ [BETA] STILL UNDER DEVELOPMENT: High risk of crashes during backup/restore. Use with caution."),
            Triple("Home Tower Lock", "tower_lock_enabled", "Modem control: Hard-lock specific PCI/EARFCN to keep 5G stable indoors."),
            Triple("Smart Data Switcher", "smart_switch_enabled", "Smart Switch: Auto-OFF mobile data on stable WiFi and Auto-ON when out (Zero Polling)."),
            Triple("Game Turbo Suite", "game_turbo_enabled", "Gaming: Auto-Performance and Ping Guard when games are launched."),
            Triple("Super Doze Mode", "super_doze_enabled", "Standby: Kernel-level Deep Sleep to achieve 0% battery drop overnight."),
            Triple("Automation Service", "automation_enabled", "Battery Pro: Auto-Parks big cores and blocks sensors during Screen-OFF standby.")
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
        
        if (summary.contains("UNDER DEVELOPMENT")) {
            tvSummary.setTextColor(android.graphics.Color.RED)
        }

        sw.isChecked = prefs.getBoolean(prefKey, false)

        sw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(prefKey, isChecked).apply()
            
            when (prefKey) {
                "storage_boost_enabled" -> thread { StorageManager.applyStorageBoost(isChecked) }
                "network_priority_enabled" -> if (!isChecked) thread { ShellUtils.runAsRoot("iptables -t mangle -F OUTPUT") }
                "adaptive_thermal_enabled" -> if (!isChecked) {
                    prefs.edit().putInt("active_cpu_cap", 100).apply()
                    thread { TweakManager.limitCpuFrequency(100); ThermalManager.setThrottlingEnabled(true) }
                }
                "optimization_enabled" -> if (!isChecked) {
                    prefs.edit().putBoolean("silent_system_enabled", false).apply()
                    TweakManager.setSilentSystem(false)
                }
                "automation_enabled" -> {
                    // Ensure the background service is aware of the change
                    startService(Intent(this, AutoTweakService::class.java))
                }
            }
        }
        layoutToggleContainer.addView(view)
    }

    private fun showKillSwitchDialog() {
        AlertDialog.Builder(this)
            .setTitle("WARNING").setMessage("This will revert ALL system modifications and reset settings to default. Proceed?")
            .setPositiveButton("REVERT") { _, _ ->
                thread { 
                    MasterManager.revertAll(this)
                    runOnUiThread { 
                        Toast.makeText(this, "System Cleaned & Reset!", Toast.LENGTH_LONG).show()
                        finish() 
                    } 
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showSafeUninstallDialog() {
        AlertDialog.Builder(this)
            .setTitle("Safe Uninstall").setMessage("Revert all changes and uninstall app?").setPositiveButton("UNINSTALL") { _, _ ->
                thread { MasterManager.revertAll(this); runOnUiThread {
                    val intent = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${packageName}"))
                    startActivity(intent)
                } }
            }.setNegativeButton("Cancel", null).show()
    }
}
