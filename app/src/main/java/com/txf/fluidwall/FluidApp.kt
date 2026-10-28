package com.txf.fluidwall

import android.app.Application
import com.txf.fluidwall.core.ConfigStore
import com.txf.fluidwall.core.LogStore
import com.txf.fluidwall.core.XLog

class FluidApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ConfigStore.load(this)
        LogStore.init(this)
        XLog.i("app started ${packageName}")
    }
}
