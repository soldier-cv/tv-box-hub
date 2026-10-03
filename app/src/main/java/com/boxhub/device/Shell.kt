package com.boxhub.device

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Runs a command with a hard timeout, capturing stdout as raw bytes (needed
 * for `screencap -p`, which emits a PNG) and stderr as text.
 *
 * stdout and stderr are drained on separate threads — a single-threaded drain
 * deadlocks as soon as the child fills the pipe buffer of the other stream.
 */
object Shell {

    class Result(val code: Int, val out: ByteArray, val err: String) {
        val ok: Boolean get() = code == 0
        val outText: String get() = String(out, Charsets.UTF_8)
    }

    private const val DEFAULT_LIMIT = 16 * 1024 * 1024

    /**
     * Why the most recent [run] produced no process at all, or null when it did.
     *
     * `run` collapses "the binary is missing" and "we are not allowed to exec it"
     * into the same null, which is useless for telling the user what is wrong with
     * their box. Callers that care read this.
     */
    @Volatile var lastError: String? = null
        private set

    fun run(args: List<String>, timeoutMs: Long = 5_000, limit: Int = DEFAULT_LIMIT): Result? {
        if (args.isEmpty()) return null
        lastError = null
        return try {
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(false)
            val p = pb.start()

            val outBuf = ByteArrayOutputStream()
            val errBuf = ByteArrayOutputStream()

            val t1 = Thread { copy(p.inputStream, outBuf, limit) }
            val t2 = Thread { copy(p.errorStream, errBuf, 64 * 1024) }
            t1.isDaemon = true; t2.isDaemon = true
            t1.start(); t2.start()

            p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (p.isAlive) {
                p.destroy()
                // Give the readers a moment to flush what is already buffered.
                t1.join(150); t2.join(150)
                Result(-1, outBuf.toByteArray(), "timeout")
            } else {
                t1.join(400); t2.join(400)
                Result(p.exitValue(), outBuf.toByteArray(), String(errBuf.toByteArray(), Charsets.UTF_8).trim())
            }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
            null
        }
    }

    private fun copy(input: InputStream, sink: ByteArrayOutputStream, limit: Int) {
        try {
            val buf = ByteArray(16 * 1024)
            while (sink.size() < limit) {
                val n = input.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
            }
        } catch (_: Exception) {}
    }

    /** Convenience: run and return trimmed stdout, or null on any failure. */
    fun text(args: List<String>, timeoutMs: Long = 5_000): String? =
        run(args, timeoutMs)?.takeIf { it.ok }?.outText?.trim()
}