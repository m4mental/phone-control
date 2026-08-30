package com.example.phonecontrol

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread

class AppBackupListActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var listView: ListView
    private lateinit var cbSelectAll: CheckBox
    private lateinit var tvSelectedCount: TextView
    private lateinit var btnBackupSelected: MaterialButton

    private val appList = mutableListOf<ApplicationInfo>()
    private val selectedPackages = mutableSetOf<String>()
    private var currentLoadTask: Thread? = null
    private lateinit var pm: PackageManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup_list)

        pm = packageManager
        findViewById<MaterialToolbar>(R.id.toolbarBackupList).setNavigationOnClickListener { finish() }

        etSearch = findViewById(R.id.etSearchBackup)
        listView = findViewById(R.id.lvBackupApps)
        cbSelectAll = findViewById(R.id.cbSelectAllBackup)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        btnBackupSelected = findViewById(R.id.btnBackupSelected)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { refreshList(s.toString().lowercase()) }
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                for (app in appList) selectedPackages.add(app.packageName)
            } else {
                selectedPackages.clear()
            }
            updateSelectionUi()
            (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
        }

        btnBackupSelected.setOnClickListener {
            if (selectedPackages.isNotEmpty()) {
                showBatchBackupDialog()
            }
        }

        refreshList()
    }

    private fun refreshList(query: String = "") {
        currentLoadTask?.interrupt()
        appList.clear()

        currentLoadTask = thread {
            try {
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
                    .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

                val filtered = apps.filter {
                    val label = pm.getApplicationLabel(it).toString().lowercase()
                    query.isEmpty() || label.contains(query) || it.packageName.lowercase().contains(query)
                }

                if (Thread.interrupted()) return@thread

                runOnUiThread {
                    appList.addAll(filtered)
                    updateAdapter()
                    updateSelectionUi()
                }
            } catch (e: Exception) {}
        }
    }

    private fun updateAdapter() {
        val adapter = object : ArrayAdapter<ApplicationInfo>(this, R.layout.item_app_picker, appList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_app_picker, parent, false)
                val item = getItem(position)!!
                val cb = v.findViewById<CheckBox>(R.id.cbSelect)
                val tvName = v.findViewById<TextView>(R.id.tvAppName)
                val tvPkg = v.findViewById<TextView>(R.id.tvPackageName)
                val ivIcon = v.findViewById<ImageView>(R.id.ivAppIcon)

                tvName.text = pm.getApplicationLabel(item)
                tvPkg.text = item.packageName
                ivIcon.setImageDrawable(pm.getApplicationIcon(item))

                cb.visibility = View.VISIBLE
                cb.isChecked = selectedPackages.contains(item.packageName)

                v.setOnClickListener {
                    if (selectedPackages.contains(item.packageName)) {
                        selectedPackages.remove(item.packageName)
                    } else {
                        selectedPackages.add(item.packageName)
                    }
                    cb.isChecked = selectedPackages.contains(item.packageName)
                    updateSelectionUi()
                }

                return v
            }
        }
        listView.adapter = adapter
    }

    private fun updateSelectionUi() {
        val count = selectedPackages.size
        tvSelectedCount.text = "$count of ${appList.size} selected"
        btnBackupSelected.text = if (count > 0) "⚡ BACKUP SELECTED ($count APPS)" else "⚡ BACKUP SELECTED (0 APPS)"
        btnBackupSelected.isEnabled = count > 0
        cbSelectAll.isChecked = count > 0 && count == appList.size
    }

    private fun showBatchBackupDialog() {
        val options = arrayOf("Application (APK)", "App Data (Private /data/data/)", "OBB Files (Media / Game Data)")
        val checkedItems = booleanArrayOf(true, true, false)

        AlertDialog.Builder(this)
            .setTitle("Backup ${selectedPackages.size} Selected Apps")
            .setMultiChoiceItems(options, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("NEXT") { _, _ ->
                showNotesDialog(checkedItems[0], checkedItems[1], checkedItems[2])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNotesDialog(apk: Boolean, data: Boolean, obb: Boolean) {
        val et = EditText(this).apply {
            hint = "Custom note (e.g., Before ROM update)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(30, 30, 30, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("Add Backup Note")
            .setView(et)
            .setPositiveButton("START BATCH BACKUP") { _, _ ->
                val intent = Intent(this, BackupService::class.java).apply {
                    action = BackupService.ACTION_BATCH_BACKUP
                    putStringArrayListExtra("package_list", ArrayList(selectedPackages))
                    putExtra("notes", et.text.toString().trim())
                    putExtra("include_apk", apk)
                    putExtra("include_data", data)
                    putExtra("include_obb", obb)
                }
                startForegroundService(intent)
                Toast.makeText(this, "Batch Backup started in background", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Back", null)
            .show()
    }
}
