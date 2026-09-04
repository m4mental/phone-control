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

class TcpBbrActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tcp_bbr)

        findViewById<MaterialToolbar>(R.id.toolbarTcpBbr).setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val rgDns = findViewById<RadioGroup>(R.id.rgDns)
        val switchTcp = findViewById<SwitchMaterial>(R.id.switchTcp)
        val switchLowLatency = findViewById<SwitchMaterial>(R.id.switchLowLatency)
        val switchSmart = findViewById<SwitchMaterial>(R.id.switchSmartSwitch)

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
            val smartEnabled = switchSmart.isChecked

            prefs.edit()
                .putString("network_dns", dnsKey)
                .putBoolean("network_tcp_tweaks", tcpEnabled)
                .putBoolean("network_low_latency", lowLatencyEnabled)
                .putBoolean("smart_switch_enabled", smartEnabled)
                .apply()

            thread {
                val dnsVal = when (dnsKey) {
                    "rbDnsGoogle" -> "8.8.8.8"
                    "rbDnsCloudflare" -> "1.1.1.1"
                    else -> ""
                }
                TweakManager.applyNetworkTweaks(tcpEnabled, dnsVal)
                if (!smartEnabled) {
                    ShellUtils.fastCmd("settings put global mobile_data 1; svc data enable")
                }
                runOnUiThread {
                    Toast.makeText(this, "Network Tweaks Applied!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
