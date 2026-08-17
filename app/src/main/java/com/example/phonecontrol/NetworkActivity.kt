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
        
        val savedDns = prefs.getString("network_dns", "rbDnsDefault")
        when (savedDns) {
            "rbDnsDefault" -> findViewById<RadioButton>(R.id.rbDnsDefault).isChecked = true
            "rbDnsGoogle" -> findViewById<RadioButton>(R.id.rbDnsGoogle).isChecked = true
            "rbDnsCloudflare" -> findViewById<RadioButton>(R.id.rbDnsCloudflare).isChecked = true
        }
        switchTcp.isChecked = prefs.getBoolean("network_tcp_tweaks", false)

        findViewById<Button>(R.id.btnApplyNetwork).setOnClickListener {
            val dnsKey = when (rgDns.checkedRadioButtonId) {
                R.id.rbDnsGoogle -> "rbDnsGoogle"
                R.id.rbDnsCloudflare -> "rbDnsCloudflare"
                else -> "rbDnsDefault"
            }
            val tcpEnabled = switchTcp.isChecked

            prefs.edit().putString("network_dns", dnsKey).putBoolean("network_tcp_tweaks", tcpEnabled).apply()
            applyNetwork(dnsKey, tcpEnabled)
        }
    }

    private fun applyNetwork(dnsKey: String, tcpEnabled: Boolean) {
        Toast.makeText(this, "Optimizing Network...", Toast.LENGTH_SHORT).show()
        thread {
            // DNS Tweak
            val dns = when (dnsKey) {
                "rbDnsGoogle" -> "8.8.8.8"
                "rbDnsCloudflare" -> "1.1.1.1"
                else -> ""
            }
            if (dns.isNotEmpty()) {
                ShellUtils.runAsRoot("settings put global private_dns_mode hostname")
                ShellUtils.runAsRoot("settings put global private_dns_specifier $dns")
            } else {
                ShellUtils.runAsRoot("settings put global private_dns_mode off")
            }

            // TCP Tweaks
            if (tcpEnabled) {
                val cmds = listOf(
                    "sysctl -w net.ipv4.tcp_timestamps=0",
                    "sysctl -w net.ipv4.tcp_sack=1",
                    "sysctl -w net.ipv4.tcp_window_scaling=1",
                    "sysctl -w net.core.rmem_max=16777216",
                    "sysctl -w net.core.wmem_max=16777216"
                )
                ShellUtils.runCommandsAsRoot(cmds)
            }
            
            runOnUiThread {
                Toast.makeText(this, "Network Optimized!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
