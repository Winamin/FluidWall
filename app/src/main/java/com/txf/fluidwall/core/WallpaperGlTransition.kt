package com.txf.fluidwall.core

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Runs on the wallpaper renderer's GL thread, after its complete native draw and before swap.
 * Call at most once per native frame: the home image is copied again on EVERY draw, so calling
 * this without a new native draw would capture our own previous output instead of the wallpaper.
 * Never makes an EGL context current, swaps buffers, clears a framebuffer, or owns the lock bitmap.
 *
 * GLES20's Java binding cannot query vertex attribute pointers. Even replacing a disabled array
 * would corrupt state the host may subsequently enable. Attribute-free point primitives cover
 * the surface in scissored tiles instead; only array enable flags are temporarily changed.
 */
class WallpaperGlTransition {
    private companion object {
        const val GL_FRAMEBUFFER_SRGB_EXT = 0x8DB9
        const val EGL_GL_COLORSPACE_KHR = 0x309D
        const val EGL_GL_COLORSPACE_SRGB_KHR = 0x3089
        const val MAX_TILES = 64
        val nextOwner = AtomicInteger()
        fun glInt(name: Int) = glInts(name, 1)[0]
        fun glInts(name: Int, size: Int) = IntArray(size).also { GLES20.glGetIntegerv(name, it, 0) }
    }

    private var ownerContext: EGLContext? = null
    private val ownerUniformName = "uFluidWallOwner${nextOwner.incrementAndGet()}"
    private var ownerDisplay: EGLDisplay? = null
    private var transitionProgram: Program? = null
    private var copyProgram: Program? = null
    private var lockTexture = 0
    private var homeTexture = 0
    private var homeWidth = 0
    private var homeHeight = 0
    private var lockWidth = 0
    private var lockHeight = 0
    private var lockGeneration = 0
    private var lockSource: WeakReference<Bitmap>? = null

    @Volatile
    var lastFailure: String? = null
        private set

