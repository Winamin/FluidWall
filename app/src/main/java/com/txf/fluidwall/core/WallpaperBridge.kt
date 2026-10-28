package com.txf.fluidwall.core

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import kotlin.math.max
import kotlin.math.min

object WallpaperBridge {

    private const val MAX_DIM = 1440

    @Volatile
    private var accessWarned = false

    /**
     * Android 14+ no longer lets a normal app read the real wallpaper through WallpaperManager.
     * getWallpaperFile()/getDrawable() are guarded by READ_WALLPAPER_INTERNAL (signature) or
     * MANAGE_EXTERNAL_STORAGE, and throw SecurityException otherwise. The launcher and the
     * wallpaper process both hold one of those, the settings app holds neither, so it must use
     * the cache exported by the wallpaper process instead of retrying a doomed call.
     */
    fun canReadSystem(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT < 33) {
            true
        } else {
            ctx.checkSelfPermission("android.permission.READ_WALLPAPER_INTERNAL") ==
                PackageManager.PERMISSION_GRANTED ||
                Environment.isExternalStorageManager()
        }
    } catch (_: Throwable) {
        false
    }

    fun lockBitmap(ctx: Context, maxDim: Int = MAX_DIM): Bitmap? =
        fullRes(ctx, WallpaperManager.FLAG_LOCK, maxDim)
            ?: drawableToBitmap(lockDrawable(ctx), maxDim)

    // canReadSystem checks the host process's privileges; the settings APK cannot request them.
    @SuppressLint("MissingPermission")
    fun homeBitmap(ctx: Context, maxDim: Int = MAX_DIM): Bitmap? {
        if (!canReadSystem(ctx)) {
            warnOnce("system wallpaper access unavailable, using exported cache")
            return null
        }
        return try {
            fullRes(ctx, WallpaperManager.FLAG_SYSTEM, maxDim)
                ?: drawableToBitmap(WallpaperManager.getInstance(ctx).drawable, maxDim)
        } catch (t: Throwable) {
            if (!isAccessDenied(t)) XLog.i("homeBitmap failed: $t")
            null
        }
    }

    /** Full resolution copy straight from the wallpaper service, avoids the downscaled drawable. */
    @SuppressLint("MissingPermission") // Checked by canReadSystem, including the all-files app-op.
    fun fullRes(ctx: Context, which: Int, maxDim: Int): Bitmap? {
        if (!canReadSystem(ctx)) {
            warnOnce("system wallpaper access unavailable, using exported cache")
            return null
        }
        return try {
            val wm = WallpaperManager.getInstance(ctx)
            wm.getWallpaperFile(which)?.use { pfd ->
                val bmp = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
                if (bmp == null) null else scaleDown(bmp, maxDim)
            }
        } catch (t: Throwable) {
            if (!isAccessDenied(t)) XLog.i("fullRes($which) failed: $t")
            null
        }
    }

    fun scaleDown(bmp: Bitmap, maxDim: Int): Bitmap {
        val m = max(bmp.width, bmp.height)
        if (m <= maxDim) return bmp
        val s = maxDim.toFloat() / m
        val out = Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * s).toInt().coerceAtLeast(1),
            (bmp.height * s).toInt().coerceAtLeast(1),
            true,
        )
        if (out !== bmp) bmp.recycle()
        return out
    }

    @SuppressLint("MissingPermission") // Uses the same host permission gate as fullRes.
    fun lockDrawable(ctx: Context): Drawable? {
        if (Build.VERSION.SDK_INT < 34 || !canReadSystem(ctx)) return null
        return try {
            WallpaperManager.getInstance(ctx).getDrawable(WallpaperManager.FLAG_LOCK)
        } catch (t: Throwable) {
            if (!isAccessDenied(t)) XLog.i("lockDrawable failed: $t")
            null
        }
    }

    private fun isAccessDenied(t: Throwable): Boolean =
        t is SecurityException || t.cause is SecurityException

    private fun warnOnce(message: String) {
        if (accessWarned) return
        accessWarned = true
        XLog.i(message)
    }

    fun blurred(src: Bitmap, factor: Int = 10): Bitmap {
        val w = max(1, src.width / factor)
        val h = max(1, src.height / factor)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(small, null, Rect(0, 0, out.width, out.height), p)
        if (small !== src) small.recycle()
        return out
    }

    fun drawableToBitmap(d: Drawable?, maxDim: Int): Bitmap? {
        if (d == null) return null
        var iw = d.intrinsicWidth
        var ih = d.intrinsicHeight
        if (iw <= 0 || ih <= 0) { iw = 1080; ih = 2400 }
        val s = min(1f, maxDim.toFloat() / max(iw, ih))
        val w = max(1, (iw * s).toInt())
        val h = max(1, (ih * s).toInt())
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, w, h)
            d.draw(c)
            bmp
        } catch (t: Throwable) {
            XLog.i("drawableToBitmap failed: $t")
            null
        }
    }

    fun demoLock(w: Int = 720, h: Int = 1600): Bitmap = demo(
        w, h,
        intArrayOf(0xFF101A3A.toInt(), 0xFF2B3E8F.toInt(), 0xFF1B6E7A.toInt()),
        intArrayOf(0xFF4C6BFF.toInt(), 0xFF28C7C7.toInt()),
    )

    fun demoHome(w: Int = 720, h: Int = 1600): Bitmap = demo(
        w, h,
        intArrayOf(0xFF2A1030.toInt(), 0xFF8A2B5A.toInt(), 0xFFE06A3B.toInt()),
        intArrayOf(0xFFFF8A5C.toInt(), 0xFFFFC46B.toInt()),
    )

    private fun demo(w: Int, h: Int, base: IntArray, dots: IntArray): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), base, null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val centers = floatArrayOf(0.25f, 0.3f, 0.72f, 0.22f, 0.6f, 0.78f)
        for (i in dots.indices) {
            val cx = centers[(i * 2) % centers.size] * w
            val cy = centers[(i * 2 + 1) % centers.size] * h
            p.shader = RadialGradient(
                cx, cy, w * 0.9f,
                intArrayOf(dots[i], dots[i] and 0x00FFFFFF),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            c.drawCircle(cx, cy, w * 0.9f, p)
        }
        p.shader = null
        return bmp
    }
}
