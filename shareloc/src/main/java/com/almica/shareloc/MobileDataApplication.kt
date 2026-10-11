package com.almica.shareloc

import android.app.Application
import timber.log.Timber

class MobileDataApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
