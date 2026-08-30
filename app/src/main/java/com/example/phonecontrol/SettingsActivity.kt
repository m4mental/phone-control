package com.example.phonecontrol

import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

    data class SubFeature(val title: String, val prefKey: String, val summary: String, val defaultEnabled: Boolean)
    data class MasterCategory(val title: String, val masterKey: String, val accentColor: String, val subFeatures: List<SubFeature>)

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

    override fun onResume() {
        super.onResume()
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
                editor.putBoolean("adaptive_thermal_enabled", true)
                editor.putBoolean("master_gaming_hub_enabled", true)
                editor.putBoolean("game_turbo_enabled", true)
                editor.putBoolean("master_security_hub_enabled", true)
                editor.putBoolean("network_priority_enabled", true)
                editor.putBoolean("master_tools_hub_enabled", true)
                editor.putBoolean("freezer_enabled", true)
                editor.putBoolean("bloatware_enabled", true)
                editor.putBoolean("adb_enabled", true)
            }
        }
        editor.commit()
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
                    SubFeature("Force Doze Mode [OS Level]", "force_doze_enabled", "Instant 0s Doze on screen off, skips Android 60m motion wait.", false),
                    SubFeature("App Standby Buckets [App Level]", "standby_guard_enabled", "Puts background apps into Restricted bucket while protecting Key Mapper.", false),
                    SubFeature("Super Doze Deep Sleep [Kernel]", "super_doze_enabled", "Kernel-level deep sleep engine to minimize overnight standby battery drop to near 0%.", false),
                    SubFeature("Charging Protection & Bypass", "battery_lab_enabled", "Direct hardware bypass charging, 80% charge limiter, and USB fast charge.", false),
                    SubFeature("Hardware Sensor Firewall", "sensor_firewall_enabled", "Blocks gyroscope, magnetometer, and motion sensors when screen is off.", false)
                )
            ),
            MasterCategory(
                title = "🚀 Performance & Display Hub",
                masterKey = "master_performance_hub_enabled",
                accentColor = "#00E5FF",
                subFeatures = listOf(
                    SubFeature("Display & Resolution Scaling", "resolution_enabled", "Modify display resolution (720p/1080p) and DPI scaling for higher framerates.", false),
                    SubFeature("Memory & ZRAM Manager", "ram_manager_enabled", "High-speed compressed physical RAM allocation and LMK tuning for fluid multitasking.", false),
                    SubFeature("UFS Storage Boost", "storage_boost_enabled", "Automated weekly FSTRIM maintenance and mq-deadline I/O scheduler tuning.", false),
                    SubFeature("Adaptive Thermal Engine", "adaptive_thermal_enabled", "Monitors battery and CPU temperatures to prevent hardware overheating.", false),
                    SubFeature("Deep System Optimization", "optimization_enabled", "Suppresses background logd overhead and cleans caches for peak responsiveness.", false)
                )
            ),
            MasterCategory(
                title = "🎮 Gaming & App Turbo Hub",
                masterKey = "master_gaming_hub_enabled",
                accentColor = "#FFD600",
                subFeatures = listOf(
                    SubFeature("Game Turbo Suite", "game_turbo_enabled", "High-priority CPU/GPU scheduling and network packet prioritization during games.", false),
                    SubFeature("Per-App Profiles", "per_app_enabled", "Set custom refresh rates, touch sampling rates, and CPU power modes per application.", false)
                )
            ),
            MasterCategory(
                title = "🛡️ Security & Network Hub",
                masterKey = "master_security_hub_enabled",
                accentColor = "#00C853",
                subFeatures = listOf(
                    SubFeature("Network Booster (TCP BBR)", "network_priority_enabled", "Enables TCP BBR congestion control and prioritizes low-latency traffic.", false),
                    SubFeature("Per-App Data Firewall", "firewall_enabled", "Restricts background network access for selected applications.", false),
                    SubFeature("Home 5G Tower Lock", "tower_lock_enabled", "Locks modem to specific carrier frequency bands to stabilize 5G reception indoors.", false)
                )
            ),
            MasterCategory(
                title = "🧰 System Tools Hub",
                masterKey = "master_tools_hub_enabled",
                accentColor = "#FF5252",
                subFeatures = listOf(
                    SubFeature("App Freezer & Hibernation", "freezer_enabled", "Freeze unused applications with a single tap to reclaim 100% background RAM.", false),
                    SubFeature("Bloatware Remover", "bloatware_enabled", "Force-disable carrier-preinstalled bloatware and unnecessary background telemetry.", false),
                    SubFeature("App & Data Vault", "vault_enabled", "⚠️ [BETA] Local offline encrypted backup and restore utility for apps.", false),
                    SubFeature("Root Shell Terminal", "adb_enabled", "Directly execute and test root Linux commands inside a secured terminal.", false)
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
        val swMaster = cardView.findViewById<SwitchMaterial>(R.id.switchCategoryMaster)
        val containerSub = cardView.findViewById<LinearLayout>(R.id.layoutSubFeaturesContainer)

        tvTitle.text = category.title
        tvTitle.setTextColor(Color.parseColor(category.accentColor))
        
        val isMasterOn = prefs.getBoolean(category.masterKey, false)
        
        swMaster.setOnCheckedChangeListener(null)
        swMaster.isChecked = isMasterOn
        containerSub.visibility = if (isMasterOn) View.VISIBLE else View.GONE

        // Master Switch Listener
        swMaster.setOnCheckedChangeListener { _, isChecked ->
            val editor = prefs.edit()
            editor.putBoolean(category.masterKey, isChecked)
            
            if (!isChecked) {
                // When Group Switch is turned OFF:
                // 1. Turn OFF all sub-features in this group
                // 2. Revert all hardware/system tweaks applied by this group
                for (sub in category.subFeatures) {
                    editor.putBoolean(sub.prefKey, false)
                    revertSpecificFeature(sub.prefKey)
                }
            }
            // When Group Switch is turned ON:
            // Hub is now Active/Unlocked for individual feature selection.
            // Do NOT force enable sub-features; user enables them one-by-one.

            editor.commit()
            containerSub.visibility = if (isChecked) View.VISIBLE else View.GONE
            
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

            val isSubOn = prefs.getBoolean(sub.prefKey, false)

            swSub.setOnCheckedChangeListener(null)
            swSub.isChecked = isSubOn
            swSub.isEnabled = isCategoryEnabled

            swSub.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(sub.prefKey, isChecked).commit()
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
                "super_doze_enabled" -> {
                    BatteryManager.setForceDoze(false)
                }
                "battery_lab_enabled" -> {
                    BatteryManager.setBypassEnabled(false)
                    BatteryManager.setChargingEnabled(true)
                    BatteryManager.setUsbFastCharge(false)
                    BatteryManager.setChargingLimit(this, 100)
                }
                "smart_switch_enabled" -> {
                    getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("smart_switch_enabled", false).commit()
                }
                "sensor_firewall_enabled" -> {
                    BatteryManager.setPrivacySensorsShield(this, false)
                    BatteryManager.setKillSensorsScreenOff(this, false)
                }
                "resolution_enabled" -> {
                    ShellUtils.runAsRoot("wm size reset")
                    ShellUtils.runAsRoot("wm density reset")
                }
                "ram_manager_enabled" -> {
                    TweakManager.applyRamSettings("rbZram4G", "rbProfileBalance")
                }
                "storage_boost_enabled" -> {
                    StorageManager.applyStorageBoost(false)
                }
                "adaptive_thermal_enabled" -> {
                    getSharedPreferences("prefs", MODE_PRIVATE).edit().putInt("active_cpu_cap", 100).commit()
                    TweakManager.limitCpuFrequency(100)
                    ThermalManager.setThrottlingEnabled(true)
                }
                "optimization_enabled" -> {
                    getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("silent_system_enabled", false).commit()
                    TweakManager.setSilentSystem(false)
                    TweakManager.setRefreshRate("Default")
                }
                "game_turbo_enabled" -> {
                    getSharedPreferences("game_turbo_prefs", MODE_PRIVATE).edit().putBoolean("game_turbo_enabled", false).commit()
                    GameTurboManager.applyTouchSampling(this, false)
                }
                "per_app_enabled" -> {
                    getSharedPreferences("per_app_prefs", MODE_PRIVATE).edit().clear().commit()
                }
                "network_priority_enabled" -> {
                    ShellUtils.runAsRoot("iptables -t mangle -F OUTPUT 2>/dev/null")
                    ShellUtils.fastCmd("sysctl -w net.ipv4.tcp_congestion_control=cubic 2>/dev/null")
                    ShellUtils.fastCmd("sysctl -w net.ipv4.tcp_fastopen=0 2>/dev/null")
                    ShellUtils.fastCmd("setprop net.dns1 \"\" 2>/dev/null")
                    ShellUtils.fastCmd("setprop net.dns2 \"\" 2>/dev/null")
                }
                "firewall_enabled" -> {
                    ShellUtils.runAsRoot("iptables -F OUTPUT 2>/dev/null")
                    getSharedPreferences("firewall_prefs", MODE_PRIVATE).edit().clear().commit()
                }
                "tower_lock_enabled" -> {
                    ShellUtils.runAsRoot("echo -e \"AT+ECELL=0\\r\\n\" > /dev/radio/pttycmd1 2>/dev/null")
                    ShellUtils.runAsRoot("echo -e \"AT+E5GSWITCH=0\\r\\n\" > /dev/radio/pttycmd1 2>/dev/null")
                    getSharedPreferences("tower_prefs", MODE_PRIVATE).edit().clear().commit()
                }
                "freezer_enabled" -> {
                    val freezerPrefs = getSharedPreferences("freezer_prefs", MODE_PRIVATE)
                    freezerPrefs.edit().putBoolean("auto_freeze_enabled", false).commit()
                    val frozen = FreezerManager.getFrozenApps(this)
                    for (pkg in frozen) {
                        FreezerManager.unfreezeApp(pkg)
                        FreezerManager.setSpecialFreeze(this, pkg, false)
                    }
                    ShellUtils.runAsRoot("for pkg in \$(pm list packages -3 | cut -d ':' -f2); do pm unsuspend \$pkg 2>/dev/null; am unfreeze --package \$pkg 2>/dev/null; am set-standby-bucket \$pkg active 2>/dev/null; done")
                }
                "vault_enabled" -> {
                    getSharedPreferences("vault_prefs", MODE_PRIVATE).edit().clear().commit()
                }
            }
        }
    }

    private fun showKillSwitchDialog() {
        AlertDialog.Builder(this)
            .setTitle("Revert All Modifications")
            .setMessage("This will reset all hardware tweaks, screen settings, network rules, governors, and unfreeze all apps back to stock phone defaults. All feature toggles will also be switched OFF. Proceed?")
            .setPositiveButton("REVERT ALL") { _, _ ->
                val progress = ProgressDialog(this).apply {
                    setMessage("Reverting all tweaks & resetting system to factory stock defaults...")
                    setCancelable(false)
                    show()
                }
                thread { 
                    MasterManager.revertAll(this)
                    runOnUiThread { 
                        progress.dismiss()
                        Toast.makeText(this, "All modifications reverted & all toggles switched OFF!", Toast.LENGTH_LONG).show()
                        refreshToggles() 
                    } 
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSafeUninstallDialog() {
        AlertDialog.Builder(this)
            .setTitle("SAFE UNINSTALL")
            .setMessage("Revert tweaks and unfreeze all apps before uninstalling?")
            .setPositiveButton("REVERT & UNINSTALL") { _, _ ->
                val progress = ProgressDialog(this).apply {
                    setMessage("Reverting all tweaks before uninstalling...")
                    setCancelable(false)
                    show()
                }
                thread {
                    MasterManager.revertAll(this)
                    runOnUiThread {
                        progress.dismiss()
                        val uri = android.net.Uri.fromParts("package", packageName, null)
                        val uninstallIntent = Intent(Intent.ACTION_DELETE, uri)
                        startActivity(uninstallIntent)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
