package com.example.phonecontrol

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import kotlin.concurrent.thread

class CleanerActivity : AppCompatActivity() {

    private lateinit var tvJunkSize: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cleaner)

        tvJunkSize = findViewById(R.id.tvJunkSize)
        findViewById<MaterialToolbar>(R.id.toolbarCleaner).setNavigationOnClickListener { finish() }

        calculateJunkSize()

        findViewById<Button>(R.id.btnCleanNow).setOnClickListener {
            runCleaner()
        }
    }

    private fun calculateJunkSize() {
        thread {
            val paths = listOf("/data/tombstones", "/data/anr", "/data/system/dropbox", "/cache")
            var totalSize = 0L
            for (path in paths) {
                val res = ShellUtils.runAsRoot("du -sk $path | awk '{print $1}'")
                totalSize += res.output.toLongOrNull() ?: 0L
            }
            runOnUiThread {
                val mb = totalSize / 1024
                tvJunkSize.text = "$mb MB"
            }
        }
    }

    private fun runCleaner() {
        Toast.makeText(this, "Cleaning System...", Toast.LENGTH_SHORT).show()
        thread {
            val cmds = listOf(
                "rm -rf /data/tombstones/*",
                "rm -rf /data/anr/*",
                "rm -rf /data/system/dropbox/*",
                "rm -rf /cache/*",
                "rm -rf /data/system/usagestats/*"
            )
            ShellUtils.runCommandsAsRoot(cmds)
            runOnUiThread {
                tvJunkSize.text = "0 MB"
                Toast.makeText(this, "System Cleaned Successfully!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
