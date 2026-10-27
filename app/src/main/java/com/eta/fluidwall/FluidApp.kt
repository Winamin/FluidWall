package com.eta.fluidwall

import android.app.Application
import com.eta.fluidwall.core.ConfigStore
import com.eta.fluidwall.xposed.FluidModule

class FluidApp : Application() {

    override fun onCreate() {
        super.onCreate()
        entryRef = FluidModule::class.java
        ConfigStore.load(this)
    }

    companion object {
        @Volatile
        var entryRef: Class<*>? = null
            private set
    }
}
