package com.example.phonecontrol

import android.content.Intent
import android.os.Bundle
import android.view.View
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
        val switchSmart = findViewById<SwitchMaterial>(R.id.switchSmartSwitch)

        // Sub-feature Card Navigation
        findViewById<View>(R.id.cardTowerLock).setOnClickListener {
            startActivity(Intent(this, HomeTowerLockActivity::class.java))
        }
        findViewById<View>(R.id.cardFirewall).setOnClickListener {
            startActivity(Intent(this, FirewallActivity::class.java))
        }
        
        // Load Saved States
        val savedDns = prefs.getString("network_dns", "rbDnsDefault")
        when (savedDns) {
            "rbDnsDefault" -> findViewById<RadioButton>(R.id.rbDnsDefault).isChecked = true
            "rbDnsGoogle" -> findViewById<RadioButton>(R.id.rbDnsGoogle).isChecked = true
            "rbDnsCloudflare" -> findViewById<RadioButton>(R.id.rbDnsCloudflare).isChecked = true
        }
        switchTcp.isChecked = prefs.getBoolean("network_tcp_tweaks", true)
        switchLowLatency.isChecked = prefs.getBoolean("network_low_latency", false)
        switchSmart.isChecked = prefs.getBoolean("smart_switch_enabled", false)

        findViewById<Button>(R.id.btnApplyNetwork).setOnClickListener {
            val dnsKey = when (rgDns.checkedRadioButtonId) {
                R.id.rbDnsGoogle -> "rbDnsGoogle"
                R.id.rbDnsCloudflare -> "rbDnsCloudflare"
                else -> "rbDnsDefault"
            }
            val tcpEnabled = switchTcp.isChecked
            val lowLatencyEnabled = switchLowLatency.isChecked

            prefs.edit()
                .putString("network_dns", dnsKey)
                .putBoolean("network_tcp_tweaks", tcpEnabled)
                .putBoolean("network_low_latency", lowLatencyEnabled)
                .apply()
                
            applyNetwork(dnsKey, tcpEnabled, lowLatencyEnabled)
        }

        switchSmart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("smart_switch_enabled", isChecked).apply()
            Toast.makeText(this, if (isChecked) "Smart Switch ON (Auto-off on Wi-Fi)" else "Smart Switch OFF", Toast.LENGTH_SHORT).show()
        }

        updateSubCardVisibility()
    }

    override fun onResume() {
        super.onResume()
        updateSubCardVisibility()
    }

    private fun updateSubCardVisibility() {
        val masterPrefs = getSharedPreferences("prefs", MODE_PRIVATE)
        findViewById<View>(R.id.cardTowerLock).visibility = 
            if (masterPrefs.getBoolean("tower_lock_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardFirewall).visibility = 
            if (masterPrefs.getBoolean("firewall_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardTcpTweaks).visibility = 
            if (masterPrefs.getBoolean("network_priority_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tvNetworkTuningHeader).visibility = 
            if (masterPrefs.getBoolean("network_priority_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardSmartData).visibility = 
            if (masterPrefs.getBoolean("smart_switch_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tvSmartDataHeader).visibility = 
            if (masterPrefs.getBoolean("smart_switch_enabled", true)) View.VISIBLE else View.GONE
    }

    private fun applyNetwork(dnsKey: String, tcp: Boolean, lowLatency: Boolean) {
        Toast.makeText(this, "Applying Network Enhancements...", Toast.LENGTH_SHORT).show()
        thread {
            // Apply DNS
            when (dnsKey) {
                "rbDnsGoogle" -> {
                    ShellUtils.runAsRoot("setprop net.dns1 8.8.8.8")
                    ShellUtils.runAsRoot("setprop net.dns2 8.8.4.4")
                }
                "rbDnsCloudflare" -> {
                    ShellUtils.runAsRoot("setprop net.dns1 1.1.1.1")
                    ShellUtils.runAsRoot("setprop net.dns2 1.0.0.1")
                }
                else -> {
                    // System Default (Reset)
                    ShellUtils.runAsRoot("setprop net.dns1 ''")
                    ShellUtils.runAsRoot("setprop net.dns2 ''")
                }
            }

            // Apply TCP BBR
            if (tcp) {
                ShellUtils.runAsRoot("sysctl -w net.ipv4.tcp_congestion_control=bbr")
                ShellUtils.runAsRoot("sysctl -w net.ipv4.tcp_ecn=0")
            } else {
                ShellUtils.runAsRoot("sysctl -w net.ipv4.tcp_congestion_control=cubic")
            }

            // Apply Low Latency Mode (Disable Wi-Fi Roaming scan)
            if (lowLatency) {
                ShellUtils.runAsRoot("cmd wifi set-scan-always-available 0")
            } else {
                ShellUtils.runAsRoot("cmd wifi set-scan-always-available 1")
            }

            runOnUiThread {
                Toast.makeText(this, "Network Configuration Applied!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
