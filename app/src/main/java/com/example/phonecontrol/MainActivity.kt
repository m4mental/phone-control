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
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var ivStatusIcon: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvRootStatus: TextView
    private lateinit var tvKernelStatus: TextView
    
    private lateinit var tvLiveWatts: TextView
    private lateinit var tvLiveVolt: TextView
    private lateinit var tvLiveCycles: TextView
    private lateinit var tvLiveRam: TextView
    private lateinit var tvLiveCpuCap: TextView
    private lateinit var tvLiveCpuUsage: TextView
    private lateinit var tvActiveStageOverride: TextView
    
    // Nothing OS Live Telemetry Views
    private lateinit var tvLiveLittleFreq: TextView
    private lateinit var pbLittleCluster: ProgressBar
    private lateinit var tvLiveBigFreq: TextView
    private lateinit var pbBigCluster: ProgressBar
    private lateinit var tvLiveSocTemp: TextView
    private lateinit var tvLiveBattTemp: TextView
    private lateinit var tvLiveCurrentMa: TextView

    private val uiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateDisplayStatus()
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            val currentOverride = prefs.getInt("manual_stage_override", 0)
            if (::tvActiveStageOverride.isInitialized) {
                updateStageButtonsUI(currentOverride)
            }
            updateLiveStats()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ivStatusIcon = findViewById(R.id.ivStatusIcon)
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
        
        tvLiveWatts = findViewById(R.id.tvLiveWatts)
        tvLiveVolt = findViewById(R.id.tvLiveVolt)
        tvLiveCycles = findViewById(R.id.tvLiveCycles)
        tvLiveRam = findViewById(R.id.tvLiveRam)
        tvLiveCpuCap = findViewById(R.id.tvLiveCpuCap)
        tvLiveCpuUsage = findViewById(R.id.tvLiveCpuUsage)

        // Telemetry Highlights & Dual-Cluster Bars
        tvLiveLittleFreq = findViewById(R.id.tvLiveLittleFreq)
        pbLittleCluster = findViewById(R.id.pbLittleCluster)
        tvLiveBigFreq = findViewById(R.id.tvLiveBigFreq)
        pbBigCluster = findViewById(R.id.pbBigCluster)
        tvLiveSocTemp = findViewById(R.id.tvLiveSocTemp)
        tvLiveBattTemp = findViewById(R.id.tvLiveBattTemp)
        tvLiveCurrentMa = findViewById(R.id.tvLiveCurrentMa)

        // Master Settings Click Listener (Nothing OS Dot-Matrix Pill)
        findViewById<View>(R.id.btnSettings)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Navigation Hub
        findViewById<View>(R.id.cardGameTurbo).setOnClickListener { 
            startActivity(Intent(this, GameTurboActivity::class.java))
        }
        findViewById<View>(R.id.cardSuperDoze).setOnClickListener {
            startActivity(Intent(this, SuperDozeActivity::class.java))
        }
        
        // Mode Control Click & Long Click Trigger for Test Lab
        val cardModeControl = findViewById<View>(R.id.cardModeControl)
        cardModeControl.setOnClickListener { startActivity(Intent(this, ModeControlActivity::class.java)) }
        cardModeControl.setOnLongClickListener {
            showTestLabUnlockDialog()
            true
        }

        findViewById<View>(R.id.cardPerApp).setOnClickListener { startActivity(Intent(this, PerAppActivity::class.java)) }
        findViewById<View>(R.id.cardFreezer).setOnClickListener { startActivity(Intent(this, FreezerActivity::class.java)) }
        findViewById<View>(R.id.cardStudioEqualizer).setOnClickListener { startActivity(Intent(this, StudioEqualizerActivity::class.java)) }
        findViewById<View>(R.id.cardAppExtractor).setOnClickListener { startActivity(Intent(this, AppExtractorActivity::class.java)) }
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

        // Stage Manual Override (Test Lab)
        val cardStageOverride = findViewById<View>(R.id.cardStageOverride)
        val btnLockTestLab = findViewById<ImageView>(R.id.btnLockTestLab)
        tvActiveStageOverride = findViewById(R.id.tvActiveStageOverride)
        val btnStage1E = findViewById<MaterialButton>(R.id.btnStage1E)
        val btnStage1D = findViewById<MaterialButton>(R.id.btnStage1D)
        val btnStage1C = findViewById<MaterialButton>(R.id.btnStage1C)
        val btnStage1B = findViewById<MaterialButton>(R.id.btnStage1B)
        val btnStage1A = findViewById<MaterialButton>(R.id.btnStage1A)
        val btnStage2 = findViewById<MaterialButton>(R.id.btnStage2)
        val btnStage3 = findViewById<MaterialButton>(R.id.btnStage3)
        val btnStage4 = findViewById<MaterialButton>(R.id.btnStage4)
        val btnStageAuto = findViewById<MaterialButton>(R.id.btnStageAuto)

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val isLabUnlocked = prefs.getBoolean("is_test_lab_unlocked", false)
        cardStageOverride.visibility = if (isLabUnlocked) View.VISIBLE else View.GONE

        btnLockTestLab.setOnClickListener {
            cardStageOverride.visibility = View.GONE
            prefs.edit().putBoolean("is_test_lab_unlocked", false).apply()
            android.widget.Toast.makeText(this, "🔒 Test Lab Locked & Hidden", android.widget.Toast.LENGTH_SHORT).show()
        }

        val currentOverride = prefs.getInt("manual_stage_override", 0)
        TweakManager.manualStageOverride = currentOverride
        updateStageButtonsUI(currentOverride)

        btnStage1E.setOnClickListener {
            TweakManager.forceStageOverride(this, 13)
            updateStageButtonsUI(13)
            updateLiveStats()
            android.widget.Toast.makeText(this, "🟢 Stage 1 (Option E): 480MHz Hardware Minimum Floor", android.widget.Toast.LENGTH_SHORT).show()
        }
        btnStage1D.setOnClickListener {
            TweakManager.forceStageOverride(this, 12)
            updateStageButtonsUI(12)
            updateLiveStats()
            android.widget.Toast.makeText(this, "🟢 Stage 1 (Option D): 550MHz Deep Eco, Big Cores 400MHz Sleeping", android.widget.Toast.LENGTH_SHORT).show()
        }
        btnStage1C.setOnClickListener {
            TweakManager.forceStageOverride(this, 11)
            updateStageButtonsUI(11)
            updateLiveStats()
            android.widget.Toast.makeText(this, "🟢 Stage 1 (Option C): 650MHz Ultra Eco, Big Cores 400MHz Sleeping", android.widget.Toast.LENGTH_SHORT).show()
        }
        btnStage1B.setOnClickListener {
            TweakManager.forceStageOverride(this, 10)
            updateStageButtonsUI(10)
            updateLiveStats()
            android.widget.Toast.makeText(this, "🟢 Stage 1 (Option B): 850MHz Extreme Eco, Big Cores 400MHz Sleeping", android.widget.Toast.LENGTH_SHORT).show()
        }
        btnStage1A.setOnClickListener {
            TweakManager.forceStageOverride(this, 1)
            updateStageButtonsUI(1)
            updateLiveStats()
            android.widget.Toast.makeText(this, "🟢 Stage 1 (Option A): 950MHz Balanced Eco, Big Cores 400MHz Sleeping", android.widget.Toast.LENGTH_SHORT).show()
        }
        btnStage2.setOnClickListener {
            TweakManager.forceStageOverride(this, 2)
            updateStageButtonsUI(2)
            updateLiveStats()
            android.widget.Toast.makeText(this, "🔵 Force Stage 2: 6-Cores 2.0GHz, Big Cores 400MHz Sleeping", android.widget.Toast.LENGTH_SHORT).show()
        }
        btnStage3.setOnClickListener {
            TweakManager.forceStageOverride(this, 3)
            updateStageButtonsUI(3)
            updateLiveStats()
            android.widget.Toast.makeText(this, "🟡 Force Stage 3: 6-Cores 2.0GHz + Big Cores 1.5GHz", android.widget.Toast.LENGTH_SHORT).show()
        }
        btnStage4.setOnClickListener {
            TweakManager.forceStageOverride(this, 4)
            updateStageButtonsUI(4)
            updateLiveStats()
            android.widget.Toast.makeText(this, "🔴 Force Stage 4: All 8 Cores Full Turbo Unleashed", android.widget.Toast.LENGTH_SHORT).show()
        }
        btnStageAuto.setOnClickListener {
            TweakManager.forceStageOverride(this, 0)
            updateStageButtonsUI(0)
            updateLiveStats()
            android.widget.Toast.makeText(this, "⚪ Restored to Auto (AI Dynamic Ladder)", android.widget.Toast.LENGTH_SHORT).show()
        }

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

        // Periodic 7-Day Auto-Backup Check
        BackupManager.checkAndRunScheduledAutoBackup(this)

        updateDisplayStatus()
    }


    override fun onDestroy() {
        try { unregisterReceiver(uiReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun updateStageButtonsUI(activeStage: Int) {
        if (!::tvActiveStageOverride.isInitialized) return
        tvActiveStageOverride.text = when (activeStage) {
            13 -> "Active: S1 (480M Min)"
            12 -> "Active: S1 (550M)"
            11 -> "Active: S1 (650M)"
            10 -> "Active: S1 (850M)"
            1 -> "Active: S1 (950M)"
            2 -> "Active: Force S2"
            3 -> "Active: Force S3"
            4 -> "Active: Force S4"
            else -> "Active: Auto"
        }
        tvActiveStageOverride.setTextColor(when (activeStage) {
            13, 12, 11, 10, 1 -> Color.parseColor("#00E5FF")
            2 -> Color.parseColor("#69F0AE")
            3 -> Color.parseColor("#FFD700")
            4 -> Color.parseColor("#FF5252")
            else -> Color.parseColor("#69F0AE")
        })
    }

    private fun checkRootAsync() {
        if (ShellUtils.isRootGrantedCached == true) {
            tvRootStatus.text = "ROOT: GRANTED"
            tvRootStatus.setTextColor(Color.WHITE)
            tvKernelStatus.text = "BBR+EAS ACTIVE"
            tvKernelStatus.setTextColor(Color.WHITE)
        }

        kotlin.concurrent.thread {
            try {
                val isRooted = ShellUtils.checkRootStandalone(4000, forceCheck = true)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (isRooted) {
                        tvRootStatus.text = "ROOT: GRANTED"
                        tvRootStatus.setTextColor(Color.WHITE)
                        tvKernelStatus.text = "BBR+EAS ACTIVE"
                        tvKernelStatus.setTextColor(Color.WHITE)
                    } else {
                        tvRootStatus.text = "ROOT: DENIED"
                        tvRootStatus.setTextColor(Color.parseColor("#D71921"))
                        tvKernelStatus.text = "RESTRICTED"
                        tvKernelStatus.setTextColor(Color.parseColor("#888888"))
                    }
                    ModeControlTileService.updateTile(this@MainActivity)
                    CooldownTileService.updateTile(this@MainActivity)
                }
            } catch (e: Exception) {
                runOnUiThread { 
                    if (!isFinishing && !isDestroyed) {
                        tvRootStatus.text = "ROOT: ERROR"
                        tvRootStatus.setTextColor(Color.parseColor("#D71921"))
                    }
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
        checkRootAsync()
        updateDisplayStatus()
        updateLiveStats()
        updateCardVisibility()
        updateEqualizerCardStatus()
        liveHandler.postDelayed(liveRunnable, 2500)
    }

    private fun updateEqualizerCardStatus() {
        val tvBadge = findViewById<TextView>(R.id.tvEqBadgeStatus) ?: return
        val tvSubtitle = findViewById<TextView>(R.id.tvEqCardSubtitle) ?: return
        val isEnabled = PowerampPresetManager.isMasterEnabled(this)
        val activePreset = PowerampPresetManager.getActivePresetName(this)
        if (isEnabled) {
            tvBadge.text = "ACTIVE"
            tvBadge.setTextColor(Color.parseColor("#00E5FF"))
            tvBadge.setBackgroundColor(Color.parseColor("#0D253A"))
            tvSubtitle.text = "Active: $activePreset • Limiter & Preamp Engaged"
            tvSubtitle.setTextColor(Color.parseColor("#80DEEA"))
        } else {
            tvBadge.text = "OFF"
            tvBadge.setTextColor(Color.parseColor("#888888"))
            tvBadge.setBackgroundColor(Color.parseColor("#1F2937"))
            tvSubtitle.text = "Tap to launch 12-band Poweramp DSP Equalizer & DTS Profiles."
            tvSubtitle.setTextColor(Color.parseColor("#D1C4E9"))
        }
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

    private fun readLittleCoreFreqKhz(): Int {
        return try {
            val f = java.io.File("/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq")
            if (f.exists() && f.canRead()) {
                f.readText().trim().toIntOrNull() ?: 0
            } else {
                val res = ShellUtils.fastCmdResult("cat /sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq 2>/dev/null").trim()
                res.toIntOrNull() ?: 0
            }
        } catch (e: Exception) { 0 }
    }

    private fun readBigCoreFreqKhz(): Int {
        return try {
            val f = java.io.File("/sys/devices/system/cpu/cpufreq/policy6/scaling_cur_freq")
            if (f.exists() && f.canRead()) {
                f.readText().trim().toIntOrNull() ?: 0
            } else {
                val res = ShellUtils.fastCmdResult("cat /sys/devices/system/cpu/cpufreq/policy6/scaling_cur_freq 2>/dev/null").trim()
                res.toIntOrNull() ?: 0
            }
        } catch (e: Exception) { 0 }
    }

    private fun readSocTempCelsius(): Int {
        return try {
            val f = java.io.File("/sys/class/thermal/thermal_zone0/temp")
            val raw = if (f.exists() && f.canRead()) f.readText().trim() else ShellUtils.fastCmdResult("cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null").trim()
            val milli = raw.toIntOrNull() ?: 0
            if (milli > 1000) milli / 1000 else milli
        } catch (e: Exception) { 0 }
    }

    private fun readBatteryCurrentMa(): Int {
        return try {
            val f = java.io.File("/sys/class/power_supply/battery/current_now")
            val raw = if (f.exists() && f.canRead()) f.readText().trim() else ShellUtils.fastCmdResult("cat /sys/class/power_supply/battery/current_now 2>/dev/null").trim()
            val uA = raw.toIntOrNull() ?: 0
            // In Android/Linux kernel, current_now in uA: negative or positive depending on charging/discharging
            uA / 1000
        } catch (e: Exception) { 0 }
    }

    private fun updateLiveStats() {
        kotlin.concurrent.thread {
            val batteryInfo = BatteryManager.getBatteryStats()
            val freeGb = readKernelMemAvailableGb()
            val cpuUsage = readKernelCpuUsage()
            val littleFreqKhz = readLittleCoreFreqKhz()
            val bigFreqKhz = readBigCoreFreqKhz()
            val socTemp = readSocTempCelsius()
            val currentMa = readBatteryCurrentMa()

            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            if (prefs.getString("selected_mode", "rbBalance") == "rbAutomatic" && !prefs.contains("active_per_app_mode")) {
                val intentAi = Intent(this@MainActivity, AutoTweakService::class.java).apply {
                    action = "com.example.phonecontrol.ACTION_AI_TICK"
                    putExtra("load", cpuUsage)
                }
                try {
                    startService(intentAi)
                } catch (e: Exception) {}
            }

            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                tvLiveWatts.text = batteryInfo.wattage
                tvLiveVolt.text = batteryInfo.voltage
                tvLiveCycles.text = if (batteryInfo.cycles.isNotBlank() && batteryInfo.cycles != "0") "${batteryInfo.cycles} cyc" else "Good"
                tvLiveRam.text = freeGb

                // --- 1. Dual-Cluster CPU Bars Update ---
                val littleMhz = littleFreqKhz / 1000
                pbLittleCluster.progress = littleMhz.coerceIn(0, 2000)
                tvLiveLittleFreq.text = if (littleMhz > 0) {
                    if (littleMhz <= 950) "$littleMhz MHz (FLOOR)" else "$littleMhz MHz"
                } else "0000 MHz"
                tvLiveLittleFreq.setTextColor(if (littleMhz > 1800) Color.parseColor("#D71921") else Color.WHITE)

                val bigMhz = bigFreqKhz / 1000
                pbBigCluster.progress = bigMhz.coerceIn(0, 2800)
                tvLiveBigFreq.text = if (bigMhz > 0) {
                    val ghz = String.format(java.util.Locale.US, "%.2f GHz", bigFreqKhz / 1000000.0)
                    if (bigMhz >= 2600) "$ghz (TURBO)" else if (bigMhz <= 1200) "$ghz (BASE)" else ghz
                } else "OFFLINE"
                tvLiveBigFreq.setTextColor(if (bigMhz >= 2400) Color.parseColor("#D71921") else Color.WHITE)

                // --- 2. Dual Temperature Gauges (Nothing Monochrome + Red Overheat) ---
                if (socTemp > 0) {
                    tvLiveSocTemp.text = "${socTemp}°C"
                    tvLiveSocTemp.setTextColor(if (socTemp >= 50) Color.parseColor("#D71921") else Color.WHITE)
                } else {
                    tvLiveSocTemp.text = "--°C"
                }

                val battTempStr = batteryInfo.temp.replace("°C", "").trim()
                val battTempInt = battTempStr.toIntOrNull() ?: 0
                tvLiveBattTemp.text = batteryInfo.temp
                tvLiveBattTemp.setTextColor(if (battTempInt >= 43) Color.parseColor("#D71921") else Color.WHITE)

                // --- 3. Live Current Drain in mA (Nothing OS Red/White) ---
                if (currentMa != 0) {
                    val isCharging = currentMa > 0
                    val absMa = kotlin.math.abs(currentMa)
                    if (isCharging) {
                        tvLiveCurrentMa.text = "+$absMa mA"
                        tvLiveCurrentMa.setTextColor(Color.WHITE) // Clean White on Charge
                    } else {
                        tvLiveCurrentMa.text = "-$absMa mA"
                        tvLiveCurrentMa.setTextColor(if (absMa > 1100) Color.parseColor("#D71921") else Color.WHITE)
                    }
                } else {
                    tvLiveCurrentMa.text = "-- mA"
                }
                
                val activeCap = prefs.getInt("active_cpu_cap", 100)
                tvLiveCpuCap.text = if (activeCap < 100) "${activeCap}%" else "100%"
                tvLiveCpuCap.setTextColor(if (activeCap < 80) Color.parseColor("#D71921") else Color.WHITE)

                val manualStage = prefs.getInt("manual_stage_override", 0)

                // Stage 1 Dynamic Color Logic: 'S' is always Cyan (#00E5FF), '1' is Cyan for 650M, Yellow for 850M, Red for 950M
                fun getS1Html(freqKhz: Int): String {
                    val oneColor = when {
                        freqKhz <= 650000 -> "#00E5FF" // Cyan for 650MHz (and 480M/550M)
                        freqKhz <= 850000 -> "#FFD700" // Yellow for 850MHz
                        else -> "#FF5252"              // Red for 950MHz
                    }
                    return "<font color='#00E5FF'>S</font><font color='$oneColor'>1</font>"
                }

                // Self-healing: If Little Core frequency has returned to 650MHz or lower, video call boost has ended
                if (TweakManager.isVideoCallBoostActive && littleFreqKhz in 1..650000) {
                    TweakManager.isVideoCallBoostActive = false
                }

                val stageHtml = when {
                    TweakManager.isVideoCallBoostActive -> getS1Html(littleFreqKhz)
                    manualStage == 13 -> "<font color='#00E5FF'>S</font><font color='#00E5FF'>1</font>"
                    manualStage == 12 -> "<font color='#00E5FF'>S</font><font color='#00E5FF'>1</font>"
                    manualStage == 11 -> "<font color='#00E5FF'>S</font><font color='#00E5FF'>1</font>"
                    manualStage == 10 -> getS1Html(littleFreqKhz)
                    manualStage == 1 -> getS1Html(littleFreqKhz)
                    manualStage == 2 -> "<font color='#69F0AE'>S2</font>"
                    manualStage == 3 -> "<font color='#FFD700'>S3</font>"
                    manualStage == 4 -> "<font color='#FF5252'>S4</font>"
                    else -> when {
                        cpuUsage < 35 -> getS1Html(littleFreqKhz)
                        cpuUsage < 70 -> "<font color='#69F0AE'>S2</font>"
                        cpuUsage < 90 -> "<font color='#FFD700'>S3</font>"
                        else -> "<font color='#FF5252'>S4</font>"
                    }
                }

                val freqLabel = if (littleFreqKhz > 0) {
                    if (littleFreqKhz >= 1000000) String.format("%.1fG", littleFreqKhz / 1000000.0)
                    else "${littleFreqKhz / 1000}M"
                } else ""
                val freqSuffix = if (freqLabel.isNotBlank()) " • $freqLabel" else ""

                tvLiveCpuUsage.text = android.text.Html.fromHtml("<font color='#FFFFFF'>$cpuUsage%</font> ($stageHtml<font color='#B0BEC5'>$freqSuffix</font>)", android.text.Html.FROM_HTML_MODE_LEGACY)
            }
        }
    }

    private fun updateDisplayStatus() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val savedMode = prefs.getString("selected_mode", "rbBalance")
        val activeAiLabel = prefs.getString("active_ai_label", "AI: Active")

        val activePerAppMode = prefs.getString("active_per_app_mode", null)
        val activePerAppPkg = prefs.getString("active_per_app_pkg", null)
        val manualStage = prefs.getInt("manual_stage_override", 0)

        val statusText = if (manualStage != 0) {
            when (manualStage) {
                13 -> "TEST LAB // S1 (480M FLOOR)"
                12 -> "TEST LAB // S1 (550M DEEP)"
                11 -> "TEST LAB // S1 (650M ULTRA)"
                10 -> "TEST LAB // S1 (850M EXT)"
                1 -> "TEST LAB // S1 (950M BAL)"
                2 -> "TEST LAB // STAGE 2 (FLUID)"
                3 -> "TEST LAB // STAGE 3 (COMPUTE)"
                4 -> "TEST LAB // STAGE 4 (TURBO)"
                else -> "TEST LAB // STAGE LOCK"
            }
        } else if (!activePerAppMode.isNullOrBlank()) {
            val appLabel = activePerAppPkg?.let { pkgs ->
                val first = pkgs.split(",").firstOrNull()?.trim() ?: ""
                try {
                    val appInfo = packageManager.getApplicationInfo(first, 0)
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) { first }
            } ?: "RECENTS"
            "PER-APP // $activePerAppMode (${appLabel.uppercase()})"
        } else when (savedMode) {
            "rbPowerSaver" -> "MANUAL // POWER SAVER"
            "rbBalance" -> "MANUAL // BALANCED EAS"
            "rbPerformance" -> "MANUAL // TURBO PERFORMANCE"
            "rbStreaming" -> "MANUAL // STREAMING ECO"
            "rbAutomatic" -> (activeAiLabel ?: "AUTOMATIC (AI)").uppercase()
            else -> "STANDBY"
        }
        tvStatus.text = statusText
        
        // Nothing OS Dynamic Status Accent (Signature Red Glyph):
        tvStatus.setTextColor(Color.WHITE)
        ivStatusIcon.setColorFilter(Color.parseColor("#D71921"))
        
        // Ensure service is running and recents hierarchy is evaluated
        val serviceIntent = Intent(this, AutoTweakService::class.java).apply {
            action = AutoTweakService.ACTION_FOREGROUND_APP_CHANGED
            putExtra(AutoTweakService.EXTRA_PACKAGE_NAME, packageName)
        }
        startService(serviceIntent)
    }

    private fun updateCardVisibility() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        findViewById<View>(R.id.cardModeControl).visibility = View.VISIBLE
        findViewById<View>(R.id.cardBattery).visibility = 
            if (prefs.getBoolean("master_battery_hub_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardOptimization).visibility = 
            if (prefs.getBoolean("master_performance_hub_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardGameTurbo).visibility = 
            if (prefs.getBoolean("master_gaming_hub_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardNetwork).visibility = 
            if (prefs.getBoolean("master_security_hub_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardFreezer).visibility = 
            if (prefs.getBoolean("master_tools_hub_enabled", true)) View.VISIBLE else View.GONE
    }


    private fun showTestLabUnlockDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("🧪 Unlock Governor Test Lab?")
            .setMessage("Are you sure you want to access the Stage Override Testing Lab? This allows manual frequency locking for deep testing.")
            .setPositiveButton("Yes, Authenticate") { _, _ ->
                promptDeviceUnlockToRevealTestLab()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptDeviceUnlockToRevealTestLab() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (keyguardManager != null && keyguardManager.isKeyguardSecure) {
            val intent = keyguardManager.createConfirmDeviceCredentialIntent(
                "Unlock Test Lab",
                "Authenticate using your Fingerprint, PIN, Pattern, or Password to access Testing Lab."
            )
            if (intent != null) {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_CODE_UNLOCK_TEST_LAB)
            } else {
                unlockTestLabUi()
            }
        } else {
            unlockTestLabUi()
        }
    }

    private fun unlockTestLabUi() {
        val cardStageOverride = findViewById<View>(R.id.cardStageOverride)
        cardStageOverride.visibility = View.VISIBLE
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("is_test_lab_unlocked", true).apply()
        cardStageOverride.post {
            val scrollView = findViewById<androidx.core.widget.NestedScrollView>(R.id.nestedScrollView)
            scrollView?.smoothScrollTo(0, cardStageOverride.top)
        }
        android.widget.Toast.makeText(this, "🧪 Testing Lab Unlocked", android.widget.Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_UNLOCK_TEST_LAB && resultCode == RESULT_OK) {
            unlockTestLabUi()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_UNLOCK_TEST_LAB = 9021
    }
}
