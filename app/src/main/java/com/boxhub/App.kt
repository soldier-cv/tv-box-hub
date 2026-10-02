package com.boxhub

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.boxhub.device.KeyInjector
import com.boxhub.device.ScreenCapture
import com.boxhub.device.Shell
import com.boxhub.fs.FileOps
import com.boxhub.http.LanScope
import com.boxhub.http.MiniServer
import com.boxhub.http.PinGuard
import com.boxhub.http.Resp
import com.boxhub.http.WsConnection
import java.io.File
import java.net.NetworkInterface
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide state: the LAN server, the file root, connected dashboards and
 * a small rolling event log.
 *
 * The server lifetime is bound to MainActivity on purpose — the user asked for
 * the port to exist only while the app is open on the TV, so this is a plain
 * foreground-lifetime socket rather than a sticky background service.
 */
object App {

    const val PORT = 8790

    @Volatile
    var server: MiniServer? = null
        private set

    lateinit var files: FileOps
        private set

    // Seeded so `pin` is a real 4-digit code even before the first start() call.
    private val guard = PinGuard().apply { reset(generatePin()) }

    /**
     * The pairing code, read straight out of [guard] — one source of truth, so
     * the number on the TV screen and the number the server accepts cannot drift
     * apart. (They did: the code was also cached in a field that start() rotated
     * without the guard hearing about it, so every pairing was refused.)
     *
     * Change it with [newPin], never by assignment.
     */
    val pin: String get() = guard.code

    /** Rotates the pairing code and forgets any accumulated backoff. */
    private fun newPin(): String = generatePin().also { guard.reset(it) }

    val clients = CopyOnWriteArrayList<WsConnection>()

    private val logLines = Collections.synchronizedList(ArrayList<String>())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    // PIN brute-force throttle lives in guard (see PinGuard).

    /**
     * Owners of the server's lifetime.
     *
     * The dashboard Activity and the keep-alive service both need to be able to
     * say "keep the port open" without either being able to switch it off under
     * the other. So neither calls start()/stop() directly: they register and
     * deregister an owner, and the port lives exactly as long as at least one
     * owner remains.
     *
     *   保持运行 off → owner is the Activity only. HOME does not destroy an
     *                  Activity, so the port survives pressing HOME and closes
     *                  when the user swipes the task away or stops the app.
     *                  This is the literal "only while the app is open".
     *   保持运行 on  → the service also owns it, so the port survives the
     *                  Activity being destroyed or the process being trimmed.
     */
    private val owners = Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile
    var suspended: Boolean = false
        private set

    private val prefs: SharedPreferences
        get() = Ctx.require().getSharedPreferences("boxhub", Context.MODE_PRIVATE)

    private var keepAlivePref: Boolean
        get() = prefs.getBoolean("keep_alive", false)
        set(value) = prefs.edit().putBoolean("keep_alive", value).apply()

    /** Whether the user asked for the port to outlive the dashboard Activity. */
    val keepAlive: Boolean get() = keepAlivePref

    /** Starts or stops the keep-alive service to match [value]. */
    fun setKeepAlive(value: Boolean) {
        if (keepAlivePref == value) return
        keepAlivePref = value
        suspended = false
        val ctx = Ctx.app ?: return
        if (value) {
            log("已开启保持运行")
            try {
                ctx.startForegroundService(Intent(ctx, HubService::class.java))
            } catch (t: Throwable) {
                log("无法启动前台服务: ${t.javaClass.simpleName}: ${t.message}")
                keepAlivePref = false
            }
        } else {
            log("已关闭保持运行")
            try { ctx.stopService(Intent(ctx, HubService::class.java)) } catch (_: Throwable) {}
            // onDestroy releases the owner, but do not depend on that callback
            // arriving before we decide the port should go down.
            owners.remove(OWNER_SERVICE)
            if (owners.isEmpty()) stop()
        }
    }

