package com.example.swarmai

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            android.util.Log.e("SwarmAI_Crash", "Uncaught exception", throwable)
        }
    }
}
