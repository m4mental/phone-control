package com.example.phonecontrol

import android.content.Intent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvRootStatus = findViewById(R.id.tvRootStatus)
        tvKernelStatus = findViewById(R.id.tvKernelStatus)
        
        tvLiveTemp = findViewById(R.id.tvLiveTemp)
        tvLiveWatts = findViewById(R.id.tvLiveWatts)
        tvLiveRam = findViewById(R.id.tvLiveRam)
        tvLiveGpu = findViewById(R.id.tvLiveGpu)
        tvLiveCpuCap = findViewById(R.id.tvLiveCpuCap)
        tvLiveCpuUsage = findViewById(R.id.tvLiveCpuUsage)

        // Navigation Hub
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

    private fun checkRootAsync() {
        kotlin.concurrent.thread {
            try {
                val result = ShellUtils.runAsRoot("id")
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (result.exitCode == 0) {
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

    override fun onResume() {
        super.onResume()
        updateDisplayStatus()
        updateLiveStats() // Refresh once on open
        updateCardVisibility()
    }
    
    override fun onPause() {
        super.onPause()
    }

    private fun updateLiveStats() {
        if (ShellUtils.isBusy) return // Skip update if a heavy task is running

        kotlin.concurrent.thread {
            val batteryInfo = BatteryManager.getBatteryStats()
            
            // Get Free RAM
            val result = ShellUtils.runAsRoot("cat /proc/meminfo | grep MemAvailable")
            val ramStr = result.output.filter { it.isDigit() }.toLongOrNull() ?: 0L
            val freeGb = String.format("%.1fGB", ramStr / 1024.0 / 1024.0)

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

                // Get CPU Usage
                val cpuResult = ShellUtils.runAsRoot("top -n 1 -b -m 1 | grep 'CPU' | head -n 1")
                val cpuUsage = parseCpuUsage(cpuResult.output)
                tvLiveCpuUsage.text = "$cpuUsage%"
                tvLiveCpuUsage.setTextColor(if (cpuUsage > 80) Color.RED else if (cpuUsage > 50) Color.YELLOW else Color.WHITE)
            }
        }
    }

    private fun parseCpuUsage(output: String): Int {
        return try {
            // Typical format: "CPU: 5% usr 2% sys..." or similar
            // We'll look for numbers followed by %
            val pattern = "(\\d+)%".toRegex()
            val matches = pattern.findAll(output)
            var total = 0
            for (match in matches) {
                val value = match.groupValues[1].toInt()
                // We sum up everything except 'idle' if possible, or just take the first value if it's "Total"
                // On most Android top: First match is usually User, Second is Sys
                if (total == 0) total = value
                else {
                    total += value
                    break // Just take User + Sys
                }
            }
            if (total > 100) 100 else total
        } catch (e: Exception) {
            0
        }
    }

    private fun updateDisplayStatus() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val savedMode = prefs.getString("selected_mode", "rbBalance")
        val statusText = when (savedMode) {
            "rbPowerSaver" -> "Manual: Power Saver"
            "rbBalance" -> "Manual: Balanced"
            "rbPerformance" -> "Manual: Performance"
            "rbAutomatic" -> "Automatic (AI)"
            else -> "Unknown"
        }
        tvStatus.text = "Mode: $statusText"
        
        // Ensure service is running
        startService(Intent(this, AutoTweakService::class.java))
    }

    private fun updateCardVisibility() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        
        // Advanced Features Mapping (Controlled by Master Settings Categories)
        findViewById<View>(R.id.cardThrottling).visibility = if (prefs.getBoolean("adaptive_thermal_enabled", false)) View.VISIBLE else View.GONE
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
