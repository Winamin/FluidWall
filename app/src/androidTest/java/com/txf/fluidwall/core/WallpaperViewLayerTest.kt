package com.txf.fluidwall.core

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class WallpaperViewLayerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private lateinit var activity: WallpaperLayerTestActivity
    @get:Rule val testName = TestName()

    @Before fun launchFixture() {
        activity = instrumentation.startActivitySync(
            Intent(target, WallpaperLayerTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as WallpaperLayerTestActivity
        awaitMain("host SurfaceView") {
            activity.hasWindowFocus() && activity.surfaceReady &&
                activity.surfaceView.surfaceControl.isValid && activity.hostFailure == null
        }
    }

    @After fun closeFixture() {
        if (::activity.isInitialized) onMain { activity.finish() }
        instrumentation.waitForIdleSync()
    }

    @Test fun agslEndpointsAndMidpointCompositeAboveTheHostBelowIcons() {
        val scene = scene()
        val baseline = homeFrame("home", scene)
        try {
            attach()
            draw(0f)
            assertTrue("Production View must use AGSL", onMain { activity.viewLayer.shaderReady })
            frameWhen("p0-lock") { allLock(it, scene) }.also {
                assertIconsVisible(it, scene)
                it.recycle()
            }
            draw(0.55f)
            frameWhen("p055-midpoint") {
                val counts = midpointCounts(it, baseline, scene)
                counts.lock > 0 && counts.home > 0 && counts.blended > 0
            }.also {
                val counts = midpointCounts(it, baseline, scene)
                assertTrue("Middle frame must retain lock pixels: $counts", counts.lock > 0)
                assertTrue("Middle frame must reveal native home pixels: $counts", counts.home > 0)
                assertTrue("Middle frame must blend transparent edges: $counts", counts.blended > 0)
                assertIconsVisible(it, scene)
                it.recycle()
            }
            draw(1f)
            exactHomeFrame("p1-native-home", baseline, scene)
            assertTrue(onMain { activity.surfaceView.surfaceControl.isValid })
        } finally {
            baseline.recycle()
        }
    }

    @Test fun hideReleaseReattachAndDisabledKeepTheParentAndBitmapAlive() {
        val scene = scene()
        val baseline = homeFrame("home", scene)
        val parent = onMain { activity.surfaceView.surfaceControl }
        try {
            attach()
            draw(0f)
            frameWhen("before-hide") { allLock(it, scene) }.recycle()
            onMain { activity.viewLayer.hide() }
            exactHomeFrame("hidden", baseline, scene)
            assertTrue("hide must preserve the host", onMain { parent.isValid })

            draw(0f)
            frameWhen("before-release") { allLock(it, scene) }.recycle()
            onMain { activity.viewLayer.release() }
            assertFalse(onMain { activity.viewLayer.ready })
            assertFalse("Borrowed bitmap must not be recycled", onMain { activity.lockBitmap.isRecycled })
            assertTrue("release must preserve the host", onMain { parent.isValid })
            exactHomeFrame("released", baseline, scene)

            attach()
            draw(0f)
            frameWhen("reattached-lock") { allLock(it, scene) }.recycle()
            assertTrue(onMain { activity.viewLayer.shaderReady })
            draw(0f, enabled = false)
            exactHomeFrame("disabled", baseline, scene)
            assertTrue(onMain { parent.isValid && !activity.lockBitmap.isRecycled })
        } finally {
            baseline.recycle()
        }
    }

    @Test fun desktopButtonReceivesRealTouchesWhileTheLayerIsVisible() {
        val scene = scene()
        homeFrame("home", scene).recycle()
        attach()
        draw(0f)
        frameWhen("lock-with-icons") { allLock(it, scene) }.also {
            assertIconsVisible(it, scene)
            it.recycle()
        }
        val before = onMain { activity.clicks }
        val center = Point(scene.button.centerX(), scene.button.centerY())
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN,
            center.x.toFloat(), center.y.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(downTime, downTime + 80L, MotionEvent.ACTION_UP,
            center.x.toFloat(), center.y.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            instrumentation.sendPointerSync(down)
            SystemClock.sleep(80L)
            instrumentation.sendPointerSync(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        awaitMain("button click through wallpaper layer") { activity.clicks == before + 1 }
        assertEquals(before + 1, onMain { activity.clicks })
        frameWhen("after-button-touch") { allLock(it, scene) }.also {
            assertIconsVisible(it, scene)
            it.recycle()
        }
    }

    @Test fun surfaceResizeAndRecreationCanAttachAndDrawAgain() {
        val original = scene()
        homeFrame("original-home", original).recycle()
        attach()
        draw(0f)
        frameWhen("original-lock") { allLock(it, original) }.recycle()
        val changed = onMain { activity.surfaceChangedCount }
        val newWidth = original.surface.width() * 3 / 4
        val newHeight = original.surface.height() * 3 / 4
        onMain { activity.resizeSurface(newWidth, newHeight) }
        awaitMain("resized host surface") {
            activity.surfaceReady && activity.surfaceChangedCount > changed &&
                activity.surfaceView.holder.surfaceFrame.width() == newWidth &&
                activity.surfaceView.holder.surfaceFrame.height() == newHeight
        }
        assertFalse("Fixture releases the old layer on resize", onMain { activity.viewLayer.ready })
        verifyFreshSurface("resized")

        val creates = onMain { activity.surfaceCreatedCount }
        val destroys = onMain { activity.surfaceDestroyedCount }
        onMain { activity.surfaceView.visibility = View.INVISIBLE }
        awaitMain("host surface destruction") { activity.surfaceDestroyedCount > destroys && !activity.surfaceReady }
        assertFalse(onMain { activity.viewLayer.ready })
        onMain { activity.surfaceView.visibility = View.VISIBLE }
        awaitMain("host surface recreation") { activity.surfaceCreatedCount > creates && activity.surfaceReady }
        verifyFreshSurface("recreated")
    }

    @Test fun defaultPreviewStillTransitionsBetweenTwoBitmaps() {
        val scene = scene()
        homeFrame("home", scene).recycle()
        val home = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val preview = onMain {
            FluidTransitionView(activity).apply {
                ownBitmaps = false
                scale = 4f
                warp = 0f
                soft = 0.2f
                grain = 0f
                bleed = 0f
                radial = 0f
                seed = 1.25f
                setBitmaps(activity.lockBitmap, home)
                (activity.surfaceView.parent as ViewGroup).addView(this, 1, ViewGroup.LayoutParams(-1, -1))
            }
        }
        try {
            assertFalse("Existing preview must default to two-image rendering", onMain { preview.revealUnderlying })
            frameWhen("preview-lock") { allLock(it, scene) }.recycle()
            assertTrue("Existing preview must still use AGSL", onMain { preview.shaderReady })
            onMain { preview.progress = 0.55f }
            frameWhen("preview-midpoint") { frame ->
                val colors = scene.points.map { frame.getPixel(it.x, it.y) }
                colors.any { closeColor(it, Color.RED) } &&
                    colors.any { closeColor(it, Color.GREEN) } &&
                    colors.any { Color.red(it) in 6..249 && Color.green(it) in 6..249 }
            }.also {
                assertIconsVisible(it, scene)
                it.recycle()
            }
            onMain { preview.progress = 1f }
            frameWhen("preview-home") { frame ->
                scene.points.all { closeColor(frame.getPixel(it.x, it.y), Color.GREEN) }
            }.recycle()
            assertTrue(onMain { preview.shaderReady })
        } finally {
            onMain {
                (preview.parent as? ViewGroup)?.removeView(preview)
                preview.release()
            }
            home.recycle()
        }
    }

    private fun verifyFreshSurface(label: String) {
        val scene = scene()
        val baseline = homeFrame("$label-home", scene)
        try {
            attach()
            draw(0f)
            frameWhen("$label-lock") { allLock(it, scene) }.recycle()
            assertTrue(onMain { activity.viewLayer.shaderReady })
            draw(1f)
            exactHomeFrame("$label-finished", baseline, scene)
            assertTrue(onMain { activity.surfaceView.surfaceControl.isValid })
        } finally {
            baseline.recycle()
        }
    }

    private fun attach() {
        val (ready, error) = onMain { activity.attachLayer() to activity.viewLayer.lastFailure }
        assertTrue("Production layer attach failed: $error", ready)
    }

    private fun draw(progress: Float, enabled: Boolean = true) {
        val (drawn, error) = onMain { activity.drawLayer(progress, enabled) to activity.viewLayer.lastFailure }
        assertTrue("Production layer draw($progress, enabled=$enabled) failed: $error", drawn)
    }

    private data class Scene(val surface: Rect, val button: Rect, val marker: Rect, val points: List<Point>)
    private data class Counts(val lock: Int, val home: Int, val blended: Int)

    private fun scene(): Scene = onMain {
        val surface = activity.boundsOnScreen(activity.surfaceView)
        val button = activity.boundsOnScreen(activity.button)
        val marker = activity.boundsOnScreen(activity.marker)
        val points = ArrayList<Point>()
        for (y in 0 until 40) for (x in 0 until 32) {
            val point = Point(surface.left + (x * 2 + 1) * surface.width() / 64,
                surface.top + (y * 2 + 1) * surface.height() / 80)
            if (!button.contains(point.x, point.y) && !marker.contains(point.x, point.y)) points += point
        }
        Scene(surface, button, marker, points)
    }

    private fun homeFrame(label: String, scene: Scene): Bitmap = frameWhen(label) { bitmap ->
        scene.points.all { point ->
            val right = point.x - scene.surface.left >= scene.surface.width() / 2
            val bottom = point.y - scene.surface.top >= scene.surface.height() / 2
            val color = when {
                bottom && right -> WallpaperLayerTestActivity.HOME_BOTTOM_RIGHT
                bottom -> WallpaperLayerTestActivity.HOME_BOTTOM_LEFT
                right -> WallpaperLayerTestActivity.HOME_TOP_RIGHT
                else -> WallpaperLayerTestActivity.HOME_TOP_LEFT
            }
            closeColor(bitmap.getPixel(point.x, point.y), color)
        }
    }

    private fun allLock(bitmap: Bitmap, scene: Scene): Boolean = scene.points.all {
        closeColor(bitmap.getPixel(it.x, it.y), WallpaperLayerTestActivity.LOCK_COLOR)
    }

    private fun midpointCounts(frame: Bitmap, baseline: Bitmap, scene: Scene): Counts {
        var lock = 0
        var home = 0
        var blended = 0
        for (point in scene.points) {
            val color = frame.getPixel(point.x, point.y)
            val native = baseline.getPixel(point.x, point.y)
            when {
                closeColor(color, WallpaperLayerTestActivity.LOCK_COLOR) -> lock++
                closeColor(color, native) -> home++
                Color.red(color) in 6..249 && (Color.green(color) > 5 || Color.blue(color) > 5) &&
                    Color.green(color) <= Color.green(native) + 3 &&
                    Color.blue(color) <= Color.blue(native) + 3 -> blended++
            }
        }
        return Counts(lock, home, blended)
    }

    private fun assertIconsVisible(bitmap: Bitmap, scene: Scene) {
        assertTrue("Normal marker View must remain above the child surface",
            closeColor(bitmap.getPixel(scene.marker.centerX(), scene.marker.centerY()),
                WallpaperLayerTestActivity.MARKER_COLOR))
        assertTrue("Normal Button must remain above the child surface",
            closeColor(bitmap.getPixel(scene.button.left + 8, scene.button.top + 8),
                WallpaperLayerTestActivity.BUTTON_COLOR))
    }

    private fun exactHomeFrame(label: String, baseline: Bitmap, scene: Scene) {
        val actual = frameWhen(label) { frame ->
            scene.points.all { frame.getPixel(it.x, it.y) == baseline.getPixel(it.x, it.y) }
        }
        try {
            val bounds = Rect(scene.surface).apply { inset(2, 2) }
            val width = bounds.width()
            val expectedPixels = IntArray(width * bounds.height())
            val actualPixels = IntArray(expectedPixels.size)
            baseline.getPixels(expectedPixels, 0, width, bounds.left, bounds.top, width, bounds.height())
            actual.getPixels(actualPixels, 0, width, bounds.left, bounds.top, width, bounds.height())
            var mismatch = 0
            for (i in expectedPixels.indices) {
                val x = bounds.left + i % width
                val y = bounds.top + i / width
                if (!scene.button.contains(x, y) && !scene.marker.contains(x, y) && expectedPixels[i] != actualPixels[i]) {
                    mismatch++
                }
            }
            assertEquals("$label must preserve every native background pixel", 0, mismatch)
            assertIconsVisible(actual, scene)
        } finally {
            actual.recycle()
        }
    }

    private fun closeColor(actual: Int, expected: Int): Boolean = max(abs(Color.red(actual) - Color.red(expected)),
        max(abs(Color.green(actual) - Color.green(expected)), abs(Color.blue(actual) - Color.blue(expected)))) <= 3

    /** UiAutomation captures SurfaceFlinger composition, including the independent child surface. */
    private fun frameWhen(label: String, predicate: (Bitmap) -> Boolean): Bitmap {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        var last: Bitmap? = null
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val raw = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Display screenshot unavailable" }
            val frame = try {
                checkNotNull(raw.copy(Bitmap.Config.ARGB_8888, false))
            } finally {
                raw.recycle()
            }
            last?.recycle()
            last = frame
            if (predicate(frame)) {
                saveScreenshot(label, frame)
                return frame
            }
            SystemClock.sleep(100L)
        }
        last?.let {
            saveScreenshot("$label-timeout", it)
            it.recycle()
        }
        throw AssertionError("Timed out waiting for composed frame $label; screenshot saved in external files/validation")
    }

    private fun saveScreenshot(label: String, bitmap: Bitmap) {
        val directory = checkNotNull(target.getExternalFilesDir("validation"))
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create $directory" }
        File(directory, "${testName.methodName}-$label.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun awaitMain(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(50L)
        }
        throw AssertionError("Timed out waiting for $description; host=${onMain { activity.hostFailure }}")
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
