package com.example.phonecontrol

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

class OptimizationActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLiveLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_optimization)

        tvStatus = findViewById(R.id.tvOptStatus)
        tvLiveLog = findViewById(R.id.tvLiveLog)
        findViewById<MaterialToolbar>(R.id.toolbarOpt).setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val switchSilent = findViewById<SwitchMaterial>(R.id.switchSilent)
        
        switchSilent.isChecked = prefs.getBoolean("silent_system_enabled", false)
        tvStatus.text = "Last run: ${prefs.getString("last_deep_opt", "Never")}"

        findViewById<Button>(R.id.btnRunNow).setOnClickListener {
            runOptimization()
        }

        // Sub-feature Card Navigation
        findViewById<View>(R.id.cardResolution).setOnClickListener {
            startActivity(Intent(this, ResolutionActivity::class.java))
        }
        findViewById<View>(R.id.cardRam).setOnClickListener {
            startActivity(Intent(this, RamActivity::class.java))
        }
        findViewById<View>(R.id.cardStorage).setOnClickListener {
            startActivity(Intent(this, StorageActivity::class.java))
        }
        findViewById<View>(R.id.cardThrottling).setOnClickListener {
            startActivity(Intent(this, ThrottlingActivity::class.java))
        }

        switchSilent.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("silent_system_enabled", isChecked).apply()
            TweakManager.setSilentSystem(isChecked)
            val msg = if (isChecked) "Silent Mode ON (Logcat Stopped)" else "Silent Mode OFF (Logcat Started)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        updateSubCardVisibility()
    }

    override fun onResume() {
        super.onResume()
        updateSubCardVisibility()
    }

    private fun updateSubCardVisibility() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        findViewById<View>(R.id.cardResolution).visibility = 
            if (prefs.getBoolean("resolution_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardRam).visibility = 
            if (prefs.getBoolean("ram_manager_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardStorage).visibility = 
            if (prefs.getBoolean("storage_boost_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardThrottling).visibility = 
            if (prefs.getBoolean("adaptive_thermal_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardOptimizationSection).visibility = 
            if (prefs.getBoolean("optimization_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnRunNow).visibility = 
            if (prefs.getBoolean("optimization_enabled", true)) View.VISIBLE else View.GONE
    }

    private fun runOptimization() {
        val btn = findViewById<Button>(R.id.btnRunNow)
        btn.isEnabled = false
        btn.text = "OPTIMIZING..."
        
        tvLiveLog.text = ""
        tvLiveLog.visibility = View.VISIBLE
        
        Toast.makeText(this, "Deep optimization started... Please do not close the app.", Toast.LENGTH_LONG).show()
        
        thread {
            DeepOptManager.runFullOptimization(
                context = this,
                onProgress = { task ->
                    runOnUiThread {
                        tvLiveLog.append("> $task\n")
                    }
                },
                onComplete = { time ->
                    runOnUiThread {
                        tvStatus.text = "Last run: $time"
                        btn.isEnabled = true
                        btn.text = "RUN DEEP OPTIMIZATION"
                        tvLiveLog.append("\n[SUCCESS] Optimization Complete!")
                        Toast.makeText(this, "Optimization Complete!", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }
}
