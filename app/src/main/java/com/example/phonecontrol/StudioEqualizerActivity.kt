package com.example.phonecontrol

import android.content.Context
import android.media.audiofx.PresetReverb
import android.media.audiofx.Visualizer
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
import java.util.Locale

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

    private var currentPreset: EqualizerPreset? = null
    private var visualizer: Visualizer? = null

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

    override fun onResume() {
        super.onResume()
        startVisualizer()
    }

    override fun onPause() {
        super.onPause()
        stopVisualizer()
    }

    override fun onDestroy() {
        stopVisualizer()
        super.onDestroy()
    }

    private fun startVisualizer() {
        try {
            if (visualizer == null) {
                visualizer = Visualizer(0).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(512)
                    setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                            curveView.updateFft(fft)
                        }
                    }, Visualizer.getMaxCaptureRate() / 2, false, true)
                    enabled = true
                }
                Log.d("StudioEQ", "Visualizer attached for live FFT spectrum")
            }
        } catch (e: Exception) {
            Log.w("StudioEQ", "Visualizer init fallback: ${e.message}")
        }
    }

    private fun stopVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
        } catch (e: Exception) {}
    }

    private fun initViews() {
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        switchMasterDsp = findViewById(R.id.switchMasterDsp)
        tvDspStatus = findViewById(R.id.tvDspStatus)
        tvActivePresetHeader = findViewById(R.id.tvActivePresetHeader)
        tvProfileTypeBadge = findViewById(R.id.tvProfileTypeBadge)
        curveView = findViewById(R.id.curveView)
        chipGroupPresets = findViewById(R.id.chipGroupPresets)

        seekPreamp = findViewById(R.id.seekPreamp)
        tvPreampValue = findViewById(R.id.tvPreampValue)
        seekToneBass = findViewById(R.id.seekToneBass)
        tvToneBassValue = findViewById(R.id.tvToneBassValue)
        seekToneTreble = findViewById(R.id.seekToneTreble)
        tvToneTrebleValue = findViewById(R.id.tvToneTrebleValue)

        seekBassBoost = findViewById(R.id.seekBassBoost)
        tvBassBoostTitle = findViewById(R.id.tvBassBoostTitle)
        seekVirtualizer = findViewById(R.id.seekVirtualizer)
        tvVirtualizerTitle = findViewById(R.id.tvVirtualizerTitle)

        llBandsContainer = findViewById(R.id.llBandsContainer)

        // Set initial Master switch state
        val isEnabled = PowerampPresetManager.isMasterEnabled(this)
        switchMasterDsp.isChecked = isEnabled
        updateMasterStatusText(isEnabled)

        // Set Bass Boost & Virtualizer
        val bb = PowerampPresetManager.getBassBoostStrength(this)
        seekBassBoost.progress = bb
        tvBassBoostTitle.text = "Bass Boost: ${bb / 10}%"

        val virt = PowerampPresetManager.getVirtualizerStrength(this)
        seekVirtualizer.progress = virt
        tvVirtualizerTitle.text = "3D Virtualizer: ${virt / 10}%"

        // ViPER FX Suite Views
        switchSurround = findViewById(R.id.switchSurround)
        seekSurround = findViewById(R.id.seekSurround)
        tvSurroundStrength = findViewById(R.id.tvSurroundStrength)
        layoutSurroundControls = findViewById(R.id.layoutSurroundControls)

        switchReverb = findViewById(R.id.switchReverb)
        scrollReverbChips = findViewById(R.id.scrollReverbChips)
        chipGroupReverb = findViewById(R.id.chipGroupReverb)
        chipReverbSmallRoom = findViewById(R.id.chipReverbSmallRoom)
        chipReverbMediumRoom = findViewById(R.id.chipReverbMediumRoom)
        chipReverbLargeRoom = findViewById(R.id.chipReverbLargeRoom)
        chipReverbMediumHall = findViewById(R.id.chipReverbMediumHall)
        chipReverbLargeHall = findViewById(R.id.chipReverbLargeHall)
        chipReverbPlate = findViewById(R.id.chipReverbPlate)

        switchDynamicSystem = findViewById(R.id.switchDynamicSystem)
        seekDynamicSystem = findViewById(R.id.seekDynamicSystem)
        tvDynamicIntensity = findViewById(R.id.tvDynamicIntensity)
        layoutDynamicControls = findViewById(R.id.layoutDynamicControls)

        switchClarity = findViewById(R.id.switchClarity)
        seekClarity = findViewById(R.id.seekClarity)
        tvClarityLevel = findViewById(R.id.tvClarityLevel)
        layoutClarityControls = findViewById(R.id.layoutClarityControls)

        // Acoustics & Channel Balance Views
        switchCrossfeed = findViewById(R.id.switchCrossfeed)
        seekCrossfeed = findViewById(R.id.seekCrossfeed)
        tvCrossfeedLevel = findViewById(R.id.tvCrossfeedLevel)
        layoutCrossfeedControls = findViewById(R.id.layoutCrossfeedControls)
        seekChannelBalance = findViewById(R.id.seekChannelBalance)
        tvChannelBalance = findViewById(R.id.tvChannelBalance)
        switchSmartOutput = findViewById(R.id.switchSmartOutput)

        // Populate initial states
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

        val crossfeedOn = PowerampPresetManager.isCrossfeedEnabled(this)
        val crossfeedLvl = PowerampPresetManager.getCrossfeedLevel(this)
        switchCrossfeed.isChecked = crossfeedOn
        seekCrossfeed.progress = crossfeedLvl
        tvCrossfeedLevel.text = "Level: ${crossfeedLvl / 10}%"
        layoutCrossfeedControls.alpha = if (crossfeedOn) 1.0f else 0.4f

        val balance = PowerampPresetManager.getChannelBalance(this)
        seekChannelBalance.progress = balance + 100
        updateBalanceText(balance)

        switchSmartOutput.isChecked = PowerampPresetManager.isSmartOutputSwitchEnabled(this)
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

        findViewById<View>(R.id.btnSaveCustomPreset).setOnClickListener {
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

        // --- Smart Output Switch ---
        switchSmartOutput.setOnCheckedChangeListener { _, isChecked ->
            PowerampPresetManager.setSmartOutputSwitchEnabled(this, isChecked)
            Toast.makeText(this, if (isChecked) "Auto-preset switching active 🎧/🔊" else "Auto-preset switching disabled", Toast.LENGTH_SHORT).show()
        }
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

    private fun updateMasterStatusText(enabled: Boolean) {
        tvDspStatus.text = if (enabled) "Poweramp DSP Engine • Active" else "Poweramp DSP Engine • Disabled"
        tvDspStatus.setTextColor(android.graphics.Color.parseColor(if (enabled) "#00E5FF" else "#94A3B8"))
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
}
