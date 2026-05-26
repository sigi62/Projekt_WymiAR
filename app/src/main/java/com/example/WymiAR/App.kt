package com.example.WymiAR

import android.app.Application
import com.example.WymiAR.Objects.AppSettings

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val settings = AppSettings(this)
        settings.applyTheme()
        settings.applyLocale(this)
    }
}
