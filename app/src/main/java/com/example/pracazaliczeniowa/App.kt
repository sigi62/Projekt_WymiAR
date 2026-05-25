package com.example.pracazaliczeniowa

import android.app.Application
import com.example.pracazaliczeniowa.Objects.AppSettings

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val settings = AppSettings(this)
        settings.applyTheme()
        settings.applyLocale(this)
    }
}
