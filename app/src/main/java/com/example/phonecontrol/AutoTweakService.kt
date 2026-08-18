package com.example.phonecontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.os.BatteryManager as AndroidBatteryManager
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*

/**
 * Modernized AutoTweakService: Now an Event-Driven Listener.
 * No infinite loops here. It only reacts to system broadcasts and Native Daemon intents.
 */
class AutoTweakService : Service() {

    private var launcherPackage: String = ""
    private var lastTopApp = ""
    private var lastGlobalMode = ""
    private var lastConfigHash = ""
    
    private val CHANNEL_STATUS_ID = "phone_control_status"
    private val CHANNEL_BOOT_ID = "phone_control_boot"
    private val CHANNEL_ALERTS_ID = "phone_control_alerts"
    
    private val GUARD_NOTIF_ID = 100
    private val BOOT_NOTIF_ID = 101
    private val COOLDOWN_NOTIF_ID = 200

    private var cooldownRemaining = 0
    private val cooldownHandler = Handler(Looper.getMainLooper())
    private val cooldownRunnable = object : Runnable {
        override fun run() {
            if (cooldownRemaining > 0) {
                updateCooldownNotification(cooldownRemaining)
                cooldownRemaining--
                cooldownHandler.postDelayed(this, 1000)
            } else {
                removeCooldownNotification()
            }
        }
    }

