package com.boxhub.device

import android.util.Log

/**
 * Grabs the current screen as a PNG using `/system/bin/screencap`, which reads
 * the framebuffer directly — no video encoding/decoding involved. This is a
 * 1-3 fps "where is the focus" preview for the remote, not a screen mirror.
 */
object ScreenCapture {

    private const val TAG = "BoxHub/Cap"
    private const val MAX_PNG = 24 * 1024 * 1024

    @Volatile
    var available: Boolean? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastSize: Int = 0
        private set

    fun probe(): Boolean {
        val png = capture()
        available = png != null
        return available == true
    }

    fun png(): ByteArray? = capture()

    private fun capture(): ByteArray? {
        return try {
            val r = Shell.run(listOf("screencap", "-p"), 6_000, MAX_PNG)
            when {
                r == null -> { lastError = "screencap could not be executed"; null }
                r.out.isEmpty() -> { lastError = "screencap produced no data (exit ${r.code}) ${r.err}"; null }
                else -> {
                    // A PNG always starts with the 8-byte PNG signature. If the
                    // bytes do not match, the exec failed and we are looking at
                    // an error string on stdout — treat it as unavailable rather
                    // than shipping a broken image to the browser.
                    if (isPng(r.out)) {
                        lastError = null
                        lastSize = r.out.size
                        r.out
                    } else {
                        lastError = "unexpected output: " + String(r.out.copyOfRange(0, minOf(120, r.out.size)), Charsets.UTF_8)
                        Log.w(TAG, lastError ?: "")
                        null
                    }
                }
            }
        } catch (t: Throwable) {
            lastError = t.toString()
            null
        }
    }

    private fun isPng(b: ByteArray): Boolean {
        if (b.size < 8) return false
        val sig = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in 0..7) if (b[i] != sig[i]) return false
        return true
    }
}