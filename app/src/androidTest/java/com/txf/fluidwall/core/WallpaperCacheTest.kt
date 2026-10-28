package com.txf.fluidwall.core

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WallpaperCacheTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(target.cacheDir, "wallpaper-regression")
    private val ctx = object : ContextWrapper(target) {
        override fun getFilesDir(): File = root
    }
    private val cached get() = File(root, "wallpaper/lock.jpg")

    @Before fun prepare() {
        root.mkdirs()
    }

    @After fun cleanup() {
        call("abort")
        root.deleteRecursively()
    }

    @Test fun completeTransferPublishesDecodableImage() {
        val bytes = jpeg(Color.RED)
        assertTrue(call("begin"))
        bytes.asList().chunked(64).forEach { assertTrue(chunk(it.toByteArray())) }
        assertFalse(WallpaperStore.hasLocal(ctx))
        assertNull(WallpaperStore.loadLocal(ctx, "lock"))
        assertTrue(call("end"))
        assertArrayEquals(bytes, cached.readBytes())
        val bitmap = WallpaperStore.loadLocal(ctx, "lock")!!
        assertEquals(8, bitmap.width)
        bitmap.recycle()
    }

    @Test fun interruptedReplacementPreservesPreviousWallpaper() {
        val previous = jpeg(Color.BLUE)
        cached.parentFile!!.mkdirs()
        cached.writeBytes(previous)
        assertTrue(call("begin"))
        assertTrue(chunk(jpeg(Color.RED).copyOfRange(0, 32)))
        assertArrayEquals(previous, cached.readBytes())
        assertTrue(call("abort"))
        assertArrayEquals(previous, cached.readBytes())
        assertFalse(call("end"))
    }

    @Test fun emptyOrInvalidTransferCannotReplacePreviousWallpaper() {
        val previous = jpeg(Color.BLUE)
        cached.parentFile!!.mkdirs()
        cached.writeBytes(previous)
        assertTrue(call("begin"))
        assertFalse(call("end"))
        assertTrue(call("begin"))
        assertTrue(chunk(byteArrayOf(1, 2, 3)))
        assertFalse(call("end"))
        assertArrayEquals(previous, cached.readBytes())
    }

    @Test fun unknownNamesAndUnstartedTransfersAreRejected() {
        assertFalse(call("end"))
        assertFalse(chunk(jpeg(Color.RED)))
        assertFalse(call("begin", "../unexpected"))
        assertFalse(call("begin", "unknown"))
        assertFalse(File(root, "unexpected.pending").exists())
    }

    @Test fun settingsAppUsesCacheWithoutSystemWallpaperPermission() {
        assertFalse(WallpaperBridge.canReadSystem(target))
        assertNull(WallpaperBridge.homeBitmap(target))
        assertNull(WallpaperBridge.lockBitmap(target))
    }

    private fun call(method: String, name: String = "lock") =
        WallpaperCache.handle(ctx, method, name, null).getBoolean("ok")

    private fun chunk(bytes: ByteArray) = WallpaperCache.handle(
        ctx, "chunk", "lock", Bundle().apply { putByteArray("data", bytes) },
    ).getBoolean("ok")

    private fun jpeg(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
