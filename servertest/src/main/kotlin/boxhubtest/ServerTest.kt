package boxhubtest

import com.boxhub.http.BodyWriter
import com.boxhub.http.ChunkedInputStream
import com.boxhub.http.MiniServer
import com.boxhub.http.Req
import com.boxhub.http.Resp
import com.boxhub.http.WsConnection
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/* ------------------------------------------------------------------ *
 * tiny test harness
 * ------------------------------------------------------------------ */

internal var passed = 0
internal val failures = ArrayList<String>()

internal fun check(name: String, ok: Boolean, detail: String? = null) {
    if (ok) {
        passed++
        println("  PASS   $name")
    } else {
        failures.add(name)
        println("  FAIL   $name   ${if (detail.isNullOrEmpty()) "" else "-> $detail"}")
    }
}

internal class Head(val status: Int, val reason: String, val headers: Map<String, String>) {
    fun header(n: String): String? = headers[n.lowercase()]
}

/** Raw-socket client so we control exact framing. */
internal class Client(port: Int) : AutoCloseable {
    val sock: Socket = Socket("127.0.0.1", port).apply { soTimeout = 8000; tcpNoDelay = true }
    private val input: InputStream = sock.getInputStream()
    val output: OutputStream = sock.getOutputStream()

    fun send(text: String) { output.write(text.toByteArray(Charsets.ISO_8859_1)); output.flush() }
    fun send(bytes: ByteArray) { output.write(bytes); output.flush() }
    fun sendBytes(bytes: ByteArray) { output.write(bytes); output.flush() }

    /** Reads one byte at a time so nothing past the header terminator is consumed. */
    fun readHead(): Head {
        val raw = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val c = input.read()
            if (c < 0) throw IllegalStateException("eof while reading headers")
            raw.write(c)
            matched = when {
                c == '\r'.code && matched == 0 -> 1
                c == '\n'.code && matched == 1 -> 2
                c == '\r'.code && matched == 2 -> 3
                c == '\n'.code && matched == 3 -> 4
                else -> 0
            }
            if (matched == 4) break
        }
        val lines = String(raw.toByteArray(), Charsets.UTF_8).split("\r\n")
        val parts = lines[0].split(' ')
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val l = lines[i]
            if (l.isEmpty()) break
            val c = l.indexOf(':')
            if (c > 0) headers[l.substring(0, c).trim().lowercase()] = l.substring(c + 1).trim()
        }
        return Head(parts[1].toInt(), parts.getOrElse(2) { "" }, headers)
    }

    fun readExactly(n: Int): ByteArray {
        val b = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(b, read, n - read)
            if (r < 0) throw IllegalStateException("eof after $read of $n bytes")
            read += r
        }
        return b
    }

    fun readAtMost(n: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val b = ByteArray(8192)
        while (out.size() < n) {
            val r = input.read(b)
            if (r < 0) break
            out.write(b, 0, r)
        }
        return out.toByteArray()
    }

    override fun close() { try { sock.close() } catch (_: Exception) {} }
}

private fun frame(opcode: Int, payload: ByteArray, masked: Boolean): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(0x80 or opcode)
    val n = payload.size
    when {
        n < 126 -> out.write((if (masked) 0x80 else 0) or n)
        n <= 0xFFFF -> {
            out.write((if (masked) 0x80 else 0) or 126)
            out.write((n shr 8) and 0xFF)
            out.write(n and 0xFF)
        }
        else -> {
            out.write((if (masked) 0x80 else 0) or 127)
            for (s in 7 downTo 0) out.write(((n.toLong() shr (s * 8)) and 0xFF).toInt())
        }
    }
    if (masked) {
        val key = ByteArray(4) { Random.nextInt(256).toByte() }
        out.write(key)
        for (i in payload.indices) out.write((payload[i].toInt() xor key[i % 4].toInt()) and 0xFF)
    } else {
        out.write(payload)
    }
    return out.toByteArray()
}

