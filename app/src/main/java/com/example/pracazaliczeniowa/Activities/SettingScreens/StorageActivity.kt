package com.example.pracazaliczeniowa.Activities.SettingScreens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.pracazaliczeniowa.R
import java.io.File

/**
 * Displays total storage consumed by cached 3-D model files,
 * lets the user delete individual models, or wipe everything at once.
 *
 * Models are stored under:  context.filesDir/models/
 * Each model is a single directory named after the model (e.g. "Cat/").
 * Adjust MODEL_DIR if your project uses a different path.
 */
class StorageActivity : AppCompatActivity() {

    // ── where model folders live ──────────────────────────────────────────
    private val modelDir: File by lazy { File(filesDir, "models") }

    // ── view refs ────────────────────────────────────────────────────────
    private lateinit var tvTotalSize: TextView
    private lateinit var tvModelCount: TextView
    private lateinit var progressStorage: ProgressBar
    private lateinit var containerModels: LinearLayout

    // assumed quota used for the ring progress (100 MB → 100 %)
    private val QUOTA_BYTES = 100L * 1024 * 1024

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage)

        tvTotalSize      = findViewById(R.id.tvTotalSize)
        tvModelCount     = findViewById(R.id.tvModelCount)
        progressStorage  = findViewById(R.id.progressStorage)
        containerModels  = findViewById(R.id.containerModels)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // "Delete all" icon in the toolbar
        findViewById<ImageButton>(R.id.btnDeleteAll).setOnClickListener {
            confirmClearAll()
        }

        // "Clear Storage" button in the body
        findViewById<View>(R.id.btnClearStorage).setOnClickListener {
            confirmClearAll()
        }

        refresh()
    }

    // ── Refresh the whole UI ─────────────────────────────────────────────

    private fun refresh() {
        val models = loadModelEntries()

        // total bytes
        val totalBytes = models.sumOf { it.sizeBytes }
        tvTotalSize.text  = formatSize(totalBytes)
        tvModelCount.text = resources.getQuantityString(
            R.plurals.downloaded_models_count, models.size, models.size
        )

        // ring progress (capped at 100)
        progressStorage.progress =
            ((totalBytes.toFloat() / QUOTA_BYTES) * 100).toInt().coerceIn(0, 100)

        // rebuild the model list
        containerModels.removeAllViews()
        if (models.isEmpty()) {
            val empty = TextView(this).apply {
                text    = getString(R.string.no_cached_models)
                textSize = 14f
                setPadding(0, 24, 0, 0)
                setTextColor(getColor(R.color.text_secondary))
            }
            containerModels.addView(empty)
        } else {
            val inflater = LayoutInflater.from(this)
            models.forEachIndexed { index, entry ->
                val row = inflater.inflate(R.layout.item_cached_model, containerModels, false)

                row.findViewById<TextView>(R.id.tvModelName).text  = entry.name
                row.findViewById<TextView>(R.id.tvModelMeta).text  =
                    getString(R.string.model_meta_format, formatSize(entry.sizeBytes), entry.ageLabel)

                row.findViewById<ImageButton>(R.id.btnDeleteModel).setOnClickListener {
                    confirmDeleteSingle(entry)
                }

                containerModels.addView(row)

                // hairline divider between rows (not after the last one)
                if (index < models.size - 1) {
                    val divider = View(this)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    divider.layoutParams = lp
                    divider.setBackgroundColor(getColor(R.color.screen_background))
                    containerModels.addView(divider)
                }
            }
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_cache))
            .setMessage(getString(R.string.clear_cache_confirm_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.clear)) { _, _ ->
                modelDir.listFiles()?.forEach { it.deleteRecursively() }
                setResult(RESULT_OK)
                Toast.makeText(this, getString(R.string.remove_all_models_toast), Toast.LENGTH_SHORT).show()
                refresh()
            }
            .show()
    }

    private fun confirmDeleteSingle(entry: ModelEntry) {
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage(getString(R.string.delete_model_confirm_message, entry.name))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                entry.file.deleteRecursively()
                setResult(RESULT_OK)
                Toast.makeText(this, getString(R.string.status_model_deleted,entry.name), Toast.LENGTH_SHORT).show()
                refresh()
            }
            .show()
    }

    // ── Data helpers ─────────────────────────────────────────────────────

    private data class ModelEntry(
        val name: String,
        val file: File,
        val sizeBytes: Long,
        val ageLabel: String
    )

    private fun loadModelEntries(): List<ModelEntry> {
        if (!modelDir.exists()) return emptyList()
        val now = System.currentTimeMillis()
        return modelDir.listFiles()
            ?.filter { it.isDirectory || it.isFile }
            ?.map { f ->
                ModelEntry(
                    name      = f.nameWithoutExtension.replaceFirstChar { it.uppercase() },
                    file      = f,
                    sizeBytes = f.walkTopDown().sumOf { it.length() },
                    ageLabel  = formatAge(now - f.lastModified())
                )
            }
            ?.sortedByDescending { it.file.lastModified() }
            ?: emptyList()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000     -> "%.1f KB".format(bytes / 1_000.0)
        else               -> "$bytes B"
    }

    private fun formatAge(deltaMs: Long): String {
        val minutes = deltaMs / 60_000
        val hours   = minutes / 60
        val days    = hours / 24
        return when {
            days    > 0 -> resources.getQuantityString(R.plurals.days_ago,    days.toInt(),    days.toInt())
            hours   > 0 -> resources.getQuantityString(R.plurals.hours_ago,   hours.toInt(),   hours.toInt())
            minutes > 0 -> resources.getQuantityString(R.plurals.minutes_ago, minutes.toInt(), minutes.toInt())
            else        -> getString(R.string.just_now)
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}