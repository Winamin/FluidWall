package com.txf.fluidwall.core

/** One unlock per lock cycle, with bounded waiting for the first drawable frame. Single-threaded. */
internal class UnlockTimeline(private val readyWindowMs: Long = 1500L) {
    private var armed = false
    private var requestedAt: Long? = null
    private var startedAt: Long? = null

    val active: Boolean get() = requestedAt != null

    fun lock() {
        cancel()
        armed = true
    }

    fun unlock(now: Long): Boolean {
        if (!armed) return false
        armed = false
        requestedAt = now
        startedAt = null
        return true
    }

    fun expired(now: Long, durationMs: Int): Boolean {
        val start = startedAt
        return if (start == null) requestedAt?.let { now - it > readyWindowMs } == true
        else now - start > durationMs.coerceAtLeast(1) + 250L
    }

    fun frame(now: Long, ready: Boolean, durationMs: Int): Float? {
        if (!active) return null
        if (expired(now, durationMs)) {
            cancel()
            return null
        }
        if (!ready) return null
        val start = startedAt ?: now.also { startedAt = it }
        val progress = ((now - start).toFloat() / durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        if (progress == 1f) cancel()
        return progress
    }

    fun cancel() {
        armed = false
        surfaceLost()
    }

    /** A recreated surface can still serve a lock cycle that has not unlocked yet. */
    fun surfaceLost() {
        requestedAt = null
        startedAt = null
    }
}
