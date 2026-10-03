package com.boxhub.device

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.boxhub.Ctx

/**
 * Remote-control primitives.
 *
 * Every path here ends at `InputManagerService.injectInputEvent`, which checks
 * the calling UID against `INJECT_EVENTS` — a `signature|privileged` permission.
 * A normally installed app can therefore never inject events, and none of these
 * fallbacks can talk its way around that: `adb shell input` works only because
 * adb runs as the `shell` UID, which *is* granted the permission.
 *
 * So the honest list of ways to make the remote work is:
 *   1. the box is rooted → `su -c "input ..."` (probed below)
 *   2. the app is installed as a system / platform-signed app
 *   3. the user drives the box over ADB from a computer instead
 *
 * When none applies, say so plainly and say why, instead of leaving the user
 * staring at a dead D-pad.
 */
object KeyInjector {

    private const val TAG = "BoxHub/Key"

    enum class Strategy { NONE, SHELL, SU, INSTRUMENTATION, UIAUTOMATION }

    /** Common locations of a root shell helper on rooted TV-box firmware. */
    private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/su/bin/su", "/system/sbin/su")

    @Volatile var strategy: Strategy = Strategy.NONE
        private set

    @Volatile var shellAvailable: Boolean? = null
        private set

    /** Why the `input` path did not work, in words meant for a human. */
    @Volatile var shellDetail: String? = null
        private set

    @Volatile var suAvailable: Boolean? = null
        private set

    @Volatile var suPath: String? = null
        private set

    @Volatile var instrumentationAvailable: Boolean? = null
        private set

    @Volatile var uiAutomationAvailable: Boolean? = null
        private set

    @Volatile var lastProbeError: String? = null
        private set

    // Lazily created fallbacks.
    private var instrumentation: Instrumentation? = null
    private var uiAutomation: Any? = null
    private var injectViaUiAutomation: ((MotionEvent) -> Boolean)? = null
    private var injectKeyViaUiAutomation: ((KeyEvent) -> Boolean)? = null

    @Synchronized
    fun probe(): Strategy {
        // keycode 0 is KEYCODE_UNKNOWN — harmless, but it proves the binary is
        // reachable, executable from this process, and actually permitted to
        // inject. Only a clean exit counts: the binary happily starts and then
        // prints "permission denied" on stderr, and treating any stderr output
        // as success reported a working remote on boxes where nothing worked.
        val input = Shell.run(listOf("input", "keyevent", "0"), 3_000)
        shellAvailable = input?.ok == true
        shellDetail = when {
            input == null -> "input 无法执行（${Shell.lastError ?: "未知错误"}）"
            input.ok -> null
            // Classify rather than dump: the raw text is a truncated Java stack
            // message ("...requires the caller (") that tells a user nothing,
            // while the class of failure tells them everything.
            input.err.contains("SecurityException") ||
                input.err.contains("requires the caller") ->
                "系统拒绝注入按键（App 缺少 INJECT_EVENTS 权限）"
            input.err.contains("Permission denied") ||
                Shell.lastError?.contains("Permission denied") == true ->
                "input 二进制没有执行权限"
            input.err.isNotEmpty() ->
                "input 被拒绝：" + input.err.lineSequence().firstOrNull()?.take(80).orEmpty()
            else -> "input 返回 ${input.code}"
        }

        suAvailable = probeSu()
        instrumentationAvailable = tryInstrumentation() != null
        uiAutomationAvailable = tryUiAutomation()

        strategy = when {
            shellAvailable == true -> Strategy.SHELL
            suAvailable == true -> Strategy.SU
            instrumentationAvailable == true -> Strategy.INSTRUMENTATION
            uiAutomationAvailable == true -> Strategy.UIAUTOMATION
            else -> Strategy.NONE
        }

        lastProbeError = when (strategy) {
            Strategy.NONE ->
                "本机固件拒绝注入按键：${shellDetail ?: "input 被拒绝"}；" +
                    "且没有 root（su）可用。Android 的 INJECT_EVENTS 是签名级权限，" +
                    "普通 App 无法注入按键 —— 需要 root 或把 App 装成系统应用。"
            else -> null
        }
        Log.i(TAG, "remote strategy = $strategy (input=$shellAvailable su=$suAvailable)")
        return strategy
    }

    /** @return the working `su`, or null. Root is the only real path on stock TV boxes. */
    private fun probeSu(): Boolean {
        for (candidate in SU_PATHS) {
            val r = Shell.run(listOf(candidate, "-c", "input keyevent 0"), 6_000)
            if (r?.ok == true) {
                suPath = candidate
                return true
            }
        }
        suPath = null
        return false
    }

    /** Runs an `input ...` command, escalating through `su` when that is what works. */
    private fun input(vararg args: String): Strategy {
        val su = suPath
        if (strategy == Strategy.SU && su != null) {
            if (Shell.run(listOf(su, "-c", (listOf("input") + args).joinToString(" ")), 4_000)?.ok == true) {
                return Strategy.SU
            }
        }
        if (strategy == Strategy.SHELL) {
            val list = ArrayList<String>(args.size + 1)
            list.add("input")
            list.addAll(args)
            if (Shell.run(list, 2_500)?.ok == true) return Strategy.SHELL
        }
        return Strategy.NONE
    }

    // ---- key events ------------------------------------------------------

    /** @return the strategy that handled the event, or NONE. */
    fun key(code: Int): Strategy {
        val done = input("keyevent", code.toString())
        if (done != Strategy.NONE) return done
        return keyViaFramework(code)
    }

