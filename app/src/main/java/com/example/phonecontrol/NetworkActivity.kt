package com.example.phonecontrol

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

class NetworkActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network)

        findViewById<MaterialToolbar>(R.id.toolbarNetwork).setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val rgDns = findViewById<RadioGroup>(R.id.rgDns)
        val switchTcp = findViewById<SwitchMaterial>(R.id.switchTcp)
        val switchLowLatency = findViewById<SwitchMaterial>(R.id.switchLowLatency)
        val switchPriority = findViewById<SwitchMaterial>(R.id.switchNetworkPriority)
        
        // Load Saved States
        val savedDns = prefs.getString("network_dns", "rbDnsDefault")
        when (savedDns) {
            "rbDnsDefault" -> findViewById<RadioButton>(R.id.rbDnsDefault).isChecked = true
            "rbDnsGoogle" -> findViewById<RadioButton>(R.id.rbDnsGoogle).isChecked = true
            "rbDnsCloudflare" -> findViewById<RadioButton>(R.id.rbDnsCloudflare).isChecked = true
        }
        switchTcp.isChecked = prefs.getBoolean("network_tcp_tweaks", false)
        switchLowLatency.isChecked = prefs.getBoolean("network_low_latency", false)
        switchPriority.isChecked = prefs.getBoolean("network_priority_enabled", false)

        findViewById<Button>(R.id.btnApplyNetwork).setOnClickListener {
            val dnsKey = when (rgDns.checkedRadioButtonId) {
                R.id.rbDnsGoogle -> "rbDnsGoogle"
                R.id.rbDnsCloudflare -> "rbDnsCloudflare"
                else -> "rbDnsDefault"
            }
            val tcpEnabled = switchTcp.isChecked
            val lowLatencyEnabled = switchLowLatency.isChecked
            val priorityEnabled = switchPriority.isChecked

            prefs.edit()
                .putString("network_dns", dnsKey)
                .putBoolean("network_tcp_tweaks", tcpEnabled)
                .putBoolean("network_low_latency", lowLatencyEnabled)
                .putBoolean("network_priority_enabled", priorityEnabled)
                .apply()
                
            applyNetwork(dnsKey, tcpEnabled, lowLatencyEnabled)
        }

        findViewById<Button>(R.id.btnManageFirewall).setOnClickListener {
            startActivity(android.content.Intent(this, FirewallActivity::class.java))
        }
    }

    private fun applyNetwork(dnsKey: String, tcpEnabled: Boolean, lowLatencyEnabled: Boolean) {
        Toast.makeText(this, "Optimizing Network...", Toast.LENGTH_SHORT).show()
        thread {
            TweakManager.applyNetworkSettings(dnsKey, tcpEnabled, lowLatencyEnabled)
            runOnUiThread {
                Toast.makeText(this, "Network Optimized for Gaming!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
