package com.example.phonecontrol

import android.app.ProgressDialog
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

class AppFreezerListActivity : AppCompatActivity() {

    private lateinit var layoutFrozenAppsList: LinearLayout
    private lateinit var pm: PackageManager
    private lateinit var layoutEditActions: LinearLayout
    private var isEditMode = false
    private val selectedToRemove = mutableSetOf<String>()

    private var cachedAppsList: List<AppItem>? = null

    data class AppItem(val info: ApplicationInfo, val label: String, var isChecked: Boolean = false)
    data class FrozenDisplayItem(
        val pkg: String,
        val name: String,
        val icon: Drawable?,
        val isSpecial: Boolean,
        val isActive: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_freezer_list)

        pm = packageManager
        findViewById<MaterialToolbar>(R.id.toolbarAppFreezerList).setNavigationOnClickListener { finish() }

        layoutFrozenAppsList = findViewById(R.id.layoutFrozenAppsList)
        layoutEditActions = findViewById(R.id.layoutEditActions)
        val switchAuto = findViewById<SwitchMaterial>(R.id.switchAutoFreeze)
        val btnFreezeAll = findViewById<MaterialButton>(R.id.btnFreezeAll)
        val btnAdd = findViewById<MaterialButton>(R.id.btnAddApps)
        val btnRemoveSelected = findViewById<MaterialButton>(R.id.btnRemoveSelected)
        val btnCancelEdit = findViewById<MaterialButton>(R.id.btnCancelEdit)

        val prefs = getSharedPreferences("freezer_prefs", Context.MODE_PRIVATE)
        switchAuto.isChecked = prefs.getBoolean("auto_freeze_enabled", false)

