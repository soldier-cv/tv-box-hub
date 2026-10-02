package com.boxhub

import com.boxhub.device.KeyInjector
import com.boxhub.device.ScreenCapture
import com.boxhub.fs.FileOps
import com.boxhub.http.BodyWriter
import com.boxhub.http.Json
import com.boxhub.http.Req
import com.boxhub.http.Resp
import com.boxhub.install.ApkInstaller
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.URLEncoder

/**
 * HTTP surface consumed by the phone dashboard.
 *
 * Everything is query-parameter driven (including upload: the browser PUTs the
 * raw file bytes as the body), so there is no request-body parser anywhere in
 * the stack and no JSON request decoding to get wrong.
 */
class Routes(private val files: FileOps) {

    private var indexCache: ByteArray? = null

    companion object {
        const val MAX_UPLOAD = 16L * 1024 * 1024 * 1024
    }

    fun dispatch(req: Req): Resp {
        val path = req.path
        return try {
            when {
                path == "/" || path == "/index.html" -> index()
                path == "/favicon.ico" -> Resp(204, "image/x-icon", ByteArray(0), contentLength = 0)
                path == "/api/ping" -> Resp.json("""{"ok":true}""")
                else -> {
                    if (!App.authorized(req.q("k"))) denied(path)
                    else route(req, path)
                }
            }
        } catch (t: Throwable) {
            Resp.json("""{"ok":false,"error":${Json.str("server error: ${t.javaClass.simpleName}")}}""", 500)
        }
    }

    private fun route(req: Req, path: String): Resp {
        val m = req.method
        val any = m == "GET" || m == "POST" || m == "PUT" || m == "HEAD"

        return when {
            path == "/api/info" && any -> Resp.json(App.infoBody())
            path == "/api/logs" && any -> Resp.json("""{"ok":true,"data":${App.logsJson()}}""")
            path == "/api/key" && any -> key(req)
            path == "/api/text" && any -> text(req)
            path == "/api/swipe" && any -> swipe(req)
            path == "/api/tap" && any -> tap(req)
            path == "/api/shot" && any -> shot()
            path == "/api/list" && any -> list(req)
            path == "/api/upload" && (m == "POST" || m == "PUT") -> upload(req)
            path == "/api/mkdir" && any -> mkdir(req)
            path == "/api/delete" && any -> delete(req)
            path == "/api/rename" && any -> rename(req)
            path == "/api/install" && any -> install(req)
            path.startsWith("/dl/") -> serveFile(req, path.removePrefix("/dl/"))
            else -> Resp.json("""{"ok":false,"error":"unknown endpoint"}""", 404)
        }
    }

    // ---- auth ------------------------------------------------------------

    private fun denied(path: String): Resp =
        if (path.startsWith("/api/"))
            Resp.json("""{"ok":false,"error":"unauthorized","need":"pin"}""", 401)
        else
            // Length must come from the bytes themselves: a hand-counted value
            // desyncs keep-alive framing and corrupts the next request.
            Resp.text("unauthorized", 401)

    // ---- remote control --------------------------------------------------

