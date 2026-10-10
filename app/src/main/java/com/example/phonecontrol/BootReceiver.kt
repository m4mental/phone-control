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
                val isRoot = ShellUtils.checkRootStandalone(2000, forceCheck = true)
                if (!isRoot) {
                    Log.w("BootReceiver", "Root access missing after boot (e.g. OTA update). Updating QS tiles to No Root.")
                    ModeControlTileService.updateTile(context)
                    CooldownTileService.updateTile(context)
                    return@thread
                }

                // Aggressive root-level activation
                ShellUtils.runAsRoot("dumpsys deviceidle whitelist +com.example.phonecontrol; cmd deviceidle whitelist +com.example.phonecontrol 2>/dev/null; am set-standby-bucket com.example.phonecontrol active 2>/dev/null; dumpsys deviceidle unforce 2>/dev/null; settings put global master_sync_enabled 1 2>/dev/null; settings put system power_sleep_tight_activated 0 2>/dev/null")
                AppEventService.enableViaRoot(context.packageName)

                // Enforce comprehensive exemption for protected apps (WhatsApp, GMS, KeyMapper, user whitelist) on boot
                val allSafeApps = MultitaskingManager.getUserWhitelist(context) + MultitaskingManager.protectedApps
                for (safePkg in allSafeApps) {
                    MultitaskingManager.grantFullExemption(safePkg, context)
                }
                
                val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

                // 1. Post-Boot Fast Startup Turbo Boost (90 Seconds / 1.5 Minutes)
                // Persist boot-boost expiry and saved mode in prefs; AutoTweakService handles expiry reconciliation
                val savedModeKey = prefs.getString("selected_mode", "rbAutomatic") ?: "rbAutomatic"
                val expiry = System.currentTimeMillis() + 90000L
                prefs.edit()
                    .putBoolean("is_post_boot_turbo_active", true)
                    .putLong("post_boot_turbo_expiry", expiry)
                    .putString("post_boot_saved_mode", savedModeKey)
                    .putString("active_ai_label", "AI: Post-Boot Turbo")
                    .apply()

                Log.d("BootReceiver", "Post-Boot Turbo Boost active for 90 seconds (persisted expiry: $expiry)...")
                TweakManager.isPostBootTurboActive = true
                TweakManager.applyGlobalMode("Performance")

                val isPerfHub = prefs.getBoolean("master_performance_hub_enabled", true)
                val isSecHub = prefs.getBoolean("master_security_hub_enabled", true)
                val isBattHub = prefs.getBoolean("master_battery_hub_enabled", true)
                val isToolsHub = prefs.getBoolean("master_tools_hub_enabled", true)

                // Re-apply RAM and Multitasking settings (gated by Performance Hub)
                if (isPerfHub) {
                    val zram = prefs.getString("zram_size", "rbZram4G") ?: "rbZram4G"
                    val profile = prefs.getString("ram_profile", "rbProfileBalance") ?: "rbProfileBalance"
                    TweakManager.applyRamSettings(zram, profile)

                    val isStorageBoost = prefs.getBoolean("storage_boost_active", false) || prefs.getBoolean("storage_boost_enabled", false)
                    if (isStorageBoost) {
                        StorageManager.applyStorageBoost(true)
                    }
                }

                // Re-apply Network, Firewall & 5G settings (gated by Security Hub)
                if (isSecHub) {
                    val dns = prefs.getString("network_dns", "rbDnsDefault") ?: "rbDnsDefault"
                    val tcp = prefs.getBoolean("network_tcp_tweaks", false) || prefs.getBoolean("tcp_bbr_active", false)
                    val lowLat = prefs.getBoolean("network_low_latency", false)
                    TweakManager.applyNetworkSettings(dns, tcp, lowLat)

                    try {
                        val firewallPrefs = context.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
                        val blockedPkgs = firewallPrefs.getStringSet("blocked_packages", emptySet()) ?: emptySet()
                        if (blockedPkgs.isNotEmpty()) {
                            val pm = context.packageManager
                            for (pkg in blockedPkgs) {
                                try {
                                    val uid = pm.getApplicationInfo(pkg, 0).uid
                                    TweakManager.setFirewallRule(uid, true)
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}

                    try {
                        val towerPrefs = context.getSharedPreferences("tower_prefs", Context.MODE_PRIVATE)
                        if (towerPrefs.getBoolean("5g_antisleep_enabled", false)) {
                            val cmds = listOf(
                                "echo -e \"AT+E5GSWITCH=1\\r\\n\" > /dev/radio/pttycmd1 2>/dev/null",
                                "echo -e \"AT+EPOWERCONF=0\\r\\n\" > /dev/radio/pttycmd1 2>/dev/null",
                                "echo -e \"AT+E5GSWITCH=1\\r\\n\" > /dev/ttyC0 2>/dev/null",
                                "setprop persist.vendor.radio.md_sleep_threshold -100",
                                "setprop persist.vendor.radio.smart5g 0",
                                "setprop persist.vendor.radio.smart5g.mode 0"
                            )
                            ShellUtils.runCommandsAsRoot(cmds)
                        }
                    } catch (_: Exception) {}
                }

                // Re-apply Battery Engine settings (gated by Battery Hub)
                if (isBattHub) {
                    val isFastCharge = prefs.getBoolean("battery_fast_charge_boost", false)
                    if (isFastCharge) {
                        BatteryManager.setFastChargeBoost(context, true)
                    }
                    val isUsbPcCharge = prefs.getBoolean("battery_usb_pc_charge", false) || prefs.getBoolean("batt_usb_fast_charge", false)
                    if (isUsbPcCharge) {
                        BatteryManager.setUsbPcCharge(context, true)
                    }
                    val isBypass = prefs.getBoolean("battery_bypass_charging", false) || prefs.getBoolean("batt_bypass_enabled", false)
                    if (isBypass) {
                        BatteryManager.setBypassCharging(context, true)
                    }
                    if (prefs.getBoolean("battery_limit_enabled", false)) {
                        val limit = prefs.getInt("battery_limit_percent", 80)
                        BatteryManager.setChargingLimit(context, limit)
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
                }

                // 100% Display Safety: Always reset resolution and density on boot
                ShellUtils.fastCmd("wm size reset")
                ShellUtils.fastCmd("wm density reset")
                
                // Re-initialize Tools settings (gated by Tools Hub)
                if (isToolsHub) {
                    if (PowerampPresetManager.isMasterEnabled(context)) {
                        StudioDspManager.init(context)
                    }
                    if (WirelessAdbManager.isEnabled(context)) {
                        WirelessAdbManager.applyBootPersistence(context)
                    }
                    if (UpdateShieldManager.isMasterEnabled(context)) {
                        UpdateShieldManager.enforceAllShields(context)
                    }
                }

                // Start AutoTweakService to reconcile post-boot turbo expiry and start maintenance
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
