package com.example.pracazaliczeniowa.Managers

import android.app.AlertDialog
import android.content.Context
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
import com.example.pracazaliczeniowa.Objects.ModelItem
import com.example.pracazaliczeniowa.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ModelLibraryManager(
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
    private val onRenameImported: (ModelItem) -> Unit,


    private val onExportWithProfile: (ModelItem) -> Unit,
    /**
     * Provides the default [ModelProfile] for a given profileKey, or null if
     * none has been saved yet. Used to compute the scaled dimension string.
     * Inject via: { key -> profileManager.loadDefault(key) }
     */
    private val loadDefaultProfile: (profileKey: String) -> ModelProfile?
) : RecyclerView.Adapter<ModelLibraryManager.ViewHolder>() {

    private val items: MutableList<ModelItem> = items.toMutableList()
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.imgModelThumbnail)
        val name: TextView = view.findViewById(R.id.tvModelName)
        val fileSize: TextView = view.findViewById(R.id.tvFileSize)
        val dimensions: TextView = view.findViewById(R.id.tvDimensions)
        val editDate: TextView = view.findViewById(R.id.tvEditDate)
        val savedBadge: TextView = view.findViewById(R.id.tvSavedBadge)
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

        // ── File size ───────────────────────────────────────────────────────
        holder.fileSize.text = getFileSizeLabel(context, item)

        // ── Dimensions ──────────────────────────────────────────────────────
        // If the model has a known base size and a saved default profile,
        // multiply the base by the saved scale to show real-world dimensions.
        // Falls back to hiding the field if either piece is missing.
        val dimText = buildDimensionString(item)
        if (dimText != null) {
            holder.dimensions.visibility = View.VISIBLE
            holder.dimensions.text = "Dimensions: $dimText"
        } else {
            holder.dimensions.visibility = View.GONE
        }

        // ── Last modified date ───────────────────────────────────────────────
        val (dateVisible, dateText) = resolveDateLabel(context, item)
        if (dateVisible) {
            holder.editDate.visibility = View.VISIBLE
            holder.editDate.text = dateText
        } else {
            holder.editDate.visibility = View.GONE
        }

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
                if (cached.exists()) {
                    menu.add(0, MENU_DELETE_THUMB, 1, context.getString(R.string.menu_delete_thumbnail))
                }
                menu.add(0, MENU_RENAME,       2, context.getString(R.string.menu_rename))
                if (item.profileKey in savedProfiles) {
                    menu.add(0, MENU_EXPORT_PROFILE, 3, context.getString(R.string.menu_export_profile))
                }
                if (!item.isAsset) {
                    menu.add(0, MENU_DELETE_MODEL, 4, context.getString(R.string.menu_delete_model))
                }
                setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        MENU_PREVIEW      -> { onPreviewClick(item); true }
                        MENU_DELETE_THUMB -> { deleteThumbnail(cached, item, holder); true }
                        MENU_RENAME       -> { onRenameImported(item); true }
                        MENU_EXPORT_PROFILE -> { onExportWithProfile(item); true }
                        MENU_DELETE_MODEL -> { confirmDeleteModel(context, item); true }
                        else              -> false
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

    // ── Dimension helpers ─────────────────────────────────────────────────────

    /**
     * Returns a display string like "25 × 10 cm" if the model has a known
     * base size AND a saved default profile to scale it with.
     *
     * • If the default profile exists, the base size is multiplied by the
     *   profile's scaleX (width) and scaleY (height).
     * • If no profile exists yet, the raw base size is shown as-is — so the
     *   user always sees something once [defaultSizeM] is provided.
     * • Returns null only when [defaultSizeM] is null (imported models with
     *   unknown native size).
     */
    private fun buildDimensionString(item: ModelItem): String? {
        val (baseW, baseH, baseD) = item.defaultSizeM ?: return null
        val profile = loadDefaultProfile(item.profileKey)
        val w = if (profile != null) baseW * profile.scaleX else baseW
        val h = if (profile != null) baseH * profile.scaleY else baseH
        val d = if (profile != null) baseD * profile.scaleZ else baseD
        // Convert metres → centimetres and round to one decimal place
        val wCm = (w * 100f)
        val hCm = (h * 100f)
        val dCm = (d* 100f)
        // Use Int display when the value is a whole number, else one decimal
        fun fmt(v: Float) = if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v)
        return "${fmt(wCm)} × ${fmt(hCm)} × ${fmt(dCm)} cm"
    }

    // ── Thumbnail helpers ─────────────────────────────────────────────────────

    private fun bindThumbnail(imageView: ImageView, item: ModelItem, filesDir: File) {
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

    private fun confirmDeleteModel(context: Context, item: ModelItem) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialog_delete_model_title, item.name))
            .setMessage(context.getString(R.string.dialog_delete_model_msg))
            .setPositiveButton(context.getString(R.string.delete)) { _, _ -> onDeleteImported(item) }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .show()
    }

    private fun getFileSizeLabel(context: Context, item: ModelItem): String {
        val bytes = if (item.isAsset) {
            try {
                context.assets.open(item.modelPath).use { it.available().toLong() }
            } catch (e: Exception) { return "" }
        } else {
            val f = File(item.modelPath)
            if (f.exists()) f.length() else return ""
        }
        return when {
            bytes >= 1_048_576 -> "${bytes / 1_048_576} MB"
            bytes >= 1_024     -> "${bytes / 1_024} KB"
            else               -> "$bytes B"
        }
    }

    private fun resolveDateLabel(context: Context, item: ModelItem): Pair<Boolean, String> {
        val fmt = dateFormat
        // A saved/modified profile exists → show "Modified: {date}"
        if (item.lastModified > 0L) {
            return true to context.getString(R.string.label_modified, fmt.format(Date(item.lastModified)))
        }
        // Explicit creation/import timestamp recorded at import time
        if (item.createdAt > 0L) {
            val labelRes = if (item.isAsset) R.string.label_created else R.string.label_imported
            return true to context.getString(labelRes, fmt.format(Date(item.createdAt)))
        }
        // Fallback for imported models whose createdAt was never stored:
        // read the timestamp directly from the file on disk.
        if (!item.isAsset) {
            val fileTs = File(item.modelPath).takeIf { it.exists() }?.lastModified() ?: 0L
            if (fileTs > 0L) {
                return true to context.getString(R.string.label_imported, fmt.format(Date(fileTs)))
            }
        }
        return false to ""
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val MENU_PREVIEW       = 1
        private const val MENU_DELETE_THUMB  = 2
        private const val MENU_RENAME        = 3
        private const val MENU_EXPORT_PROFILE =4
        private const val MENU_DELETE_MODEL  = 5
    }
}