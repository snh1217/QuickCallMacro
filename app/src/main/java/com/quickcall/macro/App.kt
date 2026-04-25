package com.quickcall.macro

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PreferencesManager.init(this)
    }
}
