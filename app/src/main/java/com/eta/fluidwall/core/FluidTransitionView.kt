package com.eta.fluidwall.core

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class FluidTransitionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val shaderPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()
    private val dstRect = RectF()

    private var runtime: RuntimeShader? = null
    private var lockBmp: Bitmap? = null
    private var homeBmp: Bitmap? = null
    private var lockShader: BitmapShader? = null
    private var homeShader: BitmapShader? = null
    private var animator: ValueAnimator? = null

    var progress: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    var scale = 3.2f
    var warp = 0.055f
    var soft = 0.16f
    var grain = 0.012f
    var bleed = 0.45f
    var radial = 0.30f
    var seed = 0f
    var tint = 0xFF6FA8FF.toInt()
    var ownBitmaps = true

    val shaderReady: Boolean get() = runtime != null

    fun setBitmaps(lock: Bitmap?, home: Bitmap?) {
        lockBmp = lock
        homeBmp = home ?: lock
        rebuild()
        invalidate()
    }

    fun setPaletteTint(color: Int) {
        tint = color
        invalidate()
    }

    fun play(durationMs: Long, onEnd: (() -> Unit)? = null) {
        animator?.cancel()
        progress = 0f
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = durationMs.coerceIn(150L, 5000L)
        a.interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
        a.addUpdateListener { progress = it.animatedValue as Float }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                onEnd?.invoke()
            }
        })
        animator = a
        a.start()
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    fun release() {
        stopAnimation()
        runtime = null
        lockShader = null
        homeShader = null
        if (ownBitmaps) {
            lockBmp?.recycle()
            homeBmp?.takeIf { it !== lockBmp }?.recycle()
        }
        lockBmp = null
        homeBmp = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        if (Build.VERSION.SDK_INT < 33) return
        val lock = lockBmp
        val home = homeBmp
        if (lock == null || home == null || width == 0 || height == 0) return
        if (lock.isRecycled || home.isRecycled) return
        try {
            val ls = BitmapShader(lock, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            ls.setLocalMatrix(coverMatrix(lock.width, lock.height, width, height))
            val hs = BitmapShader(home, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            hs.setLocalMatrix(coverMatrix(home.width, home.height, width, height))
            val rs = RuntimeShader(FluidShader.AGSL)
            rs.setInputShader("uLock", ls)
            rs.setInputShader("uHome", hs)
            rs.setFloatUniform("uSize", width.toFloat(), height.toFloat())
            lockShader = ls
            homeShader = hs
            runtime = rs
        } catch (t: Throwable) {
            runtime = null
            XLog.i("shader init failed: $t")
        }
    }

    private fun coverMatrix(bw: Int, bh: Int, w: Int, h: Int): Matrix {
        val s = max(w.toFloat() / bw, h.toFloat() / bh)
        val m = Matrix()
        m.setScale(s, s)
        m.postTranslate((w - bw * s) * 0.5f, (h - bh * s) * 0.5f)
        return m
    }

    override fun onDraw(canvas: Canvas) {
        val rs = runtime
        if (rs != null && lockShader != null && homeShader != null) {
            try {
                rs.setFloatUniform("uProgress", progress)
                rs.setFloatUniform("uScale", scale)
                rs.setFloatUniform("uWarp", warp)
                rs.setFloatUniform("uSoft", soft)
                rs.setFloatUniform("uGrain", grain)
                rs.setFloatUniform("uBleed", bleed)
                rs.setFloatUniform("uRadial", radial)
                rs.setFloatUniform("uSeed", seed)
                rs.setFloatUniform(
                    "uTint",
                    Color.red(tint) / 255f,
                    Color.green(tint) / 255f,
                    Color.blue(tint) / 255f,
                )
                shaderPaint.shader = rs
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shaderPaint)
                return
            } catch (t: Throwable) {
                XLog.i("shader draw failed: $t")
                runtime = null
            }
        }
        drawFallback(canvas)
    }

    private fun drawFallback(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        dstRect.set(0f, 0f, w, h)
        val lock = lockBmp
        val home = homeBmp
        if (lock != null && !lock.isRecycled) {
            canvas.drawBitmap(lock, null, dstRect, bitmapPaint)
        }
        if (home != null && !home.isRecycled) {
            val r = hypot(w, h) * 0.62f * progress
            canvas.save()
            clipPath.reset()
            clipPath.addCircle(w * 0.5f, h * 0.44f, max(r, 0.5f), Path.Direction.CW)
            canvas.clipPath(clipPath)
            canvas.drawBitmap(home, null, dstRect, bitmapPaint)
            canvas.restore()
            if (progress > 0.6f) {
                bitmapPaint.alpha = (((progress - 0.6f) / 0.4f) * 255f).toInt().coerceIn(0, 255)
                canvas.drawBitmap(home, null, dstRect, bitmapPaint)
                bitmapPaint.alpha = 255
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    fun frameSize(): Pair<Int, Int> = width to height

    companion object {
        fun clampDim(v: Int, minV: Int = 1): Int = min(max(v, minV), 8192)
    }
}
