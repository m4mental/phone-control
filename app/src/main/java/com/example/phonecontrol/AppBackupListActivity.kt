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
import kotlin.concurrent.thread

class AppBackupListActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var listView: ListView
    private val appList = mutableListOf<ApplicationInfo>()
    private var currentLoadTask: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup_list)

        findViewById<MaterialToolbar>(R.id.toolbarBackupList).setNavigationOnClickListener { finish() }

        etSearch = findViewById(R.id.etSearchBackup)
        listView = findViewById(R.id.lvBackupApps)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { refreshList(s.toString().lowercase()) }
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        refreshList()
    }

    private fun refreshList(query: String = "") {
        currentLoadTask?.interrupt()
        appList.clear()
        
        currentLoadTask = thread {
            try {
                val pm = packageManager
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
                }
            } catch (e: Exception) {}
        }
    }

    private fun updateAdapter() {
        val adapter = object : ArrayAdapter<ApplicationInfo>(this, R.layout.item_adb_app, appList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_adb_app, parent, false)
                val item = getItem(position)!!
                
                v.findViewById<TextView>(R.id.tvAdbAppName).text = packageManager.getApplicationLabel(item)
                v.findViewById<TextView>(R.id.tvAdbAppPackage).text = item.packageName
                v.findViewById<ImageView>(R.id.ivAdbAppIcon).setImageDrawable(packageManager.getApplicationIcon(item))
                
                v.findViewById<TextView>(R.id.tvAdbAppTag).apply {
                    text = "BACKUP"
                    visibility = View.VISIBLE
                    setBackgroundColor(Color.parseColor("#1976D2"))
                }
                return v
            }
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            showBackupDialog(appList[pos])
        }
    }

    private fun showBackupDialog(app: ApplicationInfo) {
        val options = arrayOf("Application (APK)", "App Data (Protected)", "OBB Files (Large Data)")
        val checkedItems = booleanArrayOf(true, true, false)
        
        AlertDialog.Builder(this)
            .setTitle("Backup Options: ${packageManager.getApplicationLabel(app)}")
            .setMultiChoiceItems(options, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("NEXT") { _, _ ->
                showNotesDialog(app, checkedItems[0], checkedItems[1], checkedItems[2])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNotesDialog(app: ApplicationInfo, apk: Boolean, data: Boolean, obb: Boolean) {
        val et = EditText(this)
        et.hint = "Add a custom note"
        
        AlertDialog.Builder(this)
            .setTitle("Add Note")
            .setView(et)
            .setPositiveButton("START BACKUP") { _, _ ->
                val intent = Intent(this, BackupService::class.java).apply {
                    action = "ACTION_BACKUP"
                    putExtra("package_name", app.packageName)
                    putExtra("app_name", packageManager.getApplicationLabel(app).toString())
                    putExtra("notes", et.text.toString())
                    putExtra("include_apk", apk)
                    putExtra("include_data", data)
                    putExtra("include_obb", obb)
                }
                startForegroundService(intent)
                Toast.makeText(this, "Backup started in background", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Back", null)
            .show()
    }
}
