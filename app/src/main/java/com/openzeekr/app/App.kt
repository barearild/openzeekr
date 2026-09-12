package com.openzeekr.app

import android.app.Application

class App : Application() {
    lateinit var deps: Deps
        private set

    override fun onCreate() {
        super.onCreate()
        deps = Deps(this)
    }
}
