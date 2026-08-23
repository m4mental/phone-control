package com.example.phonecontrol

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * One-Shot Setup Service.
 * Starts the Native Daemon and then stops itself to save battery/RAM.
 */
class AutoTweakService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensure storage structure is ready
        BackupManager.ensureStorageStructure()

        // Start the lightweight Native Daemon
        DaemonManager.startDaemon(this)
        
        // Initial setup for boot/first launch
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        if (prefs.getBoolean("silent_system_enabled", false)) {
            TweakManager.setSilentSystem(true)
        }
        if (prefs.getBoolean("storage_boost_enabled", false)) {
            StorageManager.applyStorageBoost(true)
        }

        // Apply Sensor Shield settings
        SensorManager.applySensorShield(this)

        // We don't need to stay in memory anymore. The Shell Daemon handles triggers.
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
