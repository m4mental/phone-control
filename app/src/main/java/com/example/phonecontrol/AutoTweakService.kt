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
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
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
        const val ACTION_RECENTS_CHANGED = "com.example.phonecontrol.ACTION_RECENTS_CHANGED"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"

        @Volatile var isPerAppActive = false
    }

    @Volatile private var lastForegroundApp = ""
    @Volatile private var isGameTurboActive = false
    @Volatile private var isPerAppBypassActive = false
    @Volatile private var isPerAppDndActive = false
    @Volatile private var isChargingCutOffActive = false
    @Volatile private var isFloatingWindowActive = false
    @Volatile private var isScreenOn = true
    @Volatile private var isWakeupBoosting = false
    @Volatile private var isCameraInUse = false
    @Volatile private var isVideoCallActive = false
    @Volatile private var lastAiMode = ""
    @Volatile private var isAudioCurrentlyActive = false
    @Volatile private var isEqualizerFrozen = false
    @Volatile private var activePerAppMergedConfig: PerAppManager.AppConfig? = null
    @Volatile private var activePerAppEqPreset: String? = null
    @Volatile private var activeAudioPlayingPkg: String? = null

    private val tweakExecutor = Executors.newSingleThreadExecutor()
    private val freezerExecutor = Executors.newSingleThreadExecutor()
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var appOpsManager: AppOpsManager
    private var cameraManager: CameraManager? = null
    private val activeCameras = Collections.synchronizedSet(mutableSetOf<String>())
    private var audioManager: AudioManager? = null
    private var equalizerFreezeHandler: Handler? = null
    private var equalizerFreezeRunnable: Runnable? = null
    private var recentsFreezeRunnable: Runnable? = null
    private var audioPauseDebounceRunnable: Runnable? = null
    private var screenOffFreezeJob: Runnable? = null
    private val screenOffHandler = Handler(Looper.getMainLooper())
    private var aiTickerHandler: Handler? = null
    private var aiTickerRunnable: Runnable? = null
    private var lastServiceCpuTotal = 0L
    private var lastServiceCpuIdle = 0L

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
            // Post-call audio cleanup delay guard (WhatsApp/Telegram audio releases ~200-500ms after camera)
            equalizerFreezeHandler?.postDelayed({
                tweakExecutor.execute { handleVideoCallStateChanged() }
            }, 500)
            equalizerFreezeHandler?.postDelayed({
                tweakExecutor.execute { handleVideoCallStateChanged() }
            }, 1200)
        }
    }

    private fun handleVideoCallStateChanged() {
        val hasCamera = activeCameras.isNotEmpty() || isCameraInUse
        val audioMode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        val isCallAudio = audioMode == AudioManager.MODE_IN_COMMUNICATION || audioMode == AudioManager.MODE_IN_CALL
        val isVideoCallNow = hasCamera || isCallAudio

        if (isVideoCallNow && !isVideoCallActive) {
            isVideoCallActive = true
            Log.d("AutoTweak", "📹 Video Call / Camera ACTIVE -> Locking Little Cores to 950MHz!")
            TweakManager.applyVideoCallEcoLock()
            sendSafeUiUpdate()
        } else if (!isVideoCallNow && (isVideoCallActive || TweakManager.isVideoCallBoostActive)) {
            isVideoCallActive = false
            Log.d("AutoTweak", "📹 Video Call / Camera ENDED -> Restoring previous state!")
            TweakManager.restorePreVideoCallState(this)
            sendSafeUiUpdate()
        }
    }

    private fun sendSafeUiUpdate() {
        if (isScreenOn) {
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


    private val audioRouteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            tweakExecutor.execute {
                // Allow audio routing to settle before polling active output
                try { Thread.sleep(150) } catch (e: Exception) {}
                val outputType = StudioDspManager.getCurrentAudioOutputType(context)
                Log.d("AutoTweak", "Audio route event: $action -> Detected output: $outputType")

                // Pure Event-Driven: Re-evaluate DSP Routing on device connection/disconnection
                updateStudioDspRouting(lastForegroundApp)

                if (PowerampPresetManager.isPerDeviceRoutingEnabled(context) || PowerampPresetManager.isSmartOutputSwitchEnabled(context)) {
                    StudioDspManager.notifyAudioDeviceChanged(context, outputType)
                }
            }
        }
    }

    private val opActiveListener = AppOpsManager.OnOpActiveChangedListener { op, uid, pkg, active ->
        if (op == AppOpsManager.OPSTR_CAMERA || op == "android:phone_call_camera") {
            Log.d("AutoTweak", "Camera Op Active Changed -> pkg: $pkg, active: $active")
            isCameraInUse = active
            tweakExecutor.execute {
                handleVideoCallStateChanged()
            }
        }
    }

    @Volatile private var isWifiCurrentlyConnected: Boolean? = null

    private fun isWifiActive(): Boolean {
        return try {
            val active = connectivityManager.activeNetwork
            if (active != null) {
                val caps = connectivityManager.getNetworkCapabilities(active)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    return true
                }
            }
            val allNetworks = connectivityManager.allNetworks
            for (network in allNetworks) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun handleSmartNetworkSwitch(isWifiNow: Boolean) {
        tweakExecutor.execute {
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("smart_switch_enabled", false)) return@execute

            if (isWifiNow) {
                if (isWifiCurrentlyConnected == true) return@execute
                isWifiCurrentlyConnected = true
                Log.d("AutoTweak", "📶 WiFi Connected -> Auto-disabling Mobile Data")
                val dataState = ShellUtils.runAsRoot("settings get global mobile_data").output.trim()
                if (dataState == "1" || prefs.getBoolean("data_was_on_before_wifi", false)) {
                    prefs.edit().putBoolean("data_was_on_before_wifi", true).apply()
                }
                ShellUtils.fastCmd("settings put global mobile_data 0; svc data disable")
            } else {
                if (isWifiCurrentlyConnected == false) return@execute
                isWifiCurrentlyConnected = false
                Log.d("AutoTweak", "📶 WiFi Lost/Disconnected -> Restoring Mobile Data")
                // Full-proof enable for Android 14 / Nothing OS (both settings and telephony/svc)
                ShellUtils.fastCmd("settings put global mobile_data 1; svc data enable")
                prefs.edit().putBoolean("data_was_on_before_wifi", false).apply()
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                handleSmartNetworkSwitch(true)
            }
        }

        override fun onLost(network: Network) {
            val stillConnected = isWifiActive()
            if (!stillConnected) {
                handleSmartNetworkSwitch(false)
            }
        }
    }

    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isConnected = isWifiActive()
            handleSmartNetworkSwitch(isConnected)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pendingAction = intent.action ?: return
            tweakExecutor.execute {
                val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

                when (pendingAction) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff(prefs)
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> onScreenOn(prefs)
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
                val plugged = intent.getIntExtra(AndroidBatteryManager.EXTRA_PLUGGED, 0)

                tweakExecutor.execute {
                    val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
                    
                    // 1. Thermal check
                    val tempCelsius = tempTenths / 10
                    if (tempCelsius > 0) {
                        ThermalManager.applyAdaptiveThrottling(this@AutoTweakService, tempCelsius)
                    }

                    // 2. Autonomous Battery Charging Cut-off Enforcer (Hysteresis Loop)
                    val isLimitEnabled = prefs.getBoolean("battery_limit_enabled", false)
                    val limitPercent = prefs.getInt("battery_limit_percent", 80)
                    if (level >= 0 && scale > 0) {
                        val battPct = (level * 100) / scale
                        if (plugged != 0) {
                            if (isLimitEnabled) {
                                if (battPct >= limitPercent && !isChargingCutOffActive) {
                                    Log.d("AutoTweak", "⚡ Battery limit reached ($battPct% >= $limitPercent%) -> Cutting off charging!")
                                    BatteryManager.setChargingEnabled(false)
                                    isChargingCutOffActive = true
                                } else if (battPct <= (limitPercent - 3) && isChargingCutOffActive) {
                                    Log.d("AutoTweak", "⚡ Battery dropped below threshold ($battPct% <= ${limitPercent - 3}%) -> Resuming charging!")
                                    BatteryManager.setChargingEnabled(true)
                                    isChargingCutOffActive = false
                                }
                            } else if (isChargingCutOffActive) {
                                BatteryManager.setChargingEnabled(true)
                                isChargingCutOffActive = false
                            }
                        } else {
                            if (isChargingCutOffActive) {
                                Log.d("AutoTweak", "⚡ Charger unplugged -> Resetting charging cut-off state")
                                BatteryManager.setChargingEnabled(true)
                                isChargingCutOffActive = false
                            }
                        }

                        // 3. Low Battery Auto-Saver Trigger
                        val isLowBattTrigger = prefs.getBoolean("batt_low_trigger_enabled", false)
                        val triggerValue = prefs.getInt("batt_low_trigger_value", 20)
                        if (isLowBattTrigger && battPct <= triggerValue) {
                            val currentMode = prefs.getString("selected_mode", "rbBalance")
                            if (currentMode != "rbPowerSaver") {
                                Log.d("AutoTweak", "Low Battery Trigger ($battPct% <= $triggerValue%) -> Auto Switching to Power Saver")
                                prefs.edit().putString("selected_mode", "rbPowerSaver").apply()
                                TweakManager.applyGlobalMode("Power Saver")
                                sendSafeUiUpdate()
                                ModeControlTileService.updateTile(this@AutoTweakService)
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
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, screenFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, screenFilter)
        }

        val battFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryThermalReceiver, battFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryThermalReceiver, battFilter)
        }
        
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        val wifiFilter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiStateReceiver, wifiFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wifiStateReceiver, wifiFilter)
        }

        // Check initial WiFi state upon service startup
        if (isWifiActive()) {
            handleSmartNetworkSwitch(true)
        }

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


            // Smart Output Auto-Switch Receiver (Headphones vs Speaker vs Bluetooth)
            val audioRouteFilter = IntentFilter().apply {
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(audioRouteReceiver, audioRouteFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(audioRouteReceiver, audioRouteFilter)
            }
        } catch (e: Exception) {
            Log.e("AutoTweak", "Failed to register audio callbacks: ${e.message}")
        }

        tweakExecutor.execute {
            handleVideoCallStateChanged()
            AppEventService.enableViaRoot(packageName)
            ShellUtils.fastCmd("dumpsys deviceidle whitelist +$packageName; am set-standby-bucket $packageName active 2>/dev/null")
            ThermalManager.checkAndRecoverCooldown(this)
            FreezerManager.cleanLegacySuspendedApps(this@AutoTweakService)
            FreezerManager.pruneUninstalledPackages(this@AutoTweakService)
            UpdateShieldManager.enforceAllShields(this@AutoTweakService)

            // Auto-initialize Studio Equalizer DSP in background on service startup
            if (PowerampPresetManager.isMasterEnabled(this@AutoTweakService)) {
                StudioDspManager.init(this@AutoTweakService)
            }

            try {
                val dnsObserver = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        PrivateDnsTileService.updateTile(applicationContext)
                    }
                }
                contentResolver.registerContentObserver(
                    android.provider.Settings.Global.getUriFor("private_dns_mode"),
                    false,
                    dnsObserver
                )
                contentResolver.registerContentObserver(
                    android.provider.Settings.Global.getUriFor("private_dns_specifier"),
                    false,
                    dnsObserver
                )
                PrivateDnsTileService.updateTile(this@AutoTweakService)
            } catch (e: Exception) {
                Log.w("AutoTweak", "Failed to register DNS observer: ${e.message}")
            }
        }

        aiTickerHandler = Handler(Looper.getMainLooper())
        startAiTicker()
    }

    private fun handleAudioPlaybackStateChanged(configs: List<AudioPlaybackConfiguration>?) {
        val isMusicActive = audioManager?.isMusicActive == true
        var isAnyConfigActive = false
        if (configs != null) {
            for (config in configs) {
                // Filter out notification/ringtone/sonification usages so transient system alerts don't interrupt music DSP
                val usage = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        config.audioAttributes?.usage ?: AudioAttributes.USAGE_UNKNOWN
                    } else AudioAttributes.USAGE_UNKNOWN
                } catch (e: Exception) { AudioAttributes.USAGE_UNKNOWN }

                val isIgnoredUsage = usage == AudioAttributes.USAGE_NOTIFICATION ||
                        usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE ||
                        usage == AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST ||
                        usage == AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT ||
                        usage == AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED ||
                        usage == AudioAttributes.USAGE_NOTIFICATION_EVENT ||
                        usage == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION ||
                        usage == AudioAttributes.USAGE_ALARM

                if (isIgnoredUsage) continue

                try {
                    val method = config.javaClass.getMethod("isActive")
                    if (method.invoke(config) as? Boolean == true) {
                        isAnyConfigActive = true
                        break
                    }
                } catch (e: Exception) {
                    try {
                        val stateMethod = config.javaClass.getMethod("getPlayerState")
                        val state = stateMethod.invoke(config) as? Int
                        if (state == 2) { // AudioPlaybackConfiguration.PLAYER_STATE_STARTED
                            isAnyConfigActive = true
                            break
                        }
                    } catch (e2: Exception) {}
                }
            }
        }
        val hasActiveAudio = isMusicActive || isAnyConfigActive
        if (hasActiveAudio) {
            isAudioCurrentlyActive = true
            audioPauseDebounceRunnable?.let { equalizerFreezeHandler?.removeCallbacks(it) }
            equalizerFreezeRunnable?.let { equalizerFreezeHandler?.removeCallbacks(it) }

            // Pure Event-Driven: Audio streaming began -> awaken DSP and route to playing app
            updateStudioDspRouting(lastForegroundApp)

            if (!FreezerManager.isEqualizerSleepEnabled(this)) return
            val eqPkg = FreezerManager.getDetectedEqualizerPackage(this) ?: return

            // CRITICAL: Only unfreeze if it was actually in frozen sleep!
            if (isEqualizerFrozen) {
                Log.d("AutoTweak", "🎵 Audio resumed -> Instant unfreeze for Equalizer [$eqPkg]")
                FreezerManager.instantUnfreezeEqualizer(eqPkg)
                isEqualizerFrozen = false
            }
        } else {
            if (isAudioCurrentlyActive) {
                isAudioCurrentlyActive = false
                Log.d("AutoTweak", "⏸️ Audio paused or chunk buffering -> Starting 4s grace debounce for PiP / track transitions")

                audioPauseDebounceRunnable?.let { equalizerFreezeHandler?.removeCallbacks(it) }
                audioPauseDebounceRunnable = Runnable {
                    tweakExecutor.execute {
                        val isStillPlaying = audioManager?.isMusicActive == true || FreezerManager.getActivePlayingAudioPackages(this@AutoTweakService).isNotEmpty()
                        if (!isStillPlaying) {
                            Log.d("AutoTweak", "⏸️ 4s elapsed with no audio -> Evaluating DSP sleep state")
                            updateStudioDspRouting(lastForegroundApp)
                        } else {
                            // If audio resumed or is still playing after temporary pause/duck, ensure DSP is awake!
                            updateStudioDspRouting(lastForegroundApp)
                        }
                    }
                }
                equalizerFreezeHandler?.postDelayed(audioPauseDebounceRunnable!!, 4000)

                equalizerFreezeRunnable?.let { equalizerFreezeHandler?.removeCallbacks(it) }
                equalizerFreezeRunnable = Runnable {
                    tweakExecutor.execute {
                        val isStillPlaying = audioManager?.isMusicActive == true || FreezerManager.getActivePlayingAudioPackages(this@AutoTweakService).isNotEmpty()
                        if (!isStillPlaying) {
                            // Put internal Studio DSP hardware to sleep (0% CPU, 0 battery drain)
                            StudioDspManager.pauseDsp()

                            val eqPkg = FreezerManager.getDetectedEqualizerPackage(this@AutoTweakService)
                            if (eqPkg != null && lastForegroundApp != eqPkg && !isEqualizerFrozen && FreezerManager.isEqualizerSleepEnabled(this@AutoTweakService)) {
                                Log.d("AutoTweak", "❄️ 30s elapsed with no audio -> Freezing Equalizer [$eqPkg]")
                                FreezerManager.instantFreezeEqualizer(eqPkg)
                                isEqualizerFrozen = true
                            }
                        }
                    }
                }
                equalizerFreezeHandler?.postDelayed(equalizerFreezeRunnable!!, 10000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        // 1. Instant Foreground App Event
        if (action == ACTION_FOREGROUND_APP_CHANGED) {
            val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
            val isPhoneControlResuming = (pkg == packageName)
            if (pkg.isNotBlank() && (pkg != lastForegroundApp || isPhoneControlResuming)) {
                val previousApp = lastForegroundApp
                lastForegroundApp = pkg
                tweakExecutor.execute {
                    handleForegroundAppTransition(previousApp, pkg)
                }
            }
            return START_STICKY
        }

        // 2. Targeted Recents Task / Dismiss Event (Debounced to prevent false freeze during app launch)
        if (action == ACTION_RECENTS_CHANGED) {
            tweakExecutor.execute {
                reevaluatePerAppHierarchy(lastForegroundApp)
            }
            recentsFreezeRunnable?.let { equalizerFreezeHandler?.removeCallbacks(it) }
            recentsFreezeRunnable = Runnable {
                triggerFreezerDispatch(lastForegroundApp)
            }
            equalizerFreezeHandler?.postDelayed(recentsFreezeRunnable!!, 600)
            return START_STICKY
        }

        // 2. AI Update Signal
        if (action == "com.example.phonecontrol.ACTION_AI_TICK") {
            val load = intent.getIntExtra("load", 0)
            tweakExecutor.execute {
                if (isPerAppActive || activePerAppMergedConfig != null) return@execute
                val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
                val focus = prefs.getString("selected_focus", "rbFocusDaily")
                applyAiTweak(load, focus ?: "rbFocusDaily")
            }
            return START_STICKY
        }

        // 3. Instant AI Focus / Mode Re-apply Signal
        if (action == "com.example.phonecontrol.ACTION_REAPPLY_AI") {
            lastAiMode = ""
            tweakExecutor.execute {
                checkAndApplyDynamicAiTweak()
            }
            return START_STICKY
        }

        // 3. Service Startup Checks
        tweakExecutor.execute {
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            prefs.edit().remove("user_saved_auto_rotate").apply()
            BackupManager.ensureStorageStructure()
            DaemonManager.startDaemon(this@AutoTweakService)

            if (prefs.getBoolean("silent_system_enabled", false)) {
                TweakManager.setSilentSystem(true)
            }

            if (prefs.getString("selected_mode", "rbBalance") == "rbAutomatic" && !isPerAppActive && activePerAppMergedConfig == null) {
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
        val hasCamera = activeCameras.isNotEmpty() || isCameraInUse
        val audioMode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        val isCallAudio = audioMode == AudioManager.MODE_IN_COMMUNICATION || audioMode == AudioManager.MODE_IN_CALL
        val isNowActive = hasCamera || isCallAudio
        if (!isNowActive && isVideoCallActive) {
            isVideoCallActive = false
        }
        return isNowActive
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
        if (pkg == packageName) {
            return 5 // Phone Control: Idle dashboard inspection, stay calm at baseline 650MHz
        }
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
        // Cancel any pending recents freeze sweep when user transitions to a new app
        recentsFreezeRunnable?.let { equalizerFreezeHandler?.removeCallbacks(it) }

        // Instant 200ms Window Animation Boost for butter-smooth 120fps app-switch transition (skip for Phone Control)
        if (newPkg != packageName) {
            TweakManager.triggerAppSwitchBoost()
        }

        // Guard: Re-verify Video Call state on app switch to guarantee no stale lock persists
        if (isVideoCallActive || TweakManager.isVideoCallBoostActive) {
            if (!isVideoCallActive()) {
                handleVideoCallStateChanged()
            }
        }

        // 1. TOP PRIORITY: Apply Tweak & Per-App Profile IMMEDIATELY (0ms latency)!
        reevaluatePerAppHierarchy(newPkg)

        // 2. BACKGROUND FREEZER DISPATCH:
        triggerFreezerDispatch(newPkg)

        // 3. Fallback verification for Recents swipe: When returning to launcher/home, re-check recents after 800ms
        if (newPkg.contains("launcher", ignoreCase = true)) {
            equalizerFreezeHandler?.postDelayed({
                tweakExecutor.execute {
                    reevaluatePerAppHierarchy(newPkg)
                }
                triggerFreezerDispatch(newPkg)
            }, 800)
        }
    }

    private fun triggerFreezerDispatch(currentForeground: String) {
        if (!FreezerManager.isAutoFreezeEnabled(this)) return

        freezerExecutor.execute {
            // 1. Register foreground app if it's a real user application (grants 0ms immunity + unfreezes)
            if (currentForeground.isNotBlank() &&
                !currentForeground.contains("launcher", ignoreCase = true) &&
                currentForeground != "com.android.systemui" &&
                currentForeground != packageName
            ) {
                FreezerManager.registerAppOpen(currentForeground)
            }

            // 2. Sync all alive recents tasks into activeSessionApps
            val recentPkgs = FreezerManager.getRecentPackages()
            for (p in recentPkgs) {
                FreezerManager.activeSessionApps.add(p)
            }

            // 3. Ultra-fast targeted freeze on apps swiped away / dismissed from Recents (~20ms)
            FreezerManager.processRecentsDismissal(this@AutoTweakService, recentPkgs, currentForeground)

            SpecialFreezerWidgetProvider.updateAllWidgets(this@AutoTweakService)
            FreezerWidgetProvider.updateAllWidgets(this@AutoTweakService)
        }
    }

    /**
     * Evaluates all configured apps in Recents + Foreground, resolves multi-app conflicts
     * using the highest performance hierarchy, and reverts to Global Baseline when all configured
     * apps are closed & removed from Recents.
     */
    private fun reevaluatePerAppHierarchy(foregroundPkg: String) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        if (TweakManager.isPostBootTurboActive) {
            // Strictly protect Post-Boot Fast Startup Turbo Boost for its full 90 seconds
            return
        }
        val manualStage = prefs.getInt("manual_stage_override", 0)
        if (manualStage != 0 || TweakManager.manualStageOverride != 0) {
            // Strictly protect user's manual stage override (do not switch frequencies on app transition)
            return
        }

        val turboPrefs = getSharedPreferences("game_turbo_prefs", MODE_PRIVATE)
        val games = turboPrefs.getStringSet("game_packages", emptySet()) ?: emptySet()
        


        // 1. Collect all live packages in Recent Tasks + In-Memory Active Sessions + Current Foreground
        val recentPkgs = FreezerManager.getRecentPackages(forceRefresh = true).toMutableSet()
        FreezerManager.activeSessionApps.retainAll(recentPkgs)
        recentPkgs.addAll(FreezerManager.activeSessionApps)
        if (foregroundPkg.isNotBlank() && !foregroundPkg.contains("launcher", ignoreCase = true) && foregroundPkg != "com.android.systemui" && foregroundPkg != packageName) {
            recentPkgs.add(foregroundPkg)
            FreezerManager.activeSessionApps.add(foregroundPkg)
        }

        // Check if current foreground app itself has an explicit profile (user actively inside it)
        val isEligibleFg = foregroundPkg.isNotBlank() && foregroundPkg != packageName && !foregroundPkg.contains("launcher", ignoreCase = true) && foregroundPkg != "com.android.systemui"
        val isGameInForeground = isEligibleFg && games.contains(foregroundPkg)
        val isGameTurboMaster = turboPrefs.getBoolean("game_turbo_enabled", false)

        val fgConfig = if (isEligibleFg) {
            val isGame = games.contains(foregroundPkg)
            if (isGame && isGameTurboMaster) {
                PerAppManager.AppConfig(
                    mode = "Game Turbo",
                    fps = if (turboPrefs.getBoolean("game_turbo_auto_120hz", true)) "120Hz" else "Auto Switch",
                    thermal = if (turboPrefs.getBoolean("auto_thermal_enabled", false)) "Disabled" else "Default",
                    touch = "On",
                    bypassCharging = false,
                    autoDnd = false
                )
            } else {
                val directCfg = PerAppManager.getConfig(this, foregroundPkg)
                if (directCfg != null) {
                    if (isGame && directCfg.mode == "Auto" && turboPrefs.getBoolean("auto_perf_enabled", true)) {
                        directCfg.copy(mode = "Performance")
                    } else {
                        directCfg
                    }
                } else if (isGame) {
                    PerAppManager.AppConfig(
                        mode = if (turboPrefs.getBoolean("auto_perf_enabled", true)) "Performance" else "Auto",
                        fps = "Auto Switch",
                        thermal = if (turboPrefs.getBoolean("auto_thermal_enabled", false)) "Disabled" else "Default",
                        touch = "On",
                        bypassCharging = false,
                        autoDnd = false
                    )
                } else null
            }
        } else null

        val activeConfigs = mutableListOf<PerAppManager.AppConfig>()
        val activeRulePackages = mutableListOf<String>()

        for (pkg in recentPkgs) {
            val isGame = games.contains(pkg)
            if (pkg == foregroundPkg && isGame && isGameTurboMaster) {
                val gameConfig = PerAppManager.AppConfig(
                    mode = "Game Turbo",
                    fps = if (turboPrefs.getBoolean("game_turbo_auto_120hz", true)) "120Hz" else "Auto Switch",
                    thermal = if (turboPrefs.getBoolean("auto_thermal_enabled", false)) "Disabled" else "Default",
                    touch = "On",
                    bypassCharging = false,
                    autoDnd = false
                )
                activeConfigs.add(gameConfig)
                activeRulePackages.add(pkg)
            } else {
                val cfg = PerAppManager.getConfig(this, pkg)
                if (cfg != null) {
                    val effectiveCfg = if (isGame && cfg.mode == "Auto" && turboPrefs.getBoolean("auto_perf_enabled", true)) {
                        cfg.copy(mode = "Performance")
                    } else {
                        cfg
                    }
                    activeConfigs.add(effectiveCfg)
                    activeRulePackages.add(pkg)
                } else if (isGame) {
                    val gameConfig = PerAppManager.AppConfig(
                        mode = if (turboPrefs.getBoolean("auto_perf_enabled", true)) "Performance" else "Auto",
                        fps = "Auto Switch",
                        thermal = if (turboPrefs.getBoolean("auto_thermal_enabled", false)) "Disabled" else "Default",
                        touch = "On",
                        bypassCharging = false,
                        autoDnd = false
                    )
                    activeConfigs.add(gameConfig)
                    activeRulePackages.add(pkg)
                }
            }
        }

        // 2. Resolve Highest Priority Rule:
        // When user is actively inside a configured app, its direct config takes immediate focus.
        // When user leaves to Phone Control, Launcher, or an unconfigured app, the highest priority rule from active Recents apps takes over!
        val mergedConfig = if (fgConfig != null) {
            fgConfig
        } else {
            PerAppManager.mergeConfigs(activeConfigs)
        }

        if (mergedConfig != null) {
            val configChanged = mergedConfig != activePerAppMergedConfig || !isPerAppActive
            if (configChanged) {
                Log.d("AutoTweak", "⚡ Per-App Hierarchy Applied: Mode=${mergedConfig.mode}, FPS=${mergedConfig.fps}, Thermal=${mergedConfig.thermal}, Touch=${mergedConfig.touch}, Bypass=${mergedConfig.bypassCharging}, DND=${mergedConfig.autoDnd}. Active Configured Apps in Recents: $activeRulePackages")
                lastAiMode = ""
                
                if (mergedConfig.mode == "Game Turbo") {
                    GameTurboManager.applyGameTurbo(this, true)
                } else {
                    if (GameTurboManager.isGameTurboActive) {
                        GameTurboManager.applyGameTurbo(this, false)
                    }
                    if (mergedConfig.mode == "Custom") {
                        TweakManager.applyCustomAppProfile(
                            mergedConfig.customLittleMin,
                            mergedConfig.customLittleMax,
                            mergedConfig.customBigMin,
                            mergedConfig.customBigMax,
                            mergedConfig.customGovernor
                        )
                    } else if (mergedConfig.mode != "Auto") {
                        TweakManager.applyGlobalMode(mergedConfig.mode)
                    }
                }
                if (mergedConfig.fps != "Auto Switch") {
                    TweakManager.setRefreshRate(mergedConfig.fps)
                }
                if (mergedConfig.thermal == "Disabled") {
                    ThermalManager.setThrottlingEnabled(false)
                } else {
                    val isThrottlingDisabled = prefs.getBoolean("disable_throttling", false)
                    ThermalManager.setThrottlingEnabled(!isThrottlingDisabled)
                }
                if (mergedConfig.touch == "On") {
                    TweakManager.applyInputBoost(true)
                }

                // Process Priority for games & performance apps
                for (p in activeRulePackages) {
                    if (mergedConfig.mode == "Performance" || games.contains(p)) {
                        TweakManager.applyProcessPriority(p, true)
                    }
                    if (games.contains(p) && turboPrefs.getBoolean("auto_ping_enabled", true)) {
                        TweakManager.setNetworkPriority(this, p, true)
                    }
                }

                // Automation: Auto Bypass Charging
                if (mergedConfig.bypassCharging) {
                    BatteryManager.setBypassCharging(this, true)
                    isPerAppBypassActive = true
                } else if (isPerAppBypassActive) {
                    val savedBypass = prefs.getBoolean("battery_bypass_charging", false)
                    BatteryManager.setBypassCharging(this, savedBypass)
                    isPerAppBypassActive = false
                }

                // Automation: Gaming DND
                if (mergedConfig.autoDnd) {
                    ShellUtils.fastCmd("cmd notification set_zen_mode 1")
                    isPerAppDndActive = true
                } else if (isPerAppDndActive) {
                    ShellUtils.fastCmd("cmd notification set_zen_mode 0")
                    isPerAppDndActive = false
                }

                activePerAppMergedConfig = mergedConfig
                isPerAppActive = true
                isGameTurboActive = activeRulePackages.any { games.contains(it) }

                val dominantPkg = if (fgConfig != null) {
                    foregroundPkg
                } else {
                    activeRulePackages.maxByOrNull { pkg ->
                        val cfg = PerAppManager.getConfig(this, pkg)
                        PerAppManager.getModePriority(cfg?.mode ?: "")
                    } ?: activeRulePackages.firstOrNull() ?: ""
                }
                prefs.edit()
                    .putString("active_per_app_mode", mergedConfig.mode)
                    .putString("active_per_app_pkg", dominantPkg)
                    .apply()
                sendSafeUiUpdate()
                ModeControlTileService.updateTile(this)
            }

            // Dynamic Load-Aware Scaling for Streaming Mode (950MHz -> 1100MHz -> 1200MHz)
            if (mergedConfig.mode == "Streaming") {
                val currentLoad = calculateAppAiLoad(foregroundPkg)
                TweakManager.applyStreamingDynamic(currentLoad)
            }
        } else {
            // NO configured apps in Recents or Foreground -> REVERT TO GLOBAL BASELINE!
            val wasPerApp = isPerAppActive || isGameTurboActive || activePerAppMergedConfig != null || GameTurboManager.isGameTurboActive
            if (wasPerApp) {
                Log.d("AutoTweak", "⚡ All configured apps closed & removed from Recents -> Instant Revert to Global Mode")
                if (GameTurboManager.isGameTurboActive) {
                    GameTurboManager.applyGameTurbo(this, false)
                }
                activePerAppMergedConfig = null
                isPerAppActive = false
                isGameTurboActive = false
                lastAiMode = "" // Force AI Engine to re-evaluate and apply hardware frequencies

                prefs.edit()
                    .remove("active_per_app_mode")
                    .remove("active_per_app_pkg")
                    .apply()
                sendSafeUiUpdate()
                ModeControlTileService.updateTile(this)

                // Restore user's saved thermal throttling preference
                val isThrottlingDisabled = prefs.getBoolean("disable_throttling", false)
                ThermalManager.setThrottlingEnabled(!isThrottlingDisabled)

                // Restore user's saved refresh rate
                val savedHzKey = prefs.getString("screen_refresh", "rbHzDynamic")
                val targetRate = when (savedHzKey) {
                    "rbHz120" -> "120Hz"
                    "rbHz90" -> "90Hz"
                    "rbHz60" -> "60Hz"
                    else -> "Default"
                }
                TweakManager.setRefreshRate(targetRate)

                // Atomically restore Bypass Charging
                if (isPerAppBypassActive) {
                    val savedBypass = prefs.getBoolean("battery_bypass_charging", false)
                    BatteryManager.setBypassCharging(this, savedBypass)
                    isPerAppBypassActive = false
                }

                // Restore Gaming DND
                if (isPerAppDndActive) {
                    ShellUtils.fastCmd("cmd notification set_zen_mode 0")
                    isPerAppDndActive = false
                }
            }

            if (!isPerAppActive && activePerAppMergedConfig == null) {
                val savedModeKey = prefs.getString("selected_mode", "rbBalance") ?: "rbBalance"
                when (savedModeKey) {
                    "rbPowerSaver" -> if (wasPerApp) TweakManager.applyGlobalMode("Power Saver")
                    "rbPerformance" -> if (wasPerApp) TweakManager.applyGlobalMode("Performance")
                    "rbStreaming" -> if (wasPerApp) TweakManager.applyGlobalMode("Streaming")
                    "rbAutomatic" -> {
                        val focus = prefs.getString("selected_focus", "rbFocusDaily") ?: "rbFocusDaily"
                        val load = calculateAppAiLoad(foregroundPkg)
                        applyAiTweak(load, focus)
                    }
                    else -> if (wasPerApp) TweakManager.applyGlobalMode("Balance")
                }
            }
        }

        // 3. Smart Event-Driven Studio DSP Routing & Background Audio Guard
        updateStudioDspRouting(foregroundPkg)
    }

    /**
     * Smart Event-Driven Studio DSP Engine Controller.
     * Evaluates output route (Headphones Only) and playback context (Targeted Apps Only),
     * ensuring DSP remains 100% active for background music players (Spotify/YT Music)
     * while putting hardware DSP to true sleep (0% CPU, enabled=false) when unlisted apps play or audio stops.
     */
    private fun updateStudioDspRouting(foregroundPkg: String = lastForegroundApp) {
        if (!PowerampPresetManager.isMasterEnabled(this)) {
            StudioDspManager.setMasterEnabled(this, false)
            return
        }

        // 1. Output Device Check: Headphones Only Mode
        val isHeadphonesOnly = PowerampPresetManager.isHeadphonesOnlyMode(this)
        val currentOutput = StudioDspManager.getCurrentAudioOutputType(this)
        val isSpeaker = currentOutput == PowerampPresetManager.AudioOutputType.SPEAKER

        if (isHeadphonesOnly && isSpeaker) {
            Log.d("AutoTweak", "🎧 Headphones Only Mode active & Phone Speaker in use -> DSP Sleep (Bypass)")
            StudioDspManager.setBypass(this, true)
            return
        }

        // 2. Targeted Apps Only Mode Check
        val isTargetAppsOnly = PowerampPresetManager.isTargetAppsOnlyMode(this)
        val activeAudioPkgs = FreezerManager.getActivePlayingAudioPackages(this)
        val isMusicPlaying = audioManager?.isMusicActive == true || activeAudioPkgs.isNotEmpty()

        if (isTargetAppsOnly) {
            val targetedRules = PowerampPresetManager.getAllAppPresets(this)
            val perAppConfigs = PerAppManager.getAllConfigs(this)
            val targetedPkgs = (targetedRules.keys + perAppConfigs.keys.filter { pkg ->
                val cfg = PerAppManager.getConfig(this, pkg)
                val p = cfg?.eqPreset
                !p.isNullOrBlank() && p != "Default" && p != "Default (System)"
            }).toSet()

            // Check if any active audio playing package is a targeted app (Foreground or Background)
            val prevPlayingTarget = activeAudioPlayingPkg?.takeIf { targetedPkgs.contains(it) }
            val playingTargetPkg = activeAudioPkgs.firstOrNull { targetedPkgs.contains(it) }
                ?: if (isMusicPlaying) prevPlayingTarget else null

            if (playingTargetPkg != null) {
                // Background or foreground targeted player is streaming audio!
                val presetName = PowerampPresetManager.getAppPreset(this, playingTargetPkg)
                    ?: PerAppManager.getConfig(this, playingTargetPkg)?.eqPreset
                    ?: PowerampPresetManager.getActivePresetName(this)

                val stateChanged = StudioDspManager.getActiveTargetAppPkg() != playingTargetPkg
                StudioDspManager.setActiveTargetApp(playingTargetPkg, presetName)
                StudioDspManager.setBypass(this, false)
                StudioDspManager.resumeDsp(this)

                if (!presetName.isNullOrBlank() && activePerAppEqPreset != presetName) {
                    Log.d("AutoTweak", "🎯 Targeted Audio Player Active ($playingTargetPkg) -> EQ Preset: $presetName")
                    StudioDspManager.applyPresetByName(this, presetName)
                    activePerAppEqPreset = presetName
                    activeAudioPlayingPkg = playingTargetPkg
                }
                if (stateChanged) {
                    sendBroadcast(Intent("com.example.phonecontrol.UPDATE_UI").setPackage(packageName))
                }
                return
            } else if (targetedPkgs.contains(foregroundPkg) && isMusicPlaying) {
                // Foreground app is targeted and playing audio
                val presetName = PowerampPresetManager.getAppPreset(this, foregroundPkg)
                    ?: PerAppManager.getConfig(this, foregroundPkg)?.eqPreset
                    ?: PowerampPresetManager.getActivePresetName(this)

                val stateChanged = StudioDspManager.getActiveTargetAppPkg() != foregroundPkg
                StudioDspManager.setActiveTargetApp(foregroundPkg, presetName)
                StudioDspManager.setBypass(this, false)
                StudioDspManager.resumeDsp(this)

                if (!presetName.isNullOrBlank() && activePerAppEqPreset != presetName) {
                    Log.d("AutoTweak", "🎯 Foreground Targeted App ($foregroundPkg) -> EQ Preset: $presetName")
                    StudioDspManager.applyPresetByName(this, presetName)
                    activePerAppEqPreset = presetName
                    activeAudioPlayingPkg = foregroundPkg
                }
                if (stateChanged) {
                    sendBroadcast(Intent("com.example.phonecontrol.UPDATE_UI").setPackage(packageName))
                }
                return
            } else {
                // Check if music is still actually active in the system before sleeping DSP
                if (isMusicPlaying && prevPlayingTarget != null) {
                    // Audio is still actively streaming (e.g. YouTube in PiP/background during track transition or notification ducking)
                    Log.d("AutoTweak", "🎯 Guarding active targeted playback ($prevPlayingTarget) during audio flux")
                    StudioDspManager.resumeDsp(this)
                    return
                }

                // Neither actively playing audio is from a targeted app nor is foreground app targeted with audio
                // Put DSP hardware into TRUE SLEEP (0% CPU, 0 battery drain)
                val wasActive = StudioDspManager.getActiveTargetAppPkg() != null
                Log.d("AutoTweak", "🎯 Targeted Apps Mode -> No targeted player streaming -> DSP SLEEP (0% CPU)")
                StudioDspManager.setActiveTargetApp(null, null)
                StudioDspManager.setBypass(this, true)
                StudioDspManager.pauseDsp()
                activePerAppEqPreset = null
                activeAudioPlayingPkg = null
                if (wasActive) {
                    sendBroadcast(Intent("com.example.phonecontrol.UPDATE_UI").setPackage(packageName))
                }
                return
            }
        }

        // 3. Normal Global Mode (Targeted Apps Only is OFF)
        StudioDspManager.setBypass(this, false)

        val isEligibleFg = foregroundPkg.isNotBlank() && foregroundPkg != packageName && !foregroundPkg.contains("launcher", ignoreCase = true) && foregroundPkg != "com.android.systemui"
        val fgEqPreset = if (isEligibleFg) {
            PowerampPresetManager.getAppPreset(this, foregroundPkg)
                ?: PerAppManager.getConfig(this, foregroundPkg)?.eqPreset?.takeIf { it.isNotBlank() && it != "Default" && it != "Default (System)" }
        } else null

        if (isMusicPlaying) {
            StudioDspManager.resumeDsp(this)
            val isFgPlayingAudio = activeAudioPkgs.contains(foregroundPkg)
            if (isFgPlayingAudio) {
                if (fgEqPreset != null && fgEqPreset != activePerAppEqPreset) {
                    Log.d("AutoTweak", "🎧 Foreground Audio App $foregroundPkg -> Switching EQ Preset to: $fgEqPreset")
                    StudioDspManager.applyPresetByName(this, fgEqPreset)
                    activePerAppEqPreset = fgEqPreset
                    activeAudioPlayingPkg = foregroundPkg
                } else if (fgEqPreset == null && activePerAppEqPreset != null && activeAudioPlayingPkg != foregroundPkg) {
                    Log.d("AutoTweak", "🎧 Foreground Audio App $foregroundPkg has no custom EQ -> Restoring default preset")
                    StudioDspManager.restoreDefaultOutputPreset(this)
                    activePerAppEqPreset = null
                    activeAudioPlayingPkg = foregroundPkg
                }
            } else {
                // Background music playing while foreground app is doing non-audio tasks (e.g. WhatsApp, Browser)
                val bgPlayingPkg = activeAudioPkgs.firstOrNull() ?: activeAudioPlayingPkg
                if (bgPlayingPkg != null) {
                    val bgPreset = PowerampPresetManager.getAppPreset(this, bgPlayingPkg)
                        ?: PerAppManager.getConfig(this, bgPlayingPkg)?.eqPreset
                    if (!bgPreset.isNullOrBlank() && bgPreset != "Default" && bgPreset != "Default (System)") {
                        if (activePerAppEqPreset != bgPreset) {
                            Log.d("AutoTweak", "🎧 Guarding background audio ($bgPlayingPkg) -> Applying EQ: $bgPreset")
                            StudioDspManager.applyPresetByName(this, bgPreset)
                            activePerAppEqPreset = bgPreset
                            activeAudioPlayingPkg = bgPlayingPkg
                        }
                    }
                }
            }
        } else {
            if (fgEqPreset != null) {
                if (fgEqPreset != activePerAppEqPreset) {
                    Log.d("AutoTweak", "🎧 Foreground App $foregroundPkg -> Applying EQ Preset: $fgEqPreset")
                    StudioDspManager.applyPresetByName(this, fgEqPreset)
                    activePerAppEqPreset = fgEqPreset
                }
            } else if (activePerAppEqPreset != null && !isEligibleFg) {
                Log.d("AutoTweak", "🎧 Returning to launcher with no audio -> Restoring default output EQ")
                StudioDspManager.restoreDefaultOutputPreset(this)
                activePerAppEqPreset = null
                activeAudioPlayingPkg = null
            }
        }
    }

    private fun startAiTicker() {
        stopAiTicker()
        if (!isScreenOn) return
        aiTickerRunnable = object : Runnable {
            override fun run() {
                if (isScreenOn) {
                    tweakExecutor.execute {
                        checkAndApplyDynamicAiTweak()
                    }
                }
                aiTickerHandler?.postDelayed(this, 1500)
            }
        }
        aiTickerHandler?.postDelayed(aiTickerRunnable!!, 1500)
    }

    private fun stopAiTicker() {
        aiTickerRunnable?.let { aiTickerHandler?.removeCallbacks(it) }
        aiTickerRunnable = null
    }

    private fun checkAndApplyDynamicAiTweak() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        if (prefs.getString("selected_mode", "rbBalance") != "rbAutomatic") return
        if (isPerAppActive || activePerAppMergedConfig != null) return
        if (prefs.getInt("manual_stage_override", 0) != 0 || TweakManager.manualStageOverride != 0) return
        if (TweakManager.isPostBootTurboActive) return

        val liveCpuUsage = readCurrentCpuUsage()
        val focus = prefs.getString("selected_focus", "rbFocusDaily") ?: "rbFocusDaily"
        applyAiTweak(liveCpuUsage, focus)
    }

    private fun readCurrentCpuUsage(): Int {
        return try {
            val reader = java.io.BufferedReader(java.io.FileReader("/proc/stat"))
            val line = reader.readLine()
            reader.close()
            if (line != null && line.startsWith("cpu ")) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 5) {
                    val user = parts[1].toLong()
                    val nice = parts[2].toLong()
                    val system = parts[3].toLong()
                    val idle = parts[4].toLong()
                    val iowait = if (parts.size > 5) parts[5].toLong() else 0L
                    val irq = if (parts.size > 6) parts[6].toLong() else 0L
                    val softirq = if (parts.size > 7) parts[7].toLong() else 0L

                    val total = user + nice + system + idle + iowait + irq + softirq
                    val active = total - (idle + iowait)

                    val totalDelta = total - lastServiceCpuTotal
                    val activeDelta = active - (lastServiceCpuTotal - lastServiceCpuIdle)

                    lastServiceCpuTotal = total
                    lastServiceCpuIdle = idle + iowait

                    if (totalDelta > 0 && activeDelta >= 0) {
                        (activeDelta * 100 / totalDelta).toInt().coerceIn(0, 100)
                    } else 0
                } else 0
            } else 0
        } catch (e: Exception) {
            0
        }
    }

    private fun applyAiTweak(load: Int, focus: String) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        if (TweakManager.isPostBootTurboActive) {
            // Strictly protect Post-Boot Fast Startup Turbo Boost for its full 90 seconds
            return
        }
        val manualStage = prefs.getInt("manual_stage_override", 0)
        if (manualStage != 0 || TweakManager.manualStageOverride != 0) {
            // Respect Test Lab Manual Stage Override 100% (Do not overwrite user test locks)
            return
        }
        if (isPerAppActive || activePerAppMergedConfig != null) {
            // Strictly protect active Per-App hierarchy while configured apps remain in Recents!
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
                    // Battery Saver Focus: 4-Stage Progressive Dynamic EAS Ladder
                    // Stage 1 (0-35% load): 650M -> 850M -> 950M (Big Cores 400MHz Sleep)
                    // Stage 2 (35-70% load): Pure 6-Core Fluid up to 2.0GHz (Big Cores 400MHz Sleep)
                    // Stage 3 (70-90% load): Dual-Cluster Compute (2.0GHz Little + 1.5GHz Big)
                    // Stage 4 (>90% load): Extreme Turbo Unleashed (2.0GHz Little + 2.8GHz Big Turbo)
                    when {
                        adjustedLoad > 90 -> "AI_Extreme"
                        adjustedLoad > 70 -> "AI_Boost"
                        adjustedLoad > 40 -> "AI_Daily"
                        adjustedLoad > 25 -> "AI_Eco950"
                        adjustedLoad > 14 -> "AI_Eco850"
                        else -> "AI_Eco650"
                    }
                }
                "rbFocusDaily" -> {
                    // Daily Usage Focus: Smart balance between speed and battery
                    when {
                        adjustedLoad > 85 -> "AI_Extreme"
                        adjustedLoad > 60 -> "AI_Boost"
                        adjustedLoad > 18 -> "AI_Daily"
                        adjustedLoad > 8 -> "AI_Eco850"
                        else -> "AI_Eco650"
                    }
                }
                "rbFocusMultitasking" -> {
                    // Multitasking Focus: Instant Stage 2 & 3 throughput with idle saver
                    when {
                        adjustedLoad > 75 -> "AI_Extreme"
                        adjustedLoad > 45 -> "AI_Boost"
                        adjustedLoad > 12 -> "AI_Daily"
                        else -> "AI_Eco850"
                    }
                }
                else -> "AI_Daily"
            }
        }

        if (targetMode != lastAiMode) {
            TweakManager.applyGlobalMode(targetMode)
            
            if (isFloatingWindowActive && adjustedLoad < 50 && (targetMode == "AI_Daily" || targetMode == "AI_Sleeping" || targetMode.startsWith("AI_Eco"))) {
                Log.d("AutoTweak", "Floating Active - Enforcing 6-Core Efficiency Priority")
                TweakManager.setClusterParking(true) 
            }

            lastAiMode = targetMode
            
            val displayLabel = when(targetMode) {
                "AI_Sleeping" -> "AI: Sleeping"
                "AI_VideoCall" -> "AI: Video Call (950M Lock)"
                "AI_Eco650", "AI_EcoActive" -> "AI: Battery Eco (650M)"
                "AI_Eco850" -> "AI: Battery Fluid (850M)"
                "AI_Eco950" -> "AI: Battery Burst (950M)"
                "AI_Daily" -> "AI: Daily Fluent"
                "AI_Boost" -> "AI: Multi-Boost"
                "AI_Extreme" -> "AI: Extreme Turbo"
                else -> "AI: Active"
            }
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("active_ai_label", displayLabel).apply()
            sendSafeUiUpdate()
            ModeControlTileService.updateTile(this)
        }
    }

    private fun onScreenOff(prefs: android.content.SharedPreferences) {
        isScreenOn = false
        isWakeupBoosting = false
        stopAiTicker()
        
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
                val currentSync = try {
                    android.content.ContentResolver.getMasterSyncAutomatically()
                } catch (e: Exception) {
                    ShellUtils.fastCmdResult("settings get global master_sync_enabled").trim() == "1"
                }
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
        if (prefs.getBoolean("standby_guard_active", false) || prefs.getBoolean("standby_guard_enabled", false)) {
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

        // 8. Auto Hibernation on Screen OFF (Targeted Media Guard & Smart Delay)
        if (FreezerManager.isAutoFreezeEnabled(this@AutoTweakService)) {
            val delaySeconds = FreezerManager.getAutoFreezeDelaySeconds(this@AutoTweakService)
            if (delaySeconds > 0) {
                scheduleScreenOffFreeze(delaySeconds, allSafeApps)
            } else {
                executeScreenOffFreeze(allSafeApps)
            }
        }

        // 9. Zero-Drain Deep Sleep Profile (480MHz Hardware Minimum Floor)
        // Strictly applies to ALL modes (Manual Stages, Manual Profiles & AI Engine)
        lastAiMode = "AI_Sleeping"
        TweakManager.applyScreenOffSleep()
    }

    private fun scheduleScreenOffFreeze(delaySeconds: Int, allSafeApps: Set<String>) {
        cancelScreenOffFreeze()
        val runnable = Runnable {
            if (!isScreenOn) {
                Log.d("AutoTweak", "⏳ Freeze Delay (${delaySeconds}s) Expired -> Executing Screen-Off Freeze")
                executeScreenOffFreeze(allSafeApps)
            }
        }
        screenOffFreezeJob = runnable
        screenOffHandler.postDelayed(runnable, delaySeconds * 1000L)
        Log.d("AutoTweak", "⏳ Scheduled Screen-Off Freeze in $delaySeconds seconds")
    }

    private fun cancelScreenOffFreeze() {
        screenOffFreezeJob?.let {
            screenOffHandler.removeCallbacks(it)
            screenOffFreezeJob = null
            Log.d("AutoTweak", "🛑 Cancelled Screen-Off Freeze (Screen Active)")
        }
    }

    private fun executeScreenOffFreeze(allSafeApps: Set<String>) {
        freezerExecutor.execute {
            val frozenApps = FreezerManager.getFrozenApps(this@AutoTweakService) + FreezerManager.getSpecialFreezeApps(this@AutoTweakService)
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
    }

    private fun onScreenOn(prefs: android.content.SharedPreferences) {
        isScreenOn = true
        cancelScreenOffFreeze()
        
        Log.d("AutoTweak", "Screen ON Event - Instant 0ms Async Wakeup")
        ShellUtils.fastCmd("echo 'on' > /data/local/tmp/pc_screen")

        // 1. Instant 0ms Atomic Wakeup Boost (Unpark cores, 3.5s MediaTek GED GPU boost, schedutil ramp)
        TweakManager.triggerTemporaryWakeupBoost()

        // 2. Force reset lastAiMode so active screen-on mode is 100% guaranteed to apply immediately
        lastAiMode = ""

        val manualStage = prefs.getInt("manual_stage_override", 0)
        if (manualStage != 0) {
            // Strictly re-enforce user's Test Lab Stage lock without overwriting governors
            TweakManager.manualStageOverride = manualStage
            TweakManager.applyRawStageScript(manualStage)
        } else {
            TweakManager.setClusterParking(false, deep = true)

            // 3. Restore Operation Mode immediately (Clears 480MHz and applies active mode)
            val savedMode = prefs.getString("selected_mode", "rbBalance")
            when (savedMode) {
                "rbPowerSaver" -> TweakManager.applyGlobalMode("Power Saver")
                "rbPerformance" -> TweakManager.applyGlobalMode("Performance")
                "rbStreaming" -> TweakManager.applyGlobalMode("Streaming")
                "rbAutomatic" -> {
                    val focus = prefs.getString("selected_focus", "rbFocusDaily") ?: "rbFocusDaily"
                    val load = calculateAppAiLoad(lastForegroundApp)
                    applyAiTweak(load, focus)
                    startAiTicker()
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
        sendSafeUiUpdate()
    }

    override fun onDestroy() {
        stopAiTicker()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioManager != null && audioPlaybackCallback != null) {
                audioManager?.unregisterAudioPlaybackCallback(audioPlaybackCallback)
            }
        } catch (e: Exception) {}
        unregisterReceiver(screenReceiver)
        unregisterReceiver(batteryThermalReceiver)
        try { unregisterReceiver(wifiStateReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(audioRouteReceiver) } catch (e: Exception) {}
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
        try {
            cameraManager?.unregisterAvailabilityCallback(cameraAvailabilityCallback)
        } catch (e: Exception) {}
        tweakExecutor.shutdown()
        freezerExecutor.shutdown()
        ShellUtils.closePersistentShell()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
