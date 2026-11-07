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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class BloatwareActivity : AppCompatActivity() {

    data class BloatItem(
        val packageName: String,
        val appName: String,
        val isSystem: Boolean,
        val isDisabled: Boolean,
        val isProtected: Boolean,
        val uadPackage: UadPackage? = null,
        val undoTimestamp: Long? = null
    )

    private lateinit var pm: PackageManager
    private lateinit var rvBloat: RecyclerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var etSearch: EditText
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var chipGroupTabs: ChipGroup
    private lateinit var chipTabUad: Chip
    private lateinit var chipTabSystem: Chip
    private lateinit var chipTabDisabled: Chip
    private lateinit var chipTabUndo: Chip
    private lateinit var scrollBrandChips: HorizontalScrollView
    private lateinit var chipGroupBrands: ChipGroup
    private lateinit var btnApplyDebloat: MaterialButton
    private lateinit var btnRestoreAllUndo: MaterialButton

    private val allSystemItems = mutableListOf<BloatItem>()
    private val disabledItems = mutableListOf<BloatItem>()
    private val uadItems = mutableListOf<BloatItem>()
    private val undoItems = mutableListOf<BloatItem>()
    private val displayedItems = mutableListOf<BloatItem>()

    // Cached system packages registry across user actions
    private var cachedInstalledMap: Map<String, ApplicationInfo> = emptyMap()
    private var cachedDisabledPkgs: Set<String> = emptySet()

    private lateinit var adapter: BloatAdapter
    private var selectedBrand = "DETECTED"

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
        chipTabUad = findViewById(R.id.chipTabUad)
        chipTabSystem = findViewById(R.id.chipTabSystem)
        chipTabDisabled = findViewById(R.id.chipTabDisabled)
        chipTabUndo = findViewById(R.id.chipTabUndo)

        scrollBrandChips = findViewById(R.id.scrollBrandChips)
        chipGroupBrands = findViewById(R.id.chipGroupBrands)
        btnApplyDebloat = findViewById(R.id.btnApplySafeDebloat)
        btnRestoreAllUndo = findViewById(R.id.btnRestoreAllUndo)

        rvBloat.layoutManager = LinearLayoutManager(this)
        rvBloat.setHasFixedSize(true)
        adapter = BloatAdapter(
            items = displayedItems,
            onActionClicked = { item -> handleItemAction(item) }
        )
        rvBloat.adapter = adapter

        // Setup Brand text
        val detected = UadDebloatDatabase.getDetectedBrand()
        findViewById<Chip>(R.id.chipBrandAuto).text = "📱 Auto ($detected)"

        chipGroupTabs.setOnCheckedChangeListener { _, checkedId ->
            scrollBrandChips.visibility = if (checkedId == R.id.chipTabUad) View.VISIBLE else View.GONE
            filterAndDisplay()
        }

        chipGroupBrands.setOnCheckedChangeListener { _, checkedId ->
            selectedBrand = when (checkedId) {
                R.id.chipBrandAll -> "ALL"
                R.id.chipBrandNothing -> "NOTHING"
                R.id.chipBrandGoogle -> "GOOGLE"
                R.id.chipBrandXiaomi -> "XIAOMI"
                R.id.chipBrandSamsung -> "SAMSUNG"
                R.id.chipBrandBbk -> "BBK"
                R.id.chipBrandCarrier -> "CARRIER"
                else -> "DETECTED"
            }
            buildUadList()
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
            handleBatchDebloat()
        }

        btnRestoreAllUndo.setOnClickListener {
            handleBatchRestoreUndo()
        }

        loadData()
    }

    private fun loadData() {
        pbLoading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        thread {
            // 1. Get disabled AND uninstalled packages list via root (-d -u)
            val disabledPkgs = mutableSetOf<String>()
            val disabledCmd = ShellUtils.runAsRoot("pm list packages -d -u", 5000)
            if (disabledCmd.exitCode == 0) {
                disabledCmd.output.lines().forEach { line ->
                    val clean = line.trim()
                    if (clean.startsWith("package:")) {
                        disabledPkgs.add(clean.substringAfter("package:").trim())
                    }
                }
            }

            // 2. Fetch all installed applications
            val allApps = try {
                pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.GET_META_DATA)
            } catch (e: Exception) {
                emptyList<ApplicationInfo>()
            }

            val installedMap = allApps.associateBy { it.packageName }
            cachedInstalledMap = installedMap
            cachedDisabledPkgs = disabledPkgs

            val sysList = mutableListOf<BloatItem>()
            val disList = mutableListOf<BloatItem>()

            for (app in allApps) {
                val isSys = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isDis = disabledPkgs.contains(app.packageName) || !app.enabled
                val isCore = UadDebloatDatabase.CORE_PROTECTED_PACKAGES.contains(app.packageName)
                val label = try { pm.getApplicationLabel(app).toString() } catch (e: Exception) { app.packageName }

                val item = BloatItem(
                    packageName = app.packageName,
                    appName = label,
                    isSystem = isSys,
                    isDisabled = isDis,
                    isProtected = isCore
                )

                if (isSys) sysList.add(item)
                if (isDis) disList.add(item)
            }

            sysList.sortBy { it.appName.lowercase() }
            disList.sortBy { it.appName.lowercase() }

            // 3. Build Undo History
            val undoRecords = UadDebloatDatabase.getUndoHistory(this)
            val undoList = undoRecords.map { rec ->
                val appInfo = installedMap[rec.packageName]
                val label = appInfo?.let {
                    try { pm.getApplicationLabel(it).toString() } catch (e: Exception) { null }
                } ?: rec.appName
                val isDis = disabledPkgs.contains(rec.packageName) || (appInfo != null && !appInfo.enabled)

                BloatItem(
                    packageName = rec.packageName,
                    appName = label,
                    isSystem = true,
                    isDisabled = isDis,
                    isProtected = false,
                    undoTimestamp = rec.timestamp
                )
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allSystemItems.clear()
                allSystemItems.addAll(sysList)

                disabledItems.clear()
                disabledItems.addAll(disList)

                undoItems.clear()
                undoItems.addAll(undoList)

                chipTabDisabled.text = "Disabled Apps (${disabledItems.size})"
                chipTabUndo.text = "⏪ Undo History (${undoItems.size})"

                buildUadList()
                pbLoading.visibility = View.GONE
                filterAndDisplay()
            }
        }
    }

    private fun buildUadList() {
        val uadEntries = UadDebloatDatabase.getPackagesForBrand(selectedBrand)
        val list = mutableListOf<BloatItem>()

        for (uad in uadEntries) {
            val app = cachedInstalledMap[uad.packageName]
            // Only list bloatware packages that are actually installed on this phone
            if (app == null && !cachedDisabledPkgs.contains(uad.packageName)) continue

            val isDis = cachedDisabledPkgs.contains(uad.packageName) || (app != null && !app.enabled)
            val isCore = UadDebloatDatabase.CORE_PROTECTED_PACKAGES.contains(uad.packageName)
            val label = app?.let {
                try { pm.getApplicationLabel(it).toString() } catch (e: Exception) { null }
            } ?: uad.name

            list.add(
                BloatItem(
                    packageName = uad.packageName,
                    appName = label,
                    isSystem = true,
                    isDisabled = isDis,
                    isProtected = isCore,
                    uadPackage = uad
                )
            )
        }

        list.sortWith(compareBy({ it.isDisabled }, { it.uadPackage?.level != DebloatLevel.RECOMMENDED }, { it.appName.lowercase() }))
        uadItems.clear()
        uadItems.addAll(list)
    }

    private fun filterAndDisplay() {
        val q = etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val checkedId = chipGroupTabs.checkedChipId

        val sourceList = when (checkedId) {
            R.id.chipTabUad -> uadItems
            R.id.chipTabDisabled -> disabledItems
            R.id.chipTabUndo -> undoItems
            else -> allSystemItems
        }

        // Show batch debloat button on UAD tab if there are safe candidates
        val safeCandidates = uadItems.filter { !it.isDisabled && !it.isProtected && it.uadPackage?.level == DebloatLevel.RECOMMENDED }
        btnApplyDebloat.visibility = if (checkedId == R.id.chipTabUad && safeCandidates.isNotEmpty()) View.VISIBLE else View.GONE
        if (btnApplyDebloat.visibility == View.VISIBLE) {
            btnApplyDebloat.text = "⚡ DEBLOAT (${safeCandidates.size} SAFE)"
        }

        // Show batch restore button on Undo tab
        val canRestoreUndo = undoItems.any { it.isDisabled }
        btnRestoreAllUndo.visibility = if (checkedId == R.id.chipTabUndo && canRestoreUndo) View.VISIBLE else View.GONE

        val filtered = sourceList.filter {
            if (q.isBlank()) true else {
                it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q) ||
                        (it.uadPackage?.description?.lowercase()?.contains(q) == true)
            }
        }

        displayedItems.clear()
        displayedItems.addAll(filtered)
        adapter.notifyDataSetChanged()

        tvCount.text = "${filtered.size} packages listed"
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = when (checkedId) {
            R.id.chipTabUad -> "No bloatware packages found on device for selected brand."
            R.id.chipTabDisabled -> "No disabled apps found. System is running at stock state."
            R.id.chipTabUndo -> "No debloat history recorded yet. Safe debloated apps will be saved here."
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
                .setMessage("Re-enable this app package so it appears in your launcher and can run normally?\n\nPackage: ${item.packageName}")
                .setPositiveButton("RESTORE") { _, _ ->
                    executeRestore(item.packageName, item.appName)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            // Disable action
            val desc = item.uadPackage?.description
            val warningMsg = if (desc != null) {
                "UAD-NG Category: ${item.uadPackage.level.label}\n\n$desc\n\nDisabling it will free RAM and halt background services."
            } else {
                "Disabling ${item.appName} will remove it from the launcher and halt its background processes."
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("Debloat ${item.appName}?")
                .setMessage("$warningMsg\n\nPackage: ${item.packageName}")
                .setPositiveButton("DEBLOAT (DISABLE)") { _, _ ->
                    executeDisable(item.packageName, item.appName)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun executeDisable(pkg: String, name: String) {
        thread {
            ShellUtils.runAsRoot("pm disable-user --user 0 $pkg")
            UadDebloatDatabase.recordDebloated(this, pkg, name)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(this, "$name debloated (saved to Undo History)", Toast.LENGTH_SHORT).show()
                loadData()
            }
        }
    }

    private fun executeRestore(pkg: String, name: String) {
        thread {
            val restoreCmd = "pm default-state --user 0 $pkg 2>/dev/null; pm unhide $pkg 2>/dev/null; pm enable $pkg 2>/dev/null; cmd package unsuspend --user 0 $pkg 2>/dev/null; pm unsuspend $pkg 2>/dev/null; cmd package install-existing $pkg 2>/dev/null; am unfreeze $pkg 2>/dev/null"
            ShellUtils.runAsRoot(restoreCmd)
            UadDebloatDatabase.removeUndo(this, pkg)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(this, "$name restored & enabled", Toast.LENGTH_SHORT).show()
                loadData()
            }
        }
    }

    private fun handleBatchDebloat() {
        val candidates = uadItems.filter { !it.isDisabled && !it.isProtected && it.uadPackage?.level == DebloatLevel.RECOMMENDED }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "No safe debloat candidates available on device", Toast.LENGTH_SHORT).show()
            return
        }

        val appNames = candidates.take(15).joinToString("\n• ") { "${it.appName} (${it.packageName})" }
        val extra = if (candidates.size > 15) "\n...and ${candidates.size - 15} more" else ""

        MaterialAlertDialogBuilder(this)
            .setTitle("⚡ 1-Tap Debloat ${candidates.size} Safe Apps?")
            .setMessage("The following UAD-NG verified bloatware will be safely disabled for User 0:\n\n• $appNames$extra\n\nAll debloated apps are recorded in the '⏪ Undo History' tab and can be restored with 1-tap anytime.")
            .setPositiveButton("DEBLOAT ALL") { _, _ ->
                pbLoading.visibility = View.VISIBLE
                thread {
                    val batchCmd = candidates.joinToString("; ") { "pm disable-user --user 0 ${it.packageName}" }
                    ShellUtils.runAsRoot(batchCmd, 25000)
                    for (item in candidates) {
                        UadDebloatDatabase.recordDebloated(this@BloatwareActivity, item.packageName, item.appName)
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        Toast.makeText(this@BloatwareActivity, "🎉 Successfully debloated ${candidates.size} apps!", Toast.LENGTH_LONG).show()
                        loadData()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleBatchRestoreUndo() {
        val toRestore = undoItems.filter { it.isDisabled }
        if (toRestore.isEmpty()) {
            Toast.makeText(this, "No disabled apps to restore in history", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("🔄 Restore All (${toRestore.size} Apps)?")
            .setMessage("This will re-enable all previously debloated applications back to their active state.")
            .setPositiveButton("RESTORE ALL") { _, _ ->
                pbLoading.visibility = View.VISIBLE
                thread {
                    val batchRestoreCmd = toRestore.joinToString("; ") {
                        "pm default-state --user 0 ${it.packageName} 2>/dev/null; pm unhide ${it.packageName} 2>/dev/null; pm enable ${it.packageName} 2>/dev/null; cmd package unsuspend --user 0 ${it.packageName} 2>/dev/null; cmd package install-existing ${it.packageName} 2>/dev/null; am unfreeze ${it.packageName} 2>/dev/null"
                    }
                    val res = ShellUtils.runAsRoot(batchRestoreCmd, 60000)
                    if (res.exitCode == 0) {
                        UadDebloatDatabase.clearAllUndo(this@BloatwareActivity)
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val msg = if (res.exitCode == 0) "Restored ${toRestore.size} apps!" else "Batch restore completed with some warnings"
                        Toast.makeText(this@BloatwareActivity, msg, Toast.LENGTH_SHORT).show()
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
            val tvDesc: TextView = view.findViewById(R.id.tvBloatDesc)
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

            // Description
            if (item.uadPackage != null && item.uadPackage.description.isNotBlank()) {
                holder.tvDesc.visibility = View.VISIBLE
                holder.tvDesc.text = item.uadPackage.description
            } else if (item.undoTimestamp != null) {
                holder.tvDesc.visibility = View.VISIBLE
                val formattedDate = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(item.undoTimestamp))
                holder.tvDesc.text = "Debloated: $formattedDate"
            } else {
                holder.tvDesc.visibility = View.GONE
            }

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
                item.uadPackage != null -> {
                    holder.tvBadge.visibility = View.VISIBLE
                    holder.tvBadge.text = "[${item.uadPackage.level.label.uppercase()}]"
                    holder.tvBadge.setTextColor(Color.parseColor(item.uadPackage.level.colorHex))

                    holder.btnAction.text = "DEBLOAT"
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
