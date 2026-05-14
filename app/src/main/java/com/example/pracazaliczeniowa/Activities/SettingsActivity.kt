package com.example.pracazaliczeniowa.Activities

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.pracazaliczeniowa.Objects.AppSettings
import com.example.pracazaliczeniowa.Objects.DistanceUnit
import com.example.pracazaliczeniowa.R

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

    private var refreshPosLabel: () -> Unit = {}
    private var refreshPosEdit:  () -> Unit = {}


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = AppSettings(this)

        supportActionBar?.apply {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
        }

        // ── Dark / Light mode toggle ─────────────────────────────────────
        val themeSwitch = findViewById<Switch>(R.id.switchDarkMode)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        themeSwitch.isChecked = settings.isDarkMode

        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.isDarkMode = isChecked
            // Apply the new mode process-wide, then recreate this activity
            // so its own layout redraws with the correct @color/ night/day values.
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES
                else           AppCompatDelegate.MODE_NIGHT_NO
            )
            recreate()
        }


        // ── Distance unit ────────────────────────────────────────────────────
        val unitCodes   = listOf(
            DistanceUnit.CENTIMETERS,
            DistanceUnit.METERS,
            DistanceUnit.MILLIMETERS
        )
        val spinnerUnit = findViewById<Spinner>(R.id.spinnerUnit)

        val unitAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.unit_display_names,
            android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spinnerUnit.adapter = unitAdapter
        spinnerUnit.setSelection(unitCodes.indexOf(settings.distanceUnit).coerceAtLeast(0))

        spinnerUnit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val newUnit = unitCodes[pos]
                if (newUnit == settings.distanceUnit) return
                settings.distanceUnit = newUnit
                refreshPosLabel()
                refreshPosEdit()
                // No recreate needed — the overlay picks it up in onResume via applySettings
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // ── Language ─────────────────────────────────────────────────────────────
        val languageCodes = listOf("", "en", "pl")   // matches the string-array order
        val spinnerLanguage = findViewById<Spinner>(R.id.spinnerLanguage)

        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.language_display_names,
            android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spinnerLanguage.adapter = adapter
        spinnerLanguage.setSelection(languageCodes.indexOf(settings.languageOverride).coerceAtLeast(0))

        spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val newCode = languageCodes[pos]
                if (newCode == settings.languageOverride) return   // no change, avoid recreate loop
                settings.languageOverride = newCode
                settings.applyLocale(this@SettingsActivity)
                recreate()   // redraw the settings screen in the new language
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // ── Position half-range ──────────────────────────────────────────
        val posLabel = findViewById<TextView>(R.id.tvPosMidValue)
        val posMinus = findViewById<Button>(R.id.btnPosMidMinus)
        val posPlus  = findViewById<Button>(R.id.btnPosMidPlus)
        val posEdit  = findViewById<EditText>(R.id.etPosMid)

        refreshPosLabel = {
            val value = settings.posMidInCurrentUnit()
            val suffix = when (settings.distanceUnit) {
                DistanceUnit.METERS      -> "m"
                DistanceUnit.CENTIMETERS -> "cm"
                DistanceUnit.MILLIMETERS -> "mm"
            }
            val formatted = if (settings.distanceUnit == DistanceUnit.METERS)
                "±%.2f %s".format(value, suffix)
            else
                "±%.0f %s".format(value, suffix)
            posLabel.text = formatted
        }

        refreshPosEdit = {
            val value = settings.posMidInCurrentUnit()
            posEdit.setText(
                if (settings.distanceUnit == DistanceUnit.METERS)
                    "%.2f".format(value)
                else
                    "%.0f".format(value)
            )
        }

        posEdit.setText(/* remove old line, use: */ "".also { refreshPosEdit() })
        refreshPosLabel()
        refreshPosEdit()

        posEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toFloatOrNull() ?: return
                settings.setPosMidFromCurrentUnit(v)
                refreshPosLabel()
            }
        })

        posMinus.setOnClickListener {
            val step = when (settings.distanceUnit) {
                DistanceUnit.METERS      -> if (settings.posMidDefault > 100) 100 else 10
                DistanceUnit.CENTIMETERS -> if (settings.posMidDefault > 100) 100 else 10
                DistanceUnit.MILLIMETERS -> if (settings.posMidDefault > 100) 100 else 10
            }
            settings.posMidDefault = (settings.posMidDefault - step).coerceAtLeast(1)
            refreshPosEdit()
            refreshPosLabel()
        }

        posPlus.setOnClickListener {
            val step = when (settings.distanceUnit) {
                DistanceUnit.METERS      -> if (settings.posMidDefault >= 100) 100 else 10
                DistanceUnit.CENTIMETERS -> if (settings.posMidDefault >= 100) 100 else 10
                DistanceUnit.MILLIMETERS -> if (settings.posMidDefault >= 100) 100 else 10
            }
            settings.posMidDefault = (settings.posMidDefault + step).coerceAtMost(1_000)
            refreshPosEdit()
            refreshPosLabel()
        }

        // ── Scale max ────────────────────────────────────────────────────
        val sclLabel = findViewById<TextView>(R.id.tvSclMaxValue)
        val sclMinus = findViewById<Button>(R.id.btnSclMaxMinus)
        val sclPlus  = findViewById<Button>(R.id.btnSclMaxPlus)
        val sclEdit  = findViewById<EditText>(R.id.etSclMax)

        fun refreshSclLabel(v: Int) { sclLabel.text = String.format("%.2f×", v / 100f) }

        sclEdit.setText(settings.sclMaxDefault.toString())
        refreshSclLabel(settings.sclMaxDefault)

        sclEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toIntOrNull() ?: return
                settings.sclMaxDefault = v
                refreshSclLabel(settings.sclMaxDefault)
            }
        })

        sclMinus.setOnClickListener {
            val step = if (settings.sclMaxDefault > 500) 500 else 100
            settings.sclMaxDefault -= step
            sclEdit.setText(settings.sclMaxDefault.toString())
            refreshSclLabel(settings.sclMaxDefault)
        }

        sclPlus.setOnClickListener {
            val step = if (settings.sclMaxDefault >= 500) 500 else 100
            settings.sclMaxDefault += step
            sclEdit.setText(settings.sclMaxDefault.toString())
            refreshSclLabel(settings.sclMaxDefault)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}