package com.example.WymiAR.Activities.SettingScreens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.WymiAR.Helpers.RulerSeekBar
import com.example.WymiAR.R
import java.io.File

class StorageActivity : AppCompatActivity() {

    private val modelDir: File by lazy { File(filesDir, "models") }

    // ── view refs ────────────────────────────────────────────────────────
    private lateinit var tvTotalSize: TextView
    private lateinit var tvModelCount: TextView
    private lateinit var progressStorage: ProgressBar
    private lateinit var containerModels: LinearLayout
    private lateinit var sliderQuota: RulerSeekBar
    private val QUOTA_MIN_MB  = 10f
    private val QUOTA_DEF_MB  = 100f

    private var quotaBytes: Long = QUOTA_DEF_MB.toLong() * 1024 * 1024

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage)

        tvTotalSize     = findViewById(R.id.tvTotalSize)
        tvModelCount    = findViewById(R.id.tvModelCount)
        progressStorage = findViewById(R.id.progressStorage)
        containerModels = findViewById(R.id.containerModels)
        sliderQuota     = findViewById(R.id.sliderQuota)
        sliderQuota.vertical = true

        sliderQuota.updateRange(
            min    = 0f,
            max    = 200f,
            center = 100f,
            major  = 100f,
            minor  = 50f
        )
        sliderQuota.decimalPlaces = 0
        sliderQuota.setStepsFromRange()
        sliderQuota.progress = QUOTA_DEF_MB.toInt()

        sliderQuota.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val rawMb = progress.toFloat().coerceAtLeast(QUOTA_MIN_MB)
                val snapped = snapToStep(rawMb)
                val snappedProgress = snapped.toInt()
                if (progress != snappedProgress) {
                    seekBar.progress = snappedProgress
                    return
                }
                quotaBytes = snapped.toLong() * 1024 * 1024
                updateProgressRing()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {
                val rawMb = seekBar.progress.toFloat().coerceAtLeast(QUOTA_MIN_MB)
                val snapped = snapToStep(rawMb)
                seekBar.progress = snapped.toInt()
                quotaBytes = snapped.toLong() * 1024 * 1024
                updateProgressRing()
            }
        })

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<ImageButton>(R.id.btnDeleteAll).setOnClickListener {
            confirmClearAll()
        }

        findViewById<View>(R.id.btnClearStorage).setOnClickListener {
            confirmClearAll()
        }

        refresh()
    }

    private val QUOTA_STEPS_MB = listOf(10f, 50f, 100f, 150f, 200f)

    private fun snapToStep(mb: Float): Float =
        QUOTA_STEPS_MB.minByOrNull { kotlin.math.abs(it - mb) } ?: mb

    private fun refresh() {
        val models = loadModelEntries()

        val totalBytes = models.sumOf { it.sizeBytes }
        tvTotalSize.text  = formatSize(totalBytes)
        tvModelCount.text = resources.getQuantityString(
            R.plurals.downloaded_models_count, models.size, models.size
        )

        updateProgressRing(totalBytes)

        containerModels.removeAllViews()
        if (models.isEmpty()) {
            val empty = TextView(this).apply {
                text     = getString(R.string.no_cached_models)
                textSize = 14f
                setPadding(0, 24, 0, 0)
                setTextColor(getColor(R.color.text_secondary))
            }
            containerModels.addView(empty)
        } else {
            val inflater = LayoutInflater.from(this)
            models.forEachIndexed { index, entry ->
                val row = inflater.inflate(R.layout.item_cached_model, containerModels, false)

                row.findViewById<TextView>(R.id.tvModelName).text = entry.name
                row.findViewById<TextView>(R.id.tvModelMeta).text =
                    getString(R.string.model_meta_format, formatSize(entry.sizeBytes), entry.ageLabel)

                row.findViewById<ImageButton>(R.id.btnDeleteModel).setOnClickListener {
                    confirmDeleteSingle(entry)
                }

                containerModels.addView(row)

                if (index < models.size - 1) {
                    val divider = View(this)
                    divider.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    divider.setBackgroundColor(getColor(R.color.screen_background))
                    containerModels.addView(divider)
                }
            }
        }
    }

    private fun updateProgressRing(totalBytes: Long = loadModelEntries().sumOf { it.sizeBytes }) {
        progressStorage.progress =
            ((totalBytes.toFloat() / quotaBytes) * 100).toInt().coerceIn(0, 100)
    }

    // ── Dialogs ──────────────────────────────────────────────────────────

    private fun confirmClearAll() {
        val view = layoutInflater.inflate(R.layout.dialog_confirm, null)
        view.findViewById<TextView>(R.id.tvDialogTitle).text = getString(R.string.clear_cache)
        view.findViewById<TextView>(R.id.tvDialogMessage).text = getString(R.string.clear_cache_confirm_message)
        view.findViewById<Button>(R.id.btnConfirm).text = getString(R.string.clear)
        view.findViewById<Button>(R.id.btnCancel).text = getString(R.string.cancel)

        val dialog = android.app.Dialog(this).apply { setContentView(view) }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            modelDir.listFiles()?.forEach { it.deleteRecursively() }
            setResult(RESULT_OK)
            Toast.makeText(this, getString(R.string.remove_all_models_toast), Toast.LENGTH_SHORT).show()
            refresh()
        }

        dialog.show()
    }

    private fun confirmDeleteSingle(entry: ModelEntry) {
        val view = layoutInflater.inflate(R.layout.dialog_confirm, null)
        view.findViewById<TextView>(R.id.tvDialogTitle).text = entry.name
        view.findViewById<TextView>(R.id.tvDialogMessage).text =
            getString(R.string.delete_model_confirm_message, entry.name)
        view.findViewById<Button>(R.id.btnConfirm).text = getString(R.string.delete)
        view.findViewById<Button>(R.id.btnCancel).text = getString(R.string.cancel)

        val dialog = android.app.Dialog(this).apply { setContentView(view) }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            entry.file.deleteRecursively()
            setResult(RESULT_OK)
            Toast.makeText(this, getString(R.string.status_model_deleted, entry.name), Toast.LENGTH_SHORT).show()
            refresh()
        }

        dialog.show()
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