    private fun keyViaFramework(code: Int): Strategy {
        val ev = KeyEvent(KeyEvent.ACTION_DOWN, code)
        val evUp = KeyEvent(KeyEvent.ACTION_UP, code)

        instrumentation?.let { instr ->
            try {
                instr.sendKeyDownUpSync(code)
                return Strategy.INSTRUMENTATION
            } catch (_: Throwable) {}
        }
        injectKeyViaUiAutomation?.let { f ->
            try {
                f(ev); f(evUp)
                return Strategy.UIAUTOMATION
            } catch (_: Throwable) {}
        }
        return Strategy.NONE
    }

    // ---- text ------------------------------------------------------------

    fun text(value: String): Strategy {
        if (value.isEmpty()) return strategy
        // `input text` uses %s for a space. Shell.run uses ProcessBuilder with an
        // argument list, so there is no metacharacter risk on the shell path —
        // but the su path goes through a command string, so quote it there.
        val encoded = value.replace(" ", "%s")
        if (strategy == Strategy.SU && suPath != null) {
            val quoted = encoded.replace("\\", "\\\\").replace("\"", "\\\"")
            if (Shell.run(listOf(suPath!!, "-c", "input text \"$quoted\""), 4_000)?.ok == true) return Strategy.SU
        }
        if (strategy == Strategy.SHELL && Shell.run(listOf("input", "text", encoded), 3_000)?.ok == true) {
            return Strategy.SHELL
        }
        // Framework path cannot synthesize arbitrary characters reliably, so
        // put the text on the clipboard and let the user paste it.
        if (pasteViaClipboard(value)) return Strategy.UIAUTOMATION
        return Strategy.NONE
    }

    private fun pasteViaClipboard(value: String): Boolean = try {
        val ctx = Ctx.app ?: return false
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java) ?: return false
        cm.setPrimaryClip(android.content.ClipData.newPlainText("boxhub", value))
        true
    } catch (_: Throwable) {
        false
    }

    // ---- pointer / swipe / touchpad --------------------------------------

    /**
     * Injects a gesture. [segments] is a list of (x, y, durationMs) moves that
     * start from the given origin.
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Strategy {
        val done = input("swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString())
        if (done != Strategy.NONE) return done
        return swipeViaFramework(x1, y1, x2, y2, durationMs)
    }

    private fun swipeViaFramework(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Strategy {
        val steps = 12
        val events = ArrayList<MotionEvent>(steps + 2)
        val down = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply { pressure = 1f; size = 1f }

        fun build(action: Int, x: Float, y: Float, t: Long): MotionEvent {
            coords.x = x; coords.y = y
            return MotionEvent.obtain(
                down, t, action, 1, arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
        }

        try {
            events.add(build(MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat(), down))
            for (i in 1..steps) {
                val f = i.toFloat() / steps
                val t = down + (durationMs.toLong() * i / steps)
                events.add(build(MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, t))
            }
            events.add(build(MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat(), down + durationMs))
        } catch (_: Throwable) {
            return Strategy.NONE
        }

        events.forEach { ev ->
            var delivered = false
            instrumentation?.let { instr ->
                try { instr.sendPointerSync(ev); delivered = true } catch (_: Throwable) {}
            }
            if (!delivered) {
                val f = injectViaUiAutomation
                if (f != null) {
                    try { delivered = f(ev) } catch (_: Throwable) {}
                }
            }
            if (!delivered) { ev.recycle(); return Strategy.NONE }
        }
        return if (instrumentation != null) Strategy.INSTRUMENTATION else Strategy.UIAUTOMATION
    }

    /** Single tap — used by the touchpad's "tap to confirm". */
    fun tap(x: Int, y: Int): Strategy = swipe(x, y, x, y, 60)

    // ---- fallback bootstrap ----------------------------------------------

    private fun tryInstrumentation(): Instrumentation? {
        if (instrumentation != null) return instrumentation
        return try {
            val i = object : Instrumentation() {
                override fun onCreate(arguments: Bundle?) {
                    super.onCreate(arguments)
                }
            }
            // API 24+ wires the underlying UiAutomation up in onCreate()/start().
            i.onCreate(Bundle())
            try { i.start() } catch (_: Throwable) {}
            // Prove it really works rather than assuming.
            i.sendKeyDownUpSync(KeyEvent.KEYCODE_UNKNOWN)
            instrumentation = i
            i
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * UiAutomation.connect() is @hide, so reach it reflectively. When it works
     * it is the best path for the touchpad: no process spawn per gesture.
     */
    private fun tryUiAutomation(): Boolean {
        if (injectKeyViaUiAutomation != null) return true
        return try {
            val ua = Class.forName("android.app.UiAutomation").getDeclaredConstructor().newInstance()
            val connect = ua.javaClass.getMethod("connect")
            connect.isAccessible = true
            connect.invoke(ua)

            // injectInputEvent(InputEvent, boolean) covers both KeyEvent and MotionEvent.
            val inject = ua.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Boolean::class.javaPrimitiveType
            ).also { it.isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val f = inject as (android.view.InputEvent, Boolean) -> Boolean

            injectKeyViaUiAutomation = { ev -> f(ev, true) }
            injectViaUiAutomation = { ev -> f(ev, true) }
            uiAutomation = ua
            true
        } catch (_: Throwable) {
            false
        }
    }
}