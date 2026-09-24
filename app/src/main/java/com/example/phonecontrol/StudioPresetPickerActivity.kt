package com.example.phonecontrol

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Universal Quick Settings (QS) Tile Long-Press Dispatcher & Fast-Access Dialog Activity:
 * - ModeControlTileService -> Mode Control Chooser Dialog (AI Auto, Balanced, Streaming, Power, Perf)
 * - PrivateDnsTileService -> Private DNS Chooser Dialog (AdGuard, Cloudflare, Google, Off)
 * - HudTileService -> Performance HUD Quick Dialog (Toggle HUD + Per-App link)
 * - QuickFreezeTileService -> Directly launches AppFreezerListActivity
 * - WirelessAdbTileService -> Directly launches AdbShellActivity
 * - CooldownTileService -> Directly launches ThrottlingActivity (Emergency Cooldown)
 * - StudioEqualizerTileService / StudioPresetTileService -> Studio Equalizer Presets Dialog
 * - StudioNightModeTileService -> Movie & Night Mode Dialog
 */
class StudioPresetPickerActivity : AppCompatActivity() {

    private lateinit var rootOverlay: View
    private lateinit var tvDialogTitle: TextView
    private lateinit var btnClosePicker: ImageButton
    private lateinit var toggleGroupViewMode: MaterialButtonToggleGroup
    private lateinit var btnTabPresets: MaterialButton
    private lateinit var btnTabNightMode: MaterialButton

    // Mode Control Dialog Section
    private lateinit var layoutSectionModeControl: LinearLayout
    private lateinit var tvModeSubtitle: TextView
    private lateinit var itemModeAuto: LinearLayout
    private lateinit var itemModeBalance: LinearLayout
    private lateinit var itemModeStreaming: LinearLayout
    private lateinit var itemModePowerSaver: LinearLayout
    private lateinit var itemModePerformance: LinearLayout
    private lateinit var ivCheckModeAuto: ImageView
    private lateinit var ivCheckModeBalance: ImageView
    private lateinit var ivCheckModeStreaming: ImageView
    private lateinit var ivCheckModePowerSaver: ImageView
    private lateinit var ivCheckModePerformance: ImageView
    private lateinit var btnOpenModeSettings: MaterialButton

    // Private DNS Dialog Section
    private lateinit var layoutSectionPrivateDns: LinearLayout
    private lateinit var tvDnsSubtitle: TextView
    private lateinit var itemDnsAdguard: LinearLayout
    private lateinit var itemDnsCloudflare: LinearLayout
    private lateinit var itemDnsGoogle: LinearLayout
    private lateinit var itemDnsQuad9: LinearLayout
    private lateinit var itemDnsMullvad: LinearLayout
    private lateinit var itemDnsControlD: LinearLayout
    private lateinit var itemDnsCustom: LinearLayout
    private lateinit var itemDnsOff: LinearLayout
    private lateinit var ivCheckDnsAdguard: ImageView
    private lateinit var ivCheckDnsCloudflare: ImageView
    private lateinit var ivCheckDnsGoogle: ImageView
    private lateinit var ivCheckDnsQuad9: ImageView
    private lateinit var ivCheckDnsMullvad: ImageView
    private lateinit var ivCheckDnsControlD: ImageView
    private lateinit var ivCheckDnsCustom: ImageView
    private lateinit var ivCheckDnsOff: ImageView
    private lateinit var tvDnsCustomDesc: TextView
    private lateinit var btnOpenNetworkSettings: MaterialButton

    // Performance HUD Dialog Section
    private lateinit var layoutSectionPerfHud: LinearLayout
    private lateinit var tvPerfHudStatusTitle: TextView
    private lateinit var tvPerfHudStatusDesc: TextView
    private lateinit var switchPerfHudToggle: MaterialSwitch
    private lateinit var btnOpenPerAppSettings: MaterialButton

    // Studio Presets Section
    private lateinit var layoutSectionPresets: LinearLayout
    private lateinit var tvActivePresetStatus: TextView
    private lateinit var rvPresetList: RecyclerView

