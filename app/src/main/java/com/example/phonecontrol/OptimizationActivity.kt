package com.example.phonecontrol

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
        val switchDaily = findViewById<SwitchMaterial>(R.id.switchDaily)
        val switchSilent = findViewById<SwitchMaterial>(R.id.switchSilent)
        
        switchDaily.isChecked = prefs.getBoolean("daily_deep_opt_enabled", false)
        switchSilent.isChecked = prefs.getBoolean("silent_system_enabled", false)
        tvStatus.text = "Last run: ${prefs.getString("last_deep_opt", "Never")}"

        findViewById<Button>(R.id.btnRunNow).setOnClickListener {
            runOptimization()
        }

        switchDaily.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("daily_deep_opt_enabled", isChecked).apply()
            if (isChecked) {
                Toast.makeText(this, "Daily optimization scheduled for 3 AM", Toast.LENGTH_SHORT).show()
            }
        }

        switchSilent.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("silent_system_enabled", isChecked).apply()
            TweakManager.setSilentSystem(isChecked)
            val msg = if (isChecked) "Silent Mode ON (Logcat Stopped)" else "Silent Mode OFF (Logcat Started)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
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
