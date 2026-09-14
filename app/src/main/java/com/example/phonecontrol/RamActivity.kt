package com.example.phonecontrol

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import kotlin.concurrent.thread

class RamActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ram)

        findViewById<MaterialToolbar>(R.id.toolbarRam).setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val rgZram = findViewById<RadioGroup>(R.id.rgZramSize)
        val rgProfile = findViewById<RadioGroup>(R.id.rgRamProfile)
        val rgAlgo = findViewById<RadioGroup>(R.id.rgZramAlgo)
        val tvCompactionStatus = findViewById<TextView>(R.id.tvCompactionStatus)
        val btnCompact = findViewById<Button>(R.id.btnCompactRam)

        // 1. ZRAM Size
        val savedZram = prefs.getString("zram_size", "rbZram8G")
        when (savedZram) {
            "rbZramOff" -> findViewById<RadioButton>(R.id.rbZramOff).isChecked = true
            "rbZram2G" -> findViewById<RadioButton>(R.id.rbZram2G).isChecked = true
            "rbZram4G" -> findViewById<RadioButton>(R.id.rbZram4G).isChecked = true
            "rbZram8G" -> findViewById<RadioButton>(R.id.rbZram8G).isChecked = true
        }

        // 2. Multitasking VM Profile
        val savedProfile = prefs.getString("ram_profile", "rbProfileBalance")
        when (savedProfile) {
            "rbProfileBalance" -> findViewById<RadioButton>(R.id.rbProfileBalance).isChecked = true
            "rbProfileMultitasking" -> findViewById<RadioButton>(R.id.rbProfileMultitasking).isChecked = true
            "rbProfilePerformance" -> findViewById<RadioButton>(R.id.rbProfilePerformance).isChecked = true
        }

        // 3. ZRAM Compression Algorithm
        val savedAlgo = prefs.getString("zram_algorithm", "lz4")
        if (savedAlgo == "zstd") {
            findViewById<RadioButton>(R.id.rbAlgoZstd).isChecked = true
        } else {
            findViewById<RadioButton>(R.id.rbAlgoLz4).isChecked = true
        }

        // 4. 1-Tap Instant ZRAM Compaction
        btnCompact.setOnClickListener {
            tvCompactionStatus.text = "Compacting and defragmenting memory pages..."
            btnCompact.isEnabled = false
            thread {
                val freedBytes = TweakManager.compactZram()
                runOnUiThread {
                    btnCompact.isEnabled = true
                    val freedKb = freedBytes / 1024
                    if (freedKb > 0) {
                        val msg = "✅ Compacted! Reclaimed ~${freedKb} KB of kernel memory."
                        tvCompactionStatus.text = msg
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    } else {
                        val msg = "✅ ZRAM memory already optimal (0 fragmentation)."
                        tvCompactionStatus.text = msg
                        Toast.makeText(this, "ZRAM memory defragmented and optimized!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 5. Apply All RAM & ZRAM Settings
        findViewById<Button>(R.id.btnApplyRam).setOnClickListener {
            val zramKey = when (rgZram.checkedRadioButtonId) {
                R.id.rbZramOff -> "rbZramOff"
                R.id.rbZram2G -> "rbZram2G"
                R.id.rbZram4G -> "rbZram4G"
                R.id.rbZram8G -> "rbZram8G"
                else -> "rbZram8G"
            }

            val profileKey = when (rgProfile.checkedRadioButtonId) {
                R.id.rbProfileBalance -> "rbProfileBalance"
                R.id.rbProfileMultitasking -> "rbProfileMultitasking"
                R.id.rbProfilePerformance -> "rbProfilePerformance"
                else -> "rbProfileBalance"
            }

            val algoKey = if (rgAlgo.checkedRadioButtonId == R.id.rbAlgoZstd) "zstd" else "lz4"

            prefs.edit()
                .putString("zram_size", zramKey)
                .putString("ram_profile", profileKey)
                .putString("zram_algorithm", algoKey)
                .apply()

            Toast.makeText(this, "Applying RAM Optimization... Please wait", Toast.LENGTH_SHORT).show()
            thread {
                TweakManager.applyRamSettings(zramKey, profileKey, algoKey)
                runOnUiThread {
                    Toast.makeText(this, "RAM & ZRAM Settings Applied! ($algoKey)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
