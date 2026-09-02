package com.example.phonecontrol

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data structures matching Poweramp Equalizer exported JSON format.
 */
data class EqualizerBand(
    val type: Int = 2,          // 0: Low Shelf (Tone Bass), 1: High Shelf (Tone Treble), 2: Graphic EQ, 3: Parametric Peaking, 4: Pass, 5: High Shelf
    val channels: Int = 0,
    val frequency: Int,         // Center or cutoff frequency in Hz
    var q: Float = 0.0f,        // Bandwidth Q factor
    var gain: Float = 0.0f,     // Gain in dB (-15dB to +15dB)
    val color: Int = 0
)

data class EqualizerPreset(
    val name: String,
    val preamp: Float = 0.0f,   // Preamp gain in dB
    val parametric: Boolean = false,
    val bands: MutableList<EqualizerBand> = mutableListOf()
)

object PowerampPresetManager {

    private const val PREFS_NAME = "studio_equalizer_prefs"
    private const val KEY_ACTIVE_PRESET = "active_preset_name"
    private const val KEY_CUSTOM_PRESETS = "custom_presets_json"
    private const val KEY_MASTER_ENABLED = "equalizer_master_enabled"
    private const val KEY_BASS_BOOST_STRENGTH = "bass_boost_strength"
    private const val KEY_VIRTUALIZER_STRENGTH = "virtualizer_strength"
    private const val KEY_CURRENT_PREAMP = "current_preamp_gain"