        switchAuto.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_freeze_enabled", isChecked).apply()
            Toast.makeText(this, if (isChecked) "Auto-Freeze on screen off enabled" else "Auto-Freeze disabled", Toast.LENGTH_SHORT).show()
        }

        btnFreezeAll.setOnClickListener { freezeAll() }
        btnAdd.setOnClickListener { showSearchableAppPicker() }
        findViewById<MaterialButton>(R.id.btnCustomWidgetApps).setOnClickListener { showCustomWidgetAppPicker() }
        btnRemoveSelected.setOnClickListener { removeMultipleApps() }
        btnCancelEdit.setOnClickListener { exitEditMode() }

        setupEqualizerGuardUI()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        setupEqualizerGuardUI()
        refreshList()
    }

    private fun setupEqualizerGuardUI() {
        val switchEq = findViewById<SwitchMaterial?>(R.id.switchEqualizerGuard) ?: return
        val tvDetected = findViewById<TextView?>(R.id.tvDetectedEqualizer) ?: return
        val btnChange = findViewById<MaterialButton?>(R.id.btnChangeEqualizer) ?: return

        fun updateUI() {
            val isEnabled = FreezerManager.isEqualizerSleepEnabled(this)
            switchEq.isChecked = isEnabled

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
                tvDetected.text = "Target: $appLabel$statusSuffix"
                tvDetected.setTextColor(
                    if (isMusicPlaying) android.graphics.Color.parseColor("#00E676")
                    else if (isFrozen) android.graphics.Color.parseColor("#00E5FF")
                    else android.graphics.Color.parseColor("#FFD700")
                )
            } else {
                tvDetected.text = "No Equalizer App Found"
                tvDetected.setTextColor(android.graphics.Color.GRAY)
            }
        }

        updateUI()

        switchEq.setOnCheckedChangeListener { _, isChecked ->
            FreezerManager.setEqualizerSleepEnabled(this, isChecked)
            val msg = if (isChecked) "Smart Equalizer Guard Enabled (0ms Audio Sleep Active)" else "Smart Equalizer Guard Disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        btnChange.setOnClickListener {
            showEqualizerPicker {
                updateUI()
            }
        }
    }

    private fun showEqualizerPicker(onSelected: () -> Unit) {
        val installedApps = getInstalledAppsList().sortedBy { it.label.lowercase() }
        val names = installedApps.map { "${it.label} (${it.info.packageName})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Audio Equalizer / DSP App")
            .setItems(names) { _, which ->
                val selectedPkg = installedApps[which].info.packageName
                FreezerManager.saveSelectedEqualizer(this, selectedPkg)
                FreezerManager.setEqualizerSleepEnabled(this, true)
                onSelected()
                Toast.makeText(this, "Selected: ${installedApps[which].label}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Auto-Detect") { _, _ ->
                FreezerManager.saveSelectedEqualizer(this, null)
                onSelected()
                Toast.makeText(this, "Reset to Auto-Detection", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshList() {
        val frozenApps = FreezerManager.getFrozenApps(this)
        
        if (frozenApps.isEmpty()) {
            layoutFrozenAppsList.removeAllViews()
            val tv = TextView(this).apply {
                text = "No apps in hibernation list.\nTap '+ Add Apps' above to select apps to freeze."
                setTextColor(android.graphics.Color.GRAY)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 50, 0, 0)
                textSize = 12f
            }
            layoutFrozenAppsList.addView(tv)
            return
        }

        thread {
            val activeSet = FreezerManager.getActivePackages(frozenApps)
            val displayItems = frozenApps.map { pkg ->
                var name = "Unknown App"
                var icon: Drawable? = null
                try {
                    val appInfo = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    icon = pm.getApplicationIcon(appInfo)
                    name = pm.getApplicationLabel(appInfo).toString()
                } catch (ignored: Exception) {}
                
                val isSpecial = FreezerManager.isSpecialFreeze(this, pkg)
                val isActive = activeSet.contains(pkg)
                FrozenDisplayItem(pkg, name, icon, isSpecial, isActive)
            }.sortedBy { it.name.lowercase() }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                layoutFrozenAppsList.removeAllViews()
                for (item in displayItems) {
                    addAppViewFromItem(item)
                }
            }
        }
    }

    private fun addAppViewFromItem(item: FrozenDisplayItem) {
        val view = layoutInflater.inflate(R.layout.item_app_picker, layoutFrozenAppsList, false)
        val ivIcon = view.findViewById<ImageView>(R.id.ivAppIcon)
        val tvName = view.findViewById<TextView>(R.id.tvAppName)
        val tvPkg = view.findViewById<TextView>(R.id.tvPackageName)
        val tvStatus = view.findViewById<TextView>(R.id.tvAppStatus)
        val cbSelect = view.findViewById<CheckBox>(R.id.cbSelect)
        
        cbSelect.visibility = if (isEditMode) View.VISIBLE else View.GONE
        cbSelect.isChecked = selectedToRemove.contains(item.pkg)

        if (item.icon != null) {
            ivIcon.setImageDrawable(item.icon)
        } else {
            ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        tvName.text = item.name
        tvPkg.text = item.pkg
        
        val isCustomWidget = FreezerManager.getCustomWidgetApps(this).contains(item.pkg)
        val widgetTag = if (isCustomWidget) " • 📱 Widget" else ""

        if (item.isSpecial) {
            tvStatus.text = "Special Freeze (Suspended)$widgetTag"
            tvStatus.setTextColor(android.graphics.Color.parseColor("#FF5252"))
        } else if (item.isActive) {
            tvStatus.text = "Active in Memory$widgetTag"
            tvStatus.setTextColor(android.graphics.Color.parseColor("#00E676"))
        } else {
            tvStatus.text = "Hibernated (0% CPU)$widgetTag"
            tvStatus.setTextColor(android.graphics.Color.parseColor("#00E5FF"))
        }

        view.setOnClickListener {
            if (isEditMode) {
                toggleSelection(item.pkg, cbSelect)
            } else {
                showAppOptionsDialog(item.pkg, item.name)
            }
        }

        view.setOnLongClickListener {
            if (!isEditMode) {
                enterEditMode(item.pkg)
            }
            true
        }

        layoutFrozenAppsList.addView(view)
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

    private fun toggleSelection(pkg: String, cb: CheckBox) {
        if (selectedToRemove.contains(pkg)) {
            selectedToRemove.remove(pkg)
            cb.isChecked = false
        } else {
            selectedToRemove.add(pkg)
            cb.isChecked = true
        }
    }

    private fun enterEditMode(initialPkg: String) {
        isEditMode = true
        selectedToRemove.clear()
        selectedToRemove.add(initialPkg)
        layoutEditActions.visibility = View.VISIBLE
        refreshList()
    }

    private fun exitEditMode() {
        isEditMode = false
        selectedToRemove.clear()
        layoutEditActions.visibility = View.GONE
        refreshList()
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
                        Toast.makeText(this, "Unfroze $count apps", Toast.LENGTH_SHORT).show()
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

        var allApps: List<AppItem> = emptyList()
        var filteredApps: List<AppItem> = emptyList()
        var adapter: AppPickerAdapter? = null

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

        fun updateList() {
            val query = etSearch.text.toString().trim().lowercase()
            filteredApps = if (query.isEmpty()) {
                allApps
            } else {
                allApps.filter { it.label.lowercase().contains(query) || it.info.packageName.lowercase().contains(query) }
            }
            adapter = AppPickerAdapter(filteredApps)
            lvApps.adapter = adapter
        }

        thread {
            val availableApps = getInstalledAppsList().filter { !currentFrozen.contains(it.info.packageName) }
            
            if (availableApps.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "All installed apps are already added!", Toast.LENGTH_SHORT).show()
                }
                return@thread
            }

            allApps = availableApps
            for (app in allApps) {
                app.isChecked = false
            }
            filteredApps = allApps

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter = AppPickerAdapter(filteredApps)
                lvApps.adapter = adapter

                etSearch.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        updateList()
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })

                cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
                    for (app in filteredApps) app.isChecked = isChecked
                    adapter?.notifyDataSetChanged()
                }

                dialog.show()
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

        var allItems: List<AppItem> = emptyList()
        var filteredItems: List<AppItem> = emptyList()
        var adapter: AppPickerAdapter? = null

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

        fun updateList() {
            val query = etSearch.text.toString().trim().lowercase()
            filteredItems = if (query.isEmpty()) {
                allItems
            } else {
                allItems.filter { it.label.lowercase().contains(query) || it.info.packageName.lowercase().contains(query) }
            }
            adapter = AppPickerAdapter(filteredItems)
            lvApps.adapter = adapter
        }

        thread {
            val items = allFrozen.mapNotNull { pkg ->
                try {
                    val appInfo = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    AppItem(appInfo, label).apply {
                        isChecked = currentCustom.contains(pkg)
                    }
                } catch (e: Exception) {
                    null
                }
            }.sortedBy { it.label.lowercase() }

            allItems = items
            filteredItems = allItems

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter = AppPickerAdapter(filteredItems)
                lvApps.adapter = adapter

                etSearch.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateList() }
                    override fun afterTextChanged(s: Editable?) {}
                })

                cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
                    for (app in filteredItems) app.isChecked = isChecked
                    adapter?.notifyDataSetChanged()
                }

                dialog.show()
            }
        }
    }

    private fun getInstalledAppsList(): List<AppItem> {
        val list = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != packageName }
            .map { AppItem(it, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
        return list
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

            ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            thread {
                val icon = try { pm.getApplicationIcon(item.info) } catch (e: Exception) { null }
                if (icon != null) {
                    runOnUiThread { ivIcon.setImageDrawable(icon) }
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
}
