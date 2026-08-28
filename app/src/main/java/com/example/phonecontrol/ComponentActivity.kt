package com.example.phonecontrol

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

class ComponentActivity : AppCompatActivity() {

    private lateinit var rvApps: RecyclerView
    private lateinit var adapter: AppAdapter
    private var allApps = mutableListOf<AppEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_component)

        findViewById<MaterialToolbar>(R.id.toolbarComponent).setNavigationOnClickListener { finish() }

        rvApps = findViewById(R.id.rvApps)
        rvApps.layoutManager = LinearLayoutManager(this)
        
        loadApps()

        findViewById<SearchView>(R.id.searchApps).setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                filterApps(newText ?: "")
                return true
            }
        })
    }

    private fun loadApps() {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        allApps.clear()
        for (app in packages) {
            // Filter out system apps if needed, but for "Battery Surgeon", we might want to allow some
            allApps.add(AppEntry(
                app.loadLabel(pm).toString(),
                app.packageName,
                app.loadIcon(pm)
            ))
        }
        allApps.sortBy { it.name.lowercase() }
        adapter = AppAdapter(allApps) { entry ->
            val intent = Intent(this, ComponentDetailActivity::class.java)
            intent.putExtra("pkg", entry.packageName)
            intent.putExtra("name", entry.name)
            startActivity(intent)
        }
        rvApps.adapter = adapter
    }

    private fun filterApps(query: String) {
        val filtered = allApps.filter { it.name.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
        adapter.updateList(filtered)
    }

    data class AppEntry(val name: String, val packageName: String, val icon: Drawable)

    inner class AppAdapter(private var list: List<AppEntry>, private val onClick: (AppEntry) -> Unit) : RecyclerView.Adapter<AppAdapter.VH>() {
        
        fun updateList(newList: List<AppEntry>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_simple, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = list[position]
            holder.tvName.text = app.name
            holder.tvPkg.text = app.packageName
            holder.ivIcon.setImageDrawable(app.icon)
            holder.itemView.setOnClickListener { onClick(app) }
        }

        override fun getItemCount(): Int = list.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivIcon: ImageView = v.findViewById(R.id.ivAppIcon)
            val tvName: TextView = v.findViewById(R.id.tvAppName)
            val tvPkg: TextView = v.findViewById(R.id.tvAppPkg)
        }
    }
}
