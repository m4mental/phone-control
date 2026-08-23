package com.example.phonecontrol

import android.content.Intent
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
import org.json.JSONObject
import kotlin.concurrent.thread

class AppRestoreListActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var listView: ListView
    private val restoreList = mutableListOf<VaultItem>()
    private var currentLoadTask: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_restore_list)

        findViewById<MaterialToolbar>(R.id.toolbarRestoreList).setNavigationOnClickListener { finish() }

        etSearch = findViewById(R.id.etSearchRestore)
        listView = findViewById(R.id.lvRestoreBackups)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { refreshList(s.toString().lowercase()) }
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        refreshList()
    }

    private fun refreshList(query: String = "") {
        currentLoadTask?.interrupt()
        restoreList.clear()
        
        val masterPath = BackupManager.getAutoVaultPath()
        currentLoadTask = thread {
            try {
                val result = ShellUtils.runAsRoot("ls $masterPath")
                if (result.exitCode == 0 && result.output.isNotBlank()) {
                    val folders = result.output.split("\n").filter { it.isNotBlank() }
                    for (folder in folders) {
                        if (Thread.interrupted()) return@thread
                        val fullPath = "$masterPath/$folder"
                        val infoStr = ShellUtils.runAsRoot("cat $fullPath/info.json").output
                        try {
                            val json = JSONObject(infoStr)
                            val item = VaultItem(
                                json.getString("app_name"),
                                json.getString("package_name"),
                                json.getString("version"),
                                json.getString("date"),
                                json.getString("notes"),
                                fullPath,
                                json.optBoolean("has_apk", false),
                                json.optBoolean("has_data", false),
                                json.optBoolean("has_obb", false)
                            )
                            if (query.isEmpty() || item.name.lowercase().contains(query) || item.notes.lowercase().contains(query)) {
                                restoreList.add(item)
                            }
                        } catch (e: Exception) {}
                    }
                }
                restoreList.sortByDescending { it.date }

                if (Thread.interrupted()) return@thread

                runOnUiThread { updateAdapter() }
            } catch (e: Exception) {}
        }
    }

    private fun updateAdapter() {
        val adapter = object : ArrayAdapter<VaultItem>(this, R.layout.item_adb_app, restoreList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_adb_app, parent, false)
                val item = getItem(position)!!
                
                v.findViewById<TextView>(R.id.tvAdbAppName).text = item.name
                val granular = "${if(item.hasApk) "APK " else ""}${if(item.hasData) "Data " else ""}${if(item.hasObb) "OBB" else ""}"
                v.findViewById<TextView>(R.id.tvAdbAppPackage).text = "Version: ${item.version}\nContent: $granular\nNote: ${item.notes}"
                
                v.findViewById<TextView>(R.id.tvAdbAppTag).apply {
                    text = "RESTORE"
                    visibility = View.VISIBLE
                    setBackgroundColor(Color.parseColor("#00C853"))
                }
                
                try {
                    v.findViewById<ImageView>(R.id.ivAdbAppIcon).setImageDrawable(packageManager.getApplicationIcon(item.packageName))
                } catch (e: Exception) {
                    v.findViewById<ImageView>(R.id.ivAdbAppIcon).setImageResource(android.R.drawable.sym_def_app_icon)
                }
                return v
            }
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            showRestoreDialog(restoreList[pos])
        }
    }

    private fun showRestoreDialog(item: VaultItem) {
        AlertDialog.Builder(this)
            .setTitle("Restore ${item.name}?")
            .setMessage("This will overwrite existing app data. Proceed?")
            .setPositiveButton("RESTORE") { _, _ ->
                val intent = Intent(this, BackupService::class.java).apply {
                    action = "ACTION_RESTORE"
                    putExtra("backup_path", item.path)
                    putExtra("app_name", item.name)
                }
                startForegroundService(intent)
                Toast.makeText(this, "Restore started in background", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    data class VaultItem(val name: String, val packageName: String, val version: String, val date: String, val notes: String, val path: String, val hasApk: Boolean, val hasData: Boolean, val hasObb: Boolean)
}
