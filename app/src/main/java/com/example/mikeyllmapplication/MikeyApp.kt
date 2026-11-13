package com.example.mikeyllmapplication

import android.app.Application

class MikeyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.autoLoad()
    }
}
