package com.txf.fluidwall.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import com.txf.fluidwall.core.ColorKMeans
import com.txf.fluidwall.core.HookStatus
import com.txf.fluidwall.core.WallpaperBridge
import com.txf.fluidwall.core.WallpaperStore
import com.txf.fluidwall.core.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean

object HookWallpaper {

    private var installed = false
    private val main = Handler(Looper.getMainLooper())
    private val exporting = AtomicBoolean(false)
    private var drawableHooked = false

    @Volatile
    var hintColor: Int = 0
        private set

    fun install(cl: ClassLoader) {
        if (installed) return
        installed = true
        HookUtil.appInit { ctx ->
            drawableHooked = hookDrawable(cl)
            watchChanges(ctx)
            exportAsync(ctx)
        }
    }

    private fun exportAsync(ctx: Context) {
        if (!exporting.compareAndSet(false, true)) return
        Thread({
            try {
                HookStatus.report(
                    ctx,
                    HookStatus.WALLPAPER_HOOK,
                    drawableHooked,
                    if (drawableHooked) "WallpaperManager.getDrawable 已 Hook"
                    else "WallpaperManager.getDrawable Hook 失败，详见日志",
                )
                WallpaperStore.exportViaApp(ctx)
            } finally {
                exporting.set(false)
            }
        }, "fluid-wp-export").start()
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
                IntentFilter().apply {
                    addAction(Intent.ACTION_WALLPAPER_CHANGED)
                    addAction(WallpaperStore.ACTION_EXPORT)
                },
                Context.RECEIVER_EXPORTED,
            )
        } catch (t: Throwable) {
            XLog.i("wallpaper change receiver failed: $t")
        }
    }

    private fun hookDrawable(cl: ClassLoader): Boolean {
        return try {
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
            true
        } catch (t: Throwable) {
            XLog.i("WallpaperManager hook failed: $t")
            false
        }
    }
}
