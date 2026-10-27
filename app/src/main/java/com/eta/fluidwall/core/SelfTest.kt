package com.eta.fluidwall.core

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.io.File
import java.io.FileOutputStream

object SelfTest {

    private const val W = 540
    private const val H = 1200

    fun run(ctx: Context) {
        Thread({
            val sb = StringBuilder()
            try {
                val home = WallpaperBridge.homeBitmap(ctx, 256)
                val lock = WallpaperBridge.lockBitmap(ctx, 256)
                sb.append("home=").append(home?.let { "${it.width}x${it.height}" } ?: "null")
                sb.append(" lock=").append(lock?.let { "${it.width}x${it.height}" } ?: "null")
                val seed = lock ?: home
                if (seed != null) {
                    val pal = ColorKMeans.extract(seed)
                    sb.append(" dominant=#").append("%06X".format(pal.dominant and 0xFFFFFF))
                    sb.append(" tint=#").append("%06X".format(pal.tint and 0xFFFFFF))
                    sb.append(" colors=").append(pal.core.size)
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    val dummy = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    val bs = BitmapShader(dummy, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    val rs = RuntimeShader(FluidShader.AGSL)
                    rs.setInputShader("uLock", bs)
                    rs.setInputShader("uHome", bs)
                    rs.setFloatUniform("uSize", 8f, 8f)
                    rs.setFloatUniform("uProgress", 0.5f)
                    rs.setFloatUniform("uScale", 3.2f)
                    rs.setFloatUniform("uWarp", 0.05f)
                    rs.setFloatUniform("uSoft", 0.16f)
                    rs.setFloatUniform("uGrain", 0.01f)
                    rs.setFloatUniform("uBleed", 0.4f)
                    rs.setFloatUniform("uRadial", 0.3f)
                    rs.setFloatUniform("uSeed", 0.4f)
                    rs.setFloatUniform("uTint", 0.4f, 0.6f, 1f)
                    sb.append(" agsl=compiled")
                }
                sb.append(" ok")
            } catch (t: Throwable) {
                sb.append(" FAIL ").append(t)
            }
            sb.append(" entry=").append(checkEntry(ctx))
            XLog.i("selftest $sb")
            capture(ctx, 0.55f, 3.2f, 0.06f, 0.016f)
        }, "fluid-selftest").start()
    }

    fun capture(ctx: Context, progress: Float, scale: Float = 3.2f, warp: Float = 0.06f, grain: Float = 0.016f) {
        val main = Handler(Looper.getMainLooper())
        main.post {
            var vd: VirtualDisplay? = null
            var pres: Presentation? = null
            try {
                val dm = ctx.getSystemService(DisplayManager::class.java)
                vd = dm.createVirtualDisplay("fluid-render", W, H, 320, null, 0)
                val display = vd.display
                val view = FluidTransitionView(ctx)
                view.setBitmaps(WallpaperBridge.demoLock(W, H), WallpaperBridge.demoHome(W, H))
                view.scale = scale
                view.soft = 0.18f
                view.warp = warp
                view.bleed = 0.5f
                view.radial = 0.32f
                view.grain = grain
                view.tint = 0xFF7A6BFF.toInt()
                view.progress = progress
                val p = Presentation(ctx, display)
                pres = p
                p.setContentView(view)
                p.show()

                val outDir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "preview")
                outDir.mkdirs()
                val tag = (progress * 100).toInt()
                main.postDelayed({
                    val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
                    try {
                        PixelCopy.request(p.window!!, bmp, { result ->
                            if (result == PixelCopy.SUCCESS) {
                                val f = File(outDir, "frame_$tag.png")
                                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                XLog.i("render p=$progress shader=${view.shaderReady} ${analyze(bmp)} file=${f.absolutePath}")
                            } else {
                                XLog.i("render p=$progress pixelcopy=$result")
                            }
                            bmp.recycle()
                            try { pres?.dismiss() } catch (t: Throwable) {}
                            try { vd?.release() } catch (t: Throwable) {}
                            view.release()
                        }, main)
                    } catch (t: Throwable) {
                        XLog.i("render p=$progress pixelcopy-fail: $t")
                        bmp.recycle()
                        try { pres?.dismiss() } catch (e: Throwable) {}
                        try { vd?.release() } catch (e: Throwable) {}
                        view.release()
                    }
                }, 1200L)
            } catch (t: Throwable) {
                XLog.i("renderTest failed: $t")
                try { pres?.dismiss() } catch (e: Throwable) {}
                try { vd?.release() } catch (e: Throwable) {}
            }
        }
    }

    private fun checkEntry(ctx: Context): String {
        val apk = ctx.applicationInfo.sourceDir ?: return "no-apk"
        val names = ArrayList<String>()
        try {
            java.util.zip.ZipFile(apk).use { z ->
                z.getEntry("META-INF/xposed/java_init.list")?.let { e ->
                    z.getInputStream(e).bufferedReader().readLines()
                        .map { it.trim() }.filter { it.isNotEmpty() }.forEach { names.add(it) }
                }
                z.getEntry("assets/xposed_init")?.let { e ->
                    z.getInputStream(e).bufferedReader().readLines()
                        .map { it.trim() }.filter { it.isNotEmpty() }.forEach { names.add(it) }
                }
            }
            if (names.isEmpty()) return "no-entry-list"
            val cl = dalvik.system.PathClassLoader(apk, null, javaClass.classLoader)
            val sb = StringBuilder()
            for (n in names.distinct()) {
                val ok = try {
                    val c = Class.forName(n, false, cl)
                    c.getDeclaredConstructor().newInstance()
                    true
                } catch (t: Throwable) {
                    sb.append(" fail($n:$t)")
                    false
                }
                sb.append(if (ok) " loaded($n)" else "")
            }
            return sb.toString()
        } catch (t: Throwable) {
            return " err:$t"
        }
    }

    private fun analyze(img: Bitmap): String {
        val w = img.width
        val h = img.height
        val px = IntArray(w * h)
        img.getPixels(px, 0, w, 0, 0, w, h)
        var cool = 0
        var warm = 0
        var dark = 0
        var n = 0
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var i = 0
        while (i < px.size) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            sr += r; sg += g; sb += b
            when {
                r > b + 26 -> warm++
                b > r + 26 -> cool++
                r < 24 && g < 24 && b < 24 -> dark++
            }
            n++
            i += 37
        }
        if (n == 0) return "empty"
        val pct = { v: Int -> v * 100 / n }
        return "samples=$n warm=" + pct(warm) + "% cool=" + pct(cool) + "% dark=" + pct(dark) +
            "% mean=" + "%02X%02X%02X".format((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
    }
}
