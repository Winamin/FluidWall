package com.txf.fluidwall.xposed

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import com.txf.fluidwall.core.ColorKMeans
import com.txf.fluidwall.core.FluidConfig
import com.txf.fluidwall.core.HookStatus
import com.txf.fluidwall.core.UnlockTimeline
import com.txf.fluidwall.core.WallpaperBridge
import com.txf.fluidwall.core.WallpaperGlTransition
import com.txf.fluidwall.core.WallpaperStore
import com.txf.fluidwall.core.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.concurrent.Executors

/**
 * HyperOS native Launcher has no DEX. The supplied 7.1.3 wallpaper APK still draws ordinary
 * desktop images in Java, on an EGL2 worker. Overlay its frame before ImageEngineImpl swaps it.
 * All reflection targets are checked by tools/verify_os4_apks.py against the supplied APKs.
 */
internal object HookOs4Wallpaper {
    private const val ROOT = "com.miui.miwallpaper."
    private val sessions = IdentityHashMap<Any, Playback>()
    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "fluid-os4-assets").apply { isDaemon = true }
    }
    private var installed = false

    fun install(ctx: Context, cl: ClassLoader) {
        if (installed || ctx.packageName != "com.miui.miwallpaper") return
        // This host requests QUERY_ALL_PACKAGES. Check the actual launcher flavor, not an OS label.
        val nativeLauncher = try {
            val info = ctx.packageManager.getApplicationInfo("com.miui.home", 0)
            info.flags and ApplicationInfo.FLAG_HAS_CODE == 0
        } catch (t: Throwable) {
            HookStatus.report(ctx, HookStatus.PLAYBACK_HOOK, false, "无法识别桌面类型：$t")
            return
        }
        if (!nativeLauncher) return // Keep the existing Java-launcher path on older systems.

        val hooks = ArrayList<XC_MethodHook.Unhook>()
        try {
            val service = cl.loadClass(ROOT + "wallpaperservice.UniversalWallpaper")
            val universalEngine = cl.loadClass(ROOT + "wallpaperservice.UniversalWallpaper\$UniversalEngine")
            val engine = cl.loadClass(ROOT + "wallpaperservice.impl.desktop.DesktopImageEngineImpl")
            val renderer = cl.loadClass(ROOT + "container.openGL.DesktopAnimImageWallpaperRenderer")
            val baseRenderer = cl.loadClass(ROOT + "opengl.ImageWallpaperRenderer")
            val animRenderer = cl.loadClass(ROOT + "opengl.AnimImageWallpaperRenderer")
            val getRenderer = engine.getMethod("P")
            val getHandler = engine.getMethod("G")
            val refresh = animRenderer.getDeclaredMethod("refresh", Boolean::class.javaPrimitiveType)
            val draw = baseRenderer.getDeclaredMethod("onDrawFrame")
            val finish = renderer.getDeclaredMethod("finish")
            val bind = engine.getDeclaredMethod("F", universalEngine)
            val surfaceDestroyed = checkNotNull(engine.superclass).getDeclaredMethod("q")
            val lock = engine.getDeclaredMethod("showKeyguardWallpaper", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val unlocks = listOf(
                engine.getDeclaredMethod("hideKeyguardWallpaper", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                engine.getDeclaredMethod("hideKeyguardWallpaper"),
                engine.getDeclaredMethod("showWallpaperUnlockAnim"),
            )
            check(baseRenderer.isAssignableFrom(renderer) && getRenderer.returnType == baseRenderer)
            check(getHandler.returnType == Handler::class.java)

            fun forEngine(host: Any): Playback? = synchronized(sessions) { sessions[getRenderer.invoke(host)] }
            hooks += XposedBridge.hookMethod(engine.getConstructor(service), after { p ->
                val target = getRenderer.invoke(p.thisObject) ?: return@after
                if (target.javaClass != renderer) return@after
                val handler = getHandler.invoke(p.thisObject) as Handler
                val playback = Playback(ctx, handler, target, refresh)
                synchronized(sessions) { sessions[target] = playback }
                playback.post { initialize() }
            })
            hooks += XposedBridge.hookMethod(bind, before { p ->
                val preview = (p.args[0] as? WallpaperService.Engine)?.isPreview ?: true
                forEngine(p.thisObject)?.post { bind(preview) }
            })
            hooks += XposedBridge.hookMethod(lock, before { p ->
                forEngine(p.thisObject)?.post { lock("keyguard") }
            })
            hooks += XposedBridge.hookMethod(surfaceDestroyed, before { p ->
                if (engine.isInstance(p.thisObject)) forEngine(p.thisObject)?.post { surfaceLost() }
            })
            for (method in unlocks) {
                hooks += XposedBridge.hookMethod(method, before { p ->
                    forEngine(p.thisObject)?.post { unlock(method.name) }
                })
            }
            hooks += XposedBridge.hookMethod(draw, after { p ->
                if (!p.hasThrowable()) synchronized(sessions) { sessions[p.thisObject] }?.draw()
            })
            hooks += XposedBridge.hookMethod(finish, before { p ->
                synchronized(sessions) { sessions.remove(p.thisObject) }?.finish()
            })
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val players = synchronized(sessions) { sessions.values.toList() }
                    for (player in players) player.post {
                        when (intent?.action) {
                            Intent.ACTION_SCREEN_OFF -> lock("screen_off")
                            Intent.ACTION_USER_PRESENT -> unlock("user_present")
                            Intent.ACTION_WALLPAPER_CHANGED, WallpaperStore.ACTION_CACHE_UPDATED -> reload()
                        }
                    }
                }
            }, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_WALLPAPER_CHANGED)
                addAction(WallpaperStore.ACTION_CACHE_UPDATED)
            }, Context.RECEIVER_EXPORTED)
            installed = true
            HookStatus.report(ctx, HookStatus.PLAYBACK_HOOK, true, "OS4 图片壁纸解锁入口已接入，等待桌面引擎")
            XLog.i("OS4 native launcher detected; desktop image unlock + EGL frame hooks installed")
        } catch (t: Throwable) {
            hooks.forEach { runCatching { it.unhook() } }
            HookStatus.report(ctx, HookStatus.PLAYBACK_HOOK, false, "OS4 图片壁纸接口不匹配：$t")
            XLog.i("OS4 wallpaper hook install failed: $t")
        }
    }

    private fun before(block: (XC_MethodHook.MethodHookParam) -> Unit) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) = guard { block(param) }
    }

    private fun after(block: (XC_MethodHook.MethodHookParam) -> Unit) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) = guard { block(param) }
    }

    private fun guard(block: () -> Unit) {
        try { block() } catch (t: Throwable) { XLog.i("OS4 wallpaper callback failed: $t") }
    }

    /** Mutable state and bitmap ownership are confined to the wallpaper's GL Handler. */
    private class Playback(
        private val ctx: Context,
        private val handler: Handler,
        private val renderer: Any,
        private val refresh: Method,
    ) {
        private val timeline = UnlockTimeline()
        private val gl = WallpaperGlTransition()
        private var preview = true
        @Volatile private var disposed = false
        private var config = FluidConfig()
        private var configReady = false
        private var lockBitmap: Bitmap? = null
        private var tint = 0xFF6FA8FF.toInt()
        private var seed = 0f
        private var revision = 0
        private var frames = 0
        private var source = ""
        private val tick = Runnable { requestFrame() }

        fun post(block: Playback.() -> Unit) {
            handler.post { if (!disposed) guard { block() } }
        }

        fun initialize() {
            if (disposed) return
            if ((ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true) {
                timeline.lock()
            }
            reload()
        }

        fun bind(isPreview: Boolean) {
            preview = isPreview
            if (preview) cancel() else {
                HookStatus.report(ctx, HookStatus.PLAYBACK_HOOK, true, "OS4 图片桌面引擎已连接，等待解锁")
                XLog.i("OS4 desktop renderer bound on ${handler.looper.thread.name}")
            }
        }

        fun lock(why: String) {
            cancel()
            timeline.lock()
            reload()
            XLog.i("OS4 lock cycle armed ($why)")
        }

        fun unlock(why: String) {
            if (preview || disposed) return
            if ((ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == false) return
            if (!timeline.unlock(SystemClock.uptimeMillis())) return
            frames = 0
            source = why
            seed = if (config.seed == 0f) (SystemClock.elapsedRealtime() % 997L) / 97f else config.seed
            XLog.i("OS4 unlock received from=$why assets=$configReady")
            schedule()
        }

        fun reload() {
            // Keep an in-flight transition's source texture stable. Refresh again on the next lock.
            if (disposed || (timeline.active && frames > 0)) return
            configReady = false
            val request = ++revision
            loader.execute {
                val cfg = FluidConfig.read(ctx)
                val bitmap = try {
                    if (cfg.enabled) WallpaperBridge.lockBitmap(ctx, 1440)
                        ?: WallpaperStore.loadRemote(ctx, "lock")
                        ?: WallpaperStore.load(WallpaperStore.LOCK) else null
                } catch (t: Throwable) {
                    XLog.i("OS4 lock bitmap load failed: $t")
                    null
                }
                val color = try { bitmap?.let { ColorKMeans.extract(it, 5, 8).tint } ?: tint }
                catch (_: Throwable) { tint }
                val accepted = handler.post {
                    if (disposed || request != revision) {
                        bitmap?.recycle()
                    } else {
                        lockBitmap?.takeIf { it !== bitmap }?.recycle()
                        lockBitmap = bitmap
                        config = cfg
                        tint = color
                        configReady = true
                        XLog.i("OS4 assets loaded lock=${bitmap?.width}x${bitmap?.height} enabled=${cfg.enabled}")
                        if (timeline.active) schedule()
                    }
                }
                if (!accepted) bitmap?.recycle()
            }
        }

        private fun schedule() {
            handler.removeCallbacks(tick)
            if (!disposed && timeline.active) handler.postDelayed(tick, 16L)
        }

        private fun requestFrame() {
            if (disposed || !timeline.active) return
            if (timeline.expired(SystemClock.uptimeMillis(), config.durationMs)) {
                fail("壁纸播放超时（缓存未就绪或 surface 不再绘制）")
                return
            }
            if (configReady && !config.enabled) {
                cancel()
                XLog.i("OS4 playback disabled by config")
                return
            }
            try {
                // Never call refresh inside the draw Hook: ThreadUtils.b runs it inline on this
                // Handler, which would recurse into ImageEngineImpl.M before eglSwapBuffers.
                refresh.invoke(renderer, true)
            } catch (t: Throwable) {
                fail("请求壁纸帧失败：$t")
            }
            schedule() // Also bounds waiting when the host ignores refresh while invisible.
        }

        fun draw() {
            if (disposed || preview || !timeline.active) return
            if (Looper.myLooper() !== handler.looper) {
                post { fail("壁纸绘制线程与 APK 契约不匹配") }
                return
            }
            if (configReady && !config.enabled) { cancel(); return }
            if (timeline.expired(SystemClock.uptimeMillis(), config.durationMs)) {
                fail("壁纸播放超时，已保留系统壁纸")
                return
            }
            val bitmap = lockBitmap
            val progress = timeline.frame(SystemClock.uptimeMillis(), configReady && bitmap != null, config.durationMs)
                ?: return
            if (progress == 1f) {
                handler.removeCallbacks(tick)
                gl.release()
                HookStatus.report(ctx, HookStatus.PLAYBACK_FRAME, true, "OS4 图片壁纸已绘制 $frames 帧；系统最终可见性需实机确认")
                XLog.i("OS4 playback completed frames=$frames from=$source")
                return // Leave the host's exact final pixels, including its crop and effects.
            }
            try {
                if (!gl.draw(bitmap!!, config, progress, tint, seed)) {
                    fail("OpenGL 未能绘制过渡：${gl.lastFailure ?: "未知原因"}")
                    return
                }
                frames++
                if (frames == 1) {
                    HookStatus.report(ctx, HookStatus.PLAYBACK_FRAME, true, "OS4 图片壁纸已绘制首帧（交换缓冲前），来源 $source")
                    XLog.i("OS4 first fluid frame submitted")
                }
            } catch (t: Throwable) {
                fail("OpenGL 过渡失败：$t")
            }
        }

        private fun fail(detail: String) {
            cancel()
            HookStatus.report(ctx, HookStatus.PLAYBACK_FRAME, false, detail)
            XLog.i("OS4 playback failed: $detail")
        }

        private fun cancel() {
            timeline.cancel()
            handler.removeCallbacks(tick)
            gl.release() // Only deletes IDs if their owning EGLContext is current.
        }

        fun surfaceLost() {
            val wasActive = timeline.active
            timeline.surfaceLost()
            handler.removeCallbacks(tick)
            gl.release()
            if (wasActive) {
                HookStatus.report(ctx, HookStatus.PLAYBACK_FRAME, false, "壁纸 surface 已销毁，取消本次过渡")
                XLog.i("OS4 playback cancelled: surface destroyed")
            }
        }

        fun finish() {
            disposed = true
            cancel()
            revision++
            lockBitmap?.recycle()
            lockBitmap = null
            XLog.i("OS4 desktop renderer finished")
        }
    }
}
