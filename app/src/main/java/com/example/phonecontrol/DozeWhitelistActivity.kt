package com.example.phonecontrol

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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

class DozeWhitelistActivity : AppCompatActivity() {

    private lateinit var layoutDozeWhitelist: LinearLayout
    private lateinit var pm: PackageManager
    private var cachedAppsList: List<ApplicationInfo>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doze_whitelist)

        pm = packageManager
        layoutDozeWhitelist = findViewById(R.id.layoutDozeWhitelist)
        val switchEnabled = findViewById<SwitchMaterial>(R.id.switchForceDozeEnabled)

        findViewById<MaterialToolbar>(R.id.toolbarDoze).setNavigationOnClickListener { finish() }
        findViewById<Button>(R.id.btnAddDozeWhitelist).setOnClickListener { showAppPicker() }

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        switchEnabled.isChecked = prefs.getBoolean("batt_force_doze_enabled", false)
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("batt_force_doze_enabled", isChecked).apply()
        }

        thread {
            cachedAppsList = pm.getInstalledApplications(0)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        }

        refreshList()
    }

    private fun refreshList() {
        layoutDozeWhitelist.removeAllViews()
        val whitelist = getWhitelist()
        
        for (pkg in whitelist) {
            val view = layoutInflater.inflate(R.layout.item_app_picker, layoutDozeWhitelist, false)
            view.findViewById<CheckBox>(R.id.cbSelect).visibility = View.GONE
            
            val tvName = view.findViewById<TextView>(R.id.tvAppName)
            val tvPkg = view.findViewById<TextView>(R.id.tvPackageName)
            val ivIcon = view.findViewById<ImageView>(R.id.ivAppIcon)

            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                tvName.text = pm.getApplicationLabel(appInfo)
                ivIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
            } catch (e: Exception) {
                tvName.text = "Unknown App"
            }
            tvPkg.text = pkg

            view.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle("Remove from Whitelist")
                    .setMessage("Remove $pkg from Doze Whitelist?")
                    .setPositiveButton("Remove") { _, _ ->
                        removeFromWhitelist(pkg)
                        refreshList()
                    }
                    .setNegativeButton("Cancel", null).show()
                true
            }
            layoutDozeWhitelist.addView(view)
        }
    }

    private fun getWhitelist(): Set<String> {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        return prefs.getStringSet("doze_whitelist", emptySet()) ?: emptySet()
    }

    private fun addToWhitelist(pkg: String) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val current = prefs.getStringSet("doze_whitelist", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(pkg)
        prefs.edit().putStringSet("doze_whitelist", current).apply()
        // Command to whitelist in system
        ShellUtils.runAsRoot("dumpsys deviceidle whitelist +$pkg")
    }

    private fun removeFromWhitelist(pkg: String) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val current = prefs.getStringSet("doze_whitelist", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(pkg)
        prefs.edit().putStringSet("doze_whitelist", current).apply()
        // Command to remove from system whitelist
        ShellUtils.runAsRoot("dumpsys deviceidle whitelist -$pkg")
    }

    private fun showAppPicker() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearchApp)
        dialogView.findViewById<View>(R.id.spinnerFilter).visibility = View.GONE
        dialogView.findViewById<View>(R.id.cbSelectAll).visibility = View.GONE
        val listView = dialogView.findViewById<ListView>(R.id.lvApps)

        val allApps = cachedAppsList ?: emptyList()
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
                filteredApps.addAll(allApps.filter { pm.getApplicationLabel(it).toString().lowercase().contains(s.toString().lowercase()) })
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        listView.setOnItemClickListener { _, _, pos, _ -> 
            addToWhitelist(filteredApps[pos].packageName)
            refreshList()
            dialog.dismiss() 
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.8).toInt())
    }
}