    /** Returns false on failure; the caller should stop this transition instead of retrying forever. */
    @Synchronized
    fun draw(lock: Bitmap, config: FluidConfig, progress: Float, tint: Int, seed: Float): Boolean {
        // An endpoint is the untouched native frame, including its original precision and colors.
        if (progress >= 1f || !config.enabled) return true
        var state: State? = null
        var copied = false
        var drawing = false
        var width = 0
        var height = 0
        var tileSize = 0
        var linearOutput = false
        var success = false
        val context = EGL14.eglGetCurrentContext()
        val display = EGL14.eglGetCurrentDisplay()
        try {
            check(context != EGL14.EGL_NO_CONTEXT && display != EGL14.EGL_NO_DISPLAY) {
                "no current EGL context/display"
            }
            check(progress.isFinite() && seed.isFinite()) { "non-finite progress or seed" }
            check(!lock.isRecycled) { "lock bitmap was recycled" }
            val surface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            check(surface != EGL14.EGL_NO_SURFACE &&
                surface == EGL14.eglGetCurrentSurface(EGL14.EGL_READ)) {
                "requires the same EGL draw/read surface"
            }
            width = surfaceInt(display, surface, EGL14.EGL_WIDTH)
            height = surfaceInt(display, surface, EGL14.EGL_HEIGHT)
            check(width > 0 && height > 0) { "empty EGL surface ${width}x$height" }

            // GL's error latch cannot be restored by any API. A pre-existing error is logged and
            // causes a skip rather than being attributed to a copy/upload and overwriting pixels.
            checkGl("host has a pending GL error; transition skipped")
            check(glInt(GLES20.GL_FRAMEBUFFER_BINDING) == 0) { "native framebuffer is not EGL surface 0" }
            val maxTexture = glInt(GLES20.GL_MAX_TEXTURE_SIZE)
            check(width <= maxTexture && height <= maxTexture) {
                "EGL surface ${width}x$height exceeds GL_MAX_TEXTURE_SIZE=$maxTexture"
            }
            val pointRange = FloatArray(2)
            GLES20.glGetFloatv(GLES20.GL_ALIASED_POINT_SIZE_RANGE, pointRange, 0)
            tileSize = min(floor(pointRange[1]).toInt(), max(width, height))
            check(tileSize > 0 &&
                ceil(width.toDouble() / tileSize) * ceil(height.toDouble() / tileSize) <= MAX_TILES) {
                "point size ${pointRange[1]} needs more than $MAX_TILES tiles"
            }
            val glExtensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().split(' ')
            val srgbControl = "GL_EXT_sRGB_write_control" in glExtensions
            linearOutput = !srgbControl && isSrgbSurface(display, surface)
            state = State.capture(srgbControl)
            checkGl("capture GL state")

            if (ownerContext != context || ownerDisplay != display) {
                // IDs are context-local. Deleting an old ID here could delete the host's new ID.
                forgetResources()
                ownerContext = context
                ownerDisplay = display
            } else if ((transitionProgram != null && !transitionProgram!!.isCurrentObject()) ||
                (lockTexture != 0 && !GLES20.glIsTexture(lockTexture)) ||
                (homeTexture != 0 && !GLES20.glIsTexture(homeTexture))) {
                // Also catches a driver reusing the numeric EGL handle after its old context died.
                forgetResources()
            }
            ensurePrograms()
            ensureTextures()
            uploadLock(lock, maxTexture)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, homeTexture)
            if (homeWidth != width || homeHeight != height) {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
                )
                checkGl("allocate home texture ${width}x$height")
                homeWidth = width
                homeHeight = height
            }
            // Copy the host's newly rendered bottom-left-origin framebuffer, never our old texture.
            GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height)
            checkGl("copy native wallpaper framebuffer")
            copied = true
            state.prepare(width, height)
            val program = checkNotNull(transitionProgram)
            GLES20.glUseProgram(program.id)
            program.float(ownerUniformName, 0f)
            program.int("uLock", 0)
            program.int("uHome", 1)
            program.vec2("uSize", width.toFloat(), height.toFloat())
            // Android Bitmap rows upload top-first; v=0 thus samples the top of the lock image.
            val cover = max(width.toFloat() / lockWidth, height.toFloat() / lockHeight)
            program.vec2("uLockUvScale", width / (lockWidth * cover), height / (lockHeight * cover))
            program.float("uProgress", progress.coerceIn(0f, 1f))
            program.float("uScale", finite(config.scale, 3.2f))
            program.float("uWarp", finite(config.warp, 0.055f))
            program.float("uSoft", finite(config.soft, 0.16f))
            program.float("uGrain", finite(config.grain, 0.012f))
            program.float("uBleed", finite(config.bleed, 0.45f))
            program.float("uRadial", finite(config.radial, 0.30f))
            program.float("uSeed", seed)
            program.vec3("uTint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f)
            program.int("uLinearOutput", if (linearOutput) 1 else 0)
            checkGl("prepare transition uniforms")
            drawing = true
            drawTiles(program, width, height, tileSize)
            checkGl("draw wallpaper transition")
            lastFailure = null
            success = true
        } catch (error: Throwable) {
            // Allocation/compilation/upload failures happen before any pixel writes. A draw error
            // may leave partial tiles; restore the saved native frame with an already linked shader.
            if (drawing && copied && context == EGL14.eglGetCurrentContext()) {
                try {
                    drainOwnErrors()
                    state?.prepare(width, height)
                    val program = checkNotNull(copyProgram)
                    GLES20.glUseProgram(program.id)
                    program.float(ownerUniformName, 0f)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, homeTexture)
                    program.int("uHome", 1)
                    program.vec2("uSize", width.toFloat(), height.toFloat())
                    program.int("uLinearOutput", if (linearOutput) 1 else 0)
                    drawTiles(program, width, height, tileSize)
                    checkGl("restore native wallpaper pixels")
                } catch (restoreError: Throwable) {
                    XLog.i("GL wallpaper pixel restore failed: $restoreError")
                }
            }
            lastFailure = error.message ?: error.javaClass.simpleName
            XLog.i("GL wallpaper transition skipped: $lastFailure")
        } finally {
            if (context == EGL14.eglGetCurrentContext()) {
                try {
                    state?.restore()
                    if (state != null) checkGl("restore host GL state")
                } catch (restoreError: Throwable) {
                    success = false
                    lastFailure = "host GL state restore: ${restoreError.message}"
                    XLog.i("GL wallpaper state restore failed: $restoreError")
                }
            } else if (state != null) {
                success = false
                lastFailure = "EGL context changed during draw; cannot restore the old context here"
                XLog.i("GL wallpaper state restore skipped: $lastFailure")
            }
        }
        return success
    }

    /** Call on the owning GL thread before EGL teardown whenever possible. */
    @Synchronized
    fun release() {
        if (ownerContext == EGL14.eglGetCurrentContext() &&
            ownerDisplay == EGL14.eglGetCurrentDisplay() && ownerContext != null &&
            transitionProgram?.isCurrentObject() == true) {
            transitionProgram?.let { GLES20.glDeleteProgram(it.id) }
            copyProgram?.let { GLES20.glDeleteProgram(it.id) }
            GLES20.glDeleteTextures(2, intArrayOf(lockTexture, homeTexture), 0)
        }
        forgetResources()
        ownerContext = null
        ownerDisplay = null
    }

    private fun forgetResources() {
        transitionProgram = null
        copyProgram = null
        lockTexture = 0
        homeTexture = 0
        homeWidth = 0
        homeHeight = 0
        lockWidth = 0
        lockHeight = 0
        lockGeneration = 0
        lockSource = null
    }

    private fun ensurePrograms() {
        if (transitionProgram != null && copyProgram != null) return
        val vertex = VERTEX.replace("uFluidWallOwner", ownerUniformName)
        if (transitionProgram == null) transitionProgram = Program(link(vertex, FRAGMENT), ownerUniformName)
        if (copyProgram == null) copyProgram = Program(link(vertex, COPY_FRAGMENT), ownerUniformName)
    }

    private fun ensureTextures() {
        val ids = IntArray(2)
        if (lockTexture == 0 || homeTexture == 0) {
            GLES20.glGenTextures(2, ids, 0)
            lockTexture = ids[0]
            homeTexture = ids[1]
            check(ids.all { it != 0 }) { "texture allocation returned 0" }
            for (index in ids.indices) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[index])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lockTexture)
    }

    private fun uploadLock(source: Bitmap, maxTexture: Int) {
        if (lockSource?.get() === source && lockGeneration == source.generationId) return
        var upload = source
        try {
            if (upload.config != Bitmap.Config.ARGB_8888) {
                upload = checkNotNull(source.copy(Bitmap.Config.ARGB_8888, false)) { "bitmap copy failed" }
            }
            val ratio = min(1f, maxTexture.toFloat() / max(upload.width, upload.height))
            if (ratio < 1f) {
                val scaled = Bitmap.createScaledBitmap(upload,
                    max(1, (upload.width * ratio).toInt()), max(1, (upload.height * ratio).toInt()), true)
                if (upload !== source && upload !== scaled) upload.recycle()
                upload = scaled
            }
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, upload, 0)
            checkGl("upload lock wallpaper")
            lockWidth = upload.width
            lockHeight = upload.height
            lockSource = WeakReference(source)
            lockGeneration = source.generationId
        } finally {
            if (upload !== source) upload.recycle()
        }
    }

    private fun drawTiles(program: Program, width: Int, height: Int, limit: Int) {
        for (y in 0 until height step limit) {
            for (x in 0 until width step limit) {
                val w = min(limit, width - x)
                val h = min(limit, height - y)
                GLES20.glScissor(x, y, w, h)
                program.vec2("uPointCenter", (x + w * 0.5f) * 2f / width - 1f,
                    (y + h * 0.5f) * 2f / height - 1f)
                program.float("uPointSize", max(w, h).toFloat())
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
            }
        }
    }

    private class Program(val id: Int, private val ownerUniform: String) {
        private val pointCenter = GLES20.glGetUniformLocation(id, "uPointCenter")
        private val pointSize = GLES20.glGetUniformLocation(id, "uPointSize")
        private val locations = hashMapOf("uPointCenter" to pointCenter, "uPointSize" to pointSize)
        fun isCurrentObject(): Boolean = GLES20.glIsProgram(id) &&
            // Context handles and GL names can both be reused. A per-instance active uniform
            // prevents deleting another renderer's resources when their numeric IDs coincide.
            GLES20.glGetUniformLocation(id, ownerUniform) >= 0 &&
            GLES20.glGetUniformLocation(id, "uPointCenter") == pointCenter &&
            GLES20.glGetUniformLocation(id, "uPointSize") == pointSize
        private fun location(name: String) = locations.getOrPut(name) {
            GLES20.glGetUniformLocation(id, name).also { check(it >= 0) { "uniform $name missing" } }
        }
        fun int(name: String, value: Int) = GLES20.glUniform1i(location(name), value)
        fun float(name: String, value: Float) = GLES20.glUniform1f(location(name), value)
        fun vec2(name: String, x: Float, y: Float) = GLES20.glUniform2f(location(name), x, y)
        fun vec3(name: String, x: Float, y: Float, z: Float) = GLES20.glUniform3f(location(name), x, y, z)
    }

    private class State(
        private val program: Int,
        private val activeTexture: Int,
        private val textures: IntArray,
        private val viewport: IntArray,
        private val scissor: IntArray,
        private val colorMask: BooleanArray,
        private val unpackAlignment: Int,
        private val capabilities: IntArray,
        private val enabled: BooleanArray,
        private val attributes: BooleanArray,
    ) {
        fun prepare(width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            capabilities.forEach { GLES20.glDisable(it) }
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glColorMask(true, true, true, true)
            attributes.forEachIndexed { index, wasEnabled -> if (wasEnabled) GLES20.glDisableVertexAttribArray(index) }
        }

        fun restore() {
            attributes.forEachIndexed { index, wasEnabled -> if (wasEnabled) GLES20.glEnableVertexAttribArray(index) }
            GLES20.glUseProgram(program)
            for (index in textures.indices) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[index])
            }
            GLES20.glActiveTexture(activeTexture)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, unpackAlignment)
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            GLES20.glScissor(scissor[0], scissor[1], scissor[2], scissor[3])
            GLES20.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3])
            capabilities.forEachIndexed { index, cap ->
                if (enabled[index]) GLES20.glEnable(cap) else GLES20.glDisable(cap)
            }
        }

        companion object {
            fun capture(srgbControl: Boolean): State {
                val active = glInt(GLES20.GL_ACTIVE_TEXTURE)
                val textures = IntArray(2)
                try {
                    for (index in textures.indices) {
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
                        textures[index] = glInt(GLES20.GL_TEXTURE_BINDING_2D)
                    }
                } finally {
                    GLES20.glActiveTexture(active)
                }
                val caps = intArrayOf(GLES20.GL_BLEND, GLES20.GL_CULL_FACE, GLES20.GL_DEPTH_TEST,
                    GLES20.GL_STENCIL_TEST, GLES20.GL_SCISSOR_TEST, GLES20.GL_DITHER,
                    GLES20.GL_SAMPLE_ALPHA_TO_COVERAGE, GLES20.GL_SAMPLE_COVERAGE) +
                    if (srgbControl) intArrayOf(GL_FRAMEBUFFER_SRGB_EXT) else intArrayOf()
                val attr = BooleanArray(glInt(GLES20.GL_MAX_VERTEX_ATTRIBS)) { index ->
                    val value = IntArray(1)
                    GLES20.glGetVertexAttribiv(index, GLES20.GL_VERTEX_ATTRIB_ARRAY_ENABLED, value, 0)
                    value[0] != 0
                }
                return State(glInt(GLES20.GL_CURRENT_PROGRAM), active, textures,
                    glInts(GLES20.GL_VIEWPORT, 4), glInts(GLES20.GL_SCISSOR_BOX, 4),
                    BooleanArray(4).also { GLES20.glGetBooleanv(GLES20.GL_COLOR_WRITEMASK, it, 0) },
                    glInt(GLES20.GL_UNPACK_ALIGNMENT), caps,
                    BooleanArray(caps.size) { GLES20.glIsEnabled(caps[it]) }, attr)
            }
        }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "glCreateShader returned 0" }
        try {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val result = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, result, 0)
            check(result[0] != 0) { "shader compile: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        } catch (error: Throwable) {
            GLES20.glDeleteShader(shader)
            throw error
        }
    }

    private fun link(vertex: String, fragment: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        var fs = 0
        var program = 0
        try {
            fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            program = GLES20.glCreateProgram()
            check(program != 0) { "glCreateProgram returned 0" }
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val result = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, result, 0)
            check(result[0] != 0) { "program link: ${GLES20.glGetProgramInfoLog(program)}" }
            return program
        } catch (error: Throwable) {
            if (program != 0) GLES20.glDeleteProgram(program)
            throw error
        } finally {
            GLES20.glDeleteShader(vs)
            if (fs != 0) GLES20.glDeleteShader(fs)
        }
    }

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "$operation: GL error 0x${error.toString(16)}" }
    }
    private fun drainOwnErrors() { repeat(16) { if (GLES20.glGetError() == GLES20.GL_NO_ERROR) return } }
    private fun finite(value: Float, fallback: Float) = if (value.isFinite()) value else fallback
    private fun surfaceInt(display: EGLDisplay, surface: EGLSurface, name: Int): Int {
        val result = IntArray(1)
        check(EGL14.eglQuerySurface(display, surface, name, result, 0)) { "eglQuerySurface failed for 0x${name.toString(16)}" }
        return result[0]
    }
    private fun isSrgbSurface(display: EGLDisplay, surface: EGLSurface): Boolean {
        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty().split(' ')
        return "EGL_KHR_gl_colorspace" in extensions &&
            surfaceInt(display, surface, EGL_GL_COLORSPACE_KHR) == EGL_GL_COLORSPACE_SRGB_KHR
    }

    private val VERTEX = """
        precision highp float;
        uniform vec2 uPointCenter;
        uniform float uPointSize;
        uniform float uFluidWallOwner;
        void main() {
            gl_Position = vec4(uPointCenter + vec2(uFluidWallOwner), 0.0, 1.0);
            gl_PointSize = uPointSize;
        }
    """.trimIndent()

    private val COLOR_OUTPUT = """
        uniform bool uLinearOutput;
        vec4 outputColor(vec4 encoded) {
            if (!uLinearOutput) return encoded;
            vec3 low = encoded.rgb / 12.92;
            vec3 high = pow(max((encoded.rgb + 0.055) / 1.055, vec3(0.0)), vec3(2.4));
            return vec4(mix(low, high, step(vec3(0.04045), encoded.rgb)), encoded.a);
        }
    """.trimIndent()

    private val PRECISION = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
    """.trimIndent()

    private val COPY_FRAGMENT = PRECISION + "\n" + COLOR_OUTPUT + "\n" + """
        uniform sampler2D uHome;
        uniform vec2 uSize;
        void main() { gl_FragColor = outputColor(texture2D(uHome, gl_FragCoord.xy / uSize)); }
    """.trimIndent()

    // Keep the field and timing identical to FluidShader.AGSL. gl_FragCoord is bottom-left;
    // all field/lock calculations use top-left pixels, then home sampling flips back to GL UVs.
    private val FRAGMENT = PRECISION + "\n" + COLOR_OUTPUT + "\n" + """
        uniform sampler2D uLock;
        uniform sampler2D uHome;
        uniform vec2 uSize;
        uniform vec2 uLockUvScale;
        uniform float uProgress;
        uniform float uScale;
        uniform float uWarp;
        uniform float uSoft;
        uniform float uGrain;
        uniform float uBleed;
        uniform float uRadial;
        uniform float uSeed;
        uniform vec3 uTint;

        float hash21(vec2 p) {
            vec2 q = p - floor(p * 0.015625) * 64.0;
            vec3 v = fract(vec3(q.x, q.y, q.x) * 0.1031);
            v += dot(v, v.yzx + 33.33);
            return fract((v.x + v.y) * v.z);
        }
        float vnoise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            vec2 u = f * f * (3.0 - 2.0 * f);
            float a = hash21(i);
            float b = hash21(i + vec2(1.0, 0.0));
            float c = hash21(i + vec2(0.0, 1.0));
            float d = hash21(i + vec2(1.0, 1.0));
            return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
        }
        float fbm(vec2 p) {
            float s = 0.0;
            float a = 0.5;
            for (int i = 0; i < 4; i++) {
                s += a * vnoise(p);
                p = p * 2.03 + vec2(17.3, 9.1);
                a *= 0.5;
            }
            return s / 0.9375;
        }
        float voronoi(vec2 p) {
            vec2 n = floor(p);
            vec2 f = fract(p);
            float d1 = 8.0;
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    vec2 g = vec2(float(x), float(y));
                    vec2 o = vec2(hash21(n + g), hash21(n + g + vec2(31.0, 71.0)));
                    vec2 r = g + o - f;
                    d1 = min(d1, dot(r, r));
                }
            }
            return clamp(sqrt(d1) / 1.15, 0.0, 1.0);
        }
        void main() {
            vec2 res = max(uSize, vec2(1.0));
            vec2 coord = vec2(gl_FragCoord.x, res.y - gl_FragCoord.y);
            vec2 uv = coord / res;
            float asp = res.x / res.y;
            vec2 p = vec2(uv.x * asp, uv.y) * uScale + vec2(uSeed * 3.7, uSeed * -1.9);
            float t = clamp(uProgress, 0.0, 1.0);
            if (t >= 1.0) {
                gl_FragColor = outputColor(texture2D(uHome, gl_FragCoord.xy / res));
                return;
            }
            float base = fbm(p);
            float vor = voronoi(p * 0.92 + base * 0.85 + vec2(0.0, t * 0.35) + vec2(uSeed * 0.4, 0.0));
            float field = base * 0.58 + (1.0 - vor) * 0.42;
            vec2 d = (uv - vec2(0.5, 0.44)) * vec2(1.0, 1.15);
            float radial = 1.0 - clamp(length(d) * 1.35, 0.0, 1.0);
            field = mix(field, field * 0.55 + radial * 0.45, clamp(uRadial, 0.0, 1.0));
            float k = mix(0.74, 0.26, t);
            float soft = max(uSoft, 0.001);
            float m = smoothstep(k - soft * 0.5, k + soft * 0.5, field);
            m *= smoothstep(0.0, 0.02, t);
            m = mix(m, 1.0, smoothstep(0.94, 1.0, t));
            float rim = 1.0 - smoothstep(0.0, soft * 0.95, abs(field - k));
            rim *= smoothstep(0.02, 0.12, t) * (1.0 - smoothstep(0.78, 0.94, t));
            float warpFade = 1.0 - smoothstep(0.58, 0.88, t);
            vec2 flow = vec2(sin((base + vor) * 6.28318), cos((base - vor) * 6.28318));
            vec2 warpAmt = (flow * 0.55 + (vec2(base, vor) - 0.5) * 0.9)
                * (uWarp * res.y * rim * warpFade);
            vec2 warped = clamp(coord + warpAmt, vec2(0.0), res - vec2(1.0));
            vec4 lockC = texture2D(uLock, (uv - 0.5) * uLockUvScale + 0.5);
            vec2 homeUv = warped / res;
            vec4 homeC = texture2D(uHome, vec2(homeUv.x, 1.0 - homeUv.y));
            vec4 c = mix(lockC, homeC, m);
            c.rgb += uTint * (rim * uBleed);
            float g = hash21(uv * 640.0 + vec2(uSeed * 13.0, 7.0)) - 0.5;
            c.rgb += vec3(g * uGrain * (1.0 - smoothstep(0.72, 0.92, t)));
            c.a = 1.0;
            gl_FragColor = outputColor(c);
        }
    """.trimIndent()
}
