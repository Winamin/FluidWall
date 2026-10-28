package com.txf.fluidwall.core

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import java.io.File
import java.io.FileOutputStream

/**
 * Stores wallpaper pixels pushed by the wallpaper process, which can read wallpapers but
 * cannot write to shared storage. Publish only complete images so a failed transfer never
 * destroys the previous wallpaper or exposes partial JPEGs to readers.
 */
object WallpaperCache {

    private val open = HashMap<String, FileOutputStream>()

    @Synchronized
    fun handle(ctx: Context, method: String, name: String?, extras: Bundle?): Bundle {
        val out = Bundle()
        val key = name?.takeIf { it == "lock" || it == "home" } ?: return out
        when (method) {
            "begin" -> {
                try {
                    close(key)
                    open[key] = FileOutputStream(File(dir(ctx), "$key.pending"), false)
                    out.putBoolean("ok", true)
                } catch (t: Throwable) {
                    XLog.i("cache begin failed: $t")
                }
            }

            "chunk" -> {
                val data = extras?.getByteArray("data")
                val stream = open[key]
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
                    if (key !in open) return out
                    close(key)
                    val pending = File(dir(ctx), "$key.pending")
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(pending.absolutePath, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                        !pending.renameTo(File(dir(ctx), "$key.jpg"))
                    ) {
                        pending.delete()
                        return out
                    }
                    out.putBoolean("ok", true)
                    XLog.i("wallpaper cache stored $key")
                } catch (t: Throwable) {
                    XLog.i("cache end failed: $t")
                }
            }

            "abort" -> {
                try {
                    close(key)
                    val f = File(dir(ctx), "$key.pending")
                    if (f.exists()) f.delete()
                    out.putBoolean("ok", true)
                } catch (t: Throwable) {
                    XLog.i("cache abort failed: $t")
                }
            }
        }
        return out
    }

    fun isCacheMethod(method: String): Boolean =
        method == "begin" || method == "chunk" || method == "end" || method == "abort"

    private fun close(key: String) {
        open.remove(key)?.close()
    }

    private fun dir(ctx: Context): File = File(ctx.filesDir, "wallpaper").apply { mkdirs() }
}
