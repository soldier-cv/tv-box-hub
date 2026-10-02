package com.boxhub.http

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small, dependency-free HTTP/1.1 server with WebSocket upgrade support.
 *
 * Deliberately hand written rather than pulled from a framework: on API 24/25
 * TV-box firmware there is no dependency-compatibility risk, the APK stays
 * tiny, and the whole request path is under our control.
 *
 * Responses always carry an explicit Content-Length — we never use
 * Transfer-Encoding: chunked on the way out, which keeps the writer path simple.
 * Request bodies may use either Content-Length or chunked.
 */

class Req(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: InputStream?,
    val keepAlive: Boolean
) {
    fun q(key: String, fallback: String = ""): String = query[key] ?: fallback
    fun qInt(key: String, fallback: Int): Int = query[key]?.toIntOrNull() ?: fallback
    fun qLong(key: String, fallback: Long): Long = query[key]?.toLongOrNull() ?: fallback
    fun h(key: String): String? = headers[key.lowercase()]
    fun isWebSocketUpgrade(): Boolean =
        (h("upgrade") ?: "").equals("websocket", true) && (h("connection") ?: "").contains("upgrade", true)
}

/** Streams a response body. The total length must be known up front. */
fun interface BodyWriter {
    fun write(out: OutputStream)
}

class Resp(
    val status: Int = 200,
    val contentType: String = "application/json; charset=utf-8",
    val body: ByteArray? = null,
    val writer: BodyWriter? = null,
    val contentLength: Long = -1L,
    val headers: List<Pair<String, String>> = emptyList()
) {
    companion object {
        fun text(s: String, status: Int = 200): Resp {
            val b = s.toByteArray(Charsets.UTF_8)
            return Resp(status, "text/plain; charset=utf-8", b, contentLength = b.size.toLong())
        }

        fun json(s: String, status: Int = 200): Resp {
            val b = s.toByteArray(Charsets.UTF_8)
            return Resp(status, "application/json; charset=utf-8", b, contentLength = b.size.toLong())
        }

        fun bytes(b: ByteArray, type: String, status: Int = 200, headers: List<Pair<String, String>> = emptyList()): Resp =
            Resp(status, type, b, contentLength = b.size.toLong(), headers = headers)
    }
}

