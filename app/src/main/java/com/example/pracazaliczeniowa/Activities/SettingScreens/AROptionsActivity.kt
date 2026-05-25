package com.example.pracazaliczeniowa.Activities.SettingScreens

import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.pracazaliczeniowa.Objects.AppSettings
import com.example.pracazaliczeniowa.Objects.DistanceUnit
import com.example.pracazaliczeniowa.R

class AROptionsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_options)

        settings = AppSettings(this)

        // ── Back button ──────────────────────────────────────────────────
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // ── Position half-range ──────────────────────────────────────────
        val posLabel = findViewById<TextView>(R.id.tvPosMidValue)
        val posMinus = findViewById<Button>(R.id.btnPosMidMinus)
        val posPlus  = findViewById<Button>(R.id.btnPosMidPlus)

        fun refreshPosLabel() {
            val value  = settings.posMidInCurrentUnit()
            val suffix = when (settings.distanceUnit) {
                DistanceUnit.METERS      -> "m"
                DistanceUnit.CENTIMETERS -> "cm"
                DistanceUnit.MILLIMETERS -> "mm"
            }
            posLabel.text = if (settings.distanceUnit == DistanceUnit.METERS)
                "±%.2f %s".format(value, suffix)
            else
                "±%.0f %s".format(value, suffix)
        }

        refreshPosLabel()

        posMinus.setOnClickListener {
            val step = if (settings.posMidDefault > 100) 100 else 10
            settings.posMidDefault = (settings.posMidDefault - step).coerceAtLeast(1)
            refreshPosLabel()
        }

        posPlus.setOnClickListener {
            val step = if (settings.posMidDefault >= 100) 100 else 10
            settings.posMidDefault = (settings.posMidDefault + step).coerceAtMost(1_000)
            refreshPosLabel()
        }

        // ── Scale max ────────────────────────────────────────────────────
        val sclLabel = findViewById<TextView>(R.id.tvSclMaxValue)
        val sclMinus = findViewById<Button>(R.id.btnSclMaxMinus)
        val sclPlus  = findViewById<Button>(R.id.btnSclMaxPlus)

        fun refreshSclLabel() {
            sclLabel.text = "%.2f×".format(settings.sclMaxDefault / 100f)
        }

        refreshSclLabel()

        sclMinus.setOnClickListener {
            val step = if (settings.sclMaxDefault > 500) 500 else 100
            settings.sclMaxDefault = (settings.sclMaxDefault - step).coerceAtLeast(100)
            refreshSclLabel()
        }

        sclPlus.setOnClickListener {
            val step = if (settings.sclMaxDefault >= 500) 500 else 100
            settings.sclMaxDefault = (settings.sclMaxDefault + step).coerceAtMost(10_000)
            refreshSclLabel()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}