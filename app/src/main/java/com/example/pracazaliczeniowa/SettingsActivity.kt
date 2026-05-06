package com.example.pracazaliczeniowa

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.pracazaliczeniowa.Helpers.AppSettings

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

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
        // ── Language ─────────────────────────────────────────────────────────────
        val languageCodes = listOf("", "en", "pl")   // matches the string-array order
        val spinnerLanguage = findViewById<android.widget.Spinner>(R.id.spinnerLanguage)

        val adapter = android.widget.ArrayAdapter.createFromResource(
            this,
            R.array.language_display_names,
            android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spinnerLanguage.adapter = adapter
        spinnerLanguage.setSelection(languageCodes.indexOf(settings.languageOverride).coerceAtLeast(0))

        spinnerLanguage.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, pos: Int, id: Long) {
                val newCode = languageCodes[pos]
                if (newCode == settings.languageOverride) return   // no change, avoid recreate loop
                settings.languageOverride = newCode
                settings.applyLocale(this@SettingsActivity)
                recreate()   // redraw the settings screen in the new language
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        // ── Position half-range ──────────────────────────────────────────
        val posLabel = findViewById<TextView>(R.id.tvPosMidValue)
        val posMinus = findViewById<Button>(R.id.btnPosMidMinus)
        val posPlus  = findViewById<Button>(R.id.btnPosMidPlus)
        val posEdit  = findViewById<EditText>(R.id.etPosMid)

        fun refreshPosLabel(v: Int) { posLabel.text = "±$v cm" }

        posEdit.setText(settings.posMidDefault.toString())
        refreshPosLabel(settings.posMidDefault)

        posEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toIntOrNull() ?: return
                settings.posMidDefault = v
                refreshPosLabel(settings.posMidDefault)
            }
        })

        posMinus.setOnClickListener {
            val step = if (settings.posMidDefault > 100) 100 else 10
            settings.posMidDefault -= step
            posEdit.setText(settings.posMidDefault.toString())
            refreshPosLabel(settings.posMidDefault)
        }

        posPlus.setOnClickListener {
            val step = if (settings.posMidDefault >= 100) 100 else 10
            settings.posMidDefault += step
            posEdit.setText(settings.posMidDefault.toString())
            refreshPosLabel(settings.posMidDefault)
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