package com.example.pracazaliczeniowa.Objects

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Centralised access to all persisted app settings.
 *
 * Keys are stored in the "app_settings" SharedPreferences file so they
 * are isolated from any other prefs the app might use.
 *
 * Settings managed here:
 *  - Dark / light mode  (boolean, default = dark)
 *  - Default position half-range in cm  (int, default = 100 → ±100 cm)
 *  - Default scale max progress         (int, default = 500 → 5.00×)
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -------------------------------------------------------------------------
    // Theme  (Dark / Light / System)
    // -------------------------------------------------------------------------

    enum class Theme { DARK, LIGHT, SYSTEM }

    /**
     * Persisted as a string tag ("dark" | "light" | "system").
     * Default is DARK to preserve the original behaviour.
     */
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


    /**
     * Applies the stored theme preference to the whole process via
     * [AppCompatDelegate.setDefaultNightMode].
     * Call from Application.onCreate() and after the user picks a new theme.
     */
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
    // -------------------------------------------------------------------------
    // ModelControlOverlayView – position default half-range (cm)
    // -------------------------------------------------------------------------

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

    /**
     * Default half-range for the position seekbar, in centimetres.
     * The seekbar will span from  –[posMidDefault]  to  +[posMidDefault].
     * Valid range: 1..1000  (hard ceiling from ModelControlOverlayView).
     */


    var posMidDefault: Int
        get() = prefs.getInt(KEY_POS_MID, 100).coerceIn(1, 1_000)
        set(value) { prefs.edit().putInt(KEY_POS_MID, value.coerceIn(1, 1_000)).apply() }


    /**
     * Returns posMidDefault converted to the current distanceUnit for display.
     */
    fun posMidInCurrentUnit(): Float = when (distanceUnit) {
        DistanceUnit.METERS      -> posMidDefault / 100f
        DistanceUnit.CENTIMETERS -> posMidDefault.toFloat()
        DistanceUnit.MILLIMETERS -> posMidDefault * 10f
    }

    /**
     * Sets posMidDefault from a value expressed in the current distanceUnit.
     */

    // -------------------------------------------------------------------------
    // ModelControlOverlayView – scale default max progress
    // -------------------------------------------------------------------------

    /**
     * Default seekbar-max for scale.
     * Displayed value = progress / 100.  E.g. 500 → max displayed = 5.00×.
     * Valid range: 100..10000  (hard ceiling from ModelControlOverlayView).
     */
    var sclMaxDefault: Int
        get() = prefs.getInt(KEY_SCL_MAX, 500).coerceIn(100, 10_000)
        set(value) { prefs.edit().putInt(KEY_SCL_MAX, value.coerceIn(100, 10_000)).apply() }


    // ── Language override ────────────────────────────────────────────────────────
    /**
     * BCP-47 language tag override. Empty string = follow system locale.
     * Supported values: "" (system), "en", "pl"
     */
    var languageOverride: String
        get() = prefs.getString(KEY_LANGUAGE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LANGUAGE, value).apply() }

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------

    companion object {
        private const val PREFS_NAME  = "app_settings"
        private const val KEY_THEME = "theme_mode"

        private const val KEY_DISTANCE_UNIT = "distance_unit"
        private const val KEY_POS_MID   = "pos_mid_default"
        private const val KEY_SCL_MAX   = "scl_max_default"
        private const val KEY_LANGUAGE = "language_override"
    }
}