package com.txf.fluidwall.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import com.txf.fluidwall.core.ColorKMeans
import com.txf.fluidwall.core.WallpaperBridge
import com.txf.fluidwall.core.WallpaperStore
import com.txf.fluidwall.core.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

object HookWallpaper {

    private var installed = false
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var hintColor: Int = 0
        private set

    fun install(cl: ClassLoader) {
        if (installed) return
        installed = true
        HookUtil.appInit { ctx ->
            hookDrawable(cl)
            exportAsync(ctx)
            watchChanges(ctx)
        }
    }

    private fun exportAsync(ctx: Context) {
        Thread({ WallpaperStore.exportViaApp(ctx) }, "fluid-wp-export").start()
    }

    private fun watchChanges(ctx: Context) {
        try {
            val app = ctx.applicationContext ?: return
            app.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        main.postDelayed({ exportAsync(c ?: app) }, 1500L)
                    }
                },
                IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
        } catch (t: Throwable) {
            XLog.i("wallpaper change receiver failed: $t")
        }
    }

    private fun hookDrawable(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.WallpaperManager",
                cl,
                "getDrawable",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val src = param.result as? Drawable ?: return
                        val copy = try {
                            src.constantState?.newDrawable()
                        } catch (t: Throwable) {
                            null
                        } ?: return
                        Thread({
                            try {
                                val bmp = WallpaperBridge.drawableToBitmap(copy, 256) ?: return@Thread
                                hintColor = ColorKMeans.extract(bmp, 5, 6).tint
                                bmp.recycle()
                            } catch (t: Throwable) {
                                XLog.i("wallpaper palette failed: $t")
                            }
                        }, "fluid-wp-palette").start()
                    }
                },
            )
            XLog.i("WallpaperManager.getDrawable hook ok")
        } catch (t: Throwable) {
            XLog.i("WallpaperManager hook failed: $t")
        }
    }
}
