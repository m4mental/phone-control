package com.example.phonecontrol

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.telephony.*
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.*

class HomeTowerLockActivity : AppCompatActivity() {

    private lateinit var tvLiveRat: TextView
    private lateinit var tvLivePci: TextView
    private lateinit var tvLiveEarfcn: TextView
    private lateinit var tvLiveRsrp: TextView
    private lateinit var tvLiveRsrq: TextView
    private lateinit var tvLiveCarrier: TextView

    private lateinit var switchAntiSleep: SwitchMaterial
    private lateinit var tvSavedHomeInfo: TextView
    private lateinit var tvLockStatus: TextView
    private lateinit var layoutTowerList: LinearLayout
    private lateinit var etManualPci: EditText
    private lateinit var etManualEarfcn: EditText

    private var currentPci = -1
    private var currentEarfcn = -1
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    data class TowerInfo(
        val pci: Int,
        val earfcn: Int,
        val rsrp: Int,
        val type: String,
        val isRegistered: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_tower_lock)

        findViewById<MaterialToolbar>(R.id.toolbarTower).setNavigationOnClickListener { finish() }

        tvLiveRat = findViewById(R.id.tvLiveRat)
        tvLivePci = findViewById(R.id.tvLivePci)
        tvLiveEarfcn = findViewById(R.id.tvLiveEarfcn)
        tvLiveRsrp = findViewById(R.id.tvLiveRsrp)
        tvLiveRsrq = findViewById(R.id.tvLiveRsrq)
        tvLiveCarrier = findViewById(R.id.tvLiveCarrier)

        switchAntiSleep = findViewById(R.id.switchAntiSleep)
        tvSavedHomeInfo = findViewById(R.id.tvSavedHomeInfo)
        tvLockStatus = findViewById(R.id.tvLockStatus)
        layoutTowerList = findViewById(R.id.layoutTowerList)
        etManualPci = findViewById(R.id.etManualPci)
        etManualEarfcn = findViewById(R.id.etManualEarfcn)

        val prefs = getSharedPreferences("tower_prefs", MODE_PRIVATE)
        switchAntiSleep.isChecked = prefs.getBoolean("5g_antisleep_enabled", false)

