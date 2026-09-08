package com.example.phonecontrol

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class AppExtractorAdapter(
    private var apps: List<AppExtractorManager.AppItem>,
    private val onExtractClicked: (AppExtractorManager.AppItem) -> Unit
) : RecyclerView.Adapter<AppExtractorAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvName: TextView = view.findViewById(R.id.tvAppName)
        val tvPackage: TextView = view.findViewById(R.id.tvAppPackage)
        val tvMeta: TextView = view.findViewById(R.id.tvAppMeta)
        val badgeSplit: TextView = view.findViewById(R.id.badgeSplit)
        val btnExtract: MaterialButton = view.findViewById(R.id.btnExtract)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_extractor, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = apps[position]

        if (item.icon != null) {
            holder.ivIcon.setImageDrawable(item.icon)
        } else {
            holder.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        holder.tvName.text = item.appName
        holder.tvPackage.text = item.packageName
        val sizeStr = AppExtractorManager.formatSize(item.totalSize)
        holder.tvMeta.text = "v${item.versionName} • $sizeStr"

        if (item.isSplit) {
            holder.badgeSplit.visibility = View.VISIBLE
            holder.badgeSplit.text = "SPLIT (${item.apkPaths.size} APKs)"
        } else {
            holder.badgeSplit.visibility = View.GONE
        }

        holder.btnExtract.setOnClickListener {
            onExtractClicked(item)
        }
        holder.itemView.setOnClickListener {
            onExtractClicked(item)
        }
    }

    override fun getItemCount(): Int = apps.size

    fun updateList(newList: List<AppExtractorManager.AppItem>) {
        apps = newList
        notifyDataSetChanged()
    }
}
