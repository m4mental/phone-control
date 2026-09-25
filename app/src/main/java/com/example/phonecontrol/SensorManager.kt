package com.example.phonecontrol

import android.content.Context
import android.provider.Settings

object SensorManager {
    /**
     * Enables or disables system sensors globally while strictly preserving the user's Auto-Rotate preference.
     * @param enabled True to allow sensors, False to block them (Firewall mode).
     */
    fun setSensorsEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        
        // 1. Capture current user auto-rotate preference BEFORE toggling sensor privacy
        val currentRotation = try {
            Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        } catch (e: Exception) {
            0
        }
        
        if (!enabled) {
            prefs.edit().putInt("user_saved_auto_rotate", currentRotation).apply()
        }

        val value = if (enabled) "0" else "1"
        ShellUtils.fastCmd("settings put global sensor_privacy $value")

        // 2. Strictly preserve and enforce user's exact Auto-Rotate state
        if (enabled) {
            val saved = if (prefs.contains("user_saved_auto_rotate")) {
                val s = prefs.getInt("user_saved_auto_rotate", 0)
                prefs.edit().remove("user_saved_auto_rotate").apply()
                s
            } else {
                currentRotation
            }

            // CRITICAL GUARD: If the user currently has Auto-Rotate disabled (0), NEVER flip it to 1!
            val targetRotation = if (currentRotation == 0) 0 else saved

            ShellUtils.fastCmd("settings put system accelerometer_rotation $targetRotation")
            
            // Android SensorPrivacyService asynchronously attempts to flip rotation;
            // re-enforce the user's exact preference to ensure it stays strictly off
            kotlin.concurrent.thread {
                try {
                    Thread.sleep(350)
                    ShellUtils.fastCmd("settings put system accelerometer_rotation $targetRotation")
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Applies individual sensor blocks based on user preferences.
     */
    fun applySensorShield(context: Context) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        
        // 1. NFC Radio
        val blockNfc = prefs.getBoolean("block_nfc", false)
        ShellUtils.fastCmd(if (blockNfc) "svc nfc disable" else "svc nfc enable")

        // 2. Motion, Gyro, Mag, Light
        val needsPrivacy = prefs.getBoolean("block_gyro", false) || 
                          prefs.getBoolean("block_mag", false) || 
                          prefs.getBoolean("block_light", false) || 
                          prefs.getBoolean("block_motion", false)

        setSensorsEnabled(context, !needsPrivacy)
    }
}
