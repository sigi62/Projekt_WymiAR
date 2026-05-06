package com.example.pracazaliczeniowa.Helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

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
    // Dark / Light mode
    // -------------------------------------------------------------------------

    var isDarkMode: Boolean
        get() = prefs.getBoolean(KEY_DARK_MODE, true)   // default: dark
        set(value) { prefs.edit().putBoolean(KEY_DARK_MODE, value).apply() }

    /**
     * Applies the stored theme preference to the whole process via
     * [AppCompatDelegate.setDefaultNightMode].  Call this from Application.onCreate()
     * *and* from [SettingsActivity] immediately after the user toggles the switch.
     */
    fun applyTheme() {
        val mode = if (isDarkMode)
            AppCompatDelegate.MODE_NIGHT_YES
        else
            AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun applyLocale(context: Context) {
        val tag = languageOverride
        val locale = if (tag.isEmpty()) {
            // Restore system default
            androidx.core.os.LocaleListCompat.getEmptyLocaleList()
        } else {
            androidx.core.os.LocaleListCompat.forLanguageTags(tag)
        }
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locale)
    }
    // -------------------------------------------------------------------------
    // ModelControlOverlayView – position default half-range (cm)
    // -------------------------------------------------------------------------

    /**
     * Default half-range for the position seekbar, in centimetres.
     * The seekbar will span from  –[posMidDefault]  to  +[posMidDefault].
     * Valid range: 1..1000  (hard ceiling from ModelControlOverlayView).
     */
    var posMidDefault: Int
        get() = prefs.getInt(KEY_POS_MID, 100).coerceIn(1, 1_000)
        set(value) { prefs.edit().putInt(KEY_POS_MID, value.coerceIn(1, 1_000)).apply() }

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
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_POS_MID   = "pos_mid_default"
        private const val KEY_SCL_MAX   = "scl_max_default"
        private const val KEY_LANGUAGE = "language_override"
    }
}