        switchAntiSleep.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("5g_antisleep_enabled", isChecked).apply()
            apply5gAntiSleep(isChecked)
        }

        findViewById<MaterialButton>(R.id.btnSaveHomeTower).setOnClickListener { saveHomeTower() }
        findViewById<MaterialButton>(R.id.btnLockHome).setOnClickListener { lockToHome() }
        findViewById<MaterialButton>(R.id.btnReleaseLock).setOnClickListener { releaseLock() }
        findViewById<MaterialButton>(R.id.btnApplyManualLock).setOnClickListener { applyManual() }
        findViewById<MaterialButton>(R.id.btnManualScan).setOnClickListener { 
            Toast.makeText(this, "Scanning live baseband towers...", Toast.LENGTH_SHORT).show()
            updateTowerInfo() 
        }

        updateSavedInfo()
        updateTowerInfo()
    }

    private fun apply5gAntiSleep(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            if (enabled) {
                val cmds = listOf(
                    "echo -e \"AT+E5GSWITCH=1\\r\\n\" > /dev/radio/pttycmd1 2>/dev/null",
                    "echo -e \"AT+EPOWERCONF=0\\r\\n\" > /dev/radio/pttycmd1 2>/dev/null",
                    "echo -e \"AT+E5GSWITCH=1\\r\\n\" > /dev/ttyC0 2>/dev/null",
                    "setprop persist.vendor.radio.md_sleep_threshold -100",
                    "setprop persist.vendor.radio.smart5g 0",
                    "setprop persist.vendor.radio.smart5g.mode 0"
                )
                ShellUtils.runCommandsAsRoot(cmds)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@HomeTowerLockActivity, "5G Modem Anti-Sleep ON (Keep-Alive Active)", Toast.LENGTH_SHORT).show()
                }
            } else {
                val cmds = listOf(
                    "echo -e \"AT+EPOWERCONF=1\\r\\n\" > /dev/radio/pttycmd1 2>/dev/null",
                    "setprop persist.vendor.radio.smart5g 1"
                )
                ShellUtils.runCommandsAsRoot(cmds)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@HomeTowerLockActivity, "5G Modem Anti-Sleep OFF (Default Power Save)", Toast.LENGTH_SHORT).show()
                }
            }
            DaemonManager.startDaemon(applicationContext)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateTowerInfo() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE), 100)
            return
        }

        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val carrier = tm.networkOperatorName.ifBlank { "Cellular Network" }
        tvLiveCarrier.text = carrier

        val cellInfoList = tm.allCellInfo ?: return
        val towerList = mutableListOf<TowerInfo>()

        for (info in cellInfoList) {
            val isRegistered = info.isRegistered
            if (info is CellInfoLte) {
                val identity = info.cellIdentity
                val pci = identity.pci
                val earfcn = identity.earfcn
                if (pci == 2147483647 || earfcn == 2147483647) continue

                val rsrp = info.cellSignalStrength.rsrp
                val rsrq = info.cellSignalStrength.rsrq

                if (isRegistered) {
                    currentPci = pci
                    currentEarfcn = earfcn
                    tvLiveRat.text = "4G LTE (${getBandName(earfcn)})"
                    tvLivePci.text = "PCI: $pci"
                    tvLiveEarfcn.text = "EARFCN: $earfcn"
                    tvLiveRsrp.text = "$rsrp dBm"
                    tvLiveRsrq.text = "$rsrq dB"
                    tvLiveRsrp.setTextColor(if (rsrp > -95) Color.parseColor("#00E676") else if (rsrp > -110) Color.YELLOW else Color.RED)
                }
                towerList.add(TowerInfo(pci, earfcn, rsrp, "LTE", isRegistered))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr) {
                val identity = info.cellIdentity as? CellIdentityNr
                val pci = identity?.pci ?: -1
                val earfcn = identity?.nrarfcn ?: -1
                if (pci == 2147483647 || earfcn == 2147483647) continue

                val rsrp = (info.cellSignalStrength as? CellSignalStrengthNr)?.ssRsrp ?: -110
                val rsrq = (info.cellSignalStrength as? CellSignalStrengthNr)?.ssRsrq ?: -15

                if (isRegistered) {
                    currentPci = pci
                    currentEarfcn = earfcn
                    tvLiveRat.text = "5G NR (${getNrBandName(earfcn)})"
                    tvLivePci.text = "PCI: $pci"
                    tvLiveEarfcn.text = "NR-ARFCN: $earfcn"
                    tvLiveRsrp.text = "$rsrp dBm"
                    tvLiveRsrq.text = "$rsrq dB"
                    tvLiveRsrp.setTextColor(Color.parseColor("#00E676"))
                }
                towerList.add(TowerInfo(pci, earfcn, rsrp, "5G NR", isRegistered))
            }
        }

        val top3 = towerList.sortedByDescending { it.rsrp }.distinctBy { "${it.pci}_${it.earfcn}" }.take(4)
        runOnUiThread { renderTowerList(top3) }
    }

    private fun getNrBandName(arfcn: Int): String {
        return when {
            arfcn in 151600..160600 -> "n28 (700MHz)"
            arfcn in 620000..653333 -> "n78 (3500MHz)"
            arfcn in 422000..434000 -> "n1 (2100MHz)"
            arfcn in 361000..376000 -> "n3 (1800MHz)"
            else -> "n78/n28"
        }
    }

    private fun getBandName(earfcn: Int): String {
        return when {
            earfcn in 0..599 -> "B1 (2100MHz)"
            earfcn in 1200..1949 -> "B3 (1800MHz)"
            earfcn in 2400..2649 -> "B5 (850MHz)"
            earfcn in 3450..3799 -> "B8 (900MHz)"
            earfcn in 9210..9659 -> "B28 (700MHz)"
            earfcn in 38650..39649 -> "B40 (2300MHz)"
            earfcn in 40620..41589 -> "B41 (2500MHz)"
            else -> "LTE"
        }
    }

    private fun renderTowerList(towers: List<TowerInfo>) {
        layoutTowerList.removeAllViews()

        if (towers.isEmpty()) {
            val tv = TextView(this)
            tv.text = "No towers detected. Tap SCAN NOW to rescan."
            tv.setTextColor(Color.GRAY)
            layoutTowerList.addView(tv)
            return
        }

        val inflater = LayoutInflater.from(this)
        towers.forEachIndexed { index, tower ->
            val v = inflater.inflate(R.layout.item_tower_info, layoutTowerList, false)
            v.findViewById<TextView>(R.id.tvTowerRank).text = (index + 1).toString()
            v.findViewById<TextView>(R.id.tvTowerMainInfo).text = "PCI: ${tower.pci} | EARFCN: ${tower.earfcn}"
            v.findViewById<TextView>(R.id.tvTowerDetails).text = "Signal: ${tower.rsrp} dBm (${tower.type})"
            
            val badge = v.findViewById<TextView>(R.id.tvTowerBadge)
            if (tower.isRegistered) {
                badge.visibility = View.VISIBLE
                badge.text = "CONNECTED"
            } else {
                badge.visibility = View.GONE
            }

            v.setOnClickListener {
                etManualPci.setText(tower.pci.toString())
                etManualEarfcn.setText(tower.earfcn.toString())
                Toast.makeText(this, "Selected PCI: ${tower.pci}", Toast.LENGTH_SHORT).show()
            }

            layoutTowerList.addView(v)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateSavedInfo() {
        val prefs = getSharedPreferences("tower_prefs", MODE_PRIVATE)
        val pci = prefs.getInt("home_pci", -1)
        val earfcn = prefs.getInt("home_earfcn", -1)
        val isLocked = prefs.getBoolean("is_tower_locked", false)
        
        if (pci != -1) {
            tvSavedHomeInfo.text = "Saved Home Tower: PCI $pci | EARFCN $earfcn"
        } else {
            tvSavedHomeInfo.text = "Saved Home Tower: Not Configured"
        }

        if (isLocked) {
            val lPci = prefs.getInt("locked_pci", -1)
            val lEarfcn = prefs.getInt("locked_earfcn", -1)
            tvLockStatus.text = "🟢 LOCKED: PCI $lPci | EARFCN $lEarfcn"
            tvLockStatus.setTextColor(Color.YELLOW)
        } else {
            tvLockStatus.text = "🔵 AUTOMATIC ROAMING (OUTDOOR)"
            tvLockStatus.setTextColor(Color.parseColor("#00C853"))
        }
    }

    private fun saveHomeTower() {
        if (currentPci == -1) {
            Toast.makeText(this, "Wait for scanner to detect tower", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("tower_prefs", MODE_PRIVATE).edit().apply {
            putInt("home_pci", currentPci)
            putInt("home_earfcn", currentEarfcn)
            apply()
        }
        updateSavedInfo()
        Toast.makeText(this, "Home Tower Saved!", Toast.LENGTH_SHORT).show()
    }

    private fun lockToHome() {
        val prefs = getSharedPreferences("tower_prefs", MODE_PRIVATE)
        val pci = prefs.getInt("home_pci", -1)
        val earfcn = prefs.getInt("home_earfcn", -1)

        if (pci == -1) {
            Toast.makeText(this, "Please save a Home Tower first", Toast.LENGTH_SHORT).show()
            return
        }

        executeRadioLock(earfcn, pci, "LOCKED TO HOME TOWER")
    }

    private fun releaseLock() {
        scope.launch(Dispatchers.IO) {
            val cmd1 = "echo -e \"AT+ECELL=0\\r\\n\" > /dev/ttyC0 2>/dev/null"
            val cmd2 = "echo -e \"AT+ECELL=0\\r\\n\" > /dev/radio/pttycmd1 2>/dev/null"
            val refresh = "cmd connectivity airplane-mode enable && sleep 1 && cmd connectivity airplane-mode disable"
            
            ShellUtils.runAsRoot(cmd1)
            ShellUtils.runAsRoot(cmd2)
            ShellUtils.runAsRoot(refresh)
            
            getSharedPreferences("tower_prefs", MODE_PRIVATE).edit().apply {
                putBoolean("is_tower_locked", false)
                apply()
            }

            withContext(Dispatchers.Main) {
                updateSavedInfo()
                Toast.makeText(this@HomeTowerLockActivity, "Modem Lock Released (Auto Roaming)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyManual() {
        val pciStr = etManualPci.text.toString()
        val earfcnStr = etManualEarfcn.text.toString()

        if (pciStr.isEmpty() || earfcnStr.isEmpty()) {
            Toast.makeText(this, "Enter PCI and EARFCN", Toast.LENGTH_SHORT).show()
            return
        }

        executeRadioLock(earfcnStr.toInt(), pciStr.toInt(), "MANUAL LOCK ACTIVE")
    }

    private fun executeRadioLock(earfcn: Int, pci: Int, statusText: String) {
        scope.launch(Dispatchers.IO) {
            val cmd1 = "echo -e \"AT+ECELL=1,$earfcn,$pci\\r\\n\" > /dev/ttyC0 2>/dev/null"
            val cmd2 = "echo -e \"AT+ECELL=1,$earfcn,$pci\\r\\n\" > /dev/radio/pttycmd1 2>/dev/null"
            
            ShellUtils.runAsRoot(cmd1)
            ShellUtils.runAsRoot(cmd2)

            getSharedPreferences("tower_prefs", MODE_PRIVATE).edit().apply {
                putBoolean("is_tower_locked", true)
                putInt("locked_pci", pci)
                putInt("locked_earfcn", earfcn)
                apply()
            }

            withContext(Dispatchers.Main) {
                updateSavedInfo()
                Toast.makeText(this@HomeTowerLockActivity, "Baseband Locked to PCI: $pci", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