    // Receives events from the Native Daemon or System
    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            
            when (intent.action) {
                "com.example.phonecontrol.ACTION_STATE_CHANGED" -> {
                    val event = intent.getStringExtra("event")
                    when (event) {
                        "app_change" -> {
                            val pkg = intent.getStringExtra("pkg") ?: ""
                            onForegroundAppChanged(pkg)
                        }
                        "screen_off" -> onScreenOff()
                        "screen_on" -> onScreenOn()
                    }
                }
                "com.example.phonecontrol.ACTION_COOLDOWN_START" -> {
                    startCooldownTimer()
                }
                "com.example.phonecontrol.ACTION_COOLDOWN_END" -> {
                    stopCooldownTimer()
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    handleBatteryLogic(prefs)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        launcherPackage = getLauncherPackageName()
        
        val filter = IntentFilter().apply {
            addAction("com.example.phonecontrol.ACTION_STATE_CHANGED")
            addAction("com.example.phonecontrol.ACTION_COOLDOWN_START")
            addAction("com.example.phonecontrol.ACTION_COOLDOWN_END")
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(eventReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(eventReceiver, filter)
        }
        createNotificationChannels()
        
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        if (prefs.getBoolean("silent_system_enabled", false)) {
            TweakManager.setSilentSystem(true)
        }
        
        showGuardNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isDelayed = intent?.getBooleanExtra("delayed_start", false) ?: false
        
        if (isDelayed) {
            // 60 Seconds Delay after Boot to let system settle
            Handler(Looper.getMainLooper()).postDelayed({
                DaemonManager.startDaemon(this)
                showBootNotification()
            }, 60000)
        } else {
            // Instant start if app is opened manually
            DaemonManager.startDaemon(this)
        }
        
        return START_STICKY
    }

    private fun startCooldownTimer() {
        cooldownRemaining = 120 // 2 Minutes
        cooldownHandler.removeCallbacks(cooldownRunnable)
        cooldownHandler.post(cooldownRunnable)
    }

    private fun stopCooldownTimer() {
        cooldownRemaining = 0
        cooldownHandler.removeCallbacks(cooldownRunnable)
        removeCooldownNotification()
        
        // Re-apply tweaks after cooldown
        val topApp = lastTopApp
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        applyTweakProfile(topApp, prefs)
    }

    private fun updateCooldownNotification(seconds: Int) {
        val minutes = seconds / 60
        val remainingSecs = seconds % 60
        val timeStr = String.format(Locale.US, "%02d:%02d", minutes, remainingSecs)

        val builder = NotificationCompat.Builder(this, CHANNEL_ALERTS_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Emergency Cooldown Active")
            .setContentText("System cooling down... Reverting in $timeStr")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFFFF1744.toInt()) 

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(COOLDOWN_NOTIF_ID, builder.build())
    }

    private fun removeCooldownNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(COOLDOWN_NOTIF_ID)
    }

    private fun showBootNotification() {
        val builder = NotificationCompat.Builder(this, CHANNEL_BOOT_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Phone Control")
            .setContentText("Optimization Activated on Boot")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(BOOT_NOTIF_ID, builder.build())
    }

    private fun showGuardNotification() {
        val builder = NotificationCompat.Builder(this, CHANNEL_STATUS_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Phone Control")
            .setContentText("System Guard Active (Native)")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
        
        // This makes it a proper foreground service notification
        startForeground(GUARD_NOTIF_ID, builder.build())
    }

    private fun onForegroundAppChanged(topApp: String) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        
        val frozenApps = FreezerManager.getFrozenApps(this)
        if (frozenApps.isNotEmpty()) {
            val lastLaunched = FreezerManager.lastLaunchedPackage
            val isGracePeriod = (System.currentTimeMillis() - FreezerManager.lastLaunchTime) < 10000

            if (frozenApps.contains(topApp) || (topApp == lastLaunched && isGracePeriod)) {
                FreezerManager.unfreezeApp(topApp)
            }
        }

        applyTweakProfile(topApp, prefs)
    }

    private fun onScreenOff() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        if (prefs.getBoolean("sensor_firewall_enabled", false)) {
            SensorManager.setSensorsEnabled(false)
        }
        
        val level = getBatteryLevel()
        val isLow = prefs.getBoolean("batt_low_trigger_enabled", false) && level <= prefs.getInt("batt_low_trigger_value", 20)
        
        if (isLow || prefs.getBoolean("batt_power_save_screen_off", false)) TweakManager.applyBatterySaver()
        if (isLow || prefs.getBoolean("batt_data_saver_screen_off", false)) ShellUtils.fastCmd("settings put global data_saver_mode 1")
        if (isLow || prefs.getBoolean("batt_force_doze_enabled", false)) BatteryManager.setForceDoze(true)
    }

    private fun onScreenOn() {
        SensorManager.setSensorsEnabled(true)
        BatteryManager.setForceDoze(false)
        ShellUtils.fastCmd("settings put global data_saver_mode 0")
        lastTopApp = ""
    }

    private fun handleBatteryLogic(prefs: android.content.SharedPreferences) {
        val bm = getSystemService(BATTERY_SERVICE) as AndroidBatteryManager
        val level = bm.getIntProperty(AndroidBatteryManager.BATTERY_PROPERTY_CAPACITY)
        
        if (prefs.getBoolean("batt_limit_enabled", false)) {
            val limit = prefs.getInt("batt_limit_value", 80)
            if (level >= limit) BatteryManager.setChargingEnabled(false)
            else if (level < (limit - 5)) BatteryManager.setChargingEnabled(true)
        }

        val temp = ThermalManager.getTemperature()
        if (!ThermalManager.isCooldownActive) {
            val autoCooldownEnabled = prefs.getBoolean("auto_cooldown_enabled", false)
            if (autoCooldownEnabled && temp >= prefs.getInt("auto_cooldown_threshold", 50)) {
                ThermalManager.startEmergencyCooldown(this) {}
            }
        }
        
        checkDailyOptimization(prefs)
    }

    private fun applyTweakProfile(topApp: String, prefs: android.content.SharedPreferences) {
        if (ThermalManager.isCooldownActive) return 

        val config = PerAppManager.getConfig(this, topApp)
        var gMode = prefs.getString("selected_mode", "rbBalance") ?: "rbBalance"
        var gFps = prefs.getString("selected_global_fps", "rbGlobalFpsAuto") ?: "rbGlobalFpsAuto"

        val level = getBatteryLevel()
        if (prefs.getBoolean("batt_low_trigger_enabled", false) && level <= prefs.getInt("batt_low_trigger_value", 20)) {
            gMode = "rbPowerSaver"; gFps = "rbGlobalFps30"
        }

        val configHash = "${config?.mode}|${config?.fps}|${config?.thermal}|${config?.touch}"
        if (topApp != lastTopApp || gMode != lastGlobalMode || configHash != lastConfigHash) {
            
            if (config != null && config.mode != "Auto") {
                when (config.mode) {
                    "Power Saver" -> TweakManager.applyBatterySaver()
                    "Balance" -> TweakManager.applyBalance()
                    "Performance" -> TweakManager.applyPerformance()
                }
            } else if (gMode == "rbAutomatic") {
                TweakManager.applyBalance()
            } else {
                when (gMode) {
                    "rbPowerSaver" -> TweakManager.applyBatterySaver()
                    "rbBalance" -> TweakManager.applyBalance()
                    "rbPerformance" -> TweakManager.applyPerformance()
                }
            }

            if (config != null) {
                TweakManager.setRefreshRate(config.fps)
                ThermalManager.setThrottlingEnabled(config.thermal != "Disabled")
                TweakManager.setTouchBoost(config.touch == "On")
            } else {
                TweakManager.setRefreshRate(when (gFps) {
                    "rbGlobalFps30" -> "30Hz"; "rbGlobalFps60" -> "60Hz"
                    "rbGlobalFps90" -> "90Hz"; "rbGlobalFps120" -> "120Hz"
                    else -> "Auto Switch"
                })
                TweakManager.setTouchBoost(false)
            }

            lastTopApp = topApp; lastGlobalMode = gMode; lastConfigHash = configHash
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as AndroidBatteryManager
        return bm.getIntProperty(AndroidBatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun getLauncherPackageName(): String {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, 0)
        return resolveInfo?.activityInfo?.packageName ?: "com.nothing.launcher"
    }

    private fun checkDailyOptimization(prefs: android.content.SharedPreferences) {
        if (!prefs.getBoolean("daily_deep_opt_enabled", false)) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour == 3) {
            val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            if (prefs.getString("last_auto_opt_date", "") != today) {
                DeepOptManager.runFullOptimization(this)
                prefs.edit().putString("last_auto_opt_date", today).apply()
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            
            // 1. System Guard Channel (Ongoing)
            val statusChannel = NotificationChannel(CHANNEL_STATUS_ID, "System Guard", NotificationManager.IMPORTANCE_LOW)
            statusChannel.description = "Ongoing system optimization status"
            manager.createNotificationChannel(statusChannel)
            
            // 2. Boot Activation Channel (One-time)
            val bootChannel = NotificationChannel(CHANNEL_BOOT_ID, "Boot Activation", NotificationManager.IMPORTANCE_DEFAULT)
            bootChannel.description = "Notifications when optimization starts after reboot"
            manager.createNotificationChannel(bootChannel)
            
            // 3. Emergency Alerts Channel (Cooldown)
            val alertsChannel = NotificationChannel(CHANNEL_ALERTS_ID, "Emergency Alerts", NotificationManager.IMPORTANCE_HIGH)
            alertsChannel.description = "Critical alerts like thermal cooldown timer"
            manager.createNotificationChannel(alertsChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { 
        unregisterReceiver(eventReceiver)
        ShellUtils.closePersistentShell()
        super.onDestroy() 
    }
}
