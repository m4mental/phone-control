package com.example.phonecontrol

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class DpiActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dpi)

        findViewById<MaterialToolbar>(R.id.toolbarDpi).setNavigationOnClickListener { finish() }

        val etDpi = findViewById<EditText>(R.id.etGlobalDpi)
        val btnApply = findViewById<Button>(R.id.btnApplyDpi)
        val btnReset = findViewById<Button>(R.id.btnResetDpi)

        // Show current DPI in hint
        val currentDpi = resources.displayMetrics.densityDpi
        etDpi.hint = "Current: $currentDpi"

        btnApply.setOnClickListener {
            val input = etDpi.text.toString().trim()
            if (input.isNotEmpty()) {
                val dpi = input.toIntOrNull()
                if (dpi != null && dpi in 200..700) {
                    applyDpi(input)
                } else {
                    Toast.makeText(this, "Invalid DPI. Use range 200-700", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnReset.setOnClickListener {
            applyDpi("reset")
            etDpi.setText("")
        }
    }

    private fun applyDpi(dpi: String) {
        Toast.makeText(this, "Applying Density...", Toast.LENGTH_SHORT).show()
        TweakManager.setDpi(dpi)
        
        // Save to prefs so it stays consistent
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .putString("screen_dpi", if (dpi == "reset") "Default" else dpi)
            .apply()
            
        Toast.makeText(this, "System UI Refreshing", Toast.LENGTH_SHORT).show()
    }
}