class MiniServer(
    private val port: Int,
    private val dispatch: (Req) -> Resp,
    private val onWebSocket: (WsConnection, Req) -> Unit,
    private val accessCheck: (InetAddress) -> Boolean = { true }
) {
    @Volatile var running = false
        private set

    @Volatile var startedAt = 0L
        private set

    @Volatile var lastError: String? = null
        private set

    private val connections = AtomicInteger(0)
    val activeConnections: Int get() = connections.get()

    private var serverSocket: ServerSocket? = null
    private var pool: ExecutorService? = null
    private var acceptThread: Thread? = null

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("0.0.0.0", port), 64)
            serverSocket = ss
            pool = Executors.newCachedThreadPool { r -> Thread(r, "boxhub-io").apply { isDaemon = true } }
            running = true
            startedAt = System.currentTimeMillis()
            lastError = null
            acceptThread = Thread({ acceptLoop(ss) }, "boxhub-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            lastError = e.toString()
            running = false
            serverSocket = null
            pool = null
            false
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { pool?.shutdownNow() } catch (_: Exception) {}
        serverSocket = null
        pool = null
        acceptThread = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        val executor = pool ?: return
        while (running) {
            val client = try {
                ss.accept()
            } catch (e: IOException) {
                if (running) continue else break
            } catch (_: Exception) {
                break
            }
            try {
                executor.execute { serve(client) }
            } catch (_: Exception) {
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun serve(socket: Socket) {
        connections.incrementAndGet()
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 90_000
            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 32 * 1024)

            // Reject peers outside the private ranges before reading anything,
            // so a WAN-reachable port never serves a response body.
            val peer = socket.inetAddress
            if (!accessCheck(peer)) {
                try {
                    writeResponse(output, Resp.json("""{"ok":false,"error":"仅允许局域网访问"}""", 403), "GET", false)
                } catch (_: Exception) {}
                return
            }

            while (running && !socket.isClosed) {
                val head = readHead(input) ?: break
                val req = parseHead(head, input) ?: break

                if (req.isWebSocketUpgrade()) {
                    val key = req.h("sec-websocket-key")
                    if (key != null) {
                        output.write(wsHandshake(key).toByteArray(Charsets.ISO_8859_1))
                        output.flush()
                        try { onWebSocket(WsConnection(input, output), req) } catch (_: Exception) {}
                    }
                    return
                }

                val resp = try {
                    dispatch(req)
                } catch (t: Throwable) {
                    Resp.json("""{"ok":false,"error":${Json.str(t.javaClass.simpleName + ": " + (t.message ?: ""))}}""", 500)
                }

                // Always consume the request body before moving to the next
                // request on a keep-alive connection, otherwise the leftover
                // bytes would be parsed as the next request line.
                try { req.body?.let { drain(it) } } catch (_: Exception) {}

                writeResponse(output, resp, req.method, req.keepAlive)
                if (!req.keepAlive) break
            }
        } catch (_: Exception) {
        } finally {
            connections.decrementAndGet()
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun wsHandshake(key: String): String = buildString {
        append("HTTP/1.1 101 Switching Protocols\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Accept: ").append(WsConnection.acceptKey(key)).append("\r\n\r\n")
    }

    private fun writeResponse(out: OutputStream, r: Resp, method: String, keepAlive: Boolean) {
        val headOnly = method.equals("HEAD", true)
        // The actual bytes are authoritative for a buffered body. Trusting a
        // hand-passed contentLength here once desynced keep-alive framing and
        // corrupted the *next* request on the socket, so never do it.
        val length = if (r.body != null) r.body.size.toLong() else r.contentLength
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
        sb.append("Content-Type: ").append(r.contentType).append("\r\n")
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        for ((k, v) in r.headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))

        if (!headOnly) {
            when {
                r.writer != null -> r.writer.write(out)
                r.body != null -> out.write(r.body)
            }
        }
        out.flush()
    }

    // ---- request parsing -------------------------------------------------

    /**
     * Reads up to and including the CRLFCRLF header terminator, one byte at a
     * time so the underlying buffered stream is never over-read: after this
     * returns, the very next byte read is the first byte of the body.
     */
    private fun readHead(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream(1024)
        var matched = 0
        while (buf.size() < 32 * 1024) {
            val c = try { input.read() } catch (_: SocketTimeoutException) { return null }
            if (c < 0) return null
            buf.write(c)
            matched = when {
                c == '\r'.code && matched == 0 -> 1
                c == '\n'.code && matched == 1 -> 2
                c == '\r'.code && matched == 2 -> 3
                c == '\n'.code && matched == 3 -> 4
                else -> 0
            }
            if (matched == 4) return buf.toByteArray()
        }
        return null
    }

    private fun parseHead(raw: ByteArray, input: InputStream): Req? {
        val lines = String(raw, Charsets.UTF_8).split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size < 2) return null
        val method = requestLine[0].uppercase()
        val target = requestLine[1]

        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            val c = line.indexOf(':')
            if (c <= 0) continue
            headers[line.substring(0, c).trim().lowercase()] = line.substring(c + 1).trim()
        }

        val qIdx = target.indexOf('?')
        val rawPath = if (qIdx >= 0) target.substring(0, qIdx) else target
        val path = percentDecode(rawPath)
        val query = if (qIdx >= 0) parseQuery(target.substring(qIdx + 1)) else emptyMap()

        val connection = (headers["connection"] ?: "").lowercase()
        val version = if (requestLine.size >= 4) requestLine[3] else "HTTP/1.1"
        val keepAlive = if (connection.contains("close")) false
        else if (connection.contains("keep-alive")) true
        else !version.equals("HTTP/1.0", true)

        val chunked = (headers["transfer-encoding"] ?: "").lowercase().contains("chunked")
        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L

        val body: InputStream? = when {
            chunked -> ChunkedInputStream(input)
            contentLength > 0 -> LimitedInputStream(input, contentLength)
            else -> null
        }

        return Req(method, path, query, headers, body, keepAlive)
    }

    companion object {
        fun reason(code: Int): String = when (code) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 206 -> "Partial Content"
            302 -> "Found"
            400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 409 -> "Conflict"; 413 -> "Payload Too Large"
            416 -> "Range Not Satisfiable"; 500 -> "Internal Server Error"
            501 -> "Not Implemented"; 503 -> "Service Unavailable"
            else -> "OK"
        }

        fun percentDecode(s: String): String {
            if (s.indexOf('%') < 0 && s.indexOf('+') < 0) return s
            val out = ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '%' && i + 2 < s.length -> {
                        val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                        if (v != null) { out.write(v); i += 3 } else { out.write(c.code); i += 1 }
                    }
                    c == '+' -> { out.write(' '.code); i += 1 }
                    else -> { out.write(c.toString().toByteArray(Charsets.UTF_8)); i += 1 }
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        fun parseQuery(qs: String): Map<String, String> {
            if (qs.isEmpty()) return emptyMap()
            val map = LinkedHashMap<String, String>()
            for (pair in qs.split('&')) {
                if (pair.isEmpty()) continue
                val i = pair.indexOf('=')
                val k = if (i >= 0) pair.substring(0, i) else pair
                val v = if (i >= 0) pair.substring(i + 1) else ""
                map[percentDecode(k)] = percentDecode(v)
            }
            return map
        }

        private fun drain(input: InputStream) {
            val buf = ByteArray(16 * 1024)
            while (input.read(buf) >= 0) { /* discard */ }
        }
    }
}

