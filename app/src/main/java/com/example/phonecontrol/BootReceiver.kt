package com.example.phonecontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d("BootReceiver", "Device rebooted. Initializing Phone Control...")
            
            // Aggressive root-level activation
            ShellUtils.runAsRoot("dumpsys deviceidle whitelist +com.example.phonecontrol")
            
            val serviceIntent = Intent(context, AutoTweakService::class.java).apply {
                putExtra("delayed_start", true)
            }
            
            // In Android 12+, we try starting the service normally first. 
            // If the app was opened before, this will work.
            try {
                context.startService(serviceIntent)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start service normally: ${e.message}")
            }
        }
    }
}
