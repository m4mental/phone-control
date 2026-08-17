package com.example.phonecontrol

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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

class BloatwareActivity : AppCompatActivity() {

    private lateinit var pm: PackageManager
    private lateinit var adapter: ArrayAdapter<ApplicationInfo>
    private val bloatApps = mutableListOf<ApplicationInfo>()
    private val filteredApps = mutableListOf<ApplicationInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bloatware)

        pm = packageManager
        findViewById<MaterialToolbar>(R.id.toolbarBloat).setNavigationOnClickListener { finish() }

        val listView = findViewById<ListView>(R.id.lvBloatApps)
        val etSearch = findViewById<EditText>(R.id.etSearchBloat)

        adapter = object : ArrayAdapter<ApplicationInfo>(this, R.layout.item_app_picker, filteredApps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_app_picker, parent, false)
                val app = getItem(position)!!
                view.findViewById<CheckBox>(R.id.cbSelect).visibility = View.GONE
                view.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(pm.getApplicationIcon(app))
                view.findViewById<TextView>(R.id.tvAppName).text = pm.getApplicationLabel(app)
                view.findViewById<TextView>(R.id.tvPackageName).text = app.packageName
                return view
            }
        }
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val app = filteredApps[position]
            showBloatDialog(app)
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().lowercase()
                filteredApps.clear()
                filteredApps.addAll(bloatApps.filter { pm.getApplicationLabel(it).toString().lowercase().contains(q) || it.packageName.contains(q) })
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        loadBloatApps()
    }

    private fun loadBloatApps() {
        thread {
            val all = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val systemApps = all.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
                .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            
            runOnUiThread {
                bloatApps.clear()
                bloatApps.addAll(systemApps)
                filteredApps.clear()
                filteredApps.addAll(systemApps)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun showBloatDialog(app: ApplicationInfo) {
        val name = pm.getApplicationLabel(app)
        AlertDialog.Builder(this)
            .setTitle("Disable Bloatware?")
            .setMessage("Do you want to disable $name? It will be removed from your launcher and background.")
            .setPositiveButton("Disable") { _, _ ->
                ShellUtils.runAsRoot("pm disable-user --user 0 ${app.packageName}")
                Toast.makeText(this, "$name Disabled", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
