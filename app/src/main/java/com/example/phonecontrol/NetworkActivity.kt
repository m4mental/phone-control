package com.example.phonecontrol

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.concurrent.thread

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
        val tvStatus = findViewById<TextView>(R.id.tvPrivateDnsStatus) ?: return
        thread {
            val current = PrivateDnsManager.getCurrentProvider()
            val spec = PrivateDnsManager.getCurrentSpecifier()
            
            runOnUiThread {
                val detail = if (current == PrivateDnsManager.DnsProvider.CUSTOM && spec.isNotBlank()) {
                    "Custom: $spec\n${current.description}"
                } else {
                    "Active: ${current.displayName}\n${current.description}"
                }
                tvStatus.text = detail
            }
        }
    }

    private fun showPrivateDnsDialog() {
        val providers = PrivateDnsManager.DnsProvider.values()
        val current = PrivateDnsManager.getCurrentProvider()
        val spec = PrivateDnsManager.getCurrentSpecifier()

        val items = providers.map {
            val isCurrent = (it == current)
            val prefix = if (isCurrent) "● " else "○ "
            if (it == PrivateDnsManager.DnsProvider.CUSTOM && isCurrent && spec.isNotBlank()) {
                "$prefix${it.displayName} ($spec)"
            } else {
                "$prefix${it.displayName}"
            }
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("🛡️ Select Private DNS Profile")
            .setItems(items) { _, which ->
                val selected = providers[which]
                if (selected == PrivateDnsManager.DnsProvider.CUSTOM) {
                    promptCustomDnsDialog()
                } else {
                    thread {
                        PrivateDnsManager.setProvider(selected)
                        runOnUiThread {
                            Toast.makeText(this, "DNS updated: ${selected.displayName}", Toast.LENGTH_SHORT).show()
                            refreshDnsStatus()
                            PrivateDnsTileService.updateTile(this)
                        }
                    }
                }
            }
            .setNeutralButton("⚡ Test Ping") { _, _ ->
                runDnsPingTest()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runDnsPingTest() {
        Toast.makeText(this, "Testing DNS latency via Root Shell...", Toast.LENGTH_SHORT).show()
        thread {
            val ping = PrivateDnsManager.measureDnsLatencyRoot()
            runOnUiThread {
                val msg = if (ping >= 0) "⚡ Root Ping: ${ping}ms (DNS Connected)" else "❌ Root Ping Failed / Offline"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun promptCustomDnsDialog() {
        val currentSpec = PrivateDnsManager.getCurrentSpecifier()
        val input = EditText(this).apply {
            hint = "e.g. xxxxxx.dns.nextdns.io or dns.quad9.net"
            setText(currentSpec)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            setPadding(40, 30, 40, 30)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("🔧 Custom Private DNS Hostname")
            .setMessage("Enter your TLS/DoH DNS provider hostname (NextDNS, Pi-hole, AdGuard Home, ControlD):")
            .setView(input)
            .setPositiveButton("APPLY") { _, _ ->
                val hostname = input.text.toString().trim()
                thread {
                    val success = PrivateDnsManager.setCustomHostname(hostname)
                    runOnUiThread {
                        if (success) {
                            Toast.makeText(this, "Custom DNS set: $hostname", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Failed to apply custom DNS", Toast.LENGTH_SHORT).show()
                        }
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
