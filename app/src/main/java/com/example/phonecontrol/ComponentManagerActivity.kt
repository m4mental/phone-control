package com.example.phonecontrol

import android.content.ComponentName
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

class ComponentManagerActivity : AppCompatActivity() {

    enum class ComponentType {
        SERVICE,
        RECEIVER,
        ACTIVITY,
        PROVIDER
    }

    data class ComponentItem(
        val packageName: String,
        val className: String,
        val simpleName: String,
        val type: ComponentType,
        val isBootReceiver: Boolean,
        val isTracker: Boolean,
        var isEnabled: Boolean
    )

    private lateinit var pm: PackageManager
    private var currentPackage: String = ""

    private lateinit var ivAppIcon: ImageView
    private lateinit var tvAppName: TextView
    private lateinit var tvAppPackage: TextView
    private lateinit var btnChangeApp: MaterialButton
    private lateinit var btnBlockBoot: MaterialButton
    private lateinit var btnBlockTrackers: MaterialButton
    private lateinit var btnResetAll: MaterialButton

    private lateinit var etSearch: EditText
    private lateinit var chipGroup: ChipGroup
    private lateinit var chipAll: Chip
    private lateinit var chipBoot: Chip
    private lateinit var chipTrackers: Chip
    private lateinit var chipServices: Chip
    private lateinit var chipReceivers: Chip
    private lateinit var chipDisabled: Chip

    private lateinit var tvCount: TextView
    private lateinit var rvComponents: RecyclerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmpty: TextView

