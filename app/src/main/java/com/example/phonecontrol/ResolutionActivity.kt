package com.example.phonecontrol

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class ResolutionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_resolution)

        findViewById<MaterialToolbar>(R.id.toolbarRes).setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val rgRes = findViewById<RadioGroup>(R.id.rgResolution)
        
        val savedRes = prefs.getString("screen_res", "rbRes1080")
        if (savedRes == "rbRes720") findViewById<RadioButton>(R.id.rbRes720).isChecked = true
        else findViewById<RadioButton>(R.id.rbRes1080).isChecked = true

        findViewById<Button>(R.id.btnApplyRes).setOnClickListener {
            val checkedId = rgRes.checkedRadioButtonId
            val key = if (checkedId == R.id.rbRes720) "rbRes720" else "rbRes1080"
            prefs.edit().putString("screen_res", key).apply()
            applyResolution(key)
        }
    }

    private fun applyResolution(key: String) {
        val sizeCmd = if (key == "rbRes720") "wm size 720x1600" else "wm size reset"
        val densityCmd = if (key == "rbRes720") "wm density 280" else "wm density reset"
        
        Toast.makeText(this, "Applying Resolution...", Toast.LENGTH_SHORT).show()
        ShellUtils.runAsRoot(sizeCmd)
        ShellUtils.runAsRoot(densityCmd)
        Toast.makeText(this, "Done!", Toast.LENGTH_SHORT).show()
    }
}
