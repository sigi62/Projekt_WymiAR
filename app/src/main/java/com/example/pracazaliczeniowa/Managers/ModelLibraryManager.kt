package com.example.pracazaliczeniowa.Managers

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
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
        val thumbnailFrame: View = view.findViewById(R.id.thumbnailFrame)
        val thumbnail: ImageView = view.findViewById(R.id.imgModelThumbnail)
        val name: TextView = view.findViewById(R.id.tvModelName)
        val fileSize: TextView = view.findViewById(R.id.tvFileSize)
        val dimensions: TextView = view.findViewById(R.id.tvDimensions)
        val editDate: TextView = view.findViewById(R.id.tvEditDate)
        val profileBadge: TextView = view.findViewById(R.id.tvProfileBadge)
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
        // The whole CardView gets the highlight colour so the rounded border
        // and all spacing around the image lights up. The thumbnail frame is
        // pinned to card_image_background so it never changes colour.
        val cardColor = if (item.profileKey == selectedKey) {
            ContextCompat.getColor(context, R.color.highlight)
        } else {
            ContextCompat.getColor(context, R.color.card_background)
        }
        (holder.itemView as CardView).setCardBackgroundColor(cardColor)
        holder.thumbnailFrame.setBackgroundColor(
            ContextCompat.getColor(context, R.color.card_image_background)
        )

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
        holder.profileBadge.visibility =
            if (item.profileKey in savedProfiles) View.VISIBLE else View.GONE

        // ── "Imported" badge ─────────────────────────────────────────────────
        holder.importedBadge.visibility =
            if (!item.isAsset) View.VISIBLE else View.GONE

        // ── Three-dots menu ─────────────────────────────────────────────────
        holder.btnOptions.setOnClickListener { anchor ->
            val cached = File(context.filesDir, "thumbnails/${item.profileKey}.jpg")

// 1. Inflate your beautiful custom container
            val layoutInflater = LayoutInflater.from(context)
            val popupView = layoutInflater.inflate(R.layout.dialog_popup, null)
            val container = popupView.findViewById<LinearLayout>(R.id.menuItemsContainer)

// 2. Initialize the PopupWindow wrapper
            val popupWindow = PopupWindow(
                popupView,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                true // Lets it close if the user clicks outside
            ).apply {
                elevation = 10f // Matches the card depth feel
            }

            // Helper function to build rows dynamically based on your logic
            fun addCustomMenuItem(title: String, onClick: () -> Unit) {
                val itemView = layoutInflater.inflate(R.layout.item_popup_menu, container, false) as TextView
                itemView.text = title
                itemView.setOnClickListener {
                    onClick()
                    popupWindow.dismiss() // Close menu on click
                }
                container.addView(itemView)
            }

// 3. Build your conditional list using your exact business logic
            addCustomMenuItem(context.getString(R.string.menu_preview)) {
                onPreviewClick(item)
            }

            if (cached.exists()) {
                addCustomMenuItem(context.getString(R.string.menu_delete_thumbnail)) {
                    deleteThumbnail(cached, item, holder)
                }
            }

            if (!item.isAsset) {
                addCustomMenuItem(context.getString(R.string.menu_rename)) {
                    onRenameImported(item)
                }
            }


            if (item.profileKey in savedProfiles) {
                addCustomMenuItem(context.getString(R.string.menu_export_profile)) {
                    onExportWithProfile(item)
                }
            }

            if (!item.isAsset) {
                addCustomMenuItem(context.getString(R.string.menu_delete_model)) {
                    confirmDeleteModel(context, item)
                }
            }

// 4. Show it anchored right next to your button/view
            popupWindow.showAsDropDown(anchor, 0, 4) // X and Y offsets to clear margins cleanly
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
        // defaultSizeM is always in metres (readBounds normalises to m)
        val wM = if (profile != null) baseW * profile.scaleX else baseW
        val hM = if (profile != null) baseH * profile.scaleY else baseH
        val dM = if (profile != null) baseD * profile.scaleZ else baseD

        fun fmt(v: Float) = if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v)

        // Pick display unit based on the largest dimension:
        //   ≥ 1 m  → display in metres
        //   ≥ 0.1 m (10 cm) → display in centimetres
        //   < 0.1 m  → display in millimetres
        val maxM = maxOf(wM, hM, dM)
        return when {
            maxM >= 1f -> {
                "${fmt(wM)} × ${fmt(hM)} × ${fmt(dM)} m"
            }
            maxM >= 0.1f -> {
                "${fmt(wM * 100f)} × ${fmt(hM * 100f)} × ${fmt(dM * 100f)} cm"
            }
            else -> {
                "${fmt(wM * 1000f)} × ${fmt(hM * 1000f)} × ${fmt(dM * 1000f)} mm"
            }
        }
    }

    // ── Thumbnail helpers ─────────────────────────────────────────────────────

    private fun bindThumbnail(imageView: ImageView, item: ModelItem, filesDir: File) {
        imageView.setImageDrawable(null)
        val cached = File(filesDir, "thumbnails/${item.profileKey}.jpg")
        val bmp    = if (cached.exists()) BitmapFactory.decodeFile(cached.absolutePath) else null
        when {
            bmp != null               -> imageView.setImageBitmap(bmp)
            item.isAsset -> {
                val context = imageView.context
                val assetThumbnailPath = "thumbnails/${item.name}.jpg"
                try {
                    context.assets.open(assetThumbnailPath).use { inputStream ->
                        val assetBmp = BitmapFactory.decodeStream(inputStream)
                        imageView.setImageBitmap(assetBmp)
                    }
                } catch (e: Exception) {
                    if (item.thumbnailRes != null) {
                        imageView.setImageResource(item.thumbnailRes)
                    } else {
                        imageView.setImageResource(R.drawable.ic_model_placeholder)
                    }
                }
            }
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


}