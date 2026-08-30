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
import kotlin.concurrent.thread

class AppInspectorActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var etSearch: EditText
    private lateinit var listView: ListView
    private val appList = mutableListOf<AdbAppItem>()
    private lateinit var allApps: List<ApplicationInfo>
    private lateinit var pm: PackageManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_inspector)

        pm = packageManager
        findViewById<MaterialToolbar>(R.id.toolbarInspector).setNavigationOnClickListener { finish() }

        tabLayout = findViewById(R.id.tabLayoutApps)
        etSearch = findViewById(R.id.etSearchApps)
        listView = findViewById(R.id.lvAdbApps)

        allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        updateList(false)

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

    private fun updateList(isSystem: Boolean, query: String = "") {
        appList.clear()
        allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val freezerApps = FreezerManager.getFrozenApps(this)

        for (app in allApps) {
            val sysApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (sysApp != isSystem) continue

            val label = pm.getApplicationLabel(app).toString()
            val pkg = app.packageName

            if (query.isNotEmpty() && !label.lowercase().contains(query) && !pkg.lowercase().contains(query)) continue

            var tag = ""
            if (!app.enabled) {
                tag = "DISABLED"
            } else if (freezerApps.contains(pkg)) {
                tag = if (FreezerManager.isSpecialFreeze(this, pkg)) "FROZEN (SPECIAL)" else "FROZEN"
            } else if ((app.flags and ApplicationInfo.FLAG_STOPPED) != 0) {
                tag = "STOPPED"
            }

            appList.add(AdbAppItem(label, pkg, pm.getApplicationIcon(app), tag, !app.enabled || freezerApps.contains(pkg)))
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

    private fun showAppOptions(item: AdbAppItem) {
        val toggleAction = if (item.isDisabledOrFrozen) "✅ Enable / Unfreeze App" else "🛑 Disable / Freeze App"
        val options = arrayOf("Select for Terminal", toggleAction, "⚡ Force Stop App", "📋 Copy Package Name")

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
                    1 -> {
                        toggleAppStatus(item)
                    }
                    2 -> {
                        thread {
                            ShellUtils.runAsRoot("am force-stop ${item.packageName}")
                            runOnUiThread {
                                Toast.makeText(this, "Force Stopped ${item.name}", Toast.LENGTH_SHORT).show()
                                updateList(tabLayout.selectedTabPosition == 1, etSearch.text.toString().lowercase())
                            }
                        }
                    }
                    3 -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Package Name", item.packageName)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, "Copied: ${item.packageName}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun toggleAppStatus(item: AdbAppItem) {
        thread {
            if (item.isDisabledOrFrozen) {
                // Enable & Unfreeze
                ShellUtils.runAsRoot("pm enable ${item.packageName} 2>/dev/null; pm unsuspend ${item.packageName} 2>/dev/null; am unfreeze ${item.packageName} 2>/dev/null; am set-standby-bucket ${item.packageName} active 2>/dev/null")
                FreezerManager.unfreezeApp(item.packageName)
                runOnUiThread {
                    Toast.makeText(this, "Enabled ${item.name}", Toast.LENGTH_SHORT).show()
                    updateList(tabLayout.selectedTabPosition == 1, etSearch.text.toString().lowercase())
                }
            } else {
                // Disable & Freeze
                ShellUtils.runAsRoot("am force-stop ${item.packageName}; pm disable-user --user 0 ${item.packageName} 2>/dev/null; am freeze ${item.packageName} 2>/dev/null")
                FreezerManager.freezeApp(this, item.packageName)
                runOnUiThread {
                    Toast.makeText(this, "Disabled & Frozen ${item.name}", Toast.LENGTH_SHORT).show()
                    updateList(tabLayout.selectedTabPosition == 1, etSearch.text.toString().lowercase())
                }
            }
        }
    }

    data class AdbAppItem(val name: String, val packageName: String, val icon: Drawable, val tag: String, val isDisabledOrFrozen: Boolean)
}
