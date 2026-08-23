package com.example.phonecontrol

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout

class AppInspectorActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var etSearch: EditText
    private lateinit var listView: ListView
    private val appList = mutableListOf<AdbAppItem>()
    private lateinit var allApps: List<ApplicationInfo>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_inspector)

        findViewById<MaterialToolbar>(R.id.toolbarInspector).setNavigationOnClickListener { finish() }

        tabLayout = findViewById(R.id.tabLayoutApps)
        etSearch = findViewById(R.id.etSearchApps)
        listView = findViewById(R.id.lvAdbApps)

        val pm = packageManager
        allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        fun updateList(isSystem: Boolean, query: String = "") {
            appList.clear()
            for (app in allApps) {
                val sysApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (sysApp != isSystem) continue
                
                val label = pm.getApplicationLabel(app).toString()
                val pkg = app.packageName
                
                if (query.isNotEmpty() && !label.lowercase().contains(query) && !pkg.lowercase().contains(query)) continue

                // Detect Status
                var tag = ""
                val freezerApps = FreezerManager.getFrozenApps(this)
                
                if (!app.enabled) tag = "DISABLED"
                else if (freezerApps.contains(pkg)) {
                    tag = if (FreezerManager.isSpecialFreeze(this, pkg)) "FROZEN (SPECIAL)" else "FROZEN"
                } else if ((app.flags and ApplicationInfo.FLAG_STOPPED) != 0) tag = "STOPPED"
                else if ((app.flags and (1 shl 27)) != 0) tag = "HIDDEN" // FLAG_INSTALLED check (approx)

                appList.add(AdbAppItem(label, pkg, pm.getApplicationIcon(app), tag))
            }
            appList.sortBy { it.name.lowercase() }
            
            val adapter = object : ArrayAdapter<AdbAppItem>(this, R.layout.item_adb_app, appList) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = convertView ?: layoutInflater.inflate(R.layout.item_adb_app, parent, false)
                    val item = getItem(position)!!
                    v.findViewById<ImageView>(R.id.ivAdbAppIcon).setImageDrawable(item.icon)
                    v.findViewById<TextView>(R.id.tvAdbAppName).text = item.name
                    v.findViewById<TextView>(R.id.tvAdbAppPackage).text = item.packageName
                    val tvTag = v.findViewById<TextView>(R.id.tvAdbAppTag)
                    if (item.tag.isNotEmpty()) {
                        tvTag.text = item.tag
                        tvTag.visibility = View.VISIBLE
                    } else {
                        tvTag.visibility = View.GONE
                    }
                    return v
                }
            }
            listView.adapter = adapter
        }

        updateList(false) // Initial: User Apps

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                updateList(tab?.position == 1, etSearch.text.toString().lowercase())
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                updateList(tabLayout.selectedTabPosition == 1, s.toString().lowercase())
            }
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val item = appList[position]
            showAppOptions(item)
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val pkg = appList[position].packageName
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Package Name", pkg)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied: $pkg", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun showAppOptions(item: AdbAppItem) {
        val options = arrayOf("Select for Terminal", "Backup App & Data", "Copy Package Name")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val data = Intent()
                        data.putExtra("package_name", item.packageName)
                        setResult(RESULT_OK, data)
                        finish()
                    }
                    1 -> showBackupDialog(item)
                    2 -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Package Name", item.packageName)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showBackupDialog(item: AdbAppItem) {
        val et = EditText(this)
        et.hint = "Add a custom note (e.g. Stable version)"
        
        AlertDialog.Builder(this)
            .setTitle("Backup ${item.name}")
            .setMessage("Full APK and Data backup. This may take a while for large apps.")
            .setView(et)
            .setPositiveButton("START BACKUP") { _, _ ->
                val notes = et.text.toString()
                val masterPath = BackupManager.getAutoVaultPath()
                
                val intent = Intent(this, BackupService::class.java).apply {
                    action = "ACTION_BACKUP"
                    putExtra("package_name", item.packageName)
                    putExtra("app_name", item.name)
                    putExtra("master_path", masterPath)
                    putExtra("notes", notes)
                }
                startForegroundService(intent)
                Toast.makeText(this, "Backup started in background", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    data class AdbAppItem(val name: String, val packageName: String, val icon: Drawable, val tag: String)
}
