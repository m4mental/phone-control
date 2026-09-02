package com.example.phonecontrol

import android.content.Context
import android.os.Bundle
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import android.media.audiofx.PresetReverb
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

        // Populate ViPER States
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
    }

    private fun setupListeners() {
        switchMasterDsp.setOnCheckedChangeListener { _, isChecked ->
            StudioDspManager.setMasterEnabled(this, isChecked)
            updateMasterStatusText(isChecked)
            Toast.makeText(this, if (isChecked) "Studio DSP Active 🎧" else "Studio DSP Disabled", Toast.LENGTH_SHORT).show()
        }

        findViewById<ImageView>(R.id.btnImportJson).setOnClickListener {
            showImportJsonDialog()
        }

        findViewById<View>(R.id.btnSaveCustomPreset).setOnClickListener {
            showSavePresetDialog()
        }

        // Preamp SeekBar: -12.0dB to +12.0dB (Max 240, 120 = 0dB)
        seekPreamp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onUserModifiedPreset()
                    val gain = (progress - 120) / 10.0f
                    tvPreampValue.text = String.format(Locale.US, "%+.1f dB", gain)
                    currentPreset?.let {
                        it.preamp = gain
                        StudioDspManager.setPreampGain(gain)
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Tone Bass Shelf (90Hz): -12.0dB to +12.0dB (Max 240)
        seekToneBass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val gain = (progress - 120) / 10.0f
                    tvToneBassValue.text = String.format(Locale.US, "%+.1f dB", gain)
                    updateShelfBand(0, gain)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Tone Treble Shelf (10kHz): -12.0dB to +12.0dB (Max 240)
        seekToneTreble.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val gain = (progress - 120) / 10.0f
                    tvToneTrebleValue.text = String.format(Locale.US, "%+.1f dB", gain)
                    updateShelfBand(1, gain)
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
            StudioDspManager.setDifferentialSurround(this, isChecked, seekSurround.progress)
        }
        seekSurround.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
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
            StudioDspManager.setReverberation(this, isChecked, getSelectedReverbPreset())
        }
        chipGroupReverb.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val preset = getSelectedReverbPreset()
                StudioDspManager.setReverberation(this, switchReverb.isChecked, preset)
            }
        }

        // --- Dynamic System ---
        switchDynamicSystem.setOnCheckedChangeListener { _, isChecked ->
            layoutDynamicControls.alpha = if (isChecked) 1.0f else 0.4f
            StudioDspManager.setDynamicSystem(this, isChecked, seekDynamicSystem.progress)
        }
        seekDynamicSystem.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvDynamicIntensity.text = "Drive: ${progress / 10}%"
                    StudioDspManager.setDynamicSystem(this@StudioEqualizerActivity, switchDynamicSystem.isChecked, progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
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
        val curr = currentPreset ?: return
        if (PowerampPresetManager.isBuiltInPreset(curr.name)) {
            val customName = "${curr.name} (Custom)"
            val forked = curr.deepCopy(customName)
            currentPreset = forked
            tvActivePresetHeader.text = customName
            tvProfileTypeBadge.text = "CUSTOM (UNSAVED)"
            tvProfileTypeBadge.setTextColor(android.graphics.Color.parseColor("#FF9100"))
            chipGroupPresets.clearCheck()
        }
    }

    private fun updateShelfBand(type: Int, gain: Float) {
        onUserModifiedPreset()
        val p = currentPreset ?: return
        val shelf = p.bands.find { it.type == type }
        if (shelf != null) {
            shelf.gain = gain
        } else {
            val freq = if (type == 0) 90 else 10000
            p.bands.add(EqualizerBand(type = type, frequency = freq, gain = gain))
        }
        StudioDspManager.applyPreset(this, p)
        curveView.setBands(p.bands)
    }

    private fun updateMasterStatusText(isEnabled: Boolean) {
        if (isEnabled) {
            tvDspStatus.text = "Poweramp DSP Engine • Active"
            tvDspStatus.setTextColor(getColor(android.R.color.holo_blue_light))
        } else {
            tvDspStatus.text = "DSP Engine • Bypassed"
            tvDspStatus.setTextColor(getColor(android.R.color.darker_gray))
        }
    }

    private fun loadActivePreset() {
        val activeName = PowerampPresetManager.getActivePresetName(this)
        val preset = PowerampPresetManager.getPresetByName(this, activeName) ?: PowerampPresetManager.getBuiltInPresets().first()
        applySelectedPreset(preset)
        rebuildPresetChips(preset.name)
    }

    private fun rebuildPresetChips(selectedName: String) {
        chipGroupPresets.removeAllViews()
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

        // Dimensional 3D Theater: Auto-engage cinema matrix
        if (workingPreset.name.equals("Dimensional 3D Theater", ignoreCase = true)) {
            switchSurround.isChecked = true
            seekSurround.progress = 800
            tvSurroundStrength.text = "Level: 80%"
            layoutSurroundControls.alpha = 1.0f
            StudioDspManager.setDifferentialSurround(this, true, 800)

            switchReverb.isChecked = true
            chipReverbLargeHall.isChecked = true
            scrollReverbChips.alpha = 1.0f
            StudioDspManager.setReverberation(this, true, PresetReverb.PRESET_LARGEHALL)

            switchDynamicSystem.isChecked = true
            seekDynamicSystem.progress = 700
            tvDynamicIntensity.text = "Drive: 70%"
            layoutDynamicControls.alpha = 1.0f
            StudioDspManager.setDynamicSystem(this, true, 700)
        }

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
            val tvGain = view.findViewById<TextView>(R.id.tvBandGain)
            val seekBand = view.findViewById<SeekBar>(R.id.seekBand)
            val tvFreq = view.findViewById<TextView>(R.id.tvBandFreq)
            val tvQ = view.findViewById<TextView>(R.id.tvBandQ)

            // Format frequency (e.g. 90Hz or 2.1kHz)
            tvFreq.text = if (band.frequency >= 1000) {
                String.format(Locale.US, "%.1fk", band.frequency / 1000.0f)
            } else {
                "${band.frequency}Hz"
            }

            if (band.q > 0.0f) {
                tvQ.visibility = View.VISIBLE
                tvQ.text = String.format(Locale.US, "Q: %.2f", band.q)
            } else {
                tvQ.visibility = View.GONE
            }

            // Gain Range: -15.0dB to +15.0dB (Max 300, 150 = 0dB)
            val progress = (band.gain * 10.0f + 150).toInt().coerceIn(0, 300)
            seekBand.progress = progress
            tvGain.text = String.format(Locale.US, "%+.1f", band.gain)

            seekBand.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (fromUser) {
                        onUserModifiedPreset()
                        val newGain = (prog - 150) / 10.0f
                        band.gain = newGain
                        tvGain.text = String.format(Locale.US, "%+.1f", newGain)
                        currentPreset?.let { p ->
                            curveView.setBands(p.bands)
                            StudioDspManager.applyPreset(this@StudioEqualizerActivity, p)
                        }
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            llBandsContainer.addView(view)
        }
    }

    private fun showImportJsonDialog() {
        val options = arrayOf("📁 Pick .json File from Storage", "📋 Paste JSON Text Manually")
        MaterialAlertDialogBuilder(this)
            .setTitle("Import Poweramp Preset")
            .setItems(options) { _, which ->
                if (which == 0) {
                    try {
                        pickJsonFileLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Cannot open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    showPasteJsonDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPasteJsonDialog() {
        val input = EditText(this).apply {
            hint = "Paste Poweramp Equalizer JSON preset here..."
            setTextColor(getColor(android.R.color.white))
            setHintTextColor(getColor(android.R.color.darker_gray))
            minLines = 6
            maxLines = 15
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Paste Poweramp Preset")
            .setMessage("Paste exported JSON profile text from Poweramp Equalizer:")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val json = input.text.toString().trim()
                processImportedJson(json)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun processImportedJson(json: String) {
        if (json.isBlank()) {
            Toast.makeText(this, "JSON content is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val parsed = PowerampPresetManager.parsePowerampJson(json)
        if (parsed.isNotEmpty()) {
            for (p in parsed) {
                PowerampPresetManager.saveCustomPreset(this, p)
            }
            val first = parsed.first()
            Toast.makeText(this, "Imported: ${first.name} 🎉", Toast.LENGTH_SHORT).show()
            applySelectedPreset(first)
            rebuildPresetChips(first.name)
        } else {
            Toast.makeText(this, "Invalid Poweramp JSON format", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSavePresetDialog() {
        val curr = currentPreset ?: return
        val defaultName = curr.name.replace(" (Custom)", "").replace(" (Modified)", "") + " Custom"
        val input = EditText(this).apply {
            setText(defaultName)
            setTextColor(getColor(android.R.color.white))
            setSelection(text.length)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Save as Custom Preset")
            .setMessage("Save your modified acoustic tuning as a separate custom preset:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    val newPreset = curr.deepCopy(name)
                    PowerampPresetManager.saveCustomPreset(this, newPreset)
                    applySelectedPreset(newPreset)
                    rebuildPresetChips(name)
                    Toast.makeText(this, "Saved Custom Preset '$name' 🎉", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
