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
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

class PerAppActivity : AppCompatActivity() {

    private lateinit var layoutPerAppList: LinearLayout
    private lateinit var pm: PackageManager
    private var cachedAppsList: List<ApplicationInfo>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_per_app)

        pm = packageManager
        layoutPerAppList = findViewById(R.id.layoutPerAppList)
        findViewById<MaterialToolbar>(R.id.toolbarPerApp).setNavigationOnClickListener { finish() }
        findViewById<Button>(R.id.btnAddPerApp).setOnClickListener { showPerAppPicker() }

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
        layoutPerAppList.removeAllViews()
        val configs = PerAppManager.getAllConfigs(this)
        
        if (configs.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No app overrides set."
                setTextColor(Color.GRAY)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 50, 0, 0)
            }
            layoutPerAppList.addView(tv)
            return
        }

        for ((pkg, data) in configs) {
            val packageName = pkg.toString()
            val config = PerAppManager.getConfig(this, packageName) ?: continue

            val view = layoutInflater.inflate(R.layout.item_active_override, layoutPerAppList, false)
            
            val ivIcon = view.findViewById<ImageView>(R.id.ivAppIcon)
            val tvName = view.findViewById<TextView>(R.id.tvAppName)
            val tvSummary = view.findViewById<TextView>(R.id.tvSummary)
            val ivArrow = view.findViewById<ImageView>(R.id.ivExpandArrow)
            val tvLiveBadge = view.findViewById<TextView>(R.id.tvLiveBadge)
            val layoutHeader = view.findViewById<View>(R.id.layoutHeader)
            val layoutConfig = view.findViewById<View>(R.id.layoutConfig)

            // Setup Header Info
            try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                tvName.text = pm.getApplicationLabel(appInfo)
                ivIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
            } catch (e: Exception) {
                tvName.text = "Unknown App"
            }
            
            // Phase 6: Show LIVE badge if running
            thread {
                val topApp = ShellUtils.runAsRoot("dumpsys window | grep mCurrentFocus").output
                if (topApp.contains(packageName)) {
                    runOnUiThread { tvLiveBadge.visibility = View.VISIBLE }
                }
            }
            
            fun updateSummaryLabel(c: PerAppManager.AppConfig) {
                val extras = mutableListOf<String>()
                if (c.bypassCharging) extras.add("🔌 Bypass")
                if (c.autoDnd) extras.add("🔕 DND")
                val extraTag = if (extras.isNotEmpty()) " | " + extras.joinToString(" • ") else ""
                tvSummary.text = "${c.mode} | ${c.fps} | Thermal: ${c.thermal} | Touch: ${c.touch}$extraTag"
            }
            updateSummaryLabel(config)

            // Setup Expand/Collapse
            layoutHeader.setOnClickListener {
                if (layoutConfig.visibility == View.VISIBLE) {
                    layoutConfig.visibility = View.GONE
                    ivArrow.rotation = 0f
                } else {
                    layoutConfig.visibility = View.VISIBLE
                    ivArrow.rotation = 180f
                }
            }

            // Setup Config Controls inside item
            val rgMode = view.findViewById<RadioGroup>(R.id.rgMode)
            val rgFps = view.findViewById<RadioGroup>(R.id.rgFps)
            val swThermal = view.findViewById<SwitchMaterial>(R.id.switchThermal)
            val swTouch = view.findViewById<SwitchMaterial>(R.id.switchTouch)
            val swBypass = view.findViewById<SwitchMaterial>(R.id.switchBypass)
            val swDnd = view.findViewById<SwitchMaterial>(R.id.switchDnd)
            val btnRemove = view.findViewById<Button>(R.id.btnRemove)

            // Initial Radio Selection
            when (config.mode) {
                "Auto" -> rgMode.check(R.id.rbModeAuto)
                "Power Saver" -> rgMode.check(R.id.rbModeSaver)
                "Performance" -> rgMode.check(R.id.rbModePerf)
                else -> rgMode.check(R.id.rbModeBalance)
            }
            when (config.fps) {
                "30Hz" -> rgFps.check(R.id.rbFps30)
                "60Hz" -> rgFps.check(R.id.rbFps60)
                "90Hz" -> rgFps.check(R.id.rbFps90)
                "120Hz" -> rgFps.check(R.id.rbFps120)
                else -> rgFps.check(R.id.rbFpsAuto)
            }
            swThermal.isChecked = config.thermal == "Disabled"
            swTouch.isChecked = config.touch == "On"
            swBypass.isChecked = config.bypassCharging
            swDnd.isChecked = config.autoDnd

            // Save on change logic
            val onConfigChange = {
                val mode = when (rgMode.checkedRadioButtonId) {
                    R.id.rbModeAuto -> "Auto"
                    R.id.rbModeSaver -> "Power Saver"
                    R.id.rbModePerf -> "Performance"
                    else -> "Balance"
                }
                val fps = when (rgFps.checkedRadioButtonId) {
                    R.id.rbFps30 -> "30Hz"
                    R.id.rbFps60 -> "60Hz"
                    R.id.rbFps90 -> "90Hz"
                    R.id.rbFps120 -> "120Hz"
                    else -> "Auto Switch"
                }
                val thermal = if (swThermal.isChecked) "Disabled" else "Default"
                val touch = if (swTouch.isChecked) "On" else "Off"
                val bypass = swBypass.isChecked
                val dnd = swDnd.isChecked
                
                val newConfig = PerAppManager.AppConfig(mode, fps, thermal, touch, bypass, dnd)
                PerAppManager.saveConfig(this, packageName, newConfig)
                updateSummaryLabel(newConfig)
                
                // Live Tuning - Apply if app is currently in foreground
                thread {
                    val topApp = ShellUtils.runAsRoot("dumpsys window | grep mCurrentFocus").output
                    if (topApp.contains(packageName)) {
                        if (mode != "Auto") TweakManager.applyGlobalMode(mode)
                        if (fps != "Auto Switch") TweakManager.setRefreshRate(fps)
                        if (thermal == "Disabled") ThermalManager.setThrottlingEnabled(false) else ThermalManager.setThrottlingEnabled(true)
                        if (touch == "On") TweakManager.applyInputBoost(true)
                        if (bypass) BatteryManager.setBypassCharging(this, true)
                        if (dnd) ShellUtils.fastCmd("cmd notification set_zen_mode 1")
                        if (mode == "Performance") TweakManager.applyProcessPriority(packageName, true)
                    }
                }
            }

            rgMode.setOnCheckedChangeListener { _, _ -> onConfigChange() }
            rgFps.setOnCheckedChangeListener { _, _ -> onConfigChange() }
            swThermal.setOnCheckedChangeListener { _, _ -> onConfigChange() }
            swTouch.setOnCheckedChangeListener { _, _ -> onConfigChange() }
            swBypass.setOnCheckedChangeListener { _, _ -> onConfigChange() }
            swDnd.setOnCheckedChangeListener { _, _ -> onConfigChange() }

            btnRemove.setOnClickListener {
                AlertDialog.Builder(this).setTitle("Remove Smart Rule")
                    .setMessage("Remove automation profile for ${tvName.text}?")
                    .setPositiveButton("Remove") { _, _ ->
                        PerAppManager.removeConfig(this, packageName)
                        refreshList()
                    }
                    .setNegativeButton("Cancel", null).show()
            }

            layoutPerAppList.addView(view)
        }
    }

    private fun showPerAppPicker() {
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
            val app = filteredApps[pos]
            val pkg = app.packageName
            val label = pm.getApplicationLabel(app)
            dialog.dismiss()

            // Prompt 1-Tap Rule Template for selected app
            showRuleTemplateDialog(pkg, label.toString())
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.8).toInt())
    }

    private fun showRuleTemplateDialog(packageName: String, appName: String) {
        val templates = arrayOf(
            "🎮 Pro Gamer (Turbo 2.8G + 120Hz + Bypass + DND)",
            "🎬 Cinema & Video (60Hz Battery Saver + Bypass)",
            "📖 Deep Reader / Eco (650M Floor + 60Hz + DND)",
            "⚙️ Custom Rule (Configure Manually)"
        )

        AlertDialog.Builder(this)
            .setTitle("Apply Smart Rule: $appName")
            .setItems(templates) { _, which ->
                val config = when (which) {
                    0 -> PerAppManager.AppConfig(
                        mode = "Performance",
                        fps = "120Hz",
                        thermal = "Disabled",
                        touch = "On",
                        bypassCharging = true,
                        autoDnd = true
                    )
                    1 -> PerAppManager.AppConfig(
                        mode = "Balance",
                        fps = "60Hz",
                        thermal = "Default",
                        touch = "Off",
                        bypassCharging = true,
                        autoDnd = false
                    )
                    2 -> PerAppManager.AppConfig(
                        mode = "Power Saver",
                        fps = "60Hz",
                        thermal = "Default",
                        touch = "Off",
                        bypassCharging = false,
                        autoDnd = true
                    )
                    else -> PerAppManager.AppConfig(
                        mode = "Auto",
                        fps = "Auto Switch",
                        thermal = "Default",
                        touch = "Off",
                        bypassCharging = false,
                        autoDnd = false
                    )
                }
                PerAppManager.saveConfig(this, packageName, config)
                refreshList()
                Toast.makeText(this, "Smart Rule saved for $appName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
