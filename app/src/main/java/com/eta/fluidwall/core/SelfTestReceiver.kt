package com.eta.fluidwall.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val i = intent ?: return
        val p = i.getIntExtra("progress", -1)
        if (p < 0) {
            SelfTest.run(context)
            return
        }
        val warp = i.getIntExtra("warp", -1)
        val grain = i.getIntExtra("grain", -1)
        val scale = i.getIntExtra("scale", -1)
        SelfTest.capture(
            context,
            p / 100f,
            if (scale >= 0) scale / 10f else 3.2f,
            if (warp >= 0) warp / 1000f else 0.06f,
            if (grain >= 0) grain / 1000f else 0.016f,
        )
    }
}
