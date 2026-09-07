package com.example.phonecontrol

import android.app.ProgressDialog
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

class AppFreezerListActivity : AppCompatActivity() {

    private lateinit var rvFrozenAppsList: RecyclerView
    private lateinit var adapter: FrozenAppsAdapter
    private lateinit var pm: PackageManager

    private var isEditMode = false
    private val selectedToRemove = mutableSetOf<String>()
    private var displayItems: List<FrozenDisplayItem> = emptyList()

    // Header State
    private var autoFreezeEnabled: Boolean = false
    private var eqGuardEnabled: Boolean = false
    private var detectedEqText: String = "Detected: None"
    private var detectedEqColor: Int = Color.GRAY

    companion object {
        var cachedDisplayItems: List<FrozenDisplayItem>? = null
        var cachedInstalledApps: List<AppItem>? = null
        val appInfoCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Drawable?>>()
    }

    data class AppItem(val info: ApplicationInfo, val label: String, var isChecked: Boolean = false)
    data class FrozenDisplayItem(
        val pkg: String,
        val name: String,
        val icon: Drawable?,
        val isSpecial: Boolean,
        val isActive: Boolean,
        val isCustomWidget: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_freezer_list)

        pm = packageManager
        findViewById<MaterialToolbar>(R.id.toolbarAppFreezerList).setNavigationOnClickListener { finish() }

        autoFreezeEnabled = FreezerManager.isAutoFreezeEnabled(this)

        rvFrozenAppsList = findViewById(R.id.rvFrozenAppsList)
        rvFrozenAppsList.layoutManager = LinearLayoutManager(this)
        rvFrozenAppsList.setHasFixedSize(false)

        adapter = FrozenAppsAdapter()
        rvFrozenAppsList.adapter = adapter

        // Background Pre-warming for 0ms instant app picker dialogs
        thread {
            if (cachedInstalledApps == null) {
                cachedInstalledApps = getInstalledAppsList()
            }
        }