// ---------------------------------------------------------------------------
// Body streams
// ---------------------------------------------------------------------------

class LimitedInputStream(private val src: InputStream, private var remain: Long) : InputStream() {
    override fun read(): Int {
        if (remain <= 0) return -1
        val b = src.read()
        if (b >= 0) remain--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remain <= 0) return -1
        val n = src.read(b, off, minOf(len.toLong(), remain).toInt())
        if (n > 0) remain -= n
        return n
    }

    override fun available(): Int = minOf(src.available().toLong(), remain).toInt()
}

class ChunkedInputStream(private val src: InputStream) : InputStream() {
    private var chunkRemain = 0
    private var finished = false
    private var needCrlf = false

    private fun readLine(): String? {
        val sb = StringBuilder(32)
        while (true) {
            val c = src.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    /**
     * Positions the stream at the start of the next chunk's data.
     *
     * The CRLF terminating a chunk's data must be consumed explicitly:
     * readLine() strips CR, so reading the next size line naively would see an
     * empty string and wrongly conclude the body had ended.
     */
    private fun advance(): Boolean {
        if (finished) return false
        if (needCrlf) {
            needCrlf = false
            val c = src.read()
            if (c == '\r'.code) src.read()
        }
        val line = readLine() ?: run { finished = true; return false }
        val sizeStr = line.substringBefore(';').trim()
        val size = if (sizeStr.isEmpty()) 0L else sizeStr.toLongOrNull(16) ?: -1L
        // A malformed size must not be able to overflow the Int remainder or
        // spin the reader — bail out and treat the body as finished.
        if (size <= 0L || size > Int.MAX_VALUE.toLong()) {
            finished = true
            try { while (true) { if (readLine()?.isEmpty() != false) break } } catch (_: Exception) {}
            return false
        }
        chunkRemain = size.toInt()
        return true
    }

    override fun read(): Int {
        if (chunkRemain == 0 && !advance()) return -1
        val b = src.read()
        if (b < 0) { finished = true; return -1 }
        chunkRemain--
        if (chunkRemain == 0) needCrlf = true
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (chunkRemain == 0 && !advance()) return -1
        val n = src.read(b, off, minOf(len, chunkRemain))
        if (n > 0) {
            chunkRemain -= n
            if (chunkRemain == 0) needCrlf = true
        }
        return n
    }

    override fun available(): Int = chunkRemain
}

// ---------------------------------------------------------------------------
// Minimal JSON string emitter. Request bodies are never parsed as JSON — the
// API is query-parameter driven on purpose, so an emitter is all we need.
// ---------------------------------------------------------------------------

object Json {
    fun str(v: String?): String {
        if (v == null) return "null"
        val sb = StringBuilder(v.length + 8)
        sb.append('"')
        for (c in v) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\u000C")
                else -> if (c.code < 0x20) sb.append("\\u").append(String.format("%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun num(v: Long): String = v.toString()
    fun num(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    fun bool(v: Boolean): String = if (v) "true" else "false"

    fun obj(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", "{", "}") { Json.str(it.first) + ":" + it.second }
}
