package com.txf.fluidwall.core

import android.app.Application
import android.content.Context
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Persistent log buffer for the settings log screen.
 *
 * XLog already writes to logcat / LSPosed, but the app is not allowed to read logcat. Hooks in
 * other processes therefore push their lines through the config provider, while the module's own
 * process writes to its private files directly.
 */
object LogStore {

    private const val MAX_BYTES = 512 * 1024L
    private const val MAX_LINES_ON_TRIM = 4000
    private const val MAX_READ_CHARS = 200_000
    private const val MAX_PENDING = 300
    private const val MAX_LINE = 8000

    private val fileLock = Any()
    private val pending = ArrayList<String>()
    private val remote = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fluid-log").apply { isDaemon = true }
    }

    @Volatile
    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext ?: context
        val queued = synchronized(pending) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        queued.forEach { dispatch(it) }
    }

    fun record(msg: String) {
        val line = "${stamp()} [${processName()}] $msg"
        if (ctx == null) {
            synchronized(pending) {
                if (pending.size >= MAX_PENDING) pending.removeAt(0)
                pending.add(line)
            }
            return
        }
        dispatch(line)
    }

    /** Used by ConfigProvider for lines pushed by hooked processes. */
    fun appendRaw(context: Context, line: String) {
        appendToFile(context, line)
    }

    fun read(context: Context): String {
        val text = synchronized(fileLock) {
            try {
                val f = logFile(context)
                if (!f.exists()) "" else f.readText().takeLast(MAX_READ_CHARS)
            } catch (_: Throwable) {
                ""
            }
        }
        return text.ifBlank { "(还没有日志)" }
    }

    private fun dispatch(line: String) {
        val context = ctx ?: return
        if (inModuleProcess()) {
            appendToFile(context, line)
        } else {
            remote.execute {
                try {
                    context.contentResolver.call(
                        Uri.parse("content://${FluidConfig.AUTHORITY}"),
                        "log",
                        line.take(MAX_LINE),
                        null,
                    )
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun appendToFile(context: Context, line: String) {
        try {
            synchronized(fileLock) {
                val f = logFile(context)
                f.appendText(line.take(MAX_LINE) + "\n")
                if (f.length() > MAX_BYTES) {
                    val kept = f.readLines().takeLast(MAX_LINES_ON_TRIM)
                    f.writeText(kept.joinToString("\n", postfix = "\n"))
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun logFile(context: Context): File {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        return File(dir, "fluidwall.log")
    }

    private fun stamp(): String = try {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
    } catch (_: Throwable) {
        System.currentTimeMillis().toString()
    }

    private fun processName(): String = try {
        Application.getProcessName() ?: "unknown"
    } catch (_: Throwable) {
        "unknown"
    }

    private fun inModuleProcess(): Boolean = try {
        Application.getProcessName() == FluidConfig.PACKAGE
    } catch (_: Throwable) {
        false
    }
}
