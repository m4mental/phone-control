package com.example.phonecontrol

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.Build
import android.util.Log

/**
 * High-Precision Studio DSP Audio Engine.
 * Utilizes Android's DynamicsProcessing framework (Pre-EQ shelves, Multi-Band Post-EQ,
 * Input Gain Preamp, Limiter, Harmonic Vocal Clarity Exciter, Stereo Crossfeed & Balance).
 * Attached to AudioSession 0 for 100% universal system-wide playback filtering,
 * plus dynamically attaches to individual player audio sessions (Spotify, YouTube Music, local players)
 * so that DSP processing runs seamlessly in the background without needing the UI to be opened.
 */
object StudioDspManager {

    private const val TAG = "StudioDspManager"
    private const val GLOBAL_AUDIO_SESSION = 0

    // Global session effects (AudioSession 0)
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var standardEqualizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var presetReverb: PresetReverb? = null

    // Player-specific session effects
    private val sessionDynamicsMap = mutableMapOf<Int, DynamicsProcessing>()
    private val sessionEqualizerMap = mutableMapOf<Int, Equalizer>()
    private val sessionBassBoostMap = mutableMapOf<Int, BassBoost>()
    private val sessionVirtualizerMap = mutableMapOf<Int, Virtualizer>()
    private val sessionReverbMap = mutableMapOf<Int, PresetReverb>()

    @Volatile private var isInitialized = false
    @Volatile private var isCurrentlyEnabled = false

    private var currentClarityGain = 0.0f
    private var currentChannelBalance = 0.0f
    private var currentBasePreamp = 0.0f

    fun ensureInitialized(context: Context) {
        if (!isInitialized || (dynamicsProcessing == null && standardEqualizer == null)) {
            init(context)
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (isInitialized && (dynamicsProcessing != null || standardEqualizer != null)) return
        try {
            // Auto-grant permissions via root for system-wide audio control
            try {
                ShellUtils.fastCmd("pm grant ${context.packageName} android.permission.MODIFY_AUDIO_SETTINGS")
                ShellUtils.fastCmd("pm grant ${context.packageName} android.permission.DUMP")
                ShellUtils.fastCmd("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
            } catch (e: Exception) {}

            // 1. Initialize BassBoost, Virtualizer & PresetReverb on Global Session
            try {
                bassBoost = BassBoost(0, GLOBAL_AUDIO_SESSION).apply {
                    val strength = PowerampPresetManager.getDynamicSystemIntensity(context)
                    setStrength(strength.toShort())
                }
            } catch (e: Exception) {
                Log.w(TAG, "BassBoost init fallback: ${e.message}")
            }

            try {
                virtualizer = Virtualizer(0, GLOBAL_AUDIO_SESSION).apply {
                    val strength = PowerampPresetManager.getSurroundStrength(context)
                    setStrength(strength.toShort())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Virtualizer init fallback: ${e.message}")
            }

            try {
                presetReverb = PresetReverb(0, GLOBAL_AUDIO_SESSION).apply {
                    preset = PowerampPresetManager.getReverbPreset(context)
                }
            } catch (e: Exception) {
                Log.w(TAG, "PresetReverb init fallback: ${e.message}")
            }

            // 2. Initialize DynamicsProcessing or Standard Equalizer on Session 0
            initDynamicsProcessingEngine(context)

            isInitialized = true
            val masterOn = PowerampPresetManager.isMasterEnabled(context)
            setMasterEnabled(context, masterOn)

            val activePreset = PowerampPresetManager.getPresetByName(context, PowerampPresetManager.getActivePresetName(context))
            if (activePreset != null) {
                applyPreset(context, activePreset)
            }
            Log.d(TAG, "Studio DSP Engine initialized successfully in background (Master: $masterOn)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Studio DSP Engine: ${e.message}")
        }
    }

    private fun initDynamicsProcessingEngine(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val channelCount = 2
                val preEqBands = 2
                val postEqBands = 10

                val builder = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    channelCount,
                    true, preEqBands,
                    false, 0,
                    true, postEqBands,
                    true
                )

                dynamicsProcessing = DynamicsProcessing(0, GLOBAL_AUDIO_SESSION, builder.build())
                Log.d(TAG, "DynamicsProcessing Audio Engine created on Session $GLOBAL_AUDIO_SESSION")
                return
            } catch (e: Exception) {
                Log.w(TAG, "DynamicsProcessing not available on global session, falling back to Equalizer: ${e.message}")
            }
        }

        try {
            standardEqualizer = Equalizer(0, GLOBAL_AUDIO_SESSION).apply {
                enabled = isCurrentlyEnabled
            }
            Log.d(TAG, "Standard Equalizer fallback created on Session $GLOBAL_AUDIO_SESSION")
        } catch (e: Exception) {
            Log.e(TAG, "Standard Equalizer fallback failed: ${e.message}")
        }
    }

