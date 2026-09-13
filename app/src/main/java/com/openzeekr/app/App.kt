package com.openzeekr.app

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Process-wide foreground flag, updated by [App]'s activity lifecycle callbacks. */
object AppForeground {
    @Volatile var isForeground: Boolean = false
        internal set
}

class App : Application() {
    lateinit var deps: Deps
        private set

    override fun onCreate() {
        super.onCreate()
        deps = Deps(this)
        registerActivityLifecycleCallbacks(ForegroundTracker())
    }

    /** Counts started activities to derive a reliable foreground/background flag. */
    private class ForegroundTracker : ActivityLifecycleCallbacks {
        private var started = 0
        override fun onActivityStarted(activity: Activity) {
            started++
            AppForeground.isForeground = started > 0
        }
        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            AppForeground.isForeground = started > 0
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
