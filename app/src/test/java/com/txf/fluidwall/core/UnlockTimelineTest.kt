package com.txf.fluidwall.core

import org.junit.Assert.*
import org.junit.Test

class UnlockTimelineTest {
    @Test fun startupAndDuplicateSignalsDoNotReplay() {
        val t = UnlockTimeline()
        assertFalse(t.unlock(0))
        t.lock()
        assertTrue(t.unlock(10))
        assertFalse(t.unlock(20))
        assertEquals(0f, t.frame(30, true, 700)!!, 0f)
        assertEquals(0.5f, t.frame(380, true, 700)!!, 0.001f)
        assertFalse(t.unlock(390))
        assertEquals(1f, t.frame(730, true, 700)!!, 0f)
        assertFalse(t.unlock(900))
        assertFalse(t.active)
    }

    @Test fun loadingDoesNotConsumeAnimationDuration() {
        val t = UnlockTimeline()
        t.lock()
        t.unlock(100)
        assertNull(t.frame(500, false, 700))
        assertTrue(t.active)
        assertEquals(0f, t.frame(900, true, 700)!!, 0f)
        assertEquals(0.5f, t.frame(1250, true, 700)!!, 0.001f)
    }

    @Test fun lateAssetsCannotPlayAnOldUnlock() {
        val t = UnlockTimeline()
        t.lock()
        t.unlock(100)
        assertTrue(t.expired(1601, 700))
        assertNull(t.frame(1601, true, 700))
        assertFalse(t.active)
    }

    @Test fun screenOffCancelsOldFramesAndArmsNextCycle() {
        val t = UnlockTimeline()
        t.lock()
        t.unlock(0)
        t.frame(0, true, 700)
        t.lock()
        assertNull(t.frame(300, true, 700))
        assertTrue(t.unlock(500))
        assertEquals(0f, t.frame(600, true, 700)!!, 0f)
    }

    @Test fun destroyOrDisableDropsPendingWork() {
        val t = UnlockTimeline()
        t.lock()
        t.unlock(0)
        t.cancel()
        assertNull(t.frame(1, true, 700))
        assertFalse(t.unlock(2))
    }

    @Test fun longAnimationDoesNotExpireAfterItStarts() {
        val t = UnlockTimeline()
        t.lock()
        t.unlock(0)
        t.frame(0, true, 4000)
        assertFalse(t.expired(3000, 4000))
        assertEquals(0.75f, t.frame(3000, true, 4000)!!, 0f)
        assertEquals(1f, t.frame(4000, true, 4000)!!, 0f)
    }

    @Test fun missingFrameDoesNotRestartTimelineAfterContextRecreation() {
        val t = UnlockTimeline()
        t.lock()
        t.unlock(0)
        t.frame(0, true, 700)
        assertNull(t.frame(200, false, 700))
        assertEquals(0.5f, t.frame(350, true, 700)!!, 0f)
    }

    @Test fun lostSurfaceAfterFirstFrameCannotScheduleForever() {
        val t = UnlockTimeline()
        t.lock()
        t.unlock(0)
        t.frame(0, true, 700)
        assertFalse(t.expired(900, 700))
        assertTrue(t.expired(951, 700))
        assertNull(t.frame(951, true, 700))
        assertFalse(t.active)
    }

    @Test fun surfaceDestructionPreservesOnlyUnconsumedLockCycles() {
        val t = UnlockTimeline()
        t.lock()
        t.surfaceLost()
        assertTrue(t.unlock(100))
        t.frame(100, true, 700)
        t.surfaceLost()
        assertFalse(t.active)
        assertFalse(t.unlock(200))
        t.lock()
        assertTrue(t.unlock(300))
    }
}