        setupEqualizerGuardUI()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        autoFreezeEnabled = FreezerManager.isAutoFreezeEnabled(this)
        setupEqualizerGuardUI()
        refreshList()
    }

    private fun setupEqualizerGuardUI() {
        eqGuardEnabled = FreezerManager.isEqualizerSleepEnabled(this)
        val detectedPkg = FreezerManager.getDetectedEqualizerPackage(this)
        if (detectedPkg != null) {
            val appLabel = try {
                val info = pm.getApplicationInfo(detectedPkg, 0)
                pm.getApplicationLabel(info).toString()
            } catch (e: Exception) {
                detectedPkg
            }
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val isMusicPlaying = audioManager?.isMusicActive == true
            val isFrozen = FreezerManager.isAppFrozen(detectedPkg)

            val statusSuffix = when {
                isMusicPlaying -> " • 🎵 Playing"
                isFrozen -> " • ❄️ Hibernated"
                else -> " • ⏸️ Paused"
            }
            detectedEqText = "Target: $appLabel$statusSuffix"
            detectedEqColor = if (isMusicPlaying) Color.parseColor("#00E676")
            else if (isFrozen) Color.parseColor("#00E5FF")
            else Color.parseColor("#FFD700")
        } else {
            detectedEqText = "No Equalizer App Found"
            detectedEqColor = Color.GRAY
        }

        if (::adapter.isInitialized) {
            adapter.notifyItemChanged(0)
        }
    }

    private fun showEqualizerPicker(onSelected: () -> Unit) {
        val currentTargetPkg = FreezerManager.getDetectedEqualizerPackage(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearchApp)
        val lvApps = dialogView.findViewById<ListView>(R.id.lvApps)
        dialogView.findViewById<View>(R.id.spinnerFilter)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.cbSelectAll)?.visibility = View.GONE
        etSearch.hint = "Search equalizer or app..."

        var allApps = cachedInstalledApps ?: emptyList()
        var filteredApps = allApps

        var dialog: AlertDialog? = null

        val pickerAdapter = EqualizerPickerAdapter(filteredApps, currentTargetPkg) { selectedItem ->
            val pkg = selectedItem.info.packageName
            FreezerManager.saveSelectedEqualizer(this, pkg)
            FreezerManager.setEqualizerSleepEnabled(this, true)
            dialog?.dismiss()
            onSelected()
            Toast.makeText(this, "Target Equalizer: ${selectedItem.label}", Toast.LENGTH_SHORT).show()
        }
        lvApps.adapter = pickerAdapter

        fun updateList() {
            val query = etSearch.text.toString().trim().lowercase()
            filteredApps = if (query.isEmpty()) {
                allApps
            } else {
                allApps.filter { it.label.lowercase().contains(query) || it.info.packageName.lowercase().contains(query) }
            }
            pickerAdapter.items = filteredApps
            pickerAdapter.notifyDataSetChanged()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dialog = AlertDialog.Builder(this)
            .setTitle("Select Audio Equalizer / DSP App")
            .setView(dialogView)
            .setNeutralButton("Auto-Detect") { _, _ ->
                FreezerManager.saveSelectedEqualizer(this, null)
                onSelected()
                Toast.makeText(this, "Reset to Auto-Detection", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        // Background pre-fetch/revalidation if cache was empty
        thread {
            if (allApps.isEmpty()) {
                val freshApps = getInstalledAppsList().sortedBy { it.label.lowercase() }
                allApps = freshApps
                runOnUiThread {
                    if (dialog.isShowing && !isFinishing && !isDestroyed) {
                        updateList()
                    }
                }
            }
        }
    }

    private fun refreshList() {
        val frozenApps = FreezerManager.getFrozenApps(this)

        if (frozenApps.isEmpty()) {
            cachedDisplayItems = emptyList()
            displayItems = emptyList()
            if (::adapter.isInitialized) {
                adapter.notifyDataSetChanged()
            }
            return
        }

        // 1. CACHE-FIRST: Instant 0ms render from memory cache
        val cached = cachedDisplayItems
        if (!cached.isNullOrEmpty()) {
            displayItems = cached
            if (::adapter.isInitialized) {
                adapter.notifyDataSetChanged()
            }
        }

        // 2. SILENT BACKGROUND REVALIDATE:
        thread {
            val activeSet = FreezerManager.getActivePackages(frozenApps)
            val customWidgetSet = FreezerManager.getCustomWidgetApps(this)
            val freshItems = frozenApps.map { pkg ->
                val cachedInfo = appInfoCache[pkg]
                val name = cachedInfo?.first ?: try {
                    val appInfo = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (ignored: Exception) { "Unknown App" }

                val icon = cachedInfo?.second ?: try {
                    val appInfo = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    pm.getApplicationIcon(appInfo)
                } catch (ignored: Exception) { null }

                appInfoCache[pkg] = Pair(name, icon)

                val isSpecial = FreezerManager.isSpecialFreeze(this, pkg)
                val isActive = activeSet.contains(pkg)
                val isCustomWidget = customWidgetSet.contains(pkg)
                FrozenDisplayItem(pkg, name, icon, isSpecial, isActive, isCustomWidget)
            }.sortedBy { it.name.lowercase() }

            cachedDisplayItems = freshItems

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                displayItems = freshItems
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun freezeAll() {
        val apps = FreezerManager.getFrozenApps(this)
        if (apps.isEmpty()) return

        val progress = ProgressDialog(this).apply {
            setMessage("Hibernating ${apps.size} apps...")
            setCancelable(false)
            show()
        }

        thread {
            FreezerManager.freezeMultipleApps(this, apps)
            runOnUiThread {
                progress.dismiss()
                refreshList()
                notifyWidgets()
                Toast.makeText(this, "Hibernated ${apps.size} apps!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun notifyWidgets() {
        FreezerWidgetProvider.updateAllWidgets(this)
        SpecialFreezerWidgetProvider.updateAllWidgets(this)
    }

    private fun showAppOptionsDialog(pkg: String, appName: String) {
        val isSpecial = FreezerManager.isSpecialFreeze(this, pkg)
        val specialLabel = if (isSpecial) "Disable Special Freeze (Restore Launcher Icon)" else "Enable Special Freeze (Hard Kill + Suspend)"
        val isCustomWidget = FreezerManager.getCustomWidgetApps(this).contains(pkg)
        val widgetLabel = if (isCustomWidget) "📱 Remove from Custom Widget" else "📱 Add to Custom Widget"
        val options = arrayOf("Resume / Unfreeze App", specialLabel, widgetLabel, "Remove from Hibernation List", "Bulk Edit Mode")

        AlertDialog.Builder(this)
            .setTitle(appName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        thread {
                            FreezerManager.unfreezeApp(pkg)
                            runOnUiThread {
                                refreshList()
                                notifyWidgets()
                                Toast.makeText(this, "$appName Unfrozen & Ready", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    1 -> {
                        val newVal = !isSpecial
                        FreezerManager.setSpecialFreeze(this, pkg, newVal)
                        thread {
                            if (newVal) {
                                FreezerManager.freezeApp(this, pkg)
                            } else {
                                ShellUtils.fastCmd("pm unsuspend $pkg 2>/dev/null")
                                FreezerManager.freezeApp(this, pkg)
                            }
                            runOnUiThread {
                                refreshList()
                                notifyWidgets()
                                val msg = if (newVal) "Special Freeze Enabled (Hard Suspend)" else "Special Freeze Disabled (Standard Hibernation Active)"
                                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    2 -> {
                        val added = FreezerManager.toggleCustomWidgetApp(this, pkg)
                        refreshList()
                        notifyWidgets()
                        val msg = if (added) "Added $appName to Custom Widget" else "Removed $appName from Custom Widget"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        val current = FreezerManager.getFrozenApps(this).toMutableSet()
                        current.remove(pkg)
                        thread {
                            FreezerManager.setSpecialFreeze(this, pkg, false)
                            FreezerManager.unfreezeApp(pkg)
                            FreezerManager.saveFrozenApps(this, current)
                            runOnUiThread {
                                refreshList()
                                notifyWidgets()
                                Toast.makeText(this, "Removed and unfreezed $appName", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    4 -> enterEditMode(pkg)
                }
            }
            .show()
    }

    private fun toggleSelection(pkg: String) {
        if (selectedToRemove.contains(pkg)) {
            selectedToRemove.remove(pkg)
        } else {
            selectedToRemove.add(pkg)
        }
        val index = displayItems.indexOfFirst { it.pkg == pkg }
        if (index != -1) {
            adapter.notifyItemChanged(index + 1)
        }
        adapter.notifyItemChanged(0) // update count in header actions
    }

    private fun enterEditMode(initialPkg: String) {
        isEditMode = true
        selectedToRemove.clear()
        selectedToRemove.add(initialPkg)
        adapter.notifyDataSetChanged()
    }

    private fun exitEditMode() {
        isEditMode = false
        selectedToRemove.clear()
        adapter.notifyDataSetChanged()
    }

    private fun removeMultipleApps() {
        if (selectedToRemove.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("Unfreeze Selected?")
            .setMessage("Stop hibernation for ${selectedToRemove.size} apps?")
            .setPositiveButton("Yes") { _, _ ->
                val count = selectedToRemove.size
                val toRemove = selectedToRemove.toSet()
                val current = FreezerManager.getFrozenApps(this).toMutableSet()
                current.removeAll(toRemove)
                FreezerManager.saveFrozenApps(this, current)

                thread {
                    for (pkg in toRemove) {
                        FreezerManager.setSpecialFreeze(this, pkg, false)
                    }
                    FreezerManager.unfreezeMultipleApps(toRemove)
                    runOnUiThread {
                        notifyWidgets()
                        exitEditMode()
                        Toast.makeText(this, "Removed & Unfroze $count apps", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun showSearchableAppPicker() {
        val currentFrozen = FreezerManager.getFrozenApps(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearchApp)
        val lvApps = dialogView.findViewById<ListView>(R.id.lvApps)
        val cbSelectAll = dialogView.findViewById<CheckBox>(R.id.cbSelectAll)
        dialogView.findViewById<View>(R.id.spinnerFilter)?.visibility = View.GONE

        // ⚡ Cache-First: Instant population from memory
        var allApps: List<AppItem> = (cachedInstalledApps ?: emptyList())
            .filter { !currentFrozen.contains(it.info.packageName) }
        for (app in allApps) {
            app.isChecked = false
        }
        var filteredApps: List<AppItem> = allApps
        var pickerAdapter = AppPickerAdapter(filteredApps)
        lvApps.adapter = pickerAdapter

        fun updateList() {
            val query = etSearch.text.toString().trim().lowercase()
            filteredApps = if (query.isEmpty()) {
                allApps
            } else {
                allApps.filter { it.label.lowercase().contains(query) || it.info.packageName.lowercase().contains(query) }
            }
            pickerAdapter = AppPickerAdapter(filteredApps)
            lvApps.adapter = pickerAdapter
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            for (app in filteredApps) app.isChecked = isChecked
            pickerAdapter.notifyDataSetChanged()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Apps to Hibernate")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val newlySelected = allApps.filter { it.isChecked }.map { it.info.packageName }.toSet()
                if (newlySelected.isNotEmpty()) {
                    val updatedSet = currentFrozen.toMutableSet().apply { addAll(newlySelected) }
                    FreezerManager.saveFrozenApps(this, updatedSet)
                    refreshList()
                    notifyWidgets()
                    Toast.makeText(this, "Added ${newlySelected.size} apps to Hibernation list", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        // ⚡ Dialog pops up IMMEDIATELY with zero lag!
        dialog.show()

        // Background silent revalidation & update if cache was empty or packages changed
        thread {
            val freshInstalled = getInstalledAppsList()
            val freshAvailable = freshInstalled.filter { !currentFrozen.contains(it.info.packageName) }
            if (freshAvailable.isNotEmpty() && (allApps.isEmpty() || freshAvailable.size != allApps.size)) {
                allApps = freshAvailable
                for (app in allApps) app.isChecked = false
                runOnUiThread {
                    if (dialog.isShowing && !isFinishing && !isDestroyed) {
                        updateList()
                    }
                }
            }
        }
    }

    private fun showCustomWidgetAppPicker() {
        val allFrozen = FreezerManager.getFrozenApps(this)
        if (allFrozen.isEmpty()) {
            Toast.makeText(this, "No apps in hibernation list yet! Add apps first.", Toast.LENGTH_SHORT).show()
            return
        }

        val currentCustom = FreezerManager.getCustomWidgetApps(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearchApp)
        val lvApps = dialogView.findViewById<ListView>(R.id.lvApps)
        val cbSelectAll = dialogView.findViewById<CheckBox>(R.id.cbSelectAll)
        dialogView.findViewById<View>(R.id.spinnerFilter)?.visibility = View.GONE

        // ⚡ Cache-First: Instant population from appInfoCache / cachedDisplayItems
        var allItems: List<AppItem> = allFrozen.mapNotNull { pkg ->
            val cachedInfo = appInfoCache[pkg]
            if (cachedInfo != null) {
                val appInfo = try { pm.getApplicationInfo(pkg, 0) } catch (e: Exception) { null }
                if (appInfo != null) {
                    AppItem(appInfo, cachedInfo.first).apply {
                        isChecked = currentCustom.contains(pkg)
                    }
                } else null
            } else null
        }.sortedBy { it.label.lowercase() }

        var filteredItems: List<AppItem> = allItems
        var pickerAdapter = AppPickerAdapter(filteredItems)
        lvApps.adapter = pickerAdapter

        fun updateList() {
            val query = etSearch.text.toString().trim().lowercase()
            filteredItems = if (query.isEmpty()) {
                allItems
            } else {
                allItems.filter { it.label.lowercase().contains(query) || it.info.packageName.lowercase().contains(query) }
            }
            pickerAdapter = AppPickerAdapter(filteredItems)
            lvApps.adapter = pickerAdapter
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateList() }
            override fun afterTextChanged(s: Editable?) {}
        })

        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            for (app in filteredItems) app.isChecked = isChecked
            pickerAdapter.notifyDataSetChanged()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select Custom Widget Apps")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val selected = allItems.filter { it.isChecked }.map { it.info.packageName }.toSet()
                FreezerManager.saveCustomWidgetApps(this, selected)
                refreshList()
                notifyWidgets()
                Toast.makeText(this, "Saved ${selected.size} apps for Custom Widget", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()

        // ⚡ Dialog pops up IMMEDIATELY!
        dialog.show()

        // Background revalidate if any app was not yet in cache
        thread {
            val freshItems = allFrozen.mapNotNull { pkg ->
                try {
                    val appInfo = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val icon = try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null }
                    appInfoCache[pkg] = Pair(label, icon)
                    AppItem(appInfo, label).apply {
                        isChecked = currentCustom.contains(pkg)
                    }
                } catch (e: Exception) {
                    null
                }
            }.sortedBy { it.label.lowercase() }

            if (freshItems.size != allItems.size) {
                allItems = freshItems
                runOnUiThread {
                    if (dialog.isShowing && !isFinishing && !isDestroyed) {
                        updateList()
                    }
                }
            }
        }
    }

    private fun getInstalledAppsList(): List<AppItem> {
        val cached = cachedInstalledApps
        if (!cached.isNullOrEmpty()) return cached

        val list = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != packageName }
            .map {
                val label = pm.getApplicationLabel(it).toString()
                if (!appInfoCache.containsKey(it.packageName)) {
                    appInfoCache[it.packageName] = Pair(label, null)
                }
                AppItem(it, label)
            }
            .sortedBy { it.label.lowercase() }
        cachedInstalledApps = list
        return list
    }

    // High-Performance Recycled List Adapter (0ms latency, zero main-thread blockage)
    private inner class FrozenAppsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_HEADER = 0
        private val TYPE_ITEM = 1

        override fun getItemCount(): Int = displayItems.size + 1

        override fun getItemViewType(position: Int): Int {
            return if (position == 0) TYPE_HEADER else TYPE_ITEM
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                val view = inflater.inflate(R.layout.layout_freezer_header, parent, false)
                HeaderViewHolder(view)
            } else {
                val view = inflater.inflate(R.layout.item_app_picker, parent, false)
                AppViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeaderViewHolder) {
                holder.bind()
            } else if (holder is AppViewHolder) {
                val item = displayItems[position - 1]
                holder.bind(item)
            }
        }
    }

    private inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val switchAuto: SwitchMaterial = itemView.findViewById(R.id.switchAutoFreeze)
        val btnFreezeAll: MaterialButton = itemView.findViewById(R.id.btnFreezeAll)
        val btnAddApps: MaterialButton = itemView.findViewById(R.id.btnAddApps)
        val btnCustomWidgetApps: MaterialButton = itemView.findViewById(R.id.btnCustomWidgetApps)

        val switchEq: SwitchMaterial = itemView.findViewById(R.id.switchEqualizerGuard)
        val tvDetectedEqualizer: TextView = itemView.findViewById(R.id.tvDetectedEqualizer)
        val btnChangeEqualizer: MaterialButton = itemView.findViewById(R.id.btnChangeEqualizer)

        val layoutEditActions: LinearLayout = itemView.findViewById(R.id.layoutEditActions)
        val tvEditModeTitle: TextView = itemView.findViewById(R.id.tvEditModeTitle)
        val btnRemoveSelected: MaterialButton = itemView.findViewById(R.id.btnRemoveSelected)
        val btnCancelEdit: MaterialButton = itemView.findViewById(R.id.btnCancelEdit)

        val tvHibernatingTitle: TextView = itemView.findViewById(R.id.tvHibernatingTitle)
        val tvEmptyState: TextView = itemView.findViewById(R.id.tvEmptyState)

        fun bind() {
            // Auto Freeze
            switchAuto.setOnCheckedChangeListener(null)
            switchAuto.isChecked = autoFreezeEnabled
            switchAuto.setOnCheckedChangeListener { _, isChecked ->
                autoFreezeEnabled = isChecked
                FreezerManager.setAutoFreezeEnabled(this@AppFreezerListActivity, isChecked)
                Toast.makeText(this@AppFreezerListActivity, if (isChecked) "Auto-Freeze on exit & recents swipe enabled" else "Auto-Freeze disabled", Toast.LENGTH_SHORT).show()
            }

            btnFreezeAll.setOnClickListener { freezeAll() }
            btnAddApps.setOnClickListener { showSearchableAppPicker() }
            btnCustomWidgetApps.setOnClickListener { showCustomWidgetAppPicker() }

            // Equalizer Guard
            switchEq.setOnCheckedChangeListener(null)
            switchEq.isChecked = eqGuardEnabled
            switchEq.setOnCheckedChangeListener { _, isChecked ->
                eqGuardEnabled = isChecked
                FreezerManager.setEqualizerSleepEnabled(this@AppFreezerListActivity, isChecked)
                val msg = if (isChecked) "Smart Equalizer Guard Enabled (0ms Audio Sleep Active)" else "Smart Equalizer Guard Disabled"
                Toast.makeText(this@AppFreezerListActivity, msg, Toast.LENGTH_SHORT).show()
            }

            tvDetectedEqualizer.text = detectedEqText
            tvDetectedEqualizer.setTextColor(detectedEqColor)
            btnChangeEqualizer.setOnClickListener {
                showEqualizerPicker {
                    setupEqualizerGuardUI()
                }
            }

            // Edit Mode Actions Bar
            layoutEditActions.visibility = if (isEditMode) View.VISIBLE else View.GONE
            val count = selectedToRemove.size
            tvEditModeTitle.text = if (count == 0) "Select apps to unfreeze" else "$count apps selected"
            btnRemoveSelected.text = if (count == 0) "Unfreeze Selected" else "Unfreeze ($count)"
            btnRemoveSelected.setOnClickListener { removeMultipleApps() }
            btnCancelEdit.setOnClickListener { exitEditMode() }

            // Section Title & Empty State
            tvHibernatingTitle.text = if (displayItems.isEmpty()) "Hibernating Apps" else "Hibernating Apps (${displayItems.size})"
            tvEmptyState.visibility = if (displayItems.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        val tvPkg: TextView = itemView.findViewById(R.id.tvPackageName)
        val tvStatus: TextView = itemView.findViewById(R.id.tvAppStatus)
        val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)

        fun bind(item: FrozenDisplayItem) {
            cbSelect.visibility = if (isEditMode) View.VISIBLE else View.GONE
            cbSelect.isChecked = selectedToRemove.contains(item.pkg)

            if (item.icon != null) {
                ivIcon.setImageDrawable(item.icon)
            } else {
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            tvName.text = item.name
            tvPkg.text = item.pkg

            val widgetTag = if (item.isCustomWidget) " • 📱 Widget" else ""

            if (item.isSpecial) {
                tvStatus.text = "Special Freeze (Suspended)$widgetTag"
                tvStatus.setTextColor(Color.parseColor("#FF5252"))
            } else if (item.isActive) {
                tvStatus.text = "Active in Memory$widgetTag"
                tvStatus.setTextColor(Color.parseColor("#00E676"))
            } else {
                tvStatus.text = "Hibernated (0% CPU)$widgetTag"
                tvStatus.setTextColor(Color.parseColor("#00E5FF"))
            }

            itemView.setOnClickListener {
                if (isEditMode) {
                    toggleSelection(item.pkg)
                } else {
                    showAppOptionsDialog(item.pkg, item.name)
                }
            }

            itemView.setOnLongClickListener {
                if (!isEditMode) {
                    enterEditMode(item.pkg)
                }
                true
            }
        }
    }

    private inner class AppPickerAdapter(val items: List<AppItem>) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): AppItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@AppFreezerListActivity).inflate(R.layout.item_app_picker, parent, false)
            val item = getItem(position)

            val ivIcon = view.findViewById<ImageView>(R.id.ivAppIcon)
            val tvName = view.findViewById<TextView>(R.id.tvAppName)
            val tvPkg = view.findViewById<TextView>(R.id.tvPackageName)
            val cbSelect = view.findViewById<CheckBox>(R.id.cbSelect)
            val tvStatus = view.findViewById<TextView>(R.id.tvAppStatus)

            tvName.text = item.label
            tvPkg.text = item.info.packageName
            cbSelect.visibility = View.VISIBLE
            cbSelect.isChecked = item.isChecked
            tvStatus.visibility = View.GONE

            val cachedIcon = appInfoCache[item.info.packageName]?.second
            if (cachedIcon != null) {
                ivIcon.setImageDrawable(cachedIcon)
            } else {
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                thread {
                    val icon = try { pm.getApplicationIcon(item.info) } catch (e: Exception) { null }
                    if (icon != null) {
                        appInfoCache[item.info.packageName] = Pair(item.label, icon)
                        runOnUiThread { ivIcon.setImageDrawable(icon) }
                    }
                }
            }

            view.setOnClickListener {
                item.isChecked = !item.isChecked
                cbSelect.isChecked = item.isChecked
            }

            cbSelect.setOnClickListener {
                item.isChecked = cbSelect.isChecked
            }

            return view
        }
    }

    private inner class EqualizerPickerAdapter(
        var items: List<AppItem>,
        val currentSelectedPkg: String?,
        val onSelect: (AppItem) -> Unit
    ) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): AppItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@AppFreezerListActivity).inflate(R.layout.item_app_picker, parent, false)
            val item = getItem(position)

            val ivIcon = view.findViewById<ImageView>(R.id.ivAppIcon)
            val tvName = view.findViewById<TextView>(R.id.tvAppName)
            val tvPkg = view.findViewById<TextView>(R.id.tvPackageName)
            val cbSelect = view.findViewById<CheckBox>(R.id.cbSelect)
            val tvStatus = view.findViewById<TextView>(R.id.tvAppStatus)

            cbSelect.visibility = View.GONE
            tvName.text = item.label
            tvPkg.text = item.info.packageName

            val isCurrent = (item.info.packageName == currentSelectedPkg)
            if (isCurrent) {
                tvStatus.text = "Target"
                tvStatus.setTextColor(Color.parseColor("#00E676"))
                tvStatus.visibility = View.VISIBLE
            } else {
                tvStatus.visibility = View.GONE
            }

            val cachedIcon = appInfoCache[item.info.packageName]?.second
            if (cachedIcon != null) {
                ivIcon.setImageDrawable(cachedIcon)
            } else {
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                thread {
                    val icon = try { pm.getApplicationIcon(item.info) } catch (e: Exception) { null }
                    if (icon != null) {
                        appInfoCache[item.info.packageName] = Pair(item.label, icon)
                        runOnUiThread { ivIcon.setImageDrawable(icon) }
                    }
                }
            }

            view.setOnClickListener {
                onSelect(item)
            }

            return view
        }
    }
}
