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
                AppEventService.enableViaRoot(context.packageName)
                
                val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

                // 1. Post-Boot Fast Startup Turbo Boost (90 Seconds / 1.5 Minutes)
                // Unleash Little (2.0GHz) & Big (2.8GHz) Cores so Android startup tasks, DexOpt, and launcher widgets finish instantly
                Log.d("BootReceiver", "Post-Boot Turbo Boost active for 90 seconds...")
                TweakManager.applyGlobalMode("Performance")
                prefs.edit().putString("active_ai_label", "AI: Post-Boot Turbo").apply()

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

                // 100% Display Safety: Always reset resolution and density on boot
                ShellUtils.fastCmd("wm size reset")
                ShellUtils.fastCmd("wm density reset")
                prefs.edit().putString("screen_res", "rbRes1080").apply()
                
                // Re-initialize Studio Equalizer DSP if enabled
                if (PowerampPresetManager.isMasterEnabled(context)) {
                    StudioDspManager.init(context)
                }

                val serviceIntent = Intent(context, AutoTweakService::class.java).apply {
                    putExtra("delayed_start", true)
                }
                
                try {
                    context.startService(serviceIntent)
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to start service normally: ${e.message}")
                }

                // 2. Settle down after 90 seconds (1.5 Minutes) to user saved Eco/Auto mode
                try {
                    Thread.sleep(90000)
                    Log.d("BootReceiver", "Post-Boot 90s completed. Transitioning to saved user mode...")
                    val savedModeKey = prefs.getString("selected_mode", "rbAutomatic") ?: "rbAutomatic"
                    if (savedModeKey == "rbAutomatic") {
                        val intentAi = Intent(context, AutoTweakService::class.java).apply {
                            action = "com.example.phonecontrol.ACTION_AI_TICK"
                            putExtra("load", 10)
                        }
                        context.startService(intentAi)
                    } else {
                        val activeMode = when(savedModeKey) {
                            "rbPowerSaver" -> "Power Saver"
                            "rbPerformance" -> "Performance"
                            else -> "Balance"
                        }
                        TweakManager.applyGlobalMode(activeMode)
                    }
                    ModeControlTileService.updateTile(context)
                    context.sendBroadcast(Intent("com.example.phonecontrol.UPDATE_UI").setPackage(context.packageName))
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Error settling down after boot: ${e.message}")
                }
            }
        }
    }
}
