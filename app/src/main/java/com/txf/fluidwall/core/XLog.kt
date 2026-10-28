package com.txf.fluidwall.core

import android.os.SystemClock
import android.util.Log
import de.robv.android.xposed.XposedBridge

object XLog {
    private const val TAG = "FluidWall"

    fun i(msg: String) {
        val line = "${SystemClock.uptimeMillis()} $msg"
        try {
            Log.i(TAG, line)
        } catch (t: Throwable) {
        }
        try {
            XposedBridge.log("$TAG: $line")
        } catch (t: Throwable) {
        }
        LogStore.record(msg)
    }
}
