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
    private lateinit var progressBar: ProgressBar
    private val displayedList = mutableListOf<AdbAppItem>()
    private val userAppsCache = mutableListOf<AdbAppItem>()
    private val systemAppsCache = mutableListOf<AdbAppItem>()
    private val disabledAppsCache = mutableListOf<AdbAppItem>()
    private var adapter: ArrayAdapter<AdbAppItem>? = null
    private lateinit var pm: PackageManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_inspector)

        pm = packageManager
        findViewById<MaterialToolbar>(R.id.toolbarInspector).setNavigationOnClickListener { finish() }

        tabLayout = findViewById(R.id.tabLayoutApps)
        etSearch = findViewById(R.id.etSearchApps)
        listView = findViewById(R.id.lvAdbApps)
        progressBar = findViewById(R.id.pbLoadingApps)

        adapter = object : ArrayAdapter<AdbAppItem>(this, R.layout.item_adb_app, displayedList) {
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
                    when (item.tag) {
                        "HIDDEN" -> tvTag.setTextColor(android.graphics.Color.parseColor("#FF1744"))
                        "DISABLED" -> tvTag.setTextColor(android.graphics.Color.parseColor("#FF9100"))
                        "FROZEN", "FROZEN (SPECIAL)" -> tvTag.setTextColor(android.graphics.Color.parseColor("#00E5FF"))
                        "SUSPENDED" -> tvTag.setTextColor(android.graphics.Color.parseColor("#E040FB"))
                        else -> tvTag.setTextColor(android.graphics.Color.parseColor("#888888"))
                    }
                } else {
                    tvTag.visibility = View.GONE
                }
                return v
            }
        }
        listView.adapter = adapter

        loadAppsInBackground()

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                filterList(tab?.position ?: 0, etSearch.text.toString().trim().lowercase())
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filterList(tabLayout.selectedTabPosition, s.toString().trim().lowercase())
            }
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in displayedList.indices) {
                val item = displayedList[position]
                showAppOptions(item)
            }
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (position in displayedList.indices) {
                val pkg = displayedList[position].packageName
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Package Name", pkg)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Copied: $pkg", Toast.LENGTH_SHORT).show()
                true
            } else false
        }
    }

    private fun loadAppsInBackground() {
        progressBar.visibility = View.VISIBLE
        thread {
            val flags = PackageManager.GET_META_DATA or
                    PackageManager.MATCH_UNINSTALLED_PACKAGES or
                    PackageManager.MATCH_DISABLED_COMPONENTS or
                    PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
            val apps = pm.getInstalledApplications(flags)
            val freezerApps = FreezerManager.getFrozenApps(this)

            // Query root disabled / hidden list to catch all hidden or disabled packages
            val rootDisabledList = ShellUtils.fastCmdResult("pm list packages -d -u 2>/dev/null").lines()
                .map { it.replace("package:", "").trim() }
                .filter { it.isNotBlank() }
                .toSet()

            val tempUser = mutableListOf<AdbAppItem>()
            val tempSys = mutableListOf<AdbAppItem>()
            val tempDisabled = mutableListOf<AdbAppItem>()

            for (app in apps) {
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val label = try { pm.getApplicationLabel(app).toString() } catch (e: Exception) { app.packageName }
                val pkg = app.packageName
                val icon = try { pm.getApplicationIcon(app) } catch (e: Exception) { pm.defaultActivityIcon }

                // Check privateFlags for PRIVATE_FLAG_HIDDEN (1 << 0)
                val isHidden = try {
                    val pFlagsField = ApplicationInfo::class.java.getField("privateFlags")
                    val pFlags = pFlagsField.getInt(app)
                    (pFlags and 1) != 0
                } catch (e: Exception) {
                    false
                }
                val isSuspended = (app.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
                val isRootDisabled = rootDisabledList.contains(pkg)
                val isDisabled = !app.enabled || isRootDisabled
                val isFrozen = freezerApps.contains(pkg)

                val tag = when {
                    isHidden -> "HIDDEN"
                    isDisabled -> "DISABLED"
                    isFrozen -> if (FreezerManager.isSpecialFreeze(this, pkg)) "FROZEN (SPECIAL)" else "FROZEN"
                    isSuspended -> "SUSPENDED"
                    (app.flags and ApplicationInfo.FLAG_STOPPED) != 0 -> "STOPPED"
                    else -> ""
                }

                val isInactive = isHidden || isDisabled || isFrozen || isSuspended
                val item = AdbAppItem(label, pkg, icon, tag, isInactive)
                if (isSystem) tempSys.add(item) else tempUser.add(item)
                if (isInactive) tempDisabled.add(item)
            }

            tempUser.sortBy { it.name.lowercase() }
            tempSys.sortBy { it.name.lowercase() }
            tempDisabled.sortBy { it.name.lowercase() }

            runOnUiThread {
                userAppsCache.clear()
                userAppsCache.addAll(tempUser)
                systemAppsCache.clear()
                systemAppsCache.addAll(tempSys)
                disabledAppsCache.clear()
                disabledAppsCache.addAll(tempDisabled)
                progressBar.visibility = View.GONE
                filterList(tabLayout.selectedTabPosition, etSearch.text.toString().trim().lowercase())
            }
        }
    }

    private fun filterList(tabPosition: Int, query: String) {
        val source = when (tabPosition) {
            1 -> systemAppsCache
            2 -> disabledAppsCache
            else -> userAppsCache
        }
        val filtered = if (query.isEmpty()) {
            source
        } else {
            source.filter {
                it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        }
        displayedList.clear()
        displayedList.addAll(filtered)
        adapter?.notifyDataSetChanged()
    }

    private fun showAppOptions(item: AdbAppItem) {
        val toggleAction = if (item.isDisabledOrFrozen) "✅ Enable & Unhide App" else "🛑 Disable & Hide App"
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
                                loadAppsInBackground()
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
                // Enable & Unfreeze & Unhide
                val cmd = "pm default-state --user 0 ${item.packageName} 2>/dev/null; pm unhide ${item.packageName} 2>/dev/null; pm enable ${item.packageName} 2>/dev/null; pm unsuspend ${item.packageName} 2>/dev/null; am unfreeze ${item.packageName} 2>/dev/null; am set-standby-bucket ${item.packageName} active 2>/dev/null"
                ShellUtils.runAsRoot(cmd)
                FreezerManager.unfreezeApp(item.packageName)
                runOnUiThread {
                    Toast.makeText(this, "Enabled & Restored ${item.name}", Toast.LENGTH_SHORT).show()
                    loadAppsInBackground()
                }
            } else {
                // Disable & Freeze & Hide
                val cmd = "am force-stop ${item.packageName}; pm disable-user --user 0 ${item.packageName} 2>/dev/null; pm hide ${item.packageName} 2>/dev/null; am freeze ${item.packageName} 2>/dev/null"
                ShellUtils.runAsRoot(cmd)
                FreezerManager.freezeApp(this, item.packageName)
                runOnUiThread {
                    Toast.makeText(this, "Disabled & Hidden ${item.name}", Toast.LENGTH_SHORT).show()
                    loadAppsInBackground()
                }
            }
        }
    }

    data class AdbAppItem(val name: String, val packageName: String, val icon: Drawable, val tag: String, val isDisabledOrFrozen: Boolean)
}
