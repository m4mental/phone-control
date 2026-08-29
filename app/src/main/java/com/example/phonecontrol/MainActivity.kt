package com.example.phonecontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvRootStatus: TextView
    private lateinit var tvKernelStatus: TextView
    
    private lateinit var tvLiveTemp: TextView
    private lateinit var tvLiveWatts: TextView
    private lateinit var tvLiveRam: TextView
    private lateinit var tvLiveGpu: TextView
    private lateinit var tvLiveCpuCap: TextView
    private lateinit var tvLiveCpuUsage: TextView

    private val uiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateDisplayStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvRootStatus = findViewById(R.id.tvRootStatus)
        tvKernelStatus = findViewById(R.id.tvKernelStatus)
        
        // Dynamic UI Update Receiver
        val uiFilter = IntentFilter("com.example.phonecontrol.UPDATE_UI")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiReceiver, uiFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(uiReceiver, uiFilter)
        }
        
        tvLiveTemp = findViewById(R.id.tvLiveTemp)
        tvLiveWatts = findViewById(R.id.tvLiveWatts)
        tvLiveRam = findViewById(R.id.tvLiveRam)
        tvLiveGpu = findViewById(R.id.tvLiveGpu)
        tvLiveCpuCap = findViewById(R.id.tvLiveCpuCap)
        tvLiveCpuUsage = findViewById(R.id.tvLiveCpuUsage)

        // Navigation Hub
        findViewById<View>(R.id.cardGameTurbo).setOnClickListener { 
            startActivity(Intent(this, GameTurboActivity::class.java))
        }
        findViewById<View>(R.id.cardSuperDoze).setOnClickListener {
            startActivity(Intent(this, SuperDozeActivity::class.java))
        }
        findViewById<View>(R.id.cardModeControl).setOnClickListener { startActivity(Intent(this, ModeControlActivity::class.java)) }
        findViewById<View>(R.id.cardPerApp).setOnClickListener { startActivity(Intent(this, PerAppActivity::class.java)) }
        findViewById<View>(R.id.cardFreezer).setOnClickListener { startActivity(Intent(this, FreezerActivity::class.java)) }
        findViewById<View>(R.id.cardBattery).setOnClickListener { startActivity(Intent(this, BatteryActivity::class.java)) }
        findViewById<View>(R.id.cardVault).setOnClickListener { startActivity(Intent(this, VaultActivity::class.java)) }
        findViewById<View>(R.id.cardTowerLock).setOnClickListener { startActivity(Intent(this, HomeTowerLockActivity::class.java)) }
        findViewById<View>(R.id.cardThrottling).setOnClickListener { startActivity(Intent(this, ThrottlingActivity::class.java)) }
        
        // Advanced Section
        findViewById<View>(R.id.cardResolution).setOnClickListener { startActivity(Intent(this, ResolutionActivity::class.java)) }
        findViewById<View>(R.id.cardBloatware).setOnClickListener { startActivity(Intent(this, BloatwareActivity::class.java)) }
        findViewById<View>(R.id.cardRam).setOnClickListener { startActivity(Intent(this, RamActivity::class.java)) }
        findViewById<View>(R.id.cardNetwork).setOnClickListener { startActivity(Intent(this, NetworkActivity::class.java)) }
        findViewById<View>(R.id.cardOptimization).setOnClickListener { startActivity(Intent(this, OptimizationActivity::class.java)) }
        findViewById<View>(R.id.cardStorage).setOnClickListener { startActivity(Intent(this, StorageActivity::class.java)) }
        findViewById<View>(R.id.cardAdb).setOnClickListener { startActivity(Intent(this, AdbShellActivity::class.java)) }

        // Manual Stats Refresh
        findViewById<View>(R.id.cardLiveDashboard).setOnClickListener {
            updateLiveStats()
            android.widget.Toast.makeText(this, "Stats Refreshed", android.widget.Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        requestNotificationPermission()
        
        // Root check on background thread with safety
        checkRootAsync()
    }

    override fun onDestroy() {
        try { unregisterReceiver(uiReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun checkRootAsync() {
        kotlin.concurrent.thread {
            try {
                val isRooted = ShellUtils.checkRootStandalone(3000)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (isRooted) {
                        tvRootStatus.text = "Root: Granted"; tvRootStatus.setTextColor(Color.GREEN)
                        tvKernelStatus.text = "Kernel Engine: Active (BBR+EAS)"; tvKernelStatus.setTextColor(Color.GREEN)
                    } else {
                        tvRootStatus.text = "Root: Denied"; tvRootStatus.setTextColor(Color.RED)
                        tvKernelStatus.text = "Kernel Engine: Restricted"; tvKernelStatus.setTextColor(Color.GRAY)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { 
                    if (!isFinishing) tvRootStatus.text = "Root: Error" 
                }
            }
        }
    }

    private val liveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val liveRunnable = object : Runnable {
        override fun run() {
            updateDisplayStatus()
            updateLiveStats()
            liveHandler.postDelayed(this, 2500)
        }
    }

    override fun onResume() {
        super.onResume()
        updateDisplayStatus()
        updateLiveStats()
        updateCardVisibility()
        liveHandler.postDelayed(liveRunnable, 2500)
    }
    
    override fun onPause() {
        liveHandler.removeCallbacks(liveRunnable)
        super.onPause()
    }

    private var lastTotalCpuTime = 0L
    private var lastIdleCpuTime = 0L

    private fun readKernelCpuUsage(): Int {
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

                    val totalDelta = total - lastTotalCpuTime
                    val activeDelta = active - (lastTotalCpuTime - lastIdleCpuTime)

                    lastTotalCpuTime = total
                    lastIdleCpuTime = idle + iowait

                    if (totalDelta > 0 && activeDelta >= 0) {
                        (activeDelta * 100 / totalDelta).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                } else 0
            } else 0
        } catch (e: Exception) {
            0
        }
    }

    private fun readKernelMemAvailableGb(): String {
        return try {
            var freeKb = 0L
            java.io.File("/proc/meminfo").forEachLine { line ->
                if (line.startsWith("MemAvailable:")) {
                    freeKb = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
                    return@forEachLine
                }
            }
            String.format("%.1fGB", freeKb / 1024.0 / 1024.0)
        } catch (e: Exception) {
            "--GB"
        }
    }

    private fun updateLiveStats() {
        kotlin.concurrent.thread {
            val batteryInfo = BatteryManager.getBatteryStats()
            val freeGb = readKernelMemAvailableGb()
            val cpuUsage = readKernelCpuUsage()

            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                tvLiveTemp.text = batteryInfo.temp
                tvLiveWatts.text = batteryInfo.wattage
                tvLiveRam.text = freeGb
                
                // Fetch Active Kernel Mode from engine state
                val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
                val activeMode = prefs.getString("active_kernel_mode", "Balance")
                tvLiveGpu.text = when(activeMode) {
                    "Performance" -> "Gaming"
                    "Power Saver" -> "PowerSave"
                    else -> "Balanced"
                }

                val activeCap = prefs.getInt("active_cpu_cap", 100)
                tvLiveCpuCap.text = if (activeCap < 100) "${activeCap}%" else "Uncapped"
                tvLiveCpuCap.setTextColor(if (activeCap < 80) Color.RED else if (activeCap < 100) Color.YELLOW else Color.GREEN)

                tvLiveCpuUsage.text = "$cpuUsage%"
                tvLiveCpuUsage.setTextColor(if (cpuUsage > 80) Color.RED else if (cpuUsage > 50) Color.YELLOW else Color.WHITE)
            }
        }
    }

    private fun updateDisplayStatus() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val savedMode = prefs.getString("selected_mode", "rbBalance")
        val activeAiLabel = prefs.getString("active_ai_label", "AI: Active")

        val statusText = when (savedMode) {
            "rbPowerSaver" -> "Manual: Power Saver"
            "rbBalance" -> "Manual: Balanced"
            "rbPerformance" -> "Manual: Performance"
            "rbAutomatic" -> activeAiLabel ?: "Automatic (AI)"
            else -> "Unknown"
        }
        tvStatus.text = "Mode: $statusText"
        
        // Dynamic Status Color
        if (savedMode == "rbAutomatic") {
            tvStatus.setTextColor(Color.parseColor("#00C853")) // Bright Green for AI
        } else {
            tvStatus.setTextColor(Color.WHITE)
        }
        
        // Ensure service is running
        startService(Intent(this, AutoTweakService::class.java))
    }

    private fun updateCardVisibility() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        
        // Advanced Features Mapping (Controlled by Master Settings Categories)
        findViewById<View>(R.id.cardThrottling).visibility = if (prefs.getBoolean("adaptive_thermal_enabled", false)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardGameTurbo).visibility = if (prefs.getBoolean("game_turbo_enabled", false)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardSuperDoze).visibility = if (prefs.getBoolean("super_doze_enabled", false)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardNetwork).visibility = if (prefs.getBoolean("network_priority_enabled", false)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardStorage).visibility = if (prefs.getBoolean("storage_boost_enabled", false)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardOptimization).visibility = if (prefs.getBoolean("optimization_enabled", false)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardResolution).visibility = if (prefs.getBoolean("resolution_enabled", false)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardRam).visibility = if (prefs.getBoolean("ram_manager_enabled", false)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardVault).visibility = if (prefs.getBoolean("vault_enabled", false)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardTowerLock).visibility = if (prefs.getBoolean("tower_lock_enabled", false)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardBloatware).visibility = if (prefs.getBoolean("bloatware_enabled", false)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardAdb).visibility = if (prefs.getBoolean("adb_enabled", false)) View.VISIBLE else View.GONE
    }


    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
}
