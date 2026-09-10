package com.example.phonecontrol

import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    private lateinit var layoutToggleContainer: LinearLayout
    private lateinit var tvDiagRoot: TextView
    private lateinit var tvDiagSelinux: TextView
    private lateinit var tvDiagKernel: TextView
    private lateinit var tvDiagEventEngine: TextView
    private lateinit var btnToggleExpandAll: MaterialButton
    private lateinit var etSearchFeatures: EditText

    private val expandedCategoryKeys = mutableSetOf<String>()
    private var hasInitializedExpansion = false
    private val categoryHolders = mutableListOf<CategoryViewHolder>()

    data class SubFeature(
        val title: String,
        val prefKey: String,
        val summary: String,
        val defaultEnabled: Boolean,
        val iconRes: Int
    )

    data class MasterCategory(
        val title: String,
        val masterKey: String,
        val accentColor: String,
        val iconRes: Int,
        val subFeatures: List<SubFeature>,
        val description: String = ""
    )

    private class SubFeatureViewItem(
        val sub: SubFeature,
        val view: View,
        val sw: SwitchMaterial
    )

    private class CategoryViewHolder(
        val category: MasterCategory,
        val cardView: MaterialCardView,
        val containerSub: LinearLayout,
        val divider: View,
        val ivChevron: ImageView,
        val tvSubtitle: TextView,
        val tvBadge: TextView,
        val swMaster: SwitchMaterial,
        var subItems: List<SubFeatureViewItem> = emptyList()
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

        btnToggleExpandAll = findViewById(R.id.btnToggleExpandAll)
        etSearchFeatures = findViewById(R.id.etSearchFeatures)

        btnToggleExpandAll.setOnClickListener {
            val willExpandAll = expandedCategoryKeys.size < categoryHolders.size
            for (holder in categoryHolders) {
                if (willExpandAll) {
                    holder.containerSub.visibility = View.VISIBLE
                    holder.divider.visibility = View.VISIBLE
                    holder.ivChevron.animate().rotation(180f).setDuration(220).start()
                    expandedCategoryKeys.add(holder.category.masterKey)
                } else {
                    holder.containerSub.visibility = View.GONE
                    holder.divider.visibility = View.GONE
                    holder.ivChevron.animate().rotation(0f).setDuration(220).start()
                    expandedCategoryKeys.remove(holder.category.masterKey)
                }
            }
            updateExpandAllButtonText()
        }

        etSearchFeatures.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterFeatures(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

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
                editor.putBoolean("force_doze_enabled", true)
                editor.putBoolean("standby_guard_enabled", true)
                editor.putBoolean("battery_lab_enabled", true)
                editor.putBoolean("super_doze_enabled", true)
                editor.putBoolean("smart_switch_enabled", true)
                editor.putBoolean("sensor_firewall_enabled", true)
                editor.putBoolean("master_performance_hub_enabled", true)
                editor.putBoolean("adaptive_thermal_enabled", true)
            }
            "Balance" -> {
                editor.putBoolean("master_battery_hub_enabled", true)
                editor.putBoolean("force_doze_enabled", true)
                editor.putBoolean("standby_guard_enabled", true)
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
                editor.putBoolean("app_extractor_enabled", true)
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
                title = "Battery & Power Hub",
                masterKey = "master_battery_hub_enabled",
                accentColor = "#00E676",
                iconRes = R.drawable.ic_hub_battery,
                description = "Force Doze, Super Doze, Bypass Charging & Sensor Shield",
                subFeatures = listOf(
                    SubFeature("Force Doze Mode [OS Level]", "force_doze_enabled", "Instant 0s Doze on screen off, skips Android 60m motion wait.", false, R.drawable.ic_sub_doze),
                    SubFeature("App Standby Buckets [App Level]", "standby_guard_enabled", "Puts background apps into Restricted bucket while protecting Key Mapper.", false, R.drawable.ic_sub_standby),
                    SubFeature("Super Doze Deep Sleep [Kernel]", "super_doze_enabled", "Kernel-level deep sleep engine to minimize overnight standby battery drop to near 0%.", false, R.drawable.ic_sub_super_doze),
                    SubFeature("Charging Protection & Bypass", "battery_lab_enabled", "Direct hardware bypass charging, 80% charge limiter, and USB fast charge.", false, R.drawable.ic_sub_battery_lab),
                    SubFeature("Hardware Sensor Firewall", "sensor_firewall_enabled", "Blocks gyroscope, magnetometer, and motion sensors when screen is off.", false, R.drawable.ic_sub_sensor)
                )
            ),
            MasterCategory(
                title = "Performance & Display Hub",
                masterKey = "master_performance_hub_enabled",
                accentColor = "#00E5FF",
                iconRes = R.drawable.ic_hub_performance,
                description = "Display scaling, ZRAM manager, Storage boost & Thermals",
                subFeatures = listOf(
                    SubFeature("Display & Resolution Scaling", "resolution_enabled", "Modify display resolution (720p/1080p) and DPI scaling for higher framerates.", false, R.drawable.ic_sub_resolution),
                    SubFeature("Memory & ZRAM Manager", "ram_manager_enabled", "High-speed compressed physical RAM allocation and LMK tuning for fluid multitasking.", false, R.drawable.ic_sub_ram),
                    SubFeature("UFS Storage Boost", "storage_boost_enabled", "Automated weekly FSTRIM maintenance and mq-deadline I/O scheduler tuning.", false, R.drawable.ic_sub_storage),
                    SubFeature("Adaptive Thermal Engine", "adaptive_thermal_enabled", "Monitors battery and CPU temperatures to prevent hardware overheating.", false, R.drawable.ic_sub_thermal),
                    SubFeature("Deep System Optimization", "optimization_enabled", "Suppresses background logd overhead and cleans caches for peak responsiveness.", false, R.drawable.ic_sub_optimization)
                )
            ),
            MasterCategory(
                title = "Gaming & App Turbo Hub",
                masterKey = "master_gaming_hub_enabled",
                accentColor = "#FFD600",
                iconRes = R.drawable.ic_hub_gaming,
                description = "Game Turbo suite & Per-app custom refresh/touch rates",
                subFeatures = listOf(
                    SubFeature("Game Turbo Suite", "game_turbo_enabled", "High-priority CPU/GPU scheduling and network packet prioritization during games.", false, R.drawable.ic_sub_game_turbo),
                    SubFeature("Per-App Profiles", "per_app_enabled", "Set custom refresh rates, touch sampling rates, and CPU power modes per application.", false, R.drawable.ic_sub_per_app)
                )
            ),
            MasterCategory(
                title = "Security & Network Hub",
                masterKey = "master_security_hub_enabled",
                accentColor = "#00C853",
                iconRes = R.drawable.ic_hub_security,
                description = "TCP BBR latency booster, Data Firewall & 5G Tower Lock",
                subFeatures = listOf(
                    SubFeature("Network Booster (TCP BBR)", "network_priority_enabled", "Enables TCP BBR congestion control and prioritizes low-latency traffic.", false, R.drawable.ic_sub_network),
                    SubFeature("Per-App Data Firewall", "firewall_enabled", "Restricts background network access for selected applications.", false, R.drawable.ic_sub_firewall),
                    SubFeature("Home 5G Tower Lock", "tower_lock_enabled", "Locks modem to specific carrier frequency bands to stabilize 5G reception indoors.", false, R.drawable.ic_sub_tower)
                )
            ),
            MasterCategory(
                title = "System Tools Hub",
                masterKey = "master_tools_hub_enabled",
                accentColor = "#FF5252",
                iconRes = R.drawable.ic_hub_tools,
                description = "App Freezer, Bloatware remover, APK Extractor & Terminal",
                subFeatures = listOf(
                    SubFeature("App Freezer & Hibernation", "freezer_enabled", "Freeze unused applications with a single tap to reclaim 100% background RAM.", false, R.drawable.ic_sub_freezer),
                    SubFeature("Bloatware Remover", "bloatware_enabled", "Force-disable carrier-preinstalled bloatware and unnecessary background telemetry.", false, R.drawable.ic_sub_bloatware),
                    SubFeature("Installed App Extractor", "app_extractor_enabled", "Extract single APKs or split app bundles (.apks) to storage/share with 1-tap.", true, R.drawable.ic_sub_extractor),
                    SubFeature("App & Data Vault", "vault_enabled", "⚠️ [BETA] Local offline encrypted backup and restore utility for apps.", false, R.drawable.ic_sub_vault),
                    SubFeature("Root Shell Terminal", "adb_enabled", "Directly execute and test root Linux commands inside a secured terminal.", false, R.drawable.ic_sub_terminal)
                )
            )
        )

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        if (!hasInitializedExpansion) {
            // By default, all categories stay collapsed as requested
            expandedCategoryKeys.clear()
            hasInitializedExpansion = true
        }

        categoryHolders.clear()
        for (category in masterCategories) {
            addCategoryCardView(category)
        }

        updateExpandAllButtonText()
        if (::etSearchFeatures.isInitialized && etSearchFeatures.text.isNotBlank()) {
            filterFeatures(etSearchFeatures.text.toString())
        }
    }

    private fun addCategoryCardView(category: MasterCategory) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val cardView = layoutInflater.inflate(R.layout.item_setting_category_card, layoutToggleContainer, false) as MaterialCardView

        val ivCategoryIcon = cardView.findViewById<ImageView>(R.id.ivCategoryIcon)
        val tvTitle = cardView.findViewById<TextView>(R.id.tvCategoryTitle)
        val tvSubtitle = cardView.findViewById<TextView>(R.id.tvCategorySubtitle)
        val tvBadge = cardView.findViewById<TextView>(R.id.tvCategoryBadge)
        val swMaster = cardView.findViewById<SwitchMaterial>(R.id.switchCategoryMaster)
        val ivChevron = cardView.findViewById<ImageView>(R.id.ivCategoryChevron)
        val divider = cardView.findViewById<View>(R.id.categoryDivider)
        val containerSub = cardView.findViewById<LinearLayout>(R.id.layoutSubFeaturesContainer)
        val layoutHeader = cardView.findViewById<LinearLayout>(R.id.layoutCategoryHeader)

        val accentColorInt = Color.parseColor(category.accentColor)
        ivCategoryIcon.setImageResource(category.iconRes)
        ivCategoryIcon.setColorFilter(accentColorInt)
        cardView.strokeColor = accentColorInt

        tvTitle.text = category.title
        tvTitle.setTextColor(accentColorInt)
        tvSubtitle.text = category.description

        val isMasterOn = prefs.getBoolean(category.masterKey, true)
        val isExpanded = expandedCategoryKeys.contains(category.masterKey)

        swMaster.setOnCheckedChangeListener(null)
        swMaster.isChecked = isMasterOn

        containerSub.visibility = if (isExpanded) View.VISIBLE else View.GONE
        divider.visibility = if (isExpanded) View.VISIBLE else View.GONE
        ivChevron.rotation = if (isExpanded) 180f else 0f

        val holder = CategoryViewHolder(
            category = category,
            cardView = cardView,
            containerSub = containerSub,
            divider = divider,
            ivChevron = ivChevron,
            tvSubtitle = tvSubtitle,
            tvBadge = tvBadge,
            swMaster = swMaster
        )
        categoryHolders.add(holder)

        // Dropdown Accordion Expand / Collapse Header Click
        layoutHeader.setOnClickListener {
            val willBeExpanded = containerSub.visibility != View.VISIBLE
            if (willBeExpanded) {
                containerSub.visibility = View.VISIBLE
                divider.visibility = View.VISIBLE
                ivChevron.animate().rotation(180f).setDuration(220).start()
                expandedCategoryKeys.add(category.masterKey)
            } else {
                containerSub.visibility = View.GONE
                divider.visibility = View.GONE
                ivChevron.animate().rotation(0f).setDuration(220).start()
                expandedCategoryKeys.remove(category.masterKey)
            }
            updateExpandAllButtonText()
        }

        // Prevent switch clicks from toggling expand/collapse
        swMaster.setOnClickListener { /* consumed */ }

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
            } else {
                // When turned ON, auto-expand dropdown if currently collapsed
                if (containerSub.visibility != View.VISIBLE) {
                    containerSub.visibility = View.VISIBLE
                    divider.visibility = View.VISIBLE
                    ivChevron.animate().rotation(180f).setDuration(220).start()
                    expandedCategoryKeys.add(category.masterKey)
                    updateExpandAllButtonText()
                }
            }

            editor.commit()
            // Refresh sub-views inside container
            renderSubFeatures(holder, isChecked)
            updateCategoryBadge(holder)
        }

        renderSubFeatures(holder, isMasterOn)
        updateCategoryBadge(holder)
        layoutToggleContainer.addView(cardView)
    }

    private fun renderSubFeatures(holder: CategoryViewHolder, isCategoryEnabled: Boolean) {
        val container = holder.containerSub
        val category = holder.category
        container.removeAllViews()
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val accentColorInt = Color.parseColor(category.accentColor)
        val items = mutableListOf<SubFeatureViewItem>()

        for (sub in category.subFeatures) {
            val subView = layoutInflater.inflate(R.layout.item_setting_toggle, container, false)
            val ivToggleIcon = subView.findViewById<ImageView>(R.id.ivToggleIcon)
            val tvSubTitle = subView.findViewById<TextView>(R.id.tvToggleTitle)
            val tvSubSummary = subView.findViewById<TextView>(R.id.tvToggleSummary)
            val swSub = subView.findViewById<SwitchMaterial>(R.id.switchFeature)

            ivToggleIcon.setImageResource(sub.iconRes)
            ivToggleIcon.setColorFilter(accentColorInt)

            tvSubTitle.text = sub.title
            tvSubSummary.text = sub.summary

            if (sub.summary.contains("BETA")) {
                tvSubSummary.setTextColor(Color.parseColor("#FFAB40"))
            }

            val isSubOn = prefs.getBoolean(sub.prefKey, sub.defaultEnabled)

            swSub.setOnCheckedChangeListener(null)
            swSub.isChecked = isSubOn
            swSub.isEnabled = isCategoryEnabled

            subView.alpha = if (isCategoryEnabled) 1.0f else 0.45f

            subView.setOnClickListener {
                if (isCategoryEnabled) {
                    swSub.isChecked = !swSub.isChecked
                }
            }

            swSub.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(sub.prefKey, isChecked).commit()
                if (!isChecked) {
                    revertSpecificFeature(sub.prefKey)
                }
                updateCategoryBadge(holder)
            }

            container.addView(subView)
            items.add(SubFeatureViewItem(sub, subView, swSub))
        }
        holder.subItems = items
    }

    private fun updateCategoryBadge(holder: CategoryViewHolder) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val isMasterOn = prefs.getBoolean(holder.category.masterKey, true)
        val onCount = if (isMasterOn) {
            holder.category.subFeatures.count { prefs.getBoolean(it.prefKey, it.defaultEnabled) }
        } else {
            0
        }
        val total = holder.category.subFeatures.size
        holder.tvBadge.text = "$onCount/$total"
        if (onCount > 0) {
            holder.tvBadge.setBackgroundResource(R.drawable.bg_badge_pill)
            holder.tvBadge.setTextColor(Color.parseColor("#00E676"))
        } else {
            holder.tvBadge.setBackgroundResource(R.drawable.bg_badge_pill_off)
            holder.tvBadge.setTextColor(Color.parseColor("#888888"))
        }
    }

    private fun updateExpandAllButtonText() {
        if (!::btnToggleExpandAll.isInitialized) return
        val allExpanded = categoryHolders.isNotEmpty() && expandedCategoryKeys.size == categoryHolders.size
        btnToggleExpandAll.text = if (allExpanded) "Collapse All" else "Expand All"
    }

    private fun filterFeatures(query: String) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            for (holder in categoryHolders) {
                holder.cardView.visibility = View.VISIBLE
                val isExpanded = expandedCategoryKeys.contains(holder.category.masterKey)
                holder.containerSub.visibility = if (isExpanded) View.VISIBLE else View.GONE
                holder.divider.visibility = if (isExpanded) View.VISIBLE else View.GONE
                holder.ivChevron.rotation = if (isExpanded) 180f else 0f
                for (item in holder.subItems) {
                    item.view.visibility = View.VISIBLE
                }
            }
            updateExpandAllButtonText()
            return
        }

        for (holder in categoryHolders) {
            val titleMatches = holder.category.title.lowercase().contains(q)
            val descMatches = holder.category.description.lowercase().contains(q)
            var anySubMatches = false

            for (item in holder.subItems) {
                val subMatches = item.sub.title.lowercase().contains(q) || item.sub.summary.lowercase().contains(q)
                if (subMatches || titleMatches) {
                    item.view.visibility = View.VISIBLE
                    anySubMatches = true
                } else {
                    item.view.visibility = View.GONE
                }
            }

            // If category feature description matches (e.g. "shield", "thermals") and no specific sub-items matched, show all items in this hub
            if (descMatches && !anySubMatches) {
                for (item in holder.subItems) {
                    item.view.visibility = View.VISIBLE
                }
                anySubMatches = true
            }

            if (titleMatches || descMatches || anySubMatches) {
                holder.cardView.visibility = View.VISIBLE
                holder.containerSub.visibility = View.VISIBLE
                holder.divider.visibility = View.VISIBLE
                holder.ivChevron.rotation = 180f
            } else {
                holder.cardView.visibility = View.GONE
            }
        }
    }

    private fun revertSpecificFeature(prefKey: String) {
        thread {
            when (prefKey) {
                "force_doze_enabled" -> {
                    BatteryManager.setForceDoze(false)
                }
                "standby_guard_enabled" -> {
                    ShellUtils.runAsRoot("for pkg in \$(pm list packages -3 | cut -d ':' -f2); do am set-standby-bucket \$pkg active 2>/dev/null; done")
                }
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
                "app_extractor_enabled" -> {
                    // Feature state toggled off; card in System Tools hub dynamically hides
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