    fun acquire(owner: String) {
        owners.add(owner)
        if (!suspended) start()
    }

    fun release(owner: String) {
        owners.remove(owner)
        if (owners.isEmpty() && !suspended) stop()
    }

    /** Explicit user action: stop everything until the toggle is used again. */
    fun suspend() {
        suspended = true
        owners.clear()
        stop()
        try { Ctx.app?.stopService(Intent(Ctx.require(), HubService::class.java)) } catch (_: Throwable) {}
    }

    fun resume() {
        suspended = false
        acquire(OWNER_ACTIVITY)
        if (keepAlive) {
            try { Ctx.app?.startForegroundService(Intent(Ctx.require(), HubService::class.java)) } catch (_: Throwable) {}
        }
    }

    fun isKeepAliveRunning(): Boolean = owners.contains(OWNER_SERVICE)

    /** Ask the service to rebuild its notification with the current PIN. */
    private fun refreshServiceNotification() {
        val ctx = Ctx.app ?: return
        if (!isKeepAliveRunning()) {
            ctx.getSystemService(android.app.NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            return
        }
        try { ctx.startService(Intent(ctx, HubService::class.java)) } catch (_: Throwable) {}
    }

    val routes: Routes by lazy { Routes(files) }

    fun init(context: Context) {
        Ctx.app = context.applicationContext
        val root = Environment.getExternalStorageDirectory()
        files = FileOps(root)
        if (server == null) {
            server = MiniServer(PORT, routes::dispatch, { conn, req -> handleSocket(conn, req.path) }) { peer ->
                // LAN-only by policy, not merely by bind address.
                if (LanScope.isAllowed(peer)) true
                else {
                    log("已拒绝非局域网连接 ${peer.hostAddress ?: "?"}")
                    false
                }
            }
        }
    }

    fun start(): Boolean {
        val s = server ?: return false
        if (s.running) return true
        newPin()
        val ok = s.start()
        if (ok) {
            log("服务已启动 端口 $PORT  配对码 $pin")
            refreshServiceNotification()
            KeyInjector.probe()
            Thread {
                // Screencap can take a second on weak TV hardware; do it off
                // the main thread so first paint is not delayed.
                ScreenCapture.probe()
                log(
                    "遥控通道: ${KeyInjector.strategy}" +
                        " / 截屏: ${if (ScreenCapture.available == true) "可用" else "不可用"}"
                )
            }.apply { isDaemon = true }.start()
        } else {
            log("启动失败: ${s.lastError}")
        }
        return ok
    }

    fun stop() {
        server?.stop()
        clients.forEach { it.close() }
        clients.clear()
        log("服务已停止")
    }

    fun isRunning(): Boolean = server?.running == true

    fun startedAt(): Long = server?.startedAt ?: 0L

    fun lastError(): String? = server?.lastError

    fun log(message: String) {
        val line = "${timeFmt.format(Date())}  $message"
        synchronized(logLines) {
            logLines.add(line)
            while (logLines.size > 200) logLines.removeAt(0)
        }
        broadcast("log", """"line":${com.boxhub.http.Json.str(line)}""")
    }

    fun logs(): List<String> = synchronized(logLines) { ArrayList(logLines) }

    fun broadcast(event: String, fields: String) {
        val payload = """{"event":"$event",$fields}"""
        val dead = ArrayList<WsConnection>()
        clients.forEach { c ->
            try {
                if (c.open) c.sendText(payload) else dead.add(c)
            } catch (_: Exception) {
                dead.add(c)
            }
        }
        if (dead.isNotEmpty()) clients.removeAll(dead.toSet())
    }

    private fun handleSocket(conn: WsConnection, path: String) {
        if (!authorized(extractKey(path))) {
            conn.sendText("""{"event":"unauthorized"}""")
            conn.close()
            return
        }
        clients.add(conn)
        conn.sendText("""{"event":"hello","data":${infoBody()},"ts":${System.currentTimeMillis()}}""")
        try {
            while (conn.open) {
                val msg = conn.readMessage() ?: break
                when (msg) {
                    "ping" -> conn.sendText("""{"event":"pong","ts":${System.currentTimeMillis()}}""")
                    "info" -> conn.sendText("""{"event":"info","data":${infoBody()},"ts":${System.currentTimeMillis()}}""")
                    "logs" -> conn.sendText("""{"event":"logs","data":${logsJson()},"ts":${System.currentTimeMillis()}}""")
                }
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(conn)
            conn.close()
        }
    }

    private fun extractKey(path: String): String {
        val i = path.indexOf('?')
        if (i < 0) return ""
        return MiniServer.parseQuery(path.substring(i + 1))["k"] ?: ""
    }

    // ---- auth ------------------------------------------------------------

    private fun generatePin(): String = String.format(Locale.US, "%04d", SecureRandom().nextInt(10000))

    /**
     * @return true when [key] is the code currently shown on the TV. A correct
     *   code is never rejected for being early; the guard's backoff only ever
     *   throttles wrong guesses. See PinGuard for why that distinction is the
     *   whole ballgame.
     */
    fun authorized(key: String?): Boolean = guard.allows(key)

    // ---- device facts ----------------------------------------------------

    fun lanAddresses(): List<String> {
        val out = ArrayList<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                if (nif.isLoopback || !nif.isUp) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                        out.add(a.hostAddress ?: continue)
                    }
                }
            }
        } catch (_: Exception) {}
        if (out.isEmpty()) out.add("127.0.0.1")
        return out
    }

    fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun androidVersion(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun storageLabel(): String {
        val root = files.root
        val total = try { root.totalSpace } catch (_: Exception) { 0L }
        val free = files.freeSpace()
        return "${human(total)} 可用 ${human(free)}"
    }

    fun human(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, if (v >= 100 || i == 0) "%.0f %s" else "%.1f %s", v, units[i])
    }

    /** Payload for GET /api/info — the dashboard's device panel. */
    fun infoBody(): String = buildString {
        append("""{"ok":${JsonBool(server?.running == true)}""")
        append(""","port":$PORT""")
        append(""","model":${JsonStr(deviceModel())}""")
        append(""","android":${JsonStr(androidVersion())}""")
        append(""","root":${JsonStr(files.root.absolutePath)}""")
        append(""","storage":${JsonStr(storageLabel())}""")
        append(""","addrs":${lanAddresses().joinToString(",", "[", "]") { JsonStr(it) }}""")
        append(""","uptimeMs":${if (startedAt() > 0) System.currentTimeMillis() - startedAt() else 0}""")
        append(""","clients":${clients.size}""")
        append(""","connections":${server?.activeConnections ?: 0}""")
        append(""","remote":${JsonStr(KeyInjector.strategy.name.lowercase())}""")
        append(""","remoteOk":${JsonBool(KeyInjector.strategy != KeyInjector.Strategy.NONE)}""")
        append(""","shellInput":${JsonBool(KeyInjector.shellAvailable == true)}""")
        append(""","capture":${JsonBool(ScreenCapture.available == true)}""")
        append(""","captureError":${JsonStr(ScreenCapture.lastError ?: "")}""")
        append(""","lastError":${JsonStr(lastError() ?: "")}""")
        append(""","keepAlive":${JsonBool(keepAlive)}""")
        append(""","keepAliveRunning":${JsonBool(isKeepAliveRunning())}""")
        append("}")
    }

    fun logsJson(): String = logs().joinToString(",", "[", "]") { JsonStr(it) }

    private const val OWNER_ACTIVITY = "activity"
    private const val OWNER_SERVICE = "service"
    private const val NOTIFICATION_ID = 0x4258

    private fun JsonStr(v: String) = com.boxhub.http.Json.str(v)
    private fun JsonBool(v: Boolean) = com.boxhub.http.Json.bool(v)
}