package com.boxhub

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.boxhub.device.KeyInjector
import com.boxhub.device.ScreenCapture
import java.util.Locale

/**
 * The TV-side screen.
 *
 * This is the only UI the box renders: it tells the user the pairing code and
 * the LAN URL to open on their phone. The server is bound to this activity's
 * lifetime — closing the app closes the port, which is what was asked for.
 */
class MainActivity : Activity() {

    private lateinit var pinLabel: TextView
    private lateinit var urlLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var toggleButton: Button
    private lateinit var keepAliveToggle: Switch
    private lateinit var keepAliveNote: TextView

    /** Suppresses the Switch listener while refresh() writes the current state. */
    private var refreshGuard = false

    private val ticker = Handler(Looper.getMainLooper())
    private var serving = false

    private val bg = Color.parseColor("#F6F6F4")
    private val ink = Color.parseColor("#171614")
    private val ink2 = Color.parseColor("#57544D")
    private val ink3 = Color.parseColor("#8C8880")
    private val line = Color.parseColor("#DEDCD6")
    private val accent = Color.parseColor("#1F6F4A")
    private val danger = Color.parseColor("#9F1239")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        App.init(this)
        setContentView(buildUi())
        // Ask after the window exists — requesting from onCreate is unreliable
        // on API 23+ and the dialog can be dropped.
        ensureStoragePermission()
        App.acquire(OWNER_ACTIVITY)
        serving = App.isRunning()
        if (App.keepAlive) {
            // The service is the owner that actually keeps the port up when
            // keep-alive is on; re-acquire so a process restart cannot
            // silently drop it.
            try { startForegroundService(Intent(this, HubService::class.java)) } catch (_: Throwable) {}
        }
        refresh()
        ticker.postDelayed(tick, 3000)
    }

    override fun onDestroy() {
        ticker.removeCallbacksAndMessages(null)
        // Release rather than stop outright: with 保持运行 on, the service is
        // still an owner and the port must stay open.
        App.release(OWNER_ACTIVITY)
        super.onDestroy()
    }

    private fun setServing(next: Boolean) {
        serving = next
        if (next) App.resume() else App.suspend()
        toggleButton.text = if (serving) "停止服务" else "启动服务"
        refresh()
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ticker.postDelayed(this, 3000)
        }
    }

    private fun ensureStoragePermission() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            } catch (_: Exception) {}
        }
    }

    // ---- ui --------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(48), dp(40), dp(48), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "BOXHUB"
            setTextColor(ink3)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            letterSpacing = 0.22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "局域网控制台"
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setPadding(0, dp(6), 0, dp(4))
        })

        // The pairing code goes FIRST, above the explanation. It is the one number the
        // user has to read off this screen and type on their phone, and on a
        // short landscape TV panel anything placed below the intro paragraph
        // ends up under the fold — verified on an API 37 emulator, where the
        // code and the URL were only reachable by scrolling.
        root.addView(caption("配对码"))
        pinLabel = TextView(this).apply {
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 92f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            letterSpacing = 0.06f
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(pinLabel)

        root.addView(caption("访问地址"))
        urlLabel = TextView(this).apply {
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setLineSpacing(dp(6).toFloat(), 1f)
        }
        root.addView(urlLabel)

        root.addView(TextView(this).apply {
            text = "在手机浏览器打开上面的地址，输入上面的配对码即可遥控与传文件。退出本应用会立即关闭端口。"
            setTextColor(ink2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(dp(4).toFloat(), 1f)
            // Max width, never a fixed right padding: a 560dp right padding used
            // to squeeze this into a narrow column and grow the paragraph.
            setMaxWidth(dp(640))
            setPadding(0, dp(20), 0, 0)
        })

        root.addView(divider(dp(24)))

        // Capability report — the on-device verification the whole remote-control
        // design hinges on. Below the fold is fine here: it is diagnostic, and
        // unlike the pairing code nobody has to read it to start using the app.
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        right.addView(caption("设备能力检测"))
        statusLabel = TextView(this).apply {
            setTextColor(ink2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setLineSpacing(dp(9).toFloat(), 1f)
        }
        right.addView(statusLabel)

        toggleButton = Button(this).apply {
            text = if (serving) "停止服务" else "启动服务"
            isAllCaps = false
            setTextColor(bg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setBackgroundColor(ink)
            setOnClickListener { setServing(!serving) }
        }
        right.addView(toggleButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(56)).apply {
            topMargin = dp(20)
        })

        // Keep-alive toggle: without it the port dies whenever Android trims the
        // process, which on a 2 GB box is routine once a video player is running.
        keepAliveToggle = Switch(this@MainActivity).apply {
            text = "保持运行"
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            isChecked = App.keepAlive
            setOnCheckedChangeListener { _, checked ->
                if (!refreshGuard) {
                    App.setKeepAlive(checked)
                    serving = App.isRunning()
                    refresh()
                }
            }
        }
        right.addView(keepAliveToggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(52)).apply {
            topMargin = dp(6)
        })

        keepAliveNote = TextView(this).apply {
            setTextColor(ink3)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, 0, dp(40), 0)
        }
        right.addView(keepAliveNote)

        val recheck = Button(this).apply {
            text = "重新检测"
            isAllCaps = false
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                Thread {
                    KeyInjector.probe()
                    ScreenCapture.probe()
                    runOnUiThread { refresh() }
                }.apply { isDaemon = true }.start()
            }
        }
        right.addView(recheck, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(56)).apply {
            topMargin = dp(8)
        })

        root.addView(right)

        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            addView(root)
        }
    }

    private fun caption(t: String) = TextView(this).apply {
        text = t.uppercase(Locale.US)
        setTextColor(ink3)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        letterSpacing = 0.18f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setPadding(0, dp(10), 0, 0)
    }

    private fun divider(margin: Int) = View(this).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(margin)
            bottomMargin = dp(margin)
        }
    }

    // ---- refresh ---------------------------------------------------------

    private fun refresh() {
        // Mirror the toggle without letting the write re-enter its listener.
        refreshGuard = true
        try {
            if (::keepAliveToggle.isInitialized) keepAliveToggle.isChecked = App.keepAlive
        } finally {
            refreshGuard = false
        }

        serving = App.isRunning()
        if (::keepAliveNote.isInitialized) {
            keepAliveNote.text = when {
                !App.keepAlive -> "关闭时：按 HOME 回桌面后端口仍在，但切走久或被系统回收后会断。"
                App.isKeepAliveRunning() -> "已开启：端口由常驻通知维持，切走应用不会断。可从通知直接停止。"
                else -> "已开启：正在启动常驻服务…"
            }
        }
        pinLabel.text = if (serving) App.pin else "----"

        val addrs = App.lanAddresses()
        val startError = App.lastError()
        urlLabel.text = when {
            serving -> addrs.joinToString("\n") { "http://$it:${App.PORT}" }
            startError != null -> "启动失败\n$startError"
            else -> "服务已停止"
        }
        urlLabel.setTextColor(if (serving) accent else danger)

        val storageOk = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

        val remoteOk = KeyInjector.strategy != KeyInjector.Strategy.NONE
        val lines = ArrayList<String>()
        lines.add("远程注入   ${badge(remoteOk)}  ${KeyInjector.strategy.name.lowercase()}")
        lines.add("input 命令  ${badge(KeyInjector.shellAvailable == true)}")
        lines.add("截屏预览   ${badge(ScreenCapture.available == true)}")
        lines.add("存储写入   ${badge(storageOk)}")
        lines.add("存储空间   ${App.storageLabel()}")
        lines.add("设备       ${App.deviceModel()}")
        lines.add("系统       ${App.androidVersion()}")
        lines.add("连接数     ${App.clients.size} 在线 / ${App.server?.activeConnections ?: 0} 活跃")

        if (!remoteOk) {
            lines.add("")
            lines.add("远程注入不可用：本机固件拒绝执行 input，")
            lines.add("且框架注入路径均被拦截。遥控功能将失效。")
        }
        if (ScreenCapture.available == true && ScreenCapture.lastError != null) {
            lines.add("截屏提示: ${ScreenCapture.lastError}")
        }
        App.lastError()?.let { lines.add(""); lines.add("启动错误: $it") }

        statusLabel.text = lines.joinToString("\n")
    }

    private fun badge(ok: Boolean) = if (ok) "✓" else "✗"

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private companion object {
        const val OWNER_ACTIVITY = "activity"
    }
}