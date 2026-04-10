package com.example.pracazaliczeniowa.Helpers

import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import com.example.pracazaliczeniowa.R

class ModelLibraryAdapter(
    private val items: List<ModelItem>,
    /** Set of profileKeys that already have a saved JSON profile. */
    private val savedProfiles: Set<String>,
    private var selectedKey: String? = null,
    private val onItemClick: (ModelItem) -> Unit,
    /** Called when the user taps "Preview" in the three-dots menu. */
    private val onPreviewClick: (ModelItem) -> Unit
) : RecyclerView.Adapter<ModelLibraryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView    = view.findViewById(R.id.imgModelThumbnail)
        val name: TextView          = view.findViewById(R.id.tvModelName)
        val savedBadge: TextView    = view.findViewById(R.id.tvSavedBadge)
        val btnOptions: ImageButton = view.findViewById(R.id.btnModelOptions)
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
        val backgroundColor = if (item.profileKey == selectedKey) {
            ContextCompat.getColor(context, R.color.card_highlight)
        } else {
            ContextCompat.getColor(context, R.color.card_background)
        }

        (holder.itemView as CardView).setCardBackgroundColor(backgroundColor)
        // ── Thumbnail ───────────────────────────────────────────────────────
        bindThumbnail(holder.thumbnail, item, context.filesDir)

        // ── Name ────────────────────────────────────────────────────────────
        holder.name.text = item.name

        // ── "✓ Saved" badge ─────────────────────────────────────────────────
        holder.savedBadge.visibility =
            if (item.profileKey in savedProfiles) View.VISIBLE else View.GONE

        // ── Three-dots menu ─────────────────────────────────────────────────
        holder.btnOptions.setOnClickListener { anchor ->
            val cached = File(context.filesDir, "thumbnails/${item.profileKey}.jpg")
            PopupMenu(context, anchor).apply {
                menu.add(0, MENU_PREVIEW,      0, "Preview")
                menu.add(0, MENU_DELETE_THUMB, 1, "Delete thumbnail")
                    .isEnabled = cached.exists()

                setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        MENU_PREVIEW -> {
                            onPreviewClick(item)
                            true
                        }
                        MENU_DELETE_THUMB -> {
                            deleteThumbnail(cached, item, holder)
                            true
                        }
                        else -> false
                    }
                }
                show()
            }
        }

        // ── Card click → launch AR ───────────────────────────────────────────
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    // -------------------------------------------------------------------------
    // Thumbnail helpers
    // -------------------------------------------------------------------------

    /**
     * Loads the thumbnail for [item] into [imageView], checking the on-disk
     * cache first, then the bundled drawable, then the generic placeholder.
     * Extracted so both onBindViewHolder and deleteThumbnail can call it.
     */
    private fun bindThumbnail(imageView: ImageView, item: ModelItem, filesDir: java.io.File) {
        imageView.setImageDrawable(null)
        val cached = File(filesDir, "thumbnails/${item.profileKey}.jpg")
        val bmp    = if (cached.exists()) BitmapFactory.decodeFile(cached.absolutePath) else null
        when {
            bmp != null               -> imageView.setImageBitmap(bmp)
            item.thumbnailRes != null -> imageView.setImageResource(item.thumbnailRes)
            else                      -> imageView.setImageResource(R.drawable.ic_model_placeholder)
        }
    }

    /**
     * Deletes the cached thumbnail PNG for [item], then immediately refreshes
     * the card in-place (no full notifyDataSetChanged needed).
     */
    private fun deleteThumbnail(
        cached: File,
        item: ModelItem,
        holder: ViewHolder
    ) {
        val context = holder.itemView.context
        if (cached.delete()) {
            // Refresh just this card's thumbnail back to the fallback drawable
            bindThumbnail(holder.thumbnail, item, context.filesDir)
            Toast.makeText(context, "Thumbnail deleted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Could not delete thumbnail", Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------------------------

    fun updateSelection(key: String?) {
        this.selectedKey = key
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    companion object {
        private const val MENU_PREVIEW      = 1
        private const val MENU_DELETE_THUMB = 2
    }
}