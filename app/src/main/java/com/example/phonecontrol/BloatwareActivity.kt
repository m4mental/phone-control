package com.example.phonecontrol

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.concurrent.thread

class BloatwareActivity : AppCompatActivity() {

    companion object {
        val CORE_PROTECTED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.settings",
            "com.android.providers.settings",
            "com.nothing.launcher",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.inputmethod.latin",
            "com.android.inputmethod.latin",
            "com.android.server.telecom",
            "com.android.bluetooth",
            "com.android.nfc",
            "com.android.keychain",
            "com.android.carrierconfig",
            "com.example.phonecontrol"
        )

        val SAFE_DEBLOAT_PRESETS = mapOf(
            "com.google.android.videos" to "Google TV / Movies",
            "com.google.android.apps.youtube.music" to "YouTube Music",
            "com.google.android.apps.tachyon" to "Google Meet / Duo",
            "com.google.android.apps.podcasts" to "Google Podcasts",
            "com.google.android.feedback" to "Market Feedback Agent",
            "com.google.android.printservice.recommendation" to "Print Service Recommendation",
            "com.android.bips" to "Default Print Service",
            "com.nothing.community" to "Nothing Community App",
            "com.nothing.feedback" to "Nothing Feedback Tool",
            "com.google.android.marvin.talkback" to "Talkback Accessibility",
            "com.google.android.projection.gearhead" to "Android Auto",
            "com.google.android.apps.subscriptions.red" to "Google One",
            "com.google.android.apps.fitness" to "Google Fit",
            "com.google.ar.core" to "Google Play Services for AR"
        )
    }

    data class BloatItem(
        val packageName: String,
        val appName: String,
        val isSystem: Boolean,
        val isDisabled: Boolean,
        val isProtected: Boolean,
        val isSafePreset: Boolean
    )

    private lateinit var pm: PackageManager
    private lateinit var rvBloat: RecyclerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var etSearch: EditText
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var chipGroupTabs: ChipGroup
    private lateinit var chipTabSystem: Chip
    private lateinit var chipTabDisabled: Chip
    private lateinit var chipTabPresets: Chip
    private lateinit var btnApplyDebloat: MaterialButton

    private val allSystemItems = mutableListOf<BloatItem>()
    private val disabledItems = mutableListOf<BloatItem>()
    private val presetItems = mutableListOf<BloatItem>()
    private val displayedItems = mutableListOf<BloatItem>()

    private lateinit var adapter: BloatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bloatware)

        pm = packageManager
        findViewById<MaterialToolbar>(R.id.toolbarBloat).setNavigationOnClickListener { finish() }

        rvBloat = findViewById(R.id.rvBloatApps)
        pbLoading = findViewById(R.id.pbBloatLoading)
        etSearch = findViewById(R.id.etSearchBloat)
        tvCount = findViewById(R.id.tvBloatCount)
        tvEmpty = findViewById(R.id.tvEmptyState)
        chipGroupTabs = findViewById(R.id.chipGroupBloatTabs)
        chipTabSystem = findViewById(R.id.chipTabSystem)
        chipTabDisabled = findViewById(R.id.chipTabDisabled)
        chipTabPresets = findViewById(R.id.chipTabPresets)
        btnApplyDebloat = findViewById(R.id.btnApplySafeDebloat)

        rvBloat.layoutManager = LinearLayoutManager(this)
        rvBloat.setHasFixedSize(true)
        adapter = BloatAdapter(
            items = displayedItems,
            onActionClicked = { item -> handleItemAction(item) }
        )
        rvBloat.adapter = adapter

        chipGroupTabs.setOnCheckedChangeListener { _, _ ->
            filterAndDisplay()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterAndDisplay()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnApplyDebloat.setOnClickListener {
            handleBatchDebloatPresets()
        }

        loadData()
    }

    private fun loadData() {
        pbLoading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        thread {
            // 1. Get raw disabled packages list via root
            val disabledPkgs = mutableSetOf<String>()
            val disabledCmd = ShellUtils.runAsRoot("pm list packages -d", 5000)
            if (disabledCmd.exitCode == 0) {
                disabledCmd.output.lines().forEach { line ->
                    val clean = line.trim()
                    if (clean.startsWith("package:")) {
                        disabledPkgs.add(clean.substringAfter("package:").trim())
                    }
                }
            }

            // 2. Fetch all installed applications
            val allApps = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.GET_META_DATA)
            val sysList = mutableListOf<BloatItem>()
            val disList = mutableListOf<BloatItem>()
            val preList = mutableListOf<BloatItem>()

            for (app in allApps) {
                val isSys = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isDis = disabledPkgs.contains(app.packageName) || !app.enabled
                val isCore = CORE_PROTECTED_PACKAGES.contains(app.packageName)
                val isPreset = SAFE_DEBLOAT_PRESETS.containsKey(app.packageName)
                val label = try { pm.getApplicationLabel(app).toString() } catch (e: Exception) { app.packageName }

                val item = BloatItem(
                    packageName = app.packageName,
                    appName = label,
                    isSystem = isSys,
                    isDisabled = isDis,
                    isProtected = isCore,
                    isSafePreset = isPreset
                )

                if (isSys) sysList.add(item)
                if (isDis) disList.add(item)
                if (isPreset) preList.add(item)
            }

            // Also check for any safe preset packages that might not be in allApps
            for ((pkg, name) in SAFE_DEBLOAT_PRESETS) {
                if (preList.none { it.packageName == pkg }) {
                    try {
                        val appInfo = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                        val isDis = disabledPkgs.contains(pkg) || !appInfo.enabled
                        preList.add(BloatItem(pkg, name, true, isDis, false, true))
                    } catch (e: Exception) {}
                }
            }

            sysList.sortBy { it.appName.lowercase() }
            disList.sortBy { it.appName.lowercase() }
            preList.sortBy { it.appName.lowercase() }

            runOnUiThread {
                allSystemItems.clear()
                allSystemItems.addAll(sysList)

                disabledItems.clear()
                disabledItems.addAll(disList)

                presetItems.clear()
                presetItems.addAll(preList)

                chipTabDisabled.text = "Disabled Apps (${disabledItems.size})"
                pbLoading.visibility = View.GONE
                filterAndDisplay()
            }
        }
    }

    private fun filterAndDisplay() {
        val q = etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val checkedId = chipGroupTabs.checkedChipId

        val sourceList = when (checkedId) {
            R.id.chipTabDisabled -> disabledItems
            R.id.chipTabPresets -> presetItems
            else -> allSystemItems
        }

        btnApplyDebloat.visibility = if (checkedId == R.id.chipTabPresets && presetItems.any { !it.isDisabled }) View.VISIBLE else View.GONE

        val filtered = sourceList.filter {
            if (q.isBlank()) true else {
                it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }

        displayedItems.clear()
        displayedItems.addAll(filtered)
        adapter.notifyDataSetChanged()

        tvCount.text = "${filtered.size} packages listed"
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = when (checkedId) {
            R.id.chipTabDisabled -> "No disabled apps found. System is running at stock state."
            R.id.chipTabPresets -> "All recommended bloatware packages are already disabled or uninstalled!"
            else -> "No matching system apps found."
        }
    }

    private fun handleItemAction(item: BloatItem) {
        if (item.isProtected) {
            MaterialAlertDialogBuilder(this)
                .setTitle("🛡️ Protected Core Component")
                .setMessage("${item.appName} (${item.packageName}) is an essential Android OS / Nothing OS component. Disabling it will crash the system or cause a bootloop.")
                .setPositiveButton("UNDERSTOOD", null)
                .show()
            return
        }

        if (item.isDisabled) {
            // Restore action
            MaterialAlertDialogBuilder(this)
                .setTitle("Restore ${item.appName}?")
                .setMessage("Re-enable this app package so it appears in your launcher and can run normally?")
                .setPositiveButton("RESTORE") { _, _ ->
                    executeRestore(item.packageName, item.appName)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            // Disable action
            val warningMsg = if (item.isSafePreset) {
                "Safe debloat candidate: ${item.appName}\nDisabling it will free RAM and prevent background activity."
            } else {
                "Disabling ${item.appName} will remove it from the launcher and halt its background processes."
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("Disable ${item.appName}?")
                .setMessage("$warningMsg\n\nPackage: ${item.packageName}")
                .setPositiveButton("DISABLE") { _, _ ->
                    executeDisable(item.packageName, item.appName)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun executeDisable(pkg: String, name: String) {
        thread {
            ShellUtils.runAsRoot("pm disable-user --user 0 $pkg")
            runOnUiThread {
                Toast.makeText(this, "$name disabled", Toast.LENGTH_SHORT).show()
                loadData()
            }
        }
    }

    private fun executeRestore(pkg: String, name: String) {
        thread {
            ShellUtils.runAsRoot("pm enable $pkg")
            runOnUiThread {
                Toast.makeText(this, "$name restored & enabled", Toast.LENGTH_SHORT).show()
                loadData()
            }
        }
    }

    private fun handleBatchDebloatPresets() {
        val candidates = presetItems.filter { !it.isDisabled && !it.isProtected }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "All presets already disabled", Toast.LENGTH_SHORT).show()
            return
        }

        val appNames = candidates.joinToString("\n• ") { it.appName }

        MaterialAlertDialogBuilder(this)
            .setTitle("⚡ 1-Tap Debloat ${candidates.size} Apps?")
            .setMessage("The following vendor/telemetry apps will be disabled for User 0:\n\n• $appNames\n\nYou can restore any of them anytime from the 'Disabled Apps' tab.")
            .setPositiveButton("DISABLE ALL") { _, _ ->
                pbLoading.visibility = View.VISIBLE
                thread {
                    for (item in candidates) {
                        ShellUtils.runAsRoot("pm disable-user --user 0 ${item.packageName}")
                    }
                    runOnUiThread {
                        Toast.makeText(this, "Debloated ${candidates.size} apps!", Toast.LENGTH_SHORT).show()
                        loadData()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class BloatAdapter(
        private val items: List<BloatItem>,
        private val onActionClicked: (BloatItem) -> Unit
    ) : RecyclerView.Adapter<BloatAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivBloatIcon)
            val tvName: TextView = view.findViewById(R.id.tvBloatName)
            val tvPkg: TextView = view.findViewById(R.id.tvBloatPkg)
            val tvBadge: TextView = view.findViewById(R.id.tvBloatBadge)
            val btnAction: MaterialButton = view.findViewById(R.id.btnBloatAction)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bloatware, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]

            holder.tvName.text = item.appName
            holder.tvPkg.text = item.packageName

            // Async/Cached icon loading
            val cachedIcon = AppCacheManager.iconCache[item.packageName]
            if (cachedIcon != null) {
                holder.ivIcon.setImageDrawable(cachedIcon)
            } else {
                holder.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                thread {
                    try {
                        val icon = pm.getApplicationIcon(item.packageName)
                        AppCacheManager.iconCache[item.packageName] = icon
                        holder.ivIcon.post { holder.ivIcon.setImageDrawable(icon) }
                    } catch (e: Exception) {}
                }
            }

            // Badges & Actions
            when {
                item.isProtected -> {
                    holder.tvBadge.visibility = View.VISIBLE
                    holder.tvBadge.text = "[CORE OS - PROTECTED]"
                    holder.tvBadge.setTextColor(Color.parseColor("#FF5252"))

                    holder.btnAction.text = "LOCKED"
                    holder.btnAction.setTextColor(Color.parseColor("#666666"))
                    holder.btnAction.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#444444"))
                    holder.btnAction.isEnabled = false
                }
                item.isDisabled -> {
                    holder.tvBadge.visibility = View.VISIBLE
                    holder.tvBadge.text = "[DISABLED / FROZEN]"
                    holder.tvBadge.setTextColor(Color.parseColor("#00E5FF"))

                    holder.btnAction.text = "RESTORE"
                    holder.btnAction.setTextColor(Color.parseColor("#00E676"))
                    holder.btnAction.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E676"))
                    holder.btnAction.isEnabled = true
                }
                item.isSafePreset -> {
                    holder.tvBadge.visibility = View.VISIBLE
                    holder.tvBadge.text = "[RECOMMENDED DEBLOAT]"
                    holder.tvBadge.setTextColor(Color.parseColor("#FFD600"))

                    holder.btnAction.text = "DISABLE"
                    holder.btnAction.setTextColor(Color.parseColor("#FF5252"))
                    holder.btnAction.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5252"))
                    holder.btnAction.isEnabled = true
                }
                else -> {
                    holder.tvBadge.visibility = View.GONE
                    holder.btnAction.text = "DISABLE"
                    holder.btnAction.setTextColor(Color.parseColor("#FF5252"))
                    holder.btnAction.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5252"))
                    holder.btnAction.isEnabled = true
                }
            }

            holder.btnAction.setOnClickListener { onActionClicked(item) }
            holder.itemView.setOnClickListener { onActionClicked(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
