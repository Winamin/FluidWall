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
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.animation.PathInterpolator
import com.txf.fluidwall.core.ColorKMeans
import com.txf.fluidwall.core.FluidConfig
import com.txf.fluidwall.core.HookStatus
import com.txf.fluidwall.core.UnlockTimeline
import com.txf.fluidwall.core.WallpaperBridge
import com.txf.fluidwall.core.WallpaperStore
import com.txf.fluidwall.core.WallpaperViewLayer
import com.txf.fluidwall.core.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Uses the APK's proven unlock events, with the original View/AGSL on a separate wallpaper child. */
internal object HookOs4Wallpaper {
    private const val ROOT = "com.miui.miwallpaper."
    private val main = Handler(Looper.getMainLooper())
    private val sessions = IdentityHashMap<Any, Playback>()
    private val surfaces = IdentityHashMap<WallpaperService.Engine, Playback>()
    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "fluid-os4-assets").apply { isDaemon = true }
    }
    private var installed = false
    @Volatile private var diagnosticContext: WeakReference<Context>? = null
    private val callbackFailureReported = AtomicBoolean(false)

    fun install(ctx: Context, cl: ClassLoader) {
        if (installed || ctx.packageName != "com.miui.miwallpaper") return
        val nativeLauncher = try {
            ctx.packageManager.getApplicationInfo("com.miui.home", 0).flags and ApplicationInfo.FLAG_HAS_CODE == 0
        } catch (t: Throwable) {
            HookStatus.report(ctx, HookStatus.PLAYBACK_HOOK, false, "无法识别桌面类型：$t")
            return
        }
        if (!nativeLauncher) return
        diagnosticContext = WeakReference(ctx.applicationContext ?: ctx)
        val hooks = ArrayList<XC_MethodHook.Unhook>()
        try {
            val serviceClass = cl.loadClass(ROOT + "wallpaperservice.UniversalWallpaper")
            val universalClass = cl.loadClass(ROOT + "wallpaperservice.UniversalWallpaper\$UniversalEngine")
            val engineClass = cl.loadClass(ROOT + "wallpaperservice.impl.desktop.DesktopImageEngineImpl")
            val parentMethod = universalClass.getDeclaredMethod("c")
            check(parentMethod.returnType == SurfaceControl::class.java)
            val bindMethod = engineClass.getDeclaredMethod("F", universalClass)
            val finishMethod = engineClass.getDeclaredMethod("L")
            val lockMethod = engineClass.getDeclaredMethod("showKeyguardWallpaper", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val unlockMethods = listOf(
                engineClass.getDeclaredMethod("hideKeyguardWallpaper", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                engineClass.getDeclaredMethod("hideKeyguardWallpaper"),
                engineClass.getDeclaredMethod("showWallpaperUnlockAnim"),
            )
            val createdMethod = universalClass.getDeclaredMethod("onSurfaceCreated", SurfaceHolder::class.java)
            val changedMethod = universalClass.getDeclaredMethod("onSurfaceChanged", SurfaceHolder::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val destroyedMethod = universalClass.getDeclaredMethod("onSurfaceDestroyed", SurfaceHolder::class.java)
            val visibleMethod = universalClass.getDeclaredMethod("onVisibilityChanged", Boolean::class.javaPrimitiveType)

            fun forEngine(host: Any): Playback? = synchronized(sessions) { sessions[host] }
            fun forSurface(host: Any): Playback? = synchronized(surfaces) { surfaces[host] }
            fun unbind(player: Playback) = synchronized(surfaces) {
                surfaces.entries.removeAll { it.value === player }
            }
            hooks += XposedBridge.hookMethod(engineClass.getConstructor(serviceClass), after { p ->
                val playback = Playback(ctx, parentMethod)
                synchronized(sessions) { sessions[p.thisObject] = playback }
                playback.post { it.initialize() }
            })
            hooks += XposedBridge.hookMethod(bindMethod, after { p ->
                val player = forEngine(p.thisObject) ?: return@after
                val engine = p.args[0] as? WallpaperService.Engine
                unbind(player)
                if (engine != null) synchronized(surfaces) { surfaces[engine] = player }
                player.post { it.bind(engine) }
            })
            hooks += XposedBridge.hookMethod(lockMethod, before { p ->
                forEngine(p.thisObject)?.post { it.lock("keyguard") }
            })
            for (method in unlockMethods) hooks += XposedBridge.hookMethod(method, before { p ->
                forEngine(p.thisObject)?.post { it.unlock(method.name) }
            })
            for (method in listOf(createdMethod, changedMethod)) {
                hooks += XposedBridge.hookMethod(method, after { p ->
                    forSurface(p.thisObject)?.post { it.surfaceChanged() }
                })
            }
            hooks += XposedBridge.hookMethod(destroyedMethod, before { p ->
                forSurface(p.thisObject)?.post { it.surfaceLost() }
            })
            hooks += XposedBridge.hookMethod(visibleMethod, after { p ->
                val visible = p.args[0] as Boolean
                forSurface(p.thisObject)?.post { it.visibility(visible) }
            })
            hooks += XposedBridge.hookMethod(finishMethod, before { p ->
                val player = synchronized(sessions) { sessions.remove(p.thisObject) } ?: return@before
                unbind(player)
                player.post { it.finish() }
            })
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val players = synchronized(sessions) { sessions.values.toList() }
                    for (player in players) player.post { playback ->
                        when (intent?.action) {
                            Intent.ACTION_SCREEN_OFF -> playback.lock("screen_off")
                            Intent.ACTION_USER_PRESENT -> playback.unlock("user_present")
                            Intent.ACTION_WALLPAPER_CHANGED, WallpaperStore.ACTION_CACHE_UPDATED -> playback.reload()
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
            HookStatus.report(ctx, HookStatus.PLAYBACK_HOOK, true, "OS4 View/AGSL 解锁入口已接入，等待桌面壁纸 surface")
            XLog.i("OS4 native launcher: View/AGSL wallpaper layer hooks installed")
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
        override fun afterHookedMethod(param: MethodHookParam) { if (!param.hasThrowable()) guard { block(param) } }
    }
    private fun guard(block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            XLog.i("OS4 wallpaper callback failed: $t")
            if (callbackFailureReported.compareAndSet(false, true)) {
                XLog.i(t.stackTraceToString())
                HookStatus.report(diagnosticContext?.get(), HookStatus.PLAYBACK_HOOK, false, "OS4 回调异常：$t")
            }
        }
    }

    /** All mutable playback, View and surface state belongs to the main Looper. */
    private class Playback(private val ctx: Context, private val parentMethod: Method) {
        private val timeline = UnlockTimeline()
        private val easing = PathInterpolator(0.22f, 1f, 0.36f, 1f)
        private var engine: WallpaperService.Engine? = null
        private var layer: WallpaperViewLayer? = null
        private var visible = false
        private var disposed = false
        private var config = FluidConfig()
        private var configReady = false
        private var lockBitmap: Bitmap? = null
        private var tint = 0xFF6FA8FF.toInt()
        private var seed = 0f
        private var revision = 0
        private var frames = 0
        private var source = ""
        private var framePending = false
        private val tick = Choreographer.FrameCallback {
            framePending = false
            try { frame() } catch (t: Throwable) { fail("View 绘制异常：$t") }
        }

        // Explicit argument prevents Method variables from shadowing Playback members.
        fun post(block: (Playback) -> Unit) { main.post { if (!disposed) guard { block(this) } } }
        fun initialize() {
            if ((ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true) timeline.lock()
            reload()
        }
        fun bind(target: WallpaperService.Engine?) {
            if (engine !== target) { layer?.release(); layer = null }
            engine = target
            if (target == null || target.isPreview) { cancel(); return }
            visible = target.isVisible
            layer = layer ?: target.displayContext?.let { WallpaperViewLayer(it) }
            surfaceChanged()
            HookStatus.report(ctx, HookStatus.PLAYBACK_HOOK, true, "OS4 图片桌面已绑定 View/AGSL，等待解锁")
        }
        fun surfaceChanged() {
            syncSurface()
            if (timeline.active) schedule()
        }
        private fun syncSurface(): Boolean {
            val host = engine ?: return false
            if (host.isPreview) return false
            val parent = parentMethod.invoke(host) as? SurfaceControl ?: return false
            if (!parent.isValid || !host.surfaceHolder.surface.isValid) return false
            val bounds = host.surfaceHolder.surfaceFrame
            if (bounds.isEmpty) return false
            val target = layer ?: host.displayContext?.let { context ->
                WallpaperViewLayer(context).also { layer = it }
            } ?: return false
            target.attach(parent, bounds.width(), bounds.height())
            return target.ready
        }
        fun visibility(value: Boolean) {
            visible = value
            if (value) surfaceChanged() else {
                timeline.surfaceLost() // Preserve a lock cycle that has not unlocked yet.
                stopFrames()
                layer?.hide()
            }
        }
        fun lock(why: String) {
            cancel()
            timeline.lock()
            reload()
            XLog.i("OS4 View lock cycle armed ($why)")
        }
        fun unlock(why: String) {
            if (engine == null || engine?.isPreview == true) return
            if ((ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == false) return
            if (!timeline.unlock(SystemClock.uptimeMillis())) return
            frames = 0
            source = why
            seed = if (config.seed == 0f) (SystemClock.elapsedRealtime() % 997L) / 97f else config.seed
            XLog.i("OS4 View unlock from=$why assets=$configReady visible=$visible")
            schedule()
        }
        fun reload() {
            if (disposed || (timeline.active && frames > 0)) return
            configReady = false
            val request = ++revision
            loader.execute {
                val cfg = FluidConfig.read(ctx)
                val bitmap = try {
                    if (cfg.enabled) WallpaperBridge.lockBitmap(ctx, 1440)
                        ?: WallpaperStore.loadRemote(ctx, "lock")
                        ?: WallpaperStore.load(WallpaperStore.LOCK) else null
                } catch (t: Throwable) { XLog.i("OS4 lock image load failed: $t"); null }
                val color = runCatching { bitmap?.let { ColorKMeans.extract(it, 5, 8).tint } }.getOrNull() ?: 0xFF6FA8FF.toInt()
                val accepted = main.post {
                    if (disposed || request != revision) bitmap?.recycle() else {
                        layer?.release()
                        lockBitmap?.takeIf { it !== bitmap }?.recycle()
                        lockBitmap = bitmap
                        config = cfg
                        tint = color
                        configReady = true
                        XLog.i("OS4 View assets ready ${bitmap?.width}x${bitmap?.height} enabled=${cfg.enabled}")
                        if (timeline.active) schedule()
                    }
                }
                if (!accepted) bitmap?.recycle()
            }
        }
        private fun schedule() {
            if (!disposed && timeline.active && !framePending) {
                framePending = true
                Choreographer.getInstance().postFrameCallback(tick)
            }
        }
        private fun stopFrames() {
            Choreographer.getInstance().removeFrameCallback(tick)
            framePending = false
        }
        private fun frame() {
            if (disposed || !timeline.active) return
            if (timeline.expired(SystemClock.uptimeMillis(), config.durationMs)) {
                fail("View 播放超时：${layer?.lastFailure ?: "图片或桌面 surface 尚未就绪"}")
                return
            }
            if (configReady && !config.enabled) { cancel(); return }
            val ready = configReady && lockBitmap != null && visible && syncSurface()
            val progress = timeline.frame(SystemClock.uptimeMillis(), ready, config.durationMs)
            if (progress != null) {
                if (progress == 1f) {
                    layer?.hide()
                    HookStatus.report(ctx, HookStatus.PLAYBACK_FRAME, true, "View/AGSL 已绘制 $frames 帧并释放遮罩")
                    XLog.i("OS4 View playback complete frames=$frames from=$source")
                } else {
                    val target = checkNotNull(layer)
                    if (!target.draw(checkNotNull(lockBitmap), config, easing.getInterpolation(progress), tint, seed)) {
                        fail(target.lastFailure ?: "View/AGSL 绘制失败")
                        return
                    }
                    if (++frames == 1) {
                        HookStatus.report(ctx, HookStatus.PLAYBACK_FRAME, true, "View/AGSL 首帧已提交，来源 $source")
                        XLog.i("OS4 View first hardware Canvas frame submitted")
                    }
                }
            }
            schedule()
        }
        private fun fail(detail: String) {
            cancel()
            HookStatus.report(ctx, HookStatus.PLAYBACK_FRAME, false, detail)
            XLog.i("OS4 View playback failed: $detail")
        }
        private fun cancel() { timeline.cancel(); stopFrames(); layer?.hide() }
        fun surfaceLost() {
            timeline.surfaceLost()
            stopFrames()
            layer?.release()
        }
        fun finish() {
            cancel()
            disposed = true
            revision++
            layer?.release()
            layer = null
            lockBitmap?.recycle()
            lockBitmap = null
            engine = null
        }
    }
}
