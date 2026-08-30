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

class DozeWhitelistActivity : AppCompatActivity() {

    private lateinit var layoutDozeWhitelist: LinearLayout
    private lateinit var pm: PackageManager
    private var cachedAppsList: List<ApplicationInfo>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doze_whitelist)

        pm = packageManager
        layoutDozeWhitelist = findViewById(R.id.layoutDozeWhitelist)

        findViewById<MaterialToolbar>(R.id.toolbarDoze).setNavigationOnClickListener { finish() }
        findViewById<Button>(R.id.btnAddDozeWhitelist).setOnClickListener { showAppPicker() }

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
        layoutDozeWhitelist.removeAllViews()
        val whitelist = MultitaskingManager.getUserWhitelist(this)
        
        if (whitelist.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No custom apps whitelisted.\nTap button above to protect Key Mapper, WhatsApp, or any essential app."
                setTextColor(Color.GRAY)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 50, 0, 0)
            }
            layoutDozeWhitelist.addView(tv)
            return
        }

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
                    .setMessage("Remove $pkg from Universal Protected Whitelist?")
                    .setPositiveButton("Remove") { _, _ ->
                        MultitaskingManager.removeAppFromWhitelist(this, pkg)
                        refreshList()
                    }
                    .setNegativeButton("Cancel", null).show()
                true
            }
            layoutDozeWhitelist.addView(view)
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
            MultitaskingManager.addAppToWhitelist(this, pkg)
            refreshList()
            dialog.dismiss() 
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.8).toInt())
    }
}
