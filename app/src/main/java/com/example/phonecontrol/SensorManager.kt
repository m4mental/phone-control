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
        
        val privacyValue = if (enabled) "0" else "1"
        ShellUtils.fastCmd("service call sensor_privacy 2 i32 $privacyValue")

        // 2. Prevent Android OS from forcibly turning Auto-Rotate ON when sensors are restored
        val targetRotation = if (enabled) {
            prefs.getInt("user_saved_auto_rotate", currentRotation)
        } else {
            0
        }
        ShellUtils.fastCmd("settings put system accelerometer_rotation $targetRotation")
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
