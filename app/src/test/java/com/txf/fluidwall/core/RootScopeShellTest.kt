package com.txf.fluidwall.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RootScopeShellTest {
    @Test fun targetsMatchThePackagedLsposedScope() {
        val configured = File("src/main/resources/META-INF/xposed/scope.list").readLines()
            .map(String::trim).filter(String::isNotEmpty)
        assertEquals(configured, ScopeRestartCommand.packages)
    }

    @Test fun rootApprovalPrecedesAnyRestartCommands() {
        val process = FakeSu("0")
        var authorized = false
        val result = RootScopeShell({ process }).restart(0) {
            assertFalse(process.commands.contains("kill -9"))
            authorized = true
        }
        assertTrue(authorized)
        assertTrue(process.commands.contains("kill -9"))
        assertEquals(setOf("com.miui.home", "com.miui.miwallpaper"), result.stopped)
        assertTrue(result.homeStarted)
        assertTrue(process.destroyed)
    }

    @Test fun nonRootShellNeverReceivesRestartCommands() {
        val process = FakeSu("2000")
        assertFailure(RootRestartFailure.DENIED) { RootScopeShell({ process }).restart(0) { fail() } }
        assertFalse(process.commands.contains("kill -9"))
        assertTrue(process.destroyed)
    }

    @Test fun rejectedAuthorizationNeverReceivesRestartCommands() {
        val process = FakeSu(null, reject = true)
        assertFailure(RootRestartFailure.DENIED) { RootScopeShell({ process }).restart(0) { fail() } }
        assertFalse(process.commands.contains("kill -9"))
    }

    @Test fun pendingAuthorizationTimesOutWithoutQueuingRestart() {
        val process = FakeSu(null)
        assertFailure(RootRestartFailure.TIMEOUT) {
            RootScopeShell({ process }, authorizationTimeoutMs = 30L).restart(0) { fail() }
        }
        assertFalse(process.commands.contains("kill -9"))
        assertTrue(process.destroyed)
    }

    @Test fun missingSuHasAnActionableFailure() {
        assertFailure(RootRestartFailure.UNAVAILABLE) {
            RootScopeShell({ throw IOException("su missing") }).restart(0) { fail() }
        }
    }

    @Test fun commandFailureIsNotReportedAsSuccess() {
        val process = FakeSu("0", commandExitCode = 1)
        assertFailure(RootRestartFailure.COMMAND) { RootScopeShell({ process }).restart(0) {} }
    }

    @Test fun emptyScopeDoesNotClaimAnythingWasRestarted() {
        val process = FakeSu("0", result = "FW_STOPPED:com.miui.home:0\nFW_STOPPED:com.miui.miwallpaper:0\nFW_DONE\n")
        val result = RootScopeShell({ process }).restart(0) {}
        assertTrue(result.stopped.isEmpty())
        assertFalse(result.homeStarted)
    }

    @Test fun incompleteCommandOutputIsNotSuccess() {
        val process = FakeSu("0", result = "FW_STOPPED:com.miui.home:1\n")
        assertFailure(RootRestartFailure.COMMAND) { RootScopeShell({ process }).restart(0) {} }
    }

    private fun assertFailure(expected: RootRestartFailure, block: () -> Unit) {
        try {
            block()
            fail("Expected $expected")
        } catch (e: RootRestartException) {
            assertEquals(expected, e.reason)
        }
    }

    /** Models su's prompt/denial/approval and pipes without requesting or exercising host Root. */
    private class FakeSu(
        private val uid: String?,
        private val reject: Boolean = false,
        private val commandExitCode: Int = 0,
        private val result: String = "FW_STOPPED:com.miui.home:1\nFW_STOPPED:com.miui.miwallpaper:2\nFW_HOME_STARTED\nFW_DONE\n",
    ) : Process() {
        private val incoming = PipedInputStream()
        private val reply = PipedOutputStream(incoming)
        private val sent = ByteArrayOutputStream()
        private val complete = CountDownLatch(1)
        private var probed = false
        private var executed = false
        var destroyed = false
        val commands: String get() = sent.toString(Charsets.UTF_8.name())
        private val sink = object : OutputStream() {
            override fun write(value: Int) { sent.write(value) }
            override fun flush() {
                if (!probed) {
                    probed = true
                    if (reject) {
                        reply.close()
                        complete.countDown()
                    } else if (uid != null) {
                        reply.write("FW_UID:$uid\n".toByteArray())
                        reply.flush()
                    }
                } else if (!executed && commands.contains("FW_DONE")) {
                    executed = true
                    reply.write(result.toByteArray())
                    reply.close()
                    complete.countDown()
                }
            }
        }

        override fun getOutputStream() = sink
        override fun getInputStream() = incoming
        override fun getErrorStream() = "".byteInputStream()
        override fun waitFor(): Int { complete.await(); return commandExitCode }
        override fun waitFor(timeout: Long, unit: TimeUnit) = complete.await(timeout, unit)
        override fun exitValue(): Int {
            check(!isAlive)
            return commandExitCode
        }
        override fun isAlive() = complete.count != 0L
        override fun destroy() {
            destroyed = true
            reply.close()
            complete.countDown()
        }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
}
