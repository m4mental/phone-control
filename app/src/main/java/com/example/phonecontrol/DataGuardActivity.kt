package com.example.phonecontrol

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.telephony.*
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.*

class DataGuardActivity : AppCompatActivity() {

    private lateinit var tvNrState: TextView
    private lateinit var tvDisplayInfo: TextView
    private lateinit var tvNetType: TextView
    private lateinit var swService: SwitchMaterial

    private lateinit var telephonyManager: TelephonyManager
    private var telephonyCallback: TelephonyCallback? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_guard)

        findViewById<MaterialToolbar>(R.id.toolbarGuard).setNavigationOnClickListener { finish() }

        tvNrState = findViewById(R.id.tvLiveNrState)
        tvDisplayInfo = findViewById(R.id.tvLiveDisplayInfo)
        tvNetType = findViewById(R.id.tvLiveNetType)
        swService = findViewById(R.id.swServiceToggle)

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val prefs = getSharedPreferences("tower_prefs", MODE_PRIVATE)
        swService.isChecked = prefs.getBoolean("5g_dataguard_enabled", false)

        swService.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("5g_dataguard_enabled", isChecked).apply()
            if (isChecked) {
                checkAndStartService()
            } else {
                stopService(Intent(this, NRMonitorService::class.java))
            }
        }

        startLiveMonitoring()
    }

    private fun checkAndStartService() {
        val permissions = mutableListOf(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needsRequest = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsRequest.isEmpty()) {
            startForegroundService(Intent(this, NRMonitorService::class.java))
        } else {
            ActivityCompat.requestPermissions(this, needsRequest.toTypedArray(), 1002)
        }
    }

    private fun startLiveMonitoring() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return

        telephonyCallback = object : TelephonyCallback(), 
            TelephonyCallback.ServiceStateListener,
            TelephonyCallback.DisplayInfoListener {
            
            override fun onServiceStateChanged(serviceState: ServiceState) {
                updateDetails(serviceState, null)
            }

            override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
                updateDetails(null, displayInfo)
            }
        }
        
        telephonyManager.registerTelephonyCallback(mainExecutor, telephonyCallback!!)
    }

    private var lastServiceState: ServiceState? = null
    private var lastDisplayInfo: TelephonyDisplayInfo? = null

    @SuppressLint("NewApi", "SetTextI18n")
    private fun updateDetails(ss: ServiceState?, di: TelephonyDisplayInfo?) {
        if (ss != null) lastServiceState = ss
        if (di != null) lastDisplayInfo = di

        val serviceState = lastServiceState ?: return
        val displayInfo = lastDisplayInfo
        
        // Extract NR State
        var nrState = NetworkRegistrationInfo.NR_STATE_NONE
        serviceState.networkRegistrationInfoList.forEach { info ->
            if (info.domain == NetworkRegistrationInfo.DOMAIN_PS && 
                info.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
                
                nrState = try {
                    val method = info.javaClass.getMethod("getNrState")
                    method.invoke(info) as Int
                } catch (e: Exception) { 0 }
            }
        }

        tvNrState.text = when(nrState) {
            NetworkRegistrationInfo.NR_STATE_CONNECTED -> "CONNECTED (REAL 5G)"
            NetworkRegistrationInfo.NR_STATE_NOT_RESTRICTED -> "NOT RESTRICTED (FAKE)"
            NetworkRegistrationInfo.NR_STATE_RESTRICTED -> "RESTRICTED"
            else -> "NONE"
        }
        tvNrState.setTextColor(if(nrState == 3) Color.GREEN else if(nrState > 0) Color.YELLOW else Color.RED)

        tvDisplayInfo.text = when(displayInfo?.overrideNetworkType) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "NR_NSA"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "NR_ADVANCED"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE_ADV"
            else -> "NONE"
        }

        val netType = if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            telephonyManager.networkType
        } else 0
        
        tvNetType.text = when(netType) {
            TelephonyManager.NETWORK_TYPE_NR -> "NR (5G)"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE (4G)"
            else -> "OTHER ($netType)"
        }
    }

    override fun onDestroy() {
        telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
        scope.cancel()
        super.onDestroy()
    }
}
