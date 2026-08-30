package com.example.phonecontrol

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class OptimizationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_optimization)

        findViewById<MaterialToolbar>(R.id.toolbarOpt).setNavigationOnClickListener { finish() }

        // Card 1: Display & Resolution Scaling
        findViewById<View>(R.id.cardResolution).setOnClickListener {
            startActivity(Intent(this, ResolutionActivity::class.java))
        }

        // Card 2: Display Refresh Rate Override
        findViewById<View>(R.id.cardRefreshRate).setOnClickListener {
            startActivity(Intent(this, RefreshRateActivity::class.java))
        }

        // Card 3: Memory & ZRAM Manager
        findViewById<View>(R.id.cardRam).setOnClickListener {
            startActivity(Intent(this, RamActivity::class.java))
        }

        // Card 4: UFS Storage Health & Trimmer
        findViewById<View>(R.id.cardStorage).setOnClickListener {
            startActivity(Intent(this, StorageActivity::class.java))
        }

        // Card 5: Thermal Throttling Safety
        findViewById<View>(R.id.cardThrottling).setOnClickListener {
            startActivity(Intent(this, ThrottlingActivity::class.java))
        }

        updateVisibility()
    }

    override fun onResume() {
        super.onResume()
        updateVisibility()
    }

    private fun updateVisibility() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        findViewById<View>(R.id.cardResolution).visibility =
            if (prefs.getBoolean("resolution_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardRefreshRate).visibility =
            if (prefs.getBoolean("optimization_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardRam).visibility =
            if (prefs.getBoolean("ram_manager_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardStorage).visibility =
            if (prefs.getBoolean("storage_boost_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardThrottling).visibility =
            if (prefs.getBoolean("adaptive_thermal_enabled", true)) View.VISIBLE else View.GONE
    }
}