private fun readFrame(input: InputStream): Pair<Int, ByteArray>? {
    val b0 = input.read()
    if (b0 < 0) return null
    val opcode = b0 and 0x0F
    val b1 = input.read()
    if (b1 < 0) return null
    val masked = (b1 and 0x80) != 0
    var len = (b1 and 0x7F).toLong()
    if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
    if (len == 127L) {
        var v = 0L
        repeat(8) { v = (v shl 8) or input.read().toLong() }
        len = v
    }
    val mask = if (masked) ByteArray(4) { input.read().toByte() } else null
    val data = ByteArray(len.toInt())
    var read = 0
    while (read < data.size) {
        val n = input.read(data, read, data.size - read)
        if (n < 0) break
        read += n
    }
    if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
    return opcode to data
}

private fun drain(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val b = ByteArray(8192)
    while (true) {
        val n = input.read(b)
        if (n < 0) break
        out.write(b, 0, n)
    }
    return out.toByteArray()
}

/* ------------------------------------------------------------------ *
 * server under test
 * ------------------------------------------------------------------ */

private val lastBody = AtomicReference<ByteArray?>(null)

internal fun dispatch(req: Req): Resp {
    when (req.path) {
        "/echo" -> {
            val body = req.body?.let { drain(it) } ?: ByteArray(0)
            lastBody.set(body)
            val q = req.query.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
            val payload = "method=${req.method} path=${req.path} query=$q keep=${req.keepAlive} body=${String(body, Charsets.UTF_8)}"
            return Resp.text(payload)
        }
        "/sha" -> {
            val body = req.body?.let { drain(it) } ?: ByteArray(0)
            val d = MessageDigest.getInstance("SHA-256").digest(body)
            val hex = d.joinToString("") { "%02x".format(it) }
            return Resp.text("len=${body.size} sha=$hex")
        }
        "/stream" -> {
            val total = req.qInt("n", 0)
            return Resp(
                contentType = "application/octet-stream",
                writer = BodyWriter { out -> for (i in 0 until total) out.write(i and 0xFF) },
                contentLength = total.toLong(),
                headers = listOf("Accept-Ranges" to "bytes")
            )
        }
        "/range" -> {
            val total = req.qInt("n", 0)
            val range = req.h("range")
            var start = 0
            var end = total - 1
            var status = 200
            if (range != null && range.startsWith("bytes=") && total > 0) {
                val parts = range.removePrefix("bytes=").split('-')
                if (parts[0].isNotBlank()) start = parts[0].trim().toInt()
                if (parts.size > 1 && parts[1].isNotBlank()) end = parts[1].trim().toInt()
                if (end >= total) end = total - 1
                status = 206
            }
            val len = if (total <= 0) 0 else end - start + 1
            val hs = ArrayList<Pair<String, String>>()
            hs.add("Accept-Ranges" to "bytes")
            if (status == 206) hs.add("Content-Range" to "bytes $start-$end/$total")
            return Resp(
                status = status,
                contentType = "application/octet-stream",
                writer = BodyWriter { out -> for (i in start until start + len) out.write(i and 0xFF) },
                contentLength = len.toLong(),
                headers = hs
            )
        }
        "/nocontent" -> return Resp(204, "image/x-icon", ByteArray(0), contentLength = 0)
        // Regression: a handler that hand-counts Content-Length wrong used to
        // desync keep-alive framing, because the extra/missing byte was parsed
        // as the next request line.
        "/badlength" -> return Resp(401, "text/plain; charset=utf-8", "unauthorized".toByteArray(), contentLength = 11L)
        "/boom" -> throw IllegalStateException("dispatch exploded on purpose")
    }
    return Resp.text("not found: ${req.method} ${req.path}", 404)
}

private val wsGot = AtomicReference<String?>(null)
private val wsEcho = AtomicReference<String?>(null)

