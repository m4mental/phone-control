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
                
                // Re-apply Kernel Mode Settings
                val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                val savedModeKey = prefs.getString("selected_mode", "rbBalance") ?: "rbBalance"
                val activeMode = when(savedModeKey) {
                    "rbPowerSaver" -> "Power Saver"
                    "rbPerformance" -> "Performance"
                    else -> "Balance"
                }
                TweakManager.applyGlobalMode(activeMode)
                
                // Re-apply RAM and Multitasking settings
                val zram = prefs.getString("zram_size", "rbZram4G") ?: "rbZram4G"
                val profile = prefs.getString("ram_profile", "rbProfileBalance") ?: "rbProfileBalance"
                TweakManager.applyRamSettings(zram, profile)

                // Re-apply Network settings
                val dns = prefs.getString("network_dns", "rbDnsDefault") ?: "rbDnsDefault"
                val tcp = prefs.getBoolean("network_tcp_tweaks", false)
                val lowLat = prefs.getBoolean("network_low_latency", false)
                TweakManager.applyNetworkSettings(dns, tcp, lowLat)

                // Re-apply Battery Engine settings
                if (prefs.getBoolean("batt_usb_fast_charge", false)) {
                    BatteryManager.setUsbFastCharge(true)
                }
                if (prefs.getBoolean("batt_bypass_enabled", false)) {
                    BatteryManager.setBypassEnabled(true)
                }
                if (prefs.getBoolean("batt_charge_speed_enabled", false)) {
                    val chargeMode = prefs.getString("batt_charge_speed_mode", "rbChargeDefault") ?: "rbChargeDefault"
                    val mA = when (chargeMode) {
                        "rbChargeSlow" -> 500
                        "rbChargeBalanced" -> 1500
                        else -> 3000
                    }
                    BatteryManager.setChargeCurrent(mA)
                }

                // Apply Global Resolution
                val resKey = prefs.getString("screen_res", "rbRes1080") ?: "rbRes1080"
                val sizeCmd = if (resKey == "rbRes720") "wm size 720x1600" else "wm size reset"
                ShellUtils.fastCmd(sizeCmd)
                
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
