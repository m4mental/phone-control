package com.example.phonecontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import kotlin.concurrent.thread

class AppRestoreListActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var listView: ListView
    private lateinit var cbSelectAll: CheckBox
    private lateinit var tvSelectedCount: TextView
    private lateinit var btnDeleteSelected: MaterialButton
    private lateinit var btnRestoreSelected: MaterialButton

    private val groupedApps = mutableListOf<AppGroup>()
    private val selectedPackages = mutableSetOf<String>()
    private var currentLoadTask: Thread? = null
    private lateinit var pm: PackageManager

    private val vaultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshList(etSearch.text.toString().lowercase())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_restore_list)

        pm = packageManager
        findViewById<MaterialToolbar>(R.id.toolbarRestoreList).setNavigationOnClickListener { finish() }

        etSearch = findViewById(R.id.etSearchRestore)
        listView = findViewById(R.id.lvRestoreBackups)
        cbSelectAll = findViewById(R.id.cbSelectAllRestore)
        tvSelectedCount = findViewById(R.id.tvSelectedRestoreCount)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelectedBackups)
        btnRestoreSelected = findViewById(R.id.btnRestoreSelected)

        val filter = IntentFilter(BackupService.ACTION_VAULT_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vaultReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(vaultReceiver, filter)
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { refreshList(s.toString().lowercase()) }
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                for (group in groupedApps) selectedPackages.add(group.packageName)
            } else {
                selectedPackages.clear()
            }
            updateSelectionUi()
            (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
        }

        btnDeleteSelected.setOnClickListener {
            if (selectedPackages.isNotEmpty()) {
                confirmBatchDelete()
            }
        }

        btnRestoreSelected.setOnClickListener {
            if (selectedPackages.isNotEmpty()) {
                showBatchRestoreOptionsDialog()
            }
        }

        refreshList()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(vaultReceiver) } catch (e: Exception) {}
    }

    private fun refreshList(query: String = "") {
        currentLoadTask?.interrupt()
        groupedApps.clear()

        val masterPath = BackupManager.getAutoVaultPath()
        currentLoadTask = thread {
            try {
                val result = ShellUtils.runAsRoot("ls $masterPath")
                val snapshotsMap = mutableMapOf<String, MutableList<VaultSnapshot>>()

                if (result.exitCode == 0 && result.output.isNotBlank()) {
                    val folders = result.output.split("\n").filter { it.isNotBlank() }
                    for (folder in folders) {
                        if (Thread.interrupted()) return@thread
                        val fullPath = "$masterPath/$folder"
                        val infoStr = ShellUtils.runAsRoot("cat $fullPath/info.json").output
                        try {
                            val json = JSONObject(infoStr)
                            val pkg = json.getString("package_name")
                            val snapshot = VaultSnapshot(
                                appName = json.getString("app_name"),
                                packageName = pkg,
                                version = json.getString("version"),
                                date = json.getString("date"),
                                notes = json.optString("notes", ""),
                                path = fullPath,
                                hasApk = json.optBoolean("has_apk", false),
                                hasData = json.optBoolean("has_data", false),
                                hasObb = json.optBoolean("has_obb", false)
                            )
                            if (!snapshotsMap.containsKey(pkg)) {
                                snapshotsMap[pkg] = mutableListOf()
                            }
                            snapshotsMap[pkg]?.add(snapshot)
                        } catch (e: Exception) {}
                    }
                }

                val groups = mutableListOf<AppGroup>()
                for ((pkg, snapshots) in snapshotsMap) {
                    snapshots.sortByDescending { it.date }
                    val latest = snapshots.first()
                    val group = AppGroup(latest.appName, pkg, snapshots)
                    if (query.isEmpty() || group.appName.lowercase().contains(query) || pkg.lowercase().contains(query)) {
                        groups.add(group)
                    }
                }
                groups.sortBy { it.appName.lowercase() }

                if (Thread.interrupted()) return@thread

                runOnUiThread {
                    groupedApps.addAll(groups)
                    updateAdapter()
                    updateSelectionUi()
                }
            } catch (e: Exception) {}
        }
    }

    private fun updateAdapter() {
        val adapter = object : ArrayAdapter<AppGroup>(this, R.layout.item_app_picker, groupedApps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_app_picker, parent, false)
                val group = getItem(position)!!
                val cb = v.findViewById<CheckBox>(R.id.cbSelect)
                val tvName = v.findViewById<TextView>(R.id.tvAppName)
                val tvPkg = v.findViewById<TextView>(R.id.tvPackageName)
                val ivIcon = v.findViewById<ImageView>(R.id.ivAppIcon)
                val tvStatus = v.findViewById<TextView>(R.id.tvAppStatus)

                tvName.text = group.appName
                val snapshotCount = group.snapshots.size
                val countText = if (snapshotCount == 1) "1 Backup" else "$snapshotCount Backups"
                tvPkg.text = "${group.packageName} • $countText"

                try {
                    ivIcon.setImageDrawable(pm.getApplicationIcon(group.packageName))
                } catch (e: Exception) {
                    ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }

                cb.visibility = View.VISIBLE
                cb.isChecked = selectedPackages.contains(group.packageName)

                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "VIEW (${snapshotCount}) →"
                tvStatus.setTextColor(Color.parseColor("#00E676"))

                cb.setOnClickListener {
                    toggleSelect(group.packageName)
                }

                v.setOnClickListener {
                    showSnapshotsDialog(group)
                }

                return v
            }
        }
        listView.adapter = adapter
    }

    private fun toggleSelect(pkg: String) {
        if (selectedPackages.contains(pkg)) {
            selectedPackages.remove(pkg)
        } else {
            selectedPackages.add(pkg)
        }
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val count = selectedPackages.size
        tvSelectedCount.text = "$count of ${groupedApps.size} selected"
        btnRestoreSelected.text = if (count > 0) "🔄 RESTORE SELECTED ($count APPS)" else "🔄 RESTORE SELECTED (0 APPS)"
        btnRestoreSelected.isEnabled = count > 0
        btnDeleteSelected.visibility = if (count > 0) View.VISIBLE else View.GONE
        btnDeleteSelected.text = "🗑️ DELETE ($count)"
        cbSelectAll.isChecked = count > 0 && count == groupedApps.size
    }

    private fun showSnapshotsDialog(group: AppGroup) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_backup_snapshots, null)
        dialogView.findViewById<TextView>(R.id.tvDialogAppName).text = group.appName
        val countText = if (group.snapshots.size == 1) "1 Backup Snapshot" else "${group.snapshots.size} Backup Snapshots"
        dialogView.findViewById<TextView>(R.id.tvDialogPkgName).text = "${group.packageName} • $countText"

        val container = dialogView.findViewById<LinearLayout>(R.id.layoutSnapshotsContainer)
        container.removeAllViews()

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        for (snapshot in group.snapshots) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.item_backup_snapshot, container, false)
            val tvDate = itemView.findViewById<TextView>(R.id.tvSnapshotDate)
            val tvDetails = itemView.findViewById<TextView>(R.id.tvSnapshotDetails)
            val tvNote = itemView.findViewById<TextView>(R.id.tvSnapshotNote)
            val btnRestore = itemView.findViewById<Button>(R.id.btnRestoreSnapshot)
            val btnDelete = itemView.findViewById<Button>(R.id.btnDeleteSnapshot)

            tvDate.text = snapshot.date
            val contents = "${if(snapshot.hasApk) "APK " else ""}${if(snapshot.hasData) "+ Data " else ""}${if(snapshot.hasObb) "+ OBB" else ""}".trim()
            tvDetails.text = "Version: ${snapshot.version} • [$contents]"

            if (snapshot.notes.isNotBlank()) {
                tvNote.text = "Note: ${snapshot.notes}"
                tvNote.visibility = View.VISIBLE
            }

            btnRestore.setOnClickListener {
                showGranularRestoreDialog(snapshot) {
                    dialog.dismiss()
                }
            }

            btnDelete.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Delete Snapshot?")
                    .setMessage("Permanently remove backup snapshot from ${snapshot.date}?")
                    .setPositiveButton("DELETE") { _, _ ->
                        thread {
                            ShellUtils.runAsRoot("rm -rf '${snapshot.path}'")
                            runOnUiThread {
                                Toast.makeText(this, "Snapshot deleted", Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                                refreshList(etSearch.text.toString().lowercase())
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            container.addView(itemView)
        }

        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showGranularRestoreDialog(snapshot: VaultSnapshot, onProceed: () -> Unit) {
        val options = arrayOf(
            "Application (APK)${if(!snapshot.hasApk) " [Not in Backup]" else ""}",
            "App Data (Private /data/data/)${if(!snapshot.hasData) " [Not in Backup]" else ""}",
            "OBB Files (Media / Game Data)${if(!snapshot.hasObb) " [Not in Backup]" else ""}"
        )
        val checkedItems = booleanArrayOf(snapshot.hasApk, snapshot.hasData, snapshot.hasObb)

        AlertDialog.Builder(this)
            .setTitle("Restore: ${snapshot.appName}")
            .setMultiChoiceItems(options, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("START RESTORE") { _, _ ->
                onProceed()
                val intent = Intent(this, BackupService::class.java).apply {
                    action = BackupService.ACTION_RESTORE
                    putExtra("backup_path", snapshot.path)
                    putExtra("app_name", snapshot.appName)
                    putExtra("restore_apk", checkedItems[0] && snapshot.hasApk)
                    putExtra("restore_data", checkedItems[1] && snapshot.hasData)
                    putExtra("restore_obb", checkedItems[2] && snapshot.hasObb)
                }
                startForegroundService(intent)
                Toast.makeText(this, "Restoring ${snapshot.appName} in background...", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBatchRestoreOptionsDialog() {
        val pathsToRestore = arrayListOf<String>()
        for (pkg in selectedPackages) {
            val group = groupedApps.firstOrNull { it.packageName == pkg }
            val latest = group?.snapshots?.firstOrNull()
            if (latest != null) {
                pathsToRestore.add(latest.path)
            }
        }

        val options = arrayOf("Application (APK)", "App Data (Private /data/data/)", "OBB Files (Media / Game Data)")
        val checkedItems = booleanArrayOf(true, true, true)

        AlertDialog.Builder(this)
            .setTitle("Batch Restore: ${pathsToRestore.size} Apps")
            .setMultiChoiceItems(options, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("START BATCH RESTORE") { _, _ ->
                val intent = Intent(this, BackupService::class.java).apply {
                    action = BackupService.ACTION_BATCH_RESTORE
                    putStringArrayListExtra("backup_paths", pathsToRestore)
                    putExtra("restore_apk", checkedItems[0])
                    putExtra("restore_data", checkedItems[1])
                    putExtra("restore_obb", checkedItems[2])
                }
                startForegroundService(intent)
                Toast.makeText(this, "Batch Restore started in background", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmBatchDelete() {
        val count = selectedPackages.size
        AlertDialog.Builder(this)
            .setTitle("Delete Backups for $count Apps?")
            .setMessage("All backup snapshots for the selected $count apps will be permanently removed. This cannot be undone. Proceed?")
            .setPositiveButton("DELETE ALL") { _, _ ->
                thread {
                    for (pkg in selectedPackages) {
                        val group = groupedApps.firstOrNull { it.packageName == pkg }
                        if (group != null) {
                            for (snap in group.snapshots) {
                                ShellUtils.runAsRoot("rm -rf '${snap.path}'")
                            }
                        }
                    }
                    runOnUiThread {
                        Toast.makeText(this, "Selected backups deleted", Toast.LENGTH_SHORT).show()
                        selectedPackages.clear()
                        refreshList(etSearch.text.toString().lowercase())
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    data class AppGroup(val appName: String, val packageName: String, val snapshots: List<VaultSnapshot>)
    data class VaultSnapshot(val appName: String, val packageName: String, val version: String, val date: String, val notes: String, val path: String, val hasApk: Boolean, val hasData: Boolean, val hasObb: Boolean)
}
