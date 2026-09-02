package com.example.phonecontrol

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager as AndroidBatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 100% Event-Driven, Fully Asynchronous AutoTweakService (ANR-Proof).
 * Handles smart Recents-Aware Auto-Hibernation, Active Media/Audio Playback Guard, Real-time Profile Adaptation, and Smart Doze.
 */
class AutoTweakService : Service() {

    companion object {
        const val ACTION_FOREGROUND_APP_CHANGED = "com.example.phonecontrol.ACTION_FOREGROUND_APP_CHANGED"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
    }

    @Volatile private var lastForegroundApp = ""
    @Volatile private var isGameTurboActive = false
    @Volatile private var isPerAppActive = false
    @Volatile private var isDynamicScalingActive = false
    @Volatile private var isFloatingWindowActive = false
    @Volatile private var isScreenOn = true
    @Volatile private var isWakeupBoosting = false
    @Volatile private var isCameraInUse = false
    @Volatile private var isVideoCallActive = false
    @Volatile private var lastAiMode = ""
    @Volatile private var isAudioCurrentlyActive = false
    @Volatile private var isEqualizerFrozen = false

    private val tweakExecutor = Executors.newSingleThreadExecutor()
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var appOpsManager: AppOpsManager
    private var cameraManager: CameraManager? = null
    private val activeCameras = Collections.synchronizedSet(mutableSetOf<String>())
    private var audioManager: AudioManager? = null
    private var equalizerFreezeHandler: Handler? = null
    private var equalizerFreezeRunnable: Runnable? = null

    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            super.onCameraUnavailable(cameraId)
            activeCameras.add(cameraId)
            isCameraInUse = true
            Log.d("AutoTweak", "📷 Camera unavailable (in use): $cameraId. Total active: ${activeCameras.size}")
            tweakExecutor.execute { handleVideoCallStateChanged() }
        }

        override fun onCameraAvailable(cameraId: String) {
            super.onCameraAvailable(cameraId)
            activeCameras.remove(cameraId)
            isCameraInUse = activeCameras.isNotEmpty()
            Log.d("AutoTweak", "📷 Camera available (closed): $cameraId. Remaining: ${activeCameras.size}")
            tweakExecutor.execute { handleVideoCallStateChanged() }
        }
    }

    private fun handleVideoCallStateChanged() {
        val hasCamera = activeCameras.isNotEmpty()
        val isCallAudio = audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION || audioManager?.mode == AudioManager.MODE_IN_CALL
        val isVideoCallNow = hasCamera || isCallAudio

        if (isVideoCallNow && !isVideoCallActive) {
            isVideoCallActive = true
            Log.d("AutoTweak", "📹 Video Call / Camera ACTIVE -> Locking Little Cores to 950MHz!")
            TweakManager.applyVideoCallEcoLock()
            sendBroadcast(Intent("com.example.phonecontrol.UPDATE_UI").setPackage(packageName))
        } else if (!isVideoCallNow && isVideoCallActive) {
            isVideoCallActive = false
            Log.d("AutoTweak", "📹 Video Call / Camera ENDED -> Restoring previous state!")
            TweakManager.restorePreVideoCallState(this)
            sendBroadcast(Intent("com.example.phonecontrol.UPDATE_UI").setPackage(packageName))
        }
    }

    private val audioPlaybackCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                super.onPlaybackConfigChanged(configs)
                tweakExecutor.execute {
                    handleAudioPlaybackStateChanged(configs)
                }
            }
        }
    } else null

    private val opActiveListener = AppOpsManager.OnOpActiveChangedListener { op, uid, pkg, active ->
        if (op == AppOpsManager.OPSTR_CAMERA || op == "android:phone_call_camera") {
            Log.d("AutoTweak", "Camera Op Active Changed -> pkg: $pkg, active: $active")
            isCameraInUse = active
            tweakExecutor.execute {
                handleVideoCallStateChanged()
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            tweakExecutor.execute {
                val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
                if (!prefs.getBoolean("smart_switch_enabled", false)) return@execute

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
        }

        override fun onLost(network: Network) {
            tweakExecutor.execute {
                val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
                if (!prefs.getBoolean("smart_switch_enabled", false)) return@execute

                if (prefs.getBoolean("data_was_on_before_wifi", false)) {
                    Log.d("AutoTweak", "WiFi Lost - Restoring Mobile Data")
                    ShellUtils.fastCmd("svc data enable")
                    prefs.edit().putBoolean("data_was_on_before_wifi", false).apply()
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pendingAction = intent.action ?: return
            tweakExecutor.execute {
                val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
                if (!prefs.getBoolean("automation_enabled", false)) return@execute

                when (pendingAction) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff(prefs)
                    Intent.ACTION_SCREEN_ON -> onScreenOn(prefs)
                }
            }
        }
    }

    private val batteryThermalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val tempTenths = intent.getIntExtra(AndroidBatteryManager.EXTRA_TEMPERATURE, 0)
                val level = intent.getIntExtra(AndroidBatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(AndroidBatteryManager.EXTRA_SCALE, -1)

                tweakExecutor.execute {
                    val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
                    
                    // 1. Thermal check
                    val tempCelsius = tempTenths / 10
                    if (tempCelsius > 0) {
                        ThermalManager.applyAdaptiveThrottling(this@AutoTweakService, tempCelsius)
                    }

                    // 2. Low Battery Auto-Saver Trigger
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
    }

    override fun onCreate() {
        super.onCreate()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            val nm = getSystemService(NotificationManager::class.java)
            nm?.cancel(1001)
        } catch (e: Exception) {}
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

        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            cameraManager?.registerAvailabilityCallback(cameraAvailabilityCallback, Handler(Looper.getMainLooper()))
            Log.d("AutoTweak", "CameraManager.AvailabilityCallback successfully registered for Video Call detection")
        } catch (e: Exception) {
            Log.e("AutoTweak", "Failed to register camera availability callback: ${e.message}")
        }

        try {
            appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOpsManager.startWatchingActive(
                    arrayOf(AppOpsManager.OPSTR_CAMERA, "android:phone_call_camera"),
                    tweakExecutor,
                    opActiveListener
                )
            }
        } catch (e: Exception) {
            Log.e("AutoTweak", "Failed to start watching active camera op: ${e.message}")
        }

        // Smart Equalizer Audio Guard (0ms Instant Unfreeze on Music Play, 15s Auto-Sleep on Pause)
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioManager != null && audioPlaybackCallback != null) {
                equalizerFreezeHandler = Handler(Looper.getMainLooper())
                audioManager?.registerAudioPlaybackCallback(audioPlaybackCallback, equalizerFreezeHandler)
                Log.d("AutoTweak", "AudioPlaybackCallback successfully registered for Equalizer Guard")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && audioManager != null) {
                audioManager?.addOnModeChangedListener(tweakExecutor, AudioManager.OnModeChangedListener { mode ->
                    Log.d("AutoTweak", "Audio Mode Changed: $mode")
                    handleVideoCallStateChanged()
                })
                Log.d("AutoTweak", "AudioManager.OnModeChangedListener successfully registered for Call detection")
            }
        } catch (e: Exception) {
            Log.e("AutoTweak", "Failed to register audio callbacks: ${e.message}")
        }

        tweakExecutor.execute {
            handleVideoCallStateChanged()
            AppEventService.enableViaRoot(packageName)
            ThermalManager.checkAndRecoverCooldown(this)
        }
    }

    private fun handleAudioPlaybackStateChanged(configs: List<AudioPlaybackConfiguration>?) {
        if (!FreezerManager.isEqualizerSleepEnabled(this)) return
        val eqPkg = FreezerManager.getDetectedEqualizerPackage(this) ?: return

        val hasActiveAudio = audioManager?.isMusicActive == true
        if (hasActiveAudio) {
            isAudioCurrentlyActive = true
            equalizerFreezeRunnable?.let { equalizerFreezeHandler?.removeCallbacks(it) }

            // CRITICAL: Only unfreeze if it was actually in frozen sleep!
            // If already playing, DO NOT TOUCH IT! Zero commands, zero service reloads, zero audio drops!
            if (isEqualizerFrozen) {
                Log.d("AutoTweak", "🎵 Audio resumed -> Instant unfreeze for Equalizer [$eqPkg]")
                FreezerManager.instantUnfreezeEqualizer(eqPkg)
                isEqualizerFrozen = false
            }
        } else {
            if (isAudioCurrentlyActive) {
                isAudioCurrentlyActive = false
                Log.d("AutoTweak", "⏸️ Audio paused -> Starting 30s grace timer for Equalizer [$eqPkg]")
                equalizerFreezeRunnable?.let { equalizerFreezeHandler?.removeCallbacks(it) }
                equalizerFreezeRunnable = Runnable {
                    tweakExecutor.execute {
                        val isStillPlaying = audioManager?.isMusicActive == true
                        if (!isStillPlaying && lastForegroundApp != eqPkg && !isEqualizerFrozen) {
                            Log.d("AutoTweak", "❄️ 30s elapsed with no audio -> Freezing Equalizer [$eqPkg]")
                            FreezerManager.instantFreezeEqualizer(eqPkg)
                            isEqualizerFrozen = true
                        }
                    }
                }
                equalizerFreezeHandler?.postDelayed(equalizerFreezeRunnable!!, 30000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        // 1. Instant Foreground App Event
        if (action == ACTION_FOREGROUND_APP_CHANGED) {
            val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
            if (pkg.isNotBlank() && pkg != lastForegroundApp) {
                val previousApp = lastForegroundApp
                lastForegroundApp = pkg
                tweakExecutor.execute {
                    handleForegroundAppTransition(previousApp, pkg)
                }
            }
            return START_STICKY
        }

        // 2. AI Update Signal
        if (action == "com.example.phonecontrol.ACTION_AI_TICK") {
            val load = intent.getIntExtra("load", 0)
            tweakExecutor.execute {
                val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
                val focus = prefs.getString("selected_focus", "rbFocusDaily")
                applyAiTweak(load, focus ?: "rbFocusDaily")
            }
            return START_STICKY
        }

        // 3. Service Startup Checks
        tweakExecutor.execute {
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            BackupManager.ensureStorageStructure()
            DaemonManager.startDaemon(this@AutoTweakService)

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
        }

        return START_STICKY
    }

    fun isVideoCallActive(): Boolean {
        return isCameraInUse
    }

    private fun handleCameraOrCallStateChanged() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val manualStage = prefs.getInt("manual_stage_override", 0)
        if (manualStage != 0 || TweakManager.manualStageOverride != 0) return

        if (prefs.getString("selected_mode", "rbBalance") == "rbAutomatic") {
            val focus = prefs.getString("selected_focus", "rbFocusDaily") ?: "rbFocusDaily"
            val targetPkg = if (lastForegroundApp.isNotBlank()) lastForegroundApp else packageName
            val load = calculateAppAiLoad(targetPkg)
            applyAiTweak(load, focus)
        }
    }

    private fun calculateAppAiLoad(pkg: String): Int {
        val lower = pkg.lowercase()
        // 1. Heavy Compute / 3D Games / Video Editing (Stage 3 & 4 - Big Cores Wake Up)
        if (lower.contains("camera") || lower.contains("video") || lower.contains("editor") ||
            lower.contains("capcut") || lower.contains("kinemaster") || lower.contains("antutu") ||
            lower.contains("geekbench") || lower.contains("3dmark") || lower.contains("genshin") ||
            lower.contains("pubg") || lower.contains("cod") || lower.contains("bgmi") ||
            lower.contains("lightroom") || lower.contains("photoshop") || lower.contains("speedtest")) {
            return 80
        }
        
        // 2. Heavy Browsing / Complex Web / Shopping (Stage 2 - 6 Little Cores up to 2.0GHz, Big Cores Sleep)
        if (lower.contains("chrome") || lower.contains("browser") || lower.contains("brave") ||
            lower.contains("amazon") || lower.contains("flipkart")) {
            return 30
        }
        
        // 3. Active Video Call (WhatsApp, Telegram, Meet, Instagram) - Stage 1 Locked 950MHz Little Cores
        if (isVideoCallActive()) {
            return 25
        }

        // 4. Daily Social, Chatting, Messaging, Media, Normal Voice Calls, Settings, System UI (Stage 1 Eco - 650M Base Floor)
        // Normal voice calls, WhatsApp chat, Telegram, YouTube, Instagram, X/Twitter, Phone, Settings, etc.
        return 10
    }

    private fun handleForegroundAppTransition(previousPkg: String, newPkg: String) {
        // Instant 200ms Window Animation Boost for butter-smooth 120fps app-switch transition
        TweakManager.triggerAppSwitchBoost()

        val frozenApps = FreezerManager.getFrozenApps(this) + FreezerManager.getSpecialFreezeApps(this)
        val allSafeApps = MultitaskingManager.getUserWhitelist(this) + MultitaskingManager.protectedApps
        val activeAudioApps = FreezerManager.getActivePlayingAudioPackages(this)

        // 1. INSTANT APP-ENTER UNFREEZE: If entering an app in Freezer list, resume it immediately!
        if (frozenApps.contains(newPkg)) {
            Log.d("AutoTweak", "⚡ Instant App-Enter Auto Unfreeze -> $newPkg")
            FreezerManager.unfreezeApp(newPkg)
        }

        // 2. SMART RECENTS & VISIBILITY-AWARE FREEZE (with Active Media & Video Player Guard):
        // Only freeze apps if they are NO LONGER in Recents, NOT visible on screen, NOT playing audio/video, and NOT whitelisted!
        if (frozenApps.isNotEmpty()) {
            val recentPkgs = FreezerManager.getRecentPackages()
            val detectedEqPkg = FreezerManager.getDetectedEqualizerPackage(this)

            for (pkg in frozenApps) {
                // If this is the active Equalizer, it is safely managed by Smart Equalizer Guard — DO NOT force-stop on app switch!
                if (pkg == detectedEqPkg && FreezerManager.isEqualizerSleepEnabled(this)) {
                    continue
                }

                if (pkg != newPkg && 
                    !recentPkgs.contains(pkg) && 
                    !allSafeApps.contains(pkg) && 
                    !activeAudioApps.contains(pkg) && 
                    !FreezerManager.isAppCurrentlyVisible(pkg)) {
                    
                    Log.d("AutoTweak", "⚡ Closed & Inactive -> Freezing App: $pkg")
                    FreezerManager.freezeApp(this, pkg)
                }
            }
            SpecialFreezerWidgetProvider.updateAllWidgets(this@AutoTweakService)
            FreezerWidgetProvider.updateAllWidgets(this@AutoTweakService)
        }

        // 3. Perform Tweak & Profile Adaptations for new app
        handleForegroundAppEvent(newPkg)
    }

    private fun handleForegroundAppEvent(pkg: String) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val manualStage = prefs.getInt("manual_stage_override", 0)
        if (manualStage != 0 || TweakManager.manualStageOverride != 0) {
            // Strictly protect user's manual stage override (do not switch frequencies on app transition)
            return
        }

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
                val focus = prefs.getString("selected_focus", "rbFocusDaily") ?: "rbFocusDaily"
                val load = calculateAppAiLoad(pkg)
                applyAiTweak(load, focus)
            }
        }
    }

    private fun applyAiTweak(load: Int, focus: String) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val manualStage = prefs.getInt("manual_stage_override", 0)
        if (manualStage != 0) {
            // Respect Test Lab Manual Stage Override 100% (Do not overwrite user test locks)
            return
        }

        val adjustedLoad = if (isFloatingWindowActive && load < 50) {
            if (load > 25) 25 else load 
        } else {
            load
        }

        val targetMode = if (!isScreenOn) {
            "AI_Sleeping"
        } else if (isVideoCallActive()) {
            "AI_VideoCall"
        } else {
            when (focus) {
                "rbFocusBattery" -> {
                    // Battery Saver Focus: Stays strictly in Stage 1 Eco (650MHz - 950MHz) for all app switching & daily tasks
                    when {
                        adjustedLoad > 70 -> "AI_Boost"
                        else -> "AI_EcoActive"
                    }
                }
                "rbFocusDaily" -> {
                    // Daily Fluent Focus: Idle drops to Stage 1 (650M-950M), active gestures/switches jump to Stage 2 (120fps), heavy tasks to Stage 3
                    when {
                        adjustedLoad > 75 -> "AI_Extreme"
                        adjustedLoad > 45 -> "AI_Boost"
                        adjustedLoad > 15 -> "AI_Daily"
                        else -> "AI_EcoActive"
                    }
                }
                "rbFocusMultitasking" -> {
                    // Multitasking Focus: Instant Stage 2 & 3 throughput with idle saver
                    when {
                        adjustedLoad > 50 -> "AI_Extreme"
                        adjustedLoad > 30 -> "AI_Boost"
                        adjustedLoad > 10 -> "AI_Daily"
                        else -> "AI_EcoActive"
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
                "AI_VideoCall" -> "AI: Video Call (950M Lock)"
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

        // 2. Super Doze & Sync Logic with State Preservation
        if (isSuperDoze || isForceDoze) {
            if (isSuperDoze && superDozePrefs.getBoolean("sync_off_enabled", true)) {
                val currentSync = android.content.ContentResolver.getMasterSyncAutomatically()
                superDozePrefs.edit().putBoolean("user_saved_sync_state", currentSync).apply()
                if (currentSync) {
                    ShellUtils.fastCmd("settings put global master_sync_enabled 0")
                }
            }
            if (isSuperDoze && superDozePrefs.getBoolean("radio_off_enabled", false)) {
                val currentData = ShellUtils.runAsRoot("settings get global mobile_data").output.trim() == "1"
                superDozePrefs.edit().putBoolean("user_saved_mobile_data", currentData).apply()
                if (currentData) {
                    ShellUtils.fastCmd("svc data disable")
                }
            }
        }

        // 3. Block Kernel Wakelocks
        TweakManager.applyWakelockBlocker(true)

        // 4. Sensor Logic
        val killSensorsActive = prefs.getBoolean("battery_kill_sensors", false)
        val privacySensorsActive = prefs.getBoolean("battery_privacy_sensors", false)
        val indivBlockActive = prefs.getBoolean("block_gyro", false) || 
                              prefs.getBoolean("block_mag", false) || 
                              prefs.getBoolean("block_light", false) || 
                              prefs.getBoolean("block_motion", false)

        if (killSensorsActive || privacySensorsActive || indivBlockActive) {
            SensorManager.setSensorsEnabled(this@AutoTweakService, false)
        }

        // 5. GPS Auto-Saver on Screen OFF with State Preservation
        if (prefs.getBoolean("gps_auto_saver_enabled", false)) {
            val currentLocMode = TweakManager.getLocationMode(this@AutoTweakService)
            prefs.edit().putInt("user_saved_location_mode", currentLocMode).apply()
            if (currentLocMode != 0) {
                TweakManager.setLocationMode(0)
            }
        }

        // 6. Guarantee Whitelist & Accessibility Exemption
        val allSafeApps = MultitaskingManager.getUserWhitelist(this@AutoTweakService) + MultitaskingManager.protectedApps
        for (pkg in allSafeApps) {
            MultitaskingManager.grantFullExemption(pkg)
        }

        // 7. Standby Guard
        if (prefs.getBoolean("standby_guard_enabled", false)) {
            val result = ShellUtils.runAsRoot("pm list packages -3 | cut -d ':' -f2")
            val packages = result.output.split("\n").filter { it.isNotBlank() }

            for (pkg in packages) {
                if (!allSafeApps.contains(pkg)) {
                    ShellUtils.fastCmd("am set-standby-bucket $pkg restricted 2>/dev/null")
                } else {
                    ShellUtils.fastCmd("am set-standby-bucket $pkg active 2>/dev/null")
                }
            }
        }

        // 8. Auto Hibernation on Screen OFF (Targeted Media Guard)
        val freezerPrefs = getSharedPreferences("freezer_prefs", MODE_PRIVATE)
        if (freezerPrefs.getBoolean("auto_freeze_enabled", false)) {
            val frozenApps = FreezerManager.getFrozenApps(this@AutoTweakService)
            val activeAudioApps = FreezerManager.getActivePlayingAudioPackages(this@AutoTweakService)

            for (pkg in frozenApps) {
                // EXEMPT ONLY the active music player; hibernate all other apps immediately!
                if (!allSafeApps.contains(pkg) && !activeAudioApps.contains(pkg)) {
                    FreezerManager.freezeApp(this@AutoTweakService, pkg)
                } else if (activeAudioApps.contains(pkg)) {
                    Log.d("AutoTweak", "🎵 Smart Media Guard: Exempting active music app '$pkg' from Screen-Off freeze")
                }
            }
        }

        // 9. Zero-Drain Deep Sleep Profile (480MHz Hardware Minimum Floor)
        val manualStage = prefs.getInt("manual_stage_override", 0)
        if (manualStage == 0) {
            TweakManager.applyScreenOffSleep()
        }
    }

    private fun onScreenOn(prefs: android.content.SharedPreferences) {
        isScreenOn = true
        
        Log.d("AutoTweak", "Screen ON Event - Instant 0ms Async Wakeup")
        // 1. Instant 2ms Atomic Wakeup Boost
        TweakManager.triggerTemporaryWakeupBoost()
        TweakManager.setClusterParking(false, deep = true) 
        ShellUtils.fastCmd("echo 'on' > /data/local/tmp/pc_screen")

        // 2. Restore Operation Mode (or Re-enforce Manual Stage)
        val manualStage = prefs.getInt("manual_stage_override", 0)
        if (manualStage != 0) {
            TweakManager.manualStageOverride = manualStage
            TweakManager.applyRawStageScript(manualStage)
        } else {
            val savedMode = prefs.getString("selected_mode", "rbBalance")
            when (savedMode) {
                "rbPowerSaver" -> TweakManager.applyGlobalMode("Power Saver")
                "rbPerformance" -> TweakManager.applyGlobalMode("Performance")
                "rbAutomatic" -> {
                    val focus = prefs.getString("selected_focus", "rbFocusDaily") ?: "rbFocusDaily"
                    val load = calculateAppAiLoad(lastForegroundApp)
                    applyAiTweak(load, focus)
                }
                else -> TweakManager.applyGlobalMode("Balance")
            }
        }

        val superDozePrefs = getSharedPreferences("super_doze_prefs", MODE_PRIVATE)
        val isSuperDoze = prefs.getBoolean("super_doze_enabled", false)

        if (isSuperDoze) {
            if (superDozePrefs.getBoolean("sync_off_enabled", true)) {
                val savedSync = superDozePrefs.getBoolean("user_saved_sync_state", false)
                if (savedSync) {
                    ShellUtils.fastCmd("settings put global master_sync_enabled 1")
                }
            }
            if (superDozePrefs.getBoolean("radio_off_enabled", false)) {
                val savedData = superDozePrefs.getBoolean("user_saved_mobile_data", false)
                if (savedData) {
                    ShellUtils.fastCmd("svc data enable")
                }
            }
        }

        // GPS Auto-Saver Restore with State Preservation
        if (prefs.getBoolean("gps_auto_saver_enabled", false)) {
            val savedLocMode = prefs.getInt("user_saved_location_mode", 0)
            if (savedLocMode != 0) {
                TweakManager.setLocationMode(savedLocMode)
            }
        }

        val indivBlockActive = prefs.getBoolean("block_gyro", false) || 
                              prefs.getBoolean("block_mag", false) || 
                              prefs.getBoolean("block_light", false) || 
                              prefs.getBoolean("block_motion", false)
        val killSensorsActive = prefs.getBoolean("battery_kill_sensors", false)
        val privacySensorsActive = prefs.getBoolean("battery_privacy_sensors", false)

        // Only restore sensor privacy if it was actually toggled off by screen-off triggers
        if (!indivBlockActive && (killSensorsActive || privacySensorsActive)) {
            SensorManager.setSensorsEnabled(this@AutoTweakService, true)
        }

        TweakManager.applyWakelockBlocker(false)
    }

    override fun onDestroy() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioManager != null && audioPlaybackCallback != null) {
                audioManager?.unregisterAudioPlaybackCallback(audioPlaybackCallback)
            }
        } catch (e: Exception) {}
        unregisterReceiver(screenReceiver)
        unregisterReceiver(batteryThermalReceiver)
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
        try {
            cameraManager?.unregisterAvailabilityCallback(cameraAvailabilityCallback)
        } catch (e: Exception) {}
        tweakExecutor.shutdown()
        ShellUtils.closePersistentShell()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
