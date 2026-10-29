package com.txf.fluidwall.core

import android.os.Process
import android.os.SystemClock
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.txf.fluidwall.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScopeRestartTest {
    @Test fun topRightMenuOpensRestartButtonWithoutRequestingRoot() {
        val lookup = ProcessBuilder("/system/bin/sh", "-c", "command -v su").start()
        try {
            assumeTrue("This UI test must never restart real scoped apps", lookup.waitFor(5, TimeUnit.SECONDS) && lookup.exitValue() != 0)
        } finally {
            lookup.destroy()
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            val menu = awaitNode { it.isVisibleToUser && it.contentDescription?.toString() == "更多操作" }
            val bounds = Rect().also(menu::getBoundsInScreen)
            val display = Rect().also(automation.rootInActiveWindow::getBoundsInScreen)
            assertTrue("Menu must be in the top right without scrolling", bounds.centerX() > display.width() * 3 / 4 && bounds.centerY() < display.height() / 4)
            assertTrue("Restart control must not appear in the main settings", findNode(automation.rootInActiveWindow) { it.text?.toString() == "重启作用域" } == null)
            val before = ScopeRestarter.state.value
            click(menu)
            val restartButton = awaitNode { it.isVisibleToUser && it.text?.toString() == "重启作用域" }
            assertEquals("Opening the menu must not request Root", before, ScopeRestarter.state.value)
            saveScreenshot("scope-menu")
            click(restartButton)
            val deadline = SystemClock.uptimeMillis() + 5_000L
            while (SystemClock.uptimeMillis() < deadline) {
                val state = ScopeRestarter.state.value
                if (!state.running && state.message != ScopeRestartState().message) break
                SystemClock.sleep(100L)
            }
            val state = ScopeRestarter.state.value
            assertTrue("Operation must finish after Root fails", !state.running)
            assertTrue("Missing Root must be visible: ${state.message}", state.message.startsWith("未找到可用的 Root") || state.message.startsWith("未获得 Root"))
            awaitNode { it.isVisibleToUser && it.text?.contains(state.message) == true }
            assertTrue(awaitNode { it.text?.toString() == "重启作用域" }.isEnabled)
            saveScreenshot("scope-menu-no-root")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitNode { it.isVisibleToUser && it.contentDescription?.toString() == "更多操作" }
            assertTrue("Back must dismiss the menu", findNode(automation.rootInActiveWindow) { it.text?.toString() == "重启作用域" } == null)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun awaitNode(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.rootInActiveWindow?.let { root -> findNode(root, match)?.let { return it } }
            SystemClock.sleep(100L)
        }
        saveScreenshot("scope-menu-timeout")
        error("Expected menu control was not found")
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable) target = checkNotNull(target.parent)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        SystemClock.sleep(350L) // Let popup entry/exit animations settle before capturing composition.
        instrumentation.uiAutomation.takeScreenshot()?.let { frame ->
            val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir("validation"))
            directory.mkdirs()
            File(directory, "$name.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            frame.recycle()
        }
    }

    // Compose provides virtual descendants; walk them instead of the optional provider text search.
    private fun findNode(node: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (match(node)) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
            findNode(child, match)?.let { return it }
        }
        return null
    }

    @Test fun ordinaryAndroidShellCannotRunRestartCommands() {
        try {
            RootScopeShell({ ProcessBuilder("/system/bin/sh").redirectErrorStream(true).start() })
                .restart(Process.myUid() / 100_000) { fail("App shell must not be treated as Root") }
            fail("Expected Root denial")
        } catch (e: RootRestartException) {
            assertEquals(RootRestartFailure.DENIED, e.reason)
        }
    }

    @Test fun androidShellParsesScriptAndReadsFullProcessNames() {
        // -n parses but never runs the privileged commands.
        val parser = ProcessBuilder("/system/bin/sh", "-n", "-c", ScopeRestartCommand.script(0))
            .redirectErrorStream(true).start()
        try {
            assertTrue(parser.waitFor(5, TimeUnit.SECONDS))
            assertEquals(parser.inputStream.bufferedReader().readText(), 0, parser.exitValue())
        } finally {
            parser.destroy()
        }
        val readName = "IFS= read -r -d '' name < /proc/${Process.myPid()}/cmdline && echo \"${'$'}name\""
        val reader = ProcessBuilder("/system/bin/sh", "-c", readName).redirectErrorStream(true).start()
        try {
            assertTrue(reader.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, reader.exitValue())
            assertEquals("com.txf.fluidwall", reader.inputStream.bufferedReader().readText().trim())
        } finally {
            reader.destroy()
        }
    }
}
