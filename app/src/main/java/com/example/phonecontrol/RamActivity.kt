package com.example.phonecontrol

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
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
        
        val savedZram = prefs.getString("zram_size", "rbZram4G")
        when (savedZram) {
            "rbZramOff" -> findViewById<RadioButton>(R.id.rbZramOff).isChecked = true
            "rbZram2G" -> findViewById<RadioButton>(R.id.rbZram2G).isChecked = true
            "rbZram4G" -> findViewById<RadioButton>(R.id.rbZram4G).isChecked = true
            "rbZram8G" -> findViewById<RadioButton>(R.id.rbZram8G).isChecked = true
        }

        val savedProfile = prefs.getString("ram_profile", "rbProfileBalance")
        when (savedProfile) {
            "rbProfileBalance" -> findViewById<RadioButton>(R.id.rbProfileBalance).isChecked = true
            "rbProfileMultitasking" -> findViewById<RadioButton>(R.id.rbProfileMultitasking).isChecked = true
            "rbProfilePerformance" -> findViewById<RadioButton>(R.id.rbProfilePerformance).isChecked = true
        }

        findViewById<Button>(R.id.btnApplyRam).setOnClickListener {
            val zramKey = when (rgZram.checkedRadioButtonId) {
                R.id.rbZramOff -> "rbZramOff"
                R.id.rbZram2G -> "rbZram2G"
                R.id.rbZram4G -> "rbZram4G"
                R.id.rbZram8G -> "rbZram8G"
                else -> "rbZram4G"
            }

            val profileKey = when (rgProfile.checkedRadioButtonId) {
                R.id.rbProfileBalance -> "rbProfileBalance"
                R.id.rbProfileMultitasking -> "rbProfileMultitasking"
                R.id.rbProfilePerformance -> "rbProfilePerformance"
                else -> "rbProfileBalance"
            }

            prefs.edit().putString("zram_size", zramKey).putString("ram_profile", profileKey).apply()
            
            Toast.makeText(this, "Applying RAM Optimization... Please wait", Toast.LENGTH_SHORT).show()
            thread {
                TweakManager.applyRamSettings(zramKey, profileKey)
                runOnUiThread {
                    Toast.makeText(this, "RAM Settings Applied!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
