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
                    val gain = (progress - 120) / 10.0f
                    tvPreampValue.text = String.format(Locale.US, "%+.1f dB", gain)
                    currentPreset?.let {
                        val updated = it.copy(preamp = gain)
                        currentPreset = updated
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
    }

    private fun updateShelfBand(type: Int, gain: Float) {
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
            val chip = Chip(this).apply {
                text = preset.name
                isCheckable = true
                isChecked = preset.name.equals(selectedName, ignoreCase = true)
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1E293B"))
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
        currentPreset = preset
        StudioDspManager.applyPreset(this, preset)

        tvActivePresetHeader.text = preset.name
        tvProfileTypeBadge.text = if (preset.parametric) "PARAMETRIC BIQUAD" else "${preset.bands.size}-BAND GRAPHIC"

        // Set Preamp UI
        val preampProgress = (preset.preamp * 10.0f + 120).toInt().coerceIn(0, 240)
        seekPreamp.progress = preampProgress
        tvPreampValue.text = String.format(Locale.US, "%+.1f dB", preset.preamp)

        // Set Tone Shelves UI
        val bassShelf = preset.bands.find { it.type == 0 }?.gain ?: 0.0f
        seekToneBass.progress = (bassShelf * 10.0f + 120).toInt().coerceIn(0, 240)
        tvToneBassValue.text = String.format(Locale.US, "%+.1f dB", bassShelf)

        val trebleShelf = preset.bands.find { it.type == 1 }?.gain ?: 0.0f
        seekToneTreble.progress = (trebleShelf * 10.0f + 120).toInt().coerceIn(0, 240)
        tvToneTrebleValue.text = String.format(Locale.US, "%+.1f dB", trebleShelf)

        // Update Frequency Curve Canvas
        curveView.setBands(preset.bands)

        // Build Multi-Band Vertical Sliders
        buildBandSliders(preset)
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
                        val newGain = (prog - 150) / 10.0f
                        band.gain = newGain
                        tvGain.text = String.format(Locale.US, "%+.1f", newGain)
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
        val input = EditText(this).apply {
            setText("${curr.name} (Custom)")
            setTextColor(getColor(android.R.color.white))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Save Custom Preset")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    val newPreset = curr.copy(name = name)
                    PowerampPresetManager.saveCustomPreset(this, newPreset)
                    applySelectedPreset(newPreset)
                    rebuildPresetChips(name)
                    Toast.makeText(this, "Preset '$name' Saved!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
