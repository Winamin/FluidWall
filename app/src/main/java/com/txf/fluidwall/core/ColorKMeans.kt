package com.txf.fluidwall.core

import android.graphics.Bitmap
import android.graphics.Color
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object ColorKMeans {

    data class Palette(
        val dominant: Int,
        val core: IntArray,
        val tint: Int,
        val luminance: Float,
    ) {
        fun tintRgb(): FloatArray = floatArrayOf(
            Color.red(tint) / 255f,
            Color.green(tint) / 255f,
            Color.blue(tint) / 255f,
        )

        val isDark: Boolean get() = luminance < 0.45f
    }

    private const val SAMPLE = 88

    fun extract(src: Bitmap, k: Int = 6, iterations: Int = 12): Palette {
        val small = scaleDown(src, SAMPLE)
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()

        val n = px.size
        if (n == 0) return Palette(0xFF202020.toInt(), IntArray(1) { 0xFF202020.toInt() }, 0xFF4C8DFF.toInt(), 0.2f)

        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        val wt = FloatArray(n)
        var lumaSum = 0f
        for (i in 0 until n) {
            val c = px[i]
            val rr = Color.red(c).toFloat()
            val gg = Color.green(c).toFloat()
            val bb = Color.blue(c).toFloat()
            r[i] = rr; g[i] = gg; b[i] = bb
            val mx = max(rr, max(gg, bb))
            val mn = min(rr, min(gg, bb))
            val sat = if (mx <= 1f) 0f else (mx - mn) / mx
            val lum = (0.2126f * rr + 0.7152f * gg + 0.0722f * bb) / 255f
            wt[i] = 0.25f + 0.55f * sat + 0.2f * (1f - abs(lum - 0.5f) * 2f)
            lumaSum += lum
        }

        val kk = min(k, n)
        val cr = FloatArray(kk)
        val cg = FloatArray(kk)
        val cb = FloatArray(kk)
        val rnd = Random(20240117L)

        var best = 0
        var bestW = -1f
        for (i in 0 until n) if (wt[i] > bestW) { bestW = wt[i]; best = i }
        cr[0] = r[best]; cg[0] = g[best]; cb[0] = b[best]

        val dist = FloatArray(n) { Float.MAX_VALUE }
        for (ci in 1 until kk) {
            var sum = 0f
            for (i in 0 until n) {
                val dr = r[i] - cr[ci - 1]
                val dg = g[i] - cg[ci - 1]
                val db = b[i] - cb[ci - 1]
                val d = dr * dr + dg * dg + db * db
                if (d < dist[i]) dist[i] = d
                sum += dist[i] * wt[i]
            }
            var pick = rnd.nextInt(n)
            if (sum > 0f) {
                var target = rnd.nextFloat() * sum
                for (i in 0 until n) {
                    target -= dist[i] * wt[i]
                    if (target <= 0f) { pick = i; break }
                }
            }
            cr[ci] = r[pick]; cg[ci] = g[pick]; cb[ci] = b[pick]
        }

        val wsum = FloatArray(kk)
        val sr = FloatArray(kk)
        val sg = FloatArray(kk)
        val sb = FloatArray(kk)
        val assign = IntArray(n)

        repeat(iterations) {
            for (i in 0 until kk) { wsum[i] = 0f; sr[i] = 0f; sg[i] = 0f; sb[i] = 0f }
            for (i in 0 until n) {
                var bi = 0
                var bd = Float.MAX_VALUE
                for (ci in 0 until kk) {
                    val dr = r[i] - cr[ci]
                    val dg = g[i] - cg[ci]
                    val db = b[i] - cb[ci]
                    val d = dr * dr + dg * dg + db * db
                    if (d < bd) { bd = d; bi = ci }
                }
                assign[i] = bi
                val ww = wt[i]
                wsum[bi] += ww
                sr[bi] += r[i] * ww
                sg[bi] += g[i] * ww
                sb[bi] += b[i] * ww
            }
            for (ci in 0 until kk) {
                if (wsum[ci] > 0f) {
                    cr[ci] = sr[ci] / wsum[ci]
                    cg[ci] = sg[ci] / wsum[ci]
                    cb[ci] = sb[ci] / wsum[ci]
                }
            }
        }

        val order = (0 until kk).sortedByDescending { wsum[it] }
        val merged = ArrayList<IntArray>()
        val mergedW = ArrayList<Float>()
        for (idx in order) {
            if (wsum[idx] <= 0f) continue
            val color = Color.rgb(
                cr[idx].toInt().coerceIn(0, 255),
                cg[idx].toInt().coerceIn(0, 255),
                cb[idx].toInt().coerceIn(0, 255),
            )
            var dup = -1
            for (j in merged.indices) {
                if (colorDistance(merged[j][0], color) < 26f) { dup = j; break }
            }
            if (dup >= 0) {
                val wOld = mergedW[dup]
                val wNew = wsum[idx]
                merged[dup] = intArrayOf(blend(merged[dup][0], color, wNew / (wOld + wNew)))
                mergedW[dup] = wOld + wNew
            } else {
                merged.add(intArrayOf(color))
                mergedW.add(wsum[idx])
            }
        }
        if (merged.isEmpty()) merged.add(intArrayOf(0xFF202020.toInt()))

        val core = IntArray(merged.size) { merged[it][0] }
        val dominant = core[0]
        var tintC = core[0]
        var bestScore = -1f
        for (i in core.indices) {
            val c = core[i]
            val mx = max(Color.red(c), max(Color.green(c), Color.blue(c))).toFloat()
            val mn = min(Color.red(c), min(Color.green(c), Color.blue(c))).toFloat()
            val sat = if (mx <= 1f) 0f else (mx - mn) / mx
            val score = mergedW[i] * (0.3f + 0.7f * sat)
            if (score > bestScore) { bestScore = score; tintC = c }
        }
        val tint = boost(tintC)
        return Palette(dominant, core, tint, lumaSum / n)
    }

    private fun boost(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * 1.35f).coerceAtMost(1f)
        if (hsv[2] < 0.35f) hsv[2] = 0.6f
        if (hsv[1] < 0.12f) { hsv[1] = 0.28f }
        return Color.HSVToColor(hsv)
    }

    private fun colorDistance(a: Int, b: Int): Float {
        val dr = (Color.red(a) - Color.red(b)).toFloat()
        val dg = (Color.green(a) - Color.green(b)).toFloat()
        val db = (Color.blue(a) - Color.blue(b)).toFloat()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt().coerceIn(0, 255),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt().coerceIn(0, 255),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt().coerceIn(0, 255),
    )

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val m = max(w, h)
        if (m <= maxDim) return src
        val s = maxDim.toFloat() / m
        val nw = max(1, (w * s).toInt())
        val nh = max(1, (h * s).toInt())
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }
}
