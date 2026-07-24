package com.realitylock.app

import android.app.Application
import com.realitylock.app.core.di.AppContainer

/**
 * Application entry point. Owns the manual dependency graph (see [AppContainer]);
 * crypto key bootstrap is added in Phase 3.
 */
class RealityLockApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
