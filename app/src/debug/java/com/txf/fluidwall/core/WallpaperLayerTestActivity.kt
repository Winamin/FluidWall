package com.txf.fluidwall.core

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** Debug-only fixture: a native host buffer below regular window Views and the production layer. */
class WallpaperLayerTestActivity : Activity(), SurfaceHolder.Callback {
    lateinit var surfaceView: SurfaceView
        private set
    lateinit var viewLayer: WallpaperViewLayer
        private set
    lateinit var lockBitmap: Bitmap
        private set
    lateinit var button: Button
        private set
    lateinit var marker: View
        private set

    var clicks = 0
        private set
    var surfaceReady = false
        private set
    var surfaceCreatedCount = 0
        private set
    var surfaceDestroyedCount = 0
        private set
    var surfaceChangedCount = 0
        private set
    var hostFailure: String? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        viewLayer = WallpaperViewLayer(this)
        lockBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(LOCK_COLOR)
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this).apply {
            setZOrderOnTop(false)
            // SurfaceView defaults to RGB_565 on some images; keep screenshot colors exact.
            holder.setFormat(PixelFormat.RGBA_8888)
            holder.addCallback(this@WallpaperLayerTestActivity)
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        marker = View(this).apply { setBackgroundColor(MARKER_COLOR) }
        root.addView(marker, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(24)
            topMargin = dp(64)
        })
        button = Button(this).apply {
            text = "Desktop icon"
            setTextColor(Color.BLACK)
            setBackgroundColor(BUTTON_COLOR)
            stateListAnimator = null
            elevation = 0f
            setOnClickListener { clicks++ }
        }
        root.addView(button, FrameLayout.LayoutParams(dp(152), dp(56), Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(24)
            topMargin = dp(64)
        })
        setContentView(root)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.systemBars())
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceCreatedCount++
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceChangedCount++
        viewLayer.release()
        surfaceReady = width > 0 && height > 0 && holder.surface.isValid
        if (surfaceReady) drawHome()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceDestroyedCount++
        surfaceReady = false
        viewLayer.release()
    }

    fun drawHome() {
        try {
            val holder = surfaceView.holder
            val canvas = checkNotNull(holder.lockCanvas()) { "Host Canvas unavailable" }
            try {
                val paint = Paint()
                val midX = canvas.width / 2f
                val midY = canvas.height / 2f
                canvas.drawColor(HOME_TOP_LEFT)
                paint.color = HOME_TOP_RIGHT
                canvas.drawRect(midX, 0f, canvas.width.toFloat(), midY, paint)
                paint.color = HOME_BOTTOM_LEFT
                canvas.drawRect(0f, midY, midX, canvas.height.toFloat(), paint)
                paint.color = HOME_BOTTOM_RIGHT
                canvas.drawRect(midX, midY, canvas.width.toFloat(), canvas.height.toFloat(), paint)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            hostFailure = null
        } catch (t: Throwable) {
            hostFailure = "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    fun attachLayer(): Boolean {
        val frame = surfaceView.holder.surfaceFrame
        viewLayer.attach(surfaceView.surfaceControl, frame.width(), frame.height())
        return viewLayer.ready
    }

    fun drawLayer(progress: Float, enabled: Boolean = true): Boolean = viewLayer.draw(
        lockBitmap,
        FluidConfig(enabled = enabled, scale = 4f, warp = 0f, soft = 0.2f,
            grain = 0f, bleed = 0f, radial = 0f),
        progress, Color.BLACK, 1.25f,
    )

    fun resizeSurface(width: Int, height: Int) {
        surfaceView.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.START)
    }

    fun boundsOnScreen(view: View): Rect {
        val position = IntArray(2)
        view.getLocationOnScreen(position)
        return Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        viewLayer.release()
        lockBitmap.recycle()
        super.onDestroy()
    }

    companion object {
        val LOCK_COLOR = Color.RED
        val MARKER_COLOR = Color.MAGENTA
        val BUTTON_COLOR = Color.YELLOW
        val HOME_TOP_LEFT = Color.rgb(0, 64, 255)
        val HOME_TOP_RIGHT = Color.rgb(0, 160, 96)
        val HOME_BOTTOM_LEFT = Color.rgb(0, 80, 128)
        val HOME_BOTTOM_RIGHT = Color.rgb(0, 220, 220)
    }
}
