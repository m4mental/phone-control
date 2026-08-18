package com.example.phonecontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d("BootReceiver", "Device rebooted. Initializing Phone Control...")
            
            kotlin.concurrent.thread {
                // Aggressive root-level activation
                ShellUtils.runAsRoot("dumpsys deviceidle whitelist +com.example.phonecontrol")
                
                // Re-apply RAM and Multitasking settings (Immediate for better boot performance)
                val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                val zram = prefs.getString("zram_size", "rbZram4G") ?: "rbZram4G"
                val profile = prefs.getString("ram_profile", "rbProfileBalance") ?: "rbProfileBalance"
                TweakManager.applyRamSettings(zram, profile)

                // Re-apply Network settings
                val dns = prefs.getString("network_dns", "rbDnsDefault") ?: "rbDnsDefault"
                val tcp = prefs.getBoolean("network_tcp_tweaks", false)
                val lowLat = prefs.getBoolean("network_low_latency", false)
                TweakManager.applyNetworkSettings(dns, tcp, lowLat)
                
                val serviceIntent = Intent(context, AutoTweakService::class.java).apply {
                    putExtra("delayed_start", true)
                }
                
                try {
                    context.startService(serviceIntent)
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to start service normally: ${e.message}")
                }
            }
        }
    }
}
