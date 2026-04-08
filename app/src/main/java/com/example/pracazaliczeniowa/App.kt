package com.example.pracazaliczeniowa

import android.app.Application
import com.example.pracazaliczeniowa.Helpers.AppSettings

/**
 * Custom Application class.
 * Applies the saved dark/light mode preference at process start, before any
 * Activity inflates a layout, so ?attr/ colour tokens always resolve correctly.
 *
 * Register in AndroidManifest.xml:
 *   <application android:name=".App" ...>
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings(this).applyTheme()
    }
}
