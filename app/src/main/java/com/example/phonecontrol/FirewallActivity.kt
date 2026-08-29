package com.example.phonecontrol

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

class FirewallActivity : AppCompatActivity() {

    private lateinit var layoutContainer: LinearLayout
    private lateinit var pm: PackageManager
    private var cachedAppsList: List<ApplicationInfo>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_firewall)

        pm = packageManager
        layoutContainer = findViewById(R.id.layoutFirewallContainer)
        findViewById<MaterialToolbar>(R.id.toolbarFirewall).setNavigationOnClickListener { finish() }
        
        findViewById<Button>(R.id.btnAddFirewallApp).setOnClickListener {
            showAppPicker()
        }

        // Sub-Feature Navigation
        findViewById<View>(R.id.cardTowerLock).setOnClickListener {
            startActivity(Intent(this, HomeTowerLockActivity::class.java))
        }

        val swTcpBbr = findViewById<SwitchMaterial>(R.id.switchTcpBbr)
        val masterPrefs = getSharedPreferences("prefs", MODE_PRIVATE)
        swTcpBbr.isChecked = masterPrefs.getBoolean("tcp_bbr_active", true)
        swTcpBbr.setOnCheckedChangeListener { _, isChecked ->
            masterPrefs.edit().putBoolean("tcp_bbr_active", isChecked).apply()
            thread {
                val algo = if (isChecked) "bbr" else "cubic"
                ShellUtils.runAsRoot("sysctl -w net.ipv4.tcp_congestion_control=$algo")
            }
            Toast.makeText(this, if (isChecked) "TCP BBR Booster Active" else "TCP BBR Disabled", Toast.LENGTH_SHORT).show()
        }

        updateSubCardVisibility()

        thread {
            cachedAppsList = getInstalledAppsList()
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        updateSubCardVisibility()
        refreshList()
    }

    private fun updateSubCardVisibility() {
        val masterPrefs = getSharedPreferences("prefs", MODE_PRIVATE)
        findViewById<View>(R.id.cardTowerLock).visibility = 
            if (masterPrefs.getBoolean("tower_lock_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardNetworkBooster).visibility = 
            if (masterPrefs.getBoolean("network_priority_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.layoutFirewallSection).visibility = 
            if (masterPrefs.getBoolean("firewall_enabled", true)) View.VISIBLE else View.GONE
    }

    private fun getInstalledAppsList(): List<ApplicationInfo> {
        return try {
            val all = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            all.filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pm.getLaunchIntentForPackage(it.packageName) != null
            }.sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun refreshList() {
        layoutContainer.removeAllViews()
        val prefs = getSharedPreferences("firewall_prefs", MODE_PRIVATE)
        val blockedApps = prefs.getStringSet("blocked_packages", emptySet()) ?: emptySet()
        
        if (blockedApps.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No apps blocked."
                setTextColor(Color.GRAY)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 50, 0, 0)
            }
            layoutContainer.addView(tv)
            return
        }

        for (pkg in blockedApps) {
            val view = layoutInflater.inflate(R.layout.item_app_picker, layoutContainer, false)
            val ivIcon = view.findViewById<ImageView>(R.id.ivAppIcon)
            val tvName = view.findViewById<TextView>(R.id.tvAppName)
            val tvPkg = view.findViewById<TextView>(R.id.tvPackageName)
            val tvStatus = view.findViewById<TextView>(R.id.tvAppStatus)
            
            view.findViewById<View>(R.id.cbSelect).visibility = View.GONE

            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                ivIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
                tvName.text = pm.getApplicationLabel(appInfo)
            } catch (e: Exception) {
                tvName.text = "Unknown App"
            }
            
            tvPkg.text = pkg
            tvStatus.text = "INTERNET BLOCKED"
            tvStatus.setTextColor(Color.RED)
            tvStatus.visibility = View.VISIBLE

            view.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Unblock App")
                    .setMessage("Restore internet access for $pkg?")
                    .setPositiveButton("Unblock") { _, _ ->
                        unblockApp(pkg)
                        refreshList()
                    }
                    .setNegativeButton("Cancel", null).show()
                true
            }

            layoutContainer.addView(view)
        }
    }

    private fun blockApp(packageName: String) {
        val prefs = getSharedPreferences("firewall_prefs", MODE_PRIVATE)
        val current = prefs.getStringSet("blocked_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(packageName)
        prefs.edit().putStringSet("blocked_packages", current).apply()
        
        thread {
            try {
                val uid = pm.getApplicationInfo(packageName, 0).uid
                TweakManager.setFirewallRule(uid, true)
            } catch (e: Exception) {}
        }
    }

    private fun unblockApp(packageName: String) {
        val prefs = getSharedPreferences("firewall_prefs", MODE_PRIVATE)
        val current = prefs.getStringSet("blocked_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(packageName)
        prefs.edit().putStringSet("blocked_packages", current).apply()
        
        thread {
            try {
                val uid = pm.getApplicationInfo(packageName, 0).uid
                TweakManager.setFirewallRule(uid, false)
            } catch (e: Exception) {}
        }
    }

    private fun showAppPicker() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearchApp)
        dialogView.findViewById<View>(R.id.spinnerFilter).visibility = View.GONE
        dialogView.findViewById<View>(R.id.cbSelectAll).visibility = View.GONE
        val listView = dialogView.findViewById<ListView>(R.id.lvApps)

        val allApps = cachedAppsList ?: getInstalledAppsList()
        val filteredApps = allApps.toMutableList()
        val adapter = object : ArrayAdapter<ApplicationInfo>(this, R.layout.item_app_picker, filteredApps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_app_picker, parent, false)
                val app = getItem(position)!!
                view.findViewById<CheckBox>(R.id.cbSelect).visibility = View.GONE
                view.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(pm.getApplicationIcon(app))
                view.findViewById<TextView>(R.id.tvAppName).text = pm.getApplicationLabel(app)
                view.findViewById<TextView>(R.id.tvPackageName).text = app.packageName
                return view
            }
        }
        listView.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filteredApps.clear()
                filteredApps.addAll(allApps.filter { pm.getApplicationLabel(it).toString().lowercase().contains(s.toString().lowercase()) || it.packageName.lowercase().contains(s.toString().lowercase()) })
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        listView.setOnItemClickListener { _, _, pos, _ -> 
            blockApp(filteredApps[pos].packageName)
            refreshList()
            dialog.dismiss() 
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.8).toInt())
    }
}
