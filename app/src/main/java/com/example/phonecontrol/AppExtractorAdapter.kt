package com.example.phonecontrol

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class AppExtractorAdapter(
    private var apps: List<AppExtractorManager.AppItem>,
    private val onExtractClicked: (AppExtractorManager.AppItem) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
    private val onMultiSelectModeChanged: ((Boolean) -> Unit)? = null
) : RecyclerView.Adapter<AppExtractorAdapter.ViewHolder>() {

    var isMultiSelectMode: Boolean = false
        private set

    val selectedPackages = mutableSetOf<String>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cbSelect: CheckBox = view.findViewById(R.id.cbSelect)
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

        if (isMultiSelectMode) {
            holder.cbSelect.visibility = View.VISIBLE
            holder.cbSelect.isChecked = selectedPackages.contains(item.packageName)
            holder.btnExtract.visibility = View.GONE

            val toggleAction = {
                if (selectedPackages.contains(item.packageName)) {
                    selectedPackages.remove(item.packageName)
                } else {
                    selectedPackages.add(item.packageName)
                }
                notifyItemChanged(position)
                onSelectionChanged(selectedPackages.size)
            }

            holder.itemView.setOnClickListener { toggleAction() }
            holder.cbSelect.setOnClickListener { toggleAction() }
            holder.itemView.setOnLongClickListener(null)
        } else {
            holder.cbSelect.visibility = View.GONE
            holder.btnExtract.visibility = View.VISIBLE

            holder.btnExtract.setOnClickListener {
                onExtractClicked(item)
            }
            holder.itemView.setOnClickListener {
                onExtractClicked(item)
            }
            holder.itemView.setOnLongClickListener {
                setMultiSelectMode(true)
                selectedPackages.add(item.packageName)
                notifyDataSetChanged()
                onSelectionChanged(selectedPackages.size)
                true
            }
        }
    }

    override fun getItemCount(): Int = apps.size

    fun setMultiSelectMode(enabled: Boolean) {
        if (isMultiSelectMode != enabled) {
            isMultiSelectMode = enabled
            if (!enabled) {
                selectedPackages.clear()
                onSelectionChanged(0)
            }
            notifyDataSetChanged()
            onMultiSelectModeChanged?.invoke(enabled)
        }
    }

    fun selectAll() {
        selectedPackages.clear()
        for (app in apps) {
            selectedPackages.add(app.packageName)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedPackages.size)
    }

    fun deselectAll() {
        selectedPackages.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun getSelectedApps(): List<AppExtractorManager.AppItem> {
        return apps.filter { selectedPackages.contains(it.packageName) }
    }

    fun updateList(newList: List<AppExtractorManager.AppItem>) {
        apps = newList
        notifyDataSetChanged()
        onSelectionChanged(selectedPackages.size)
    }
}
