package com.example.phonecontrol

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
        
        findViewById<Button>(R.id.btnRemoveSelected).setOnClickListener {
            removeMultipleApps()
        }
        
        findViewById<Button>(R.id.btnCancelEdit).setOnClickListener {
            exitEditMode()
        }

        thread {
            cachedAppsList = pm.getInstalledApplications(0)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        }
    }

    override fun onResume() {
        super.onResume()
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
            val statusMap = mutableMapOf<String, Boolean>()
            for (pkg in frozenApps) {
                statusMap[pkg] = FreezerManager.isAppTrulyActive(pkg)
            }

            runOnUiThread {
                for (pkg in frozenApps) {
                    addAppView(pkg, statusMap[pkg] ?: false)
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

    private fun showAppOptionsDialog(pkg: String, appName: String) {
        val options = arrayOf("Special Freeze (Hard Kill + Suspend)", "Remove from List", "Bulk Edit Mode")

        AlertDialog.Builder(this)
            .setTitle(appName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Special Freeze Toggle
                        val newVal = !FreezerManager.isSpecialFreeze(this, pkg)
                        FreezerManager.setSpecialFreeze(this, pkg, newVal)
                        // Re-apply freeze to apply new settings immediately
                        if (!FreezerManager.isAppTrulyActive(pkg)) {
                            FreezerManager.freezeApp(this, pkg)
                        }
                        refreshList()
                        Toast.makeText(this, "Special Freeze ${if(newVal) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
                    }
                    1 -> { // Remove
                        val current = FreezerManager.getFrozenApps(this).toMutableSet()
                        current.remove(pkg)
                        FreezerManager.unfreezeApp(pkg)
                        FreezerManager.saveFrozenApps(this, current)
                        refreshList()
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
                val current = FreezerManager.getFrozenApps(this).toMutableSet()
                for (pkg in selectedToRemove) {
                    current.remove(pkg)
                    FreezerManager.unfreezeApp(pkg)
                }
                FreezerManager.saveFrozenApps(this, current)
                FreezerWidgetProvider.updateAllWidgets(this)
                exitEditMode()
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

        val allApps = cachedAppsList ?: pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

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
            cbSelectAll.isChecked = filteredApps.all { selectedPackages.contains(it.packageName) }
            adapter.notifyDataSetChanged()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Add to Hibernation") { _, _ ->
                val current = FreezerManager.getFrozenApps(this).toMutableSet()
                selectedPackages.forEach { current.add(it); FreezerManager.freezeApp(this, it) }
                FreezerManager.saveFrozenApps(this, current)
                FreezerWidgetProvider.updateAllWidgets(this)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.8).toInt())
    }
}
