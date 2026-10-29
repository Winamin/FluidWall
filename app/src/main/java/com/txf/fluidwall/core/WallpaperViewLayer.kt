package com.txf.fluidwall.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Looper
import android.view.Surface
import android.view.SurfaceControl
import android.view.View

/**
 * An input-free child of the wallpaper window, drawn with the existing View/AGSL renderer.
 * All calls belong on the main thread. The caller drives frames; this View has no ViewRoot.
 * The wallpaper parent and bitmap are borrowed, and their lifetimes remain with the caller.
 */
class WallpaperViewLayer(context: Context) {
    init {
        checkMainThread()
    }

    private val view = FluidTransitionView(context).apply {
        ownBitmaps = false
        revealUnderlying = true
    }
    private var parent: SurfaceControl? = null
    private var control: SurfaceControl? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var visible = false
    private var lockBitmap: Bitmap? = null

    val ready: Boolean
        get() = parent?.isValid == true && control?.isValid == true && surface?.isValid == true

    val shaderReady: Boolean get() = view.shaderReady

    var lastFailure: String? = null
        private set

    /** Keeps an existing matching layer; a new parent or size gets a fresh hidden buffer queue. */
    fun attach(parent: SurfaceControl, w: Int, h: Int) {
        checkMainThread()
        try {
            check(parent.isValid) { "Wallpaper parent SurfaceControl is not ready" }
            require(w in 1..8192 && h in 1..8192) { "Invalid wallpaper surface size ${w}x$h" }
            if (this.parent === parent && width == w && height == h && ready) {
                lastFailure = null
                return
            }
            releaseSurface()
            this.parent = parent
            width = w
            height = h
            val child = SurfaceControl.Builder()
                .setName("FluidWall View transition")
                .setParent(parent)
                .setBufferSize(w, h)
                .setFormat(PixelFormat.RGBA_8888)
                .setOpaque(false)
                .setHidden(true)
                .build()
            control = child
            surface = Surface(child)
            check(ready) { "Wallpaper child surface could not be created" }
            SurfaceControl.Transaction().use { transaction ->
                transaction.setLayer(child, 1)
                    .setPosition(child, 0f, 0f)
                    .setCrop(child, Rect(0, 0, w, h))
                    .setVisibility(child, false)
                    .apply()
            }
            view.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, w, h)
            lastFailure = null
        } catch (t: Throwable) {
            releaseSurface()
            fail("Attach wallpaper View layer", t)
        }
    }

    /** Posts one complete hardware Canvas frame, then reveals a previously hidden layer. */
    fun draw(lock: Bitmap, config: FluidConfig, progress: Float, tint: Int, seed: Float): Boolean {
        checkMainThread()
        return try {
            require(progress.isFinite() && seed.isFinite()) { "Non-finite progress or seed" }
            if (!config.enabled || progress >= 1f) {
                hideSurface()
                lastFailure = null
                return true
            }
            check(ready) { "Wallpaper View layer is not attached to a valid surface" }
            check(!lock.isRecycled) { "Lock wallpaper bitmap was recycled" }
            if (lockBitmap !== lock) {
                view.setBitmaps(lock, lock)
                lockBitmap = lock
            }
            view.scale = config.scale
            view.warp = config.warp
            view.soft = config.soft
            view.grain = config.grain
            view.bleed = config.bleed
            view.radial = config.radial
            view.seed = seed
            view.tint = tint
            view.progress = progress
            check(view.shaderReady) { "Fluid AGSL shader is not ready" }

            val target = checkNotNull(surface)
            val child = checkNotNull(control)
            val canvas = target.lockHardwareCanvas()
            var frameDrawn = false
            try {
                check(canvas.isHardwareAccelerated) { "Wallpaper Canvas is not hardware accelerated" }
                // Hardware Canvas buffers are not preserved. Clear the whole buffer every frame.
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                view.draw(canvas)
                check(view.shaderReady) { "Fluid AGSL shader failed while drawing" }
                frameDrawn = true
            } finally {
                try {
                    // Do not submit a partially drawn or fallback image when AGSL failed.
                    if (!frameDrawn) canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                } finally {
                    target.unlockCanvasAndPost(canvas)
                }
            }
            if (!visible) {
                SurfaceControl.Transaction().use { transaction ->
                    transaction.setVisibility(child, true).apply()
                }
                visible = true
            }
            lastFailure = null
            true
        } catch (t: Throwable) {
            fail("Draw wallpaper View layer", t)
            false
        }
    }

    fun hide() {
        checkMainThread()
        try {
            hideSurface()
        } catch (t: Throwable) {
            releaseSurface()
            fail("Hide wallpaper View layer", t)
        }
    }

    /** Releases only this layer and the View's shader references; the instance can attach again. */
    fun release() {
        checkMainThread()
        releaseSurface()
        view.release()
        lockBitmap = null
    }

    private fun hideSurface() {
        control?.takeIf { it.isValid }?.let { child ->
            SurfaceControl.Transaction().use { transaction ->
                transaction.setVisibility(child, false).apply()
            }
        }
        visible = false
    }

    private fun releaseSurface() {
        val oldSurface = surface
        val oldControl = control
        surface = null
        control = null
        parent = null
        width = 0
        height = 0
        visible = false
        // Detach only our child. Never release or change the borrowed wallpaper parent.
        if (oldControl?.isValid == true) {
            runCatching {
                SurfaceControl.Transaction().use { transaction ->
                    transaction.setVisibility(oldControl, false).reparent(oldControl, null).apply()
                }
            }
        }
        runCatching { oldSurface?.release() }
        runCatching { oldControl?.release() }
    }

    private fun fail(operation: String, cause: Throwable) {
        // Allow a later attempt to rebuild a shader that failed inside View.onDraw.
        lockBitmap = null
        runCatching { hideSurface() }.onFailure { releaseSurface() }
        lastFailure = "$operation: ${cause.javaClass.simpleName}: ${cause.message ?: "unknown error"}"
    }

    private fun checkMainThread() {
        check(Looper.myLooper() === Looper.getMainLooper()) { "WallpaperViewLayer requires the main thread" }
    }
}
