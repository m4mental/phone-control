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
        
        val savedZram = prefs.getString("zram_size", "rbZram4G")
        when (savedZram) {
            "rbZramOff" -> findViewById<RadioButton>(R.id.rbZramOff).isChecked = true
            "rbZram2G" -> findViewById<RadioButton>(R.id.rbZram2G).isChecked = true
            "rbZram4G" -> findViewById<RadioButton>(R.id.rbZram4G).isChecked = true
            "rbZram8G" -> findViewById<RadioButton>(R.id.rbZram8G).isChecked = true
        }

        findViewById<Button>(R.id.btnApplyRam).setOnClickListener {
            val checkedId = rgZram.checkedRadioButtonId
            val key = when (checkedId) {
                R.id.rbZramOff -> "rbZramOff"
                R.id.rbZram2G -> "rbZram2G"
                R.id.rbZram4G -> "rbZram4G"
                R.id.rbZram8G -> "rbZram8G"
                else -> "rbZram4G"
            }
            prefs.edit().putString("zram_size", key).apply()
            applyZram(key)
        }
    }

    private fun applyZram(key: String) {
        val size = when (key) {
            "rbZramOff" -> "0"
            "rbZram2G" -> "2147483648"
            "rbZram4G" -> "4294967296"
            "rbZram8G" -> "8589934592"
            else -> "4294967296"
        }

        Toast.makeText(this, "Applying ZRAM... Please wait", Toast.LENGTH_SHORT).show()
        thread {
            ShellUtils.runAsRoot("swapoff /dev/block/zram0")
            if (size != "0") {
                ShellUtils.runAsRoot("echo 1 > /sys/block/zram0/reset")
                ShellUtils.runAsRoot("echo $size > /sys/block/zram0/disksize")
                ShellUtils.runAsRoot("mkswap /dev/block/zram0")
                ShellUtils.runAsRoot("swapon /dev/block/zram0")
            }
            runOnUiThread {
                Toast.makeText(this, "ZRAM Settings Applied!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
