package com.example.phonecontrol

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Poweramp Equalizer Preset Manager.
 * Loads factory profiles (DTS Sound Unbound, DTS Theater, Dimensional 3D Theater, Parametric Biquad)
 * and provides full JSON export/import compatibility with Poweramp Equalizer.
 * Each preset maintains its own frequency response curve, preamp gain, ViPER FX Suite state, and Audio Clarity.
 */
data class EqualizerBand(
    var type: Int,          // 0: Low Shelf, 1: High Shelf, 2: Peaking Graphic, 3: Peaking Parametric, 4: Low Pass, 5: High Pass
    var channels: Int = 0,  // 0: Stereo/Both, 1: Left, 2: Right
    var frequency: Int,     // Center frequency in Hz (e.g. 31, 62, 125... 16000)
    var q: Float = 0.0f,    // Quality factor Q (for parametric biquad filters)
    var gain: Float = 0.0f, // Gain in dB (-15.0dB to +15.0dB)
    var color: Int = 0
)

data class EqualizerPreset(
    var name: String,
    var preamp: Float = 0.0f,   // Preamp gain in dB
    var parametric: Boolean = false,
    val bands: MutableList<EqualizerBand> = mutableListOf(),
    var surroundEnabled: Boolean = false,
    var surroundStrength: Int = 500,
    var reverbEnabled: Boolean = false,
    var reverbPreset: Short = 2, // Medium Room
    var dynamicSystemEnabled: Boolean = false,
    var dynamicSystemIntensity: Int = 600,
    var clarityEnabled: Boolean = false,
    var clarityLevel: Int = 500,
    var crossfeedEnabled: Boolean = false,
    var crossfeedLevel: Int = 500,
    var channelBalance: Float = 0.0f // -1.0 (Left) to +1.0 (Right), 0.0 Center
) {
    fun deepCopy(newName: String = name): EqualizerPreset {
        return EqualizerPreset(
            name = newName,
            preamp = preamp,
            parametric = parametric,
            bands = bands.map { it.copy() }.toMutableList(),
            surroundEnabled = surroundEnabled,
            surroundStrength = surroundStrength,
            reverbEnabled = reverbEnabled,
            reverbPreset = reverbPreset,
            dynamicSystemEnabled = dynamicSystemEnabled,
            dynamicSystemIntensity = dynamicSystemIntensity,
            clarityEnabled = clarityEnabled,
            clarityLevel = clarityLevel,
            crossfeedEnabled = crossfeedEnabled,
            crossfeedLevel = crossfeedLevel,
            channelBalance = channelBalance
        )
    }
}

object PowerampPresetManager {

    private const val PREFS_NAME = "studio_equalizer_prefs"
    private const val KEY_ACTIVE_PRESET = "active_preset_name"
    private const val KEY_CUSTOM_PRESETS = "custom_presets_json"
    private const val KEY_MASTER_ENABLED = "equalizer_master_enabled"
    private const val KEY_BASS_BOOST_STRENGTH = "bass_boost_strength"
    private const val KEY_VIRTUALIZER_STRENGTH = "virtualizer_strength"
    private const val KEY_CURRENT_PREAMP = "current_preamp_gain"

