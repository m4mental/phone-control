package com.example.phonecontrol

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.UserManager
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

typealias FirewallApp = ProfileApp

class FirewallActivity : AppCompatActivity() {


    private lateinit var layoutContainer: LinearLayout
    private lateinit var pm: PackageManager
    private var cachedAppsList: List<FirewallApp>? = null
    private var selectedFilterCategory: AppCategory? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_firewall)

        pm = packageManager
        layoutContainer = findViewById(R.id.layoutFirewallContainer)
        findViewById<MaterialToolbar>(R.id.toolbarFirewall).setNavigationOnClickListener { finish() }
        
        findViewById<Button>(R.id.btnAddFirewallApp).setOnClickListener {
            showAppPicker()
        }

        val btnMasterTile = findViewById<Button>(R.id.btnMasterQuickTile)
        fun updateMasterTileButtonState() {
            thread {
                val res = ShellUtils.runAsRoot("settings get secure sysui_qs_tiles", 2000)
                val inQs = res.output.contains("custom(${packageName}/.FirewallMasterTileService)")
                runOnUiThread {
                    if (inQs) {
                        btnMasterTile.text = "🗑️ REMOVE MASTER TILE"
                        btnMasterTile.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3D1515"))
                        btnMasterTile.setTextColor(Color.parseColor("#FF5252"))
                    } else {
                        btnMasterTile.text = "⚡ MASTER TILE"
                        btnMasterTile.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#152422"))
                        btnMasterTile.setTextColor(Color.parseColor("#00BFA5"))
                    }
                }
            }
        }
        updateMasterTileButtonState()

        btnMasterTile.setOnClickListener {
            thread {
                val res = ShellUtils.runAsRoot("settings get secure sysui_qs_tiles", 2000)
                val inQs = res.output.contains("custom(${packageName}/.FirewallMasterTileService)")
                if (inQs) {
                    FirewallManager.removeTileFromQuickSettings(this, FirewallMasterTileService::class.java)
                    runOnUiThread {
                        Toast.makeText(this, "Master Firewall Tile removed from Quick Settings", Toast.LENGTH_SHORT).show()
                        updateMasterTileButtonState()
                    }
                } else {
                    FirewallManager.addTileToQuickSettings(this, FirewallMasterTileService::class.java)
                    FirewallManager.updateMasterTile(this)
                    runOnUiThread {
                        Toast.makeText(this, "Master Firewall Tile added to Quick Settings panel!", Toast.LENGTH_SHORT).show()
                        updateMasterTileButtonState()
                    }
                }
            }
        }

        // Sub-Feature Navigation
        findViewById<View>(R.id.cardTowerLock).setOnClickListener {
            startActivity(Intent(this, HomeTowerLockActivity::class.java))
        }

        val swTcpBbr = findViewById<SwitchMaterial>(R.id.switchTcpBbr)
        val masterPrefs = getSharedPreferences("prefs", MODE_PRIVATE)
        swTcpBbr.isChecked = masterPrefs.getBoolean("network_tcp_tweaks", true) || masterPrefs.getBoolean("tcp_bbr_active", true)
        swTcpBbr.setOnCheckedChangeListener { _, isChecked ->
            masterPrefs.edit()
                .putBoolean("tcp_bbr_active", isChecked)
                .putBoolean("network_tcp_tweaks", isChecked)
                .apply()
            thread {
                val algo = if (isChecked) "bbr" else "cubic"
                ShellUtils.runAsRoot("sysctl -w net.ipv4.tcp_congestion_control=$algo")
            }
            Toast.makeText(this, if (isChecked) "TCP BBR Booster Active" else "TCP BBR Disabled", Toast.LENGTH_SHORT).show()
        }

        // Category Filter Chips Listener
        findViewById<ChipGroup>(R.id.chipGroupFirewallFilter)?.setOnCheckedChangeListener { _, checkedId ->
            selectedFilterCategory = when (checkedId) {
                R.id.chipFirewallMain -> AppCategory.MAIN
                R.id.chipFirewallDual -> AppCategory.DUAL
                R.id.chipFirewallPrivate -> AppCategory.PRIVATE
                else -> null
            }
            refreshList()
        }

        updateSubCardVisibility()

        thread {
            val apps = getInstalledAppsAcrossProfiles()
            cachedAppsList = apps
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    refreshList()
                }
            }
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        updateSubCardVisibility()
        refreshList()
    }

    private fun updateSubCardVisibility() {
        val masterPrefs = getSharedPreferences("prefs", MODE_PRIVATE)
        findViewById<View>(R.id.cardTowerLock).visibility = 
            if (masterPrefs.getBoolean(DaemonManager.PREF_TOWER_LOCK_ENABLED, true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardNetworkBooster).visibility = 
            if (masterPrefs.getBoolean("network_priority_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.layoutFirewallSection).visibility = 
            if (masterPrefs.getBoolean("firewall_enabled", true)) View.VISIBLE else View.GONE
    }

    private fun getInstalledAppsAcrossProfiles(): List<FirewallApp> {
        return ProfileAppUtils.getInstalledAppsAcrossProfiles(this)
    }

    private fun refreshList() {
        layoutContainer.removeAllViews()
        val prefs = getSharedPreferences("firewall_prefs", MODE_PRIVATE)
        val blockedKeys = prefs.getStringSet("blocked_packages", emptySet()) ?: emptySet()
        
        if (blockedKeys.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No apps blocked."
                setTextColor(Color.GRAY)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 50, 0, 0)
            }
            layoutContainer.addView(tv)
            return
        }

        var visibleCount = 0

        for (entry in blockedKeys) {
            val (pkg, userId) = if (entry.contains(":")) {
                val parts = entry.split(":")
                Pair(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 0)
            } else {
                Pair(entry, 0)
            }

            val category = AppCategory.fromUserId(userId)

            // Category filter check
            if (selectedFilterCategory != null && category != selectedFilterCategory) {
                continue
            }

            visibleCount++
            val storedUid = prefs.getInt("uid_$entry", if (userId == 0) prefs.getInt("uid_$pkg", -1) else -1)
            val storedLabel = prefs.getString("label_$entry", null)
            var curWifi = prefs.getBoolean("block_wifi_$entry", true)
            var curData = prefs.getBoolean("block_data_$entry", true)

            val view = layoutInflater.inflate(R.layout.item_firewall_rule, layoutContainer, false)
            val ivIcon = view.findViewById<ImageView>(R.id.ivAppIcon)
            val tvName = view.findViewById<TextView>(R.id.tvAppName)
            val tvPkg = view.findViewById<TextView>(R.id.tvPackageName)
            val tvStatus = view.findViewById<TextView>(R.id.tvAppStatus)
            val tvBadge = view.findViewById<TextView?>(R.id.tvCategoryBadge)
            val btnRemove = view.findViewById<ImageButton>(R.id.btnRemoveRule)
            val btnWifi = view.findViewById<Button>(R.id.btnToggleWifi)
            val btnData = view.findViewById<Button>(R.id.btnToggleData)

            // Resolve Icon & Label
            var resolvedIcon: Drawable? = null
            var resolvedLabel = storedLabel

            // 1. Try cached apps first
            cachedAppsList?.find { it.packageName == pkg && it.userId == userId }?.let {
                resolvedIcon = it.icon
                if (resolvedLabel == null) resolvedLabel = it.label
            }

            // 2. Try LauncherApps badged icon for non-zero users
            if (resolvedIcon == null && userId != 0) {
                try {
                    val userManager = getSystemService(Context.USER_SERVICE) as? UserManager
                    val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                    val targetUser = userManager?.userProfiles?.find {
                        Regex("""\d+""").find(it.toString())?.value?.toIntOrNull() == userId
                    }
                    if (targetUser != null && launcherApps != null) {
                        val acts = launcherApps.getActivityList(pkg, targetUser)
                        if (acts.isNotEmpty()) {
                            resolvedIcon = acts[0].getBadgedIcon(0)
                            if (resolvedLabel == null) resolvedLabel = acts[0].label.toString()
                        }
                    }
                } catch (_: Exception) {}
            }

            // 3. Fallback to package manager
            if (resolvedIcon == null) {
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    resolvedIcon = pm.getApplicationIcon(appInfo)
                    if (resolvedLabel == null) resolvedLabel = pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {}
            }

            if (resolvedIcon != null) {
                ivIcon.setImageDrawable(resolvedIcon)
            } else {
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            tvName.text = resolvedLabel ?: pkg

            // Category badge
            if (tvBadge != null) {
                tvBadge.visibility = View.VISIBLE
                tvBadge.text = "[ ${category.badge} ]"
                tvBadge.setTextColor(Color.parseColor(category.colorHex))
            }

            val uidText = if (storedUid != -1) " • UID $storedUid" else ""
            tvPkg.text = "$pkg$uidText"

            fun updateToggleUI(wifiBlocked: Boolean, dataBlocked: Boolean) {
                if (wifiBlocked && dataBlocked) {
                    tvStatus.text = "🚫 INTERNET COMPLETELY BLOCKED"
                    tvStatus.setTextColor(Color.parseColor("#FF5252"))
                } else if (wifiBlocked) {
                    tvStatus.text = "🛜 ONLY WI-FI BLOCKED • 📶 DATA ACTIVE"
                    tvStatus.setTextColor(Color.parseColor("#FF9800"))
                } else if (dataBlocked) {
                    tvStatus.text = "📶 ONLY DATA BLOCKED • 🛜 WI-FI ACTIVE"
                    tvStatus.setTextColor(Color.parseColor("#FF9800"))
                } else {
                    tvStatus.text = "✅ ALL INTERNET ALLOWED"
                    tvStatus.setTextColor(Color.parseColor("#00E676"))
                }

                if (wifiBlocked) {
                    btnWifi.text = "🛜 WI-FI: BLOCKED"
                    btnWifi.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#3D1515"))
                    btnWifi.setTextColor(Color.parseColor("#FF5252"))
                } else {
                    btnWifi.text = "🛜 WI-FI: ALLOWED"
                    btnWifi.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#152D1E"))
                    btnWifi.setTextColor(Color.parseColor("#69F0AE"))
                }

                if (dataBlocked) {
                    btnData.text = "📶 DATA: BLOCKED"
                    btnData.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#3D1515"))
                    btnData.setTextColor(Color.parseColor("#FF5252"))
                } else {
                    btnData.text = "📶 DATA: ALLOWED"
                    btnData.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#152D1E"))
                    btnData.setTextColor(Color.parseColor("#69F0AE"))
                }
            }

            updateToggleUI(curWifi, curData)

            btnWifi.setOnClickListener {
                curWifi = !curWifi
                prefs.edit().putBoolean("block_wifi_$entry", curWifi).apply()
                updateToggleUI(curWifi, curData)
                if (storedUid != -1) {
                    thread {
                        try {
                            TweakManager.setFirewallRule(storedUid, curWifi, curData)
                            FirewallManager.updateAllTiles(this@FirewallActivity)
                        } catch (_: Exception) {}
                    }
                }
            }

            btnData.setOnClickListener {
                curData = !curData
                prefs.edit().putBoolean("block_data_$entry", curData).apply()
                updateToggleUI(curWifi, curData)
                if (storedUid != -1) {
                    thread {
                        try {
                            TweakManager.setFirewallRule(storedUid, curWifi, curData)
                            FirewallManager.updateAllTiles(this@FirewallActivity)
                        } catch (_: Exception) {}
                    }
                }
            }

            val btnQuickTile = view.findViewById<Button>(R.id.btnQuickTile)
            fun updateQuickTileButton() {
                val assignedSlot = FirewallManager.getAssignedSlot(this@FirewallActivity, entry)
                if (assignedSlot != null) {
                    btnQuickTile.text = "🗑️ REMOVE QUICK TILE (SLOT $assignedSlot)"
                    btnQuickTile.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3D1515"))
                    btnQuickTile.setTextColor(Color.parseColor("#FF5252"))
                } else {
                    btnQuickTile.text = "⚡ ADD QUICK TILE"
                    btnQuickTile.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#152433"))
                    btnQuickTile.setTextColor(Color.parseColor("#60A5FA"))
                }
            }
            updateQuickTileButton()

            btnQuickTile.setOnClickListener {
                val assignedSlot = FirewallManager.getAssignedSlot(this@FirewallActivity, entry)
                if (assignedSlot != null) {
                    FirewallManager.removeAppFromSlot(this@FirewallActivity, entry)
                    updateQuickTileButton()
                    Toast.makeText(this@FirewallActivity, "Quick Tile (Slot $assignedSlot) removed for ${resolvedLabel ?: pkg}", Toast.LENGTH_SHORT).show()
                } else {
                    val slot = FirewallManager.assignAppToSlot(this@FirewallActivity, entry)
                    if (slot != null) {
                        updateQuickTileButton()
                        Toast.makeText(this@FirewallActivity, "Quick Tile created for ${resolvedLabel ?: pkg} in Slot $slot!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@FirewallActivity, "All ${FirewallManager.MAX_SLOTS} Quick Tile slots in use! Please remove another app's tile first.", Toast.LENGTH_LONG).show()
                    }
                }
            }

            val btnSpeedLimit = view.findViewById<Button>(R.id.btnSpeedLimit)
            fun updateSpeedLimitButton() {
                val currentLimit = FirewallManager.getAppBandwidthLimit(this@FirewallActivity, entry)
                if (currentLimit > 0) {
                    val label = if (currentLimit >= 1024) "${currentLimit / 1024} Mbps" else "$currentLimit Kbps"
                    btnSpeedLimit.text = "⚡ SPEED: $label"
                    btnSpeedLimit.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#381E15"))
                    btnSpeedLimit.setTextColor(Color.parseColor("#FB923C"))
                } else {
                    btnSpeedLimit.text = "🚀 SPEED: MAX"
                    btnSpeedLimit.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#241B35"))
                    btnSpeedLimit.setTextColor(Color.parseColor("#C084FC"))
                }
            }
            updateSpeedLimitButton()

            btnSpeedLimit.setOnClickListener {
                val options = arrayOf(
                    "🚀 Unlimited / Full Speed",
                    "⚡ 512 Kbps (Data Saver / Ultra Low)",
                    "⚡ 1 Mbps (Light Browsing / Gaming Ping)",
                    "⚡ 2 Mbps (Smooth 720p Video)",
                    "⚡ 5 Mbps (Fast HD Streaming)"
                )
                val values = intArrayOf(0, 512, 1024, 2048, 5120)
                val currentLimit = FirewallManager.getAppBandwidthLimit(this@FirewallActivity, entry)
                val selectedIdx = values.indexOf(currentLimit).coerceAtLeast(0)

                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@FirewallActivity)
                    .setTitle("Bandwidth Speed Limit")
                    .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                        val chosenLimit = values[which]
                        FirewallManager.setAppBandwidthLimit(this@FirewallActivity, entry, chosenLimit)
                        updateSpeedLimitButton()
                        val msg = if (chosenLimit > 0) "Speed capped at ${options[which].substringAfter(" ")} for ${resolvedLabel ?: pkg}" else "Full speed restored for ${resolvedLabel ?: pkg}"
                        Toast.makeText(this@FirewallActivity, msg, Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            btnRemove.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Remove from Firewall")
                    .setMessage("Restore all network access for ${resolvedLabel ?: pkg} (${category.title})?$uidText")
                    .setPositiveButton("Remove") { _, _ ->
                        unblockApp(entry, storedUid)
                        refreshList()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            layoutContainer.addView(view)
        }

        if (visibleCount == 0) {
            val tv = TextView(this).apply {
                text = "No blocked apps in ${selectedFilterCategory?.title ?: "this category"}."
                setTextColor(Color.GRAY)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 50, 0, 0)
            }
            layoutContainer.addView(tv)
        }
    }

    private fun blockApp(app: FirewallApp) {
        val prefs = getSharedPreferences("firewall_prefs", MODE_PRIVATE)
        val current = prefs.getStringSet("blocked_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
        val key = app.uniqueKey
        current.add(key)
        val editor = prefs.edit().putStringSet("blocked_packages", current)
        editor.putInt("uid_$key", app.uid)
        editor.putString("label_$key", app.label)
        editor.putString("cat_$key", app.category.name)
        editor.putInt("userid_$key", app.userId)
        editor.putBoolean("block_wifi_$key", true)
        editor.putBoolean("block_data_$key", true)
        if (app.userId == 0) {
            editor.putInt("uid_${app.packageName}", app.uid)
            editor.putBoolean("block_wifi_${app.packageName}", true)
            editor.putBoolean("block_data_${app.packageName}", true)
        }
        editor.apply()
        
        thread {
            try {
                TweakManager.setFirewallRule(app.uid, blockWifi = true, blockData = true)
                FirewallManager.updateMasterTile(this)
            } catch (_: Exception) {}
        }
    }

    private fun unblockApp(key: String, storedUid: Int = -1) {
        val prefs = getSharedPreferences("firewall_prefs", MODE_PRIVATE)
        val (pkg, userId) = if (key.contains(":")) {
            val parts = key.split(":")
            Pair(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 0)
        } else {
            Pair(key, 0)
        }

        val retainedUid = if (storedUid != -1) storedUid else prefs.getInt("uid_$key", prefs.getInt("uid_$pkg", -1))
        val current = prefs.getStringSet("blocked_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(key)
        current.remove(pkg) // Legacy fallback

        val editor = prefs.edit().putStringSet("blocked_packages", current)
            .remove("uid_$key")
            .remove("label_$key")
            .remove("cat_$key")
            .remove("userid_$key")
            .remove("block_wifi_$key")
            .remove("block_data_$key")
        if (userId == 0) {
            editor.remove("uid_$pkg")
            editor.remove("block_wifi_$pkg")
            editor.remove("block_data_$pkg")
        }
        editor.apply()
        
        FirewallManager.removeAppFromSlot(this, key)
        thread {
            try {
                val uid = if (retainedUid != -1) {
                    retainedUid
                } else {
                    try {
                        pm.getApplicationInfo(pkg, 0).uid
                    } catch (_: Exception) {
                        null
                    }
                }
                if (uid != null) {
                    TweakManager.setFirewallRule(uid, blockWifi = false, blockData = false)
                }
                FirewallManager.updateAllTiles(this)
            } catch (_: Exception) {}
        }
    }

    private fun showAppPicker() {
        val cached = cachedAppsList
        if (cached == null) {
            Toast.makeText(this, "Loading installed apps, please wait...", Toast.LENGTH_SHORT).show()
            thread {
                val list = getInstalledAppsAcrossProfiles()
                cachedAppsList = list
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showAppPicker()
                    }
                }
            }
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.etSearchApp)
        dialogView.findViewById<View>(R.id.spinnerFilter).visibility = View.GONE
        dialogView.findViewById<View>(R.id.cbSelectAll).visibility = View.GONE
        val scrollPickerFilter = dialogView.findViewById<View?>(R.id.scrollPickerFilter)
        val chipGroupFilter = dialogView.findViewById<ChipGroup?>(R.id.chipGroupPickerFilter)
        val listView = dialogView.findViewById<ListView>(R.id.lvApps)

        scrollPickerFilter?.visibility = View.VISIBLE

        val allApps = cached
        var currentCategory: AppCategory? = null
        val filteredApps = allApps.toMutableList()

        val adapter = object : ArrayAdapter<FirewallApp>(this, R.layout.item_app_picker, filteredApps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_app_picker, parent, false)
                val app = getItem(position)!!
                view.findViewById<CheckBox>(R.id.cbSelect).visibility = View.GONE
                val ivIcon = view.findViewById<ImageView>(R.id.ivAppIcon)
                if (app.icon != null) {
                    ivIcon.setImageDrawable(app.icon)
                } else {
                    ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }

                view.findViewById<TextView>(R.id.tvAppName).text = app.label
                view.findViewById<TextView>(R.id.tvPackageName).text = "${app.packageName} • UID ${app.uid}"

                val tvBadge = view.findViewById<TextView?>(R.id.tvCategoryBadge)
                if (tvBadge != null) {
                    tvBadge.visibility = View.VISIBLE
                    tvBadge.text = "[ ${app.category.badge} ]"
                    tvBadge.setTextColor(Color.parseColor(app.category.colorHex))
                }

                view.findViewById<TextView>(R.id.tvAppStatus).visibility = View.GONE
                return view
            }
        }
        listView.adapter = adapter

        fun applyFilter() {
            val query = etSearch.text?.toString()?.trim()?.lowercase() ?: ""
            filteredApps.clear()
            filteredApps.addAll(allApps.filter { item ->
                val matchesCat = currentCategory == null || item.category == currentCategory
                val matchesQuery = query.isEmpty() ||
                        item.label.lowercase().contains(query) ||
                        item.packageName.lowercase().contains(query)
                matchesCat && matchesQuery
            })
            adapter.notifyDataSetChanged()
        }

        chipGroupFilter?.setOnCheckedChangeListener { _, checkedId ->
            currentCategory = when (checkedId) {
                R.id.chipPickMain -> AppCategory.MAIN
                R.id.chipPickDual -> AppCategory.DUAL
                R.id.chipPickPrivate -> AppCategory.PRIVATE
                else -> null
            }
            applyFilter()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        listView.setOnItemClickListener { _, _, pos, _ -> 
            blockApp(filteredApps[pos])
            refreshList()
            dialog.dismiss() 
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.8).toInt())
    }
}
