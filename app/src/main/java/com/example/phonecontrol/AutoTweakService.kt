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
import android.os.IBinder
import android.util.Log

/**
 * Modernized AutoTweakService: Now an Event-Driven Listener.
 * No infinite loops here. It only reacts to system broadcasts.
 */
class AutoTweakService : Service() {

    private val gameWatcherHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val gameWatcherRunnable = object : Runnable {
        override fun run() {
            checkForegroundApp()
            gameWatcherHandler.postDelayed(this, 3000)
        }
    }

    private var lastForegroundApp = ""
    private var isGameTurboActive = false
    private var isFloatingWindowActive = false

    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("smart_switch_enabled", false)) return

            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                Log.d("AutoTweak", "WiFi Connected - Auto-disabling Mobile Data")
                // Save current state: 1 means it was ON
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

            // If we turned it off, turn it back on
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

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
        gameWatcherHandler.post(gameWatcherRunnable)
        
        // Register Network Monitor
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        // Handle AI Update Signal from Daemon
        if (intent?.action == "com.example.phonecontrol.ACTION_AI_TICK") {
            val load = intent.getIntExtra("load", 0)
            val focus = prefs.getString("selected_focus", "rbFocusDaily")
            applyAiTweak(load, focus ?: "rbFocusDaily")
        }

        // Ensure storage structure is ready
        BackupManager.ensureStorageStructure()
        
        // Ensure Native Daemon is running
        DaemonManager.startDaemon(this)

        // Initial setup for boot/first launch
        if (prefs.getBoolean("silent_system_enabled", false)) {
            TweakManager.setSilentSystem(true)
        }

        // Force an immediate AI Profile sync if in Automatic mode
        if (prefs.getString("selected_mode", "rbBalance") == "rbAutomatic") {
            val focus = prefs.getString("selected_focus", "rbFocusDaily") ?: "rbFocusDaily"
            applyAiTweak(25, focus) 
        } else {
            // Clear AI label if not in AI mode
            prefs.edit().remove("active_ai_label").apply()
        }

        return START_STICKY
    }

    private fun checkForegroundApp() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        
        kotlin.concurrent.thread {
            // Nothing OS Native Floating Window Detection:
            // We check for 'mResumedActivity' count and 'isFreeform' status in dumpsys
            val stackResult = ShellUtils.runAsRoot("dumpsys activity containers | grep -E 'mResumedActivity|windowingMode=freeform'")
            val output = stackResult.output
            
            val resumedCount = output.split("\n").filter { it.contains("mResumedActivity") }.size
            val hasFloatingWindow = output.contains("windowingMode=freeform") || resumedCount > 1
            
            // Extract the main package (first resumed activity)
            val pkg = extractPackageName(output.substringBefore("\n"))
            
            if (pkg != lastForegroundApp || hasFloatingWindow) {
                lastForegroundApp = pkg
                handleForegroundAppChange(pkg, hasFloatingWindow)
            }
        }
    }

    private fun extractPackageName(line: String): String {
        return try {
            val parts = line.split(" ")
            for (part in parts) {
                if (part.contains("/")) return part.substringBefore("/")
            }
            ""
        } catch (e: Exception) { "" }
    }

    private fun handleForegroundAppChange(pkg: String, hasFloatingWindow: Boolean) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val turboPrefs = getSharedPreferences("game_turbo_prefs", MODE_PRIVATE)
        val games = turboPrefs.getStringSet("game_packages", emptySet()) ?: emptySet()
        
        // Update Floating Window State for AI Engine
        isFloatingWindowActive = hasFloatingWindow

        // Nothing OS Native Floating Window Priority logic moved to applyAiTweak (Adaptive)
        if (hasFloatingWindow && prefs.getString("selected_mode", "rbBalance") == "rbAutomatic") {
             Log.d("AutoTweak", "Nothing Floating Window Detected - Adaptive 6-Core Priority Active")
             // We don't force 80% anymore. The next AI_TICK will handle it adaptively.
             return
        }

        if (games.contains(pkg)) {
            if (!isGameTurboActive) {
                Log.d("AutoTweak", "Game Detected: $pkg - Activating Turbo")
                if (turboPrefs.getBoolean("auto_perf_enabled", true)) TweakManager.applyGlobalMode("Performance")
                if (turboPrefs.getBoolean("auto_ping_enabled", true)) TweakManager.setNetworkPriority(this, pkg, true)
                if (turboPrefs.getBoolean("auto_thermal_enabled", false)) ThermalManager.setThrottlingEnabled(false)
                isGameTurboActive = true
            }
        } else {
            if (isGameTurboActive) {
                Log.d("AutoTweak", "Game Closed - Deactivating Turbo")
                val globalMode = getSharedPreferences("prefs", MODE_PRIVATE).getString("selected_mode", "rbBalance")
                TweakManager.applyGlobalMode(if (globalMode == "rbAutomatic") "Balance" else "Balance")
                ThermalManager.setThrottlingEnabled(true)
                isGameTurboActive = false
            }
        }
    }

    private var lastAiMode = ""

    private fun applyAiTweak(load: Int, focus: String) {
        // Adaptive Load Balancing:
        // If floating window is active, we prioritize the 6 Efficiency Cores (Cortex-A510)
        // unless the load is genuinely high (>50%).
        val adjustedLoad = if (isFloatingWindowActive && load < 50) {
            // Keep it in 'Daily' range to avoid waking up BIG cores prematurely
            if (load > 25) 25 else load 
        } else {
            load
        }

        val targetMode = when (focus) {
            "rbFocusBattery" -> {
                when {
                    adjustedLoad > 40 -> "AI_Boost"  
                    adjustedLoad > 20 -> "AI_Daily"
                    else -> "AI_Sleeping"
                }
            }
            "rbFocusDaily" -> {
                when {
                    adjustedLoad > 35 -> "AI_Boost"
                    adjustedLoad > 10 -> "AI_Daily"
                    else -> "AI_Sleeping"
                }
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

        if (targetMode != lastAiMode) {
            TweakManager.applyGlobalMode(targetMode)
            
            // FORCED OVERRIDE: 6-Core Priority for Floating Windows
            // If we are in Daily or Sleeping mode while floating is active, 
            // ensure the 2 Big Cores (6-7) are parked to save battery on 5G.
            if (isFloatingWindowActive && adjustedLoad < 50 && (targetMode == "AI_Daily" || targetMode == "AI_Sleeping")) {
                Log.d("AutoTweak", "Floating Active - Enforcing 6-Core Efficiency Priority")
                TweakManager.setClusterParking(true) 
            }

            lastAiMode = targetMode
            
            // Save state for UI dashboard
            val displayLabel = when(targetMode) {
                "AI_Sleeping" -> "AI: Sleeping"
                "AI_Daily" -> "AI: Daily Fluent"
                "AI_Boost" -> "AI: Multi-Boost"
                "AI_Extreme" -> "AI: Extreme"
                else -> "AI: Active"
            }
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("active_ai_label", displayLabel).apply()
            
            // Notify UI to refresh label
            sendBroadcast(Intent("com.example.phonecontrol.UPDATE_UI").setPackage(packageName))
        }
    }

    private fun onScreenOff(prefs: android.content.SharedPreferences) {
        Log.d("AutoTweak", "Screen OFF - Ultra Fast Transition")
        
        // IMMEDIATE: Tell Daemon to slow down
        ShellUtils.fastCmd("echo 'off' > /data/local/tmp/pc_screen")
        
        // Move heavy root logic to background thread to avoid locking SystemUI/PowerManager
        kotlin.concurrent.thread {
            val superDozePrefs = getSharedPreferences("super_doze_prefs", MODE_PRIVATE)
            val isSuperDoze = prefs.getBoolean("super_doze_enabled", false)

            // 1. Core Parking
            if (isSuperDoze && superDozePrefs.getBoolean("deep_parking_enabled", true)) {
                TweakManager.setClusterParking(true, deep = true)
            } else if (prefs.getBoolean("core_parking_enabled", false)) {
                TweakManager.setClusterParking(true, deep = false)
            }

            // 2. Super Doze Extras
            if (isSuperDoze) {
                if (superDozePrefs.getBoolean("sync_off_enabled", true)) ShellUtils.fastCmd("settings put global master_sync_enabled 0")
                ShellUtils.fastCmd("dumpsys deviceidle force-idle deep")
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

            // 5. Standby Guard (WhatsApp ONLY Notification Safe-List)
            if (prefs.getBoolean("standby_guard_enabled", false)) {
                val result = ShellUtils.runAsRoot("pm list packages -3 | cut -d ':' -f2")
                val packages = result.output.split("\n").filter { it.isNotBlank() }
                val safeList = listOf("com.whatsapp")
                for (pkg in packages) {
                    if (!safeList.contains(pkg)) {
                        ShellUtils.fastCmd("am set-standby-bucket $pkg restricted")
                    } else {
                        ShellUtils.fastCmd("am set-standby-bucket $pkg active")
                    }
                }
                ShellUtils.fastCmd("cmd battery-saver set-enabled true")
            }

            // 6. Auto Hibernation
            val freezerPrefs = getSharedPreferences("freezer_prefs", MODE_PRIVATE)
            if (freezerPrefs.getBoolean("auto_freeze_enabled", false)) {
                val frozenApps = FreezerManager.getFrozenApps(this@AutoTweakService)
                for (pkg in frozenApps) {
                    FreezerManager.freezeApp(this@AutoTweakService, pkg)
                }
            }
        }
        
        gameWatcherHandler.removeCallbacks(gameWatcherRunnable)
    }

    private fun onScreenOn(prefs: android.content.SharedPreferences) {
        Log.d("AutoTweak", "Screen ON - Instant Recovery")
        
        // IMMEDIATE CORE UNPARK: Must happen first and on background thread to not block UI
        kotlin.concurrent.thread {
            TweakManager.setClusterParking(false, deep = true) 
            ShellUtils.fastCmd("echo 'on' > /data/local/tmp/pc_screen")

            if (prefs.getBoolean("super_doze_enabled", false)) {
                ShellUtils.fastCmd("settings put global master_sync_enabled 1")
            }

            val indivBlockActive = prefs.getBoolean("block_gyro", false) || 
                                  prefs.getBoolean("block_mag", false) || 
                                  prefs.getBoolean("block_light", false) || 
                                  prefs.getBoolean("block_motion", false)

            if (!indivBlockActive) {
                SensorManager.setSensorsEnabled(true)
            }

            TweakManager.applyWakelockBlocker(false)

            if (prefs.getBoolean("standby_guard_enabled", false)) {
                ShellUtils.fastCmd("cmd battery-saver set-enabled false")
            }
        }
        
        gameWatcherHandler.post(gameWatcherRunnable)
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
        gameWatcherHandler.removeCallbacks(gameWatcherRunnable)
        ShellUtils.closePersistentShell()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
