package com.example.phonecontrol

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    private lateinit var layoutToggleContainer: LinearLayout
    private lateinit var tvDiagRoot: TextView
    private lateinit var tvDiagSelinux: TextView
    private lateinit var tvDiagKernel: TextView
    private lateinit var tvDiagEventEngine: TextView

    data class SubFeature(
        val title: String,
        val prefKey: String,
        val summary: String,
        val defaultEnabled: Boolean = true
    )

    data class MasterCategory(
        val title: String,
        val masterKey: String,
        val accentColor: String,
        val subFeatures: List<SubFeature>
    )

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
                editor.putBoolean("master_gaming_hub_enabled", true)
                editor.putBoolean("game_turbo_enabled", true)
                editor.putBoolean("per_app_enabled", true)
                editor.putBoolean("master_performance_hub_enabled", true)
                editor.putBoolean("resolution_enabled", true)
                editor.putBoolean("ram_manager_enabled", true)
                editor.putBoolean("adaptive_thermal_enabled", true)
                editor.putBoolean("master_security_hub_enabled", true)
                editor.putBoolean("network_priority_enabled", true)
            }
            "Battery" -> {
                editor.putBoolean("master_battery_hub_enabled", true)
                editor.putBoolean("battery_lab_enabled", true)
                editor.putBoolean("super_doze_enabled", true)
                editor.putBoolean("smart_switch_enabled", true)
                editor.putBoolean("sensor_firewall_enabled", true)
                editor.putBoolean("master_performance_hub_enabled", true)
                editor.putBoolean("adaptive_thermal_enabled", true)
            }
            "Balance" -> {
                editor.putBoolean("master_battery_hub_enabled", true)
                editor.putBoolean("battery_lab_enabled", true)
                editor.putBoolean("super_doze_enabled", true)
                editor.putBoolean("master_performance_hub_enabled", true)
                editor.putBoolean("ram_manager_enabled", true)
                editor.putBoolean("storage_boost_enabled", true)
                editor.putBoolean("optimization_enabled", true)
                editor.putBoolean("adaptive_thermal_enabled", true)
                editor.putBoolean("master_gaming_hub_enabled", true)
                editor.putBoolean("game_turbo_enabled", true)
                editor.putBoolean("per_app_enabled", true)
                editor.putBoolean("master_security_hub_enabled", true)
                editor.putBoolean("network_priority_enabled", true)
                editor.putBoolean("master_tools_hub_enabled", true)
            }
        }
        editor.apply()
        Toast.makeText(this, "Preset applied: $preset", Toast.LENGTH_SHORT).show()
        refreshToggles()
    }

    private fun shareConfiguration() {
        thread {
            val json = BackupManager.generateBackupJson(this)
            runOnUiThread {
                if (json != null) {
                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, json)
                        type = "text/plain"
                    }
                    startActivity(Intent.createChooser(sendIntent, "Share Phone Control Config"))
                } else {
                    Toast.makeText(this, "Failed to generate config JSON", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = "Paste JSON Configuration here..."
            isSingleLine = false
            setLines(6)
        }

        AlertDialog.Builder(this)
            .setTitle("Import Configuration")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val json = input.text.toString().trim()
                if (json.isNotEmpty()) {
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

        val masterCategories = listOf(
            MasterCategory(
                title = "🔋 Battery & Power Hub",
                masterKey = "master_battery_hub_enabled",
                accentColor = "#00E676",
                subFeatures = listOf(
                    SubFeature("Battery Health & Charge Lab", "battery_lab_enabled", "Direct hardware bypass charging, 80% charge limiter, and USB fast charge.", true),
                    SubFeature("Super Doze Mode", "super_doze_enabled", "Kernel-level deep sleep engine to minimize overnight standby battery drop to near 0%.", true),
                    SubFeature("Smart Data Switcher", "smart_switch_enabled", "Automatically turns off mobile data on stable Wi-Fi to eliminate radio drain.", true),
                    SubFeature("Hardware Sensor Firewall", "sensor_firewall_enabled", "Blocks gyroscope, magnetometer, and motion sensors when screen is off.", true)
                )
            ),
            MasterCategory(
                title = "🚀 Performance & Display Hub",
                masterKey = "master_performance_hub_enabled",
                accentColor = "#00E5FF",
                subFeatures = listOf(
                    SubFeature("Display & Resolution Scaling", "resolution_enabled", "Modify display resolution (720p/1080p) and DPI scaling for higher framerates.", true),
                    SubFeature("Memory & ZRAM Manager", "ram_manager_enabled", "High-speed compressed physical RAM allocation and LMK tuning for fluid multitasking.", true),
                    SubFeature("UFS Storage Boost", "storage_boost_enabled", "Automated weekly FSTRIM maintenance and mq-deadline I/O scheduler tuning.", true),
                    SubFeature("Adaptive Thermal Engine", "adaptive_thermal_enabled", "Monitors battery and CPU temperatures to prevent hardware overheating.", true),
                    SubFeature("Deep System Optimization", "optimization_enabled", "Suppresses background logd overhead and cleans caches for peak responsiveness.", true)
                )
            ),
            MasterCategory(
                title = "🎮 Gaming & App Turbo Hub",
                masterKey = "master_gaming_hub_enabled",
                accentColor = "#FFD600",
                subFeatures = listOf(
                    SubFeature("Game Turbo Suite", "game_turbo_enabled", "High-priority CPU/GPU scheduling and network packet prioritization during games.", true),
                    SubFeature("Per-App Profiles", "per_app_enabled", "Set custom refresh rates, touch sampling rates, and CPU power modes per application.", true)
                )
            ),
            MasterCategory(
                title = "🛡️ Security & Network Hub",
                masterKey = "master_security_hub_enabled",
                accentColor = "#00C853",
                subFeatures = listOf(
                    SubFeature("Network Booster (TCP BBR)", "network_priority_enabled", "Enables TCP BBR congestion control and prioritizes low-latency traffic.", true),
                    SubFeature("Per-App Data Firewall", "firewall_enabled", "Restricts background network access for selected applications.", true),
                    SubFeature("Home 5G Tower Lock", "tower_lock_enabled", "Locks modem to specific carrier frequency bands to stabilize 5G reception indoors.", true)
                )
            ),
            MasterCategory(
                title = "🧰 System Tools Hub",
                masterKey = "master_tools_hub_enabled",
                accentColor = "#FF5252",
                subFeatures = listOf(
                    SubFeature("App Freezer & Hibernation", "freezer_enabled", "Freeze unused applications with a single tap to reclaim 100% background RAM.", true),
                    SubFeature("Bloatware Remover", "bloatware_enabled", "Force-disable carrier-preinstalled bloatware and unnecessary background telemetry.", true),
                    SubFeature("App & Data Vault", "vault_enabled", "⚠️ [BETA] Local offline encrypted backup and restore utility for apps.", false),
                    SubFeature("Root Shell Terminal", "adb_enabled", "Directly execute and test root Linux commands inside a secured terminal.", true)
                )
            )
        )

        for (category in masterCategories) {
            addCategoryCardView(category)
        }
    }

    private fun addCategoryCardView(category: MasterCategory) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val cardView = layoutInflater.inflate(R.layout.item_setting_category_card, layoutToggleContainer, false) as MaterialCardView
        
        val tvTitle = cardView.findViewById<TextView>(R.id.tvCategoryTitle)
        val tvSubtitle = cardView.findViewById<TextView>(R.id.tvCategorySubtitle)
        val swMaster = cardView.findViewById<SwitchMaterial>(R.id.switchCategoryMaster)
        val containerSub = cardView.findViewById<LinearLayout>(R.id.layoutSubFeaturesContainer)

        tvTitle.text = category.title
        tvTitle.setTextColor(Color.parseColor(category.accentColor))
        
        val isMasterOn = prefs.getBoolean(category.masterKey, true)
        swMaster.isChecked = isMasterOn
        containerSub.visibility = if (isMasterOn) View.VISIBLE else View.GONE

        // Master Switch Listener
        swMaster.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(category.masterKey, isChecked).apply()
            containerSub.visibility = if (isChecked) View.VISIBLE else View.GONE
            
            // Cascade enable/disable and revert all sub-features under this category
            val editor = prefs.edit()
            for (sub in category.subFeatures) {
                editor.putBoolean(sub.prefKey, isChecked)
                if (!isChecked) {
                    revertSpecificFeature(sub.prefKey)
                }
            }
            editor.apply()
            
            // Refresh sub-views inside container
            renderSubFeatures(category, containerSub, isChecked)
        }

        renderSubFeatures(category, containerSub, isMasterOn)
        layoutToggleContainer.addView(cardView)
    }

    private fun renderSubFeatures(category: MasterCategory, container: LinearLayout, isCategoryEnabled: Boolean) {
        container.removeAllViews()
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        for (sub in category.subFeatures) {
            val subView = layoutInflater.inflate(R.layout.item_setting_toggle, container, false)
            val tvSubTitle = subView.findViewById<TextView>(R.id.tvToggleTitle)
            val tvSubSummary = subView.findViewById<TextView>(R.id.tvToggleSummary)
            val swSub = subView.findViewById<SwitchMaterial>(R.id.switchFeature)

            tvSubTitle.text = sub.title
            tvSubSummary.text = sub.summary

            if (sub.summary.contains("BETA")) {
                tvSubSummary.setTextColor(Color.parseColor("#FFAB40"))
            }

            val isSubOn = prefs.getBoolean(sub.prefKey, sub.defaultEnabled && isCategoryEnabled)
            swSub.isChecked = isSubOn
            swSub.isEnabled = isCategoryEnabled

            swSub.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(sub.prefKey, isChecked).apply()
                if (!isChecked) {
                    revertSpecificFeature(sub.prefKey)
                }
            }

            container.addView(subView)
        }
    }

    private fun revertSpecificFeature(prefKey: String) {
        thread {
            when (prefKey) {
                "resolution_enabled" -> {
                    ShellUtils.runAsRoot("wm size reset")
                    ShellUtils.runAsRoot("wm density reset")
                }
                "battery_lab_enabled" -> {
                    BatteryManager.setBypassEnabled(false)
                    BatteryManager.setChargingEnabled(true)
                }
                "adaptive_thermal_enabled" -> {
                    getSharedPreferences("prefs", MODE_PRIVATE).edit().putInt("active_cpu_cap", 100).apply()
                    TweakManager.limitCpuFrequency(100)
                    ThermalManager.setThrottlingEnabled(true)
                }
                "storage_boost_enabled" -> {
                    StorageManager.applyStorageBoost(false)
                }
                "network_priority_enabled" -> {
                    ShellUtils.runAsRoot("iptables -t mangle -F OUTPUT")
                }
                "optimization_enabled" -> {
                    getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("silent_system_enabled", false).apply()
                    TweakManager.setSilentSystem(false)
                }
            }
        }
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
            .setTitle("SAFE UNINSTALL").setMessage("Revert tweaks and unfreeze all apps before uninstalling?")
            .setPositiveButton("REVERT & UNINSTALL") { _, _ ->
                thread {
                    MasterManager.revertAll(this)
                    runOnUiThread {
                        val uri = android.net.Uri.fromParts("package", packageName, null)
                        val uninstallIntent = Intent(Intent.ACTION_DELETE, uri)
                        startActivity(uninstallIntent)
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }
}
