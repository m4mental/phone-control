package com.example.phonecontrol

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class AutoEqAdapter(
    private var items: List<AutoEqHeadphone>,
    private val onApplyClick: (AutoEqHeadphone) -> Unit
) : RecyclerView.Adapter<AutoEqAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvModel: TextView = view.findViewById(R.id.tvHeadphoneModel)
        val tvDesc: TextView = view.findViewById(R.id.tvHeadphoneDesc)
        val btnApply: MaterialButton = view.findViewById(R.id.btnApplyAutoEq)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_autoeq_headphone, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvModel.text = item.model
        holder.tvDesc.text = "${item.brand} • ${item.description}"
        holder.btnApply.setOnClickListener {
            onApplyClick(item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newItems: List<AutoEqHeadphone>) {
        this.items = newItems
        notifyDataSetChanged()
    }
}
