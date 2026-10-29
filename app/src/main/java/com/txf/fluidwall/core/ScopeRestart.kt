package com.txf.fluidwall.core

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/** Only the packages in the module's static LSPosed scope may be restarted. */
internal object ScopeRestartCommand {
    val packages = listOf("com.miui.home", "com.miui.miwallpaper")

    fun script(userId: Int): String {
        require(userId >= 0)
        return """
            PATH=/system/bin:/system/xbin
            export PATH
            failed=0
            for package in ${packages.joinToString(" ")}; do
                stopped=0
                for entry in /proc/[0-9]*; do
                    [ -r "${'$'}entry/cmdline" ] || continue
                    IFS= read -r -d '' name < "${'$'}entry/cmdline" 2>/dev/null || continue
                    case "${'$'}name" in
                        "${'$'}package"|"${'$'}package":*) ;;
                        *) continue ;;
                    esac
                    uid=${'$'}(stat -c %u "${'$'}entry" 2>/dev/null)
                    case "${'$'}uid" in ''|*[!0-9]*) continue ;; esac
                    [ ${'$'}((uid / 100000)) -eq $userId ] || continue
                    pid=${'$'}{entry##*/}
                    if kill -9 "${'$'}pid" 2>/dev/null; then
                        stopped=${'$'}((stopped + 1))
                    elif [ -d "${'$'}entry" ]; then
                        failed=1
                    fi
                done
                echo "FW_STOPPED:${'$'}package:${'$'}stopped"
            done
            # Killing, rather than force-stopping, allows Android to rebind the live wallpaper.
            if pm path --user $userId com.miui.home 2>/dev/null | grep -q '^package:'; then
                if am start --user $userId -a android.intent.action.MAIN -c android.intent.category.HOME -p com.miui.home; then
                    echo FW_HOME_STARTED
                else
                    failed=1
                fi
            fi
            echo FW_DONE
            exit "${'$'}failed"
        """.trimIndent()
    }
}

internal enum class RootRestartFailure { UNAVAILABLE, DENIED, TIMEOUT, COMMAND }

internal class RootRestartException(val reason: RootRestartFailure, cause: Throwable? = null) :
    IOException(reason.name, cause)

internal data class ScopeRestartResult(val stopped: Set<String>, val homeStarted: Boolean)

/** Runs on a worker thread. No restart commands enter stdin until su proves it has UID 0. */
internal class RootScopeShell(
    private val startProcess: () -> Process = { ProcessBuilder("su").redirectErrorStream(true).start() },
    private val authorizationTimeoutMs: Long = 60_000L,
    private val commandTimeoutMs: Long = 20_000L,
) {
    fun restart(userId: Int, onAuthorized: () -> Unit): ScopeRestartResult {
        val script = ScopeRestartCommand.script(userId)
        val process = try { startProcess() } catch (e: IOException) {
            throw RootRestartException(RootRestartFailure.UNAVAILABLE, e)
        }
        val identity = CompletableFuture<String?>()
        val output = StringBuffer()
        val reader = thread(name = "fluid-root-output", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.startsWith("FW_UID:")) identity.complete(line.removePrefix("FW_UID:").trim())
                        synchronized(output) {
                            output.append(line.take(2048)).append('\n')
                            if (output.length > 16_384) output.delete(0, output.length - 16_384)
                        }
                    }
                }
            } catch (_: IOException) {
                // Closing a denied or timed-out su also closes its output pipe.
            } finally {
                identity.complete(null)
            }
        }
        try {
            val input = process.outputStream.bufferedWriter()
            input.write("echo FW_UID:${'$'}(/system/bin/id -u)\n")
            input.flush()
            if (identity.get(authorizationTimeoutMs, TimeUnit.MILLISECONDS) != "0") {
                throw RootRestartException(RootRestartFailure.DENIED)
            }
            onAuthorized()
            input.write(script)
            input.newLine()
            input.flush()
            if (!process.waitFor(commandTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw RootRestartException(RootRestartFailure.TIMEOUT)
            }
            reader.join(1000L)
            val lines = output.toString().lineSequence().toSet()
            if (process.exitValue() != 0 || "FW_DONE" !in lines) {
                throw RootRestartException(RootRestartFailure.COMMAND)
            }
            val stopped = ScopeRestartCommand.packages.filterTo(mutableSetOf()) { pkg ->
                lines.any { it.startsWith("FW_STOPPED:$pkg:") && (it.substringAfterLast(':').toIntOrNull() ?: 0) > 0 }
            }
            return ScopeRestartResult(stopped, "FW_HOME_STARTED" in lines)
        } catch (e: TimeoutException) {
            throw RootRestartException(RootRestartFailure.TIMEOUT, e)
        } catch (e: RootRestartException) {
            throw e
        } catch (e: IOException) {
            throw RootRestartException(
                if (identity.getNow(null) == "0") RootRestartFailure.COMMAND else RootRestartFailure.DENIED, e,
            )
        } finally {
            // A late approval must never execute a previously queued restart after timeout.
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            reader.join(1000L)
        }
    }
}