fun main() {
    val server = MiniServer(18790, ::dispatch, onWebSocket = { conn: WsConnection, _ ->
        val msg = conn.readMessage()
        wsGot.set(msg)
        conn.sendText("echo:$msg")
        // stay open briefly so the client can read the frame
        Thread.sleep(120)
        conn.close()
    })

    if (!server.start()) {
        println("FATAL: server did not start: ${server.lastError}")
        return
    }
    println("BoxHub server test  (port 18790)\n")

    try {
        testChunkedStreamDirect()
        testContentLengthAuthority()
        testBasicGet()
        testKeepAlive()
        testHeadAndNoContent()
        testPostBody()
        testChunkedBody()
        testLargeBody()
        testStreamingResponse()
        testRange()
        testErrorHandling()
        testConcurrent()
        testWebSocket()
        pinGuardTests()
    } finally {
        server.stop()
    }

    println("\n--------------------------------------------------")
    println("passed: $passed   failed: ${failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        System.exit(1)
    }
    println("ALL GREEN")
}

/* ------------------------------------------------------------------ *
 * tests
 * ------------------------------------------------------------------ */

private fun testChunkedStreamDirect() {
    println("\n[0] ChunkedInputStream in isolation (no sockets)")
    fun bodyOf(wire: String): ByteArray =
        drain(ChunkedInputStream(java.io.ByteArrayInputStream(wire.toByteArray(Charsets.ISO_8859_1))))

    val one = bodyOf("70\r\n" + "a".repeat(112) + "\r\n0\r\n\r\n")
    check("single 112-byte chunk", one.size == 112, "got ${one.size}")

    val many = bodyOf("5\r\nhello\r\n6\r\n-world\r\n0\r\n\r\n")
    check("two small chunks", String(many) == "hello-world", String(many))

    val ext = bodyOf("3;ext=1\r\nabc\r\n3;ext=2\r\ndef\r\n0\r\nX-T: v\r\n\r\n")
    check("chunk extensions + trailer", String(ext) == "abcdef", String(ext))

    val mismatched = bodyOf("64\r\n" + "a".repeat(112) + "\r\n0\r\n\r\n")
    check("mismatched size does not hang or explode", mismatched.size <= 8192, "got ${mismatched.size}")

    val empty = bodyOf("0\r\n\r\n")
    check("empty body", empty.isEmpty(), "got ${empty.size}")

    val bulk = ChunkedInputStream(java.io.ByteArrayInputStream(("70\r\n" + "b".repeat(112) + "\r\n0\r\n\r\n").toByteArray(Charsets.ISO_8859_1)))
    val buf = ByteArray(64)
    var total = 0
    while (true) {
        val n = bulk.read(buf, 0, buf.size)
        if (n < 0) break
        total += n
    }
    check("bulk path total length", total == 112, "got $total")
}

private fun testContentLengthAuthority() {
    println("\n[17] byte length overrides a hand-counted Content-Length")
    Client(18790).use { c ->
        c.send("GET /badlength HTTP/1.1\r\nHost: box\r\n\r\n")
        val h = c.readHead()
        val declared = h.header("content-length")?.toIntOrNull() ?: -1
        check("declared length matches the real body", declared == 12, declared.toString())
        val body = String(c.readExactly(declared), Charsets.UTF_8)
        check("body delivered intact", body == "unauthorized", body)

        // The whole point: the socket must still be in sync afterwards.
        c.send("GET /echo?after=desync HTTP/1.1\r\nHost: box\r\nConnection: close\r\n\r\n")
        val h2 = c.readHead()
        val body2 = String(c.readExactly(h2.header("content-length")!!.toInt()), Charsets.UTF_8)
        check("keep-alive stays in sync after a short/long body", h2.status == 200 && body2.contains("after=desync"), body2)
    }
}

