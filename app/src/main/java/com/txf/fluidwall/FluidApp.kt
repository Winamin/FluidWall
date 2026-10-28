package com.txf.fluidwall

import android.app.Application
import com.txf.fluidwall.core.ConfigStore
import com.txf.fluidwall.xposed.FluidModule

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
