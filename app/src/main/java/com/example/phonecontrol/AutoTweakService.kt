package com.example.phonecontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager as AndroidBatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*

class AutoTweakService : Service() {

    private var timer: Timer? = null
    private var launcherPackage: String = ""
    
    private var lastTopApp = ""
    private var lastGlobalMode = ""
    private var lastFocus = ""
    private var lastGlobalFps = ""
    private var lastConfigHash = ""
    private var hasShownActivation = false
    private var isScreenOff = false
    
    private val CHANNEL_ID = "phone_control_activation"

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                isScreenOff = true
                restartMonitoring(10000) // Slow down to 10s when screen is OFF
                
                val isLow = isLowBattery(prefs)
                if (isLow || prefs.getBoolean("batt_power_save_screen_off", false)) TweakManager.applyBatterySaver()
                if (isLow || prefs.getBoolean("batt_data_saver_screen_off", false)) ShellUtils.fastCmd("settings put global data_saver_mode 1")
                
                // --- FIX 2: Only Force Doze when screen is OFF ---
                if (isLow || prefs.getBoolean("batt_force_doze_enabled", false)) {
                    BatteryManager.setForceDoze(true)
                }
            } else if (intent.action == Intent.ACTION_SCREEN_ON) {
                isScreenOff = false
                restartMonitoring(2000) // Speed up to 2s when screen is ON
                
                // Disable aggressive doze immediately on wake
                BatteryManager.setForceDoze(false)
                ShellUtils.fastCmd("settings put global data_saver_mode 0")
                lastTopApp = "" 
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        launcherPackage = getLauncherPackageName()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isDelayed = intent?.getBooleanExtra("delayed_start", false) ?: false
        startMonitoring(if (isDelayed) 60000L else 0L, 2000)
        return START_STICKY
    }

    private fun restartMonitoring(interval: Long) {
        timer?.cancel()
        startMonitoring(0, interval)
    }

    private fun startMonitoring(delay: Long, interval: Long) {
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                // Boot activation notification
                if (delay > 0 && !hasShownActivation && System.currentTimeMillis() > 0) {
                    showActivationNotification(); hasShownActivation = true
                }

                val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
                val topApp = getForegroundApp()
                
                // --- FIX 4: Only unfreeze if app is in foreground or recently used (max 3) ---
                val frozenApps = FreezerManager.getFrozenApps(this@AutoTweakService)
                if (frozenApps.isNotEmpty()) {
                    val recentApps = getRecentPackages().take(3) // Only keep last 3 apps unfrozen
                    val lastLaunched = FreezerManager.lastLaunchedPackage
                    val lastLaunchTime = FreezerManager.lastLaunchTime
                    val isGracePeriod = (System.currentTimeMillis() - lastLaunchTime) < 10000

                    for (pkg in frozenApps) {
                        // Don't freeze if in foreground, recent apps, OR in 10s grace period after launch
                        if (pkg == topApp || recentApps.contains(pkg) || (pkg == lastLaunched && isGracePeriod)) {
                            FreezerManager.unfreezeApp(pkg)
                        } else {
                            FreezerManager.freezeApp(pkg)
                        }
                    }
                }

                // Battery Logic
                handleBatteryLimit(prefs)
                
                // --- Re-apply Force Doze every tick IF screen is off ---
                if (isScreenOff && (isLowBattery(prefs) || prefs.getBoolean("batt_force_doze_enabled", false))) {
                    BatteryManager.setForceDoze(true)
                }

                // Tweak Logic (Optimized)
                val config = PerAppManager.getConfig(this@AutoTweakService, topApp)
                var gMode = prefs.getString("selected_mode", "rbBalance") ?: "rbBalance"
                var gFps = prefs.getString("selected_global_fps", "rbGlobalFpsAuto") ?: "rbGlobalFpsAuto"
                var gFocus = prefs.getString("selected_focus", "rbFocusDaily") ?: "rbFocusDaily"
                
                if (isLowBattery(prefs)) {
                    gMode = "rbPowerSaver"; gFps = "rbGlobalFps30"; gFocus = "rbFocusBattery"
                }
                
                val configHash = "${config?.mode}|${config?.fps}|${config?.thermal}|${config?.touch}"
                if (topApp != lastTopApp || gMode != lastGlobalMode || gFocus != lastFocus || gFps != lastGlobalFps || configHash != lastConfigHash) {
                    applyTweaks(config, gMode, gFocus, gFps)
                    lastTopApp = topApp; lastGlobalMode = gMode; lastFocus = gFocus; lastGlobalFps = gFps; lastConfigHash = configHash
                }

                if (!ThermalManager.isCooldownActive) handleThermalSafety(prefs, ThermalManager.getTemperature())
                if (System.currentTimeMillis() % 120000 < 2000) checkDailyOptimization(prefs)
            }
        }, delay, interval)
    }

    private fun isLowBattery(prefs: android.content.SharedPreferences): Boolean {
        val bm = getSystemService(BATTERY_SERVICE) as AndroidBatteryManager
        return prefs.getBoolean("batt_low_trigger_enabled", false) && 
               bm.getIntProperty(AndroidBatteryManager.BATTERY_PROPERTY_CAPACITY) <= prefs.getInt("batt_low_trigger_value", 20)
    }

    private fun handleBatteryLimit(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean("batt_limit_enabled", false)) {
            val bm = getSystemService(BATTERY_SERVICE) as AndroidBatteryManager
            val level = bm.getIntProperty(AndroidBatteryManager.BATTERY_PROPERTY_CAPACITY)
            val limit = prefs.getInt("batt_limit_value", 80)
            if (level >= limit) BatteryManager.setChargingEnabled(false)
            else if (level < (limit - 5)) BatteryManager.setChargingEnabled(true)
        }
    }

    private fun handleThermalSafety(prefs: android.content.SharedPreferences, currentTemp: Int) {
        val autoCooldownEnabled = prefs.getBoolean("auto_cooldown_enabled", false)
        if (autoCooldownEnabled && currentTemp >= prefs.getInt("auto_cooldown_threshold", 50)) {
            ThermalManager.startEmergencyCooldown(this@AutoTweakService) {}
        } else {
            val isThrottlingDisabled = prefs.getBoolean("disable_throttling", false)
            if (isThrottlingDisabled && currentTemp >= prefs.getInt("temp_fuse", 45)) {
                ThermalManager.setThrottlingEnabled(true)
                prefs.edit().putBoolean("disable_throttling", false).apply()
            }
        }
    }

    private fun applyTweaks(config: PerAppManager.AppConfig?, gMode: String, gFocus: String, gFps: String) {
        if (ThermalManager.isCooldownActive) return
        if (config != null && config.mode != "Auto") applyMode(config.mode)
        else if (gMode == "rbAutomatic") applyAi(getCpuUsage(), gFocus)
        else applyManual(gMode)

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
            val isThrottlingDisabled = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("disable_throttling", false)
            ThermalManager.setThrottlingEnabled(!isThrottlingDisabled)
        }
    }

    private fun getForegroundApp(): String {
        val result = ShellUtils.runAsRoot("dumpsys activity activities | grep mResumedActivity")
        return try {
            val output = result.output
            if (output.contains(" ")) {
                val parts = output.split(" ")
                val pkgPart = parts.find { it.contains("/") }
                pkgPart?.split("/")?.get(0) ?: ""
            } else ""
        } catch (e: Exception) { "" }
    }

    private fun getLauncherPackageName(): String {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName ?: "com.nothing.launcher"
    }

    private fun getCpuUsage(): Int {
        val result = ShellUtils.runAsRoot("top -n 1 -b | head -n 20 | grep 'CPU' | awk '{print $2}' | head -n 1")
        return try { result.output.replace("%", "").split(".")[0].toInt() } catch (e: Exception) { 50 }
    }

    private fun getRecentPackages(): List<String> {
        val result = ShellUtils.runAsRoot("dumpsys activity recents | grep 'cmp=' | head -n 8 | cut -d '=' -f3 | cut -d '/' -f1")
        return result.output.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun applyMode(mode: String) {
        when (mode) {
            "Power Saver" -> TweakManager.applyBatterySaver()
            "Balance" -> TweakManager.applyBalance()
            "Performance" -> TweakManager.applyPerformance()
        }
    }

    private fun applyManual(key: String) {
        when (key) {
            "rbPowerSaver" -> TweakManager.applyBatterySaver()
            "rbBalance" -> TweakManager.applyBalance()
            "rbPerformance" -> TweakManager.applyPerformance()
        }
    }

    private fun applyAi(usage: Int, focus: String) {
        if (focus == "rbFocusBattery") {
            if (usage > 80) TweakManager.applyBalance() else TweakManager.applyBatterySaver()
        } else {
            if (usage > 60) TweakManager.applyPerformance()
            else if (usage < 15) TweakManager.applyBatterySaver()
            else TweakManager.applyBalance()
        }
    }

    private fun checkDailyOptimization(prefs: android.content.SharedPreferences) {
        if (!prefs.getBoolean("daily_deep_opt_enabled", false)) return
        if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) == 3) {
            val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            if (prefs.getString("last_auto_opt_date", "") != today) {
                DeepOptManager.runFullOptimization(this)
                prefs.edit().putString("last_auto_opt_date", today).apply()
            }
        }
    }

    private fun showActivationNotification() {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Phone Control").setContentText("System Optimization Activated").setPriority(NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(100, builder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Activation Status", NotificationManager.IMPORTANCE_DEFAULT)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { unregisterReceiver(screenReceiver); timer?.cancel(); ShellUtils.closePersistentShell(); super.onDestroy() }
}
