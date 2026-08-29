package com.example.phonecontrol

import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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

class FreezerActivity : AppCompatActivity() {

    private lateinit var layoutFrozenAppsList: LinearLayout
    private lateinit var layoutEditActions: LinearLayout
    private lateinit var pm: PackageManager
    
    private var isEditMode = false
    private val selectedToRemove = mutableSetOf<String>()
    private var cachedAppsList: List<ApplicationInfo>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_freezer)

        pm = packageManager
        layoutFrozenAppsList = findViewById(R.id.layoutFrozenAppsList)
        layoutEditActions = findViewById(R.id.layoutEditActions)

        findViewById<MaterialToolbar>(R.id.toolbarFreezer).setNavigationOnClickListener {
            if (isEditMode) exitEditMode() else finish()
        }

        findViewById<Button>(R.id.btnAddNewFreeze).setOnClickListener {
            showSearchableAppPicker()
        }

        findViewById<Button>(R.id.btnFreezeAllNow).setOnClickListener {
            freezeAll()
        }
        
        findViewById<Button>(R.id.btnRemoveSelected).setOnClickListener {
            removeMultipleApps()
        }
        
        findViewById<Button>(R.id.btnCancelEdit).setOnClickListener {
            exitEditMode()
        }

        val swAutoFreeze = findViewById<SwitchMaterial>(R.id.switchAutoFreeze)
        val prefs = getSharedPreferences("freezer_prefs", MODE_PRIVATE)
        swAutoFreeze.isChecked = prefs.getBoolean("auto_freeze_enabled", false)
        swAutoFreeze.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_freeze_enabled", isChecked).apply()
            Toast.makeText(this, if (isChecked) "Auto-Hibernation Active" else "Auto-Hibernation Disabled", Toast.LENGTH_SHORT).show()
        }

        // Sub-feature Card Navigation
        findViewById<View>(R.id.cardBloatware).setOnClickListener {
            startActivity(Intent(this, BloatwareActivity::class.java))
        }
        findViewById<View>(R.id.cardVault).setOnClickListener {
            startActivity(Intent(this, VaultActivity::class.java))
        }
        findViewById<View>(R.id.cardTerminal).setOnClickListener {
            startActivity(Intent(this, AdbShellActivity::class.java))
        }

        updateSubCardVisibility()

        thread {
            cachedAppsList = getInstalledAppsList()
        }
    }

    private fun updateSubCardVisibility() {
        val masterPrefs = getSharedPreferences("prefs", MODE_PRIVATE)
        findViewById<View>(R.id.cardBloatware).visibility = 
            if (masterPrefs.getBoolean("bloatware_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardVault).visibility = 
            if (masterPrefs.getBoolean("vault_enabled", false)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardTerminal).visibility = 
            if (masterPrefs.getBoolean("adb_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.layoutFreezerSection).visibility = 
            if (masterPrefs.getBoolean("freezer_enabled", true)) View.VISIBLE else View.GONE
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

    override fun onResume() {
        super.onResume()
        updateSubCardVisibility()
        refreshList()
    }

    private fun refreshList() {
        layoutFrozenAppsList.removeAllViews()
        val frozenApps = FreezerManager.getFrozenApps(this)
        
        if (frozenApps.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No apps in hibernation list."
                setTextColor(android.graphics.Color.GRAY)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 50, 0, 0)
            }
            layoutFrozenAppsList.addView(tv)
            return
        }

        thread {
            val activeSet = FreezerManager.getActivePackages(frozenApps)

            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                for (pkg in frozenApps) {
                    addAppView(pkg, activeSet.contains(pkg))
                }
            }
        }
    }

    private fun addAppView(pkg: String, isActive: Boolean) {
        val view = layoutInflater.inflate(R.layout.item_app_picker, layoutFrozenAppsList, false)
        val ivIcon = view.findViewById<ImageView>(R.id.ivAppIcon)
        val tvName = view.findViewById<TextView>(R.id.tvAppName)
        val tvPkg = view.findViewById<TextView>(R.id.tvPackageName)
        val tvStatus = view.findViewById<TextView>(R.id.tvAppStatus)
        val cbSelect = view.findViewById<CheckBox>(R.id.cbSelect)
        
        cbSelect.visibility = if (isEditMode) View.VISIBLE else View.GONE
        cbSelect.isChecked = selectedToRemove.contains(pkg)

        try {
            val appInfo = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            ivIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
            tvName.text = pm.getApplicationLabel(appInfo)
        } catch (e: Exception) {
            tvName.text = "Unknown App"
        }
        
        tvPkg.text = pkg
        
        if (isActive) {
            tvStatus.text = if (FreezerManager.isSpecialFreeze(this, pkg)) "ACTIVE (SPECIAL)" else "ACTIVE"
            tvStatus.setTextColor(android.graphics.Color.GREEN)
        } else {
            tvStatus.text = if (FreezerManager.isSpecialFreeze(this, pkg)) "HIBERNATING (SPECIAL)" else "HIBERNATING"
            tvStatus.setTextColor(android.graphics.Color.CYAN)
        }

        view.setOnClickListener {
            if (isEditMode) {
                toggleSelection(pkg, cbSelect)
            } else {
                FreezerManager.launchApp(this, pkg)
                Toast.makeText(this, "Resuming...", Toast.LENGTH_SHORT).show()
            }
        }
        
        view.setOnLongClickListener {
            if (!isEditMode) {
                showAppOptionsDialog(pkg, tvName.text.toString())
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
                Toast.makeText(this, "All apps hibernated!", Toast.LENGTH_SHORT).show()
                refreshList()
            }
        }
    }

    private fun showAppOptionsDialog(pkg: String, appName: String) {
        val options = arrayOf("Special Freeze (Hard Kill + Suspend)", "Remove from List", "Bulk Edit Mode")

        AlertDialog.Builder(this)
            .setTitle(appName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val newVal = !FreezerManager.isSpecialFreeze(this, pkg)
                        FreezerManager.setSpecialFreeze(this, pkg, newVal)
                        thread {
                            FreezerManager.freezeApp(this, pkg)
                            runOnUiThread {
                                refreshList()
                                Toast.makeText(this, "Special Freeze ${if(newVal) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    1 -> {
                        val current = FreezerManager.getFrozenApps(this).toMutableSet()
                        current.remove(pkg)
                        thread {
                            FreezerManager.unfreezeApp(pkg)
                            FreezerManager.saveFrozenApps(this, current)
                            runOnUiThread { refreshList() }
                        }
                    }
                    2 -> enterEditMode(pkg)
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
                    FreezerManager.unfreezeMultipleApps(toRemove)
                    runOnUiThread {
                        FreezerWidgetProvider.updateAllWidgets(this)
                        exitEditMode()
                        Toast.makeText(this, "Unfroze $count apps", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun showSearchableAppPicker() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearchApp)
        val spinnerFilter = dialogView.findViewById<Spinner>(R.id.spinnerFilter)
        val cbSelectAll = dialogView.findViewById<CheckBox>(R.id.cbSelectAll)
        val listView = dialogView.findViewById<ListView>(R.id.lvApps)

        val allApps = cachedAppsList ?: getInstalledAppsList()

        val filterOptions = arrayOf("User Apps", "System Apps")
        spinnerFilter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, filterOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val selectedPackages = mutableSetOf<String>()
        val filteredApps = mutableListOf<ApplicationInfo>()
        
        val adapter = object : ArrayAdapter<ApplicationInfo>(this, R.layout.item_app_picker, filteredApps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_app_picker, parent, false)
                val app = getItem(position)!!
                view.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(pm.getApplicationIcon(app))
                view.findViewById<TextView>(R.id.tvAppName).text = pm.getApplicationLabel(app)
                view.findViewById<TextView>(R.id.tvPackageName).text = app.packageName
                view.findViewById<CheckBox>(R.id.cbSelect).apply {
                    visibility = View.VISIBLE
                    isChecked = selectedPackages.contains(app.packageName)
                }
                view.findViewById<TextView>(R.id.tvAppStatus).visibility = View.GONE
                return view
            }
        }
        listView.adapter = adapter

        fun updateFilteredList() {
            val query = etSearch.text.toString().lowercase()
            val isSystemSelected = spinnerFilter.selectedItemPosition == 1
            filteredApps.clear()
            filteredApps.addAll(allApps.filter { app ->
                val isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val matchesFilter = if (isSystemSelected) isSystemApp else !isSystemApp
                val matchesQuery = pm.getApplicationLabel(app).toString().lowercase().contains(query) || 
                                  app.packageName.lowercase().contains(query)
                matchesFilter && matchesQuery
            })
            cbSelectAll.isChecked = filteredApps.isNotEmpty() && filteredApps.all { selectedPackages.contains(it.packageName) }
            adapter.notifyDataSetChanged()
        }

        spinnerFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) { updateFilteredList() }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateFilteredList() }
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        cbSelectAll.setOnClickListener {
            if (cbSelectAll.isChecked) filteredApps.forEach { selectedPackages.add(it.packageName) }
            else filteredApps.forEach { selectedPackages.remove(it.packageName) }
            adapter.notifyDataSetChanged()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val pkg = filteredApps[position].packageName
            if (selectedPackages.contains(pkg)) selectedPackages.remove(pkg) else selectedPackages.add(pkg)
            cbSelectAll.isChecked = filteredApps.isNotEmpty() && filteredApps.all { selectedPackages.contains(it.packageName) }
            adapter.notifyDataSetChanged()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Add to Hibernation") { _, _ ->
                val count = selectedPackages.size
                val current = FreezerManager.getFrozenApps(this).toMutableSet()
                current.addAll(selectedPackages)
                FreezerManager.saveFrozenApps(this, current)
                
                val progress = ProgressDialog(this).apply {
                    setMessage("Freezing $count apps...")
                    setCancelable(false)
                    show()
                }

                thread {
                    FreezerManager.freezeMultipleApps(this, selectedPackages)
                    runOnUiThread {
                        progress.dismiss()
                        FreezerWidgetProvider.updateAllWidgets(this)
                        Toast.makeText(this, "$count apps added to hibernation!", Toast.LENGTH_SHORT).show()
                        refreshList()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.8).toInt())
    }
}
