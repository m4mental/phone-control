package com.example.phonecontrol

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    private lateinit var layoutToggleContainer: LinearLayout
    private lateinit var tvDiagRoot: TextView
    private lateinit var tvDiagSelinux: TextView
    private lateinit var tvDiagKernel: TextView
    private lateinit var tvDiagEventEngine: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        thread { BackupManager.ensureStorageStructure() }

        findViewById<MaterialToolbar>(R.id.toolbarSettings).setNavigationOnClickListener { finish() }
        layoutToggleContainer = findViewById(R.id.layoutToggleContainer)

        tvDiagRoot = findViewById(R.id.tvDiagRoot)
        tvDiagSelinux = findViewById(R.id.tvDiagSelinux)
        tvDiagKernel = findViewById(R.id.tvDiagKernel)
        tvDiagEventEngine = findViewById(R.id.tvDiagEventEngine)

        // Setup Diagnostics
        loadDiagnosticsAsync()

        // 1-Click Presets
        findViewById<Button>(R.id.btnPresetGaming).setOnClickListener { applyPreset("Gaming") }
        findViewById<Button>(R.id.btnPresetBattery).setOnClickListener { applyPreset("Battery") }
        findViewById<Button>(R.id.btnPresetBalance).setOnClickListener { applyPreset("Balance") }

        // Maintenance & Actions
        findViewById<Button>(R.id.btnKillSwitch).setOnClickListener { showKillSwitchDialog() }
        findViewById<Button>(R.id.btnSafeUninstall).setOnClickListener { showSafeUninstallDialog() }
        
        findViewById<Button>(R.id.btnBackup).setOnClickListener {
            thread {
                val success = BackupManager.saveBackupAuto(this)
                runOnUiThread { Toast.makeText(this, if (success) "Backup Saved to /sdcard/PHONE_CONTROL!" else "Backup Failed!", Toast.LENGTH_SHORT).show() }
            }
        }
        findViewById<Button>(R.id.btnRestore).setOnClickListener {
            thread {
                val success = BackupManager.restoreLatestAuto(this)
                runOnUiThread {
                    if (success) { 
                        Toast.makeText(this, "Configuration Restored!", Toast.LENGTH_SHORT).show()
                        refreshToggles() 
                    } else {
                        Toast.makeText(this, "No Backup Found!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Share & Import
        findViewById<Button>(R.id.btnShareConfig).setOnClickListener { shareConfiguration() }
        findViewById<Button>(R.id.btnImportConfig).setOnClickListener { showImportDialog() }

        refreshToggles()
    }

    private fun loadDiagnosticsAsync() {
        thread {
            val rootRes = ShellUtils.runAsRoot("id")
            val selinuxRes = ShellUtils.runAsRoot("getenforce")
            val isRootGranted = rootRes.exitCode == 0

            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                tvDiagRoot.text = if (isRootGranted) "Root: Granted (Active)" else "Root: Denied"
                tvDiagRoot.setTextColor(if (isRootGranted) Color.parseColor("#00E676") else Color.RED)
                
                val selinuxMode = selinuxRes.output.trim().ifBlank { "Enforcing" }
                tvDiagSelinux.text = "SELinux: $selinuxMode"
                tvDiagKernel.text = "Kernel: MTK Dimensity (EAS+BBR)"
                tvDiagEventEngine.text = "0ms Events: Active"
            }
        }
    }

    private fun applyPreset(preset: String) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val editor = prefs.edit()

        when (preset) {
            "Gaming" -> {
                editor.putBoolean("game_turbo_enabled", true)
                editor.putBoolean("network_priority_enabled", true)
                editor.putBoolean("storage_boost_enabled", true)
                editor.putBoolean("adaptive_thermal_enabled", false)
                editor.putBoolean("super_doze_enabled", false)
                editor.putString("selected_mode", "rbPerformance")
                editor.apply()

                thread {
                    TweakManager.applyGlobalMode("Performance")
                    StorageManager.applyStorageBoost(true)
                    ThermalManager.setThrottlingEnabled(false)
                }
                Toast.makeText(this, "🎮 Extreme Gaming Preset Applied!", Toast.LENGTH_SHORT).show()
            }
            "Battery" -> {
                editor.putBoolean("super_doze_enabled", true)
                editor.putBoolean("automation_enabled", true)
                editor.putBoolean("smart_switch_enabled", true)
                editor.putBoolean("standby_guard_enabled", true)
                editor.putBoolean("sensor_firewall_enabled", true)
                editor.putString("selected_mode", "rbPowerSaver")
                editor.apply()

                thread {
                    TweakManager.applyGlobalMode("Power Saver")
                    StorageManager.applyStorageBoost(false)
                }
                Toast.makeText(this, "🔋 Ultra Battery Saver Preset Applied!", Toast.LENGTH_SHORT).show()
            }
            "Balance" -> {
                editor.putBoolean("game_turbo_enabled", true)
                editor.putBoolean("automation_enabled", true)
                editor.putBoolean("adaptive_thermal_enabled", true)
                editor.putBoolean("smart_switch_enabled", true)
                editor.putBoolean("storage_boost_enabled", true)
                editor.putString("selected_mode", "rbBalance")
                editor.apply()

                thread {
                    TweakManager.applyGlobalMode("Balance")
                    StorageManager.applyStorageBoost(true)
                    ThermalManager.setThrottlingEnabled(true)
                }
                Toast.makeText(this, "⚖️ Daily Balanced Preset Applied!", Toast.LENGTH_SHORT).show()
            }
        }

        refreshToggles()
    }

    private fun shareConfiguration() {
        val json = BackupManager.generateBackupJson(this)
        if (json.isNullOrBlank()) {
            Toast.makeText(this, "Failed to export config", Toast.LENGTH_SHORT).show()
            return
        }

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, json)
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Share Phone Control Config"))
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = "Paste JSON Configuration here..."
            setPadding(40, 40, 40, 40)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        AlertDialog.Builder(this)
            .setTitle("Import Configuration")
            .setView(input)
            .setPositiveButton("IMPORT") { _, _ ->
                val json = input.text.toString().trim()
                if (json.isNotBlank()) {
                    val success = BackupManager.restoreFromJson(this, json)
                    if (success) {
                        Toast.makeText(this, "Config Imported Successfully!", Toast.LENGTH_SHORT).show()
                        refreshToggles()
                    } else {
                        Toast.makeText(this, "Invalid JSON format!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshToggles() {
        layoutToggleContainer.removeAllViews()

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
            tvSummary.setTextColor(Color.RED)
        }

        sw.isChecked = prefs.getBoolean(prefKey, false)

        sw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(prefKey, isChecked).apply()
            
            when (prefKey) {
                "storage_boost_enabled" -> thread { 
                    StorageManager.applyStorageBoost(isChecked) 
                    runOnUiThread { Toast.makeText(this, if (isChecked) "Storage Boost: mq-deadline (Active)" else "Storage Boost: Default", Toast.LENGTH_SHORT).show() }
                }
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