    private fun key(req: Req): Resp {
        val code = req.qInt("code", -1)
        if (code < 0) return bad("missing code")
        val via = KeyInjector.key(code)
        if (via == KeyInjector.Strategy.NONE) {
            App.log("按键 $code 未生效（远程注入不可用）")
        } else {
            App.broadcast("key", """"code":$code,"via":"${via.name.lowercase()}"""")
        }
        return Resp.json("""{"ok":${Json.bool(via != KeyInjector.Strategy.NONE)},"via":"${via.name.lowercase()}"}""")
    }

    private fun text(req: Req): Resp {
        val value = req.q("text")
        if (value.isEmpty()) return bad("missing text")
        if (value.length > 500) return bad("text too long")
        val via = KeyInjector.text(value)
        return Resp.json("""{"ok":${Json.bool(via != KeyInjector.Strategy.NONE)},"via":"${via.name.lowercase()}"}""")
    }

    private fun swipe(req: Req): Resp {
        val x1 = req.qInt("x1", 960); val y1 = req.qInt("y1", 540)
        val x2 = req.qInt("x2", 960); val y2 = req.qInt("y2", 540)
        val ms = req.qInt("ms", 300).coerceIn(16, 3_000)
        val via = KeyInjector.swipe(x1, y1, x2, y2, ms)
        return Resp.json("""{"ok":${Json.bool(via != KeyInjector.Strategy.NONE)},"via":"${via.name.lowercase()}"}""")
    }

    private fun tap(req: Req): Resp {
        val x = req.qInt("x", 960); val y = req.qInt("y", 540)
        val via = KeyInjector.tap(x, y)
        return Resp.json("""{"ok":${Json.bool(via != KeyInjector.Strategy.NONE)},"via":"${via.name.lowercase()}"}""")
    }

    private fun shot(): Resp {
        val png = ScreenCapture.png()
            ?: return Resp.json(
                """{"ok":false,"error":${Json.str(ScreenCapture.lastError ?: "capture unavailable")}}""",
                501
            )
        return Resp.bytes(png, "image/png", headers = listOf("Cache-Control" to "no-store, no-cache, must-revalidate"))
    }

    // ---- files -----------------------------------------------------------

    private fun list(req: Req): Resp {
        val result = files.list(req.q("path"))
            ?: return Resp.json("""{"ok":false,"error":"目录不存在或超出允许范围"}""", 404)
        val items = result.second.joinToString(",") { e ->
            """{"name":${Json.str(e.name)},"dir":${Json.bool(e.dir)},"size":${e.size},"mtime":${e.modified},"ext":${Json.str(e.ext)},"apk":${Json.bool(e.apk)}}"""
        }
        return Resp.json(
            """{"ok":true,"path":${Json.str(files.relative(File(result.first)))},"abs":${Json.str(result.first)},"storage":${Json.str(App.storageLabel())},"items":[$items]}"""
        )
    }

    private fun upload(req: Req): Resp {
        val body = req.body ?: return bad("请求体为空")
        val declared = req.h("content-length")?.toLongOrNull() ?: -1L
        if (declared > MAX_UPLOAD) return Resp.json("""{"ok":false,"error":"文件过大"}""", 413)

        val result = try {
            files.upload(req.q("path"), req.q("name"), body, MAX_UPLOAD)
        } catch (t: Throwable) {
            return Resp.json("""{"ok":false,"error":${Json.str(t.message ?: "写入失败")}}""", 400)
        } ?: return Resp.json("""{"ok":false,"error":"路径或文件名不合法"}""", 400)

        val (file, bytes) = result
        App.log("上传 ${file.name} ${App.human(bytes)}")
        App.broadcast("upload", """"name":${Json.str(file.name)},"size":$bytes""")
        return Resp.json("""{"ok":true,"name":${Json.str(file.name)},"size":$bytes,"path":${Json.str(files.relative(file))}}""")
    }

    private fun mkdir(req: Req): Resp {
        val name = files.sanitizeName(req.q("name"))
            ?: return bad("文件名不合法")
        val parent = req.q("path")
        val target = files.resolve(parent) ?: return bad("路径超出允许范围")
        val ok = files.mkdir(files.relative(File(target, name)))
        if (ok) App.log("新建目录 $name")
        return Resp.json("""{"ok":${Json.bool(ok)}}""")
    }

    private fun delete(req: Req): Resp {
        val rel = req.q("path")
        val stat = files.stat(rel) ?: return bad("文件不存在")
        // Recursive delete is safe here: files.delete() re-resolves the path and
        // refuses anything outside the root. Refusing non-empty directories
        // would leave the UI with no way to remove a folder at all.
        val isDir = stat.file.isDirectory
        val entries = if (isDir) (stat.file.list()?.size ?: 0) else 0
        val ok = files.delete(rel)
        if (ok) {
            App.log("删除 ${stat.file.name}" + if (isDir) "（含 $entries 项）" else "")
            App.broadcast("delete", """"name":${Json.str(stat.file.name)}""")
        }
        return Resp.json(
            """{"ok":${Json.bool(ok)},"dir":${Json.bool(isDir)},"entries":$entries,"name":${Json.str(stat.file.name)}}"""
        )
    }

    private fun rename(req: Req): Resp {
        val ok = files.rename(req.q("path"), req.q("name"))
        if (ok) App.log("重命名为 ${req.q("name")}")
        return Resp.json("""{"ok":${Json.bool(ok)}}""")
    }

    private fun install(req: Req): Resp {
        val stat = files.stat(req.q("path"))
            ?: return Resp.json("""{"ok":false,"error":"文件不存在"}""", 404)
        val src = stat.file
        if (src.isDirectory || !src.name.lowercase().endsWith(".apk"))
            return Resp.json("""{"ok":false,"error":"不是 APK 文件"}""", 400)

        // The provider only exposes Download/, so stage the file there first.
        val dir = ApkInstaller.downloadDir()
        val staged = try {
            val same = try {
                src.canonicalFile == File(dir, src.name).canonicalFile
            } catch (_: Exception) { false }
            if (same) src else {
                val dest = files.uniqueChild(dir, src.name)
                src.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
                dest
            }
        } catch (t: Throwable) {
            return Resp.json("""{"ok":false,"error":${Json.str("暂存失败: ${t.message}")}}""", 500)
        }

        val ok = ApkInstaller.install(staged)
        App.log("安装 ${staged.name} → ${if (ok) "已拉起系统安装器" else "失败"}")
        App.broadcast("install", """"name":${Json.str(staged.name)},"ok":${Json.bool(ok)}""")
        return Resp.json("""{"ok":${Json.bool(ok)},"name":${Json.str(staged.name)},"size":${staged.length()}}""")
    }

    /** Serves a file with Range support so the browser can seek in <video>. */
    private fun serveFile(req: Req, rel: String): Resp {
        val stat = files.stat(rel) ?: return Resp.text("not found", 404)
        val f = stat.file
        if (f.isDirectory) return Resp.text("is a directory", 400)
        val total = stat.size

        var start = 0L
        var end = total - 1
        var status = 200

        val range = req.h("range")
        if (range != null && range.startsWith("bytes=") && total > 0) {
            val spec = range.removePrefix("bytes=").substringBefore(',')
            val parts = spec.split('-')
            try {
                if (parts[0].isNotBlank()) start = parts[0].trim().toLong()
                if (parts.size > 1 && parts[1].isNotBlank()) end = parts[1].trim().toLong()
                if (start < 0 || start >= total || end < start)
                    return Resp(416, "text/plain", ByteArray(0), contentLength = 0L,
                        headers = listOf("Content-Range" to "bytes */$total", "Accept-Ranges" to "bytes"))
                if (end >= total) end = total - 1
                status = 206
            } catch (_: Throwable) {
                start = 0L; end = total - 1; status = 200
            }
        }

        val length = if (total <= 0L) 0L else end - start + 1
        val headers = ArrayList<Pair<String, String>>()
        headers.add("Accept-Ranges" to "bytes")
        headers.add("Cache-Control" to "no-cache")
        headers.add("Content-Disposition" to "inline; filename*=UTF-8''" + urlEncode(f.name))
        if (status == 206) headers.add("Content-Range" to "bytes $start-$end/$total")

        return Resp(
            status = status,
            contentType = mimeFor(f.name),
            writer = BodyWriter { out -> copyRange(f, start, length, out) },
            contentLength = length,
            headers = headers
        )
    }

    private fun copyRange(f: File, start: Long, length: Long, out: OutputStream) {
        if (length <= 0) return
        try {
            FileInputStream(f).use { input ->
                var skipped = 0L
                while (skipped < start) {
                    val n = input.skip(start - skipped)
                    if (n <= 0) { input.read(); skipped++ } else skipped += n
                }
                val buf = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
        } catch (_: Exception) {}
        out.flush()
    }

    // ---- ui --------------------------------------------------------------

    private fun index(): Resp {
        var cached = indexCache
        if (cached == null) {
            cached = try {
                Ctx.require().assets.open("index.html").use { it.readBytes() }
            } catch (_: Throwable) { null }
            if (cached == null) return Resp.text("index.html 缺失于 assets", 500)
            indexCache = cached
        }
        return Resp.bytes(cached, "text/html; charset=utf-8", headers = listOf("Cache-Control" to "no-cache"))
    }

    private fun bad(message: String) = Resp.json("""{"ok":false,"error":${Json.str(message)}}""", 400)

    private fun urlEncode(s: String): String = try {
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    } catch (_: Exception) { "file" }

    private fun mimeFor(name: String): String {
        val i = name.lastIndexOf('.')
        val ext = if (i >= 0) name.substring(i + 1).lowercase() else ""
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts", "m2ts" -> "video/mp2t"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "rmvb", "rm" -> "application/vnd.rn-realmedia"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "wma" -> "audio/x-ms-wma"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "heic" -> "image/heic"
            "apk" -> "application/vnd.android.package-archive"
            "txt", "log", "srt", "ass", "vtt", "nfo" -> "text/plain; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "xml" -> "application/xml; charset=utf-8"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}