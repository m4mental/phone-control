package com.example.phonecontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.PresetReverb
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Spinner
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.Locale
import kotlin.concurrent.thread

class StudioEqualizerActivity : AppCompatActivity() {

    private lateinit var switchMasterDsp: MaterialSwitch
    private lateinit var tvDspStatus: TextView
    private lateinit var tvActivePresetHeader: TextView
    private lateinit var tvProfileTypeBadge: TextView
    private lateinit var curveView: EqualizerCurveView
    private lateinit var chipGroupPresets: ChipGroup
    private lateinit var seekPreamp: SeekBar
    private lateinit var tvPreampValue: TextView
    private lateinit var seekToneBass: SeekBar
    private lateinit var tvToneBassValue: TextView
    private lateinit var seekToneTreble: SeekBar
    private lateinit var tvToneTrebleValue: TextView
    private lateinit var seekBassBoost: SeekBar
    private lateinit var tvBassBoostTitle: TextView
    private lateinit var seekVirtualizer: SeekBar
    private lateinit var tvVirtualizerTitle: TextView
    private lateinit var llBandsContainer: LinearLayout

    // ViPER FX Views
    private lateinit var switchSurround: MaterialSwitch
    private lateinit var seekSurround: SeekBar
    private lateinit var tvSurroundStrength: TextView
    private lateinit var layoutSurroundControls: View

    private lateinit var switchReverb: MaterialSwitch
    private lateinit var scrollReverbChips: View
    private lateinit var chipGroupReverb: ChipGroup
    private lateinit var chipReverbSmallRoom: Chip
    private lateinit var chipReverbMediumRoom: Chip
    private lateinit var chipReverbLargeRoom: Chip
    private lateinit var chipReverbMediumHall: Chip
    private lateinit var chipReverbLargeHall: Chip
    private lateinit var chipReverbPlate: Chip

    private lateinit var switchDynamicSystem: MaterialSwitch
    private lateinit var seekDynamicSystem: SeekBar
    private lateinit var tvDynamicIntensity: TextView
    private lateinit var layoutDynamicControls: View

    private lateinit var switchClarity: MaterialSwitch
    private lateinit var seekClarity: SeekBar
    private lateinit var tvClarityLevel: TextView
    private lateinit var layoutClarityControls: View

    // Acoustics & Spatial Imaging Views
    private lateinit var switchCrossfeed: MaterialSwitch
    private lateinit var seekCrossfeed: SeekBar
    private lateinit var tvCrossfeedLevel: TextView
    private lateinit var layoutCrossfeedControls: View
    private lateinit var seekChannelBalance: SeekBar
    private lateinit var tvChannelBalance: TextView
    private lateinit var switchSmartOutput: MaterialSwitch
    private lateinit var switchHeadphonesOnly: MaterialSwitch

    // Auto-Preamp Views
    private lateinit var switchAutoPreamp: MaterialSwitch
    private lateinit var tvAutoPreampSubtitle: TextView

    // Per-Device Output Routing Views
    private lateinit var tvActiveOutputDevice: TextView
    private lateinit var tvSpeakerProfilePreset: TextView
    private lateinit var btnSetSpeakerProfile: View
    private lateinit var tvBluetoothProfilePreset: TextView
    private lateinit var btnSetBluetoothProfile: View
    private lateinit var tvWiredProfilePreset: TextView
    private lateinit var btnSetWiredProfile: View

    // Movie & Night Mode Views
    private lateinit var switchNightMode: MaterialSwitch
    private lateinit var layoutNightModeControls: View
    private lateinit var toggleGroupNightMode: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var seekDialogueBoost: SeekBar
    private lateinit var tvDialogueBoostValue: TextView

    // Per-App Equalizer Views
    private lateinit var llPerAppEqContainer: LinearLayout
    private lateinit var tvNoPerAppEqRules: TextView
    private lateinit var btnAddPerAppEq: com.google.android.material.button.MaterialButton
    private lateinit var switchTargetAppsOnly: MaterialSwitch
    private var cachedInstalledApps: List<ApplicationInfo>? = null

    // Multi-Page Navigation Views
    private lateinit var viewPagerStudioEq: ViewPager2
    private lateinit var bottomNavStudioEq: BottomNavigationView
    private lateinit var btnSaveCustomPreset: View

    private var currentPreset: EqualizerPreset? = null

