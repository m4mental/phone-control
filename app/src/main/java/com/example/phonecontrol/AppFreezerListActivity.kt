package com.example.phonecontrol

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlin.concurrent.thread

class AppFreezerListActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var pm: PackageManager

    // Tab 1: Normal Freezer
    private lateinit var rvNormalFreezer: RecyclerView
    private lateinit var normalAdapter: NormalFreezerAdapter
    private var normalDisplayItems: List<FrozenDisplayItem> = emptyList()
    private var isNormalEditMode = false
    private val selectedNormalToRemove = mutableSetOf<String>()

    // Tab 2: Special Freezer
    private lateinit var rvSpecialFreezer: RecyclerView
    private lateinit var specialAdapter: SpecialFreezerAdapter
    private var specialDisplayItems: List<FrozenDisplayItem> = emptyList()
    private var isSpecialEditMode = false
    private val selectedSpecialToRemove = mutableSetOf<String>()

    // Tab 3: Immunity Manager
    private lateinit var rvImmunityList: RecyclerView
    private lateinit var immunityAdapter: ImmunityAdapter
    private var immunityDisplayItems: List<ImmunityDisplayItem> = emptyList()

    // Normal Header State
    private var autoFreezeEnabled: Boolean = false
    private var eqGuardEnabled: Boolean = false
    private var detectedEqText: String = "Detected: None"
    private var detectedEqColor: Int = Color.GRAY

    companion object {
        var cachedNormalItems: List<FrozenDisplayItem>? = null
        var cachedSpecialItems: List<FrozenDisplayItem>? = null
        var cachedImmunityItems: List<ImmunityDisplayItem>? = null
        var cachedInstalledApps: List<AppItem>? = null
        val appInfoCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Drawable?>>()

        val CRITICAL_SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.android.providers.settings",
            "com.android.settings",
            "com.google.android.inputmethod.latin",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller"
        )
    }

    data class AppItem(val info: ApplicationInfo, val label: String, var isChecked: Boolean = false)
    data class FrozenDisplayItem(
        val pkg: String,
        val name: String,
        val icon: Drawable?,
        val isSpecial: Boolean,
        val isActive: Boolean,
        val isCustomWidget: Boolean = false,
        val isSystem: Boolean = false
    )
    data class ImmunityDisplayItem(
        val pkg: String,
        val name: String,
        val icon: Drawable?,
        var isImmune: Boolean,
        val hasActiveTask: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_freezer_list)

        pm = packageManager
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarAppFreezerList)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.menu_app_freezer_list)
        toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.action_live_services) {
                startActivity(Intent(this, RunningServicesActivity::class.java))
                true
            } else false
        }

        tabLayout = findViewById(R.id.tabLayoutFreezer)
        viewPager = findViewById(R.id.viewPagerFreezer)
        viewPager.offscreenPageLimit = 2

        autoFreezeEnabled = FreezerManager.isAutoFreezeEnabled(this)

        // Inflate Tab Pages
        val inflater = LayoutInflater.from(this)
        val pageNormal = inflater.inflate(R.layout.layout_tab_freezer_normal, viewPager, false)
        val pageSpecial = inflater.inflate(R.layout.layout_tab_freezer_special, viewPager, false)
        val pageImmunity = inflater.inflate(R.layout.layout_tab_freezer_immunity, viewPager, false)

        // Setup Tab 1
        rvNormalFreezer = pageNormal.findViewById(R.id.rvNormalFreezer)
        rvNormalFreezer.layoutManager = LinearLayoutManager(this)
        rvNormalFreezer.setHasFixedSize(true)
        rvNormalFreezer.setItemViewCacheSize(25)
        normalAdapter = NormalFreezerAdapter()
        rvNormalFreezer.adapter = normalAdapter

        // Setup Tab 2
        rvSpecialFreezer = pageSpecial.findViewById(R.id.rvSpecialFreezer)
        rvSpecialFreezer.layoutManager = LinearLayoutManager(this)
        rvSpecialFreezer.setHasFixedSize(true)
        rvSpecialFreezer.setItemViewCacheSize(25)
        specialAdapter = SpecialFreezerAdapter()
        rvSpecialFreezer.adapter = specialAdapter

        // Setup Tab 3
        rvImmunityList = pageImmunity.findViewById(R.id.rvImmunityList)
        rvImmunityList.layoutManager = LinearLayoutManager(this)
        rvImmunityList.setHasFixedSize(true)
        rvImmunityList.setItemViewCacheSize(25)
        immunityAdapter = ImmunityAdapter()
        rvImmunityList.adapter = immunityAdapter

        val pages = listOf(pageNormal, pageSpecial, pageImmunity)
        viewPager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = pages.size
            override fun getItemViewType(position: Int): Int = position
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val page = pages[viewType]
                if (page.parent != null) {
                    (page.parent as? ViewGroup)?.removeView(page)
                }
                return object : RecyclerView.ViewHolder(page) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
        }

        // Attach ViewPager2 with TabLayout with smooth indicator animations
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            when (position) {
                0 -> tab.text = "❄️ Normal Freezer"
                1 -> tab.text = "🔒 Special Freezer"
                2 -> tab.text = "🛡️ FGS Immunity"
            }
        }.attach()

        // Background Pre-warming for 0ms instant app picker dialogs
        cachedInstalledApps = null
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

        if (::normalAdapter.isInitialized) {
            normalAdapter.notifyItemChanged(0)
        }
    }

    private fun refreshList() {
        FreezerManager.pruneUninstalledPackages(this)
        val frozenApps = FreezerManager.getFrozenApps(this)
        val specialApps = FreezerManager.getSpecialFreezeApps(this)

        // 1. CACHE-FIRST: Instant 0ms render from memory cache
        if (cachedNormalItems != null) normalDisplayItems = cachedNormalItems!!
        if (cachedSpecialItems != null) specialDisplayItems = cachedSpecialItems!!
        if (cachedImmunityItems != null) immunityDisplayItems = cachedImmunityItems!!

        if (::normalAdapter.isInitialized) normalAdapter.notifyDataSetChanged()
        if (::specialAdapter.isInitialized) specialAdapter.notifyDataSetChanged()
        if (::immunityAdapter.isInitialized) immunityAdapter.notifyDataSetChanged()

        // 2. SILENT BACKGROUND REVALIDATE:
        thread {
            val activeSet = FreezerManager.getActivePackages(this@AppFreezerListActivity, frozenApps)
            val customWidgetSet = FreezerManager.getCustomWidgetApps(this)
            val fgsImmuneSet = FreezerManager.getFgsImmuneApps(this)

            val normalPkgs = frozenApps.filter { !specialApps.contains(it) }
            val specialPkgs = specialApps.toList()

            val freshNormalItems = normalPkgs.mapNotNull { pkg ->
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val cachedInfo = appInfoCache[pkg]
                    val name = cachedInfo?.first ?: pm.getApplicationLabel(appInfo).toString()
                    val icon = cachedInfo?.second ?: try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null }
                    appInfoCache[pkg] = Pair(name, icon)

                    val isActive = activeSet.contains(pkg)
                    val isCustomWidget = customWidgetSet.contains(pkg)
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    FrozenDisplayItem(pkg, name, icon, isSpecial = false, isActive = isActive, isCustomWidget = isCustomWidget, isSystem = isSystem)
                } catch (e: Exception) {
                    null
                }
            }.sortedBy { it.name.lowercase() }

            val freshSpecialItems = specialPkgs.mapNotNull { pkg ->
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val cachedInfo = appInfoCache[pkg]
                    val name = cachedInfo?.first ?: pm.getApplicationLabel(appInfo).toString()
                    val icon = cachedInfo?.second ?: try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null }
                    appInfoCache[pkg] = Pair(name, icon)

                    val isActive = activeSet.contains(pkg)
                    val isCustomWidget = customWidgetSet.contains(pkg)
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    FrozenDisplayItem(pkg, name, icon, isSpecial = true, isActive = isActive, isCustomWidget = isCustomWidget, isSystem = isSystem)
                } catch (e: Exception) {
                    null
                }
            }.sortedBy { it.name.lowercase() }

            val freshImmunityItems = freshNormalItems.map { normalItem ->
                val isImmune = fgsImmuneSet.contains(normalItem.pkg)
                val prevItem = cachedImmunityItems?.find { it.pkg == normalItem.pkg }
                ImmunityDisplayItem(
                    pkg = normalItem.pkg,
                    name = normalItem.name,
                    icon = normalItem.icon,
                    isImmune = isImmune,
                    hasActiveTask = prevItem?.hasActiveTask ?: false
                )
            }.toMutableList()

            cachedNormalItems = freshNormalItems
            cachedSpecialItems = freshSpecialItems
            cachedImmunityItems = freshImmunityItems

            // 1. Publish Normal and Special lists immediately with zero blocking delay
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                normalDisplayItems = freshNormalItems
                specialDisplayItems = freshSpecialItems
                immunityDisplayItems = freshImmunityItems
                normalAdapter.notifyDataSetChanged()
                specialAdapter.notifyDataSetChanged()
                immunityAdapter.notifyDataSetChanged()
            }

            // 2. Defer task-status checks in background and update immunity statuses afterward
            val updatedImmunityItems = freshNormalItems.map { normalItem ->
                val isImmune = fgsImmuneSet.contains(normalItem.pkg)
                val hasTask = if (activeSet.contains(normalItem.pkg)) {
                    RecentTasksManager.hasActiveForegroundTask(normalItem.pkg)
                } else {
                    false
                }
                ImmunityDisplayItem(
                    pkg = normalItem.pkg,
                    name = normalItem.name,
                    icon = normalItem.icon,
                    isImmune = isImmune,
                    hasActiveTask = hasTask
                )
            }

            cachedImmunityItems = updatedImmunityItems
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                immunityDisplayItems = updatedImmunityItems
                immunityAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun freezeAllNormal() {
        val normalApps = normalDisplayItems.map { it.pkg }.toSet()
        if (normalApps.isEmpty()) {
            Toast.makeText(this, "No apps in Normal Freezer list!", Toast.LENGTH_SHORT).show()
            return
        }

        val progress = ProgressDialog(this).apply {
            setMessage("Hibernating ${normalApps.size} Normal apps...")
            setCancelable(false)
            show()
        }

        thread {
            FreezerManager.freezeMultipleApps(this, normalApps, force = true)
            val estimatedRamMb = (normalApps.size * 110).coerceAtLeast(100)
            runOnUiThread {
                progress.dismiss()
                refreshList()
                notifyWidgets()
                Toast.makeText(this, "❄️ Hibernated ${normalApps.size} apps! ~${estimatedRamMb} MB background RAM reclaimed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun freezeAllSpecial() {
        val specialApps = specialDisplayItems.map { it.pkg }.toSet()
        if (specialApps.isEmpty()) {
            Toast.makeText(this, "No apps in Special Freeze list!", Toast.LENGTH_SHORT).show()
            return
        }

        val progress = ProgressDialog(this).apply {
            setMessage("Suspending ${specialApps.size} Special apps...")
            setCancelable(false)
            show()
        }

        thread {
            FreezerManager.freezeMultipleApps(this, specialApps, force = true)
            runOnUiThread {
                progress.dismiss()
                refreshList()
                notifyWidgets()
                Toast.makeText(this, "🔒 Suspended ${specialApps.size} Special isolated apps!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun notifyWidgets() {
        FreezerWidgetProvider.updateAllWidgets(this)
        SpecialFreezerWidgetProvider.updateAllWidgets(this)
    }

    private fun showNormalAppOptionsDialog(pkg: String, appName: String) {
        val isCustomWidget = FreezerManager.getCustomWidgetApps(this).contains(pkg)
        val widgetLabel = if (isCustomWidget) "📱 Remove from Custom Widget" else "📱 Add to Custom Widget"
        val options = arrayOf(
            "🚀 Launch App",
            "❄️ Resume / Unfreeze App",
            "🔒 Move to Special Freeze (Deep Suspend)",
            widgetLabel,
            "Remove from Hibernation List",
            "Bulk Edit Mode"
        )

        AlertDialog.Builder(this)
            .setTitle(appName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        FreezerManager.launchApp(this, pkg)
                        Toast.makeText(this, "Launching $appName...", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        thread {
                            FreezerManager.unfreezeApp(pkg)
                            runOnUiThread {
                                refreshList()
                                notifyWidgets()
                                Toast.makeText(this, "$appName Unfrozen & Ready", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    2 -> {
                        FreezerManager.setSpecialFreeze(this, pkg, true)
                        thread {
                            FreezerManager.freezeApp(this, pkg, force = true)
                            runOnUiThread {
                                refreshList()
                                notifyWidgets()
                                Toast.makeText(this, "Moved $appName to Special Freeze (Suspended)", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    3 -> {
                        val added = FreezerManager.toggleCustomWidgetApp(this, pkg)
                        refreshList()
                        notifyWidgets()
                        val msg = if (added) "Added $appName to Custom Widget" else "Removed $appName from Custom Widget"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        val current = FreezerManager.getFrozenApps(this).toMutableSet()
                        current.remove(pkg)
                        thread {
                            FreezerManager.removeAppFromFreezer(this, pkg)
                            runOnUiThread {
                                refreshList()
                                notifyWidgets()
                                Toast.makeText(this, "Removed and unfreezed $appName", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    5 -> enterNormalEditMode(pkg)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSpecialAppOptionsDialog(pkg: String, appName: String) {
        val isCustomWidget = FreezerManager.getCustomWidgetApps(this).contains(pkg)
        val widgetLabel = if (isCustomWidget) "📱 Remove from Custom Widget" else "📱 Add to Custom Widget"
        val options = arrayOf(
            "🚀 Launch App",
            "❄️ Unsuspend & Restore App",
            "❄️ Move to Normal Freezer (am freeze)",
            widgetLabel,
            "Remove from Special Freeze List",
            "Bulk Edit Mode"
        )

        AlertDialog.Builder(this)
            .setTitle("🔒 $appName (Special Freeze)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        FreezerManager.launchApp(this, pkg)
                        Toast.makeText(this, "Launching $appName...", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        thread {
                            ShellUtils.fastCmd("cmd package unsuspend --user 0 $pkg 2>/dev/null; pm unsuspend $pkg 2>/dev/null")
                            FreezerManager.setSpecialFreeze(this, pkg, false)
                            FreezerManager.unfreezeApp(pkg)
                            runOnUiThread {
                                refreshList()
                                notifyWidgets()
                                Toast.makeText(this, "Unsuspended & Restored $appName", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    2 -> {
                        thread {
                            ShellUtils.fastCmd("cmd package unsuspend --user 0 $pkg 2>/dev/null; pm unsuspend $pkg 2>/dev/null")
                            FreezerManager.setSpecialFreeze(this, pkg, false)
                            FreezerManager.freezeApp(this, pkg, force = true)
                            runOnUiThread {
                                refreshList()
                                notifyWidgets()
                                Toast.makeText(this, "Moved $appName to Normal Freezer", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    3 -> {
                        val added = FreezerManager.toggleCustomWidgetApp(this, pkg)
                        refreshList()
                        notifyWidgets()
                        val msg = if (added) "Added $appName to Custom Widget" else "Removed $appName from Custom Widget"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        thread {
                            ShellUtils.fastCmd("cmd package unsuspend --user 0 $pkg 2>/dev/null; pm unsuspend $pkg 2>/dev/null")
                            FreezerManager.removeAppFromFreezer(this, pkg)
                            runOnUiThread {
                                refreshList()
                                notifyWidgets()
                                Toast.makeText(this, "Removed and restored $appName", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    5 -> enterSpecialEditMode(pkg)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleNormalSelection(pkg: String) {
        if (selectedNormalToRemove.contains(pkg)) {
            selectedNormalToRemove.remove(pkg)
        } else {
            selectedNormalToRemove.add(pkg)
        }
        val index = normalDisplayItems.indexOfFirst { it.pkg == pkg }
        if (index != -1) {
            normalAdapter.notifyItemChanged(index + 1)
        }
        normalAdapter.notifyItemChanged(0)
    }

    private fun enterNormalEditMode(initialPkg: String) {
        isNormalEditMode = true
        selectedNormalToRemove.clear()
        selectedNormalToRemove.add(initialPkg)
        normalAdapter.notifyDataSetChanged()
    }

    private fun exitNormalEditMode() {
        isNormalEditMode = false
        selectedNormalToRemove.clear()
        normalAdapter.notifyDataSetChanged()
    }

    private fun removeMultipleNormalApps() {
        if (selectedNormalToRemove.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Unfreeze Selected?")
            .setMessage("Stop hibernation for ${selectedNormalToRemove.size} apps?")
            .setPositiveButton("Yes") { _, _ ->
                val count = selectedNormalToRemove.size
                val toRemove = selectedNormalToRemove.toSet()
                thread {
                    for (pkg in toRemove) {
                        FreezerManager.removeAppFromFreezer(this, pkg)
                    }
                    runOnUiThread {
                        notifyWidgets()
                        exitNormalEditMode()
                        refreshList()
                        Toast.makeText(this, "Removed & Unfroze $count apps", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun toggleSpecialSelection(pkg: String) {
        if (selectedSpecialToRemove.contains(pkg)) {
            selectedSpecialToRemove.remove(pkg)
        } else {
            selectedSpecialToRemove.add(pkg)
        }
        val index = specialDisplayItems.indexOfFirst { it.pkg == pkg }
        if (index != -1) {
            specialAdapter.notifyItemChanged(index + 1)
        }
        specialAdapter.notifyItemChanged(0)
    }

    private fun enterSpecialEditMode(initialPkg: String) {
        isSpecialEditMode = true
        selectedSpecialToRemove.clear()
        selectedSpecialToRemove.add(initialPkg)
        specialAdapter.notifyDataSetChanged()
    }

    private fun exitSpecialEditMode() {
        isSpecialEditMode = false
        selectedSpecialToRemove.clear()
        specialAdapter.notifyDataSetChanged()
    }

    private fun removeMultipleSpecialApps() {
        if (selectedSpecialToRemove.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Unsuspend Selected?")
            .setMessage("Restore and unsuspend ${selectedSpecialToRemove.size} apps?")
            .setPositiveButton("Yes") { _, _ ->
                val count = selectedSpecialToRemove.size
                val toRemove = selectedSpecialToRemove.toSet()
                thread {
                    for (pkg in toRemove) {
                        ShellUtils.fastCmd("cmd package unsuspend --user 0 $pkg 2>/dev/null; pm unsuspend $pkg 2>/dev/null")
                        FreezerManager.removeAppFromFreezer(this, pkg)
                    }
                    runOnUiThread {
                        notifyWidgets()
                        exitSpecialEditMode()
                        refreshList()
                        Toast.makeText(this, "Restored & Unsuspended $count apps", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun showSearchableAppPicker(isSpecialTarget: Boolean = false) {
        val currentFrozen = FreezerManager.getFrozenApps(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearchApp)
        val lvApps = dialogView.findViewById<ListView>(R.id.lvApps)
        val cbSelectAll = dialogView.findViewById<CheckBox>(R.id.cbSelectAll)
        val spinnerFilter = dialogView.findViewById<Spinner>(R.id.spinnerFilter)

        spinnerFilter.visibility = View.VISIBLE
        val filterOptions = arrayOf("👤 User Apps", "⚙️ System Apps", "All Apps")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, filterOptions)
        spinnerFilter.adapter = spinnerAdapter

        // ⚡ Cache-First: Instant population from memory
        var allApps: List<AppItem> = (cachedInstalledApps ?: emptyList())
            .filter { !currentFrozen.contains(it.info.packageName) }
        for (app in allApps) {
            app.isChecked = false
        }
        var filteredApps: List<AppItem> = allApps
        val pickerAdapter = AppPickerAdapter(filteredApps)
        lvApps.adapter = pickerAdapter

        fun updateList() {
            val query = etSearch.text.toString().trim().lowercase()
            val filterMode = spinnerFilter.selectedItemPosition
            filteredApps = allApps.filter { item ->
                val isSystem = (item.info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val matchesCategory = when (filterMode) {
                    0 -> !isSystem
                    1 -> isSystem
                    else -> true
                }
                val matchesQuery = query.isEmpty() ||
                        item.label.lowercase().contains(query) ||
                        item.info.packageName.lowercase().contains(query)
                matchesCategory && matchesQuery
            }
            pickerAdapter.items = filteredApps
            pickerAdapter.notifyDataSetChanged()
        }

        spinnerFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateList()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        updateList()

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

        val title = if (isSpecialTarget) "Add Apps to Special Freeze (Suspend)" else "Add Apps to Normal Hibernate"

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val newlySelected = allApps.filter { it.isChecked }.map { it.info.packageName }.toSet()
                if (newlySelected.isNotEmpty()) {
                    val updatedSet = currentFrozen.toMutableSet().apply { addAll(newlySelected) }
                    FreezerManager.saveFrozenApps(this, updatedSet)

                    if (isSpecialTarget) {
                        for (pkg in newlySelected) {
                            FreezerManager.setSpecialFreeze(this, pkg, true)
                        }
                    }

                    if (!FreezerManager.isAutoFreezeEnabled(this)) {
                        FreezerManager.setAutoFreezeEnabled(this, true)
                    }

                    thread {
                        for (pkg in newlySelected) {
                            FreezerManager.freezeApp(this@AppFreezerListActivity, pkg, force = true)
                        }
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            refreshList()
                            notifyWidgets()
                            val targetLabel = if (isSpecialTarget) "Special Freeze (Suspended)" else "Normal Hibernation list"
                            Toast.makeText(this@AppFreezerListActivity, "Added ${newlySelected.size} apps to $targetLabel", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

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
        val allFrozen = FreezerManager.getFrozenApps(this) + FreezerManager.getSpecialFreezeApps(this)
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
        val pickerAdapter = AppPickerAdapter(filteredItems)
        lvApps.adapter = pickerAdapter

        fun updateList() {
            val query = etSearch.text.toString().trim().lowercase()
            filteredItems = if (query.isEmpty()) {
                allItems
            } else {
                allItems.filter { it.label.lowercase().contains(query) || it.info.packageName.lowercase().contains(query) }
            }
            pickerAdapter.items = filteredItems
            pickerAdapter.notifyDataSetChanged()
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

        dialog.show()

        thread {
            val freshItems = allFrozen.mapNotNull { pkg ->
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
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

    private fun getInstalledAppsList(): List<AppItem> {
        val cached = cachedInstalledApps
        if (!cached.isNullOrEmpty()) return cached

        val list = pm.getInstalledApplications(0)
            .filter {
                it.packageName != packageName &&
                it.enabled &&
                !CRITICAL_SYSTEM_PACKAGES.contains(it.packageName)
            }
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

    // ==========================================
    // TAB 1: Normal Freezer Adapter
    // ==========================================
    private inner class NormalFreezerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TYPE_HEADER = 0
        private val TYPE_ITEM = 1

        override fun getItemCount(): Int = normalDisplayItems.size + 1
        override fun getItemViewType(position: Int): Int = if (position == 0) TYPE_HEADER else TYPE_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                val view = inflater.inflate(R.layout.layout_freezer_header, parent, false)
                NormalHeaderViewHolder(view)
            } else {
                val view = inflater.inflate(R.layout.item_app_picker, parent, false)
                AppViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is NormalHeaderViewHolder) {
                holder.bind()
            } else if (holder is AppViewHolder) {
                val item = normalDisplayItems[position - 1]
                holder.bind(item, isNormalEditMode, selectedNormalToRemove.contains(item.pkg)) {
                    if (isNormalEditMode) {
                        toggleNormalSelection(item.pkg)
                    } else {
                        showNormalAppOptionsDialog(item.pkg, item.name)
                    }
                }
                holder.itemView.setOnLongClickListener {
                    if (!isNormalEditMode) {
                        enterNormalEditMode(item.pkg)
                    }
                    true
                }
            }
        }
    }

    private inner class NormalHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val switchAuto: SwitchMaterial = itemView.findViewById(R.id.switchAutoFreeze)
        val layoutDelayContainer: View = itemView.findViewById(R.id.layoutDelayContainer)
        val chipGroupDelay: com.google.android.material.chip.ChipGroup = itemView.findViewById(R.id.chipGroupFreezeDelay)
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
            switchAuto.setOnCheckedChangeListener(null)
            switchAuto.isChecked = autoFreezeEnabled
            layoutDelayContainer.visibility = if (autoFreezeEnabled) View.VISIBLE else View.GONE

            val currentDelay = FreezerManager.getAutoFreezeDelaySeconds(this@AppFreezerListActivity)
            val checkedChipId = when (currentDelay) {
                60 -> R.id.chipDelay1m
                300 -> R.id.chipDelay5m
                else -> R.id.chipDelayInstant
            }

            val chipInstant = itemView.findViewById<com.google.android.material.chip.Chip>(R.id.chipDelayInstant)
            val chip1m = itemView.findViewById<com.google.android.material.chip.Chip>(R.id.chipDelay1m)
            val chip5m = itemView.findViewById<com.google.android.material.chip.Chip>(R.id.chipDelay5m)

            fun updateChipVisuals(activeId: Int) {
                val allChips = listOf(chipInstant, chip1m, chip5m)
                for (chip in allChips) {
                    val isActive = chip.id == activeId
                    if (isActive) {
                        chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E676"))
                        chip.setTextColor(Color.parseColor("#000000"))
                        chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E676"))
                        chip.chipStrokeWidth = 2f
                    } else {
                        chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#202024"))
                        chip.setTextColor(Color.parseColor("#8E8E93"))
                        chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#333338"))
                        chip.chipStrokeWidth = 1f
                    }
                }
            }

            chipGroupDelay.setOnCheckedChangeListener(null)
            chipGroupDelay.check(checkedChipId)
            updateChipVisuals(checkedChipId)

            chipGroupDelay.setOnCheckedChangeListener { _, checkedId ->
                val newDelay = when (checkedId) {
                    R.id.chipDelay1m -> 60
                    R.id.chipDelay5m -> 300
                    else -> 0
                }
                updateChipVisuals(checkedId)
                FreezerManager.setAutoFreezeDelaySeconds(this@AppFreezerListActivity, newDelay)
                val label = if (newDelay == 0) "Instant (0s)" else if (newDelay == 60) "1 Minute" else "5 Minutes"
                Toast.makeText(this@AppFreezerListActivity, "Auto-Freeze Delay: $label", Toast.LENGTH_SHORT).show()
            }

            switchAuto.setOnCheckedChangeListener { _, isChecked ->
                autoFreezeEnabled = isChecked
                FreezerManager.setAutoFreezeEnabled(this@AppFreezerListActivity, isChecked)
                layoutDelayContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
                Toast.makeText(this@AppFreezerListActivity, if (isChecked) "Auto-Freeze on screen off enabled" else "Auto-Freeze disabled", Toast.LENGTH_SHORT).show()
            }

            btnFreezeAll.setOnClickListener { freezeAllNormal() }
            btnAddApps.setOnClickListener { showSearchableAppPicker(isSpecialTarget = false) }
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
            layoutEditActions.visibility = if (isNormalEditMode) View.VISIBLE else View.GONE
            val count = selectedNormalToRemove.size
            tvEditModeTitle.text = if (count == 0) "Select apps to unfreeze" else "$count apps selected"
            btnRemoveSelected.text = if (count == 0) "Unfreeze Selected" else "Unfreeze ($count)"
            btnRemoveSelected.setOnClickListener { removeMultipleNormalApps() }
            btnCancelEdit.setOnClickListener { exitNormalEditMode() }

            // Section Title & Empty State
            tvHibernatingTitle.text = if (normalDisplayItems.isEmpty()) "Normal Hibernating Apps (am freeze)" else "Normal Hibernating Apps (${normalDisplayItems.size})"
            tvEmptyState.visibility = if (normalDisplayItems.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // ==========================================
    // TAB 2: Special Freezer Adapter
    // ==========================================
    private inner class SpecialFreezerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TYPE_HEADER = 0
        private val TYPE_ITEM = 1

        override fun getItemCount(): Int = specialDisplayItems.size + 1
        override fun getItemViewType(position: Int): Int = if (position == 0) TYPE_HEADER else TYPE_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                val view = inflater.inflate(R.layout.layout_special_freezer_header, parent, false)
                SpecialHeaderViewHolder(view)
            } else {
                val view = inflater.inflate(R.layout.item_app_picker, parent, false)
                AppViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is SpecialHeaderViewHolder) {
                holder.bind()
            } else if (holder is AppViewHolder) {
                val item = specialDisplayItems[position - 1]
                holder.bind(item, isSpecialEditMode, selectedSpecialToRemove.contains(item.pkg)) {
                    if (isSpecialEditMode) {
                        toggleSpecialSelection(item.pkg)
                    } else {
                        showSpecialAppOptionsDialog(item.pkg, item.name)
                    }
                }
                holder.itemView.setOnLongClickListener {
                    if (!isSpecialEditMode) {
                        enterSpecialEditMode(item.pkg)
                    }
                    true
                }
            }
        }
    }

    private inner class SpecialHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val btnFreezeAllSpecial: MaterialButton = itemView.findViewById(R.id.btnFreezeAllSpecial)
        val btnAddSpecialApps: MaterialButton = itemView.findViewById(R.id.btnAddSpecialApps)
        val layoutEditActionsSpecial: LinearLayout = itemView.findViewById(R.id.layoutEditActionsSpecial)
        val tvEditModeTitleSpecial: TextView = itemView.findViewById(R.id.tvEditModeTitleSpecial)
        val btnRemoveSelectedSpecial: MaterialButton = itemView.findViewById(R.id.btnRemoveSelectedSpecial)
        val btnCancelEditSpecial: MaterialButton = itemView.findViewById(R.id.btnCancelEditSpecial)
        val tvSpecialTitle: TextView = itemView.findViewById(R.id.tvSpecialTitle)
        val tvEmptyStateSpecial: TextView = itemView.findViewById(R.id.tvEmptyStateSpecial)

        fun bind() {
            btnFreezeAllSpecial.setOnClickListener { freezeAllSpecial() }
            btnAddSpecialApps.setOnClickListener { showSearchableAppPicker(isSpecialTarget = true) }

            layoutEditActionsSpecial.visibility = if (isSpecialEditMode) View.VISIBLE else View.GONE
            val count = selectedSpecialToRemove.size
            tvEditModeTitleSpecial.text = if (count == 0) "Select apps to unsuspend" else "$count apps selected"
            btnRemoveSelectedSpecial.text = if (count == 0) "Unsuspend Selected" else "Unsuspend ($count)"
            btnRemoveSelectedSpecial.setOnClickListener { removeMultipleSpecialApps() }
            btnCancelEditSpecial.setOnClickListener { exitSpecialEditMode() }

            tvSpecialTitle.text = if (specialDisplayItems.isEmpty()) "Suspended Apps (Hard Kill)" else "Suspended Apps (${specialDisplayItems.size})"
            tvEmptyStateSpecial.visibility = if (specialDisplayItems.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // ==========================================
    // TAB 3: Immunity Adapter
    // ==========================================
    private inner class ImmunityAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TYPE_HEADER = 0
        private val TYPE_ITEM = 1

        override fun getItemCount(): Int = immunityDisplayItems.size + 1
        override fun getItemViewType(position: Int): Int = if (position == 0) TYPE_HEADER else TYPE_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                val view = inflater.inflate(R.layout.layout_immunity_header, parent, false)
                ImmunityHeaderViewHolder(view)
            } else {
                val view = inflater.inflate(R.layout.item_immunity_app, parent, false)
                ImmunityItemViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is ImmunityHeaderViewHolder) {
                holder.bind()
            } else if (holder is ImmunityItemViewHolder) {
                val item = immunityDisplayItems[position - 1]
                holder.bind(item)
            }
        }
    }

    private inner class ImmunityHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val btnEnableAll: MaterialButton = itemView.findViewById(R.id.btnEnableAllImmunity)
        val btnDisableAll: MaterialButton = itemView.findViewById(R.id.btnDisableAllImmunity)
        val tvImmunityTitle: TextView = itemView.findViewById(R.id.tvImmunityTitle)
        val tvEmptyStateImmunity: TextView = itemView.findViewById(R.id.tvEmptyStateImmunity)

        fun bind() {
            btnEnableAll.setOnClickListener {
                val normalPkgs = normalDisplayItems.map { it.pkg }.toSet()
                if (normalPkgs.isEmpty()) {
                    Toast.makeText(this@AppFreezerListActivity, "No apps in Normal Freezer!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val existing = FreezerManager.getFgsImmuneApps(this@AppFreezerListActivity).toMutableSet()
                existing.addAll(normalPkgs)
                FreezerManager.saveFgsImmuneApps(this@AppFreezerListActivity, existing)
                refreshList()
                Toast.makeText(this@AppFreezerListActivity, "Granted FGS immunity to all Normal Freezer apps", Toast.LENGTH_SHORT).show()
            }

            btnDisableAll.setOnClickListener {
                val normalPkgs = normalDisplayItems.map { it.pkg }.toSet()
                if (normalPkgs.isEmpty()) return@setOnClickListener
                val existing = FreezerManager.getFgsImmuneApps(this@AppFreezerListActivity).toMutableSet()
                existing.removeAll(normalPkgs)
                FreezerManager.saveFgsImmuneApps(this@AppFreezerListActivity, existing)
                refreshList()
                Toast.makeText(this@AppFreezerListActivity, "Revoked FGS immunity for all Normal Freezer apps", Toast.LENGTH_SHORT).show()
            }

            tvImmunityTitle.text = if (immunityDisplayItems.isEmpty()) "Normal Freezer Apps (Per-App Permission)" else "Normal Freezer Apps (${immunityDisplayItems.size})"
            tvEmptyStateImmunity.visibility = if (immunityDisplayItems.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private inner class ImmunityItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        val tvPkg: TextView = itemView.findViewById(R.id.tvPackageName)
        val tvStatus: TextView = itemView.findViewById(R.id.tvAppStatus)
        val switchImmunity: SwitchMaterial = itemView.findViewById(R.id.switchFgsImmunity)

        fun bind(item: ImmunityDisplayItem) {
            tvName.text = if (item.name.isNotBlank() && item.name != "Unknown App") item.name else item.pkg
            tvPkg.text = item.pkg

            if (item.icon != null) {
                ivIcon.setImageDrawable(item.icon)
            } else {
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            if (item.hasActiveTask) {
                tvStatus.text = "⚡ Active FGS / Downloading"
                tvStatus.setTextColor(Color.parseColor("#00E676"))
            } else if (item.isImmune) {
                tvStatus.text = "🛡️ Immune while active FGS / Download"
                tvStatus.setTextColor(Color.parseColor("#00E5FF"))
            } else {
                tvStatus.text = "❄️ Normal (Immediate Freeze)"
                tvStatus.setTextColor(Color.parseColor("#8E8E93"))
            }

            switchImmunity.setOnCheckedChangeListener(null)
            switchImmunity.isChecked = item.isImmune
            switchImmunity.setOnCheckedChangeListener { _, isChecked ->
                item.isImmune = isChecked
                FreezerManager.setFgsImmunity(this@AppFreezerListActivity, item.pkg, isChecked)
                if (item.hasActiveTask) {
                    tvStatus.text = "⚡ Active FGS / Downloading"
                    tvStatus.setTextColor(Color.parseColor("#00E676"))
                } else if (isChecked) {
                    tvStatus.text = "🛡️ Immune while active FGS / Download"
                    tvStatus.setTextColor(Color.parseColor("#00E5FF"))
                } else {
                    tvStatus.text = "❄️ Normal (Immediate Freeze)"
                    tvStatus.setTextColor(Color.parseColor("#8E8E93"))
                }
                val msg = if (isChecked) "🛡️ FGS Immunity granted to ${item.name}" else "❄️ Immediate freeze enabled for ${item.name}"
                Toast.makeText(this@AppFreezerListActivity, msg, Toast.LENGTH_SHORT).show()
            }

            itemView.setOnClickListener {
                switchImmunity.isChecked = !switchImmunity.isChecked
            }
        }
    }

    // ==========================================
    // Common App View Holder for Tab 1 & Tab 2
    // ==========================================
    private inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        val tvPkg: TextView = itemView.findViewById(R.id.tvPackageName)
        val tvStatus: TextView = itemView.findViewById(R.id.tvAppStatus)
        val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)

        fun bind(
            item: FrozenDisplayItem,
            isEditMode: Boolean,
            isSelected: Boolean,
            onClick: () -> Unit
        ) {
            cbSelect.visibility = if (isEditMode) View.VISIBLE else View.GONE
            cbSelect.isChecked = isSelected

            if (item.icon != null) {
                ivIcon.setImageDrawable(item.icon)
            } else {
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            tvName.text = if (item.name.isNotBlank() && item.name != "Unknown App") item.name else item.pkg
            tvPkg.text = item.pkg
            tvPkg.visibility = View.VISIBLE

            val systemTag = if (item.isSystem) " • ⚙️ System" else ""
            val widgetTag = if (item.isCustomWidget) " • 📱 Widget" else ""

            if (item.isSpecial) {
                tvStatus.text = "Special Freeze (Suspended)$systemTag$widgetTag"
                tvStatus.setTextColor(Color.parseColor("#FF5252"))
            } else if (item.isActive) {
                tvStatus.text = "Active in Memory$systemTag$widgetTag"
                tvStatus.setTextColor(Color.parseColor("#00E676"))
            } else {
                tvStatus.text = "Hibernated (am freeze)$systemTag$widgetTag"
                tvStatus.setTextColor(Color.parseColor("#00E5FF"))
            }

            itemView.setOnClickListener { onClick() }
        }
    }

    private inner class AppPickerAdapter(var items: List<AppItem>) : BaseAdapter() {
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

            val isSystem = (item.info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem) {
                tvStatus.text = "SYSTEM"
                tvStatus.setTextColor(Color.parseColor("#888888"))
                tvStatus.visibility = View.VISIBLE
            } else {
                tvStatus.visibility = View.GONE
            }

            val pkgName = item.info.packageName
            ivIcon.tag = pkgName
            val cachedIcon = appInfoCache[pkgName]?.second
            if (cachedIcon != null) {
                ivIcon.setImageDrawable(cachedIcon)
            } else {
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                thread {
                    val icon = try { pm.getApplicationIcon(item.info) } catch (e: Exception) { null }
                    if (icon != null) {
                        appInfoCache[pkgName] = Pair(item.label, icon)
                        runOnUiThread {
                            if (ivIcon.tag == pkgName) {
                                ivIcon.setImageDrawable(icon)
                            }
                        }
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
