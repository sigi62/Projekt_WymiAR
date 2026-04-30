package com.example.pracazaliczeniowa.Helpers

import android.graphics.BitmapFactory
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
    items: List<ModelItem>,
    /** Set of profileKeys that already have a saved JSON profile. */
    private val savedProfiles: Set<String>,
    private var selectedKey: String? = null,
    private val onItemClick: (ModelItem) -> Unit,
    /** Called when the user taps "Preview" in the three-dots menu. */
    private val onPreviewClick: (ModelItem) -> Unit,
    /**
     * Called when the user confirms "Delete model" for a user-imported item.
     * Never called for bundled asset models.
     */
    private val onDeleteImported: (ModelItem) -> Unit,
    /**
     * Called when the user taps "Rename" for a user-imported item and submits
     * a new name via the dialog. The host (LibraryActivity) is responsible for
     * calling [ModelImportManager.renameModel] on a background thread and then
     * refreshing the adapter via [updateItems].
     * Never called for bundled asset models.
     */
    private val onRenameImported: (ModelItem) -> Unit
) : RecyclerView.Adapter<ModelLibraryAdapter.ViewHolder>() {

    private val items: MutableList<ModelItem> = items.toMutableList()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView    = view.findViewById(R.id.imgModelThumbnail)
        val name: TextView          = view.findViewById(R.id.tvModelName)
        val savedBadge: TextView    = view.findViewById(R.id.tvSavedBadge)
        val importedBadge: TextView = view.findViewById(R.id.tvImportedBadge)
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

        // ── "Imported" badge ─────────────────────────────────────────────────
        holder.importedBadge.visibility =
            if (!item.isAsset) View.VISIBLE else View.GONE

        // ── Three-dots menu ─────────────────────────────────────────────────
        holder.btnOptions.setOnClickListener { anchor ->
            val cached = File(context.filesDir, "thumbnails/${item.profileKey}.jpg")
            PopupMenu(context, anchor).apply {
                menu.add(0, MENU_PREVIEW,       0, context.getString(R.string.menu_preview))
                menu.add(0, MENU_DELETE_THUMB,  1, context.getString(R.string.menu_delete_thumbnail))
                    .isEnabled = cached.exists()
                // "Rename" and "Delete model" only shown for user-imported files
                if (!item.isAsset) {
                    menu.add(0, MENU_RENAME,       2, context.getString(R.string.menu_rename))
                    menu.add(0, MENU_DELETE_MODEL, 3, context.getString(R.string.menu_delete_model))
                }

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
                        MENU_RENAME -> {
                            onRenameImported(item)
                            true
                        }
                        MENU_DELETE_MODEL -> {
                            confirmDeleteModel(context, item)
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

    // ── Public update helpers ─────────────────────────────────────────────────

    fun updateSelection(key: String?) {
        this.selectedKey = key
        notifyDataSetChanged()
    }

    /** Replaces the full item list and refreshes the grid. */
    fun updateItems(newItems: List<ModelItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    // ── Thumbnail helpers ─────────────────────────────────────────────────────

    /**
     * Loads the thumbnail for [item] into [imageView]:
     *  1. On-disk cached JPEG (saved via "Set Thumbnail" in ModelPreviewActivity)
     *  2. Bundled drawable resource (asset models only)
     *  3. Generic placeholder
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

    private fun deleteThumbnail(cached: File, item: ModelItem, holder: ViewHolder) {
        val context = holder.itemView.context
        if (cached.delete()) {
            bindThumbnail(holder.thumbnail, item, context.filesDir)
            Toast.makeText(context, context.getString(R.string.toast_thumbnail_deleted), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, context.getString(R.string.toast_thumbnail_delete_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteModel(context: android.content.Context, item: ModelItem) {
        android.app.AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialog_delete_model_title, item.name))
            .setMessage(context.getString(R.string.dialog_delete_model_msg))
            .setPositiveButton(context.getString(R.string.delete)) { _, _ -> onDeleteImported(item) }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .show()
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val MENU_PREVIEW       = 1
        private const val MENU_DELETE_THUMB  = 2
        private const val MENU_RENAME        = 3
        private const val MENU_DELETE_MODEL  = 4
    }
}