package com.txf.fluidwall.core

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup

/**
 * Wallpaper-layer transition inside the launcher window.
 *
 * The view is the first child of the decor view, so it paints above the wallpaper window and
 * below every icon / widget the launcher draws.
 *
 * While the keyguard is up the view holds a static frame of the lock wallpaper, so removing the
 * keyguard never flashes the home wallpaper underneath; the fluid mask then takes over.
 *
 * Playback is armed by a lock cycle and consumed by the first unlock signal, no matter how many
 * signals arrive or in which order. Arming does not depend on having seen the previous unlock,
 * which is what keeps the second and later unlocks working.
 */
object LauncherLayer {

    private val main = Handler(Looper.getMainLooper())

    private var ctx: Context? = null
    private var km: KeyguardManager? = null
    private var decor: ViewGroup? = null
    private var view: FluidTransitionView? = null
    private var cfg = FluidConfig()

    /** View is showing a static lock-wallpaper frame while the keyguard is up. */
    private var holding = false
    private var holdPending = false

    /** A lock cycle is waiting for its unlock; cleared by the first play of the cycle. */
    private var armed = false
    private var cycleStart = 0L

    private var locked = true

    /** Moment the current unlock was reported; also used to reject the fingerprint relock blip. */
    private var unlockAt = 0L

    private var playing = false

    /** Guards animation callbacks from a previous play. */
    private var generation = 0

    private var ignoreUntil = 0L
    private var watching = false

    private var loading = false
    private var lockBmp: Bitmap? = null
    private var homeBmp: Bitmap? = null
    private var tint = 0xFF6FA8FF.toInt()
    private var ready = false

    private const val SETTLE_LOCK_MS = 400L
    private const val RELOCK_GUARD_MS = 1200L
    private const val PLAY_WINDOW_MS = 1800L
    private const val IGNORE_AFTER_REGISTER_MS = 1500L

    fun onResume(activity: Activity?) {
        if (activity == null) return
        attach(activity)
        tryPlay("resume")
    }

    fun onWindowFocus(gained: Boolean) {
        if (gained) tryPlay("focus")
    }

    fun onPause() = Unit

    /**
     * Called from the launcher's own unlock animation entry points. Those run while the keyguard
     * is still animating away, so the keyguard / window visibility gates do not apply.
     */
    fun onSystemUnlockAnim(source: String) {
        unlocked()
        tryPlay(source, force = true)
    }

    fun onWallpaperChanged() {
        if (playing) {
            XLog.i("wallpaper reload skipped: animation in progress")
            return
        }
        loadBitmaps(ctx)
    }

