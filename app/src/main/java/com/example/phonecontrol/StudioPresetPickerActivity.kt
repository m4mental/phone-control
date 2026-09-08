package com.example.phonecontrol

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Locale

/**
 * Fast-access Dialog Activity launched on QS Tile Long-Press or Quick Settings Shortcut:
 * - StudioEqualizerTileService / StudioPresetTileService long-press -> Opens Preset Switcher
 * - StudioNightModeTileService long-press -> Opens Movie & Night Mode profile controls
 */
class StudioPresetPickerActivity : AppCompatActivity() {

    private lateinit var rootOverlay: View
    private lateinit var tvDialogTitle: TextView
    private lateinit var btnClosePicker: ImageButton
    private lateinit var toggleGroupViewMode: MaterialButtonToggleGroup
    private lateinit var btnTabPresets: MaterialButton
    private lateinit var btnTabNightMode: MaterialButton

    private lateinit var layoutSectionPresets: LinearLayout
    private lateinit var tvActivePresetStatus: TextView
    private lateinit var rvPresetList: RecyclerView

    private lateinit var layoutSectionNightMode: LinearLayout
    private lateinit var switchNightModePicker: MaterialSwitch
    private lateinit var layoutNightPickerControls: LinearLayout
    private lateinit var toggleGroupNightProfilePicker: MaterialButtonToggleGroup
    private lateinit var seekDialogueBoostPicker: SeekBar
    private lateinit var tvDialogueBoostPickerValue: TextView

    private lateinit var btnOpenFullEqualizer: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_studio_preset_picker)

        initViews()
        setupModeRouting()
        setupPresetsSection()
        setupNightModeSection()
        setupGlobalActions()
    }

    private fun initViews() {
        rootOverlay = findViewById(R.id.rootOverlay)
        tvDialogTitle = findViewById(R.id.tvDialogTitle)
        btnClosePicker = findViewById(R.id.btnClosePicker)
        toggleGroupViewMode = findViewById(R.id.toggleGroupViewMode)
        btnTabPresets = findViewById(R.id.btnTabPresets)
        btnTabNightMode = findViewById(R.id.btnTabNightMode)

        layoutSectionPresets = findViewById(R.id.layoutSectionPresets)
        tvActivePresetStatus = findViewById(R.id.tvActivePresetStatus)
        rvPresetList = findViewById(R.id.rvPresetList)

        layoutSectionNightMode = findViewById(R.id.layoutSectionNightMode)
        switchNightModePicker = findViewById(R.id.switchNightModePicker)
        layoutNightPickerControls = findViewById(R.id.layoutNightPickerControls)
        toggleGroupNightProfilePicker = findViewById(R.id.toggleGroupNightProfilePicker)
        seekDialogueBoostPicker = findViewById(R.id.seekDialogueBoostPicker)
        tvDialogueBoostPickerValue = findViewById(R.id.tvDialogueBoostPickerValue)

        btnOpenFullEqualizer = findViewById(R.id.btnOpenFullEqualizer)
    }

    private fun setupModeRouting() {
        val targetComponent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME)
        }

        val isNightModeTarget = targetComponent?.className?.contains("StudioNightModeTileService") == true ||
                intent.getStringExtra("target_section") == "night_mode"

        if (isNightModeTarget) {
            showNightModeTab()
        } else {
            showPresetsTab()
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

    private fun showPresetsTab() {
        btnTabPresets.isChecked = true
        layoutSectionPresets.visibility = View.VISIBLE
        layoutSectionNightMode.visibility = View.GONE
        tvDialogTitle.text = "🎛️ Equalizer Presets"
    }

    private fun showNightModeTab() {
        btnTabNightMode.isChecked = true
        layoutSectionPresets.visibility = View.GONE
        layoutSectionNightMode.visibility = View.VISIBLE
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
