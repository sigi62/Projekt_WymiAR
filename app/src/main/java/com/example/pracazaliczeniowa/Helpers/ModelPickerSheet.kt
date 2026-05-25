package com.example.pracazaliczeniowa.Helpers

import android.content.Context
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.pracazaliczeniowa.Managers.ModelImportManager
import com.example.pracazaliczeniowa.Objects.ModelItem
import com.example.pracazaliczeniowa.R
import java.io.File

class ModelPickerPopup(private val context: Context) {

    var onModelPicked: ((ModelItem) -> Unit)? = null
    private var popupWindow: PopupWindow? = null

    private fun getAllAvailableModels(): List<ModelItem> {
        val bundled = try {
            (context.assets.list("models") ?: emptyArray())
                .filter { it.endsWith(".glb", ignoreCase = true) }
                .map { ModelImportManager.bundledItem(context, "models/$it") }
        } catch (e: Exception) {
            emptyList()
        }

        val imported = ModelImportManager.loadImported(context)

        return bundled + imported
    }

    fun show(anchorView: View, activePath: String) {
        if (popupWindow?.isShowing == true) {
            popupWindow?.dismiss()
            return
        }

        val items = getAllAvailableModels()
        val inflater = LayoutInflater.from(context)
        val contentView = inflater.inflate(R.layout.model_picker_sheet, null, false)

        val container = contentView.findViewById<LinearLayout>(R.id.pickerContainer)
        val scrollView = contentView.findViewById<ScrollView>(R.id.pickerScroll)

        val screenHeight = context.resources.displayMetrics.heightPixels
        val maxHeightPx = (screenHeight * 0.40).toInt()

        scrollView.post {
            if (scrollView.height > maxHeightPx) {
                val params = scrollView.layoutParams
                params.height = maxHeightPx
                scrollView.layoutParams = params
            }
        }

        items.forEach { item ->
            val row = inflater.inflate(R.layout.item_picker_card, container, false)

            val thumb = row.findViewById<ImageView>(R.id.pickerThumb)
            val label = row.findViewById<TextView>(R.id.pickerLabel)

            if (item.modelPath == activePath) {
                row.setBackgroundColor(ContextCompat.getColor(context, R.color.highlight))
            }

            val cached = File(context.filesDir, "thumbnails/${item.modelId}.jpg")
            val bmp = if (cached.exists()) BitmapFactory.decodeFile(cached.absolutePath) else null

            when {
                bmp != null -> thumb.setImageBitmap(bmp)
                item.thumbnailRes != null -> thumb.setImageResource(item.thumbnailRes)
                else -> thumb.setImageResource(R.drawable.ic_model_placeholder)
            }

            label.text = item.name

            row.setOnClickListener {
                onModelPicked?.invoke(item)
                popupWindow?.dismiss()
            }

            container.addView(row)
        }

        val widthPx = (100 * context.resources.displayMetrics.density).toInt()

        popupWindow = PopupWindow(
            contentView,
            widthPx,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 12f
            isOutsideTouchable = true
            showAsDropDown(anchorView, 0, 8)
        }
    }
}