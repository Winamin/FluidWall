package com.txf.fluidwall.core

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Small cross-process status board.
 *
 * The wallpaper hooks run inside com.miui.miwallpaper, so the settings app cannot inspect them
 * directly. They report through the config provider and the settings screen reads the latest
 * snapshot to tell the user whether the wallpaper hook actually worked.
 */
object HookStatus {

    const val WALLPAPER_HOOK = "wallpaper_hook"
    const val WALLPAPER_EXPORT = "wallpaper_export"

    private const val PREFS = "fluidwall_diagnostics"
    private val areas = setOf(WALLPAPER_HOOK, WALLPAPER_EXPORT)
    private const val REMOTE_RETRIES = 6

    private val remote = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fluid-status").apply { isDaemon = true }
    }

    data class Entry(
        val ok: Boolean,
        val detail: String,
        val at: Long,
    ) {
        fun describe(): String {
            val state = if (ok) "正常" else "失败"
            val text = detail.ifBlank { "无详情" }
            return "$state · $text · ${formatTime(at)}"
        }
    }

    data class Snapshot(
        val wallpaperHook: Entry?,
        val wallpaperExport: Entry?,
    )

    fun report(ctx: Context?, area: String, ok: Boolean, detail: String) {
        if (ctx == null || area !in areas) return
        if (inModuleProcess()) {
            write(ctx, area, ok, detail)
            return
        }
        val app = ctx.applicationContext ?: ctx
        remote.execute {
            val extras = Bundle().apply {
                putBoolean("ok", ok)
                putString("detail", detail.take(500))
            }
            repeat(REMOTE_RETRIES) { attempt ->
                try {
                    val result = app.contentResolver.call(
                        Uri.parse("content://${FluidConfig.AUTHORITY}"),
                        "status",
                        area,
                        extras,
                    )
                    if (result?.getBoolean("ok") == true) return@execute
                } catch (_: Throwable) {
                    // A provider may be unavailable while the module app starts.
                }
                if (attempt < REMOTE_RETRIES - 1) {
                    try {
                        Thread.sleep(1000L * (attempt + 1))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@execute
                    }
                }
            }
            XLog.i("status report failed after $REMOTE_RETRIES attempts: $area")
        }
    }

    fun handle(ctx: Context, area: String?, extras: Bundle?): Bundle {
        val out = Bundle()
        if (area == null || area !in areas) return out
        write(ctx, area, extras?.getBoolean("ok") ?: false, extras?.getString("detail").orEmpty())
        out.putBoolean("ok", true)
        return out
    }

    fun read(ctx: Context): Snapshot = try {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Snapshot(
            wallpaperHook = readEntry(prefs, WALLPAPER_HOOK),
            wallpaperExport = readEntry(prefs, WALLPAPER_EXPORT),
        )
    } catch (_: Throwable) {
        Snapshot(null, null)
    }

    fun formatTime(at: Long): String {
        if (at <= 0L) return "从未"
        return try {
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(at))
        } catch (_: Throwable) {
            at.toString()
        }
    }

    private fun readEntry(
        prefs: android.content.SharedPreferences,
        area: String,
    ): Entry? {
        val at = prefs.getLong("$area.at", 0L)
        if (at <= 0L) return null
        return Entry(
            ok = prefs.getBoolean("$area.ok", false),
            detail = prefs.getString("$area.detail", null).orEmpty(),
            at = at,
        )
    }

    private fun write(ctx: Context, area: String, ok: Boolean, detail: String) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("$area.ok", ok)
                .putString("$area.detail", detail.take(500))
                .putLong("$area.at", System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun inModuleProcess(): Boolean = try {
        Application.getProcessName() == FluidConfig.PACKAGE
    } catch (_: Throwable) {
        false
    }
}