    // Studio Night Mode Section
    private lateinit var layoutSectionNightMode: LinearLayout
    private lateinit var switchNightModePicker: MaterialSwitch
    private lateinit var layoutNightPickerControls: LinearLayout
    private lateinit var toggleGroupNightProfilePicker: MaterialButtonToggleGroup
    private lateinit var seekDialogueBoostPicker: SeekBar
    private lateinit var tvDialogueBoostPickerValue: TextView

    // Equalizer Action Footer
    private lateinit var layoutFooterEqualizer: LinearLayout
    private lateinit var btnOpenFullEqualizer: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Intercept direct-launch QS Tiles BEFORE inflating views to avoid flicker
        if (handleDirectLaunchTiles()) {
            return
        }

        setContentView(R.layout.activity_studio_preset_picker)

        initViews()
        setupRouting()
        setupGlobalActions()
    }

    private fun handleDirectLaunchTiles(): Boolean {
        val targetComponent = getTargetComponent()
        val className = targetComponent?.className ?: ""

        if (className.contains("QuickFreezeTileService")) {
            val intent = Intent(this, AppFreezerListActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            finish()
            return true
        }

        if (className.contains("WirelessAdbTileService")) {
            val intent = Intent(this, SettingsActivity::class.java).apply {
                putExtra("target_section", "wireless_adb")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            finish()
            return true
        }

        if (className.contains("CooldownTileService")) {
            val intent = Intent(this, ThrottlingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            finish()
            return true
        }

        return false
    }

    private fun getTargetComponent(): ComponentName? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME)
        } ?: (intent.getStringExtra(Intent.EXTRA_COMPONENT_NAME)?.let { ComponentName.unflattenFromString(it) })
    }

    private fun initViews() {
        rootOverlay = findViewById(R.id.rootOverlay)
        tvDialogTitle = findViewById(R.id.tvDialogTitle)
        btnClosePicker = findViewById(R.id.btnClosePicker)
        toggleGroupViewMode = findViewById(R.id.toggleGroupViewMode)
        btnTabPresets = findViewById(R.id.btnTabPresets)
        btnTabNightMode = findViewById(R.id.btnTabNightMode)

        // Mode Control
        layoutSectionModeControl = findViewById(R.id.layoutSectionModeControl)
        tvModeSubtitle = findViewById(R.id.tvModeSubtitle)
        itemModeAuto = findViewById(R.id.itemModeAuto)
        itemModeBalance = findViewById(R.id.itemModeBalance)
        itemModeStreaming = findViewById(R.id.itemModeStreaming)
        itemModePowerSaver = findViewById(R.id.itemModePowerSaver)
        itemModePerformance = findViewById(R.id.itemModePerformance)
        ivCheckModeAuto = findViewById(R.id.ivCheckModeAuto)
        ivCheckModeBalance = findViewById(R.id.ivCheckModeBalance)
        ivCheckModeStreaming = findViewById(R.id.ivCheckModeStreaming)
        ivCheckModePowerSaver = findViewById(R.id.ivCheckModePowerSaver)
        ivCheckModePerformance = findViewById(R.id.ivCheckModePerformance)
        btnOpenModeSettings = findViewById(R.id.btnOpenModeSettings)

        // Private DNS
        layoutSectionPrivateDns = findViewById(R.id.layoutSectionPrivateDns)
        tvDnsSubtitle = findViewById(R.id.tvDnsSubtitle)
        itemDnsAdguard = findViewById(R.id.itemDnsAdguard)
        itemDnsCloudflare = findViewById(R.id.itemDnsCloudflare)
        itemDnsGoogle = findViewById(R.id.itemDnsGoogle)
        itemDnsQuad9 = findViewById(R.id.itemDnsQuad9)
        itemDnsMullvad = findViewById(R.id.itemDnsMullvad)
        itemDnsControlD = findViewById(R.id.itemDnsControlD)
        itemDnsCustom = findViewById(R.id.itemDnsCustom)
        itemDnsOff = findViewById(R.id.itemDnsOff)
        ivCheckDnsAdguard = findViewById(R.id.ivCheckDnsAdguard)
        ivCheckDnsCloudflare = findViewById(R.id.ivCheckDnsCloudflare)
        ivCheckDnsGoogle = findViewById(R.id.ivCheckDnsGoogle)
        ivCheckDnsQuad9 = findViewById(R.id.ivCheckDnsQuad9)
        ivCheckDnsMullvad = findViewById(R.id.ivCheckDnsMullvad)
        ivCheckDnsControlD = findViewById(R.id.ivCheckDnsControlD)
        ivCheckDnsCustom = findViewById(R.id.ivCheckDnsCustom)
        ivCheckDnsOff = findViewById(R.id.ivCheckDnsOff)
        tvDnsCustomDesc = findViewById(R.id.tvDnsCustomDesc)
        btnOpenNetworkSettings = findViewById(R.id.btnOpenNetworkSettings)

        // Performance HUD
        layoutSectionPerfHud = findViewById(R.id.layoutSectionPerfHud)
        tvPerfHudStatusTitle = findViewById(R.id.tvPerfHudStatusTitle)
        tvPerfHudStatusDesc = findViewById(R.id.tvPerfHudStatusDesc)
        switchPerfHudToggle = findViewById(R.id.switchPerfHudToggle)
        btnOpenPerAppSettings = findViewById(R.id.btnOpenPerAppSettings)

        // Studio Presets
        layoutSectionPresets = findViewById(R.id.layoutSectionPresets)
        tvActivePresetStatus = findViewById(R.id.tvActivePresetStatus)
        rvPresetList = findViewById(R.id.rvPresetList)

        // Night Mode
        layoutSectionNightMode = findViewById(R.id.layoutSectionNightMode)
        switchNightModePicker = findViewById(R.id.switchNightModePicker)
        layoutNightPickerControls = findViewById(R.id.layoutNightPickerControls)
        toggleGroupNightProfilePicker = findViewById(R.id.toggleGroupNightProfilePicker)
        seekDialogueBoostPicker = findViewById(R.id.seekDialogueBoostPicker)
        tvDialogueBoostPickerValue = findViewById(R.id.tvDialogueBoostPickerValue)

        // Equalizer Action Footer
        layoutFooterEqualizer = findViewById(R.id.layoutFooterEqualizer)
        btnOpenFullEqualizer = findViewById(R.id.btnOpenFullEqualizer)
    }

    private fun hideAllSections() {
        toggleGroupViewMode.visibility = View.GONE
        layoutSectionModeControl.visibility = View.GONE
        layoutSectionPrivateDns.visibility = View.GONE
        layoutSectionPerfHud.visibility = View.GONE
        layoutSectionPresets.visibility = View.GONE
        layoutSectionNightMode.visibility = View.GONE
        layoutFooterEqualizer.visibility = View.GONE
    }

    private fun setupRouting() {
        val targetComponent = getTargetComponent()
        val className = targetComponent?.className ?: ""
        val targetSectionExtra = intent.getStringExtra("target_section") ?: ""

        when {
            className.contains("ModeControlTileService") || targetSectionExtra == "mode_control" -> {
                showModeControlDialog()
            }
            className.contains("PrivateDnsTileService") || targetSectionExtra == "private_dns" -> {
                showPrivateDnsDialog()
            }
            className.contains("HudTileService") || targetSectionExtra == "perf_hud" -> {
                showPerfHudDialog()
            }
            className.contains("StudioNightModeTileService") || targetSectionExtra == "night_mode" -> {
                setupNightModeSection()
                showNightModeTab()
            }
            else -> {
                // Default / StudioEqualizerTileService / StudioPresetTileService
                setupPresetsSection()
                setupNightModeSection()
                showPresetsTab()
            }
        }

        toggleGroupViewMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnTabPresets -> showPresetsTab()
                    R.id.btnTabNightMode -> showNightModeTab()
                }
            }
        }
    }

    // =========================================================================
    // 1. MODE CONTROL CHOOSER DIALOG
    // =========================================================================
    private fun showModeControlDialog() {
        hideAllSections()
        tvDialogTitle.text = "⚡ Mode Control"
        layoutSectionModeControl.visibility = View.VISIBLE

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val currentMode = prefs.getString("selected_mode", "rbBalance")
        val manualStage = prefs.getInt("manual_stage_override", 0)

        if (manualStage != 0) {
            tvModeSubtitle.text = "🔓 Test Lab Override Active • Select profile to restore:"
            tvModeSubtitle.setTextColor(0xFFF59E0B.toInt())
        } else {
            tvModeSubtitle.text = "Choose CPU/GPU operating profile:"
            tvModeSubtitle.setTextColor(0xFF94A3B8.toInt())
        }

        // Update active checkmarks
        ivCheckModeAuto.visibility = if (currentMode == "rbAutomatic" && manualStage == 0) View.VISIBLE else View.GONE
        ivCheckModeBalance.visibility = if (currentMode == "rbBalance" && manualStage == 0) View.VISIBLE else View.GONE
        ivCheckModeStreaming.visibility = if (currentMode == "rbStreaming" && manualStage == 0) View.VISIBLE else View.GONE
        ivCheckModePowerSaver.visibility = if (currentMode == "rbPowerSaver" && manualStage == 0) View.VISIBLE else View.GONE
        ivCheckModePerformance.visibility = if (currentMode == "rbPerformance" && manualStage == 0) View.VISIBLE else View.GONE

        itemModeAuto.setOnClickListener { selectGlobalMode("rbAutomatic") }
        itemModeBalance.setOnClickListener { selectGlobalMode("rbBalance") }
        itemModeStreaming.setOnClickListener { selectGlobalMode("rbStreaming") }
        itemModePowerSaver.setOnClickListener { selectGlobalMode("rbPowerSaver") }
        itemModePerformance.setOnClickListener { selectGlobalMode("rbPerformance") }

        btnOpenModeSettings.setOnClickListener {
            startActivity(Intent(this, ModeControlActivity::class.java))
            finish()
        }
    }

    private fun selectGlobalMode(modeKey: String) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        prefs.edit().putInt("manual_stage_override", 0).apply()
        TweakManager.manualStageOverride = 0
        prefs.edit().putString("selected_mode", modeKey).apply()

        thread {
            if (modeKey == "rbAutomatic") {
                startService(Intent(this, AutoTweakService::class.java))
            } else {
                prefs.edit().remove("active_ai_label").apply()
                val displayMode = when (modeKey) {
                    "rbPowerSaver" -> "Power Saver"
                    "rbPerformance" -> "Performance"
                    "rbStreaming" -> "Streaming"
                    else -> "Balance"
                }
                TweakManager.applyGlobalMode(displayMode)
            }

            val updateIntent = Intent("com.example.phonecontrol.UPDATE_UI").apply {
                setPackage(packageName)
            }
            sendBroadcast(updateIntent)

            val toastMsg = when (modeKey) {
                "rbAutomatic" -> "🤖 AI Dynamic Mode Active"
                "rbStreaming" -> "🎬 Streaming Mode Active"
                "rbPowerSaver" -> "🔋 Power Saver Mode Active"
                "rbPerformance" -> "🚀 Performance Mode Active"
                else -> "⚡ Balanced Mode Active"
            }
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, toastMsg, Toast.LENGTH_SHORT).show()
            }
        }
        ModeControlTileService.updateTile(this)
        finish()
    }

    // =========================================================================
    // 2. PRIVATE DNS CHOOSER DIALOG
    // =========================================================================
    private fun showPrivateDnsDialog() {
        hideAllSections()
        tvDialogTitle.text = "🛡️ Private DNS Profile"
        layoutSectionPrivateDns.visibility = View.VISIBLE

        syncDnsState()

        itemDnsAdguard.setOnClickListener { selectDns(PrivateDnsManager.DnsProvider.ADGUARD) }
        itemDnsCloudflare.setOnClickListener { selectDns(PrivateDnsManager.DnsProvider.CLOUDFLARE) }
        itemDnsGoogle.setOnClickListener { selectDns(PrivateDnsManager.DnsProvider.GOOGLE) }
        itemDnsQuad9.setOnClickListener { selectDns(PrivateDnsManager.DnsProvider.QUAD9) }
        itemDnsMullvad.setOnClickListener { selectDns(PrivateDnsManager.DnsProvider.MULLVAD) }
        itemDnsControlD.setOnClickListener { selectDns(PrivateDnsManager.DnsProvider.CONTROLD) }
        itemDnsCustom.setOnClickListener { promptCustomDnsDialog() }
        itemDnsOff.setOnClickListener { selectDns(PrivateDnsManager.DnsProvider.OFF) }

        btnOpenNetworkSettings.setOnClickListener {
            startActivity(Intent(this, NetworkActivity::class.java))
            finish()
        }
    }

    private fun syncDnsState() {
        val curProvider = PrivateDnsManager.getCurrentProvider(this)
        val spec = PrivateDnsManager.getCurrentSpecifier(this)

        val detail = if (curProvider == PrivateDnsManager.DnsProvider.CUSTOM && spec.isNotBlank()) {
            "Active: Custom ($spec)"
        } else {
            "Active: ${curProvider.displayName}"
        }
        tvDnsSubtitle.text = detail

        if (curProvider == PrivateDnsManager.DnsProvider.CUSTOM && spec.isNotBlank()) {
            tvDnsCustomDesc.text = "Active: $spec (tap to edit)"
        } else {
            tvDnsCustomDesc.text = "NextDNS, Pi-hole, or user-defined hostname"
        }

        ivCheckDnsAdguard.visibility = if (curProvider == PrivateDnsManager.DnsProvider.ADGUARD) View.VISIBLE else View.GONE
        ivCheckDnsCloudflare.visibility = if (curProvider == PrivateDnsManager.DnsProvider.CLOUDFLARE) View.VISIBLE else View.GONE
        ivCheckDnsGoogle.visibility = if (curProvider == PrivateDnsManager.DnsProvider.GOOGLE) View.VISIBLE else View.GONE
        ivCheckDnsQuad9.visibility = if (curProvider == PrivateDnsManager.DnsProvider.QUAD9) View.VISIBLE else View.GONE
        ivCheckDnsMullvad.visibility = if (curProvider == PrivateDnsManager.DnsProvider.MULLVAD) View.VISIBLE else View.GONE
        ivCheckDnsControlD.visibility = if (curProvider == PrivateDnsManager.DnsProvider.CONTROLD) View.VISIBLE else View.GONE
        ivCheckDnsCustom.visibility = if (curProvider == PrivateDnsManager.DnsProvider.CUSTOM) View.VISIBLE else View.GONE
        ivCheckDnsOff.visibility = if (curProvider == PrivateDnsManager.DnsProvider.OFF) View.VISIBLE else View.GONE
    }

    private fun selectDns(provider: PrivateDnsManager.DnsProvider) {
        thread {
            PrivateDnsManager.setProvider(provider, this@StudioPresetPickerActivity)
            Handler(Looper.getMainLooper()).post {
                val message = when (provider) {
                    PrivateDnsManager.DnsProvider.ADGUARD -> "🛡️ AdGuard DNS: Ads Blocked System-Wide"
                    PrivateDnsManager.DnsProvider.CLOUDFLARE -> "⚡ Cloudflare 1.1.1.1: Gaming DNS Active"
                    PrivateDnsManager.DnsProvider.GOOGLE -> "🌐 Google DNS: Fast CDN Active"
                    PrivateDnsManager.DnsProvider.QUAD9 -> "🛡️ Quad9 DNS: Malware Protection Active"
                    PrivateDnsManager.DnsProvider.MULLVAD -> "🔒 Mullvad DNS: Privacy & AdBlock Active"
                    PrivateDnsManager.DnsProvider.CONTROLD -> "⚡ ControlD DNS: Filter Active"
                    PrivateDnsManager.DnsProvider.CUSTOM -> "🔧 Custom DNS Hostname Active"
                    PrivateDnsManager.DnsProvider.OFF -> "⚪ DNS: Default ISP / Automatic"
                }
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                PrivateDnsTileService.updateTile(this@StudioPresetPickerActivity)
                finish()
            }
        }
    }

    private fun promptCustomDnsDialog() {
        val currentSpec = PrivateDnsManager.getCurrentSpecifier(this)
        val input = EditText(this).apply {
            hint = "e.g. xxxxxx.dns.nextdns.io or dns.quad9.net"
            setText(currentSpec)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            setPadding(40, 30, 40, 30)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("🔧 Custom Private DNS Hostname")
            .setMessage("Enter your TLS/DoH DNS provider hostname (NextDNS, Pi-hole, AdGuard Home, ControlD):")
            .setView(input)
            .setPositiveButton("APPLY") { _, _ ->
                val hostname = input.text.toString().trim()
                thread {
                    val success = PrivateDnsManager.setCustomHostname(hostname, this@StudioPresetPickerActivity)
                    Handler(Looper.getMainLooper()).post {
                        if (success) {
                            Toast.makeText(this@StudioPresetPickerActivity, "Custom DNS set: $hostname", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@StudioPresetPickerActivity, "Failed to apply custom DNS", Toast.LENGTH_SHORT).show()
                        }
                        PrivateDnsTileService.updateTile(this@StudioPresetPickerActivity)
                        finish()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================================
    // 3. PERFORMANCE HUD QUICK DIALOG
    // =========================================================================
    private fun showPerfHudDialog() {
        hideAllSections()
        tvDialogTitle.text = "📊 Performance HUD"
        layoutSectionPerfHud.visibility = View.VISIBLE

        val isRunning = FloatingHudService.isRunning
        switchPerfHudToggle.isChecked = isRunning
        tvPerfHudStatusDesc.text = if (isRunning) "Status: Active (On-Screen)" else "Status: Inactive (Hidden)"

        switchPerfHudToggle.setOnClickListener {
            FloatingHudService.toggle(this)
            val active = FloatingHudService.isRunning
            switchPerfHudToggle.isChecked = active
            tvPerfHudStatusDesc.text = if (active) "Status: Active (On-Screen)" else "Status: Inactive (Hidden)"
            HudTileService.updateTile(this)
            val msg = if (active) "Floating HUD Enabled" else "Floating HUD Disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        btnOpenPerAppSettings.setOnClickListener {
            startActivity(Intent(this, PerAppActivity::class.java))
            finish()
        }
    }

    // =========================================================================
    // 4. STUDIO EQUALIZER PRESETS & NIGHT MODE
    // =========================================================================
    private fun showPresetsTab() {
        hideAllSections()
        toggleGroupViewMode.visibility = View.VISIBLE
        btnTabPresets.isChecked = true
        layoutSectionPresets.visibility = View.VISIBLE
        layoutFooterEqualizer.visibility = View.VISIBLE
        tvDialogTitle.text = "🎛️ Equalizer Presets"
    }

    private fun showNightModeTab() {
        hideAllSections()
        toggleGroupViewMode.visibility = View.VISIBLE
        btnTabNightMode.isChecked = true
        layoutSectionNightMode.visibility = View.VISIBLE
        layoutFooterEqualizer.visibility = View.VISIBLE
        tvDialogTitle.text = "🎬 Movie & Night Mode"
    }

    private fun setupPresetsSection() {
        val currentPresetName = PowerampPresetManager.getActivePresetName(this)
        tvActivePresetStatus.text = "Active: $currentPresetName"

        rvPresetList.layoutManager = LinearLayoutManager(this)
        val allPresets = PowerampPresetManager.getAllPresets(this)

        rvPresetList.adapter = object : RecyclerView.Adapter<PresetViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PresetViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_studio_preset_compact, parent, false)
                return PresetViewHolder(view)
            }

            override fun onBindViewHolder(holder: PresetViewHolder, position: Int) {
                val preset = allPresets[position]
                val isActive = preset.name.equals(PowerampPresetManager.getActivePresetName(this@StudioPresetPickerActivity), ignoreCase = true)

                holder.tvName.text = preset.name
                val bandCount = preset.bands.size
                val details = when {
                    preset.name.contains("Theater") -> "$bandCount Bands • Cinema Acoustics"
                    preset.name.contains("Harman") -> "$bandCount Bands • Audiophile Target"
                    preset.name.contains("Bass") -> "$bandCount Bands • Deep Sub-Bass"
                    else -> "$bandCount Bands • Studio Curve"
                }
                holder.tvSubtitle.text = details

                holder.ivCheck.visibility = if (isActive) View.VISIBLE else View.GONE
                holder.tvName.setTextColor(if (isActive) 0xFF00E5FF.toInt() else 0xFFF1F5F9.toInt())

                holder.itemView.setOnClickListener {
                    StudioDspManager.ensureInitialized(this@StudioPresetPickerActivity)
                    StudioDspManager.applyPreset(this@StudioPresetPickerActivity, preset)

                    // Keep QS tiles refreshed
                    StudioEqualizerTileService.updateTile(this@StudioPresetPickerActivity)
                    StudioPresetTileService.updateTile(this@StudioPresetPickerActivity)

                    Toast.makeText(this@StudioPresetPickerActivity, "🎵 Preset: ${preset.name}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }

            override fun getItemCount() = allPresets.size
        }
    }

    class PresetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvPresetItemName)
        val tvSubtitle: TextView = itemView.findViewById(R.id.tvPresetItemSubtitle)
        val ivCheck: ImageView = itemView.findViewById(R.id.ivPresetCheck)
    }

    private fun setupNightModeSection() {
        val isEnabled = PowerampPresetManager.isNightModeEnabled(this)
        val profile = PowerampPresetManager.getNightModeProfile(this)
        val boost = PowerampPresetManager.getDialogueBoostLevel(this)

        switchNightModePicker.isChecked = isEnabled
        layoutNightPickerControls.alpha = if (isEnabled) 1.0f else 0.4f

        when (profile) {
            2 -> findViewById<MaterialButton>(R.id.btnProfilePickerNightMax).isChecked = true
            3 -> findViewById<MaterialButton>(R.id.btnProfilePickerSpeech).isChecked = true
            else -> findViewById<MaterialButton>(R.id.btnProfilePickerCinema).isChecked = true
        }

        seekDialogueBoostPicker.progress = (boost * 10).toInt().coerceIn(0, 80)
        tvDialogueBoostPickerValue.text = String.format(Locale.US, "%+.1f dB", boost)

        switchNightModePicker.setOnCheckedChangeListener { _, checked ->
            val curProfile = getSelectedNightModeProfile()
            val curBoost = seekDialogueBoostPicker.progress / 10.0f
            StudioDspManager.ensureInitialized(this)
            StudioDspManager.setNightMode(this, checked, curProfile, curBoost)
            StudioNightModeTileService.updateTile(this)
            layoutNightPickerControls.alpha = if (checked) 1.0f else 0.4f

            val msg = if (checked) "Movie & Night Mode: Enabled 🎬" else "Movie Mode: Disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        toggleGroupNightProfilePicker.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newProfile = when (checkedId) {
                    R.id.btnProfilePickerNightMax -> 2
                    R.id.btnProfilePickerSpeech -> 3
                    else -> 1
                }
                val curBoost = seekDialogueBoostPicker.progress / 10.0f
                val active = switchNightModePicker.isChecked
                StudioDspManager.setNightMode(this, active, newProfile, curBoost)
                StudioNightModeTileService.updateTile(this)
            }
        }

        seekDialogueBoostPicker.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val boostVal = progress / 10.0f
                tvDialogueBoostPickerValue.text = String.format(Locale.US, "%+.1f dB", boostVal)
                if (fromUser) {
                    val curProfile = getSelectedNightModeProfile()
                    val active = switchNightModePicker.isChecked
                    StudioDspManager.setNightMode(this@StudioPresetPickerActivity, active, curProfile, boostVal)
                    StudioNightModeTileService.updateTile(this@StudioPresetPickerActivity)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun getSelectedNightModeProfile(): Int {
        return when (toggleGroupNightProfilePicker.checkedButtonId) {
            R.id.btnProfilePickerNightMax -> 2
            R.id.btnProfilePickerSpeech -> 3
            else -> 1
        }
    }

    private fun setupGlobalActions() {
        rootOverlay.setOnClickListener { finish() }
        btnClosePicker.setOnClickListener { finish() }

        btnOpenFullEqualizer.setOnClickListener {
            val intent = Intent(this, StudioEqualizerActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}