    private val pickJsonFileLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val jsonText = inputStream.bufferedReader().use { it.readText() }
                    processImportedJson(jsonText)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to read JSON file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_studio_equalizer)

        // Initialize Audio Engine
        StudioDspManager.init(this)

        initViews()
        setupListeners()
        loadActivePreset()
    }



    private fun initViews() {
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        switchMasterDsp = findViewById(R.id.switchMasterDsp)
        tvDspStatus = findViewById(R.id.tvDspStatus)
        viewPagerStudioEq = findViewById(R.id.viewPagerStudioEq)
        bottomNavStudioEq = findViewById(R.id.bottomNavStudioEq)

        // Inflate the 4 pages with viewPager as parent to get MATCH_PARENT LayoutParams
        val pageEq = layoutInflater.inflate(R.layout.layout_tab_eq, viewPagerStudioEq, false)
        val pageEffects = layoutInflater.inflate(R.layout.layout_tab_effects, viewPagerStudioEq, false)
        val pageSpatial = layoutInflater.inflate(R.layout.layout_tab_spatial, viewPagerStudioEq, false)
        val pageApps = layoutInflater.inflate(R.layout.layout_tab_apps_cinema, viewPagerStudioEq, false)

        val pages = listOf(pageEq, pageEffects, pageSpatial, pageApps)

        viewPagerStudioEq.offscreenPageLimit = 3
        viewPagerStudioEq.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = pages.size
            override fun getItemViewType(position: Int): Int = position
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val page = pages[viewType]
                if (page.parent != null) {
                    (page.parent as? ViewGroup)?.removeView(page)
                }
                return object : RecyclerView.ViewHolder(page) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
        }

        // Connect ViewPager2 swipe with BottomNavigationView
        viewPagerStudioEq.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val menuItemId = when (position) {
                    0 -> R.id.nav_eq
                    1 -> R.id.nav_effects
                    2 -> R.id.nav_spatial
                    3 -> R.id.nav_apps
                    else -> R.id.nav_eq
                }
                if (bottomNavStudioEq.selectedItemId != menuItemId) {
                    bottomNavStudioEq.selectedItemId = menuItemId
                }
            }
        })

        bottomNavStudioEq.setOnItemSelectedListener { item ->
            val targetPage = when (item.itemId) {
                R.id.nav_eq -> 0
                R.id.nav_effects -> 1
                R.id.nav_spatial -> 2
                R.id.nav_apps -> 3
                else -> 0
            }
            if (viewPagerStudioEq.currentItem != targetPage) {
                viewPagerStudioEq.setCurrentItem(targetPage, true)
            }
            true
        }

        // Set initial Master switch state
        val isEnabled = PowerampPresetManager.isMasterEnabled(this)
        switchMasterDsp.isChecked = isEnabled
        updateMasterStatusText(isEnabled)

        // --- Bind Tab 1: Equalizer & Presets ---
        tvActivePresetHeader = pageEq.findViewById(R.id.tvActivePresetHeader)
        tvProfileTypeBadge = pageEq.findViewById(R.id.tvProfileTypeBadge)
        curveView = pageEq.findViewById(R.id.curveView)
        chipGroupPresets = pageEq.findViewById(R.id.chipGroupPresets)
        btnSaveCustomPreset = pageEq.findViewById(R.id.btnSaveCustomPreset)
        llBandsContainer = pageEq.findViewById(R.id.llBandsContainer)

        // --- Bind Tab 2: Effects & Tone ---
        seekPreamp = pageEffects.findViewById(R.id.seekPreamp)
        tvPreampValue = pageEffects.findViewById(R.id.tvPreampValue)
        seekToneBass = pageEffects.findViewById(R.id.seekToneBass)
        tvToneBassValue = pageEffects.findViewById(R.id.tvToneBassValue)
        seekToneTreble = pageEffects.findViewById(R.id.seekToneTreble)
        tvToneTrebleValue = pageEffects.findViewById(R.id.tvToneTrebleValue)

        seekBassBoost = pageEffects.findViewById(R.id.seekBassBoost)
        tvBassBoostTitle = pageEffects.findViewById(R.id.tvBassBoostTitle)
        seekVirtualizer = pageEffects.findViewById(R.id.seekVirtualizer)
        tvVirtualizerTitle = pageEffects.findViewById(R.id.tvVirtualizerTitle)

        // Set Bass Boost & Virtualizer
        val bb = PowerampPresetManager.getBassBoostStrength(this)
        seekBassBoost.progress = bb
        tvBassBoostTitle.text = "Bass Boost: ${bb / 10}%"

        val virt = PowerampPresetManager.getVirtualizerStrength(this)
        seekVirtualizer.progress = virt
        tvVirtualizerTitle.text = "3D Virtualizer: ${virt / 10}%"

        // ViPER FX Suite Views
        switchSurround = pageEffects.findViewById(R.id.switchSurround)
        seekSurround = pageEffects.findViewById(R.id.seekSurround)
        tvSurroundStrength = pageEffects.findViewById(R.id.tvSurroundStrength)
        layoutSurroundControls = pageEffects.findViewById(R.id.layoutSurroundControls)

        switchReverb = pageEffects.findViewById(R.id.switchReverb)
        scrollReverbChips = pageEffects.findViewById(R.id.scrollReverbChips)
        chipGroupReverb = pageEffects.findViewById(R.id.chipGroupReverb)
        chipReverbSmallRoom = pageEffects.findViewById(R.id.chipReverbSmallRoom)
        chipReverbMediumRoom = pageEffects.findViewById(R.id.chipReverbMediumRoom)
        chipReverbLargeRoom = pageEffects.findViewById(R.id.chipReverbLargeRoom)
        chipReverbMediumHall = pageEffects.findViewById(R.id.chipReverbMediumHall)
        chipReverbLargeHall = pageEffects.findViewById(R.id.chipReverbLargeHall)
        chipReverbPlate = pageEffects.findViewById(R.id.chipReverbPlate)

        switchDynamicSystem = pageEffects.findViewById(R.id.switchDynamicSystem)
        seekDynamicSystem = pageEffects.findViewById(R.id.seekDynamicSystem)
        tvDynamicIntensity = pageEffects.findViewById(R.id.tvDynamicIntensity)
        layoutDynamicControls = pageEffects.findViewById(R.id.layoutDynamicControls)

        switchClarity = pageEffects.findViewById(R.id.switchClarity)
        seekClarity = pageEffects.findViewById(R.id.seekClarity)
        tvClarityLevel = pageEffects.findViewById(R.id.tvClarityLevel)
        layoutClarityControls = pageEffects.findViewById(R.id.layoutClarityControls)

        // Auto-Preamp Views
        switchAutoPreamp = pageEffects.findViewById(R.id.switchAutoPreamp)
        tvAutoPreampSubtitle = pageEffects.findViewById(R.id.tvAutoPreampSubtitle)
        switchAutoPreamp.isChecked = PowerampPresetManager.isAutoPreampEnabled(this)
        updateAutoPreampSubtitle()

        // Populate Effects initial states
        val surroundOn = PowerampPresetManager.isSurroundEnabled(this)
        val surroundStr = PowerampPresetManager.getSurroundStrength(this)
        switchSurround.isChecked = surroundOn
        seekSurround.progress = surroundStr
        tvSurroundStrength.text = "Level: ${surroundStr / 10}%"
        layoutSurroundControls.alpha = if (surroundOn) 1.0f else 0.4f

        val reverbOn = PowerampPresetManager.isReverbEnabled(this)
        val reverbPreset = PowerampPresetManager.getReverbPreset(this)
        switchReverb.isChecked = reverbOn
        scrollReverbChips.alpha = if (reverbOn) 1.0f else 0.4f
        when (reverbPreset) {
            PresetReverb.PRESET_SMALLROOM -> chipReverbSmallRoom.isChecked = true
            PresetReverb.PRESET_MEDIUMROOM -> chipReverbMediumRoom.isChecked = true
            PresetReverb.PRESET_LARGEROOM -> chipReverbLargeRoom.isChecked = true
            PresetReverb.PRESET_MEDIUMHALL -> chipReverbMediumHall.isChecked = true
            PresetReverb.PRESET_LARGEHALL -> chipReverbLargeHall.isChecked = true
            PresetReverb.PRESET_PLATE -> chipReverbPlate.isChecked = true
            else -> chipReverbMediumRoom.isChecked = true
        }

        val dynamicOn = PowerampPresetManager.isDynamicSystemEnabled(this)
        val dynamicStr = PowerampPresetManager.getDynamicSystemIntensity(this)
        switchDynamicSystem.isChecked = dynamicOn
        seekDynamicSystem.progress = dynamicStr
        tvDynamicIntensity.text = "Drive: ${dynamicStr / 10}%"
        layoutDynamicControls.alpha = if (dynamicOn) 1.0f else 0.4f

        val clarityOn = PowerampPresetManager.isClarityEnabled(this)
        val clarityLvl = PowerampPresetManager.getClarityLevel(this)
        switchClarity.isChecked = clarityOn
        seekClarity.progress = clarityLvl
        tvClarityLevel.text = "Level: ${clarityLvl / 10}%"
        layoutClarityControls.alpha = if (clarityOn) 1.0f else 0.4f

        // --- Bind Tab 3: Spatial & Devices ---
        switchCrossfeed = pageSpatial.findViewById(R.id.switchCrossfeed)
        seekCrossfeed = pageSpatial.findViewById(R.id.seekCrossfeed)
        tvCrossfeedLevel = pageSpatial.findViewById(R.id.tvCrossfeedLevel)
        layoutCrossfeedControls = pageSpatial.findViewById(R.id.layoutCrossfeedControls)
        seekChannelBalance = pageSpatial.findViewById(R.id.seekChannelBalance)
        tvChannelBalance = pageSpatial.findViewById(R.id.tvChannelBalance)
        switchSmartOutput = pageSpatial.findViewById(R.id.switchSmartOutput)

        val crossfeedOn = PowerampPresetManager.isCrossfeedEnabled(this)
        val crossfeedLvl = PowerampPresetManager.getCrossfeedLevel(this)
        switchCrossfeed.isChecked = crossfeedOn
        seekCrossfeed.progress = crossfeedLvl
        tvCrossfeedLevel.text = "Level: ${crossfeedLvl / 10}%"
        layoutCrossfeedControls.alpha = if (crossfeedOn) 1.0f else 0.4f

        val balance = PowerampPresetManager.getChannelBalance(this)
        seekChannelBalance.progress = balance + 100
        updateBalanceText(balance)

        val smartRoutingOn = PowerampPresetManager.isPerDeviceRoutingEnabled(this)
        switchSmartOutput.isChecked = smartRoutingOn

        switchHeadphonesOnly = pageSpatial.findViewById(R.id.switchHeadphonesOnly)
        switchHeadphonesOnly.isChecked = PowerampPresetManager.isHeadphonesOnlyMode(this)

        tvActiveOutputDevice = pageSpatial.findViewById(R.id.tvActiveOutputDevice)
        tvSpeakerProfilePreset = pageSpatial.findViewById(R.id.tvSpeakerProfilePreset)
        btnSetSpeakerProfile = pageSpatial.findViewById(R.id.btnSetSpeakerProfile)
        tvBluetoothProfilePreset = pageSpatial.findViewById(R.id.tvBluetoothProfilePreset)
        btnSetBluetoothProfile = pageSpatial.findViewById(R.id.btnSetBluetoothProfile)
        tvWiredProfilePreset = pageSpatial.findViewById(R.id.tvWiredProfilePreset)
        btnSetWiredProfile = pageSpatial.findViewById(R.id.btnSetWiredProfile)
        refreshDeviceRoutingUi()

        // --- Bind Tab 4: Apps & Cinema ---
        switchNightMode = pageApps.findViewById(R.id.switchNightMode)
        layoutNightModeControls = pageApps.findViewById(R.id.layoutNightModeControls)
        toggleGroupNightMode = pageApps.findViewById(R.id.toggleGroupNightMode)
        seekDialogueBoost = pageApps.findViewById(R.id.seekDialogueBoost)
        tvDialogueBoostValue = pageApps.findViewById(R.id.tvDialogueBoostValue)

        val nightOn = PowerampPresetManager.isNightModeEnabled(this)
        switchNightMode.isChecked = nightOn
        layoutNightModeControls.alpha = if (nightOn) 1.0f else 0.4f

        val nightProfile = PowerampPresetManager.getNightModeProfile(this)
        when (nightProfile) {
            1 -> toggleGroupNightMode.check(R.id.btnProfileCinema)
            2 -> toggleGroupNightMode.check(R.id.btnProfileNightMax)
            3 -> toggleGroupNightMode.check(R.id.btnProfileSpeech)
            else -> toggleGroupNightMode.check(R.id.btnProfileCinema)
        }

        val dialogueLvl = PowerampPresetManager.getDialogueBoostLevel(this)
        seekDialogueBoost.progress = (dialogueLvl * 10).toInt().coerceIn(0, 80)
        tvDialogueBoostValue.text = String.format(Locale.US, "%+.1f dB", dialogueLvl)

        // Per-App Sound Profiles Views
        llPerAppEqContainer = pageApps.findViewById(R.id.llPerAppEqContainer)
        tvNoPerAppEqRules = pageApps.findViewById(R.id.tvNoPerAppEqRules)
        btnAddPerAppEq = pageApps.findViewById(R.id.btnAddPerAppEq)
        btnAddPerAppEq.setOnClickListener { showPerAppPicker() }
        switchTargetAppsOnly = pageApps.findViewById(R.id.switchTargetAppsOnly)
        switchTargetAppsOnly.isChecked = PowerampPresetManager.isTargetAppsOnlyMode(this)
        loadPerAppRules()
    }

    private fun updateBalanceText(balance: Int) {
        tvChannelBalance.text = when {
            balance == 0 -> "Center (0)"
            balance < 0 -> "L +${-balance}%"
            else -> "R +${balance}%"
        }
    }

    private fun setupListeners() {
        switchMasterDsp.setOnCheckedChangeListener { _, isChecked ->
            StudioDspManager.setMasterEnabled(this, isChecked)
            updateMasterStatusText(isChecked)
            Toast.makeText(this, if (isChecked) "Studio DSP Active 🎧" else "Studio DSP Disabled", Toast.LENGTH_SHORT).show()
        }

        findViewById<ImageView>(R.id.btnAutoEq).setOnClickListener {
            showAutoEqSearchDialog()
        }

        findViewById<ImageView>(R.id.btnImportJson).setOnClickListener {
            showImportJsonDialog()
        }

        btnSaveCustomPreset.setOnClickListener {
            showSavePresetDialog()
        }

        // Preamp SeekBar: -12.0dB to +12.0dB
        seekPreamp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onUserModifiedPreset()
                    val gainDb = (progress - 120) / 10.0f
                    tvPreampValue.text = String.format(Locale.US, "%+.1f dB", gainDb)
                    currentPreset?.preamp = gainDb
                    StudioDspManager.setPreampGain(gainDb)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Tone Bass Shelf (90Hz): -12.0dB to +12.0dB
        seekToneBass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onUserModifiedPreset()
                    val gainDb = (progress - 120) / 10.0f
                    tvToneBassValue.text = String.format(Locale.US, "%+.1f dB", gainDb)
                    currentPreset?.let { preset ->
                        var shelf = preset.bands.find { it.type == 0 }
                        if (shelf == null) {
                            shelf = EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = gainDb)
                            preset.bands.add(0, shelf)
                        } else {
                            shelf.gain = gainDb
                        }
                        curveView.setBands(preset.bands)
                        StudioDspManager.applyPreset(this@StudioEqualizerActivity, preset)
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Tone Treble Shelf (10kHz): -12.0dB to +12.0dB
        seekToneTreble.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onUserModifiedPreset()
                    val gainDb = (progress - 120) / 10.0f
                    tvToneTrebleValue.text = String.format(Locale.US, "%+.1f dB", gainDb)
                    currentPreset?.let { preset ->
                        var shelf = preset.bands.find { it.type == 1 }
                        if (shelf == null) {
                            shelf = EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = gainDb)
                            preset.bands.add(shelf)
                        } else {
                            shelf.gain = gainDb
                        }
                        curveView.setBands(preset.bands)
                        StudioDspManager.applyPreset(this@StudioEqualizerActivity, preset)
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Bass Boost
        seekBassBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvBassBoostTitle.text = "Bass Boost: ${progress / 10}%"
                    StudioDspManager.setBassBoost(this@StudioEqualizerActivity, progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 3D Virtualizer
        seekVirtualizer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvVirtualizerTitle.text = "3D Virtualizer: ${progress / 10}%"
                    StudioDspManager.setVirtualizer(this@StudioEqualizerActivity, progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // --- Differential Surround ---
        switchSurround.setOnCheckedChangeListener { _, isChecked ->
            layoutSurroundControls.alpha = if (isChecked) 1.0f else 0.4f
            currentPreset?.let {
                if (it.surroundEnabled != isChecked) {
                    onUserModifiedPreset()
                    it.surroundEnabled = isChecked
                }
            }
            StudioDspManager.setDifferentialSurround(this, isChecked, seekSurround.progress)
        }
        seekSurround.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onUserModifiedPreset()
                    currentPreset?.surroundStrength = progress
                    tvSurroundStrength.text = "Level: ${progress / 10}%"
                    StudioDspManager.setDifferentialSurround(this@StudioEqualizerActivity, switchSurround.isChecked, progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // --- Reverberation ---
        switchReverb.setOnCheckedChangeListener { _, isChecked ->
            scrollReverbChips.alpha = if (isChecked) 1.0f else 0.4f
            currentPreset?.let {
                if (it.reverbEnabled != isChecked) {
                    onUserModifiedPreset()
                    it.reverbEnabled = isChecked
                }
            }
            StudioDspManager.setReverberation(this, isChecked, getSelectedReverbPreset())
        }
        chipGroupReverb.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val preset = getSelectedReverbPreset()
                currentPreset?.let {
                    if (it.reverbPreset != preset) {
                        onUserModifiedPreset()
                        it.reverbPreset = preset
                    }
                }
                StudioDspManager.setReverberation(this, switchReverb.isChecked, preset)
            }
        }

        // --- Dynamic System ---
        switchDynamicSystem.setOnCheckedChangeListener { _, isChecked ->
            layoutDynamicControls.alpha = if (isChecked) 1.0f else 0.4f
            currentPreset?.let {
                if (it.dynamicSystemEnabled != isChecked) {
                    onUserModifiedPreset()
                    it.dynamicSystemEnabled = isChecked
                }
            }
            StudioDspManager.setDynamicSystem(this, isChecked, seekDynamicSystem.progress)
        }
        seekDynamicSystem.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onUserModifiedPreset()
                    currentPreset?.dynamicSystemIntensity = progress
                    tvDynamicIntensity.text = "Drive: ${progress / 10}%"
                    StudioDspManager.setDynamicSystem(this@StudioEqualizerActivity, switchDynamicSystem.isChecked, progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // --- ViPER Audio Clarity ---
        switchClarity.setOnCheckedChangeListener { _, isChecked ->
            layoutClarityControls.alpha = if (isChecked) 1.0f else 0.4f
            currentPreset?.let {
                if (it.clarityEnabled != isChecked) {
                    onUserModifiedPreset()
                    it.clarityEnabled = isChecked
                }
            }
            StudioDspManager.setAudioClarity(this, isChecked, seekClarity.progress)
        }
        seekClarity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onUserModifiedPreset()
                    currentPreset?.clarityLevel = progress
                    tvClarityLevel.text = "Level: ${progress / 10}%"
                    StudioDspManager.setAudioClarity(this@StudioEqualizerActivity, switchClarity.isChecked, progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // --- Bauer Stereo Crossfeed ---
        switchCrossfeed.setOnCheckedChangeListener { _, isChecked ->
            layoutCrossfeedControls.alpha = if (isChecked) 1.0f else 0.4f
            currentPreset?.let {
                if (it.crossfeedEnabled != isChecked) {
                    onUserModifiedPreset()
                    it.crossfeedEnabled = isChecked
                }
            }
            StudioDspManager.setCrossfeed(this, isChecked, seekCrossfeed.progress)
        }
        seekCrossfeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onUserModifiedPreset()
                    currentPreset?.crossfeedLevel = progress
                    tvCrossfeedLevel.text = "Level: ${progress / 10}%"
                    StudioDspManager.setCrossfeed(this@StudioEqualizerActivity, switchCrossfeed.isChecked, progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // --- Channel Balance ---
        seekChannelBalance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val balance = progress - 100
                    updateBalanceText(balance)
                    currentPreset?.channelBalance = balance / 100.0f
                    StudioDspManager.setChannelBalance(this@StudioEqualizerActivity, balance)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // --- Auto-Preamp Headroom Balancer ---
        switchAutoPreamp.setOnCheckedChangeListener { _, isChecked ->
            StudioDspManager.setAutoPreampEnabled(this, isChecked)
            updateAutoPreampSubtitle()
            val msg = if (isChecked) "Auto-Preamp Guard: Active (Anti-Clipping)" else "Auto-Preamp: Disabled (Manual Mode)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // --- Per-Device Output Routing ---
        switchSmartOutput.setOnCheckedChangeListener { _, isChecked ->
            PowerampPresetManager.setSmartOutputSwitchEnabled(this, isChecked)
            PowerampPresetManager.setPerDeviceRoutingEnabled(this, isChecked)
            if (isChecked) {
                val currentType = StudioDspManager.getCurrentAudioOutputType(this)
                val assignedName = PowerampPresetManager.getDevicePresetName(this, currentType)
                val preset = PowerampPresetManager.getPresetByName(this, assignedName)
                if (preset != null) {
                    applySelectedPreset(preset)
                    rebuildPresetChips(preset.name)
                }
            }
            Toast.makeText(this, if (isChecked) "Device Output Profiles: Active" else "Device Profiles: Disabled", Toast.LENGTH_SHORT).show()
        }

        switchHeadphonesOnly.setOnCheckedChangeListener { _, isChecked ->
            PowerampPresetManager.setHeadphonesOnlyMode(this, isChecked)
            sendBroadcast(Intent("com.example.phonecontrol.UPDATE_UI").setPackage(packageName))
            updateMasterStatusText()
            val msg = if (isChecked) "Headphones Only: Equalizer auto-disabled on phone speaker 🔇" else "Equalizer Active on All Outputs"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        switchTargetAppsOnly.setOnCheckedChangeListener { _, isChecked ->
            PowerampPresetManager.setTargetAppsOnlyMode(this, isChecked)
            sendBroadcast(Intent("com.example.phonecontrol.UPDATE_UI").setPackage(packageName))
            updateMasterStatusText()
            val msg = if (isChecked) "Targeted Apps Only: Active (Whitelisted Apps) 🎯" else "Global Equalizer: Active on All Apps"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        btnSetSpeakerProfile.setOnClickListener {
            showSelectDevicePresetDialog(PowerampPresetManager.AudioOutputType.SPEAKER)
        }
        btnSetBluetoothProfile.setOnClickListener {
            showSelectDevicePresetDialog(PowerampPresetManager.AudioOutputType.BLUETOOTH)
        }
        btnSetWiredProfile.setOnClickListener {
            showSelectDevicePresetDialog(PowerampPresetManager.AudioOutputType.WIRED)
        }

        // --- Movie & Night Mode ---
        switchNightMode.setOnCheckedChangeListener { _, isChecked ->
            val profile = getSelectedNightModeProfile()
            val boost = seekDialogueBoost.progress / 10.0f
            StudioDspManager.setNightMode(this, isChecked, profile, boost)
            layoutNightModeControls.alpha = if (isChecked) 1.0f else 0.4f
            val msg = if (isChecked) "Movie & Night Mode: Enabled 🎬" else "Movie Mode: Disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        toggleGroupNightMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val profile = when (checkedId) {
                    R.id.btnProfileCinema -> 1
                    R.id.btnProfileNightMax -> 2
                    R.id.btnProfileSpeech -> 3
                    else -> 1
                }
                val boost = seekDialogueBoost.progress / 10.0f
                val enabled = switchNightMode.isChecked
                StudioDspManager.setNightMode(this, enabled, profile, boost)
            }
        }

        seekDialogueBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val boost = progress / 10.0f
                tvDialogueBoostValue.text = String.format(Locale.US, "%+.1f dB", boost)
                if (fromUser) {
                    val profile = getSelectedNightModeProfile()
                    val enabled = switchNightMode.isChecked
                    StudioDspManager.setNightMode(this@StudioEqualizerActivity, enabled, profile, boost)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun showAutoEqSearchDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_autoeq_search, null)
        val etSearch = dialogView.findViewById<TextInputEditText>(R.id.etAutoEqSearch)
        val rvList = dialogView.findViewById<RecyclerView>(R.id.rvAutoEqList)
        val btnClose = dialogView.findViewById<View>(R.id.btnCloseAutoEq)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        val adapter = AutoEqAdapter(AutoEqManager.POPULAR_HEADPHONES) { headphone ->
            applySelectedPreset(headphone.preset)
            rebuildPresetChips(headphone.preset.name)
            Toast.makeText(this, "Calibrated: ${headphone.model} 🎧", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        rvList.layoutManager = LinearLayoutManager(this)
        rvList.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val results = AutoEqManager.searchHeadphones(s?.toString() ?: "")
                adapter.updateList(results)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun getSelectedReverbPreset(): Short {
        return when (chipGroupReverb.checkedChipId) {
            R.id.chipReverbSmallRoom -> PresetReverb.PRESET_SMALLROOM
            R.id.chipReverbMediumRoom -> PresetReverb.PRESET_MEDIUMROOM
            R.id.chipReverbLargeRoom -> PresetReverb.PRESET_LARGEROOM
            R.id.chipReverbMediumHall -> PresetReverb.PRESET_MEDIUMHALL
            R.id.chipReverbLargeHall -> PresetReverb.PRESET_LARGEHALL
            R.id.chipReverbPlate -> PresetReverb.PRESET_PLATE
            else -> PresetReverb.PRESET_MEDIUMROOM
        }
    }

    private fun onUserModifiedPreset() {
        val active = currentPreset ?: return
        if (PowerampPresetManager.isBuiltInPreset(active.name)) {
            active.name = "${active.name} (Custom)"
            tvActivePresetHeader.text = active.name
            tvProfileTypeBadge.text = "CUSTOM (UNSAVED)"
            tvProfileTypeBadge.setTextColor(android.graphics.Color.parseColor("#FB923C"))
        }
    }

    private fun updateMasterStatusText(enabled: Boolean = PowerampPresetManager.isMasterEnabled(this)) {
        if (!enabled) {
            tvDspStatus.text = "Poweramp DSP Engine • Disabled"
            tvDspStatus.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            return
        }

        val isHeadphonesOnly = PowerampPresetManager.isHeadphonesOnlyMode(this)
        val currentOutput = StudioDspManager.getCurrentAudioOutputType(this)
        val isSpeaker = currentOutput == PowerampPresetManager.AudioOutputType.SPEAKER

        if (isHeadphonesOnly && isSpeaker) {
            tvDspStatus.text = "DSP Sleeping • Speaker (Headphones Only Mode)"
            tvDspStatus.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
            return
        }

        val isTargetAppsOnly = PowerampPresetManager.isTargetAppsOnlyMode(this)
        if (isTargetAppsOnly) {
            val targetedRules = PowerampPresetManager.getAllAppPresets(this)
            val perAppConfigs = PerAppManager.getAllConfigs(this)
            val targetedPkgs = (targetedRules.keys + perAppConfigs.keys.filter { pkg ->
                val cfg = PerAppManager.getConfig(this, pkg)
                val p = cfg?.eqPreset
                !p.isNullOrBlank() && p != "Default" && p != "Default (System)"
            }).toSet()

            val activeAudioPkgs = FreezerManager.getActivePlayingAudioPackages(this)
            val playingTargetPkg = activeAudioPkgs.firstOrNull { targetedPkgs.contains(it) }

            if (playingTargetPkg != null) {
                val presetName = PowerampPresetManager.getAppPreset(this, playingTargetPkg)
                    ?: PerAppManager.getConfig(this, playingTargetPkg)?.eqPreset
                    ?: PowerampPresetManager.getActivePresetName(this)
                tvDspStatus.text = "Target App Active • $presetName"
                tvDspStatus.setTextColor(android.graphics.Color.parseColor("#00E5FF"))
            } else {
                tvDspStatus.text = "DSP Sleeping • Waiting for Target App"
                tvDspStatus.setTextColor(android.graphics.Color.parseColor("#C084FC"))
            }
            return
        }

        if (StudioDspManager.isCurrentlyMasterActive()) {
            val activeName = PowerampPresetManager.getActivePresetName(this)
            tvDspStatus.text = "Poweramp DSP Engine • $activeName"
            tvDspStatus.setTextColor(android.graphics.Color.parseColor("#00E5FF"))
        } else {
            tvDspStatus.text = "Poweramp DSP Engine • Standby"
            tvDspStatus.setTextColor(android.graphics.Color.parseColor("#38BDF8"))
        }
    }

    private fun loadActivePreset() {
        val activeName = PowerampPresetManager.getActivePresetName(this)
        rebuildPresetChips(activeName)

        val preset = PowerampPresetManager.getPresetByName(this, activeName)
        if (preset != null) {
            applySelectedPreset(preset)
        }
    }

    private fun rebuildPresetChips(selectedName: String) {
        chipGroupPresets.removeAllViews()

        // 1. AutoEQ Quick Launcher Chip
        val autoEqChip = Chip(this).apply {
            text = "🎧 AutoEQ Calibration"
            isCheckable = false
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4C1D95"))
            setTextColor(android.graphics.Color.parseColor("#E9D5FF"))
            setOnClickListener {
                showAutoEqSearchDialog()
            }
        }
        chipGroupPresets.addView(autoEqChip)

        val allPresets = PowerampPresetManager.getAllPresets(this)

        for (preset in allPresets) {
            val isBuiltIn = PowerampPresetManager.isBuiltInPreset(preset.name)
            val chip = Chip(this).apply {
                text = if (isBuiltIn) preset.name else "★ ${preset.name}"
                isCheckable = true
                isChecked = preset.name.equals(selectedName, ignoreCase = true)
                val bgColor = if (!isBuiltIn) "#2D1B36" else "#1E293B"
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(bgColor))
                setTextColor(if (isChecked) getColor(android.R.color.white) else getColor(android.R.color.darker_gray))
                setOnClickListener {
                    applySelectedPreset(preset)
                    rebuildPresetChips(preset.name)
                }
            }
            chipGroupPresets.addView(chip)
        }
    }

    private fun applySelectedPreset(preset: EqualizerPreset) {
        val workingPreset = preset.deepCopy()
        currentPreset = workingPreset
        StudioDspManager.applyPreset(this, workingPreset)

        tvActivePresetHeader.text = workingPreset.name
        val isBuiltIn = PowerampPresetManager.isBuiltInPreset(workingPreset.name)
        if (isBuiltIn) {
            tvProfileTypeBadge.text = if (workingPreset.parametric) "PARAMETRIC BIQUAD" else "${workingPreset.bands.size}-BAND GRAPHIC"
            tvProfileTypeBadge.setTextColor(android.graphics.Color.parseColor("#00E5FF"))
        } else {
            tvProfileTypeBadge.text = "CUSTOM PROFILE"
            tvProfileTypeBadge.setTextColor(android.graphics.Color.parseColor("#C084FC"))
        }

        // Set Preamp UI
        val preampProgress = (workingPreset.preamp * 10.0f + 120).toInt().coerceIn(0, 240)
        seekPreamp.progress = preampProgress
        tvPreampValue.text = String.format(Locale.US, "%+.1f dB", workingPreset.preamp)

        // Set Tone Shelves UI
        val bassShelf = workingPreset.bands.find { it.type == 0 }?.gain ?: 0.0f
        seekToneBass.progress = (bassShelf * 10.0f + 120).toInt().coerceIn(0, 240)
        tvToneBassValue.text = String.format(Locale.US, "%+.1f dB", bassShelf)

        val trebleShelf = workingPreset.bands.find { it.type == 1 }?.gain ?: 0.0f
        seekToneTreble.progress = (trebleShelf * 10.0f + 120).toInt().coerceIn(0, 240)
        tvToneTrebleValue.text = String.format(Locale.US, "%+.1f dB", trebleShelf)

        // Update Frequency Curve Canvas
        curveView.setBands(workingPreset.bands)

        // Sync ViPER FX Suite UI & DSP precisely to this preset's profile!
        // 1. Differential Surround
        switchSurround.isChecked = workingPreset.surroundEnabled
        seekSurround.progress = workingPreset.surroundStrength
        tvSurroundStrength.text = "Level: ${workingPreset.surroundStrength / 10}%"
        layoutSurroundControls.alpha = if (workingPreset.surroundEnabled) 1.0f else 0.4f
        StudioDspManager.setDifferentialSurround(this, workingPreset.surroundEnabled, workingPreset.surroundStrength)

        // 2. Reverberation
        switchReverb.isChecked = workingPreset.reverbEnabled
        scrollReverbChips.alpha = if (workingPreset.reverbEnabled) 1.0f else 0.4f
        when (workingPreset.reverbPreset.toInt()) {
            1 -> chipReverbSmallRoom.isChecked = true
            2 -> chipReverbMediumRoom.isChecked = true
            3 -> chipReverbLargeRoom.isChecked = true
            4 -> chipReverbMediumHall.isChecked = true
            5 -> chipReverbLargeHall.isChecked = true
            6 -> chipReverbPlate.isChecked = true
            else -> chipReverbMediumRoom.isChecked = true
        }
        StudioDspManager.setReverberation(this, workingPreset.reverbEnabled, workingPreset.reverbPreset)

        // 3. Dynamic System
        switchDynamicSystem.isChecked = workingPreset.dynamicSystemEnabled
        seekDynamicSystem.progress = workingPreset.dynamicSystemIntensity
        tvDynamicIntensity.text = "Drive: ${workingPreset.dynamicSystemIntensity / 10}%"
        layoutDynamicControls.alpha = if (workingPreset.dynamicSystemEnabled) 1.0f else 0.4f
        StudioDspManager.setDynamicSystem(this, workingPreset.dynamicSystemEnabled, workingPreset.dynamicSystemIntensity)

        // 4. ViPER Audio Clarity
        switchClarity.isChecked = workingPreset.clarityEnabled
        seekClarity.progress = workingPreset.clarityLevel
        tvClarityLevel.text = "Level: ${workingPreset.clarityLevel / 10}%"
        layoutClarityControls.alpha = if (workingPreset.clarityEnabled) 1.0f else 0.4f
        StudioDspManager.setAudioClarity(this, workingPreset.clarityEnabled, workingPreset.clarityLevel)

        // 5. Bauer Stereo Crossfeed
        switchCrossfeed.isChecked = workingPreset.crossfeedEnabled
        seekCrossfeed.progress = workingPreset.crossfeedLevel
        tvCrossfeedLevel.text = "Level: ${workingPreset.crossfeedLevel / 10}%"
        layoutCrossfeedControls.alpha = if (workingPreset.crossfeedEnabled) 1.0f else 0.4f
        StudioDspManager.setCrossfeed(this, workingPreset.crossfeedEnabled, workingPreset.crossfeedLevel)

        // 6. Channel Balance
        val balance = (workingPreset.channelBalance * 100).toInt().coerceIn(-100, 100)
        seekChannelBalance.progress = balance + 100
        updateBalanceText(balance)
        StudioDspManager.setChannelBalance(this, balance)

        // Build Multi-Band Vertical Sliders
        buildBandSliders(workingPreset)
        updateAutoPreampSubtitle()
    }

    private fun buildBandSliders(preset: EqualizerPreset) {
        llBandsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        // Peaking bands
        val peakingBands = preset.bands.filter { it.type != 0 && it.type != 1 }

        for (band in peakingBands) {
            val view = inflater.inflate(R.layout.item_equalizer_band, llBandsContainer, false)
            val tvFreq = view.findViewById<TextView>(R.id.tvBandFreq)
            val tvGain = view.findViewById<TextView>(R.id.tvBandGain)
            val seekGain = view.findViewById<SeekBar>(R.id.seekBand)

            // Frequency label formatting (e.g. 31Hz, 1kHz, 16kHz)
            tvFreq.text = formatFrequencyLabel(band.frequency)
            tvGain.text = String.format(Locale.US, "%+.1f", band.gain)

            // SeekBar is 0 to 300, center is 150 (0.0dB)
            val progress = (band.gain * 10.0f + 150).toInt().coerceIn(0, 300)
            seekGain.progress = progress

            seekGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (fromUser) {
                        onUserModifiedPreset()
                        val gainDb = (prog - 150) / 10.0f
                        band.gain = gainDb
                        tvGain.text = String.format(Locale.US, "%+.1f", gainDb)

                        // Update visualizer curve & audio DSP
                        curveView.setBands(preset.bands)
                        StudioDspManager.applyPreset(this@StudioEqualizerActivity, preset)
                        updateAutoPreampSubtitle()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            llBandsContainer.addView(view)
        }
    }

    private fun formatFrequencyLabel(freqHz: Int): String {
        return if (freqHz >= 1000) {
            val khz = freqHz / 1000.0
            if (khz == khz.toLong().toDouble()) "${khz.toLong()}k" else String.format(Locale.US, "%.1fk", khz)
        } else {
            "${freqHz}Hz"
        }
    }

    private fun showImportJsonDialog() {
        val items = arrayOf("📂 Select JSON File (Storage)", "📝 Paste Poweramp JSON String", "📤 Export Current Preset")
        MaterialAlertDialogBuilder(this)
            .setTitle("Poweramp Profile Hub")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickJsonFileLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    1 -> showPasteJsonDialog()
                    2 -> currentPreset?.let { showExportDialog(it) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPasteJsonDialog() {
        val editText = EditText(this).apply {
            hint = "Paste [{ \"name\": \"...\", \"bands\": [...] }] here"
            minLines = 6
            setPadding(32, 32, 32, 32)
            setTextColor(getColor(android.R.color.white))
            setBackgroundColor(android.graphics.Color.parseColor("#0F172A"))
        }

        val container = FrameLayout(this).apply {
            setPadding(40, 20, 40, 10)
            addView(editText)
        }

        AlertDialog.Builder(this)
            .setTitle("Import Poweramp JSON")
            .setView(container)
            .setPositiveButton("Import") { _, _ ->
                val text = editText.text.toString().trim()
                if (text.isNotBlank()) {
                    processImportedJson(text)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun processImportedJson(jsonText: String) {
        val presets = PowerampPresetManager.parsePowerampJson(jsonText)
        if (presets.isEmpty()) {
            Toast.makeText(this, "Could not parse valid Poweramp preset JSON", Toast.LENGTH_LONG).show()
            return
        }

        for (preset in presets) {
            PowerampPresetManager.saveCustomPreset(this, preset)
        }

        val first = presets.first()
        rebuildPresetChips(first.name)
        applySelectedPreset(first)
        Toast.makeText(this, "Imported ${presets.size} preset(s) successfully! 🎶", Toast.LENGTH_SHORT).show()
    }

    private fun showExportDialog(preset: EqualizerPreset) {
        val json = PowerampPresetManager.exportPresetToJson(preset)
        val editText = EditText(this).apply {
            setText(json)
            minLines = 8
            isFocusable = true
            isClickable = true
            setPadding(32, 32, 32, 32)
            setTextColor(getColor(android.R.color.white))
            setBackgroundColor(android.graphics.Color.parseColor("#0F172A"))
        }

        val container = FrameLayout(this).apply {
            setPadding(40, 20, 40, 10)
            addView(editText)
        }

        AlertDialog.Builder(this)
            .setTitle("Export JSON - ${preset.name}")
            .setView(container)
            .setPositiveButton("Copy to Clipboard") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Poweramp Preset", json)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Preset JSON copied to clipboard! 📋", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSavePresetDialog() {
        val active = currentPreset ?: return
        val defaultName = if (PowerampPresetManager.isBuiltInPreset(active.name)) "${active.name} (Custom)" else active.name
        val editText = EditText(this).apply {
            setText(defaultName)
            setSelection(defaultName.length)
            setTextColor(getColor(android.R.color.white))
        }

        val container = FrameLayout(this).apply {
            setPadding(50, 20, 50, 10)
            addView(editText)
        }

        AlertDialog.Builder(this)
            .setTitle("Save Custom Preset")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val customName = editText.text.toString().trim()
                if (customName.isNotBlank()) {
                    val toSave = active.deepCopy(customName)
                    PowerampPresetManager.saveCustomPreset(this, toSave)
                    rebuildPresetChips(customName)
                    applySelectedPreset(toSave)
                    Toast.makeText(this, "Preset '$customName' saved! 💾", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val dspUiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread {
                updateMasterStatusText()
                refreshDeviceRoutingUi()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            val filter = IntentFilter("com.example.phonecontrol.UPDATE_UI")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dspUiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(dspUiReceiver, filter)
            }
        } catch (e: Exception) {}
        val isEnabled = PowerampPresetManager.isMasterEnabled(this)
        switchMasterDsp.isChecked = isEnabled
        updateMasterStatusText(isEnabled)
        refreshDeviceRoutingUi()
        updateAutoPreampSubtitle()
        switchHeadphonesOnly.isChecked = PowerampPresetManager.isHeadphonesOnlyMode(this)
        switchTargetAppsOnly.isChecked = PowerampPresetManager.isTargetAppsOnlyMode(this)
        loadPerAppRules()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(dspUiReceiver)
        } catch (e: Exception) {}
    }

    private fun getSelectedNightModeProfile(): Int {
        return when (toggleGroupNightMode.checkedButtonId) {
            R.id.btnProfileCinema -> 1
            R.id.btnProfileNightMax -> 2
            R.id.btnProfileSpeech -> 3
            else -> 1
        }
    }

    private fun updateAutoPreampSubtitle() {
        val enabled = PowerampPresetManager.isAutoPreampEnabled(this)
        if (enabled) {
            tvAutoPreampSubtitle.text = "Studio Limiter Active • Dynamic headroom & +1.5 dB Makeup Gain"
        } else {
            tvAutoPreampSubtitle.text = "Manual preamp (Risk of clipping at 100% volume)"
        }
    }

    private fun refreshDeviceRoutingUi() {
        val currentType = StudioDspManager.getCurrentAudioOutputType(this)
        tvActiveOutputDevice.text = when (currentType) {
            PowerampPresetManager.AudioOutputType.SPEAKER -> "Phone Speaker (Active)"
            PowerampPresetManager.AudioOutputType.BLUETOOTH -> "Bluetooth Wireless (Active)"
            PowerampPresetManager.AudioOutputType.WIRED -> "Wired / USB-C DAC (Active)"
        }
        tvSpeakerProfilePreset.text = PowerampPresetManager.getDevicePresetName(this, PowerampPresetManager.AudioOutputType.SPEAKER)
        tvBluetoothProfilePreset.text = PowerampPresetManager.getDevicePresetName(this, PowerampPresetManager.AudioOutputType.BLUETOOTH)
        tvWiredProfilePreset.text = PowerampPresetManager.getDevicePresetName(this, PowerampPresetManager.AudioOutputType.WIRED)
    }

    private fun showSelectDevicePresetDialog(type: PowerampPresetManager.AudioOutputType) {
        val allPresets = PowerampPresetManager.getAllPresets(this)
        val names = allPresets.map { it.name }.toTypedArray()
        val currentAssigned = PowerampPresetManager.getDevicePresetName(this, type)
        val selectedIndex = names.indexOf(currentAssigned).coerceAtLeast(0)

        val deviceName = when (type) {
            PowerampPresetManager.AudioOutputType.SPEAKER -> "Phone Speaker"
            PowerampPresetManager.AudioOutputType.BLUETOOTH -> "Bluetooth Earphones"
            PowerampPresetManager.AudioOutputType.WIRED -> "Wired / USB-C DAC"
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Assign Profile for $deviceName")
            .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                val chosenName = names[which]
                PowerampPresetManager.setDevicePresetName(this, type, chosenName)
                refreshDeviceRoutingUi()
                if (PowerampPresetManager.isPerDeviceRoutingEnabled(this) && StudioDspManager.getCurrentAudioOutputType(this) == type) {
                    val preset = PowerampPresetManager.getPresetByName(this, chosenName)
                    if (preset != null) {
                        applySelectedPreset(preset)
                        rebuildPresetChips(preset.name)
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadPerAppRules() {
        val rules = PowerampPresetManager.getAllAppPresets(this)
        if (rules.isEmpty()) {
            tvNoPerAppEqRules.visibility = View.VISIBLE
            llPerAppEqContainer.visibility = View.GONE
            llPerAppEqContainer.removeAllViews()
            return
        }

        tvNoPerAppEqRules.visibility = View.GONE
        llPerAppEqContainer.visibility = View.VISIBLE
        llPerAppEqContainer.removeAllViews()

        val allPresets = PowerampPresetManager.getAllPresets(this)
        val presetNames = allPresets.map { it.name }
        val pm = packageManager

        for ((pkg, presetName) in rules) {
            val itemView = layoutInflater.inflate(R.layout.item_per_app_eq_rule, llPerAppEqContainer, false)
            val ivIcon = itemView.findViewById<ImageView>(R.id.ivRuleAppIcon)
            val tvName = itemView.findViewById<TextView>(R.id.tvRuleAppName)
            val tvPackage = itemView.findViewById<TextView>(R.id.tvRulePackage)
            val spinner = itemView.findViewById<Spinner>(R.id.spinnerRulePreset)
            val btnDelete = itemView.findViewById<ImageView>(R.id.btnDeleteRule)

            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                ivIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
                tvName.text = pm.getApplicationLabel(appInfo)
            } catch (e: Exception) {
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                tvName.text = pkg
            }
            tvPackage.text = pkg

            val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, presetNames) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    view.setTextColor(Color.WHITE)
                    view.textSize = 12f
                    view.maxLines = 1
                    view.ellipsize = android.text.TextUtils.TruncateAt.END
                    return view
                }

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getDropDownView(position, convertView, parent) as TextView
                    view.setTextColor(Color.WHITE)
                    view.setBackgroundColor(Color.parseColor("#1E293B"))
                    view.setPadding(24, 24, 24, 24)
                    return view
                }
            }
            spinner.adapter = adapter

            val selectedIndex = presetNames.indexOf(presetName).coerceAtLeast(0)
            spinner.setSelection(selectedIndex, false)

            var isFirstCall = true
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (isFirstCall) {
                        isFirstCall = false
                        return
                    }
                    val newPreset = presetNames[position]
                    PowerampPresetManager.setAppPreset(this@StudioEqualizerActivity, pkg, newPreset)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            btnDelete.setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Remove Audio Profile?")
                    .setMessage("Remove custom equalizer rule for ${tvName.text}?")
                    .setPositiveButton("Remove") { _, _ ->
                        PowerampPresetManager.removeAppPreset(this, pkg)
                        loadPerAppRules()
                        Toast.makeText(this, "Rule removed", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            llPerAppEqContainer.addView(itemView)
        }
    }

    private fun showPerAppPicker() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_per_app_picker, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearchApp)
        val tvAppCount = dialogView.findViewById<TextView>(R.id.tvAppCount)
        val btnClose = dialogView.findViewById<ImageView>(R.id.btnCloseDialog)
        val listView = dialogView.findViewById<ListView>(R.id.lvApps)

        val pm = packageManager

        fun getInstalledApps(): List<ApplicationInfo> {
            val flags = PackageManager.GET_META_DATA
            return pm.getInstalledApplications(flags).filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                pm.getLaunchIntentForPackage(it.packageName) != null
            }.sortedBy { pm.getApplicationLabel(it).toString().lowercase(Locale.ROOT) }
        }

        val allApps = cachedInstalledApps ?: getInstalledApps().also { cachedInstalledApps = it }
        val filteredApps = allApps.toMutableList()
        tvAppCount.text = "${filteredApps.size} apps available"

        val adapter = object : ArrayAdapter<ApplicationInfo>(this, R.layout.item_per_app_picker, filteredApps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_per_app_picker, parent, false)
                val app = getItem(position)!!
                view.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(pm.getApplicationIcon(app))
                view.findViewById<TextView>(R.id.tvAppName).text = pm.getApplicationLabel(app)
                view.findViewById<TextView>(R.id.tvPackageName).text = app.packageName
                return view
            }
        }
        listView.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filteredApps.clear()
                val query = s?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
                if (query.isEmpty()) {
                    filteredApps.addAll(allApps)
                    tvAppCount.text = "${filteredApps.size} apps available"
                } else {
                    filteredApps.addAll(allApps.filter {
                        pm.getApplicationLabel(it).toString().lowercase(Locale.ROOT).contains(query) ||
                        it.packageName.lowercase(Locale.ROOT).contains(query)
                    })
                    tvAppCount.text = "${filteredApps.size} apps found"
                }
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        btnClose.setOnClickListener { dialog.dismiss() }

        listView.setOnItemClickListener { _, _, pos, _ ->
            val app = filteredApps[pos]
            val pkg = app.packageName
            val label = pm.getApplicationLabel(app).toString()
            dialog.dismiss()

            showSelectPresetForAppDialog(pkg, label)
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        val width = (resources.displayMetrics.widthPixels * 0.94).toInt()
        val height = (resources.displayMetrics.heightPixels * 0.88).toInt()
        dialog.window?.setLayout(width, height)
    }

    private fun showSelectPresetForAppDialog(packageName: String, appName: String) {
        val allPresets = PowerampPresetManager.getAllPresets(this)
        val names = allPresets.map { it.name }.toTypedArray()
        val currentAssigned = PowerampPresetManager.getAppPreset(this, packageName) ?: allPresets.firstOrNull()?.name ?: "Flat Studio"
        val selectedIndex = names.indexOf(currentAssigned).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Audio Profile for $appName")
            .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                val chosenName = names[which]
                PowerampPresetManager.setAppPreset(this, packageName, chosenName)
                loadPerAppRules()
                Toast.makeText(this, "Profile '$chosenName' assigned to $appName", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

