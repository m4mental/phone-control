package com.example.phonecontrol

object SensorManager {
    /**
     * Enables or disables system sensors globally.
     * @param enabled True to allow sensors, False to block them (Firewall mode).
     */
    fun setSensorsEnabled(enabled: Boolean) {
        val value = if (enabled) "0" else "1"
        // Method 1: Global Settings (Android 10+)
        // 0 = Allowed, 1 = Restricted
        ShellUtils.fastCmd("settings put global sensor_privacy $value")
        
        // Method 2: Service Call (Brute force for some devices/versions)
        // 1 = set sensor privacy, i32 1 = block, i32 0 = allow
        val privacyValue = if (enabled) "0" else "1"
        ShellUtils.fastCmd("service call sensor_privacy 2 i32 $privacyValue")
        
        // Method 3: Kill sensor hal (Extreme, use with caution if above fails)
        // if (!enabled) ShellUtils.fastCmd("stop sensors-hal-2-0")
        // else ShellUtils.fastCmd("start sensors-hal-2-0")
    }
}