private fun testBasicGet() {
    println("\n[1] basic GET, query parsing, percent decoding")
    Client(18790).use { c ->
        c.send("GET /echo?k=1234&path=%E4%B8%AD%E6%96%87%2F%E7%94%B5%E5%BD%B1 HTTP/1.1\r\nHost: box:18790\r\nConnection: close\r\n\r\n")
        val h = c.readHead()
        check("status is 200", h.status == 200, h.status.toString())
        check("content-type text/plain", (h.header("content-type") ?: "").startsWith("text/plain"), h.header("content-type").toString())
        val len = h.header("content-length")?.toIntOrNull() ?: -1
        check("content-length present", len > 0, h.header("content-length").toString())
        val body = String(c.readExactly(len), Charsets.UTF_8)
        check("method echoed", body.contains("method=GET"), body)
        check("query echoed", body.contains("k=1234"), body)
        check("path percent-decoded to UTF-8", body.contains("path=中文/电影"), body)
        check("Connection: close honoured", (h.header("connection") ?: "") == "close", h.header("connection").toString())
    }
}

private fun testKeepAlive() {
    println("\n[2] keep-alive: two requests on one socket")
    Client(18790).use { c ->
        c.send("GET /echo?a=1 HTTP/1.1\r\nHost: box\r\n\r\n")
        val h1 = c.readHead()
        val b1 = c.readExactly(h1.header("content-length")!!.toInt())
        check("first request on same connection", String(b1).contains("a=1"), String(b1))
        check("keep-alive announced", (h1.header("connection") ?: "") == "keep-alive", h1.header("connection").toString())

        c.send("GET /echo?b=2 HTTP/1.1\r\nHost: box\r\n\r\n")
        val h2 = c.readHead()
        val b2 = c.readExactly(h2.header("content-length")!!.toInt())
        check("second request reuses socket", String(b2).contains("b=2"), String(b2))
    }
}

private fun testHeadAndNoContent() {
    println("\n[3] HEAD and 204 responses")
    Client(18790).use { c ->
        c.send("HEAD /echo HTTP/1.1\r\nHost: box\r\nConnection: close\r\n\r\n")
        val h = c.readHead()
        check("HEAD keeps Content-Length", (h.header("content-length")?.toIntOrNull() ?: 0) > 0, h.header("content-length").toString())
        check("HEAD sends no body", drain(c.sock.getInputStream()).isEmpty(), "unexpected body bytes")
    }
    Client(18790).use { c ->
        c.send("GET /nocontent HTTP/1.1\r\nHost: box\r\nConnection: close\r\n\r\n")
        val h = c.readHead()
        check("204 for empty payload", h.status == 204, h.status.toString())
        check("204 has zero length", h.header("content-length")?.toIntOrNull() == 0, h.header("content-length").toString())
    }
}

private fun testPostBody() {
    println("\n[4] POST with Content-Length body")
    val payload = "hello=世界".toByteArray(Charsets.UTF_8)
    Client(18790).use { c ->
        val head = "POST /echo HTTP/1.1\r\nHost: box\r\nContent-Type: text/plain\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
        c.send(head)
        c.sendBytes(payload)
        val h = c.readHead()
        val body = String(c.readExactly(h.header("content-length")!!.toInt()), Charsets.UTF_8)
        check("body forwarded intact (utf-8)", body.contains("body=hello=世界"), body)
        check("body length matches", lastBody.get()!!.contentEquals(payload), "len=${lastBody.get()!!.size}")
    }
}

