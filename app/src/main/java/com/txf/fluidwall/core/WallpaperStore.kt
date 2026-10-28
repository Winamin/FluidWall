package com.txf.fluidwall.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.io.File

object WallpaperStore {

    const val LOCK = "/sdcard/FluidWall/lock.png"
    const val HOME = "/sdcard/FluidWall/home.png"

    private const val MAX_DIM = 1080
    private const val CHUNK = 48 * 1024
    private const val QUALITY = 88

    /**
     * Called from the wallpaper process, which can read wallpapers but cannot write to shared
     * storage. Pixels are pushed into this module's private files through its provider, where
     * the settings app can read them.
     */
    fun exportViaApp(ctx: Context) {
        try {
            val lock = WallpaperBridge.lockBitmap(ctx, MAX_DIM)
            val home = WallpaperBridge.homeBitmap(ctx, MAX_DIM)
            if (lock == null && home == null) {
                XLog.i("export skipped: no wallpaper access")
                return
            }
            if (lock != null) push(ctx, "lock", lock) else XLog.i("export: lock null")
            if (home != null) push(ctx, "home", home) else XLog.i("export: home null")
        } catch (t: Throwable) {
            XLog.i("exportViaApp failed: $t")
        }
    }

    private fun push(ctx: Context, name: String, bmp: Bitmap) {
        val bytes = try {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, bos)
            bos.toByteArray()
        } catch (t: Throwable) {
            XLog.i("encode $name failed: $t")
            return
        } finally {
            bmp.recycle()
        }
        val uri = Uri.parse("content://${FluidConfig.AUTHORITY}")
        try {
            if (ctx.contentResolver.call(uri, "begin", name, null)?.getBoolean("ok") != true) {
                XLog.i("push $name: begin refused")
                return
            }
            var off = 0
            while (off < bytes.size) {
                val n = minOf(CHUNK, bytes.size - off)
                val bundle = Bundle().apply { putByteArray("data", bytes.copyOfRange(off, off + n)) }
                if (ctx.contentResolver.call(uri, "chunk", name, bundle)?.getBoolean("ok") != true) {
                    XLog.i("push $name: chunk refused at $off")
                    return
                }
                off += n
            }
            if (ctx.contentResolver.call(uri, "end", name, null)?.getBoolean("ok") == true) {
                XLog.i("pushed $name ${bytes.size / 1024}KB")
            }
        } catch (t: Throwable) {
            XLog.i("push $name failed: $t")
        }
    }

    /** Reads the cached wallpaper that the wallpaper process pushed into this app. */
    fun loadLocal(ctx: Context, name: String, maxDim: Int = MAX_DIM): Bitmap? = try {
        val f = File(File(ctx.filesDir, "wallpaper"), "$name.jpg")
        if (!f.exists() || f.length() == 0L) null else decode(f, maxDim)
    } catch (t: Throwable) {
        XLog.i("loadLocal $name failed: $t")
        null
    }

    fun hasLocal(ctx: Context): Boolean = try {
        val d = File(ctx.filesDir, "wallpaper")
        (d.listFiles()?.count { it.length() > 0L } ?: 0) > 0
    } catch (t: Throwable) {
        false
    }

    fun load(path: String, maxDim: Int = MAX_DIM): Bitmap? = try {
        val f = File(path)
        if (!f.exists() || f.length() == 0L) null else decode(f, maxDim)
    } catch (t: Throwable) {
        XLog.i("load failed $path: $t")
        null
    }

    private fun decode(f: File, maxDim: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inPreferredConfig = Bitmap.Config.ARGB_8888
        val b = BitmapFactory.decodeFile(f.absolutePath, o) ?: return null
        val m = maxOf(b.width, b.height)
        if (m <= maxDim) return b
        val s = maxDim.toFloat() / m
        val scaled = Bitmap.createScaledBitmap(
            b,
            (b.width * s).toInt().coerceAtLeast(1),
            (b.height * s).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== b) b.recycle()
        return scaled
    }

    fun stamp(): Long = maxOf(File(LOCK).lastModified(), File(HOME).lastModified())
}
