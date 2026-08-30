package com.example.phonecontrol

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread

class RefreshRateActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_refresh_rate)

        findViewById<MaterialToolbar>(R.id.toolbarRefreshRate).setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val rg = findViewById<RadioGroup>(R.id.rgRefreshRate)

        val savedHz = prefs.getString("screen_refresh", "rbHzDynamic")
        when (savedHz) {
            "rbHz120" -> findViewById<RadioButton>(R.id.rbHz120).isChecked = true
            "rbHz90" -> findViewById<RadioButton>(R.id.rbHz90).isChecked = true
            "rbHz60" -> findViewById<RadioButton>(R.id.rbHz60).isChecked = true
            else -> findViewById<RadioButton>(R.id.rbHzDynamic).isChecked = true
        }

        findViewById<MaterialButton>(R.id.btnApplyRefresh).setOnClickListener {
            val selectedId = rg.checkedRadioButtonId
            val key = when (selectedId) {
                R.id.rbHz120 -> "rbHz120"
                R.id.rbHz90 -> "rbHz90"
                R.id.rbHz60 -> "rbHz60"
                else -> "rbHzDynamic"
            }
            val rateStr = when (selectedId) {
                R.id.rbHz120 -> "120Hz"
                R.id.rbHz90 -> "90Hz"
                R.id.rbHz60 -> "60Hz"
                else -> "Default"
            }

            prefs.edit().putString("screen_refresh", key).apply()
            thread {
                TweakManager.setRefreshRate(rateStr)
                runOnUiThread {
                    Toast.makeText(this, "Refresh rate set to $rateStr", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
