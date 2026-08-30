package com.example.phonecontrol

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class NetworkActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network)

        findViewById<MaterialToolbar>(R.id.toolbarNetwork).setNavigationOnClickListener { finish() }

        // Card 1: Home 5G Tower Lock
        findViewById<View>(R.id.cardTowerLock).setOnClickListener {
            startActivity(Intent(this, HomeTowerLockActivity::class.java))
        }

        // Card 2: Per-App Data Firewall
        findViewById<View>(R.id.cardFirewall).setOnClickListener {
            startActivity(Intent(this, FirewallActivity::class.java))
        }

        // Card 3: TCP BBR & Latency Booster
        findViewById<View>(R.id.cardTcpBbr).setOnClickListener {
            startActivity(Intent(this, TcpBbrActivity::class.java))
        }

        updateVisibility()
    }

    override fun onResume() {
        super.onResume()
        updateVisibility()
    }

    private fun updateVisibility() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        findViewById<View>(R.id.cardTowerLock).visibility =
            if (prefs.getBoolean("tower_lock_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardFirewall).visibility =
            if (prefs.getBoolean("firewall_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardTcpBbr).visibility =
            if (prefs.getBoolean("network_priority_enabled", true)) View.VISIBLE else View.GONE
    }
}
