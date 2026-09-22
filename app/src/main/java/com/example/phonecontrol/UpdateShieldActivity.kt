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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import kotlin.concurrent.thread

class UpdateShieldActivity : AppCompatActivity() {

    data class ShieldAppItem(
        val appName: String,
        val packageName: String,
        val versionName: String,
        val icon: Drawable?,
        val isSystem: Boolean,
        var isShielded: Boolean
    )

    private lateinit var toolbar: MaterialToolbar
    private lateinit var cardShieldStatus: MaterialCardView
    private lateinit var ivShieldHeaderIcon: ImageView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDesc: TextView
    private lateinit var swMasterShieldSync: SwitchMaterial
    private lateinit var etSearch: EditText
    private lateinit var btnClearSearch: ImageView
    private lateinit var tabLayout: TabLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView

    private val allApps = mutableListOf<ShieldAppItem>()
    private val displayedApps = mutableListOf<ShieldAppItem>()
    private lateinit var adapter: ShieldAppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update_shield)

        toolbar = findViewById(R.id.toolbarUpdateShield)
        cardShieldStatus = findViewById(R.id.cardShieldStatus)
        ivShieldHeaderIcon = findViewById(R.id.ivShieldHeaderIcon)
        tvStatusTitle = findViewById(R.id.tvShieldStatusTitle)
        tvStatusDesc = findViewById(R.id.tvShieldStatusDesc)
        swMasterShieldSync = findViewById(R.id.swMasterShieldSync)
        etSearch = findViewById(R.id.etSearchShield)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        tabLayout = findViewById(R.id.tabLayoutShield)
        progressBar = findViewById(R.id.pbLoadingShield)
        recyclerView = findViewById(R.id.rvShieldApps)

        toolbar.setNavigationOnClickListener { finish() }

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ShieldAppAdapter()
        recyclerView.adapter = adapter

        setupListeners()
        loadInstalledApps()
    }

    override fun onResume() {
        super.onResume()
        updateHeaderStatus()
        adapter.notifyDataSetChanged()
    }

    private fun setupListeners() {
        swMasterShieldSync.setOnCheckedChangeListener { _, isChecked ->
            UpdateShieldManager.setMasterEnabled(this, isChecked)
            updateHeaderStatus()
            adapter.notifyDataSetChanged()
            val msg = if (isChecked) {
                "🛡️ Master Setting Synced: Play Store Update Shield Active"
            } else {
                "⏸️ Master Setting Synced: Play Store Update Shield Paused"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                applyFilter()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                btnClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                applyFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        btnClearSearch.setOnClickListener {
            etSearch.text.clear()
        }
    }

    private fun loadInstalledApps() {
        progressBar.visibility = View.VISIBLE
        thread {
            val pm = packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val shieldedSet = UpdateShieldManager.getShieldedPackages(this)

            val items = mutableListOf<ShieldAppItem>()
            for (app in installed) {
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                // Skip Phone Control itself
                if (app.packageName == packageName) continue

                val label = try {
                    pm.getApplicationLabel(app).toString()
                } catch (e: Exception) {
                    app.packageName
                }

                val version = try {
                    val pInfo = pm.getPackageInfo(app.packageName, 0)
                    "v${pInfo.versionName ?: "1.0"}"
                } catch (e: Exception) {
                    ""
                }

                val icon = try {
                    pm.getApplicationIcon(app)
                } catch (e: Exception) {
                    null
                }

                val isShielded = shieldedSet.contains(app.packageName)
                items.add(ShieldAppItem(label, app.packageName, version, icon, isSystem, isShielded))
            }

            // Sort: Shielded first, then alphabetically
            items.sortWith(compareByDescending<ShieldAppItem> { it.isShielded }.thenBy { it.appName.lowercase() })

            runOnUiThread {
                allApps.clear()
                allApps.addAll(items)
                progressBar.visibility = View.GONE
                updateHeaderStatus()
                applyFilter()
            }
        }
    }

    private fun updateHeaderStatus() {
        val isMasterOn = UpdateShieldManager.isMasterEnabled(this)
        val shieldedCount = allApps.count { it.isShielded }

        swMasterShieldSync.setOnCheckedChangeListener(null)
        swMasterShieldSync.isChecked = isMasterOn
        swMasterShieldSync.setOnCheckedChangeListener { _, isChecked ->
            UpdateShieldManager.setMasterEnabled(this, isChecked)
            updateHeaderStatus()
            adapter.notifyDataSetChanged()
            val msg = if (isChecked) {
                "🛡️ Master Setting Synced: Play Store Update Shield Active"
            } else {
                "⏸️ Master Setting Synced: Play Store Update Shield Paused"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        if (isMasterOn) {
            cardShieldStatus.setCardBackgroundColor(Color.parseColor("#0A161E"))
            cardShieldStatus.strokeColor = Color.parseColor("#00E5FF")
            ivShieldHeaderIcon.setColorFilter(Color.parseColor("#00E5FF"))
            tvStatusTitle.text = "$shieldedCount Apps Shielded • Master Active"
            tvStatusTitle.setTextColor(Color.parseColor("#00E5FF"))
            tvStatusDesc.text = "Shield active & synced with Master Settings. Auto-updates blocked."
            tvStatusDesc.setTextColor(Color.parseColor("#80DEEA"))
            recyclerView.alpha = 1.0f
        } else {
            cardShieldStatus.setCardBackgroundColor(Color.parseColor("#141414"))
            cardShieldStatus.strokeColor = Color.parseColor("#444444")
            ivShieldHeaderIcon.setColorFilter(Color.parseColor("#777777"))
            tvStatusTitle.text = "Master Shield Paused ($shieldedCount Apps)"
            tvStatusTitle.setTextColor(Color.parseColor("#AAAAAA"))
            tvStatusDesc.text = "Disabled in Master Settings. Toggle switch on the right to activate."
            tvStatusDesc.setTextColor(Color.parseColor("#777777"))
            recyclerView.alpha = 0.55f
        }
    }

    private fun applyFilter() {
        val query = etSearch.text.toString().trim().lowercase()
        val tabPos = tabLayout.selectedTabPosition

        val filtered = allApps.filter { item ->
            val matchesTab = when (tabPos) {
                1 -> item.isShielded
                2 -> !item.isSystem
                3 -> item.isSystem
                else -> true
            }

            val matchesSearch = query.isEmpty() ||
                    item.appName.lowercase().contains(query) ||
                    item.packageName.lowercase().contains(query)

            matchesTab && matchesSearch
        }

        displayedApps.clear()
        displayedApps.addAll(filtered)
        adapter.notifyDataSetChanged()
    }

    private inner class ShieldAppAdapter : RecyclerView.Adapter<ShieldAppAdapter.ViewHolder>() {

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val ivIcon: ImageView = v.findViewById(R.id.ivAppIcon)
            val tvName: TextView = v.findViewById(R.id.tvAppName)
            val tvPkg: TextView = v.findViewById(R.id.tvAppPkg)
            val tvVersion: TextView = v.findViewById(R.id.tvAppVersion)
            val tvBadge: TextView = v.findViewById(R.id.tvShieldBadge)
            val swShield: SwitchMaterial = v.findViewById(R.id.swShield)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_update_shield_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = displayedApps[position]
            val isMasterOn = UpdateShieldManager.isMasterEnabled(this@UpdateShieldActivity)

            holder.tvName.text = item.appName
            holder.tvPkg.text = item.packageName
            holder.tvVersion.text = item.versionName
            if (item.icon != null) {
                holder.ivIcon.setImageDrawable(item.icon)
            } else {
                holder.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            holder.tvBadge.visibility = if (item.isShielded && isMasterOn) View.VISIBLE else View.GONE

            holder.swShield.setOnCheckedChangeListener(null)
            holder.swShield.isChecked = item.isShielded

            holder.itemView.setOnClickListener {
                holder.swShield.isChecked = !holder.swShield.isChecked
            }

            holder.swShield.setOnCheckedChangeListener { _, isChecked ->
                item.isShielded = isChecked

                // If user toggles shield ON while master setting is OFF, automatically activate Master Setting!
                if (isChecked && !UpdateShieldManager.isMasterEnabled(this@UpdateShieldActivity)) {
                    UpdateShieldManager.setMasterEnabled(this@UpdateShieldActivity, true)
                    Toast.makeText(this@UpdateShieldActivity, "⚡ Auto-activated Master Shield in Settings!", Toast.LENGTH_SHORT).show()
                }

                updateHeaderStatus()
                holder.tvBadge.visibility = if (isChecked && UpdateShieldManager.isMasterEnabled(this@UpdateShieldActivity)) View.VISIBLE else View.GONE

                thread {
                    val success = UpdateShieldManager.setShielded(this@UpdateShieldActivity, item.packageName, isChecked)
                    runOnUiThread {
                        if (success) {
                            val msg = if (isChecked) {
                                "🛡️ Shielded ${item.appName} (Play Store updates blocked)"
                            } else {
                                "🔄 Re-attached ${item.appName} to Play Store"
                            }
                            Toast.makeText(this@UpdateShieldActivity, msg, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@UpdateShieldActivity, "Failed to apply shield for ${item.appName}", Toast.LENGTH_SHORT).show()
                            holder.swShield.isChecked = !isChecked
                            item.isShielded = !isChecked
                            holder.tvBadge.visibility = if (item.isShielded) View.VISIBLE else View.GONE
                            updateHeaderStatus()
                        }
                    }
                }
            }
        }

        override fun getItemCount(): Int = displayedApps.size
    }
}
