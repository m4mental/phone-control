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
        findViewById<View>(R.id.btnToggleHud).setOnClickListener {
            FloatingHudService.toggle(this)
            val msg = if (FloatingHudService.isRunning) "Floating Performance HUD Enabled" else "Floating Performance HUD Disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        thread {
            cachedAppsList = getInstalledAppsList()
        }

        refreshList()
    }

    companion object {
        private var cachedApps: List<ApplicationInfo>? = null
    }

    private fun getInstalledAppsList(): List<ApplicationInfo> {
        cachedApps?.let { return it }
        return try {
            val all = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val filtered = all.filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pm.getLaunchIntentForPackage(it.packageName) != null
            }.sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            cachedApps = filtered
            filtered
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun refreshList(expandPkg: String? = null) {
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
                val modeDesc = if (c.mode == "Custom" && c.customLittleMin > 0) {
                    "Custom (${c.customLittleMin / 1000}-${c.customLittleMax / 1000}M / ${c.customBigMin / 1000}-${c.customBigMax / 1000}M)"
                } else {
                    c.mode
                }
                tvSummary.text = "$modeDesc | ${c.fps} | Thermal: ${c.thermal} | Touch: ${c.touch}$extraTag"
            }
            updateSummaryLabel(config)

            // Setup Expand/Collapse
            val shouldExpand = (packageName == expandPkg)
            if (shouldExpand) {
                layoutConfig.visibility = View.VISIBLE
                ivArrow.rotation = 180f
            } else {
                layoutConfig.visibility = View.GONE
                ivArrow.rotation = 0f
            }

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
            val layoutCustomFreqs = view.findViewById<LinearLayout>(R.id.layoutCustomFreqs)
            val tvLittleMinLabel = view.findViewById<TextView>(R.id.tvLittleMinLabel)
            val sbLittleMin = view.findViewById<SeekBar>(R.id.sbLittleMin)
            val tvLittleMaxLabel = view.findViewById<TextView>(R.id.tvLittleMaxLabel)
            val sbLittleMax = view.findViewById<SeekBar>(R.id.sbLittleMax)
            val tvBigMinLabel = view.findViewById<TextView>(R.id.tvBigMinLabel)
            val sbBigMin = view.findViewById<SeekBar>(R.id.sbBigMin)
            val tvBigMaxLabel = view.findViewById<TextView>(R.id.tvBigMaxLabel)
            val sbBigMax = view.findViewById<SeekBar>(R.id.sbBigMax)
            val rgGovernor = view.findViewById<RadioGroup>(R.id.rgGovernor)

            val rgFps = view.findViewById<RadioGroup>(R.id.rgFps)
            val swThermal = view.findViewById<SwitchMaterial>(R.id.switchThermal)
            val swTouch = view.findViewById<SwitchMaterial>(R.id.switchTouch)
            val swBypass = view.findViewById<SwitchMaterial>(R.id.switchBypass)
            val swDnd = view.findViewById<SwitchMaterial>(R.id.switchDnd)
            val btnRemove = view.findViewById<Button>(R.id.btnRemove)

            // Setup Sliders Data
            val littleFreqValues = intArrayOf(480000, 650000, 750000, 850000, 950000, 1100000, 1200000, 1350000, 1500000, 1750000, 2000000)
            val bigFreqValues = intArrayOf(400000, 600000, 800000, 1000000, 1200000, 1400000, 1600000, 1800000, 2000000, 2200000, 2400000, 2600000, 2800000)

            sbLittleMin.max = littleFreqValues.size - 1
            sbLittleMax.max = littleFreqValues.size - 1
            sbBigMin.max = bigFreqValues.size - 1
            sbBigMax.max = bigFreqValues.size - 1

            val lMinIndex = littleFreqValues.indexOf(config.customLittleMin).let { if (it >= 0) it else 1 }
            val lMaxIndex = littleFreqValues.indexOf(config.customLittleMax).let { if (it >= 0) it else littleFreqValues.lastIndex }
            val bMinIndex = bigFreqValues.indexOf(config.customBigMin).let { if (it >= 0) it else 0 }
            val bMaxIndex = bigFreqValues.indexOf(config.customBigMax).let { if (it >= 0) it else bigFreqValues.lastIndex }

            sbLittleMin.progress = lMinIndex
            sbLittleMax.progress = lMaxIndex
            sbBigMin.progress = bMinIndex
            sbBigMax.progress = bMaxIndex

            tvLittleMinLabel.text = "Min Frequency: ${littleFreqValues[lMinIndex] / 1000} MHz"
            tvLittleMaxLabel.text = "Max Frequency: ${littleFreqValues[lMaxIndex] / 1000} MHz"
            tvBigMinLabel.text = "Min Frequency: ${bigFreqValues[bMinIndex] / 1000} MHz"
            tvBigMaxLabel.text = "Max Frequency: ${bigFreqValues[bMaxIndex] / 1000} MHz"

            when (config.customGovernor) {
                "performance" -> rgGovernor.check(R.id.rbGovPerformance)
                "powersave" -> rgGovernor.check(R.id.rbGovPowersave)
                else -> rgGovernor.check(R.id.rbGovSchedutil)
            }

            // Initial Radio Selection
            when (config.mode) {
                "Auto" -> rgMode.check(R.id.rbModeAuto)
                "Power Saver" -> rgMode.check(R.id.rbModeSaver)
                "Performance" -> rgMode.check(R.id.rbModePerf)
                "Streaming" -> rgMode.check(R.id.rbModeStreaming)
                "Custom" -> {
                    rgMode.check(R.id.rbModeCustom)
                    layoutCustomFreqs.visibility = View.VISIBLE
                }
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
                    R.id.rbModeStreaming -> "Streaming"
                    R.id.rbModeCustom -> "Custom"
                    else -> "Balance"
                }
                layoutCustomFreqs.visibility = if (mode == "Custom") View.VISIBLE else View.GONE
                val lMin = littleFreqValues[sbLittleMin.progress.coerceIn(0, littleFreqValues.lastIndex)]
                val lMax = littleFreqValues[sbLittleMax.progress.coerceIn(0, littleFreqValues.lastIndex)]
                val bMin = bigFreqValues[sbBigMin.progress.coerceIn(0, bigFreqValues.lastIndex)]
                val bMax = bigFreqValues[sbBigMax.progress.coerceIn(0, bigFreqValues.lastIndex)]
                val gov = when (rgGovernor.checkedRadioButtonId) {
                    R.id.rbGovPerformance -> "performance"
                    R.id.rbGovPowersave -> "powersave"
                    else -> "schedutil"
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
                
                val newConfig = PerAppManager.AppConfig(
                    mode = mode,
                    fps = fps,
                    thermal = thermal,
                    touch = touch,
                    bypassCharging = bypass,
                    autoDnd = dnd,
                    customLittleMin = lMin,
                    customLittleMax = lMax,
                    customBigMin = bMin,
                    customBigMax = bMax,
                    customGovernor = gov
                )
                PerAppManager.saveConfig(this, packageName, newConfig)
                updateSummaryLabel(newConfig)
                
                // Live Tuning - Apply if app is currently in foreground
                thread {
                    val topApp = ShellUtils.runAsRoot("dumpsys window | grep mCurrentFocus").output
                    if (topApp.contains(packageName)) {
                        if (mode == "Custom") {
                            TweakManager.applyCustomAppProfile(lMin, lMax, bMin, bMax, gov)
                        } else if (mode != "Auto") {
                            TweakManager.applyGlobalMode(mode)
                        }
                        if (fps != "Auto Switch") TweakManager.setRefreshRate(fps)
                        if (thermal == "Disabled") ThermalManager.setThrottlingEnabled(false) else ThermalManager.setThrottlingEnabled(true)
                        if (touch == "On") TweakManager.applyInputBoost(true)
                        if (bypass) BatteryManager.setBypassCharging(this, true)
                        if (dnd) ShellUtils.fastCmd("cmd notification set_zen_mode 1")
                        if (mode == "Performance") TweakManager.applyProcessPriority(packageName, true)
                    }
                }
            }

            // SeekBars Left-to-Right Listeners
            val seekBarListener = object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        when (sb?.id) {
                            R.id.sbLittleMin -> {
                                if (progress > sbLittleMax.progress) {
                                    sbLittleMax.progress = progress
                                }
                            }
                            R.id.sbLittleMax -> {
                                if (progress < sbLittleMin.progress) {
                                    sbLittleMin.progress = progress
                                }
                            }
                            R.id.sbBigMin -> {
                                if (progress > sbBigMax.progress) {
                                    sbBigMax.progress = progress
                                }
                            }
                            R.id.sbBigMax -> {
                                if (progress < sbBigMin.progress) {
                                    sbBigMin.progress = progress
                                }
                            }
                        }
                    }
                    tvLittleMinLabel.text = "Min Frequency: ${littleFreqValues[sbLittleMin.progress] / 1000} MHz"
                    tvLittleMaxLabel.text = "Max Frequency: ${littleFreqValues[sbLittleMax.progress] / 1000} MHz"
                    tvBigMinLabel.text = "Min Frequency: ${bigFreqValues[sbBigMin.progress] / 1000} MHz"
                    tvBigMaxLabel.text = "Max Frequency: ${bigFreqValues[sbBigMax.progress] / 1000} MHz"
                    if (rgMode.checkedRadioButtonId == R.id.rbModeCustom) {
                        onConfigChange()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            }

            sbLittleMin.setOnSeekBarChangeListener(seekBarListener)
            sbLittleMax.setOnSeekBarChangeListener(seekBarListener)
            sbBigMin.setOnSeekBarChangeListener(seekBarListener)
            sbBigMax.setOnSeekBarChangeListener(seekBarListener)
            rgGovernor.setOnCheckedChangeListener { _, _ ->
                if (rgMode.checkedRadioButtonId == R.id.rbModeCustom) {
                    onConfigChange()
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

            if (shouldExpand) {
                view.post {
                    val scrollView = findViewById<androidx.core.widget.NestedScrollView>(R.id.scrollPerApp)
                    scrollView?.smoothScrollTo(0, view.top)
                }
            }
        }
    }

    private fun showPerAppPicker() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_per_app_picker, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearchApp)
        val tvAppCount = dialogView.findViewById<TextView>(R.id.tvAppCount)
        val btnClose = dialogView.findViewById<ImageView>(R.id.btnCloseDialog)
        val listView = dialogView.findViewById<ListView>(R.id.lvApps)

        val allApps = cachedAppsList ?: getInstalledAppsList()
        val filteredApps = allApps.toMutableList()
        tvAppCount.text = "${filteredApps.size} apps available"

        val adapter = object : ArrayAdapter<ApplicationInfo>(this, R.layout.item_per_app_picker, filteredApps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_per_app_picker, parent, false)
                val app = getItem(position)!!
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
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                if (query.isEmpty()) {
                    filteredApps.addAll(allApps)
                    tvAppCount.text = "${filteredApps.size} apps available"
                } else {
                    filteredApps.addAll(allApps.filter { 
                        pm.getApplicationLabel(it).toString().lowercase().contains(query) || 
                        it.packageName.lowercase().contains(query) 
                    })
                    tvAppCount.text = "${filteredApps.size} apps found"
                }
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        btnClose.setOnClickListener { dialog.dismiss() }

        listView.setOnItemClickListener { _, _, pos, _ -> 
            val app = filteredApps[pos]
            val pkg = app.packageName
            val label = pm.getApplicationLabel(app)
            dialog.dismiss()

            // Prompt 1-Tap Rule Template for selected app
            showRuleTemplateDialog(pkg, label.toString())
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        val width = (resources.displayMetrics.widthPixels * 0.94).toInt()
        val height = (resources.displayMetrics.heightPixels * 0.88).toInt()
        dialog.window?.setLayout(width, height)
    }

    private fun showRuleTemplateDialog(packageName: String, appName: String) {
        val templates = arrayOf(
            "🎮 Pro Gamer (Turbo 2.8G + 120Hz + Bypass + DND)",
            "🎬 Cinema & Video (Streaming 950-1200M + 60Hz + Bypass)",
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
                        mode = "Streaming",
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
                        mode = "Custom",
                        fps = "Auto Switch",
                        thermal = "Default",
                        touch = "Off",
                        bypassCharging = false,
                        autoDnd = false,
                        customLittleMin = 650000,
                        customLittleMax = 2000000,
                        customBigMin = 400000,
                        customBigMax = 2800000,
                        customGovernor = "schedutil"
                    )
                }
                val isCustom = (which == 3)
                PerAppManager.saveConfig(this, packageName, config)
                refreshList(expandPkg = if (isCustom) packageName else null)
                AppToast.show(this, if (isCustom) "Custom Rule active for $appName — configure options below" else "Smart Rule saved for $appName")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
