package com.boxhub.http

import android.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Minimal RFC 6455 WebSocket endpoint — text frames only, which is all the
 * BoxHub dashboard needs (status push + log tail).
 *
 * Hand rolled because the framework has no server-side WebSocket support and
 * pulling in a library for ~150 lines of framing is not worth the APK weight.
 */
class WsConnection(
    private val input: InputStream,
    private val output: OutputStream
) {
    private val sendLock = Any()
    @Volatile var open = true
        private set

    /** Blocks until the next text message, or null on close/error. */
    fun readMessage(): String? {
        val assembled = StringBuilder()
        while (open) {
            val b0 = input.read()
            if (b0 < 0) { open = false; return null }
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F

            val b1 = input.read()
            if (b1 < 0) { open = false; return null }
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                val h = readByte(); val l = readByte()
                if (h < 0 || l < 0) { open = false; return null }
                len = ((h.toLong() shl 8) or l.toLong())
            } else if (len == 127L) {
                var v = 0L
                repeat(8) {
                    val b = readByte()
                    if (b < 0) { open = false; return null }
                    v = (v shl 8) or b.toLong()
                }
                len = v
            }
            if (len < 0 || len > MAX_FRAME) { open = false; return null }

            val mask = if (masked) {
                val m = ByteArray(4)
                for (i in 0..3) m[i] = readByte().toByte()
                m
            } else null
            val payload = ByteArray(len.toInt())
            var read = 0
            while (read < payload.size) {
                val n = input.read(payload, read, payload.size - read)
                if (n < 0) { open = false; return null }
                read += n
            }
            if (mask != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }

            when (opcode) {
                OP_TEXT, OP_CONTINUATION -> {
                    assembled.append(String(payload, Charsets.UTF_8))
                    if (fin) return assembled.toString()
                }
                OP_BINARY -> if (fin) return "" else return ""
                OP_CLOSE -> { close(); return null }
                OP_PING -> sendFrame(OP_PONG, payload)
                OP_PONG -> { /* ignore */ }
                else -> { open = false; return null }
            }
        }
        return null
    }

    fun sendText(s: String) {
        if (!open) return
        sendFrame(OP_TEXT, s.toByteArray(Charsets.UTF_8))
    }

    fun ping() {
        if (!open) return
        sendFrame(OP_PING, ByteArray(0))
    }

    fun close() {
        if (!open) return
        open = false
        try { sendFrame(OP_CLOSE, ByteArray(0)) } catch (_: Exception) {}
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val header = java.io.ByteArrayOutputStream(10)
        header.write(0x80 or opcode)
        val n = payload.size
        when {
            n < 126 -> header.write(n)
            n <= 0xFFFF -> {
                header.write(126)
                header.write((n shr 8) and 0xFF)
                header.write(n and 0xFF)
            }
            else -> {
                header.write(127)
                for (s in 7 downTo 0) header.write(((n.toLong() shr (s * 8)) and 0xFF).toInt())
            }
        }
        synchronized(sendLock) {
            output.write(header.toByteArray())
            if (n > 0) output.write(payload)
            output.flush()
        }
    }

    private fun readByte(): Int = try { input.read() } catch (_: Exception) { -1 }

    companion object {
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val MAX_FRAME = 4L * 1024 * 1024

        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        fun acceptKey(clientKey: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
            val hash = digest.digest((clientKey + GUID).toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(hash, Base64.NO_WRAP)
        }
    }
}