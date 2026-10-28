package com.txf.fluidwall.core

import android.content.Context
import android.net.Uri
import org.json.JSONObject

data class FluidConfig(
    val enabled: Boolean = true,
    val durationMs: Int = 700,
    val scale: Float = 3.2f,
    val warp: Float = 0.055f,
    val soft: Float = 0.16f,
    val grain: Float = 0.012f,
    val bleed: Float = 0.45f,
    val radial: Float = 0.30f,
    val seed: Float = 0f,
) {
    fun toJson(): String = JSONObject()
        .put("enabled", enabled)
        .put("durationMs", durationMs)
        .put("scale", scale.toDouble())
        .put("warp", warp.toDouble())
        .put("soft", soft.toDouble())
        .put("grain", grain.toDouble())
        .put("bleed", bleed.toDouble())
        .put("radial", radial.toDouble())
        .put("seed", seed.toDouble())
        .toString()

    companion object {
        const val PACKAGE = "com.txf.fluidwall"
        const val AUTHORITY = "com.txf.fluidwall.config"

        fun parse(raw: String?): FluidConfig {
            if (raw.isNullOrBlank()) return FluidConfig()
            return try {
                val o = JSONObject(raw)
                val d = FluidConfig()
                FluidConfig(
                    enabled = o.optBoolean("enabled", d.enabled),
                    durationMs = o.optInt("durationMs", d.durationMs).coerceIn(250, 4000),
                    scale = o.optDouble("scale", d.scale.toDouble()).toFloat().coerceIn(1f, 10f),
                    warp = o.optDouble("warp", d.warp.toDouble()).toFloat().coerceIn(0f, 0.3f),
                    soft = o.optDouble("soft", d.soft.toDouble()).toFloat().coerceIn(0.02f, 0.8f),
                    grain = o.optDouble("grain", d.grain.toDouble()).toFloat().coerceIn(0f, 0.1f),
                    bleed = o.optDouble("bleed", d.bleed.toDouble()).toFloat().coerceIn(0f, 2f),
                    radial = o.optDouble("radial", d.radial.toDouble()).toFloat().coerceIn(0f, 1f),
                    seed = o.optDouble("seed", 0.0).toFloat(),
                )
            } catch (t: Throwable) {
                FluidConfig()
            }
        }

        fun read(ctx: Context): FluidConfig = try {
            val b = ctx.contentResolver.call(
                Uri.parse("content://$AUTHORITY"),
                "get", null, null,
            )
            parse(b?.getString("json"))
        } catch (t: Throwable) {
            FluidConfig()
        }
    }
}

object ConfigStore {
    private const val PREFS = "fluidwall"
    private const val KEY = "config"

    fun load(ctx: Context): FluidConfig = try {
        FluidConfig.parse(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
    } catch (t: Throwable) {
        FluidConfig()
    }

    fun save(ctx: Context, cfg: FluidConfig) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, cfg.toJson()).apply()
        } catch (t: Throwable) {
        }
    }
}
