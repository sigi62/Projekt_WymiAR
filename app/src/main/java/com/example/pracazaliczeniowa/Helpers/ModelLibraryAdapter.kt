package com.example.pracazaliczeniowa.Helpers

import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.pracazaliczeniowa.Helpers.ModelItem
import java.io.File

import com.example.pracazaliczeniowa.R

class ModelLibraryAdapter(
    private val items: List<ModelItem>,
    /** Set of profileKeys that already have a saved JSON profile. */
    private val savedProfiles: Set<String>,
    private var selectedKey: String? = null,
    private val onItemClick: (ModelItem) -> Unit
) : RecyclerView.Adapter<ModelLibraryAdapter.ViewHolder>() {


    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView  = view.findViewById(R.id.imgModelThumbnail)
        val name: TextView        = view.findViewById(R.id.tvModelName)
        val savedBadge: TextView  = view.findViewById(R.id.tvSavedBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_model_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item    = items[position]
        val context = holder.itemView.context

        // ── Selection highlight ──────────────────────────────────────────────
        // FIX: was holder.itemView.rootView (= the window's DecorView), now correctly targets the card
        holder.itemView.setBackgroundColor(
            if (item.profileKey == selectedKey) Color.parseColor("#330000FF")
            else Color.TRANSPARENT
        )

        // ── Thumbnail ───────────────────────────────────────────────────────
        // FIX: removed early `return` that was skipping name, badge, and click listener
        holder.thumbnail.setImageDrawable(null)

        val cached = File(context.filesDir, "thumbnails/${item.profileKey}.png")
        val bmp    = if (cached.exists()) BitmapFactory.decodeFile(cached.absolutePath) else null
        when {
            bmp != null               -> holder.thumbnail.setImageBitmap(bmp)
            item.thumbnailRes != null -> holder.thumbnail.setImageResource(item.thumbnailRes)
            else                      -> holder.thumbnail.setImageResource(R.drawable.ic_model_placeholder)
        }

        // ── Name ────────────────────────────────────────────────────────────
        holder.name.text = item.name

        // ── "✓ Saved" badge ─────────────────────────────────────────────────
        holder.savedBadge.visibility =
            if (item.profileKey in savedProfiles) View.VISIBLE else View.GONE

        // ── Click ───────────────────────────────────────────────────────────
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    fun updateSelection(key: String?) {
        this.selectedKey = key
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
}
