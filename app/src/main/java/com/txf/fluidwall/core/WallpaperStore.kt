package com.txf.fluidwall.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.io.File

object WallpaperStore {

    const val LOCK = "/sdcard/FluidWall/lock.png"
    const val HOME = "/sdcard/FluidWall/home.png"
    const val ACTION_EXPORT = "com.txf.fluidwall.EXPORT"
    const val ACTION_CACHE_UPDATED = "com.txf.fluidwall.CACHE_UPDATED"

    private const val MAX_DIM = 1080
    private const val CHUNK = 48 * 1024
    private const val QUALITY = 88
    private const val PUSH_RETRIES = 6

    private val wallpaperPackages = arrayOf(
        "com.miui.miwallpaper",
        "com.miui.wallpaper",
        "com.android.wallpaper",
    )

    /**
     * Called from the wallpaper process, which can read wallpapers but cannot write to shared
     * storage. Pixels are pushed into this module's private files through its provider, where
     * the settings app can read them.
     *
     * The provider may not be published yet when the wallpaper process starts right after an
     * install or a reboot, so the transfer is retried with backoff instead of giving up on the
     * first "Unknown authority" error.
     */
    fun exportViaApp(ctx: Context) {
        try {
            val lock = WallpaperBridge.lockBitmap(ctx, MAX_DIM)
            val home = WallpaperBridge.homeBitmap(ctx, MAX_DIM)
            if (lock == null && home == null) {
                XLog.i("export skipped: no wallpaper access")
                HookStatus.report(
                    ctx,
                    HookStatus.WALLPAPER_EXPORT,
                    false,
                    "无法读取锁屏和桌面壁纸",
                )
                return
            }
            val exported = ArrayList<String>()
            if (lock != null) {
                if (push(ctx, "lock", lock)) exported.add("lock") else XLog.i("export: lock failed")
            } else {
                XLog.i("export: lock null")
            }
            if (home != null) {
                if (push(ctx, "home", home)) exported.add("home") else XLog.i("export: home failed")
            } else {
                XLog.i("export: home null")
            }
            val ok = exported.isNotEmpty()
            HookStatus.report(
                ctx,
                HookStatus.WALLPAPER_EXPORT,
                ok,
                if (ok) "已导出 ${exported.joinToString("/")}" else "壁纸编码或写入失败",
            )
            if (ok) {
                for (pkg in arrayOf("com.miui.home", "com.android.launcher3", "com.android.launcher")) {
                    ctx.sendBroadcast(Intent(ACTION_CACHE_UPDATED).setPackage(pkg))
                }
            }
        } catch (t: Throwable) {
            XLog.i("exportViaApp failed: $t")
            HookStatus.report(
                ctx,
                HookStatus.WALLPAPER_EXPORT,
                false,
                "导出异常: $t",
            )
        }
    }

    /**
     * Ask the wallpaper process to export fresh pixels. The receiver lives in the wallpaper
     * process and is registered by the Xposed hook; the settings app uses this when its cache is
     * empty or the user taps "重新检测".
     */
    fun requestExport(ctx: Context) {
        for (pkg in wallpaperPackages) {
            try {
                ctx.sendBroadcast(Intent(ACTION_EXPORT).setPackage(pkg))
            } catch (_: Throwable) {
            }
        }
    }

    private fun push(ctx: Context, name: String, bmp: Bitmap): Boolean {
        val bytes = try {
            val bos = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, bos)) return false
            bos.toByteArray()
        } catch (t: Throwable) {
            XLog.i("encode $name failed: $t")
            return false
        } finally {
            bmp.recycle()
        }
        return pushBytes(ctx, name, bytes)
    }

    private fun pushBytes(ctx: Context, name: String, bytes: ByteArray): Boolean {
        val uri = Uri.parse("content://${FluidConfig.AUTHORITY}")
        var lastError: Throwable? = null
        repeat(PUSH_RETRIES) { attempt ->
            try {
                if (ctx.contentResolver.call(uri, "begin", name, null)?.getBoolean("ok") == true) {
                    var off = 0
                    var complete = true
                    while (off < bytes.size) {
                        val n = minOf(CHUNK, bytes.size - off)
                        val bundle = Bundle().apply {
                            putByteArray("data", bytes.copyOfRange(off, off + n))
                        }
                        if (ctx.contentResolver.call(uri, "chunk", name, bundle)?.getBoolean("ok") != true) {
                            XLog.i("push $name: chunk refused at $off")
                            complete = false
                            break
                        }
                        off += n
                    }
                    if (complete &&
                        ctx.contentResolver.call(uri, "end", name, null)?.getBoolean("ok") == true
                    ) {
                        XLog.i("pushed $name ${bytes.size / 1024}KB")
                        return true
                    }
                    abort(ctx, name)
                }
            } catch (t: Throwable) {
                lastError = t
                abort(ctx, name)
            }
            if (attempt < PUSH_RETRIES - 1) {
                XLog.i("push $name retry ${attempt + 1}: ${lastError ?: "provider not ready"}")
                try {
                    Thread.sleep(1000L * (attempt + 1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        XLog.i("push $name failed after $PUSH_RETRIES attempts: $lastError")
        return false
    }

    private fun abort(ctx: Context, name: String) {
        try {
            ctx.contentResolver.call(
                Uri.parse("content://${FluidConfig.AUTHORITY}"),
                "abort",
                name,
                null,
            )
        } catch (_: Throwable) {
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

    /**
     * Reads the cache even when the caller is another process (the launcher). The provider
     * serves the app's private file through openFile(), which is safe because the provider is
     * exported for exactly this cross-process fallback.
     */
    fun loadRemote(ctx: Context, name: String, maxDim: Int = MAX_DIM): Bitmap? {
        loadLocal(ctx, name, maxDim)?.let { return it }
        return try {
            val uri = Uri.parse("content://${FluidConfig.AUTHORITY}/wallpaper/$name")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val o = BitmapFactory.Options()
                o.inPreferredConfig = Bitmap.Config.ARGB_8888
                val b = BitmapFactory.decodeStream(input, null, o) ?: return@use null
                scale(b, maxDim)
            }
        } catch (t: Throwable) {
            XLog.i("loadRemote $name failed: $t")
            null
        }
    }

    fun hasLocal(ctx: Context): Boolean = try {
        val d = File(ctx.filesDir, "wallpaper")
        listOf("lock.jpg", "home.jpg").any { File(d, it).length() > 0L }
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
        return scale(b, maxDim)
    }

    private fun scale(b: Bitmap, maxDim: Int): Bitmap {
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