private fun testChunkedBody() {
    println("\n[5] POST with chunked Transfer-Encoding")
    Client(18790).use { c ->
        c.send("POST /echo HTTP/1.1\r\nHost: box\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
        c.send("5\r\nhello\r\n")
        c.send("6\r\n-world\r\n")
        c.send("0\r\n\r\n")
        val h = c.readHead()
        val body = String(c.readExactly(h.header("content-length")!!.toInt()), Charsets.UTF_8)
        check("chunked body reassembled across chunks", body.contains("body=hello-world"), body)
    }
    // chunk extensions + trailer header, then the terminating chunk
    Client(18790).use { c ->
        c.send("POST /echo HTTP/1.1\r\nHost: box\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
        c.send("3;ext=1\r\nabc\r\n")
        c.send("3;ext=2\r\ndef\r\n")
        c.send("0\r\nX-Trailer: v\r\n\r\n")
        val h = c.readHead()
        val body = String(c.readExactly(h.header("content-length")!!.toInt()), Charsets.UTF_8)
        check("chunk extensions and trailers tolerated", body.contains("body=abcdef"), body)
    }
    // A single large chunk — this is what a browser actually sends when it PUTs
    // a whole file with chunked encoding. Payload length is computed, never
    // hand-counted, so the framing cannot silently disagree.
    val payload = "0123456789abcdef".repeat(6)
    Client(18790).use { c ->
        c.send("POST /sha HTTP/1.1\r\nHost: box\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
        c.send("%x\r\n".format(payload.length))
        c.send(payload + "\r\n")
        c.send("0\r\n\r\n")
        val h = c.readHead()
        val body = String(c.readExactly(h.header("content-length")!!.toInt()), Charsets.UTF_8)
        check("single ${payload.length}-byte chunk decodes", body.contains("len=${payload.length}"), body)
    }
    // chunk declares fewer bytes than actually sent: server must bail out on
    // the malformed framing instead of blocking forever on missing data.
    Client(18790).use { c ->
        c.send("POST /sha HTTP/1.1\r\nHost: box\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
        c.send("10\r\n")
        c.send(payload + "\r\n")
        c.send("0\r\n\r\n")
        val h = c.readHead()
        check("truncated chunk size still answers", h.status == 200, h.status.toString())
    }
    // chunk declares MORE bytes than are sent: the client closes, the server
    // must see EOF and recover rather than wedge the connection.
    Client(18790).use { c ->
        c.send("POST /sha HTTP/1.1\r\nHost: box\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
        c.send("%x\r\n".format(payload.length * 4))
        c.send(payload + "\r\n")
        c.close()
    }
    Thread.sleep(300)
    // absurd chunk size (would overflow an Int remainder) must be refused
    Client(18790).use { c ->
        c.send("POST /sha HTTP/1.1\r\nHost: box\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
        c.send("fffffffffffffff\r\n")
        c.send("0\r\n\r\n")
        val h = c.readHead()
        check("overflowing chunk size refused, still answers", h.status == 200, h.status.toString())
    }
}

private fun testLargeBody() {
    println("\n[6] 2 MiB upload integrity (SHA-256 round trip)")
    val data = ByteArray(2 * 1024 * 1024) { (it and 0xFF).toByte() }
    val expected = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it) }
    Client(18790).use { c ->
        c.send("PUT /sha HTTP/1.1\r\nHost: box\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n")
        c.sendBytes(data)
        val h = c.readHead()
        val body = String(c.readExactly(h.header("content-length")!!.toInt()), Charsets.UTF_8)
        check("2 MiB length reported", body.contains("len=${data.size}"), body)
        check("2 MiB checksum matches", body.contains(expected), body.take(90))
    }
}

private fun testStreamingResponse() {
    println("\n[7] streamed writer response")
    Client(18790).use { c ->
        c.send("GET /stream?n=5000 HTTP/1.1\r\nHost: box\r\nConnection: close\r\n\r\n")
        val h = c.readHead()
        check("stream content-length", h.header("content-length") == "5000", h.header("content-length").toString())
        val body = c.readExactly(5000)
        val ok = body.indices.all { body[it] == (it and 0xFF).toByte() }
        check("stream bytes in order", ok, "first mismatch")
    }
}

private fun testRange() {
    println("\n[8] HTTP Range (needed for <video> seeking)")
    Client(18790).use { c ->
        c.send("GET /range?n=1000 HTTP/1.1\r\nHost: box\r\nRange: bytes=100-199\r\nConnection: close\r\n\r\n")
        val h = c.readHead()
        check("206 Partial Content", h.status == 206, h.status.toString())
        check("Content-Range header", h.header("content-range") == "bytes 100-199/1000", h.header("content-range").toString())
        check("range length is 100", h.header("content-length") == "100", h.header("content-length").toString())
        val body = c.readExactly(100)
        check("range starts at byte 100", body[0] == 100.toByte(), body[0].toString())
        check("range ends at byte 199", body[99] == 199.toByte(), body[99].toString())
    }
    Client(18790).use { c ->
        c.send("GET /range?n=1000 HTTP/1.1\r\nHost: box\r\nRange: bytes=900-\r\nConnection: close\r\n\r\n")
        val h = c.readHead()
        check("open-ended range clamped", h.header("content-length") == "100", h.header("content-length").toString())
    }
}

private fun testErrorHandling() {
    println("\n[9] handler exception does not kill the connection or the server")
    Client(18790).use { c ->
        c.send("GET /boom HTTP/1.1\r\nHost: box\r\nConnection: close\r\n\r\n")
        val h = c.readHead()
        check("exception becomes HTTP 500", h.status == 500, h.status.toString())
        val body = String(c.readExactly(h.header("content-length")!!.toInt()), Charsets.UTF_8)
        check("500 body is valid json envelope", body.startsWith("{\"ok\":false"), body)
    }
    Client(18790).use { c ->
        c.send("GET /nope HTTP/1.1\r\nHost: box\r\nConnection: close\r\n\r\n")
        val h = c.readHead()
        check("unknown route returns 404", h.status == 404, h.status.toString())
    }
    Client(18790).use { c ->
        // Malformed request line: server must drop it, not spin.
        c.send("GARBAGE\r\n\r\n")
        val closed = try { drain(c.sock.getInputStream()).isEmpty() } catch (_: Exception) { true }
        check("malformed request closes cleanly", closed, "connection left open")
    }
}

private fun testConcurrent() {
    println("\n[10] 12 concurrent connections")
    val n = 12
    val latch = CountDownLatch(n)
    val okCount = AtomicInteger(0)
    val threads = (0 until n).map { i ->
        Thread {
            try {
                Client(18790).use { c ->
                    val p = "client$i"
                    c.send("POST /echo HTTP/1.1\r\nHost: box\r\nContent-Length: ${p.length}\r\nConnection: close\r\n\r\n$p")
                    val h = c.readHead()
                    val body = String(c.readExactly(h.header("content-length")!!.toInt()), Charsets.UTF_8)
                    if (h.status == 200 && body.contains("body=$p")) okCount.incrementAndGet()
                }
            } catch (_: Exception) {
            } finally { latch.countDown() }
        }.apply { isDaemon = true }
    }
    threads.forEach { it.start() }
    latch.await(25, TimeUnit.SECONDS)
    check("all $n concurrent requests answered correctly", okCount.get() == n, "ok=$okCount")
}

private fun testWebSocket() {
    println("\n[11] WebSocket handshake and text frames")
    val keyBytes = ByteArray(16) { Random.nextInt(256).toByte() }
    val key = java.util.Base64.getEncoder().encodeToString(keyBytes)
    val expectedAccept = java.util.Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.UTF_8))
    )

    wsGot.set(null); wsEcho.set(null)
    Client(18790).use { c ->
        c.send(
            "GET /ws?k=1234 HTTP/1.1\r\nHost: box:18790\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        )
        val h = c.readHead()
        check("101 Switching Protocols", h.status == 101, h.status.toString())
        check("Upgrade header echoed", (h.header("upgrade") ?: "").equals("websocket", true), h.header("upgrade").toString())
        check("Sec-WebSocket-Accept is correct RFC6455 value", h.header("sec-websocket-accept") == expectedAccept,
            "${h.header("sec-websocket-accept")} != $expectedAccept")

        val payload = "ping-世界".toByteArray(Charsets.UTF_8)
        c.sendBytes(frame(0x1, payload, masked = true))
        val f = readFrame(c.sock.getInputStream())
        // The handler echoes before replying, so the first frame the client
        // reads back is the echo. Decoding correctness is asserted via wsGot.
        check("server unmasks and echoes the UTF-8 text frame",
            f != null && String(f.second, Charsets.UTF_8) == "echo:ping-世界",
            f?.let { String(it.second, Charsets.UTF_8) } ?: "no frame")
        check("server frame is unmasked text", f != null && f.first == 0x1, f?.first?.toString() ?: "-")
        check("handler saw the decoded text", wsGot.get() == "ping-世界", wsGot.get().toString())
    }

    // A second connection must still be accepted after the first closed.
    println("\n[12] second WebSocket connection after close")
    wsGot.set(null)
    Client(18790).use { c ->
        c.send(
            "GET /ws?k=1234 HTTP/1.1\r\nHost: box:18790\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        )
        val h = c.readHead()
        check("reconnect gets 101", h.status == 101, h.status.toString())
        c.sendBytes(frame(0x1, "again".toByteArray(), masked = true))
        val f = readFrame(c.sock.getInputStream())
        check("second connection echoes too", f != null && String(f.second, Charsets.UTF_8) == "echo:again",
            f?.let { String(it.second, Charsets.UTF_8) } ?: "-")
    }

    println("\n[13] HTTP still served after WebSocket traffic")
    Client(18790).use { c ->
        c.send("GET /echo?after=ws HTTP/1.1\r\nHost: box\r\nConnection: close\r\n\r\n")
        val h = c.readHead()
        val body = String(c.readExactly(h.header("content-length")!!.toInt()), Charsets.UTF_8)
        check("server alive after sockets closed", h.status == 200 && body.contains("after=ws"), body)
    }

    println()
    testWebSocketQueryAuth()
    testFileOps()
    testLanScope()
    testAccessControlEnforced()
    testOwnerModel()
}

/**
 * The WebSocket upgrade must expose the pairing code through `Req.query`.
 *
 * This is the contract the app's socket handler depends on, and getting it wrong
 * is invisible: `Req.path` is the request target *without* the query string, so
 * a handler that looks for "?k=" there always found nothing, every socket was
 * answered with `{"event":"unauthorized"}`, and the dashboard slammed its PIN
 * gate back over a session that had just paired. HTTP kept working the whole
 * time, because the routes read `req.q("k")` — which is exactly why neither the
 * socket tests nor the jsdom UI tests noticed.
 */
private fun testWebSocketQueryAuth() {
    println("\n[14] a WebSocket upgrade carries the pairing code in Req.query")

    val seen = AtomicReference<String?>(null)
    val seenPath = AtomicReference<String?>(null)

    val server = MiniServer(18791, ::dispatch, onWebSocket = { conn: WsConnection, req: Req ->
        // Exactly what App.handleSocket does.
        seen.set(req.q("k"))
        seenPath.set(req.path)
        conn.sendText("""{"event":"hello"}""")
        Thread.sleep(60)
        conn.close()
    })
    if (!server.start()) {
        check("socket-auth server started", false, server.lastError)
        return
    }

    try {
        val key = "Y2h4aW5nZQ=="
        Client(18791).use { c ->
            c.send(
                "GET /ws?k=4821&v=2 HTTP/1.1\r\nHost: box:18791\r\nUpgrade: websocket\r\n" +
                    "Connection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
            )
            val h = c.readHead()
            check("upgrade accepted", h.status == 101, h.status.toString())
        }

        val path = seenPath.get() ?: "?"
        check("handler saw the pairing code from the query", seen.get() == "4821", seen.get().orEmpty())
        // Spelled out on purpose: this is the trap. Reading the code out of the
        // path yields "" here, which is what broke pairing in the field.
        check("Req.path deliberately excludes the query", path == "/ws", path)
        check(
            "so a path-based lookup would find no code at all",
            !path.contains('?'),
            "path=$path would yield an empty key"
        )
    } finally {
        server.stop()
    }
}