package com.example.WymiAR.Objects

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class Theme { DARK, LIGHT, SYSTEM }

    var themeMode: Theme
        get() = when (prefs.getString(KEY_THEME, "dark")) {
            "light"  -> Theme.LIGHT
            "system" -> Theme.SYSTEM
            else     -> Theme.DARK
        }
        set(value) {
            val tag = when (value) {
                Theme.DARK   -> "dark"
                Theme.LIGHT  -> "light"
                Theme.SYSTEM -> "system"
            }
            prefs.edit().putString(KEY_THEME, tag).apply()
        }

    fun applyTheme() {
        val mode = when (themeMode) {
            Theme.DARK   -> AppCompatDelegate.MODE_NIGHT_YES
            Theme.LIGHT  -> AppCompatDelegate.MODE_NIGHT_NO
            Theme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun applyLocale(context: Context) {
        val tag = languageOverride
        val locale = if (tag.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locale)
    }

    var distanceUnit: DistanceUnit
        get() = when (prefs.getString(KEY_DISTANCE_UNIT, "cm")) {
            "m"  -> DistanceUnit.METERS
            "mm" -> DistanceUnit.MILLIMETERS
            else -> DistanceUnit.CENTIMETERS
        }
        set(value) {
            val tag = when (value) {
                DistanceUnit.METERS      -> "m"
                DistanceUnit.CENTIMETERS -> "cm"
                DistanceUnit.MILLIMETERS -> "mm"
            }
            prefs.edit().putString(KEY_DISTANCE_UNIT, tag).apply()
        }


    var posMidDefault: Int
        get() = prefs.getInt(KEY_POS_MID, 100).coerceIn(1, 1_000)
        set(value) { prefs.edit().putInt(KEY_POS_MID, value.coerceIn(1, 1_000)).apply() }


    fun posMidInCurrentUnit(): Float = when (distanceUnit) {
        DistanceUnit.METERS      -> posMidDefault / 100f
        DistanceUnit.CENTIMETERS -> posMidDefault.toFloat()
        DistanceUnit.MILLIMETERS -> posMidDefault * 10f
    }


    var sclMaxDefault: Int
        get() = prefs.getInt(KEY_SCL_MAX, 500).coerceIn(100, 10_000)
        set(value) { prefs.edit().putInt(KEY_SCL_MAX, value.coerceIn(100, 10_000)).apply() }


    var languageOverride: String
        get() = prefs.getString(KEY_LANGUAGE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LANGUAGE, value).apply() }

    companion object {
        private const val PREFS_NAME  = "app_settings"
        private const val KEY_THEME = "theme_mode"

        private const val KEY_DISTANCE_UNIT = "distance_unit"
        private const val KEY_POS_MID   = "pos_mid_default"
        private const val KEY_SCL_MAX   = "scl_max_default"
        private const val KEY_LANGUAGE = "language_override"
    }
}