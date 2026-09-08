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

        // Card 4: Private DNS & Ad-Blocker
        findViewById<View>(R.id.cardPrivateDns).setOnClickListener {
            showPrivateDnsDialog()
        }

        updateVisibility()
        refreshDnsStatus()
    }

    override fun onResume() {
        super.onResume()
        updateVisibility()
        refreshDnsStatus()
    }

    private fun refreshDnsStatus() {
        val tvStatus = findViewById<android.widget.TextView>(R.id.tvPrivateDnsStatus) ?: return
        kotlin.concurrent.thread {
            val current = PrivateDnsManager.getCurrentProvider()
            runOnUiThread {
                tvStatus.text = "Active: ${current.displayName}\n${current.description}"
            }
        }
    }

    private fun showPrivateDnsDialog() {
        val providers = PrivateDnsManager.DnsProvider.values()
        val current = PrivateDnsManager.getCurrentProvider()
        val items = providers.map {
            if (it == current) "● ${it.displayName}" else "○ ${it.displayName}"
        }.toTypedArray()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("🛡️ Select Private DNS Profile")
            .setItems(items) { _, which ->
                val selected = providers[which]
                kotlin.concurrent.thread {
                    PrivateDnsManager.setProvider(selected)
                    runOnUiThread {
                        android.widget.Toast.makeText(this, "DNS updated: ${selected.displayName}", android.widget.Toast.LENGTH_SHORT).show()
                        refreshDnsStatus()
                        PrivateDnsTileService.updateTile(this)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
