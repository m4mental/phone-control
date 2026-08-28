package com.example.phonecontrol

import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial

class ComponentDetailActivity : AppCompatActivity() {

    private lateinit var pkgName: String
    private lateinit var rv: RecyclerView
    private lateinit var adapter: ComponentAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_component_detail)

        pkgName = intent.getStringExtra("pkg") ?: return
        val name = intent.getStringExtra("name") ?: pkgName

        findViewById<MaterialToolbar>(R.id.toolbarDetail).setNavigationOnClickListener { finish() }
        findViewById<TextView>(R.id.tvDetailName).text = name
        findViewById<TextView>(R.id.tvDetailPkg).text = pkgName
        
        try {
            findViewById<ImageView>(R.id.ivDetailIcon).setImageDrawable(packageManager.getApplicationIcon(pkgName))
        } catch (e: Exception) {}

        rv = findViewById(R.id.rvComponents)
        rv.layoutManager = LinearLayoutManager(this)

        val components = ComponentManager.getAppComponents(packageManager, pkgName)
        adapter = ComponentAdapter(components)
        rv.adapter = adapter
    }

    inner class ComponentAdapter(private val list: List<ComponentManager.ComponentInfo>) : RecyclerView.Adapter<ComponentAdapter.VH>() {
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_component, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvName.text = item.name.substringAfterLast(".")
            holder.tvType.text = item.type
            holder.sw.isChecked = item.isEnabled

            // Highlight suspicious components (Analytics, Ads, Trackers)
            val lower = item.name.lowercase()
            if (lower.contains("analytics") || lower.contains("ads") || lower.contains("tracker") || lower.contains("measurement") || lower.contains("telemetry")) {
                holder.tvName.setTextColor(Color.parseColor("#FFD600")) // Battery Yellow
            } else {
                holder.tvName.setTextColor(Color.WHITE)
            }

            holder.sw.setOnCheckedChangeListener { _, isChecked ->
                ComponentManager.setComponentState(pkgName, item.name, isChecked)
                item.isEnabled = isChecked
            }
        }

        override fun getItemCount(): Int = list.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvCompName)
            val tvType: TextView = v.findViewById(R.id.tvCompType)
            val sw: SwitchMaterial = v.findViewById(R.id.switchComp)
        }
    }
}