    private val allComponents = mutableListOf<ComponentItem>()
    private val displayedComponents = mutableListOf<ComponentItem>()
    private lateinit var adapter: ComponentAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_component_manager)

        pm = packageManager
        findViewById<MaterialToolbar>(R.id.toolbarComponentManager).setNavigationOnClickListener { finish() }

        ivAppIcon = findViewById(R.id.ivSelectedAppIcon)
        tvAppName = findViewById(R.id.tvSelectedAppName)
        tvAppPackage = findViewById(R.id.tvSelectedAppPackage)
        btnChangeApp = findViewById(R.id.btnChangeApp)
        btnBlockBoot = findViewById(R.id.btnBlockBoot)
        btnBlockTrackers = findViewById(R.id.btnBlockTrackers)
        btnResetAll = findViewById(R.id.btnResetAll)

        etSearch = findViewById(R.id.etSearchComponent)
        chipGroup = findViewById(R.id.chipGroupComponents)
        chipAll = findViewById(R.id.chipCompAll)
        chipBoot = findViewById(R.id.chipCompBoot)
        chipTrackers = findViewById(R.id.chipCompTrackers)
        chipServices = findViewById(R.id.chipCompServices)
        chipReceivers = findViewById(R.id.chipCompReceivers)
        chipDisabled = findViewById(R.id.chipCompDisabled)

        tvCount = findViewById(R.id.tvComponentCount)
        rvComponents = findViewById(R.id.rvComponents)
        pbLoading = findViewById(R.id.pbComponentLoading)
        tvEmpty = findViewById(R.id.tvEmptyComponents)

        rvComponents.layoutManager = LinearLayoutManager(this)
        rvComponents.setHasFixedSize(true)
        adapter = ComponentAdapter(displayedComponents) { item, isChecked ->
            toggleComponentState(item, isChecked)
        }
        rvComponents.adapter = adapter

        chipGroup.setOnCheckedChangeListener { _, _ -> filterAndDisplay() }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterAndDisplay()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnChangeApp.setOnClickListener { showAppPickerDialog() }
        btnBlockBoot.setOnClickListener { handleBlockBoot() }
        btnBlockTrackers.setOnClickListener { handleBlockTrackers() }
        btnResetAll.setOnClickListener { handleResetAll() }

        val targetPkg = intent.getStringExtra("package_name")
        if (!targetPkg.isNullOrBlank()) {
            loadComponentsForPackage(targetPkg)
        } else {
            thread {
                val apps = AppCacheManager.getOrLoadUserApps(this)
                val firstPkg = apps.firstOrNull()?.packageName ?: "com.google.android.gms"
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    loadComponentsForPackage(firstPkg)
                }
            }
        }
    }

    private fun loadComponentsForPackage(pkg: String) {
        currentPackage = pkg
        pbLoading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        // Update Header UI
        try {
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_DISABLED_COMPONENTS or PackageManager.GET_UNINSTALLED_PACKAGES
            }
            val appInfo = pm.getApplicationInfo(pkg, flags)
            tvAppName.text = pm.getApplicationLabel(appInfo).toString()
            tvAppPackage.text = pkg
            ivAppIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
        } catch (e: Exception) {
            tvAppName.text = pkg
            tvAppPackage.text = pkg
            ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        thread {
            val flags = PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_PROVIDERS or
                    PackageManager.MATCH_DISABLED_COMPONENTS

            val list = mutableListOf<ComponentItem>()

            // Query dumpsys disabled components safely without capturing enabled components
            val dumpsysCmd = ShellUtils.runAsRoot("dumpsys package '$pkg'", 6000)
            val rootDisabledSet = mutableSetOf<String>()
            if (dumpsysCmd.exitCode == 0 && dumpsysCmd.output.contains("disabledComponents:")) {
                var inDisabledSection = false
                for (rawLine in dumpsysCmd.output.lines()) {
                    val line = rawLine.trim()
                    if (line.startsWith("disabledComponents:")) {
                        inDisabledSection = true
                        continue
                    }
                    if (inDisabledSection) {
                        // Stop immediately when encountering another section header or blank line
                        if (line.endsWith(":") || line.startsWith("User ") || line.isEmpty()) {
                            inDisabledSection = false
                            break
                        }
                        if (line.contains(".") && !line.contains(" ")) {
                            rootDisabledSet.add(line)
                        }
                    }
                }
            }

            try {
                val pkgInfo = pm.getPackageInfo(pkg, flags)

                // 1. Services
                pkgInfo.services?.forEach { svc ->
                    list.add(createComponentItem(pkg, svc, ComponentType.SERVICE, rootDisabledSet))
                }

                // 2. Receivers
                pkgInfo.receivers?.forEach { rcv ->
                    list.add(createComponentItem(pkg, rcv, ComponentType.RECEIVER, rootDisabledSet))
                }

                // 3. Providers
                pkgInfo.providers?.forEach { prv ->
                    list.add(createComponentItem(pkg, prv, ComponentType.PROVIDER, rootDisabledSet))
                }

                // 4. Activities
                pkgInfo.activities?.forEach { act ->
                    list.add(createComponentItem(pkg, act, ComponentType.ACTIVITY, rootDisabledSet))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Sort: Boot first, then trackers, then disabled, then alphabetically
            list.sortWith(compareBy({ !it.isBootReceiver }, { !it.isTracker }, { it.isEnabled }, { it.simpleName.lowercase() }))

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allComponents.clear()
                allComponents.addAll(list)

                // Update Chip Counters
                val bootCount = list.count { it.isBootReceiver }
                val trackerCount = list.count { it.isTracker }
                val serviceCount = list.count { it.type == ComponentType.SERVICE }
                val receiverCount = list.count { it.type == ComponentType.RECEIVER }
                val disabledCount = list.count { !it.isEnabled }

                chipAll.text = "All (${list.size})"
                chipBoot.text = "⚡ Boot ($bootCount)"
                chipTrackers.text = "📡 Trackers ($trackerCount)"
                chipServices.text = "⚙️ Services ($serviceCount)"
                chipReceivers.text = "📻 Receivers ($receiverCount)"
                chipDisabled.text = "🛑 Disabled ($disabledCount)"

                btnBlockBoot.visibility = if (bootCount > 0) View.VISIBLE else View.GONE
                btnBlockTrackers.visibility = if (trackerCount > 0) View.VISIBLE else View.GONE
                btnResetAll.visibility = if (disabledCount > 0) View.VISIBLE else View.GONE

                pbLoading.visibility = View.GONE
                filterAndDisplay()
            }
        }
    }

    private fun createComponentItem(
        pkg: String,
        info: ComponentInfo,
        type: ComponentType,
        rootDisabledSet: Set<String>
    ): ComponentItem {
        val clsName = info.name
        val simple = clsName.substringAfterLast(".")
        val lower = clsName.lowercase()

        val isBoot = lower.contains("boot") || lower.contains("startup") || lower.contains("autostart") ||
                lower.contains("reboot") || lower.contains("quickboot") || lower.contains("poweron")

        val isTracker = lower.contains("analytics") || lower.contains("telemetry") || lower.contains("tracker") ||
                lower.contains("metric") || lower.contains("measurement") || lower.contains("crashlytics") ||
                lower.contains("firebase") || lower.contains("facebook") || lower.contains("adjust") ||
                lower.contains("appsflyer") || lower.contains("branch") || lower.contains("umeng") ||
                lower.contains("bugly") || lower.contains("sensorevent") || lower.contains("mixpanel") ||
                lower.contains("flurry") || lower.contains("braze") || lower.contains("onesignal") ||
                lower.contains("unity3d.services") || lower.contains("kochava") || lower.contains("singular") ||
                lower.contains("segment") || lower.contains("moengage") || lower.contains("clevertap") ||
                lower.contains("inmobi") || lower.contains("admob") || lower.contains("applovin") ||
                lower.contains("ironsource") || lower.contains("mintegral") || lower.contains("vungle") ||
                lower.contains("pangle") || lower.contains("criteo")

        val state = try {
            pm.getComponentEnabledSetting(ComponentName(pkg, clsName))
        } catch (e: Exception) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }

        val isEnabled = when {
            rootDisabledSet.contains(clsName) -> false
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> info.isEnabled
        }

        return ComponentItem(
            packageName = pkg,
            className = clsName,
            simpleName = simple,
            type = type,
            isBootReceiver = isBoot,
            isTracker = isTracker,
            isEnabled = isEnabled
        )
    }

    private fun filterAndDisplay() {
        val q = etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val checkedId = chipGroup.checkedChipId

        val filtered = allComponents.filter { item ->
            val matchesTab = when (checkedId) {
                R.id.chipCompBoot -> item.isBootReceiver
                R.id.chipCompTrackers -> item.isTracker
                R.id.chipCompServices -> item.type == ComponentType.SERVICE
                R.id.chipCompReceivers -> item.type == ComponentType.RECEIVER
                R.id.chipCompDisabled -> !item.isEnabled
                else -> true
            }

            val matchesQuery = if (q.isBlank()) true else {
                item.simpleName.lowercase().contains(q) || item.className.lowercase().contains(q)
            }

            matchesTab && matchesQuery
        }

        displayedComponents.clear()
        displayedComponents.addAll(filtered)
        adapter.notifyDataSetChanged()

        tvCount.text = "${filtered.size} components shown"
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = when (checkedId) {
            R.id.chipCompBoot -> "No boot receiver components found in this app."
            R.id.chipCompTrackers -> "No analytics or tracking components found in this app."
            R.id.chipCompDisabled -> "No disabled components. All components are active."
            else -> "No matching components found."
        }
    }

    private fun toggleComponentState(item: ComponentItem, enabled: Boolean) {
        item.isEnabled = enabled
        thread {
            val action = if (enabled) "enable" else "disable"
            ShellUtils.runAsRoot("pm $action '${item.packageName}/${item.className}'")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(this, "${item.simpleName} $action" + "d", Toast.LENGTH_SHORT).show()
                val disabledCount = allComponents.count { !it.isEnabled }
                chipDisabled.text = "🛑 Disabled ($disabledCount)"
                btnResetAll.visibility = if (disabledCount > 0) View.VISIBLE else View.GONE
                filterAndDisplay()
            }
        }
    }

    private fun handleBlockBoot() {
        val bootItems = allComponents.filter { it.isBootReceiver && it.isEnabled }
        if (bootItems.isEmpty()) {
            Toast.makeText(this, "All boot receivers are already blocked!", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("🛡️ Block ${bootItems.size} Boot Receivers?")
            .setMessage("Disabling boot receivers prevents ${tvAppName.text} from launching itself in the background when your phone powers on.")
            .setPositiveButton("BLOCK BOOT") { _, _ ->
                pbLoading.visibility = View.VISIBLE
                thread {
                    val batchCmd = bootItems.joinToString("; ") { "pm disable '${it.packageName}/${it.className}'" }
                    ShellUtils.runAsRoot(batchCmd, 15000)
                    for (item in bootItems) item.isEnabled = false
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        Toast.makeText(this, "Blocked ${bootItems.size} boot receivers!", Toast.LENGTH_SHORT).show()
                        loadComponentsForPackage(currentPackage)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleBlockTrackers() {
        val trackers = allComponents.filter { it.isTracker && it.isEnabled }
        if (trackers.isEmpty()) {
            Toast.makeText(this, "All tracking components are already blocked!", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("🚫 Block ${trackers.size} Trackers & Analytics?")
            .setMessage("Disabling tracking components disables telemetry, metric collection, and ad-tracking services for ${tvAppName.text} without breaking main app features.")
            .setPositiveButton("BLOCK TRACKERS") { _, _ ->
                pbLoading.visibility = View.VISIBLE
                thread {
                    val batchCmd = trackers.joinToString("; ") { "pm disable '${it.packageName}/${it.className}'" }
                    ShellUtils.runAsRoot(batchCmd, 15000)
                    for (item in trackers) item.isEnabled = false
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        Toast.makeText(this, "Blocked ${trackers.size} tracking components!", Toast.LENGTH_SHORT).show()
                        loadComponentsForPackage(currentPackage)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleResetAll() {
        val disabledList = allComponents.filter { !it.isEnabled }
        if (disabledList.isEmpty()) {
            Toast.makeText(this, "No disabled components to reset", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("🔄 Reset All Components?")
            .setMessage("Re-enable all ${disabledList.size} disabled services and receivers for ${tvAppName.text}?")
            .setPositiveButton("RESET ALL") { _, _ ->
                pbLoading.visibility = View.VISIBLE
                thread {
                    val batchCmd = disabledList.joinToString("; ") { "pm enable '${it.packageName}/${it.className}'" }
                    ShellUtils.runAsRoot(batchCmd, 15000)
                    for (item in disabledList) item.isEnabled = true
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        Toast.makeText(this, "Reset ${disabledList.size} components to enabled!", Toast.LENGTH_SHORT).show()
                        loadComponentsForPackage(currentPackage)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAppPickerDialog() {
        val apps = AppCacheManager.getOrLoadAllApps(this)
        if (apps.isEmpty()) {
            Toast.makeText(this, "Loading apps...", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.layout_dialog_app_picker, null)
        val etFilter = dialogView.findViewById<EditText>(R.id.etFilterAppPicker)
        val lvApps = dialogView.findViewById<ListView>(R.id.lvAppPicker)

        val fullLabels = apps.map { "${it.appName}\n${it.packageName}" }.toMutableList()
        val appIndexList = apps.indices.toMutableList()

        val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, fullLabels)
        lvApps.adapter = listAdapter

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select App to Inspect")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        etFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                fullLabels.clear()
                appIndexList.clear()
                apps.forEachIndexed { index, app ->
                    if (q.isBlank() || app.appName.lowercase().contains(q) || app.packageName.lowercase().contains(q)) {
                        fullLabels.add("${app.appName}\n${app.packageName}")
                        appIndexList.add(index)
                    }
                }
                listAdapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        lvApps.setOnItemClickListener { _, _, position, _ ->
            if (position in appIndexList.indices) {
                val actualIndex = appIndexList[position]
                loadComponentsForPackage(apps[actualIndex].packageName)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    inner class ComponentAdapter(
        private val items: List<ComponentItem>,
        private val onToggle: (ComponentItem, Boolean) -> Unit
    ) : RecyclerView.Adapter<ComponentAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvSimple: TextView = view.findViewById(R.id.tvComponentSimpleName)
            val tvType: TextView = view.findViewById(R.id.tvComponentTypeBadge)
            val tvBoot: TextView = view.findViewById(R.id.tvBootBadge)
            val tvTracker: TextView = view.findViewById(R.id.tvTrackerBadge)
            val tvFull: TextView = view.findViewById(R.id.tvComponentFullName)
            val swToggle: SwitchMaterial = view.findViewById(R.id.switchComponent)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_component, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]

            holder.tvSimple.text = item.simpleName
            holder.tvFull.text = item.className
            holder.tvType.text = item.type.name

            holder.tvBoot.visibility = if (item.isBootReceiver) View.VISIBLE else View.GONE
            holder.tvTracker.visibility = if (item.isTracker) View.VISIBLE else View.GONE

            when (item.type) {
                ComponentType.SERVICE -> {
                    holder.tvType.setTextColor(Color.parseColor("#00E5FF"))
                    holder.tvType.setBackgroundColor(Color.parseColor("#162E38"))
                }
                ComponentType.RECEIVER -> {
                    holder.tvType.setTextColor(Color.parseColor("#FFD600"))
                    holder.tvType.setBackgroundColor(Color.parseColor("#332E00"))
                }
                ComponentType.PROVIDER -> {
                    holder.tvType.setTextColor(Color.parseColor("#00E676"))
                    holder.tvType.setBackgroundColor(Color.parseColor("#163820"))
                }
                ComponentType.ACTIVITY -> {
                    holder.tvType.setTextColor(Color.parseColor("#B388FF"))
                    holder.tvType.setBackgroundColor(Color.parseColor("#291638"))
                }
            }

            // Temporarily detach listener to avoid re-triggering during recycling
            holder.swToggle.setOnCheckedChangeListener(null)
            holder.swToggle.isChecked = item.isEnabled
            holder.swToggle.setOnCheckedChangeListener { _, isChecked ->
                onToggle(item, isChecked)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
