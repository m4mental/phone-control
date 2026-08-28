package com.example.phonecontrol

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
import kotlin.concurrent.thread

class ScalingWhitelistActivity : AppCompatActivity() {

    private lateinit var layoutContainer: LinearLayout
    private lateinit var pm: PackageManager
    private var cachedAppsList: List<ApplicationInfo>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scaling_whitelist)

        pm = packageManager
        layoutContainer = findViewById(R.id.layoutScalingContainer)
        findViewById<MaterialToolbar>(R.id.toolbarScalingWhitelist).setNavigationOnClickListener { finish() }
        
        findViewById<Button>(R.id.btnAddScalingApp).setOnClickListener {
            showAppPicker()
        }

        thread {
            cachedAppsList = getInstalledAppsList()
        }

        refreshList()
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
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val whitelist = prefs.getStringSet("scaling_whitelist", emptySet()) ?: emptySet()
        
        if (whitelist.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No apps in scaling list."
                setTextColor(Color.GRAY)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 50, 0, 0)
            }
            layoutContainer.addView(tv)
            return
        }

        for (pkg in whitelist) {
            val view = layoutInflater.inflate(R.layout.item_app_picker, layoutContainer, false)
            val ivIcon = view.findViewById<ImageView>(R.id.ivAppIcon)
            val tvName = view.findViewById<TextView>(R.id.tvAppName)
            val tvPkg = view.findViewById<TextView>(R.id.tvPackageName)
            
            view.findViewById<View>(R.id.cbSelect).visibility = View.GONE

            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                ivIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
                tvName.text = pm.getApplicationLabel(appInfo)
            } catch (e: Exception) {
                tvName.text = "Unknown App"
            }
            
            tvPkg.text = pkg

            view.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Remove from List")
                    .setMessage("Remove $pkg from scaling list?")
                    .setPositiveButton("Remove") { _, _ ->
                        val current = prefs.getStringSet("scaling_whitelist", emptySet())?.toMutableSet() ?: mutableSetOf()
                        current.remove(pkg)
                        prefs.edit().putStringSet("scaling_whitelist", current).apply()
                        refreshList()
                    }
                    .setNegativeButton("Cancel", null).show()
                true
            }

            layoutContainer.addView(view)
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
            val pkg = filteredApps[pos].packageName
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            val current = prefs.getStringSet("scaling_whitelist", emptySet())?.toMutableSet() ?: mutableSetOf()
            current.add(pkg)
            prefs.edit().putStringSet("scaling_whitelist", current).apply()
            refreshList()
            dialog.dismiss() 
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.8).toInt())
    }
}
