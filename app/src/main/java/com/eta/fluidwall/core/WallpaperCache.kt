package com.eta.fluidwall.core

import android.content.Context
import android.os.Bundle
import java.io.File
import java.io.FileOutputStream

/**
 * Stores wallpaper pixels pushed by the wallpaper process, which can read wallpapers but
 * cannot write to shared storage. Reuses the already registered config provider so the
 * manifest never changes.
 */
object WallpaperCache {

    private val open = HashMap<Int, FileOutputStream>()

    fun handle(ctx: Context, method: String, name: String?, extras: Bundle?): Bundle {
        val out = Bundle()
        val key = name ?: return out
        when (method) {
            "begin" -> {
                try {
                    val f = File(dir(ctx), "$key.jpg")
                    if (f.exists()) f.delete()
                    open[idOf(key)] = FileOutputStream(f, false)
                    out.putBoolean("ok", true)
                } catch (t: Throwable) {
                    XLog.i("cache begin failed: $t")
                }
            }

            "chunk" -> {
                val data = extras?.getByteArray("data")
                val stream = open[idOf(key)]
                if (data != null && stream != null) {
                    try {
                        stream.write(data)
                        out.putBoolean("ok", true)
                    } catch (t: Throwable) {
                        XLog.i("cache chunk failed: $t")
                    }
                }
            }

            "end" -> {
                try {
                    open.remove(idOf(key))?.close()
                    out.putBoolean("ok", true)
                    XLog.i("wallpaper cache stored $key")
                } catch (t: Throwable) {
                    XLog.i("cache end failed: $t")
                }
            }
        }
        return out
    }

    fun isCacheMethod(method: String): Boolean =
        method == "begin" || method == "chunk" || method == "end"

    private fun idOf(name: String): Int = if (name == "lock") 1 else 2

    private fun dir(ctx: Context): File = File(ctx.filesDir, "wallpaper").apply { mkdirs() }
}
