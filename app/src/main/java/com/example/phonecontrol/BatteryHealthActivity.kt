package com.example.phonecontrol

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread

class BatteryHealthActivity : AppCompatActivity() {

    private lateinit var tvVolt: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvWattage: TextView
    private lateinit var tvCycles: TextView
    private lateinit var tvHealth: TextView
    private lateinit var tvWear: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_battery_health)

        findViewById<MaterialToolbar>(R.id.toolbarBatteryHealth).setNavigationOnClickListener { finish() }

        tvVolt = findViewById(R.id.tvVolt)
        tvTemp = findViewById(R.id.tvTemp)
        tvWattage = findViewById(R.id.tvWattage)
        tvCycles = findViewById(R.id.tvCycles)
        tvHealth = findViewById(R.id.tvHealth)
        tvWear = findViewById(R.id.tvWear)

        findViewById<MaterialButton>(R.id.btnRefreshHealth).setOnClickListener {
            updateLiveStats()
        }

        updateLiveStats()
    }

    override fun onResume() {
        super.onResume()
        updateLiveStats()
    }

    private fun updateLiveStats() {
        thread {
            val stats = BatteryManager.getBatteryAnalytics(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                tvVolt.text = "${stats.voltageMv / 1000.0}V"
                tvTemp.text = "${stats.tempDeciC / 10.0}°C"
                tvWattage.text = "${String.format("%.2f", stats.currentWatts)}W"
                tvCycles.text = if (stats.cycles > 0) "${stats.cycles} cycles" else "N/A"
                tvHealth.text = stats.health
                tvWear.text = if (stats.wearPercent > 0) "${stats.wearPercent}% Health" else "100% (Healthy)"
            }
        }
    }
}
