package com.example.pracazaliczeniowa.Activities

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.pracazaliczeniowa.Activities.SettingScreens.AROptionsActivity
import com.example.pracazaliczeniowa.Activities.SettingScreens.StorageActivity
import com.example.pracazaliczeniowa.Objects.AppSettings
import com.example.pracazaliczeniowa.Objects.DistanceUnit
import com.example.pracazaliczeniowa.R
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private val modelDir: File by lazy { File(filesDir, "models") }

    private val storageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateStorageSummary()
        setResult(RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = AppSettings(this)

        supportActionBar?.apply {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
        }

        // ── Back button ──────────────────────────────────────────────────
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // ── Appearance row → Theme popup ─────────────────────────────────
        findViewById<LinearLayout>(R.id.rowAppAppearance).setOnClickListener {
            showThemeDialog()
        }

        // ── AR Options rows → AROptionsActivity ──────────────────────────
        findViewById<LinearLayout>(R.id.rowControlsRange).setOnClickListener {
            startActivity(Intent(this, AROptionsActivity::class.java))
        }

        // ── Measurement Unit row → Unit popup ────────────────────────────
        findViewById<LinearLayout>(R.id.rowARMeasurementUnit).setOnClickListener {
            showUnitDialog()
        }

        // ── Storage rows ─────────────────────────────────────────────────
        updateStorageSummary()

        findViewById<LinearLayout>(R.id.rowManageStorage).setOnClickListener {
            storageLauncher.launch(Intent(this, StorageActivity::class.java))  // CHANGED from startActivity(...)
        }

        findViewById<LinearLayout>(R.id.rowClearCache).setOnClickListener {
            confirmClearAll()
        }

        // ── Language row → Language popup ────────────────────────────────
        findViewById<LinearLayout>(R.id.rowAccesibility).setOnClickListener {
            showLanguageDialog()
        }
    }

    // ── Called when returning from any sub-screen ─────────────────────────
    override fun onResume() {
        super.onResume()
        updateStorageSummary()
        updateAppearanceSummary()
        updateAROptionsSummary()
        updateAccessibilitySummary()
    }

    // ── Popup dialogs ─────────────────────────────────────────────────────

    /** Radio popup: Dark / Light / System */
    private fun showThemeDialog() {
        val options = listOf(
            getString(R.string.theme_dark),
            getString(R.string.theme_light),
            getString(R.string.theme_system)
        )
        val currentIndex = when (settings.themeMode) {
            AppSettings.Theme.DARK   -> 0
            AppSettings.Theme.LIGHT  -> 1
            AppSettings.Theme.SYSTEM -> 2
        }
        showRadioDialog(
            title    = getString(R.string.app_theme),
            options  = options,
            selected = currentIndex
        ) { chosenIndex ->
            settings.themeMode = when (chosenIndex) {
                0    -> AppSettings.Theme.DARK
                1    -> AppSettings.Theme.LIGHT
                else -> AppSettings.Theme.SYSTEM
            }
            settings.applyTheme()
            updateAppearanceSummary()
        }
    }

    /** Radio popup: Centimeters / Meters / Millimeters */
    private fun showUnitDialog() {
        val options = listOf(
            getString(R.string.full_unit_mm),
            getString(R.string.full_unit_cm),
            getString(R.string.full_unit_m)
        )
        val currentIndex = when (settings.distanceUnit) {
            DistanceUnit.MILLIMETERS -> 0
            DistanceUnit.CENTIMETERS -> 1
            DistanceUnit.METERS      -> 2
        }
        showRadioDialog(
            title    = getString(R.string.unit_label),
            options  = options,
            selected = currentIndex,
        ) { chosenIndex ->
            settings.distanceUnit = when (chosenIndex) {
                0 -> DistanceUnit.MILLIMETERS
                1 -> DistanceUnit.CENTIMETERS
                else -> DistanceUnit.METERS
            }
            updateAROptionsSummary()
        }
    }

    /** Radio popup: System default / English / Polish */
    private fun showLanguageDialog() {
        val options = listOf(
            getString(R.string.language_system),
            getString(R.string.language_english),
            getString(R.string.language_polish)
        )
        val currentIndex = when (settings.languageOverride) {
            "en" -> 1
            "pl" -> 2
            else -> 0
        }
        showRadioDialog(
            title    = getString(R.string.language),
            options  = options,
            selected = currentIndex
        ) { chosenIndex ->
            settings.languageOverride = when (chosenIndex) {
                1 -> "en"
                2 -> "pl"
                else -> ""
            }
            updateAccessibilitySummary()
        }
    }

    /**
     * Generic single-choice radio dialog.
     *
     * @param title    Dialog title.
     * @param options  List of labels for radio buttons.
     * @param selected Index of the currently active option.
     * @param onPick   Called with the chosen index when the user taps an option.
     */
    private fun showRadioDialog(
        title: String,
        options: List<String>,
        selected: Int,
        onPick: (Int) -> Unit
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_radio, null)

        view.findViewById<TextView>(R.id.tvRadioTitle).text = title

        val radioGroup = view.findViewById<RadioGroup>(R.id.radioGroup)
        val vertPx = (10 * resources.displayMetrics.density).toInt()

        options.forEachIndexed { index, label ->
            RadioButton(this).apply {
                text = label
                id = index
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
                isChecked = (index == selected)
                buttonTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(
                        getColor(R.color.color_secondary),  // checked color
                        getColor(R.color.text_secondary)  // unchecked color
                    )
                )
                setPadding(paddingLeft, vertPx, paddingRight, vertPx)
                radioGroup.addView(this)
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<Button>(R.id.btnRadioCancel).setOnClickListener {
            dialog.dismiss()
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            onPick(checkedId)
            dialog.dismiss()
        }

        dialog.show()
    }
    // ── Summary helpers ───────────────────────────────────────────────────

    /** "Dark" / "Light" / "System" under the Appearance row. */
    private fun updateAppearanceSummary() {
        val tv = findViewById<TextView>(R.id.tvAppearanceSummary) ?: return
        tv.text = when (settings.themeMode) {
            AppSettings.Theme.DARK   -> getString(R.string.theme_dark)
            AppSettings.Theme.LIGHT  -> getString(R.string.theme_light)
            AppSettings.Theme.SYSTEM -> getString(R.string.theme_system)
        }
    }

    /**
     * "±100 cm  ·  5.00×" under the Controls Range row, and
     * "Centimeters (cm)" under the Measurement Unit row.
     */
    private fun updateAROptionsSummary() {
        val unitSuffix = when (settings.distanceUnit) {
            DistanceUnit.METERS      -> "m"
            DistanceUnit.CENTIMETERS -> "cm"
            DistanceUnit.MILLIMETERS -> "mm"
        }
        val posValue = settings.posMidInCurrentUnit()
        val posText = if (settings.distanceUnit == DistanceUnit.METERS)
            "±%.2f %s".format(posValue, unitSuffix)
        else
            "±%.0f %s".format(posValue, unitSuffix)
        val sclText = "%.2f×".format(settings.sclMaxDefault / 100f)

        // Controls Range row summary
        findViewById<TextView>(R.id.tvControlsRangeSummary)?.text =
            "$posText  ·  $sclText"

        // Measurement Unit row summary
        val unitLabel = when (settings.distanceUnit) {
            DistanceUnit.CENTIMETERS -> getString(R.string.full_unit_cm)
            DistanceUnit.METERS      -> getString(R.string.full_unit_m)
            DistanceUnit.MILLIMETERS -> getString(R.string.full_unit_mm)
        }
        findViewById<TextView>(R.id.tvMeasurementUnitSummary)?.text = unitLabel
    }

    /** Current language name under the Accessibility row. */
    private fun updateAccessibilitySummary() {
        val tv = findViewById<TextView>(R.id.tvAccessibilitySummary) ?: return
        tv.text = when (settings.languageOverride) {
            "en" -> getString(R.string.language_english)
            "pl" -> getString(R.string.language_polish)
            else -> getString(R.string.language_system)
        }
    }

    /** Live size + model count under "Manage Storage". */
    private fun updateStorageSummary() {
        val summaryView = findViewById<TextView>(R.id.tvStorageSummary) ?: return
        if (!modelDir.exists()) {
            summaryView.text = getString(R.string.storage_empty)
            return
        }
        val totalBytes = modelDir.walkTopDown().sumOf { it.length() }
        val modelCount = modelDir.listFiles()?.size ?: 0
        val sizeFmt = when {
            totalBytes >= 1_000_000 -> "%.1f MB".format(totalBytes / 1_000_000.0)
            totalBytes >= 1_000     -> "%.1f KB".format(totalBytes / 1_000.0)
            else                    -> "$totalBytes B"
        }
        summaryView.text = getString(R.string.storage_summary_format, sizeFmt, modelCount)
    }
    // New helper in SettingsActivity:
    private fun confirmClearAll() {
        val view = layoutInflater.inflate(R.layout.dialog_confirm, null)
        view.findViewById<TextView>(R.id.tvDialogTitle).text = getString(R.string.clear_cache)
        view.findViewById<TextView>(R.id.tvDialogMessage).text = getString(R.string.clear_cache_confirm_message)
        view.findViewById<Button>(R.id.btnConfirm).text = getString(R.string.clear)

        val dialog = android.app.Dialog(this).apply { setContentView(view) }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            modelDir.listFiles()?.forEach { it.deleteRecursively() }
            updateStorageSummary()
            Toast.makeText(this, getString(R.string.cache_cleared), Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            dialog.dismiss()
        }

        dialog.show()
    }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}