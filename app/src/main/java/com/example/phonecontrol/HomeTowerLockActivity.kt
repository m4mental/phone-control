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
    private lateinit var tvSavedHomeInfo: TextView
    private lateinit var tvLockStatus: TextView
    private lateinit var layoutTowerList: LinearLayout
    private lateinit var swPersistent: SwitchMaterial
    private lateinit var sw5gAntiSleep: SwitchMaterial
    private lateinit var etManualPci: EditText
    private lateinit var etManualEarfcn: EditText

    private var currentPci = -1
    private var currentEarfcn = -1
    
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_tower_lock)

        findViewById<MaterialToolbar>(R.id.toolbarTower).setNavigationOnClickListener { finish() }

        layoutTowerList = findViewById(R.id.layoutTowerList)
        tvSavedHomeInfo = findViewById(R.id.tvSavedHomeInfo)
        tvLockStatus = findViewById(R.id.tvLockStatus)
        swPersistent = findViewById(R.id.swPersistentLock)
        sw5gAntiSleep = findViewById(R.id.sw5gAntiSleep)
        etManualPci = findViewById(R.id.etManualPci)
        etManualEarfcn = findViewById(R.id.etManualEarfcn)

        val towerPrefs = getSharedPreferences("tower_prefs", MODE_PRIVATE)
        swPersistent.isChecked = towerPrefs.getBoolean("persistent_lock_enabled", false)
        sw5gAntiSleep.isChecked = towerPrefs.getBoolean("5g_antisleep_enabled", false)

        swPersistent.setOnCheckedChangeListener { _, isChecked ->
            towerPrefs.edit().putBoolean("persistent_lock_enabled", isChecked).apply()
            DaemonManager.startDaemon(this)
            Toast.makeText(this, "Persistence logic updated", Toast.LENGTH_SHORT).show()
        }

        sw5gAntiSleep.setOnCheckedChangeListener { _, isChecked ->
            towerPrefs.edit().putBoolean("5g_antisleep_enabled", isChecked).apply()
            DaemonManager.startDaemon(this)
            val msg = if (isChecked) "5G Anti-Sleep Active" else "5G Power Save Restored"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }


        findViewById<MaterialButton>(R.id.btnSaveHomeTower).setOnClickListener { saveHomeTower() }
        findViewById<MaterialButton>(R.id.btnLockHome).setOnClickListener { lockToHome() }
        findViewById<MaterialButton>(R.id.btnReleaseLock).setOnClickListener { releaseLock() }
        findViewById<MaterialButton>(R.id.btnApplyManualLock).setOnClickListener { applyManual() }
        findViewById<MaterialButton>(R.id.btnManualScan).setOnClickListener { 
            Toast.makeText(this, "Scanning nearby towers...", Toast.LENGTH_SHORT).show()
            updateTowerInfo() 
        }

        updateSavedInfo()
        // Removed startLiveScanner() from onCreate to save battery
    }


    @SuppressLint("SetTextI18n")
    private fun updateTowerInfo() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE), 100)
            return
        }

        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val cellInfoList = tm.allCellInfo ?: return
        val towerList = mutableListOf<TowerInfo>()

        for (info in cellInfoList) {
            val isRegistered = info.isRegistered
            if (info is CellInfoLte) {
                val identity = info.cellIdentity
                val pci = identity.pci
                val earfcn = identity.earfcn
                if (pci == 2147483647 || earfcn == 2147483647) continue // Filter invalid
                
                val rsrp = info.cellSignalStrength.rsrp
                towerList.add(TowerInfo(pci, earfcn, rsrp, "LTE", isRegistered))
            } else if (info is CellInfoNr) {
                val identity = info.cellIdentity as CellIdentityNr
                val pci = identity.pci
                val earfcn = identity.nrarfcn
                if (pci == 2147483647 || earfcn == 2147483647) continue // Filter invalid
                
                val rsrp = info.cellSignalStrength.dbm
                towerList.add(TowerInfo(pci, earfcn, rsrp, "5G NR", isRegistered))
            }
        }

        // Sort by Signal (Descending) and take top 3
        val top3 = towerList.sortedByDescending { it.rsrp }.distinctBy { "${it.pci}_${it.earfcn}" }.take(3)
        
        runOnUiThread { renderTowerList(top3) }
    }

    private fun renderTowerList(towers: List<TowerInfo>) {
        layoutTowerList.removeAllViews()
        val inflater = LayoutInflater.from(this)

        if (towers.isEmpty()) {
            val tv = TextView(this)
            tv.text = "No towers detected. Checking..."
            tv.setTextColor(Color.GRAY)
            layoutTowerList.addView(tv)
            return
        }

        towers.forEachIndexed { index, tower ->
            val v = inflater.inflate(R.layout.item_tower_info, layoutTowerList, false)
            v.findViewById<TextView>(R.id.tvTowerRank).text = (index + 1).toString()
            v.findViewById<TextView>(R.id.tvTowerMainInfo).text = "PCI: ${tower.pci} | EARFCN: ${tower.earfcn}"
            v.findViewById<TextView>(R.id.tvTowerDetails).text = "Signal: ${tower.rsrp} dBm (${tower.type})"
            
            if (tower.isActive) {
                v.findViewById<TextView>(R.id.tvTowerBadge).visibility = View.VISIBLE
                currentPci = tower.pci
                currentEarfcn = tower.earfcn
            }
            layoutTowerList.addView(v)
        }
    }

    data class TowerInfo(val pci: Int, val earfcn: Int, val rsrp: Int, val type: String, val isActive: Boolean)

    private fun getLteBand(earfcn: Int): String {
        return when {
            earfcn in 0..599 -> "B1"
            earfcn in 600..1199 -> "B2"
            earfcn in 1200..1949 -> "B3"
            earfcn in 1950..2399 -> "B4"
            earfcn in 2400..2649 -> "B5"
            earfcn in 2750..3449 -> "B7"
            earfcn in 3450..3799 -> "B8"
            earfcn in 6150..6449 -> "B20"
            earfcn in 38650..39649 -> "B40"
            earfcn in 40620..41589 -> "B41"
            else -> "B?"
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateSavedInfo() {
        val prefs = getSharedPreferences("tower_prefs", MODE_PRIVATE)
        val pci = prefs.getInt("home_pci", -1)
        val earfcn = prefs.getInt("home_earfcn", -1)
        val isLocked = prefs.getBoolean("is_tower_locked", false)
        
        if (pci != -1) {
            tvSavedHomeInfo.text = "Saved: PCI $pci | EARFCN $earfcn"
        } else {
            tvSavedHomeInfo.text = "Saved: Not Configured"
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
            val cmd = "echo -e \"AT+ECELL=0\\r\\n\" > /dev/radio/pttycmd1"
            val refresh = "cmd connectivity airplane-mode enable && sleep 2 && cmd connectivity airplane-mode disable"
            
            ShellUtils.runAsRoot(cmd)
            ShellUtils.runAsRoot(refresh)
            
            getSharedPreferences("tower_prefs", MODE_PRIVATE).edit().apply {
                putBoolean("is_tower_locked", false)
                apply()
            }

            withContext(Dispatchers.Main) {
                tvLockStatus.text = "🔵 AUTOMATIC ROAMING (OUTDOOR)"
                tvLockStatus.setTextColor(Color.parseColor("#00C853"))
                Toast.makeText(this@HomeTowerLockActivity, "Modem Lock Released", Toast.LENGTH_SHORT).show()
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
            // 1. Send the lock command
            val cmd = "echo -e \"AT+ECELL=1,$earfcn,$pci\\r\\n\" > /dev/radio/pttycmd1"
            ShellUtils.runAsRoot(cmd)

            // 2. Force Modem Full Reset (AT+CFUN=1,1) to clear cell memory and force re-scan
            // This is the "Hard Lock" part that prevents the modem from ignoring the command.
            ShellUtils.runAsRoot("echo -e \"AT+CFUN=1,1\\r\\n\" > /dev/radio/pttycmd1")
            
            // 3. System Radio Refresh
            val refresh = "cmd connectivity airplane-mode enable && sleep 3 && cmd connectivity airplane-mode disable"
            ShellUtils.runAsRoot(refresh)
            
            getSharedPreferences("tower_prefs", MODE_PRIVATE).edit().apply {
                putBoolean("is_tower_locked", true)
                putInt("locked_pci", pci)
                putInt("locked_earfcn", earfcn)
                apply()
            }

            withContext(Dispatchers.Main) {
                tvLockStatus.text = "🟢 $statusText"
                tvLockStatus.setTextColor(Color.YELLOW)
                Toast.makeText(this@HomeTowerLockActivity, "Modem Hard-Locked to PCI: $pci", Toast.LENGTH_LONG).show()
                DaemonManager.startDaemon(this@HomeTowerLockActivity)
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
