package com.example.phonecontrol

/**
 * AutoEQ Headphone Calibration Database.
 * Contains calibrated Harman Target compensation profiles for the world's most popular
 * headphones, IEMs, and TWS earbuds.
 */
data class AutoEqHeadphone(
    val brand: String,
    val model: String,
    val description: String,
    val preset: EqualizerPreset
)

object AutoEqManager {

    val POPULAR_HEADPHONES: List<AutoEqHeadphone> = listOf(
        // --- NOTHING ---
        AutoEqHeadphone(
            brand = "Nothing",
            model = "Nothing Ear (2)",
            description = "Harman In-Ear Target (Calibrated by Crinacle)",
            preset = EqualizerPreset(
                name = "Nothing Ear (2) [AutoEQ]",
                preamp = -3.2f,
                parametric = false,
                bands = mutableListOf(
                    EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = 2.4f),
                    EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = -1.5f),
                    EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 3.5f),
                    EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = 2.8f),
                    EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = 0.5f),
                    EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = -1.2f),
                    EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = -0.8f),
                    EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 0.4f),
                    EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 1.6f),
                    EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = -2.1f),
                    EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 2.0f),
                    EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 1.0f)
                ),
                surroundEnabled = true, surroundStrength = 400,
                reverbEnabled = false, dynamicSystemEnabled = true, dynamicSystemIntensity = 500
            )
        ),
        AutoEqHeadphone(
            brand = "Nothing",
            model = "Nothing Ear (a)",
            description = "Harman Bass & Treble Air Target",
            preset = EqualizerPreset(
                name = "Nothing Ear (a) [AutoEQ]",
                preamp = -2.8f,
                parametric = false,
                bands = mutableListOf(
                    EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = 3.2f),
                    EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 0.5f),
                    EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 4.2f),
                    EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = 3.5f),
                    EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = 1.2f),
                    EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = -0.5f),
                    EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = -1.0f),
                    EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 0.0f),
                    EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 1.8f),
                    EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = -1.5f),
                    EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 2.5f),
                    EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 2.0f)
                ),
                surroundEnabled = true, surroundStrength = 500,
                reverbEnabled = false, dynamicSystemEnabled = true, dynamicSystemIntensity = 600
            )
        ),
        AutoEqHeadphone(
            brand = "Nothing",
            model = "Nothing Ear (1)",
            description = "Linear Midrange & Sub-Bass Target",
            preset = EqualizerPreset(
                name = "Nothing Ear (1) [AutoEQ]",
                preamp = -2.5f,
                parametric = false,
                bands = mutableListOf(
                    EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = 2.8f),
                    EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = -2.0f),
                    EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 3.8f),
                    EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = 3.0f),
                    EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = 0.8f),
                    EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = -1.5f),
                    EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = -0.5f),
                    EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 0.8f),
                    EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 2.0f),
                    EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = -2.5f),
                    EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 1.5f),
                    EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 0.5f)
                )
            )
        ),

        // --- SONY ---
        AutoEqHeadphone(
            brand = "Sony",
            model = "Sony WH-1000XM5",
            description = "Harman Over-Ear Target (Clarity & Sub-Bass Taming)",
            preset = EqualizerPreset(
                name = "Sony WH-1000XM5 [AutoEQ]",
                preamp = -4.0f,
                parametric = false,
                bands = mutableListOf(
                    EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = -2.5f),
                    EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 3.5f),
                    EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 1.5f),
                    EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = -1.8f),
                    EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = -3.2f),
                    EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = -2.5f),
                    EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = 0.5f),
                    EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 2.0f),
                    EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 3.8f),
                    EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = 1.5f),
                    EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 4.0f),
                    EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 3.0f)
                ),
                surroundEnabled = true, surroundStrength = 600,
                reverbEnabled = false, dynamicSystemEnabled = true, dynamicSystemIntensity = 500
            )
        ),
        AutoEqHeadphone(
            brand = "Sony",
            model = "Sony WH-1000XM4",
            description = "Harman Over-Ear Target (Mud Removal & High-End Sparkle)",
            preset = EqualizerPreset(
                name = "Sony WH-1000XM4 [AutoEQ]",
                preamp = -3.8f,
                parametric = false,
                bands = mutableListOf(
                    EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = -3.0f),
                    EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 4.0f),
                    EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 2.0f),
                    EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = -2.5f),
                    EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = -4.0f),
                    EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = -3.0f),
                    EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = 1.0f),
                    EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 2.5f),
                    EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 4.2f),
                    EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = 0.5f),
                    EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 4.5f),
                    EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 3.5f)
                ),
                surroundEnabled = true, surroundStrength = 650,
                reverbEnabled = false, dynamicSystemEnabled = true, dynamicSystemIntensity = 550
            )
        ),
        AutoEqHeadphone(
            brand = "Sony",
            model = "Sony WF-1000XM5 / XM4",
            description = "Harman In-Ear Audiophile Calibration",
            preset = EqualizerPreset(
                name = "Sony WF-1000XM5 [AutoEQ]",
                preamp = -3.0f,
                parametric = false,
                bands = mutableListOf(
                    EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = -1.5f),
                    EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 2.5f),
                    EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 2.5f),
                    EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = 0.5f),
                    EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = -2.0f),
                    EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = -1.8f),
                    EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = 0.2f),
                    EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 1.5f),
                    EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 3.0f),
                    EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = -1.0f),
                    EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 3.2f),
                    EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 2.0f)
                )
            )
        ),

        // --- APPLE ---
        AutoEqHeadphone(
            brand = "Apple",
            model = "AirPods Pro 2 / Pro",
            description = "Harman Target In-Ear (Sub-Bass Extension & Treble Air)",
            preset = EqualizerPreset(
                name = "AirPods Pro 2 [AutoEQ]",
                preamp = -2.5f,
                parametric = false,
                bands = mutableListOf(
                    EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = 2.5f),
                    EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 2.0f),
                    EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 4.0f),
                    EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = 2.8f),
                    EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = 0.5f),
                    EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = -0.5f),
                    EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = 0.0f),
                    EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 0.5f),
                    EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 1.2f),
                    EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = 1.0f),
                    EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 2.8f),
                    EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 3.0f)
                ),
                surroundEnabled = true, surroundStrength = 550,
                reverbEnabled = false, dynamicSystemEnabled = true, dynamicSystemIntensity = 500
            )
        ),
        AutoEqHeadphone(
            brand = "Apple",
            model = "AirPods Max",
            description = "Harman Over-Ear Target (Deep Sub-Bass & Neutral Mids)",
            preset = EqualizerPreset(
                name = "AirPods Max [AutoEQ]",
                preamp = -3.0f,
                parametric = false,
                bands = mutableListOf(
                    EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = 1.5f),
                    EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 2.5f),
                    EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 3.0f),
                    EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = 1.8f),
                    EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = -0.5f),
                    EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = -1.2f),
                    EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = 0.2f),
                    EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 1.5f),
                    EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 2.2f),
                    EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = -1.8f),
                    EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 3.0f),
                    EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 2.5f)
                )
            )
        ),

        // --- SENNHEISER ---
        AutoEqHeadphone(
            brand = "Sennheiser",
            model = "Sennheiser HD 600 / HD 650",
            description = "Harman Open-Back Reference (Sub-Bass Extension)",
            preset = EqualizerPreset(
                name = "Sennheiser HD 600 [AutoEQ]",
                preamp = -5.0f,
                parametric = false,
                bands = mutableListOf(
                    EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = 6.0f),
                    EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 1.5f),
                    EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 7.5f),
                    EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = 5.2f),
                    EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = 1.5f),
                    EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = -0.5f),
                    EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = 0.0f),
                    EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 0.0f),
                    EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 0.5f),
                    EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = -1.0f),
                    EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 2.0f),
                    EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 1.8f)
                ),
                surroundEnabled = false, reverbEnabled = false, dynamicSystemEnabled = true, dynamicSystemIntensity = 400
            )
        ),
        AutoEqHeadphone(
            brand = "Sennheiser",
            model = "Sennheiser Momentum 4",
            description = "Harman Over-Ear Target (Bass Tightening & Crystalline Vocal)",
            preset = EqualizerPreset(
                name = "Sennheiser Momentum 4 [AutoEQ]",
                preamp = -3.5f,
                parametric = false,
                bands = mutableListOf(
                    EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = -2.0f),
                    EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 2.8f),
                    EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 1.0f),
                    EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = -1.5f),
                    EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = -3.0f),
                    EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = -2.0f),
                    EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = 0.8f),
                    EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 2.2f),
                    EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 3.5f),
                    EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = 0.5f),
                    EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 3.5f),
                    EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 2.5f)
                )
            )
        ),

        // --- MOONDROP & AUDIOPHILE IEMS ---
        AutoEqHeadphone(
            brand = "Moondrop",
            model = "Moondrop Chu / Chu II",
            description = "Harman VDSF Target (Sub-Bass Warmth & Sibilance Reduction)",
            preset = EqualizerPreset(
                name = "Moondrop Chu II [AutoEQ]",
                preamp = -2.5f,
                parametric = false,
                bands = mutableListOf(
                    EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = 2.0f),
                    EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = -1.8f),
                    EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 3.0f),
                    EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = 2.2f),
                    EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = 0.5f),
                    EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = -0.8f),
                    EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = 0.0f),
                    EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 0.5f),
                    EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 1.0f),
                    EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = -2.0f),
                    EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = -1.5f),
                    EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 1.5f)
                )
            )
        ),
        AutoEqHeadphone(
            brand = "Moondrop",
            model = "Moondrop Aria / Blessing 2",
            description = "Harman Reference IEM Signature",
            preset = EqualizerPreset(
                name = "Moondrop Aria [AutoEQ]",
                preamp = -2.0f,
                parametric = false,
                bands = mutableListOf(
                    EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = 1.5f),
                    EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 0.8f),
                    EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 2.5f),
                    EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = 1.8f),
                    EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = 0.2f),
                    EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = -0.5f),
                    EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = 0.0f),
                    EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 0.5f),
                    EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 1.5f),
                    EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = -0.5f),
                    EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 1.2f),
                    EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 2.0f)
                )
            )
        ),

        // --- ONEPLUS ---
        AutoEqHeadphone(
            brand = "OnePlus",
            model = "OnePlus Buds Pro 2 / 3",
            description = "Harman Target (Bass Clarification & Dynaudio Tuning)",
            preset = EqualizerPreset(
                name = "OnePlus Buds Pro 2 [AutoEQ]",
                preamp = -2.8f,
                parametric = false,
                bands = mutableListOf(
                    EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = -1.2f),
                    EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 2.2f),
                    EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 2.0f),
                    EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = 0.5f),
                    EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = -2.2f),
                    EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = -1.5f),
                    EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = 0.5f),
                    EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 1.8f),
                    EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 2.8f),
                    EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = -1.0f),
                    EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 3.0f),
                    EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 2.5f)
                )
            )
        ),

        // --- BOAT ---
        AutoEqHeadphone(
            brand = "boAt",
            model = "boAt Rockerz 450 / 550",
            description = "Harman Target (Boomy Bass Tamed & Vocal Clarity Restored)",
            preset = EqualizerPreset(
                name = "boAt Rockerz [AutoEQ]",
                preamp = -4.2f,
                parametric = false,
                bands = mutableListOf(
                    EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = -5.0f),
                    EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 5.0f),
                    EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 2.0f),
                    EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = -3.5f),
                    EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = -6.0f),
                    EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = -4.5f),
                    EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = 1.5f),
                    EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 3.8f),
                    EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 5.0f),
                    EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = 2.0f),
                    EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 6.0f),
                    EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 5.5f)
                ),
                surroundEnabled = true, surroundStrength = 600,
                reverbEnabled = false, dynamicSystemEnabled = true, dynamicSystemIntensity = 500
            )
        ),

        // --- REALME ---
        AutoEqHeadphone(
            brand = "Realme",
            model = "Realme Buds Air 5 Pro",
            description = "Harman Target In-Ear (Hi-Res LDAC Tuning)",
            preset = EqualizerPreset(
                name = "Realme Buds Air 5 Pro [AutoEQ]",
                preamp = -2.5f,
                parametric = false,
                bands = mutableListOf(
                    EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = 1.5f),
                    EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 1.8f),
                    EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = 3.0f),
                    EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = 2.0f),
                    EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = -0.5f),
                    EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = -1.2f),
                    EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = 0.2f),
                    EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 1.5f),
                    EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 2.4f),
                    EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = -0.8f),
                    EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 2.5f),
                    EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 2.2f)
                )
            )
        ),

        // --- PHONE SPEAKER CALIBRATION ---
        AutoEqHeadphone(
            brand = "Device",
            model = "Built-in Phone Speaker (Clarity & Safety)",
            description = "Acoustic Speaker Protection & Vocal Boost (Anti-Distortion)",
            preset = EqualizerPreset(
                name = "Phone Speaker [Clarity Guard]",
                preamp = 0.0f,
                parametric = false,
                bands = mutableListOf(
                    EqualizerBand(type = 0, frequency = 90, q = 0.8f, gain = -8.0f), // Cut sub-bass to protect tiny drivers
                    EqualizerBand(type = 1, frequency = 10000, q = 0.6f, gain = 3.0f), // Air boost
                    EqualizerBand(type = 2, frequency = 31, q = 0.0f, gain = -12.0f), // High-pass protection
                    EqualizerBand(type = 2, frequency = 62, q = 0.0f, gain = -8.0f),
                    EqualizerBand(type = 2, frequency = 124, q = 0.0f, gain = -3.0f),
                    EqualizerBand(type = 2, frequency = 249, q = 0.0f, gain = 1.0f),
                    EqualizerBand(type = 2, frequency = 498, q = 0.0f, gain = 2.5f),  // Warmth
                    EqualizerBand(type = 2, frequency = 996, q = 0.0f, gain = 4.0f),  // Voice clarity
                    EqualizerBand(type = 2, frequency = 1995, q = 0.0f, gain = 4.5f), // Speech presence
                    EqualizerBand(type = 2, frequency = 3993, q = 0.0f, gain = 3.0f),
                    EqualizerBand(type = 2, frequency = 7993, q = 0.0f, gain = 3.5f),
                    EqualizerBand(type = 2, frequency = 16000, q = 0.0f, gain = 2.0f)
                ),
                surroundEnabled = false, reverbEnabled = false, dynamicSystemEnabled = false
            )
        )
    )

    fun searchHeadphones(query: String): List<AutoEqHeadphone> {
        if (query.isBlank()) return POPULAR_HEADPHONES
        val q = query.trim().lowercase()
        return POPULAR_HEADPHONES.filter {
            it.brand.lowercase().contains(q) ||
            it.model.lowercase().contains(q) ||
            it.description.lowercase().contains(q)
        }
    }
}
