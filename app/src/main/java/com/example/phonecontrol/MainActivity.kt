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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvRootStatus = findViewById(R.id.tvRootStatus)

        // Navigation Hub
        findViewById<View>(R.id.cardModeControl).setOnClickListener { startActivity(Intent(this, ModeControlActivity::class.java)) }
        findViewById<View>(R.id.cardPerApp).setOnClickListener { startActivity(Intent(this, PerAppActivity::class.java)) }
        findViewById<View>(R.id.cardFreezer).setOnClickListener { startActivity(Intent(this, FreezerActivity::class.java)) }
        findViewById<View>(R.id.cardBattery).setOnClickListener { startActivity(Intent(this, BatteryActivity::class.java)) }
        findViewById<View>(R.id.cardThrottling).setOnClickListener { startActivity(Intent(this, ThrottlingActivity::class.java)) }
        
        // Advanced Section
        findViewById<View>(R.id.cardResolution).setOnClickListener { startActivity(Intent(this, ResolutionActivity::class.java)) }
        findViewById<View>(R.id.cardBloatware).setOnClickListener { startActivity(Intent(this, BloatwareActivity::class.java)) }
        findViewById<View>(R.id.cardRam).setOnClickListener { startActivity(Intent(this, RamActivity::class.java)) }
        findViewById<View>(R.id.cardNetwork).setOnClickListener { startActivity(Intent(this, NetworkActivity::class.java)) }
        findViewById<View>(R.id.cardOptimization).setOnClickListener { startActivity(Intent(this, OptimizationActivity::class.java)) }

        requestNotificationPermission()
        checkRoot()
    }

    override fun onResume() {
        super.onResume()
        updateDisplayStatus()
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

    private fun checkRoot() {
        val result = ShellUtils.runAsRoot("id")
        if (result.exitCode == 0) {
            tvRootStatus.text = "Root: Granted"; tvRootStatus.setTextColor(Color.GREEN)
        } else {
            tvRootStatus.text = "Root: Denied"; tvRootStatus.setTextColor(Color.RED)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
}