    @Synchronized
    fun onAudioSessionOpened(context: Context, sessionId: Int, packageName: String?) {
        if (sessionId <= 0 || sessionId == GLOBAL_AUDIO_SESSION) return
        if (sessionDynamicsMap.containsKey(sessionId) || sessionEqualizerMap.containsKey(sessionId)) {
            Log.d(TAG, "🎧 Session ID $sessionId is already attached to Studio DSP, skipping duplicate")
            return
        }
        Log.d(TAG, "🎧 New Media Session opened by [$packageName]: Session ID $sessionId -> Attaching Studio DSP")

        try {
            ensureInitialized(context)

            // 1. Attach DynamicsProcessing to player-specific session
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val builder = DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        2,
                        true, 2,
                        false, 0,
                        true, 10,
                        true
                    )
                    val dp = DynamicsProcessing(0, sessionId, builder.build()).apply {
                        enabled = isCurrentlyEnabled
                    }
                    sessionDynamicsMap[sessionId] = dp
                } catch (e: Exception) {
                    Log.w(TAG, "Could not attach DynamicsProcessing to session $sessionId: ${e.message}")
                }
            }

            // 2. Attach Equalizer fallback
            if (!sessionDynamicsMap.containsKey(sessionId)) {
                try {
                    val eq = Equalizer(0, sessionId).apply {
                        enabled = isCurrentlyEnabled
                    }
                    sessionEqualizerMap[sessionId] = eq
                } catch (e: Exception) {}
            }

            // 3. Attach BassBoost, Virtualizer, Reverb
            if (PowerampPresetManager.isDynamicSystemEnabled(context)) {
                try {
                    val bb = BassBoost(0, sessionId).apply {
                        val strength = PowerampPresetManager.getDynamicSystemIntensity(context)
                        setStrength(strength.toShort())
                        enabled = isCurrentlyEnabled
                    }
                    sessionBassBoostMap[sessionId] = bb
                } catch (e: Exception) {}
            }

            if (PowerampPresetManager.isSurroundEnabled(context)) {
                try {
                    val virt = Virtualizer(0, sessionId).apply {
                        val strength = PowerampPresetManager.getSurroundStrength(context)
                        setStrength(strength.toShort())
                        enabled = isCurrentlyEnabled
                    }
                    sessionVirtualizerMap[sessionId] = virt
                } catch (e: Exception) {}
            }

            if (PowerampPresetManager.isReverbEnabled(context)) {
                try {
                    val rev = PresetReverb(0, sessionId).apply {
                        preset = PowerampPresetManager.getReverbPreset(context)
                        enabled = isCurrentlyEnabled
                    }
                    sessionReverbMap[sessionId] = rev
                } catch (e: Exception) {}
            }

            // Sync current active preset curves to new session
            val activePreset = PowerampPresetManager.getPresetByName(context, PowerampPresetManager.getActivePresetName(context))
            if (activePreset != null) {
                applyPreset(context, activePreset)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching session $sessionId: ${e.message}")
        }
    }

    @Synchronized
    fun onAudioSessionClosed(sessionId: Int) {
        if (sessionId <= 0 || sessionId == GLOBAL_AUDIO_SESSION) return
        Log.d(TAG, "🔌 Detaching Studio DSP from Session: $sessionId")
        try {
            sessionDynamicsMap.remove(sessionId)?.apply {
                enabled = false
                release()
            }
            sessionEqualizerMap.remove(sessionId)?.apply {
                enabled = false
                release()
            }
            sessionBassBoostMap.remove(sessionId)?.apply {
                enabled = false
                release()
            }
            sessionVirtualizerMap.remove(sessionId)?.apply {
                enabled = false
                release()
            }
            sessionReverbMap.remove(sessionId)?.apply {
                enabled = false
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up session $sessionId: ${e.message}")
        }
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        isCurrentlyEnabled = enabled
        PowerampPresetManager.setMasterEnabled(context, enabled)

        try {
            dynamicsProcessing?.enabled = enabled
            standardEqualizer?.enabled = enabled
            bassBoost?.enabled = enabled && PowerampPresetManager.isDynamicSystemEnabled(context)
            virtualizer?.enabled = enabled && (PowerampPresetManager.isSurroundEnabled(context) || PowerampPresetManager.isCrossfeedEnabled(context))
            presetReverb?.enabled = enabled && PowerampPresetManager.isReverbEnabled(context)

            // Also update all attached player sessions
            sessionDynamicsMap.values.forEach { try { it.enabled = enabled } catch (e: Exception) {} }
            sessionEqualizerMap.values.forEach { try { it.enabled = enabled } catch (e: Exception) {} }
            sessionBassBoostMap.values.forEach { try { it.enabled = enabled && PowerampPresetManager.isDynamicSystemEnabled(context) } catch (e: Exception) {} }
            sessionVirtualizerMap.values.forEach { try { it.enabled = enabled && (PowerampPresetManager.isSurroundEnabled(context) || PowerampPresetManager.isCrossfeedEnabled(context)) } catch (e: Exception) {} }
            sessionReverbMap.values.forEach { try { it.enabled = enabled && PowerampPresetManager.isReverbEnabled(context) } catch (e: Exception) {} }

            Log.d(TAG, "Studio DSP Master set to: $enabled (Active Sessions: ${sessionDynamicsMap.size + 1})")
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling master DSP state: ${e.message}")
        }
    }

    fun applyPreset(context: Context, preset: EqualizerPreset) {
        PowerampPresetManager.setActivePresetName(context, preset.name)
        PowerampPresetManager.setActivePreamp(context, preset.preamp)

        currentBasePreamp = preset.preamp

        // 1. Apply Preamp Gain with Channel Balance
        updateInputGains()

        // 2. Apply Shelf and Peaking Bands
        val shelfBands = preset.bands.filter { it.type == 0 || it.type == 1 }
        val peakingBands = preset.bands.filter { it.type != 0 && it.type != 1 }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dynamicsProcessing?.let { applyDpBandsToEngine(it, shelfBands, peakingBands) }
            sessionDynamicsMap.values.forEach { dp ->
                applyDpBandsToEngine(dp, shelfBands, peakingBands)
            }
        }

        standardEqualizer?.let { applyEqBandsToEngine(it, peakingBands) }
        sessionEqualizerMap.values.forEach { eq ->
            applyEqBandsToEngine(eq, peakingBands)
        }
    }

    private fun applyDpBandsToEngine(dp: DynamicsProcessing, shelfBands: List<EqualizerBand>, peakingBands: List<EqualizerBand>) {
        try {
            for (ch in 0..1) {
                // A. Apply Pre-EQ Shelves
                val preEq = DynamicsProcessing.Eq(true, true, 2)
                val bassShelf = shelfBands.find { it.type == 0 }?.gain ?: 0.0f
                val trebleShelf = shelfBands.find { it.type == 1 }?.gain ?: 0.0f

                preEq.getBand(0).apply {
                    isEnabled = true
                    cutoffFrequency = 90.0f
                    gain = bassShelf
                }
                preEq.getBand(1).apply {
                    isEnabled = true
                    cutoffFrequency = 10000.0f
                    gain = trebleShelf + currentClarityGain
                }
                dp.setPreEqByChannelIndex(ch, preEq)

                // B. Apply Post-EQ 10-Band Graphic/Parametric Curve
                val postEq = DynamicsProcessing.Eq(true, true, 10)
                for (i in 0 until 10) {
                    val targetFreq = PowerampPresetManager.STANDARD_FREQUENCIES.getOrElse(i) { 1000 }
                    val matchedBand = peakingBands.minByOrNull { Math.abs(it.frequency - targetFreq) }
                    val bandGain = matchedBand?.gain ?: 0.0f

                    postEq.getBand(i).apply {
                        isEnabled = true
                        cutoffFrequency = targetFreq.toFloat()
                        gain = bandGain
                    }
                }
                dp.setPostEqByChannelIndex(ch, postEq)

                // C. Brickwall Peak Limiter (Prevents clipping & distortion)
                val limiter = DynamicsProcessing.Limiter(
                    true, true,
                    0,      // inChannel
                    1.0f,   // attackTime (1ms ultra-fast brickwall)
                    50.0f,  // releaseTime (50ms transparent release)
                    10.0f,  // ratio (10:1 hard knee)
                    -0.5f,  // threshold (-0.5dB ceiling protection)
                    0.0f    // postGain
                )
                dp.setLimiterByChannelIndex(ch, limiter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying DynamicsProcessing bands: ${e.message}")
        }
    }

    private fun applyEqBandsToEngine(eq: Equalizer, peakingBands: List<EqualizerBand>) {
        try {
            val numBands = eq.numberOfBands.toInt()
            for (i in 0 until numBands) {
                val centerFreqHz = eq.getCenterFreq(i.toShort()) / 1000
                val matched = peakingBands.minByOrNull { Math.abs(it.frequency - centerFreqHz) }
                val gainDb = matched?.gain ?: 0.0f
                val millibels = (gainDb * 100).toInt().coerceIn(-1500, 1500).toShort()
                eq.setBandLevel(i.toShort(), millibels)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying Standard Equalizer bands: ${e.message}")
        }
    }

    private fun updateInputGains() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                dynamicsProcessing?.setInputGainAllChannelsTo(currentBasePreamp)
                sessionDynamicsMap.values.forEach { dp ->
                    try {
                        dp.setInputGainAllChannelsTo(currentBasePreamp)
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting input gains: ${e.message}")
            }
        }
    }

    fun setPreampGain(gainDb: Float) {
        currentBasePreamp = gainDb
        updateInputGains()
    }

    fun setBassBoost(context: Context, strength: Int) {
        PowerampPresetManager.setBassBoostStrength(context, strength)
        val shortStr = strength.coerceIn(0, 1000).toShort()
        val enabled = isCurrentlyEnabled && strength > 0
        try {
            bassBoost?.apply {
                setStrength(shortStr)
                this.enabled = enabled
            }
            sessionBassBoostMap.values.forEach { bb ->
                try {
                    bb.setStrength(shortStr)
                    bb.enabled = enabled
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting bass boost: ${e.message}")
        }
    }

    // --- Differential Surround (Stereo Widening & Haas effect) ---
    fun setDifferentialSurround(context: Context, enabled: Boolean, strength: Int) {
        PowerampPresetManager.setSurroundEnabled(context, enabled)
        PowerampPresetManager.setSurroundStrength(context, strength)
        val shortStr = strength.coerceIn(0, 1000).toShort()
        val isEngaged = isCurrentlyEnabled && (enabled || PowerampPresetManager.isCrossfeedEnabled(context))
        try {
            virtualizer?.apply {
                setStrength(shortStr)
                this.enabled = isEngaged
            }
            sessionVirtualizerMap.values.forEach { virt ->
                try {
                    virt.setStrength(shortStr)
                    virt.enabled = isEngaged
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting Differential Surround: ${e.message}")
        }
    }

    // --- Reverberation (Acoustic Space / Hall Reflection) ---
    fun setReverberation(context: Context, enabled: Boolean, preset: Short) {
        PowerampPresetManager.setReverbEnabled(context, enabled)
        PowerampPresetManager.setReverbPreset(context, preset)
        val isEngaged = isCurrentlyEnabled && enabled
        try {
            presetReverb?.apply {
                this.preset = preset
                this.enabled = isEngaged
            }
            sessionReverbMap.values.forEach { rev ->
                try {
                    rev.preset = preset
                    rev.enabled = isEngaged
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting Reverberation: ${e.message}")
        }
    }

    // --- Dynamic System (Subwoofer Harmonics & Driver Resonance) ---
    fun setDynamicSystem(context: Context, enabled: Boolean, intensity: Int) {
        PowerampPresetManager.setDynamicSystemEnabled(context, enabled)
        PowerampPresetManager.setDynamicSystemIntensity(context, intensity)
        val shortStr = intensity.coerceIn(0, 1000).toShort()
        val isEngaged = isCurrentlyEnabled && enabled
        try {
            bassBoost?.apply {
                setStrength(shortStr)
                this.enabled = isEngaged
            }
            sessionBassBoostMap.values.forEach { bb ->
                try {
                    bb.setStrength(shortStr)
                    bb.enabled = isEngaged
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting Dynamic System: ${e.message}")
        }
    }

    // --- ViPER Audio Clarity (Harmonic Treble Exciter) ---
    fun setAudioClarity(context: Context, enabled: Boolean, level: Int) {
        PowerampPresetManager.setClarityEnabled(context, enabled)
        PowerampPresetManager.setClarityLevel(context, level)
        currentClarityGain = if (enabled) (level / 1000.0f) * 4.5f else 0.0f
        val activePreset = PowerampPresetManager.getPresetByName(context, PowerampPresetManager.getActivePresetName(context))
        if (activePreset != null) {
            applyPreset(context, activePreset)
        }
    }

    // --- Bauer Stereo Crossfeed ---
    fun setCrossfeed(context: Context, enabled: Boolean, level: Int) {
        PowerampPresetManager.setCrossfeedEnabled(context, enabled)
        PowerampPresetManager.setCrossfeedLevel(context, level)
        val str = if (enabled) (level * 0.7f).toInt().coerceIn(0, 1000) else 0
        setDifferentialSurround(context, enabled || PowerampPresetManager.isSurroundEnabled(context), if (enabled) str else PowerampPresetManager.getSurroundStrength(context))
    }

    // --- Channel Balance (-100 to +100) ---
    fun setChannelBalance(context: Context, balance: Int) {
        PowerampPresetManager.setChannelBalance(context, balance)
        currentChannelBalance = balance / 100.0f
        updateInputGains()
    }

    fun setVirtualizer(context: Context, strength: Int) {
        setDifferentialSurround(context, strength > 0, strength)
    }

    /**
     * Smart Sleep Guard: Suspends DSP processing to 0% CPU when audio is paused.
     */
    fun pauseDsp() {
        if (!isCurrentlyEnabled) return
        try {
            dynamicsProcessing?.enabled = false
            standardEqualizer?.enabled = false
            bassBoost?.enabled = false
            virtualizer?.enabled = false
            presetReverb?.enabled = false

            sessionDynamicsMap.values.forEach { try { it.enabled = false } catch (e: Exception) {} }
            sessionEqualizerMap.values.forEach { try { it.enabled = false } catch (e: Exception) {} }
            sessionBassBoostMap.values.forEach { try { it.enabled = false } catch (e: Exception) {} }
            sessionVirtualizerMap.values.forEach { try { it.enabled = false } catch (e: Exception) {} }
            sessionReverbMap.values.forEach { try { it.enabled = false } catch (e: Exception) {} }

            Log.d(TAG, "Studio DSP put to SLEEP (0% CPU)")
        } catch (e: Exception) {}
    }

    /**
     * Smart Sleep Guard: Resumes DSP processing in 0ms when audio starts playing.
     * Guarantees that DSP is fully initialized and operational even if UI was never opened!
     */
    fun resumeDsp(context: Context) {
        val masterOn = PowerampPresetManager.isMasterEnabled(context)
        if (!masterOn) return

        ensureInitialized(context)

        try {
            dynamicsProcessing?.enabled = true
            standardEqualizer?.enabled = true
            bassBoost?.enabled = PowerampPresetManager.isDynamicSystemEnabled(context)
            virtualizer?.enabled = PowerampPresetManager.isSurroundEnabled(context) || PowerampPresetManager.isCrossfeedEnabled(context)
            presetReverb?.enabled = PowerampPresetManager.isReverbEnabled(context)

            sessionDynamicsMap.values.forEach { try { it.enabled = true } catch (e: Exception) {} }
            sessionEqualizerMap.values.forEach { try { it.enabled = true } catch (e: Exception) {} }
            sessionBassBoostMap.values.forEach { try { it.enabled = PowerampPresetManager.isDynamicSystemEnabled(context) } catch (e: Exception) {} }
            sessionVirtualizerMap.values.forEach { try { it.enabled = PowerampPresetManager.isSurroundEnabled(context) || PowerampPresetManager.isCrossfeedEnabled(context) } catch (e: Exception) {} }
            sessionReverbMap.values.forEach { try { it.enabled = PowerampPresetManager.isReverbEnabled(context) } catch (e: Exception) {} }

            Log.d(TAG, "Studio DSP WOKE UP in 0ms (Active Sessions: ${sessionDynamicsMap.size + 1})")
        } catch (e: Exception) {
            // Re-init if system audio server crashed or restarted
            init(context)
        }
    }

    fun release() {
        try {
            dynamicsProcessing?.release()
            standardEqualizer?.release()
            bassBoost?.release()
            virtualizer?.release()
            presetReverb?.release()

            sessionDynamicsMap.values.forEach { try { it.release() } catch (e: Exception) {} }
            sessionEqualizerMap.values.forEach { try { it.release() } catch (e: Exception) {} }
            sessionBassBoostMap.values.forEach { try { it.release() } catch (e: Exception) {} }
            sessionVirtualizerMap.values.forEach { try { it.release() } catch (e: Exception) {} }
            sessionReverbMap.values.forEach { try { it.release() } catch (e: Exception) {} }

            sessionDynamicsMap.clear()
            sessionEqualizerMap.clear()
            sessionBassBoostMap.clear()
            sessionVirtualizerMap.clear()
            sessionReverbMap.clear()

            dynamicsProcessing = null
            standardEqualizer = null
            bassBoost = null
            virtualizer = null
            presetReverb = null
            isInitialized = false
        } catch (e: Exception) {}
    }
}
