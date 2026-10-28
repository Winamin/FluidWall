package com.txf.fluidwall.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { LogStore.init(it) }
        return true
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
