package com.example.pracazaliczeniowa

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ModelLibraryAdapter(
    private val items: List<ModelItem>,
    /** Set of profileKeys that already have a saved JSON profile. */
    private val savedProfiles: Set<String>,
    private val onItemClick: (ModelItem) -> Unit
) : RecyclerView.Adapter<ModelLibraryAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.imgModelThumbnail)
        val name: TextView      = view.findViewById(R.id.tvModelName)
        val savedBadge: TextView = view.findViewById(R.id.tvSavedBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_model_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        // Thumbnail – use provided resource or a generic placeholder
        if (item.thumbnailRes != null) {
            holder.thumbnail.setImageResource(item.thumbnailRes)
        } else {
            holder.thumbnail.setImageResource(R.drawable.ic_model_placeholder)
        }

        holder.name.text = item.name

        // Show "✓ Saved" badge if a profile JSON exists for this model
        if (item.profileKey in savedProfiles) {
            holder.savedBadge.visibility = View.VISIBLE
        } else {
            holder.savedBadge.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size
}