    // Default 10 Standard Octave ISO Frequencies
    val STANDARD_FREQUENCIES = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isMasterEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_MASTER_ENABLED, false)
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply()
    }

    fun getBassBoostStrength(context: Context): Int {
        return getPrefs(context).getInt(KEY_BASS_BOOST_STRENGTH, 0) // 0 to 1000
    }

    fun setBassBoostStrength(context: Context, strength: Int) {
        getPrefs(context).edit().putInt(KEY_BASS_BOOST_STRENGTH, strength).apply()
    }

    fun getVirtualizerStrength(context: Context): Int {
        return getPrefs(context).getInt(KEY_VIRTUALIZER_STRENGTH, 0) // 0 to 1000
    }

    fun setVirtualizerStrength(context: Context, strength: Int) {
        getPrefs(context).edit().putInt(KEY_VIRTUALIZER_STRENGTH, strength).apply()
    }

    fun getActivePreamp(context: Context): Float {
        return getPrefs(context).getFloat(KEY_CURRENT_PREAMP, 0.0f)
    }

    fun setActivePreamp(context: Context, preamp: Float) {
        getPrefs(context).edit().putFloat(KEY_CURRENT_PREAMP, preamp).apply()
    }

    fun getActivePresetName(context: Context): String {
        return getPrefs(context).getString(KEY_ACTIVE_PRESET, "DTS Sound Unbound profile") ?: "DTS Sound Unbound profile"
    }

    fun setActivePresetName(context: Context, name: String) {
        getPrefs(context).edit().putString(KEY_ACTIVE_PRESET, name).apply()
    }

    /**
     * Built-in factory presets including user's DTS and Parametric profiles.
     */
    fun getBuiltInPresets(): List<EqualizerPreset> {
        val list = mutableListOf<EqualizerPreset>()

        // 1. DTS Sound Unbound profile
        list.add(EqualizerPreset(
            name = "DTS Sound Unbound profile",
            preamp = -0.01f,
            parametric = false,
            bands = mutableListOf(
                EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = 5.23f),
                EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 3.01f),
                EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 6.45f),
                EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = 5.02f),
                EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = 2.94f),
                EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = -1.02f),
                EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = -0.96f),
                EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 0.0f),
                EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 1.02f),
                EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = 1.95f),
                EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 3.48f),
                EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 4.99f)
            )
        ))

        // 2. DTS Theater Mode
        list.add(EqualizerPreset(
            name = "DTS Theater Mode",
            preamp = -1.01f,
            parametric = false,
            bands = mutableListOf(
                EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = 7.54f),
                EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 4.52f),
                EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = -3.52f),
                EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = 5.54f),
                EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = 2.05f),
                EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = -1.98f),
                EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = -1.47f),
                EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 1.47f),
                EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 2.49f),
                EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = 1.02f),
                EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 4.00f),
                EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 6.01f)
            )
        ))

        // 3. DTS Theater Mode 2
        list.add(EqualizerPreset(
            name = "DTS Theater Mode 2",
            preamp = -6.02f,
            parametric = false,
            bands = mutableListOf(
                EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = 7.47f),
                EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 4.53f),
                EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 7.99f),
                EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = 7.01f),
                EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = 3.51f),
                EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = 0.0f),
                EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = 0.01f),
                EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 1.47f),
                EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 3.01f),
                EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = 1.95f),
                EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 3.96f),
                EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 4.99f)
            )
        ))

        // 4. My song 2 (Full Parametric Profile)
        list.add(EqualizerPreset(
            name = "My song 2",
            preamp = 0.0f,
            parametric = true,
            bands = mutableListOf(
                EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = 9.05f),
                EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 5.97f),
                EqualizerBand(type = 4, frequency = 105, q = 0.7f, gain = -0.1f),
                EqualizerBand(type = 3, frequency = 178, q = 0.71f, gain = -2.4f),
                EqualizerBand(type = 3, frequency = 77, q = 1.96f, gain = 2.9f),
                EqualizerBand(type = 3, frequency = 2105, q = 2.65f, gain = 1.6f),
                EqualizerBand(type = 3, frequency = 3987, q = 3.57f, gain = 1.5f),
                EqualizerBand(type = 5, frequency = 10000, q = 0.7f, gain = 5.0f),
                EqualizerBand(type = 3, frequency = 8637, q = 1.39f, gain = 2.7f),
                EqualizerBand(type = 3, frequency = 5689, q = 5.27f, gain = -4.6f),
                EqualizerBand(type = 3, frequency = 1006, q = 4.28f, gain = 1.4f),
                EqualizerBand(type = 3, frequency = 790, q = 3.65f, gain = -0.7f)
            )
        ))

        // 5. Studio Bypass / Flat
        list.add(EqualizerPreset(
            name = "Studio Flat (Bypass)",
            preamp = 0.0f,
            parametric = false,
            bands = STANDARD_FREQUENCIES.map { freq ->
                EqualizerBand(type = 2, frequency = freq, q = 0.0f, gain = 0.0f)
            }.toMutableList()
        ))

        return list
    }

    /**
     * Retrieves all presets (built-in + user imported).
     */
    fun getAllPresets(context: Context): List<EqualizerPreset> {
        val builtIns = getBuiltInPresets().toMutableList()
        val customPresets = getCustomPresets(context)
        for (custom in customPresets) {
            builtIns.removeAll { it.name.equals(custom.name, ignoreCase = true) }
            builtIns.add(custom)
        }
        return builtIns
    }

    fun getPresetByName(context: Context, name: String): EqualizerPreset? {
        return getAllPresets(context).find { it.name.equals(name, ignoreCase = true) }
            ?: getBuiltInPresets().firstOrNull()
    }

    /**
     * Parses Poweramp JSON export string (can be an Array of presets or a single Preset object).
     */
    fun parsePowerampJson(jsonString: String): List<EqualizerPreset> {
        val result = mutableListOf<EqualizerPreset>()
        try {
            val trimmed = jsonString.trim()
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    parseSinglePreset(obj)?.let { result.add(it) }
                }
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                parseSinglePreset(obj)?.let { result.add(it) }
            }
        } catch (e: Exception) {
            Log.e("PowerampPresetManager", "JSON Parse error: ${e.message}")
        }
        return result
    }

    private fun parseSinglePreset(obj: JSONObject): EqualizerPreset? {
        val name = obj.optString("name", "Custom Preset").trim()
        val preamp = obj.optDouble("preamp", 0.0).toFloat()
        val parametric = obj.optBoolean("parametric", false)
        val bandsArray = obj.optJSONArray("bands") ?: return null

        val bands = mutableListOf<EqualizerBand>()
        for (i in 0 until bandsArray.length()) {
            val bObj = bandsArray.optJSONObject(i) ?: continue
            val band = EqualizerBand(
                type = bObj.optInt("type", 2),
                channels = bObj.optInt("channels", 0),
                frequency = bObj.optInt("frequency", 1000),
                q = bObj.optDouble("q", 0.0).toFloat(),
                gain = bObj.optDouble("gain", 0.0).toFloat(),
                color = bObj.optInt("color", 0)
            )
            bands.add(band)
        }

        return EqualizerPreset(name = name, preamp = preamp, parametric = parametric, bands = bands)
    }

    /**
     * Saves a custom preset to SharedPreferences.
     */
    fun saveCustomPreset(context: Context, preset: EqualizerPreset) {
        val customs = getCustomPresets(context).toMutableList()
        customs.removeAll { it.name.equals(preset.name, ignoreCase = true) }
        customs.add(preset)

        val array = JSONArray()
        for (p in customs) {
            val pObj = JSONObject()
            pObj.put("name", p.name)
            pObj.put("preamp", p.preamp.toDouble())
            pObj.put("parametric", p.parametric)

            val bandsArr = JSONArray()
            for (b in p.bands) {
                val bObj = JSONObject()
                bObj.put("type", b.type)
                bObj.put("channels", b.channels)
                bObj.put("frequency", b.frequency)
                bObj.put("q", b.q.toDouble())
                bObj.put("gain", b.gain.toDouble())
                bObj.put("color", b.color)
                bandsArr.put(bObj)
            }
            pObj.put("bands", bandsArr)
            array.put(pObj)
        }

        getPrefs(context).edit().putString(KEY_CUSTOM_PRESETS, array.toString()).apply()
    }

    fun getCustomPresets(context: Context): List<EqualizerPreset> {
        val json = getPrefs(context).getString(KEY_CUSTOM_PRESETS, "") ?: ""
        if (json.isBlank()) return emptyList()
        return parsePowerampJson(json)
    }

    /**
     * Serializes a preset to Poweramp JSON format for sharing or exporting.
     */
    fun exportPresetToJson(preset: EqualizerPreset): String {
        val pObj = JSONObject()
        pObj.put("name", preset.name)
        pObj.put("preamp", preset.preamp.toDouble())
        pObj.put("parametric", preset.parametric)

        val bandsArr = JSONArray()
        for (b in preset.bands) {
            val bObj = JSONObject()
            bObj.put("type", b.type)
            bObj.put("channels", b.channels)
            bObj.put("frequency", b.frequency)
            bObj.put("q", b.q.toDouble())
            bObj.put("gain", b.gain.toDouble())
            bObj.put("color", b.color)
            bandsArr.put(bObj)
        }
        pObj.put("bands", bandsArr)

        val array = JSONArray()
        array.put(pObj)
        return array.toString(2)
    }
}