    private fun attach(activity: Activity) {
        val dec = try {
            activity.window?.decorView as? ViewGroup
        } catch (t: Throwable) {
            null
        } ?: return

        val app = activity.applicationContext
        ctx = app
        LogStore.init(app)
        cfg = FluidConfig.read(app)
        if (km == null) {
            km = app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        }

        if (!watching) {
            watching = true
            locked = keyguardLocked()
            if (locked) arm(SystemClock.uptimeMillis(), "attach")
            watchKeyguard(app)
            watchScreenOff(app)
            watchWallpaperChanges(app)
            watchUserPresent(app)
            watchDebugTrigger(app)
        }

        val current = view
        if (current != null && current.isAttachedToWindow && decor === dec) return

        decor = dec
        val v = current ?: FluidTransitionView(app).also {
            it.ownBitmaps = false
            it.visibility = View.GONE
        }
        try {
            if (v.parent != null) (v.parent as? ViewGroup)?.removeView(v)
            dec.addView(
                v,
                0,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            view = v
        } catch (t: Throwable) {
            XLog.i("attach failed: $t")
            return
        }

        if (keyguardLocked()) holdPending = true
        loadBitmaps(app)
        XLog.i("layer attached children=${dec.childCount} locked=${keyguardLocked()} armed=$armed")
    }

    // ---------------------------------------------------------------- bitmaps

    /**
     * Screen off is the one signal every lock cycle produces. Arming here keeps later unlocks
     * working even when the keyguard state listener drops a transition.
     */
    private fun watchScreenOff(app: Context) {
        try {
            app.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        if (playing) return
                        if (!armed) arm(SystemClock.uptimeMillis(), "screen_off")
                    }
                },
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                Context.RECEIVER_EXPORTED,
            )
        } catch (t: Throwable) {
            XLog.i("screen off receiver failed: $t")
        }
    }

    private fun watchWallpaperChanges(app: Context) {
        try {
            app.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) = onWallpaperChanged()
                },
                IntentFilter(Intent.ACTION_WALLPAPER_CHANGED).apply {
                    addAction(WallpaperStore.ACTION_CACHE_UPDATED)
                },
                Context.RECEIVER_EXPORTED,
            )
        } catch (t: Throwable) {
            XLog.i("wallpaper receiver failed: $t")
        }
    }

    private fun watchUserPresent(app: Context) {
        try {
            app.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        unlocked()
                        if (!tryPlay("user_present") && !armed && !playing) releaseHold()
                    }
                },
                IntentFilter(Intent.ACTION_USER_PRESENT),
                Context.RECEIVER_EXPORTED,
            )
        } catch (t: Throwable) {
            XLog.i("user_present receiver failed: $t")
        }
    }

    private fun watchDebugTrigger(app: Context) {
        try {
            app.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        cfg = FluidConfig.read(app)
                        fire("debug_test")
                    }
                },
                IntentFilter("com.txf.fluidwall.LAYERTEST"),
                Context.RECEIVER_EXPORTED,
            )
        } catch (t: Throwable) {
            XLog.i("debug receiver failed: $t")
        }
    }

    private fun loadBitmaps(app: Context?) {
        if (app == null || loading) return
        loading = true
        Thread({
            var lock: Bitmap? = null
            var home: Bitmap? = null
            try {
                lock = WallpaperBridge.lockBitmap(app, 1080)
                home = WallpaperBridge.homeBitmap(app, 1080)
                if (lock == null || home == null) XLog.i("direct wallpaper read unavailable")
            } catch (t: Throwable) {
                XLog.i("direct wallpaper read failed: $t")
            }
            if (lock == null) lock = WallpaperStore.loadRemote(app, "lock")
                ?: WallpaperStore.load(WallpaperStore.LOCK)
            if (home == null) home = WallpaperStore.loadRemote(app, "home")
                ?: WallpaperStore.load(WallpaperStore.HOME)
            if (lock == null) lock = home?.let { WallpaperBridge.blurred(it, 14) }
            val l = lock
            val h = home ?: lock
            if (l == null || h == null) {
                XLog.i("no wallpaper bitmaps yet")
                WallpaperStore.requestExport(app)
                loading = false
                return@Thread
            }
            val t = try {
                ColorKMeans.extract(l, 5, 8).tint
            } catch (e: Throwable) {
                tint
            }
            main.post {
                val oldLock = lockBmp
                val oldHome = homeBmp
                lockBmp = l
                homeBmp = h
                tint = t
                ready = true
                loading = false
                if (oldLock !== l) oldLock?.recycle()
                if (oldHome !== h) oldHome?.recycle()
                view?.setBitmaps(l, h)
                XLog.i("bitmaps ready ${l.width}x${l.height} / ${h.width}x${h.height}")
                if (holdPending || (keyguardLocked() && !playing)) holdLockWallpaper()
            }
        }, "fluid-layer-load").start()
    }

    // ---------------------------------------------------------------- hold / release

    /**
     * Show a static lock-wallpaper frame so the keyguard can be removed without the system
     * flashing the home wallpaper underneath. Drawing it once also compiles the AGSL shader
     * well before the unlock.
     */
    private fun holdLockWallpaper() {
        if (playing) return
        if (!ready) {
            holdPending = true
            return
        }
        val v = view ?: return
        holdPending = false
        if (v.visibility == View.VISIBLE) {
            holding = true
            return
        }
        holding = true
        v.animate().cancel()
        v.progress = 0f
        v.alpha = 1f
        v.visibility = View.VISIBLE
        XLog.i("lock wallpaper held")
    }

    private fun releaseHold() {
        holdPending = false
        if (playing) return
        holding = false
        val v = view ?: return
        if (v.visibility != View.VISIBLE) return
        v.animate().cancel()
        v.progress = 0f
        v.alpha = 1f
        v.visibility = View.GONE
        XLog.i("hold released")
    }

    // ---------------------------------------------------------------- trigger

    @android.annotation.SuppressLint("MissingPermission") // Host privilege checked below, not in our APK.
    private fun watchKeyguard(app: Context) {
        if (app.checkSelfPermission("android.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE") !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            XLog.i("keyguard listener unavailable, rely on launcher hooks")
            return
        }
        try {
            val manager = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            ignoreUntil = SystemClock.uptimeMillis() + IGNORE_AFTER_REGISTER_MS
            val exec = java.util.concurrent.Executor { r -> main.post(r) }
            manager.addKeyguardLockedStateListener(exec) { isLocked -> onLockState(isLocked) }
            XLog.i("keyguard listener ok locked=${manager.isKeyguardLocked}")
        } catch (t: Throwable) {
            XLog.i("keyguard listener failed: $t, rely on launcher hooks")
        }
    }

    /**
     * Arm a lock cycle. Arming is independent of the previous cycle's outcome, so a missed
     * unlock report can never leave the layer permanently disarmed.
     */
    private fun arm(now: Long, why: String) {
        armed = true
        cycleStart = now
        locked = true
        XLog.i("lock cycle armed ($why)")
    }

    private fun unlocked() {
        unlockAt = SystemClock.uptimeMillis()
        locked = false
    }

    private fun onLockState(isLocked: Boolean) {
        val now = SystemClock.uptimeMillis()
        // The listener replays the current state right after registration.
        if (now < ignoreUntil) {
            locked = isLocked
            if (isLocked) arm(now, "register")
            return
        }
        if (isLocked) {
            // Fingerprint auth reports a short relock right after the unlock it belongs to.
            if (now - unlockAt < RELOCK_GUARD_MS && interactive()) {
                XLog.i("relock blip ignored (${now - unlockAt}ms into unlock)")
                return
            }
            if (!armed) {
                arm(now, "keyguard")
            } else {
                locked = true
            }
            holdLockWallpaper()
            return
        }
        unlocked()
        if (tryPlay("keyguard_gone")) return
        if (now - cycleStart >= SETTLE_LOCK_MS) {
            armed = false
            releaseHold()
        }
    }

    private fun keyguardLocked(): Boolean = try {
        km?.isKeyguardLocked ?: false
    } catch (t: Throwable) {
        false
    }

    private fun interactive(): Boolean = try {
        val pm = ctx?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        pm?.isInteractive ?: true
    } catch (t: Throwable) {
        true
    }

    private fun canDraw(): Boolean {
        val v = view ?: return false
        val d = decor ?: return false
        return v.isAttachedToWindow && d.isShown
    }

    /**
     * Plays once per lock cycle, for the first unlock signal inside [PLAY_WINDOW_MS].
     *
     * [force] is used by the launcher's unlock animation hooks: they fire before the keyguard
     * state settles, so the keyguard and window-visibility gates are skipped there.
     */
    private fun tryPlay(source: String, force: Boolean = false): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now < ignoreUntil) return false
        if (!armed) return false
        if (now - cycleStart < SETTLE_LOCK_MS) return false
        if (unlockAt == 0L || now - unlockAt > PLAY_WINDOW_MS) return false
        if (!force && keyguardLocked()) return false
        if (!interactive()) {
            XLog.i("unlock ignored: not interactive ($source)")
            return false
        }
        if (!force && !canDraw()) {
            XLog.i("unlock ignored: launcher not shown ($source)")
            return false
        }
        if (!ready) {
            XLog.i("play delayed: bitmaps not ready ($source)")
            return false
        }
        armed = false
        if (!cfg.enabled) return false
        fire(source)
        return true
    }

    private fun fire(source: String) {
        val v = view ?: return
        if (!ready) {
            XLog.i("play skipped: bitmaps not ready from=$source")
            return
        }
        val gen = ++generation
        playing = true
        holding = false
        holdPending = false
        v.animate().cancel()
        v.scale = cfg.scale
        v.warp = cfg.warp
        v.soft = cfg.soft
        v.grain = cfg.grain
        v.bleed = cfg.bleed
        v.radial = cfg.radial
        v.seed = if (cfg.seed == 0f) (SystemClock.elapsedRealtime() % 997L) / 97f else cfg.seed
        v.tint = tint
        v.progress = 0f
        v.alpha = 1f
        v.visibility = View.VISIBLE
        try {
            v.play(cfg.durationMs.toLong()) {
                if (gen != generation) return@play
                playing = false
                if (!v.isAttachedToWindow) {
                    v.visibility = View.GONE
                    v.alpha = 1f
                    return@play
                }
                v.animate().alpha(0f).setDuration(140L).withEndAction {
                    if (gen != generation) return@withEndAction
                    v.visibility = View.GONE
                    v.alpha = 1f
                }.start()
            }
        } catch (t: Throwable) {
            XLog.i("play failed: $t")
            playing = false
            v.visibility = View.GONE
            v.alpha = 1f
        }
        XLog.i(
            "layer play from=$source dur=${cfg.durationMs} locked=${keyguardLocked()} " +
                "shown=${decor?.isShown} shader=${v.shaderReady}",
        )
    }
}
