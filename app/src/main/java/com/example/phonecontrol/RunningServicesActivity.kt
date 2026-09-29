package com.example.phonecontrol

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Live Running Services & Process Monitor:
 * Real-time inspection of active Foreground Services (FGS - Downloads, Music, Sync)
 * and Background Workers across all installed apps.
 */
class RunningServicesActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var rvServices: RecyclerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmpty: TextView

    // Summary Header Views
    private lateinit var tvStatTotal: TextView
    private lateinit var tvStatForeground: TextView
    private lateinit var tvStatBackground: TextView

    // Search & Filter
    private lateinit var etSearch: EditText
    private lateinit var btnClearSearch: ImageView
    private lateinit var chipGroupFilter: ChipGroup

    private lateinit var adapter: RunningServicesAdapter
    private lateinit var pm: PackageManager

    private val allGroupsMaster = mutableListOf<AppServicesGroup>()
    private val displayedGroups = mutableListOf<AppServicesGroup>()
    private val expandedPackages = ConcurrentHashMap.newKeySet<String>()

    private var activeFilterMode = FILTER_ALL
    private var currentSearchQuery = ""

    companion object {
        const val FILTER_ALL = 0
        const val FILTER_FOREGROUND = 1
        const val FILTER_BACKGROUND = 2
        const val FILTER_USER = 3
        const val FILTER_FREEZER = 4
        const val FILTER_SYSTEM = 5

        private val appInfoCache = ConcurrentHashMap<String, Triple<String, Drawable?, Boolean>>()
    }

    data class RunningServiceRecord(
        val serviceClass: String,
        val isForeground: Boolean,
        val pid: Int
    )

    data class AppServicesGroup(
        val packageName: String,
        val appName: String,
        val icon: Drawable?,
        val isSystemApp: Boolean,
        val pids: List<Int>,
        val bestOomAdj: Int,
        val services: List<RunningServiceRecord>,
        val hasForegroundService: Boolean,
        val isPlayingAudio: Boolean,
        val isConfiguredInFreezer: Boolean,
        val isSpecialFreezer: Boolean,
        val isWhitelisted: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_running_services)

        pm = packageManager
        initViews()
        setupListeners()
        loadRunningServices(forceRefresh = true)
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbarRunningServices)
        toolbar.setNavigationOnClickListener { finish() }

        swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.swipeRefreshServices)
        rvServices = findViewById(R.id.rvRunningServices)
        pbLoading = findViewById(R.id.pbLoadingServices)
        tvEmpty = findViewById(R.id.tvEmptyServices)

        tvStatTotal = findViewById(R.id.tvStatTotalServices)
        tvStatForeground = findViewById(R.id.tvStatForegroundServices)
        tvStatBackground = findViewById(R.id.tvStatBackgroundServices)

        etSearch = findViewById(R.id.etSearchServices)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        chipGroupFilter = findViewById(R.id.chipGroupServiceFilter)

        rvServices.layoutManager = LinearLayoutManager(this)
        adapter = RunningServicesAdapter()
        rvServices.adapter = adapter
    }

    private fun setupListeners() {
        swipeRefresh.setOnRefreshListener {
            loadRunningServices(forceRefresh = true)
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s?.toString()?.trim()?.lowercase() ?: ""
                btnClearSearch.visibility = if (currentSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                applyFiltersAndDisplay()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        btnClearSearch.setOnClickListener {
            etSearch.setText("")
        }

        chipGroupFilter.setOnCheckedChangeListener { _, checkedId ->
            activeFilterMode = when (checkedId) {
                R.id.chipFilterForeground -> FILTER_FOREGROUND
                R.id.chipFilterBackground -> FILTER_BACKGROUND
                R.id.chipFilterUser -> FILTER_USER
                R.id.chipFilterFreezer -> FILTER_FREEZER
                R.id.chipFilterSystem -> FILTER_SYSTEM
                else -> FILTER_ALL
            }
            applyFiltersAndDisplay()
        }
    }

    private fun loadRunningServices(forceRefresh: Boolean = false) {
        if (!swipeRefresh.isRefreshing) {
            pbLoading.visibility = View.VISIBLE
        }
        tvEmpty.visibility = View.GONE

        thread {
            val script = """
                dumpsys activity services 2>/dev/null | awk '
                  /\* ServiceRecord\{/ {
                    split(${'$'}4, comp, "/")
                    pkg = comp[1]
                    srv = comp[2]
                    pid = "0"
                    is_fg = "0"
                  }
                  /app=ProcessRecord\{/ {
                    split(${'$'}2, p_info, ":")
                    pid = p_info[1]
                  }
                  /isForeground=true/ {
                    is_fg = "1"
                  }
                  /ConnectionRecord\{.*(FGS|FGSA)/ {
                    is_fg = "1"
                  }
                  /createTime=/ {
                    if (pkg != "" && srv != "") {
                      print pkg "\t" srv "\t" pid "\t" is_fg
                    }
                  }
                ' | sort -u
            """.trimIndent()

            val rawOutput = ShellUtils.fastCmdResult(script, 3500)
            val parsedLines = rawOutput.lines()
                .map { it.trim() }
                .filter { it.contains("\t") }

            val rawGroups = mutableMapOf<String, MutableList<RunningServiceRecord>>()
            for (line in parsedLines) {
                val parts = line.split("\t")
                if (parts.size >= 4) {
                    val pkg = parts[0].trim()
                    val srv = parts[1].trim()
                    val pid = parts[2].trim().toIntOrNull() ?: 0
                    val isFg = (parts[3].trim() == "1")
                    rawGroups.getOrPut(pkg) { mutableListOf() }.add(
                        RunningServiceRecord(srv, isFg, pid)
                    )
                }
            }

            // Gather context data for all packages
            val activeAudioPackages = FreezerManager.getActivePlayingAudioPackages(this@RunningServicesActivity)
            val frozenApps = FreezerManager.getFrozenApps(this@RunningServicesActivity)
            val specialFrozenApps = FreezerManager.getSpecialFreezeApps(this@RunningServicesActivity)
            val userWhitelist = MultitaskingManager.getUserWhitelist(this@RunningServicesActivity)

            val resultGroups = mutableListOf<AppServicesGroup>()

            for ((pkg, srvList) in rawGroups) {
                val cached = appInfoCache[pkg]
                val (appName, icon, isSystem) = if (cached != null) {
                    cached
                } else {
                    try {
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        val name = pm.getApplicationLabel(appInfo).toString()
                        val ic = pm.getApplicationIcon(appInfo)
                        val sys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        val res = Triple(name, ic, sys)
                        appInfoCache[pkg] = res
                        res
                    } catch (_: Exception) {
                        Triple(pkg, null, false)
                    }
                }

                val pids = srvList.map { it.pid }.filter { it > 0 }.distinct()
                var bestAdj = 999
                for (p in pids) {
                    val adjStr = ShellUtils.fastCmdResult("cat /proc/$p/oom_score_adj 2>/dev/null", 1000).trim()
                    val adjVal = adjStr.toIntOrNull()
                    if (adjVal != null && adjVal < bestAdj) {
                        bestAdj = adjVal
                    }
                }

                val hasFgs = srvList.any { it.isForeground } || (bestAdj <= 250 && bestAdj >= 0)
                val isAudio = activeAudioPackages.contains(pkg)
                val isFrozen = frozenApps.contains(pkg) || specialFrozenApps.contains(pkg)
                val isSpecial = specialFrozenApps.contains(pkg)
                val isWhite = userWhitelist.contains(pkg) || MultitaskingManager.protectedApps.contains(pkg)

                resultGroups.add(
                    AppServicesGroup(
                        packageName = pkg,
                        appName = appName,
                        icon = icon,
                        isSystemApp = isSystem,
                        pids = pids,
                        bestOomAdj = if (bestAdj == 999) 500 else bestAdj,
                        services = srvList.distinctBy { it.serviceClass },
                        hasForegroundService = hasFgs,
                        isPlayingAudio = isAudio,
                        isConfiguredInFreezer = isFrozen,
                        isSpecialFreezer = isSpecial,
                        isWhitelisted = isWhite
                    )
                )
            }

            // Sort: Foreground/Downloads/Audio at the TOP, then User apps, then System apps
            resultGroups.sortWith(
                compareByDescending<AppServicesGroup> { it.hasForegroundService || it.isPlayingAudio }
                    .thenBy { it.isSystemApp }
                    .thenBy { it.appName.lowercase() }
            )

            val totalServicesCount = resultGroups.sumOf { it.services.size }
            val foregroundCount = resultGroups.count { it.hasForegroundService || it.isPlayingAudio }
            val backgroundCount = resultGroups.size - foregroundCount

            runOnUiThread {
                allGroupsMaster.clear()
                allGroupsMaster.addAll(resultGroups)

                tvStatTotal.text = totalServicesCount.toString()
                tvStatForeground.text = foregroundCount.toString()
                tvStatBackground.text = backgroundCount.toString()

                pbLoading.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                applyFiltersAndDisplay()
            }
        }
    }

    private fun applyFiltersAndDisplay() {
        val filtered = allGroupsMaster.filter { group ->
            // Filter by Mode
            val matchesFilter = when (activeFilterMode) {
                FILTER_FOREGROUND -> group.hasForegroundService || group.isPlayingAudio
                FILTER_BACKGROUND -> !group.hasForegroundService && !group.isPlayingAudio
                FILTER_USER -> !group.isSystemApp
                FILTER_FREEZER -> group.isConfiguredInFreezer
                FILTER_SYSTEM -> group.isSystemApp
                else -> true
            }

            // Filter by Search Query
            val matchesSearch = if (currentSearchQuery.isEmpty()) {
                true
            } else {
                group.appName.lowercase().contains(currentSearchQuery) ||
                group.packageName.lowercase().contains(currentSearchQuery) ||
                group.services.any { it.serviceClass.lowercase().contains(currentSearchQuery) }
            }

            matchesFilter && matchesSearch
        }

        displayedGroups.clear()
        displayedGroups.addAll(filtered)
        adapter.notifyDataSetChanged()

        tvEmpty.visibility = if (displayedGroups.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showServiceOptionsDialog(group: AppServicesGroup) {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // 1. Force Stop
        options.add("🛑 Force Stop (${group.appName})")
        actions.add {
            thread {
                ShellUtils.fastCmd("am force-stop \"${group.packageName}\" 2>/dev/null")
                runOnUiThread {
                    Toast.makeText(this@RunningServicesActivity, "Stopped ${group.appName}", Toast.LENGTH_SHORT).show()
                    loadRunningServices(forceRefresh = true)
                }
            }
        }

        // 2. Freeze / Hibernate
        options.add("❄️ Hibernate / Freeze Process")
        actions.add {
            thread {
                FreezerManager.freezeApp(this@RunningServicesActivity, group.packageName, force = true, isExplicitDismiss = true)
                runOnUiThread {
                    Toast.makeText(this@RunningServicesActivity, "Hibernated ${group.appName}", Toast.LENGTH_SHORT).show()
                    loadRunningServices(forceRefresh = true)
                }
            }
        }

        // 3. Freezer toggle
        if (group.isConfiguredInFreezer) {
            options.add("⚡ Remove from Freezer List")
            actions.add {
                FreezerManager.setSpecialFreeze(this, group.packageName, false)
                val frozen = FreezerManager.getFrozenApps(this).toMutableSet()
                frozen.remove(group.packageName)
                FreezerManager.saveFrozenApps(this, frozen)
                Toast.makeText(this, "Removed ${group.appName} from Freezer", Toast.LENGTH_SHORT).show()
                loadRunningServices(forceRefresh = true)
            }
        } else {
            options.add("❄️ Add to Special Freezer")
            actions.add {
                FreezerManager.setSpecialFreeze(this, group.packageName, true)
                Toast.makeText(this, "Added ${group.appName} to Special Freezer", Toast.LENGTH_SHORT).show()
                loadRunningServices(forceRefresh = true)
            }
        }

        // 4. Whitelist toggle
        if (group.isWhitelisted) {
            options.add("🛡️ Remove from Protected Whitelist")
            actions.add {
                MultitaskingManager.removeAppFromWhitelist(this, group.packageName)
                Toast.makeText(this, "Removed from Whitelist", Toast.LENGTH_SHORT).show()
                loadRunningServices(forceRefresh = true)
            }
        } else {
            options.add("🛡️ Add to Protected Whitelist")
            actions.add {
                MultitaskingManager.addAppToWhitelist(this, group.packageName)
                Toast.makeText(this, "Protected in Whitelist", Toast.LENGTH_SHORT).show()
                loadRunningServices(forceRefresh = true)
            }
        }

        // 5. Open System App Info
        options.add("ℹ️ Open System App Info")
        actions.add {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", group.packageName, null)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open App Info: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // 6. Copy Package & Services
        options.add("📋 Copy Package & Service Details")
        actions.add {
            val textToCopy = buildString {
                append("App: ${group.appName} (${group.packageName})\n")
                append("PIDs: ${group.pids.joinToString(", ")} | ADJ: ${group.bestOomAdj}\n")
                append("Services (${group.services.size}):\n")
                for (s in group.services) {
                    append(" - ${s.serviceClass} [${if (s.isForeground) "FG" else "BG"}]\n")
                }
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Service Details", textToCopy))
            Toast.makeText(this, "Service details copied!", Toast.LENGTH_SHORT).show()
        }

        AlertDialog.Builder(this)
            .setTitle("${group.appName} (${group.packageName})")
            .setItems(options.toTypedArray()) { _, which ->
                if (which in actions.indices) {
                    actions[which].invoke()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class RunningServicesAdapter : RecyclerView.Adapter<RunningServicesAdapter.ViewHolder>() {

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val ivIcon: ImageView = v.findViewById(R.id.ivServiceAppIcon)
            val tvName: TextView = v.findViewById(R.id.tvServiceAppName)
            val tvPackage: TextView = v.findViewById(R.id.tvServiceAppPackage)
            val btnStop: Button = v.findViewById(R.id.btnQuickStopService)
            val tvStateBadge: TextView = v.findViewById(R.id.tvServiceStateBadge)
            val tvOomBadge: TextView = v.findViewById(R.id.tvServiceOomBadge)
            val tvCountBadge: TextView = v.findViewById(R.id.tvServiceCountBadge)
            val ivChevron: ImageView = v.findViewById(R.id.ivExpandChevron)
            val llServicesContainer: LinearLayout = v.findViewById(R.id.llServicesDetailList)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_running_service_app, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = displayedGroups[position]

            holder.ivIcon.setImageDrawable(group.icon ?: pm.defaultActivityIcon)
            holder.tvName.text = group.appName
            holder.tvPackage.text = group.packageName

            // State Badge
            when {
                group.isPlayingAudio -> {
                    holder.tvStateBadge.text = "🎵 AUDIO PLAYING"
                    holder.tvStateBadge.setTextColor(Color.parseColor("#E040FB"))
                    holder.tvStateBadge.setBackgroundColor(Color.parseColor("#2D0036"))
                }
                group.hasForegroundService -> {
                    holder.tvStateBadge.text = "🟢 FOREGROUND (FGS)"
                    holder.tvStateBadge.setTextColor(Color.parseColor("#00E676"))
                    holder.tvStateBadge.setBackgroundColor(Color.parseColor("#1B3322"))
                }
                group.isConfiguredInFreezer -> {
                    holder.tvStateBadge.text = "❄️ IN FREEZER"
                    holder.tvStateBadge.setTextColor(Color.parseColor("#00E5FF"))
                    holder.tvStateBadge.setBackgroundColor(Color.parseColor("#05212B"))
                }
                else -> {
                    holder.tvStateBadge.text = "🟡 BACKGROUND"
                    holder.tvStateBadge.setTextColor(Color.parseColor("#FFB300"))
                    holder.tvStateBadge.setBackgroundColor(Color.parseColor("#2B2100"))
                }
            }

            // OOM Score & PID Badge
            val pidText = if (group.pids.isNotEmpty()) "PID: ${group.pids.first()}" else "PID: --"
            holder.tvOomBadge.text = "$pidText • ADJ: ${group.bestOomAdj}"

            // Services Count
            val countText = if (group.services.size == 1) "1 Service" else "${group.services.size} Services"
            holder.tvCountBadge.text = countText

            // Expand/Collapse state
            val isExpanded = expandedPackages.contains(group.packageName)
            holder.llServicesContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
            holder.ivChevron.rotation = if (isExpanded) 180f else 0f

            if (isExpanded) {
                holder.llServicesContainer.removeAllViews()
                for (srv in group.services) {
                    val srvView = TextView(holder.itemView.context).apply {
                        val cleanClass = if (srv.serviceClass.startsWith(group.packageName)) {
                            srv.serviceClass.removePrefix(group.packageName)
                        } else {
                            srv.serviceClass
                        }
                        val prefix = if (srv.isForeground) "🟢 [FGS] " else "• "
                        text = "$prefix$cleanClass"
                        setTextColor(if (srv.isForeground) Color.parseColor("#00E676") else Color.parseColor("#B0BEC5"))
                        textSize = 11.5f
                        setPadding(4, 3, 4, 3)
                    }
                    holder.llServicesContainer.addView(srvView)
                }
            }

            // Toggle Expand on chevron click or row click
            val toggleExpand = View.OnClickListener {
                if (expandedPackages.contains(group.packageName)) {
                    expandedPackages.remove(group.packageName)
                } else {
                    expandedPackages.add(group.packageName)
                }
                notifyItemChanged(position)
            }
            holder.ivChevron.setOnClickListener(toggleExpand)
            holder.tvCountBadge.setOnClickListener(toggleExpand)
            holder.itemView.setOnClickListener {
                showServiceOptionsDialog(group)
            }

            // Quick Stop Button
            holder.btnStop.setOnClickListener {
                AlertDialog.Builder(this@RunningServicesActivity)
                    .setTitle("Stop ${group.appName}?")
                    .setMessage("This will cleanly terminate active services of ${group.packageName}.")
                    .setPositiveButton("Stop") { _, _ ->
                        thread {
                            ShellUtils.fastCmd("am force-stop \"${group.packageName}\" 2>/dev/null")
                            if (group.isConfiguredInFreezer) {
                                FreezerManager.freezeApp(this@RunningServicesActivity, group.packageName, force = true, isExplicitDismiss = true)
                            }
                            runOnUiThread {
                                Toast.makeText(this@RunningServicesActivity, "Stopped ${group.appName}", Toast.LENGTH_SHORT).show()
                                loadRunningServices(forceRefresh = true)
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        override fun getItemCount(): Int = displayedGroups.size
    }
}
