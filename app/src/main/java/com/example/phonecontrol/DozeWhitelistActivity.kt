package com.example.phonecontrol

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
import com.google.android.material.chip.ChipGroup
import kotlin.concurrent.thread

class DozeWhitelistActivity : AppCompatActivity() {

    private lateinit var layoutDozeWhitelist: LinearLayout
    private var cachedAppsList: List<ProfileApp>? = null
    private var selectedCategoryFilter: AppCategory? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doze_whitelist)

        layoutDozeWhitelist = findViewById(R.id.layoutDozeWhitelist)

        findViewById<MaterialToolbar>(R.id.toolbarDoze).setNavigationOnClickListener { finish() }
        findViewById<Button>(R.id.btnAddDozeWhitelist).setOnClickListener { showAppPicker() }

        findViewById<ChipGroup>(R.id.chipGroupWhitelistFilter)?.setOnCheckedChangeListener { _, checkedId ->
            selectedCategoryFilter = when (checkedId) {
                R.id.chipWhitelistMain -> AppCategory.MAIN
                R.id.chipWhitelistDual -> AppCategory.DUAL
                R.id.chipWhitelistPrivate -> AppCategory.PRIVATE
                else -> null
            }
            refreshList()
        }

        thread {
            val apps = ProfileAppUtils.getInstalledAppsAcrossProfiles(this)
            cachedAppsList = apps
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    refreshList()
                }
            }
        }

        refreshList()
    }

    private fun refreshList() {
        layoutDozeWhitelist.removeAllViews()
        val whitelist = MultitaskingManager.getUserWhitelist(this)

        if (whitelist.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No custom apps whitelisted.\nTap button above to protect Key Mapper, WhatsApp, or any essential app."
                setTextColor(Color.GRAY)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 50, 0, 0)
            }
            layoutDozeWhitelist.addView(tv)
            return
        }

        val allApps = cachedAppsList ?: emptyList()
        var count = 0

        for (entry in whitelist) {
            val (pkg, userId) = FreezerManager.parsePackageKey(entry)
            val cat = AppCategory.fromUserId(userId)
            if (selectedCategoryFilter != null && cat != selectedCategoryFilter) {
                continue
            }
            count++

            val view = layoutInflater.inflate(R.layout.item_app_picker, layoutDozeWhitelist, false)
            view.findViewById<CheckBox>(R.id.cbSelect).visibility = View.GONE

            val tvName = view.findViewById<TextView>(R.id.tvAppName)
            val tvPkg = view.findViewById<TextView>(R.id.tvPackageName)
            val tvCategoryBadge = view.findViewById<TextView>(R.id.tvCategoryBadge)
            val ivIcon = view.findViewById<ImageView>(R.id.ivAppIcon)

            val matchedApp = allApps.find { it.packageName == pkg && it.userId == userId }
            val name = matchedApp?.label ?: pkg.substringAfterLast(".")
            tvName.text = name

            if (matchedApp?.icon != null) {
                ivIcon.setImageDrawable(matchedApp.icon)
            } else {
                try {
                    ivIcon.setImageDrawable(packageManager.getApplicationIcon(pkg))
                } catch (_: Exception) {
                    ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            }

            if (userId != 0) {
                tvCategoryBadge.visibility = View.VISIBLE
                tvCategoryBadge.text = cat.title
                tvCategoryBadge.setTextColor(Color.parseColor(cat.colorHex))
                tvPkg.text = "$pkg • ${cat.title}"
            } else {
                tvCategoryBadge.visibility = View.GONE
                tvPkg.text = pkg
            }

            view.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle("Remove from Whitelist")
                    .setMessage("Remove $name ($entry) from Universal Protected Whitelist?")
                    .setPositiveButton("Remove") { _, _ ->
                        MultitaskingManager.removeAppFromWhitelist(this, entry)
                        refreshList()
                    }
                    .setNegativeButton("Cancel", null).show()
                true
            }
            layoutDozeWhitelist.addView(view)
        }

        if (count == 0 && selectedCategoryFilter != null) {
            val tv = TextView(this).apply {
                text = "No ${selectedCategoryFilter?.title} apps whitelisted."
                setTextColor(Color.GRAY)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 50, 0, 0)
            }
            layoutDozeWhitelist.addView(tv)
        }
    }

    private fun showAppPicker() {
        val cached = cachedAppsList
        if (cached == null) {
            Toast.makeText(this, "Loading installed apps, please wait...", Toast.LENGTH_SHORT).show()
            thread {
                val list = ProfileAppUtils.getInstalledAppsAcrossProfiles(this)
                cachedAppsList = list
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showAppPicker()
                    }
                }
            }
            return
        }

        val currentWhitelist = MultitaskingManager.getUserWhitelist(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearchApp)
        val scrollPickerFilter = dialogView.findViewById<View>(R.id.scrollPickerFilter)
        val chipGroupPickerFilter = dialogView.findViewById<ChipGroup>(R.id.chipGroupPickerFilter)
        dialogView.findViewById<View>(R.id.spinnerFilter)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.cbSelectAll)?.visibility = View.GONE
        val listView = dialogView.findViewById<ListView>(R.id.lvApps)

        scrollPickerFilter?.visibility = View.VISIBLE
        var pickerCategory: AppCategory? = null

        val allApps = cached
            .filter { !currentWhitelist.contains(it.uniqueKey) }
            .sortedBy { it.label.lowercase() }

        var filteredApps = allApps.toMutableList()

        val adapter = object : ArrayAdapter<ProfileApp>(this, R.layout.item_app_picker, filteredApps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_app_picker, parent, false)
                val app = getItem(position)!!
                view.findViewById<CheckBox>(R.id.cbSelect).visibility = View.GONE
                val ivIcon = view.findViewById<ImageView>(R.id.ivAppIcon)
                val tvName = view.findViewById<TextView>(R.id.tvAppName)
                val tvPkg = view.findViewById<TextView>(R.id.tvPackageName)
                val tvCategoryBadge = view.findViewById<TextView>(R.id.tvCategoryBadge)

                if (app.icon != null) {
                    ivIcon.setImageDrawable(app.icon)
                } else {
                    ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }

                tvName.text = app.label
                if (app.userId != 0) {
                    tvCategoryBadge.visibility = View.VISIBLE
                    tvCategoryBadge.text = app.category.title
                    tvCategoryBadge.setTextColor(Color.parseColor(app.category.colorHex))
                    tvPkg.text = "${app.packageName} • ${app.category.title}"
                } else {
                    tvCategoryBadge.visibility = View.GONE
                    tvPkg.text = app.packageName
                }
                return view
            }
        }
        listView.adapter = adapter

        fun updatePickerList() {
            val query = etSearch.text.toString().trim().lowercase()
            filteredApps.clear()
            filteredApps.addAll(allApps.filter { item ->
                val matchesCategory = (pickerCategory == null || item.category == pickerCategory)
                val matchesQuery = query.isEmpty() ||
                        item.label.lowercase().contains(query) ||
                        item.packageName.lowercase().contains(query)
                matchesCategory && matchesQuery
            })
            adapter.notifyDataSetChanged()
        }

        chipGroupPickerFilter?.setOnCheckedChangeListener { _, checkedId ->
            pickerCategory = when (checkedId) {
                R.id.chipPickMain -> AppCategory.MAIN
                R.id.chipPickDual -> AppCategory.DUAL
                R.id.chipPickPrivate -> AppCategory.PRIVATE
                else -> null
            }
            updatePickerList()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updatePickerList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        listView.setOnItemClickListener { _, _, pos, _ ->
            val selected = filteredApps[pos]
            MultitaskingManager.addAppToWhitelist(this, selected.uniqueKey)
            refreshList()
            Toast.makeText(this, "Protected ${selected.label} (${selected.category.title})", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.8).toInt())
    }
}
