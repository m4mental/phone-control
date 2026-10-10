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
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import kotlin.concurrent.thread

class DynamicPrivacyActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var cardPrivacyMaster: MaterialCardView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDesc: TextView
    private lateinit var switchMaster: SwitchMaterial
    private lateinit var ivMasterIcon: ImageView

    private lateinit var switchGuardLocation: SwitchMaterial
    private lateinit var switchGuardCamera: SwitchMaterial
    private lateinit var switchGuardMic: SwitchMaterial
    private lateinit var switchGuardClipboard: SwitchMaterial

    private lateinit var btnAutoSelectSensitive: MaterialButton
    private lateinit var btnClearAll: MaterialButton

    private lateinit var etSearch: EditText
    private lateinit var btnClearSearch: ImageView
    private lateinit var tabLayout: TabLayout
    private lateinit var pbLoading: ProgressBar
    private lateinit var rvApps: RecyclerView

    private val allApps = mutableListOf<PrivacyAppItem>()
    private val displayedApps = mutableListOf<PrivacyAppItem>()
    private lateinit var adapter: PrivacyAppAdapter

    private var currentFilterTab = 0 // 0: All, 1: Guarded, 2: User
    private var currentSearchQuery = ""

    data class PrivacyAppItem(
        val appName: String,
        val packageName: String,
        val isSystem: Boolean,
        val hasSensitivePerms: Boolean,
        var isGuarded: Boolean,
        var icon: Drawable? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dynamic_privacy)

        initViews()
        setupListeners()
        loadAppsAsync()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbarPrivacy)
        toolbar.setNavigationOnClickListener { finish() }

        cardPrivacyMaster = findViewById(R.id.cardPrivacyMaster)
        tvStatusTitle = findViewById(R.id.tvPrivacyStatusTitle)
        tvStatusDesc = findViewById(R.id.tvPrivacyStatusDesc)
        switchMaster = findViewById(R.id.switchMasterPrivacy)
        ivMasterIcon = findViewById(R.id.ivPrivacyMasterIcon)

        switchGuardLocation = findViewById(R.id.switchGuardLocation)
        switchGuardCamera = findViewById(R.id.switchGuardCamera)
        switchGuardMic = findViewById(R.id.switchGuardMic)
        switchGuardClipboard = findViewById(R.id.switchGuardClipboard)

        findViewById<LinearLayout>(R.id.layoutToggleLocation).setOnClickListener {
            switchGuardLocation.isChecked = !switchGuardLocation.isChecked
        }
        findViewById<LinearLayout>(R.id.layoutToggleCamera).setOnClickListener {
            switchGuardCamera.isChecked = !switchGuardCamera.isChecked
        }
        findViewById<LinearLayout>(R.id.layoutToggleMic).setOnClickListener {
            switchGuardMic.isChecked = !switchGuardMic.isChecked
        }
        findViewById<LinearLayout>(R.id.layoutToggleClipboard).setOnClickListener {
            switchGuardClipboard.isChecked = !switchGuardClipboard.isChecked
        }

        btnAutoSelectSensitive = findViewById(R.id.btnAutoSelectSensitive)
        btnClearAll = findViewById(R.id.btnClearAll)

        etSearch = findViewById(R.id.etSearchPrivacy)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        tabLayout = findViewById(R.id.tabLayoutPrivacy)
        pbLoading = findViewById(R.id.pbLoadingPrivacy)
        rvApps = findViewById(R.id.rvPrivacyApps)

        rvApps.layoutManager = LinearLayoutManager(this)
        adapter = PrivacyAppAdapter()
        rvApps.adapter = adapter

        syncMasterStateUi()
        syncVectorTogglesUi()
    }

    private fun syncMasterStateUi() {
        val isMaster = DynamicPrivacyManager.isMasterEnabled(this)
        val guardedCount = DynamicPrivacyManager.getGuardedPackages(this).size

        switchMaster.setOnCheckedChangeListener(null)
        switchMaster.isChecked = isMaster

        if (isMaster) {
            cardPrivacyMaster.setCardBackgroundColor(Color.parseColor("#0A1E14"))
            cardPrivacyMaster.strokeColor = Color.parseColor("#00E676")
            ivMasterIcon.setColorFilter(Color.parseColor("#00E676"))
            tvStatusTitle.text = "$guardedCount Apps Guarded (Active)"
            tvStatusTitle.setTextColor(Color.parseColor("#00E676"))
            tvStatusDesc.text = "Permissions set to ignore on background/screen-off. 0ms restore on reopen."
        } else {
            cardPrivacyMaster.setCardBackgroundColor(Color.parseColor("#18181A"))
            cardPrivacyMaster.strokeColor = Color.parseColor("#444444")
            ivMasterIcon.setColorFilter(Color.parseColor("#888888"))
            tvStatusTitle.text = "$guardedCount Apps Configured (Paused)"
            tvStatusTitle.setTextColor(Color.parseColor("#AAAAAA"))
            tvStatusDesc.text = "Guard is paused. Turn ON to automatically revoke background permissions."
        }

        switchMaster.setOnCheckedChangeListener { _, isChecked ->
            DynamicPrivacyManager.setMasterEnabled(this, isChecked)
            syncMasterStateUi()
            val msg = if (isChecked) "🛡️ Dynamic Privacy Guard Activated" else "Dynamic Privacy Guard Paused"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncVectorTogglesUi() {
        switchGuardLocation.isChecked = DynamicPrivacyManager.isGuardLocation(this)
        switchGuardCamera.isChecked = DynamicPrivacyManager.isGuardCamera(this)
        switchGuardMic.isChecked = DynamicPrivacyManager.isGuardMic(this)
        switchGuardClipboard.isChecked = DynamicPrivacyManager.isGuardClipboard(this)

        switchGuardLocation.setOnCheckedChangeListener { _, isChecked ->
            DynamicPrivacyManager.setGuardLocation(this, isChecked)
        }
        switchGuardCamera.setOnCheckedChangeListener { _, isChecked ->
            DynamicPrivacyManager.setGuardCamera(this, isChecked)
        }
        switchGuardMic.setOnCheckedChangeListener { _, isChecked ->
            DynamicPrivacyManager.setGuardMic(this, isChecked)
        }
        switchGuardClipboard.setOnCheckedChangeListener { _, isChecked ->
            DynamicPrivacyManager.setGuardClipboard(this, isChecked)
        }
    }

    private fun setupListeners() {
        btnAutoSelectSensitive.setOnClickListener {
            thread {
                val selected = DynamicPrivacyManager.autoSelectSensitiveApps(this@DynamicPrivacyActivity)
                runOnUiThread {
                    for (app in allApps) {
                        app.isGuarded = selected.contains(app.packageName)
                    }
                    syncMasterStateUi()
                    filterAndDisplayApps()
                    Toast.makeText(
                        this@DynamicPrivacyActivity,
                        "⚡ Auto-selected ${selected.size} sensitive apps (Social, Delivery & Cabs)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        btnClearAll.setOnClickListener {
            DynamicPrivacyManager.setGuardedPackages(this, emptySet())
            for (app in allApps) {
                app.isGuarded = false
            }
            syncMasterStateUi()
            filterAndDisplayApps()
            Toast.makeText(this, "Cleared all guarded apps", Toast.LENGTH_SHORT).show()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                btnClearSearch.visibility = if (currentSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                filterAndDisplayApps()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnClearSearch.setOnClickListener {
            etSearch.setText("")
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentFilterTab = tab?.position ?: 0
                filterAndDisplayApps()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun loadAppsAsync() {
        pbLoading.visibility = View.VISIBLE
        rvApps.visibility = View.GONE

        thread {
            val pm = packageManager
            val installedPackages = try {
                pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            } catch (e: Exception) {
                emptyList()
            }

            val guardedSet = DynamicPrivacyManager.getGuardedPackages(this@DynamicPrivacyActivity)
            val tempApps = mutableListOf<PrivacyAppItem>()

            for (pkgInfo in installedPackages) {
                val pkgName = pkgInfo.packageName
                if (pkgName == packageName) continue

                val appInfo = pkgInfo.applicationInfo ?: continue
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                val perms = pkgInfo.requestedPermissions
                val hasSensitive = perms != null && (
                    perms.contains(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
                    perms.contains(android.Manifest.permission.ACCESS_COARSE_LOCATION) ||
                    perms.contains(android.Manifest.permission.CAMERA) ||
                    perms.contains(android.Manifest.permission.RECORD_AUDIO)
                )

                val label = try {
                    appInfo.loadLabel(pm).toString()
                } catch (e: Exception) {
                    pkgName
                }

                val icon = try {
                    appInfo.loadIcon(pm)
                } catch (e: Exception) {
                    null
                }

                tempApps.add(
                    PrivacyAppItem(
                        appName = label,
                        packageName = pkgName,
                        isSystem = isSystem,
                        hasSensitivePerms = hasSensitive,
                        isGuarded = guardedSet.contains(pkgName),
                        icon = icon
                    )
                )
            }

            // Sort: Guarded apps first, then sensitive user apps, then user apps, then system apps
            tempApps.sortWith(
                compareByDescending<PrivacyAppItem> { it.isGuarded }
                    .thenBy { it.isSystem }
                    .thenByDescending { it.hasSensitivePerms }
                    .thenBy { it.appName.lowercase() }
            )

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allApps.clear()
                allApps.addAll(tempApps)
                pbLoading.visibility = View.GONE
                rvApps.visibility = View.VISIBLE
                filterAndDisplayApps()
                syncMasterStateUi()
            }
        }
    }

    private fun filterAndDisplayApps() {
        displayedApps.clear()
        for (item in allApps) {
            val matchesTab = when (currentFilterTab) {
                1 -> item.isGuarded
                2 -> !item.isSystem
                else -> true
            }

            if (!matchesTab) continue

            val matchesSearch = if (currentSearchQuery.isEmpty()) {
                true
            } else {
                item.appName.contains(currentSearchQuery, ignoreCase = true) ||
                item.packageName.contains(currentSearchQuery, ignoreCase = true)
            }

            if (matchesSearch) {
                displayedApps.add(item)
            }
        }
        adapter.notifyDataSetChanged()
    }

    private inner class PrivacyAppAdapter : RecyclerView.Adapter<PrivacyAppAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardView: MaterialCardView = view as MaterialCardView
            val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
            val tvName: TextView = view.findViewById(R.id.tvAppName)
            val tvPackage: TextView = view.findViewById(R.id.tvPackageName)
            val tvBadge: TextView = view.findViewById(R.id.tvGuardedBadge)
            val tvPerms: TextView = view.findViewById(R.id.tvPermissionsDetected)
            val swGuard: SwitchMaterial = view.findViewById(R.id.switchGuardApp)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_guarded_app, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount(): Int = displayedApps.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = displayedApps[position]

            holder.tvName.text = app.appName
            holder.tvPackage.text = app.packageName

            if (app.icon != null) {
                holder.ivIcon.setImageDrawable(app.icon)
            } else {
                holder.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            holder.tvBadge.visibility = if (app.isGuarded) View.VISIBLE else View.GONE
            holder.cardView.strokeColor = if (app.isGuarded) Color.parseColor("#00E676") else Color.parseColor("#26262B")

            if (app.hasSensitivePerms) {
                holder.tvPerms.visibility = View.VISIBLE
                holder.tvPerms.text = "📍 GPS • 📷 Camera • 🎙️ Mic"
            } else {
                holder.tvPerms.visibility = View.GONE
            }

            holder.swGuard.setOnCheckedChangeListener(null)
            holder.swGuard.isChecked = app.isGuarded

            val onToggle = { isChecked: Boolean ->
                app.isGuarded = isChecked
                DynamicPrivacyManager.setAppGuarded(this@DynamicPrivacyActivity, app.packageName, isChecked)
                holder.tvBadge.visibility = if (isChecked) View.VISIBLE else View.GONE
                holder.cardView.strokeColor = if (isChecked) Color.parseColor("#00E676") else Color.parseColor("#26262B")
                syncMasterStateUi()
            }

            holder.swGuard.setOnCheckedChangeListener { _, isChecked ->
                onToggle(isChecked)
            }

            holder.cardView.setOnClickListener {
                holder.swGuard.isChecked = !holder.swGuard.isChecked
            }
        }
    }
}
