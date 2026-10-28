package com.txf.fluidwall.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File

class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { LogStore.init(it) }
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx: Context = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (name != "lock" && name != "home") return null
        val f = File(File(ctx.filesDir, "wallpaper"), "$name.jpg")
        if (!f.exists() || f.length() == 0L) return null
        return try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (t: Throwable) {
            XLog.i("provider openFile $name failed: $t")
            null
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val json = currentJson()
        return MatrixCursor(arrayOf("json"), 1).apply { addRow(arrayOf<Any>(json)) }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx: Context = context ?: return Bundle()
        if (method == "log") {
            if (!arg.isNullOrBlank()) LogStore.appendRaw(ctx, arg)
            return Bundle().apply { putBoolean("ok", true) }
        }
        if (method == "status") {
            return HookStatus.handle(ctx, arg, extras)
        }
        if (WallpaperCache.isCacheMethod(method)) {
            return WallpaperCache.handle(ctx, method, arg, extras)
        }
        return Bundle().apply {
            putString("json", currentJson())
            putBoolean("alive", true)
        }
    }

    private fun currentJson(): String {
        val ctx: Context = context ?: return FluidConfig().toJson()
        return ConfigStore.load(ctx).toJson()
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/fluidwall-config"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
