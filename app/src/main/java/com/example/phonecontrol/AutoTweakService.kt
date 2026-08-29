package com.example.phonecontrol

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager as AndroidBatteryManager
import android.os.IBinder
import android.util.Log

/**
 * 100% Event-Driven AutoTweakService with unified whitelisting & complete Doze synchronization.
 */
class AutoTweakService : Service() {

    companion object {
        const val ACTION_FOREGROUND_APP_CHANGED = "com.example.phonecontrol.ACTION_FOREGROUND_APP_CHANGED"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
    }

    private var lastForegroundApp = ""
    private var isGameTurboActive = false
    private var isPerAppActive = false
    private var isDynamicScalingActive = false
    private var isFloatingWindowActive = false
    @Volatile private var isScreenOn = true
    @Volatile private var isWakeupBoosting = false

    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("smart_switch_enabled", false)) return

            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                Log.d("AutoTweak", "WiFi Connected - Auto-disabling Mobile Data")
                val dataState = ShellUtils.runAsRoot("settings get global mobile_data").output
                if (dataState == "1") {
                    prefs.edit().putBoolean("data_was_on_before_wifi", true).apply()
                    ShellUtils.fastCmd("svc data disable")
                }
            }
        }

        override fun onLost(network: Network) {
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("smart_switch_enabled", false)) return

            if (prefs.getBoolean("data_was_on_before_wifi", false)) {
                Log.d("AutoTweak", "WiFi Lost - Restoring Mobile Data")
                ShellUtils.fastCmd("svc data enable")
                prefs.edit().putBoolean("data_was_on_before_wifi", false).apply()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("automation_enabled", false)) return

            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff(prefs)
                Intent.ACTION_SCREEN_ON -> onScreenOn(prefs)
            }
        }
    }

    private val batteryThermalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
                
                // 1. Thermal check
                val tempTenths = intent.getIntExtra(AndroidBatteryManager.EXTRA_TEMPERATURE, 0)
                val tempCelsius = tempTenths / 10
                if (tempCelsius > 0) {
                    ThermalManager.applyAdaptiveThrottling(context, tempCelsius)
                }

                // 2. Low Battery Auto-Saver Trigger
                val level = intent.getIntExtra(AndroidBatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(AndroidBatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    val battPct = (level * 100) / scale
                    val isLowBattTrigger = prefs.getBoolean("batt_low_trigger_enabled", false)
                    val triggerValue = prefs.getInt("batt_low_trigger_value", 20)
                    if (isLowBattTrigger && battPct <= triggerValue) {
                        val currentMode = prefs.getString("selected_mode", "rbBalance")
                        if (currentMode != "rbPowerSaver") {
                            Log.d("AutoTweak", "Low Battery Trigger ($battPct% <= $triggerValue%) -> Auto Switching to Power Saver")
                            prefs.edit().putString("selected_mode", "rbPowerSaver").apply()
                            TweakManager.applyGlobalMode("Power Saver")
                            sendBroadcast(Intent("com.example.phonecontrol.UPDATE_UI").setPackage(packageName))
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, screenFilter)

        val battFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryThermalReceiver, battFilter)
        
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        AppEventService.enableViaRoot(packageName)
        ThermalManager.checkAndRecoverCooldown(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        // 1. Instant 0ms Foreground App Event
        if (intent?.action == ACTION_FOREGROUND_APP_CHANGED) {
            val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
            if (pkg.isNotBlank() && pkg != lastForegroundApp) {
                lastForegroundApp = pkg
                handleForegroundAppEvent(pkg)
            }
            return START_STICKY
        }

        // 2. AI Update Signal
        if (intent?.action == "com.example.phonecontrol.ACTION_AI_TICK") {
            val load = intent.getIntExtra("load", 0)
            val focus = prefs.getString("selected_focus", "rbFocusDaily")
            applyAiTweak(load, focus ?: "rbFocusDaily")
        }

        BackupManager.ensureStorageStructure()
        DaemonManager.startDaemon(this)

        if (prefs.getBoolean("silent_system_enabled", false)) {
            TweakManager.setSilentSystem(true)
        }

        if (prefs.getString("selected_mode", "rbBalance") == "rbAutomatic") {
            val focus = prefs.getString("selected_focus", "rbFocusDaily") ?: "rbFocusDaily"
            val targetPkg = if (lastForegroundApp.isNotBlank()) lastForegroundApp else packageName
            val load = calculateAppAiLoad(targetPkg)
            applyAiTweak(load, focus) 
        } else {
            prefs.edit().remove("active_ai_label").apply()
        }

        return START_STICKY
    }

    private fun calculateAppAiLoad(pkg: String): Int {
        val lower = pkg.lowercase()
        // 1. Heavy Apps (Camera, Video Editors, Benchmarks, High graphics)
        if (lower.contains("camera") || lower.contains("video") || lower.contains("editor") ||
            lower.contains("capcut") || lower.contains("kinemaster") || lower.contains("antutu") ||
            lower.contains("geekbench") || lower.contains("3dmark") || lower.contains("genshin") ||
            lower.contains("pubg") || lower.contains("cod") || lower.contains("bgmi") ||
            lower.contains("lightroom") || lower.contains("photoshop") || lower.contains("speedtest")) {
            return 60
        }
        
        // 2. Daily Fluent / Media / Social
        if (lower.contains("chrome") || lower.contains("browser") || lower.contains("youtube") ||
            lower.contains("instagram") || lower.contains("whatsapp") || lower.contains("telegram") ||
            lower.contains("twitter") || lower.contains("x.android") || lower.contains("tiktok") ||
            lower.contains("spotify") || lower.contains("netflix") || lower.contains("amazon") ||
            lower.contains("flipkart")) {
            return 25
        }
        
        // 3. System / Light / Launcher
        return 10
    }

    private fun handleForegroundAppEvent(pkg: String) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val turboPrefs = getSharedPreferences("game_turbo_prefs", MODE_PRIVATE)
        val games = turboPrefs.getStringSet("game_packages", emptySet()) ?: emptySet()
        val perAppConfig = PerAppManager.getConfig(this, pkg)
        
        // Dynamic Resolution Scaling Whitelist Check
        val isDynamicScalingEnabled = prefs.getBoolean("dynamic_scaling_enabled", false)
        val scalingWhitelist = prefs.getStringSet("scaling_whitelist", emptySet()) ?: emptySet()

        if (isDynamicScalingEnabled) {
            if (scalingWhitelist.contains(pkg)) {
                if (!isDynamicScalingActive) {
                    Log.d("AutoTweak", "Dynamic Resolution Scaling -> 720p for $pkg")
                    TweakManager.setSystemResolution(true)
                    isDynamicScalingActive = true
                }
            } else if (isDynamicScalingActive) {
                Log.d("AutoTweak", "Dynamic Resolution Scaling -> Reverting to standard resolution")
                val savedRes = prefs.getString("screen_res", "rbRes1080")
                TweakManager.setSystemResolution(savedRes == "rbRes720")
                isDynamicScalingActive = false
            }
        }

        if (games.contains(pkg)) {
            if (!isGameTurboActive) {
                Log.d("AutoTweak", "Instant Game Event: $pkg - Activating Turbo")
                if (turboPrefs.getBoolean("auto_perf_enabled", true)) TweakManager.applyGlobalMode("Performance")
                if (turboPrefs.getBoolean("auto_ping_enabled", true)) TweakManager.setNetworkPriority(this, pkg, true)
                if (turboPrefs.getBoolean("auto_thermal_enabled", false)) ThermalManager.setThrottlingEnabled(false)
                isGameTurboActive = true
                isPerAppActive = false
            }
        } else if (perAppConfig != null && perAppConfig.mode != "Auto") {
            Log.d("AutoTweak", "Instant Per-App Event: $pkg -> Mode: ${perAppConfig.mode}, FPS: ${perAppConfig.fps}")
            TweakManager.applyGlobalMode(perAppConfig.mode)
            TweakManager.setRefreshRate(perAppConfig.fps)
            if (perAppConfig.thermal == "Disabled") ThermalManager.setThrottlingEnabled(false) else ThermalManager.setThrottlingEnabled(true)
            if (perAppConfig.touch == "On") TweakManager.applyInputBoost(true)
            if (perAppConfig.mode == "Performance") TweakManager.applyProcessPriority(pkg, true)
            isPerAppActive = true
            isGameTurboActive = false
        } else {
            val isAutomaticMode = prefs.getString("selected_mode", "rbBalance") == "rbAutomatic"
            
            if (isGameTurboActive || isPerAppActive) {
                Log.d("AutoTweak", "Instant Exit Event - Restoring User's Selected Mode")
                val savedModeKey = prefs.getString("selected_mode", "rbBalance") ?: "rbBalance"
                when (savedModeKey) {
                    "rbPowerSaver" -> TweakManager.applyGlobalMode("Power Saver")
                    "rbPerformance" -> TweakManager.applyGlobalMode("Performance")
                    "rbAutomatic" -> {
                        val focus = prefs.getString("selected_focus", "rbFocusDaily") ?: "rbFocusDaily"
                        val load = calculateAppAiLoad(pkg)
                        applyAiTweak(load, focus)
                    }
                    else -> TweakManager.applyGlobalMode("Balance")
                }
                ThermalManager.setThrottlingEnabled(true)
                TweakManager.setRefreshRate("Default")
                isGameTurboActive = false
                isPerAppActive = false
            } else if (isAutomaticMode) {
                // Dynamic 0ms AI load adaptation on every app transition
                val focus = prefs.getString("selected_focus", "rbFocusDaily") ?: "rbFocusDaily"
                val load = calculateAppAiLoad(pkg)
                applyAiTweak(load, focus)
            }
        }
    }

    private var lastAiMode = ""

    private fun applyAiTweak(load: Int, focus: String) {
        val adjustedLoad = if (isFloatingWindowActive && load < 50) {
            if (load > 25) 25 else load 
        } else {
            load
        }

        val targetMode = if (!isScreenOn) {
            "AI_Sleeping"
        } else {
            when (focus) {
                "rbFocusBattery" -> {
                    // Dynamic scaling based on actual usage during Screen ON:
                    when {
                        adjustedLoad > 40 -> "AI_Boost"
                        adjustedLoad > 10 -> "AI_Daily"
                        else -> "AI_EcoActive" // Light / Idle active usage: 8 cores online, low power, schedutil, 0 UI lag
                    }
                }
                "rbFocusDaily" -> {
                    if (adjustedLoad > 35) "AI_Boost" else "AI_Daily"
                }
                "rbFocusMultitasking" -> {
                    when {
                        adjustedLoad > 30 -> "AI_Extreme"
                        adjustedLoad > 15 -> "AI_Boost"
                        else -> "AI_Daily"
                    }
                }
                else -> "AI_Daily"
            }
        }

        if (targetMode != lastAiMode) {
            TweakManager.applyGlobalMode(targetMode)
            
            if (isFloatingWindowActive && adjustedLoad < 50 && (targetMode == "AI_Daily" || targetMode == "AI_Sleeping" || targetMode == "AI_EcoActive")) {
                Log.d("AutoTweak", "Floating Active - Enforcing 6-Core Efficiency Priority")
                TweakManager.setClusterParking(true) 
            }

            lastAiMode = targetMode
            
            val displayLabel = when(targetMode) {
                "AI_Sleeping" -> "AI: Sleeping"
                "AI_EcoActive" -> "AI: Eco Active"
                "AI_Daily" -> "AI: Daily Fluent"
                "AI_Boost" -> "AI: Multi-Boost"
                "AI_Extreme" -> "AI: Extreme"
                else -> "AI: Active"
            }
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("active_ai_label", displayLabel).apply()
            sendBroadcast(Intent("com.example.phonecontrol.UPDATE_UI").setPackage(packageName))
        }
    }

    private fun onScreenOff(prefs: android.content.SharedPreferences) {
        isScreenOn = false
        isWakeupBoosting = false
        
        kotlin.concurrent.thread {
            Log.d("AutoTweak", "Screen OFF Event - Transitioning to Deep Sleep (Async)")
            ShellUtils.fastCmd("echo 'off' > /data/local/tmp/pc_screen")

            val superDozePrefs = getSharedPreferences("super_doze_prefs", MODE_PRIVATE)
            val isSuperDoze = prefs.getBoolean("super_doze_enabled", false)
            val isForceDoze = prefs.getBoolean("batt_force_doze_enabled", false)

            // 1. Core Parking
            if (isSuperDoze && superDozePrefs.getBoolean("deep_parking_enabled", true)) {
                TweakManager.setClusterParking(true, deep = true)
            } else if (prefs.getBoolean("core_parking_enabled", false)) {
                TweakManager.setClusterParking(true, deep = false)
            }

            // 2. Super Doze & Sync Logic (Safe sleep without breaking fingerprint HAL)
            if (isSuperDoze || isForceDoze) {
                if (isSuperDoze && superDozePrefs.getBoolean("sync_off_enabled", true)) {
                    ShellUtils.fastCmd("settings put global master_sync_enabled 0")
                }
                if (isSuperDoze && superDozePrefs.getBoolean("radio_off_enabled", false)) {
                    ShellUtils.fastCmd("svc data disable")
                }
            }

            // 3. Block Kernel Wakelocks
            TweakManager.applyWakelockBlocker(true)

            // 4. Sensor Logic
            val firewallActive = prefs.getBoolean("sensor_firewall_enabled", false)
            val indivBlockActive = prefs.getBoolean("block_gyro", false) || 
                                  prefs.getBoolean("block_mag", false) || 
                                  prefs.getBoolean("block_light", false) || 
                                  prefs.getBoolean("block_motion", false)

            if (firewallActive || indivBlockActive) {
                SensorManager.setSensorsEnabled(false)
            }

            // 5. GPS Auto-Saver on Screen OFF
            if (prefs.getBoolean("gps_auto_saver_enabled", false)) {
                TweakManager.setLocationEnabled(false)
            }

            // 6. Standby Guard
            if (prefs.getBoolean("standby_guard_enabled", false)) {
                val result = ShellUtils.runAsRoot("pm list packages -3 | cut -d ':' -f2")
                val packages = result.output.split("\n").filter { it.isNotBlank() }
                
                val userWhitelist = MultitaskingManager.getUserWhitelist(this@AutoTweakService)
                val dozeWhitelist = prefs.getStringSet("doze_whitelist", emptySet()) ?: emptySet()
                
                val defaultSafeList = setOf(
                    "com.whatsapp",
                    "org.telegram.messenger",
                    "com.google.android.gm",
                    "com.google.android.dialer",
                    "com.android.phone",
                    "com.google.android.apps.messaging",
                    "com.truecaller",
                    "com.google.android.apps.nbu.paisa.user",
                    "net.one97.paytm",
                    "com.phonepe.app",
                    "in.org.npci.upiapp"
                )
                val allSafeApps = defaultSafeList + userWhitelist + dozeWhitelist + MultitaskingManager.protectedApps

                for (pkg in packages) {
                    if (!allSafeApps.contains(pkg)) {
                        ShellUtils.fastCmd("am set-standby-bucket $pkg restricted")
                    } else {
                        ShellUtils.fastCmd("am set-standby-bucket $pkg active")
                    }
                }
            }

            // 7. Auto Hibernation
            val freezerPrefs = getSharedPreferences("freezer_prefs", MODE_PRIVATE)
            if (freezerPrefs.getBoolean("auto_freeze_enabled", false)) {
                val frozenApps = FreezerManager.getFrozenApps(this@AutoTweakService)
                for (pkg in frozenApps) {
                    FreezerManager.freezeApp(this@AutoTweakService, pkg)
                }
            }

            // 8. AI Sleeping Profile
            if (prefs.getString("selected_mode", "rbBalance") == "rbAutomatic") {
                applyAiTweak(0, "rbFocusDaily")
            }
        }
    }

    private fun onScreenOn(prefs: android.content.SharedPreferences) {
        isScreenOn = true
        
        kotlin.concurrent.thread {
            Log.d("AutoTweak", "Screen ON Event - Instant 0ms Async Wakeup")
            // 1. Instant 2ms Atomic Wakeup Boost
            TweakManager.triggerTemporaryWakeupBoost()
            TweakManager.setClusterParking(false, deep = true) 
            ShellUtils.fastCmd("echo 'on' > /data/local/tmp/pc_screen")

            // 2. Restore Automatic AI Profile IMMEDIATELY
            if (isScreenOn && prefs.getString("selected_mode", "rbBalance") == "rbAutomatic") {
                val focus = prefs.getString("selected_focus", "rbFocusDaily") ?: "rbFocusDaily"
                val load = calculateAppAiLoad(lastForegroundApp)
                applyAiTweak(load, focus)
            }

            val superDozePrefs = getSharedPreferences("super_doze_prefs", MODE_PRIVATE)
            val isSuperDoze = prefs.getBoolean("super_doze_enabled", false)

            if (isSuperDoze) {
                if (superDozePrefs.getBoolean("sync_off_enabled", true)) {
                    ShellUtils.fastCmd("settings put global master_sync_enabled 1")
                }
                if (superDozePrefs.getBoolean("radio_off_enabled", false)) {
                    ShellUtils.fastCmd("svc data enable")
                }
            }

            // GPS Auto-Saver Restore
            if (prefs.getBoolean("gps_auto_saver_enabled", false)) {
                TweakManager.setLocationEnabled(true)
            }

            val indivBlockActive = prefs.getBoolean("block_gyro", false) || 
                                  prefs.getBoolean("block_mag", false) || 
                                  prefs.getBoolean("block_light", false) || 
                                  prefs.getBoolean("block_motion", false)

            if (!indivBlockActive) {
                SensorManager.setSensorsEnabled(true)
            }

            TweakManager.applyWakelockBlocker(false)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        unregisterReceiver(batteryThermalReceiver)
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
        ShellUtils.closePersistentShell()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