    val STANDARD_FREQUENCIES = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isMasterEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_MASTER_ENABLED, true)
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply()
    }

    fun getActivePreamp(context: Context): Float {
        return getPrefs(context).getFloat(KEY_CURRENT_PREAMP, 0.0f)
    }

    fun setActivePreamp(context: Context, preamp: Float) {
        getPrefs(context).edit().putFloat(KEY_CURRENT_PREAMP, preamp).apply()
    }

    fun getBassBoostStrength(context: Context): Int {
        return getPrefs(context).getInt(KEY_BASS_BOOST_STRENGTH, 0)
    }

    fun setBassBoostStrength(context: Context, strength: Int) {
        getPrefs(context).edit().putInt(KEY_BASS_BOOST_STRENGTH, strength).apply()
    }

    fun getVirtualizerStrength(context: Context): Int {
        return getPrefs(context).getInt(KEY_VIRTUALIZER_STRENGTH, 0)
    }

    fun setVirtualizerStrength(context: Context, strength: Int) {
        getPrefs(context).edit().putInt(KEY_VIRTUALIZER_STRENGTH, strength).apply()
    }

    // --- Differential Surround ---
    fun isSurroundEnabled(context: Context): Boolean = getPrefs(context).getBoolean("surround_enabled", false)
    fun setSurroundEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean("surround_enabled", enabled).apply()
    fun getSurroundStrength(context: Context): Int = getPrefs(context).getInt("surround_strength", 500)
    fun setSurroundStrength(context: Context, str: Int) = getPrefs(context).edit().putInt("surround_strength", str).apply()

    // --- Reverberation ---
    fun isReverbEnabled(context: Context): Boolean = getPrefs(context).getBoolean("reverb_enabled", false)
    fun setReverbEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean("reverb_enabled", enabled).apply()
    fun getReverbPreset(context: Context): Short = getPrefs(context).getInt("reverb_preset", 2).toShort()
    fun setReverbPreset(context: Context, preset: Short) = getPrefs(context).edit().putInt("reverb_preset", preset.toInt()).apply()

    // --- Dynamic System (Harmonic Bass Drive) ---
    fun isDynamicSystemEnabled(context: Context): Boolean = getPrefs(context).getBoolean("dynamic_system_enabled", false)
    fun setDynamicSystemEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean("dynamic_system_enabled", enabled).apply()
    fun getDynamicSystemIntensity(context: Context): Int = getPrefs(context).getInt("dynamic_system_intensity", 600)
    fun setDynamicSystemIntensity(context: Context, intensity: Int) = getPrefs(context).edit().putInt("dynamic_system_intensity", intensity).apply()

    // --- ViPER Audio Clarity (Natural Vocal Exciter) ---
    fun isClarityEnabled(context: Context): Boolean = getPrefs(context).getBoolean("clarity_enabled", false)
    fun setClarityEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean("clarity_enabled", enabled).apply()
    fun getClarityLevel(context: Context): Int = getPrefs(context).getInt("clarity_level", 500)
    fun setClarityLevel(context: Context, level: Int) = getPrefs(context).edit().putInt("clarity_level", level).apply()

    // --- Bauer Stereo Crossfeed ---
    fun isCrossfeedEnabled(context: Context): Boolean = getPrefs(context).getBoolean("crossfeed_enabled", false)
    fun setCrossfeedEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean("crossfeed_enabled", enabled).apply()
    fun getCrossfeedLevel(context: Context): Int = getPrefs(context).getInt("crossfeed_level", 500)
    fun setCrossfeedLevel(context: Context, level: Int) = getPrefs(context).edit().putInt("crossfeed_level", level).apply()

    // --- Channel Balance (-100 to +100) ---
    fun getChannelBalance(context: Context): Int = getPrefs(context).getInt("channel_balance", 0)
    fun setChannelBalance(context: Context, balance: Int) = getPrefs(context).edit().putInt("channel_balance", balance).apply()

    // --- Smart Output Auto Switcher ---
    fun isSmartOutputSwitchEnabled(context: Context): Boolean = getPrefs(context).getBoolean("smart_output_switch_enabled", true)
    fun setSmartOutputSwitchEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean("smart_output_switch_enabled", enabled).apply()
    fun getSavedHeadphonePreset(context: Context): String = getPrefs(context).getString("saved_headphone_preset", "Dimensional 3D Theater") ?: "Dimensional 3D Theater"
    fun setSavedHeadphonePreset(context: Context, name: String) = getPrefs(context).edit().putString("saved_headphone_preset", name).apply()
    fun getSavedSpeakerPreset(context: Context): String = getPrefs(context).getString("saved_speaker_preset", "Phone Speaker [Clarity Guard]") ?: "Phone Speaker [Clarity Guard]"
    fun setSavedSpeakerPreset(context: Context, name: String) = getPrefs(context).edit().putString("saved_speaker_preset", name).apply()

    fun getActivePresetName(context: Context): String {
        return getPrefs(context).getString(KEY_ACTIVE_PRESET, "DTS Sound Unbound profile") ?: "DTS Sound Unbound profile"
    }

    fun setActivePresetName(context: Context, name: String) {
        getPrefs(context).edit().putString(KEY_ACTIVE_PRESET, name).apply()
    }

    /**
     * Built-in factory presets including user's DTS, Dimensional 3D Theater, and Parametric profiles.
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
            ),
            surroundEnabled = true,
            surroundStrength = 500,
            reverbEnabled = false,
            reverbPreset = 2,
            dynamicSystemEnabled = true,
            dynamicSystemIntensity = 500,
            clarityEnabled = true,
            clarityLevel = 500,
            crossfeedEnabled = false,
            crossfeedLevel = 400
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
            ),
            surroundEnabled = true,
            surroundStrength = 650,
            reverbEnabled = true,
            reverbPreset = 4, // Medium Hall
            dynamicSystemEnabled = true,
            dynamicSystemIntensity = 600,
            clarityEnabled = true,
            clarityLevel = 600,
            crossfeedEnabled = true,
            crossfeedLevel = 500
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
            ),
            surroundEnabled = true,
            surroundStrength = 700,
            reverbEnabled = true,
            reverbPreset = 4, // Medium Hall
            dynamicSystemEnabled = true,
            dynamicSystemIntensity = 800,
            clarityEnabled = true,
            clarityLevel = 650,
            crossfeedEnabled = true,
            crossfeedLevel = 500
        ))

        // 4. Dimensional 3D Theater (IMAX & Dolby Atmos Cinema Acoustics)
        list.add(EqualizerPreset(
            name = "Dimensional 3D Theater",
            preamp = -3.0f,
            parametric = false,
            bands = mutableListOf(
                EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = 6.5f),   // Deep Cinema Bass Shelf
                EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 4.5f), // Air Surround Treble Shelf
                EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 7.5f),   // Subwoofer Floor Rumble
                EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = 6.0f),   // Explosive Impact
                EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = 3.0f),  // Cinematic Warmth
                EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = 1.0f),
                EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = -0.5f), // Mud clarity dip
                EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 2.0f),  // Dialogue presence
                EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 2.8f), // Speech clarity boost
                EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = 1.5f), // Spatial depth
                EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 4.2f), // 3D Foley sound effects
                EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 5.5f) // Atmospheric air
            ),
            surroundEnabled = true,
            surroundStrength = 800,
            reverbEnabled = true,
            reverbPreset = 5, // Concert / Large Hall
            dynamicSystemEnabled = true,
            dynamicSystemIntensity = 700,
            clarityEnabled = true,
            clarityLevel = 750,
            crossfeedEnabled = true,
            crossfeedLevel = 600
        ))

        // 5. My song 2 (Full Parametric Profile)
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
            ),
            surroundEnabled = false,
            surroundStrength = 0,
            reverbEnabled = false,
            reverbPreset = 2,
            dynamicSystemEnabled = false,
            dynamicSystemIntensity = 0,
            clarityEnabled = false,
            clarityLevel = 0,
            crossfeedEnabled = false,
            crossfeedLevel = 0
        ))

        // 6. Studio Bypass / Flat
        list.add(EqualizerPreset(
            name = "Studio Flat (Bypass)",
            preamp = 0.0f,
            parametric = false,
            bands = STANDARD_FREQUENCIES.map { freq ->
                EqualizerBand(type = 2, frequency = freq, q = 0.0f, gain = 0.0f)
            }.toMutableList(),
            surroundEnabled = false,
            surroundStrength = 0,
            reverbEnabled = false,
            reverbPreset = 2,
            dynamicSystemEnabled = false,
            dynamicSystemIntensity = 0,
            clarityEnabled = false,
            clarityLevel = 0,
            crossfeedEnabled = false,
            crossfeedLevel = 0
        ))

        return list
    }

    /**
     * Retrieves all presets (built-in + user imported + popular AutoEQ).
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
            ?: AutoEqManager.POPULAR_HEADPHONES.find { it.preset.name.equals(name, ignoreCase = true) }?.preset
            ?: getBuiltInPresets().firstOrNull()
    }

    fun isBuiltInPreset(name: String): Boolean {
        return getBuiltInPresets().any { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Parses Poweramp JSON export string.
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

        val surroundEnabled = obj.optBoolean("surround_enabled", false)
        val surroundStrength = obj.optInt("surround_strength", 500)
        val reverbEnabled = obj.optBoolean("reverb_enabled", false)
        val reverbPreset = obj.optInt("reverb_preset", 2).toShort()
        val dynamicSystemEnabled = obj.optBoolean("dynamic_system_enabled", false)
        val dynamicSystemIntensity = obj.optInt("dynamic_system_intensity", 600)
        val clarityEnabled = obj.optBoolean("clarity_enabled", false)
        val clarityLevel = obj.optInt("clarity_level", 500)
        val crossfeedEnabled = obj.optBoolean("crossfeed_enabled", false)
        val crossfeedLevel = obj.optInt("crossfeed_level", 500)
        val channelBalance = obj.optDouble("channel_balance", 0.0).toFloat()

        return EqualizerPreset(
            name = name,
            preamp = preamp,
            parametric = parametric,
            bands = bands,
            surroundEnabled = surroundEnabled,
            surroundStrength = surroundStrength,
            reverbEnabled = reverbEnabled,
            reverbPreset = reverbPreset,
            dynamicSystemEnabled = dynamicSystemEnabled,
            dynamicSystemIntensity = dynamicSystemIntensity,
            clarityEnabled = clarityEnabled,
            clarityLevel = clarityLevel,
            crossfeedEnabled = crossfeedEnabled,
            crossfeedLevel = crossfeedLevel,
            channelBalance = channelBalance
        )
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
            pObj.put("surround_enabled", p.surroundEnabled)
            pObj.put("surround_strength", p.surroundStrength)
            pObj.put("reverb_enabled", p.reverbEnabled)
            pObj.put("reverb_preset", p.reverbPreset.toInt())
            pObj.put("dynamic_system_enabled", p.dynamicSystemEnabled)
            pObj.put("dynamic_system_intensity", p.dynamicSystemIntensity)
            pObj.put("clarity_enabled", p.clarityEnabled)
            pObj.put("clarity_level", p.clarityLevel)
            pObj.put("crossfeed_enabled", p.crossfeedEnabled)
            pObj.put("crossfeed_level", p.crossfeedLevel)
            pObj.put("channel_balance", p.channelBalance.toDouble())

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
        pObj.put("surround_enabled", preset.surroundEnabled)
        pObj.put("surround_strength", preset.surroundStrength)
        pObj.put("reverb_enabled", preset.reverbEnabled)
        pObj.put("reverb_preset", preset.reverbPreset.toInt())
        pObj.put("dynamic_system_enabled", preset.dynamicSystemEnabled)
        pObj.put("dynamic_system_intensity", preset.dynamicSystemIntensity)
        pObj.put("clarity_enabled", preset.clarityEnabled)
        pObj.put("clarity_level", preset.clarityLevel)
        pObj.put("crossfeed_enabled", preset.crossfeedEnabled)
        pObj.put("crossfeed_level", preset.crossfeedLevel)
        pObj.put("channel_balance", preset.channelBalance.toDouble())

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
