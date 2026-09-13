package com.example.phonecontrol

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.concurrent.thread

/**
 * Floating Real-Time Performance HUD:
 * Draggable overlay displaying active Per-App / System Mode, live Little & Big core MHz,
 * and device temperature directly on top of games, videos, and recent apps.
 */
class FloatingHudService : Service() {

    companion object {
        private const val TAG = "FloatingHudService"
        private const val NOTIFICATION_ID = 8821
        private const val CHANNEL_ID = "phone_control_hud_channel"
        @Volatile var isRunning: Boolean = false

        fun start(context: Context) {
            val intent = Intent(context, FloatingHudService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingHudService::class.java))
        }

        fun toggle(context: Context) {
            if (isRunning) stop(context) else start(context)
        }
    }

    private var windowManager: WindowManager? = null
    private var hudView: View? = null
    private var isLoopRunning = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Ensure overlay permission via Root if not yet granted
        if (!Settings.canDrawOverlays(this)) {
            ShellUtils.fastCmd("appops set $packageName SYSTEM_ALERT_WINDOW allow 2>/dev/null")
            ShellUtils.fastCmd("pm grant $packageName android.permission.SYSTEM_ALERT_WINDOW 2>/dev/null")
        }

        initHudView()
        startMetricsLoop()
    }

    private fun initHudView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.layout_floating_hud, null)
        hudView = view

        val prefs = getSharedPreferences("hud_prefs", Context.MODE_PRIVATE)
        val initialX = prefs.getInt("hud_x", 60)
        val initialY = prefs.getInt("hud_y", 180)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        // Close button action
        view.findViewById<TextView>(R.id.btnHudClose).setOnClickListener {
            stopSelf()
        }

        // Touch listener for smooth dragging across screen
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var initialParamX = 0
            private var initialParamY = 0
            private var isMoving = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        initialParamX = params.x
                        initialParamY = params.y
                        isMoving = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                            isMoving = true
                        }
                        params.x = initialParamX + dx
                        params.y = initialParamY + dy
                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (e: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        prefs.edit().putInt("hud_x", params.x).putInt("hud_y", params.y).apply()
                        if (!isMoving) {
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add HUD view: ${e.message}")
        }
    }

    private fun startMetricsLoop() {
        isLoopRunning = true
        thread(name = "FloatingHud-Metrics") {
            val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

            while (isLoopRunning) {
                // 1. Read Little & Big Core live frequencies directly from sysfs
                val littleFreqKHz = readSysfsInt("/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq")
                    .takeIf { it > 0 } ?: readSysfsInt("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
                val bigFreqKHz = readSysfsInt("/sys/devices/system/cpu/cpufreq/policy6/scaling_cur_freq")
                    .takeIf { it > 0 } ?: readSysfsInt("/sys/devices/system/cpu/cpu6/cpufreq/scaling_cur_freq")

                // 2. Read Active Mode & Per-App status
                val perAppMode = prefs.getString("active_per_app_mode", null)
                val perAppPkg = prefs.getString("active_per_app_pkg", null)
                val currentMode = TweakManager.currentMode

                val modeLabel = if (perAppMode != null) {
                    val appLabel = try {
                        if (!perAppPkg.isNullOrBlank()) {
                            packageManager.getApplicationLabel(packageManager.getApplicationInfo(perAppPkg, 0)).toString()
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                    if (appLabel != null) "$perAppMode ($appLabel)" else perAppMode
                } else {
                    currentMode
                }

                // 3. Read Battery Temp reliably via BatteryManager
                val batteryStatus = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val rawTemp = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                val tempC = if (rawTemp > 0) rawTemp / 10f else 0f

                // Update UI on main thread
                mainHandler.post {
                    hudView?.let { root ->
                        root.findViewById<TextView>(R.id.tvHudMode).text = "Mode: $modeLabel"
                        root.findViewById<TextView>(R.id.tvHudLittleFreq).text = "L: ${littleFreqKHz / 1000} MHz"
                        root.findViewById<TextView>(R.id.tvHudBigFreq).text = "B: ${bigFreqKHz / 1000} MHz"
                        root.findViewById<TextView>(R.id.tvHudTemp).text = String.format("%.1f°C", tempC)
                    }
                }

                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun readSysfsInt(path: String): Int {
        return try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                file.readText().trim().toIntOrNull() ?: 0
            } else 0
        } catch (e: Exception) {
            0
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Performance HUD",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active Floating Performance HUD Overlay"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingHudService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Control HUD Active")
            .setContentText("Displaying live CPU frequencies & active profile overlay")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .addAction(R.drawable.ic_launcher_foreground, "Close HUD", stopIntent)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isLoopRunning = false
        isRunning = false
        HudTileService.updateTile(this)
        try {
            hudView?.let { windowManager?.removeView(it) }
            hudView = null
        } catch (e: Exception) {
            Log.w(TAG, "Error removing HUD view: ${e.message}")
        }
    }
}
