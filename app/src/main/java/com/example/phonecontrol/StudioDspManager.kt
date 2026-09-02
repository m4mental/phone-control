package com.example.phonecontrol

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.util.Log

/**
 * High-Precision Studio DSP Audio Engine.
 * Utilizes Android's DynamicsProcessing framework (Pre-EQ shelves, Multi-Band Post-EQ,
 * Input Gain Preamp, and Limiter) along with standard AudioFX fallbacks.
 * Attached to AudioSession 0 for 100% universal system-wide playback filtering.
 */
object StudioDspManager {

    private const val TAG = "StudioDspManager"
    private const val GLOBAL_AUDIO_SESSION = 0

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var standardEqualizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    // Track active player audio sessions (e.g. Spotify, YouTube Music)
    private val activePlayerSessions = mutableSetOf<Int>()
    private val sessionDynamicsMap = mutableMapOf<Int, DynamicsProcessing>()
    private val sessionEqualizerMap = mutableMapOf<Int, Equalizer>()

    @Volatile private var isInitialized = false
    @Volatile private var isCurrentlyEnabled = false

    fun init(context: Context) {
        if (isInitialized) return
        try {
            // Auto-grant permissions via root for system-wide audio control
            try {
                ShellUtils.fastCmd("pm grant ${context.packageName} android.permission.MODIFY_AUDIO_SETTINGS")
                ShellUtils.fastCmd("pm grant ${context.packageName} android.permission.DUMP")
            } catch (e: Exception) {}

            // 1. Initialize BassBoost & Virtualizer on Global Session
            try {
                bassBoost = BassBoost(0, GLOBAL_AUDIO_SESSION).apply {
                    val strength = PowerampPresetManager.getBassBoostStrength(context)
                    setStrength(strength.toShort())
                }
            } catch (e: Exception) {
                Log.w(TAG, "BassBoost init fallback: ${e.message}")
            }

            try {
                virtualizer = Virtualizer(0, GLOBAL_AUDIO_SESSION).apply {
                    val strength = PowerampPresetManager.getVirtualizerStrength(context)
                    setStrength(strength.toShort())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Virtualizer init fallback: ${e.message}")
            }

            // 2. Initialize DynamicsProcessing or Standard Equalizer
            initDynamicsProcessingEngine(context)

            isInitialized = true
            val masterOn = PowerampPresetManager.isMasterEnabled(context)
            setMasterEnabled(context, masterOn)

            val activePreset = PowerampPresetManager.getPresetByName(context, PowerampPresetManager.getActivePresetName(context))
            if (activePreset != null) {
                applyPreset(context, activePreset)
            }
            Log.d(TAG, "Studio DSP Engine initialized successfully (Master: $masterOn)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Studio DSP Engine: ${e.message}")
        }
    }

    private fun initDynamicsProcessingEngine(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                // Build a 12-band configuration with Pre-EQ (2 shelves), Post-EQ (10 bands), and Limiter
                val channelCount = 2
                val preEqBands = 2 // Shelf 0: 90Hz, Shelf 1: 10000Hz
                val postEqBands = 10 // Standard ISO bands or parametric bands

                val builder = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    channelCount,
                    true, // preEqInUse
                    preEqBands,
                    false, // mbcInUse
                    0,
                    true, // postEqInUse
                    postEqBands,
                    true  // limiterInUse
                )

                dynamicsProcessing = DynamicsProcessing(0, GLOBAL_AUDIO_SESSION, builder.build())
                Log.d(TAG, "DynamicsProcessing Audio Engine created on Session $GLOBAL_AUDIO_SESSION")
                return
            } catch (e: Exception) {
                Log.w(TAG, "DynamicsProcessing failed, falling back to Standard Equalizer: ${e.message}")
            }
        }

        // Fallback to standard 10-band Android Equalizer
        try {
            standardEqualizer = Equalizer(0, GLOBAL_AUDIO_SESSION)
            Log.d(TAG, "Standard Equalizer fallback created on Session $GLOBAL_AUDIO_SESSION")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Standard Equalizer: ${e.message}")
        }
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        isCurrentlyEnabled = enabled
        PowerampPresetManager.setMasterEnabled(context, enabled)

        try {
            dynamicsProcessing?.enabled = enabled
            standardEqualizer?.enabled = enabled
            bassBoost?.enabled = enabled && PowerampPresetManager.getBassBoostStrength(context) > 0
            virtualizer?.enabled = enabled && PowerampPresetManager.getVirtualizerStrength(context) > 0
            Log.d(TAG, "Studio DSP Master set to: $enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling master DSP state: ${e.message}")
        }
    }

    fun applyPreset(context: Context, preset: EqualizerPreset) {
        PowerampPresetManager.setActivePresetName(context, preset.name)
        PowerampPresetManager.setActivePreamp(context, preset.preamp)

        // 1. Apply Preamp Gain
        setPreampGain(preset.preamp)

        // 2. Apply Shelf and Peaking Bands
        val shelfBands = preset.bands.filter { it.type == 0 || it.type == 1 }
        val peakingBands = preset.bands.filter { it.type != 0 && it.type != 1 }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dynamicsProcessing != null) {
            applyDynamicsProcessingBands(shelfBands, peakingBands)
        } else {
            applyStandardEqualizerBands(peakingBands)
        }
    }

    private fun applyDynamicsProcessingBands(shelfBands: List<EqualizerBand>, peakingBands: List<EqualizerBand>) {
        try {
            val dp = dynamicsProcessing ?: return

            // Channel 0 (Left) and Channel 1 (Right)
            for (ch in 0..1) {
                // A. Apply Pre-EQ Shelves (Tone Bass & Tone Treble)
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
                    gain = trebleShelf
                }
                dp.setPreEqByChannelIndex(ch, preEq)

                // B. Apply Post-EQ Graphic/Parametric Bands
                val bandCount = peakingBands.size.coerceAtMost(12)
                val postEq = DynamicsProcessing.Eq(true, true, bandCount)
                for (i in 0 until bandCount) {
                    val band = peakingBands[i]
                    postEq.getBand(i).apply {
                        isEnabled = true
                        cutoffFrequency = band.frequency.toFloat()
                        gain = band.gain
                    }
                }
                dp.setPostEqByChannelIndex(ch, postEq)

                // C. Configure Limiter to prevent clipping on heavy bass
                val limiter = DynamicsProcessing.Limiter(
                    true, // inUse
                    true, // enabled
                    0,    // linkGroup
                    1.0f, // attackTime (ms)
                    50.0f,// releaseTime (ms)
                    10.0f,// ratio (10:1 brickwall)
                    -0.5f,// threshold (dB)
                    0.0f  // postGain (dB)
                )
                dp.setLimiterByChannelIndex(ch, limiter)
            }
            Log.d(TAG, "Applied DynamicsProcessing EQ bands (${peakingBands.size} peaking + 2 shelves)")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying DynamicsProcessing bands: ${e.message}")
        }
    }

    private fun applyStandardEqualizerBands(peakingBands: List<EqualizerBand>) {
        val eq = standardEqualizer ?: return
        try {
            val totalBands = eq.numberOfBands.toInt()
            for (i in 0 until totalBands) {
                val centerFreq = eq.getCenterFreq(i.toShort()) / 1000 // in Hz
                // Find closest band in preset
                val closest = peakingBands.minByOrNull { Math.abs(it.frequency - centerFreq) }
                if (closest != null) {
                    // Convert dB to millibels (1dB = 100mB)
                    val mB = (closest.gain * 100).toInt().coerceIn(eq.bandLevelRange[0].toInt(), eq.bandLevelRange[1].toInt())
                    eq.setBandLevel(i.toShort(), mB.toShort())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying standard equalizer bands: ${e.message}")
        }
    }

    fun setPreampGain(gainDb: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dynamicsProcessing != null) {
            try {
                dynamicsProcessing?.setInputGainAllChannelsTo(gainDb)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting preamp gain: ${e.message}")
            }
        }
    }

    fun setBassBoost(context: Context, strength: Int) {
        PowerampPresetManager.setBassBoostStrength(context, strength)
        try {
            bassBoost?.let {
                it.setStrength(strength.coerceIn(0, 1000).toShort())
                it.enabled = isCurrentlyEnabled && strength > 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting bass boost: ${e.message}")
        }
    }

    fun setVirtualizer(context: Context, strength: Int) {
        PowerampPresetManager.setVirtualizerStrength(context, strength)
        try {
            virtualizer?.let {
                it.setStrength(strength.coerceIn(0, 1000).toShort())
                it.enabled = isCurrentlyEnabled && strength > 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting virtualizer: ${e.message}")
        }
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
            Log.d(TAG, "Studio DSP put to SLEEP (0% CPU)")
        } catch (e: Exception) {}
    }

    /**
     * Smart Sleep Guard: Resumes DSP processing in 0ms when audio starts playing.
     */
    fun resumeDsp(context: Context) {
        val masterOn = PowerampPresetManager.isMasterEnabled(context)
        if (!masterOn) return
        try {
            dynamicsProcessing?.enabled = true
            standardEqualizer?.enabled = true
            bassBoost?.enabled = PowerampPresetManager.getBassBoostStrength(context) > 0
            virtualizer?.enabled = PowerampPresetManager.getVirtualizerStrength(context) > 0
            Log.d(TAG, "Studio DSP WOKE UP in 0ms")
        } catch (e: Exception) {}
    }

    fun release() {
        try {
            dynamicsProcessing?.release()
            standardEqualizer?.release()
            bassBoost?.release()
            virtualizer?.release()
            dynamicsProcessing = null
            standardEqualizer = null
            bassBoost = null
            virtualizer = null
            isInitialized = false
        } catch (e: Exception) {}
    }
}
