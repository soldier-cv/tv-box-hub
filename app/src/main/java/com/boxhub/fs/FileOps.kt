package com.boxhub.fs

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * All file access is chrooted to the shared external storage root. Every
 * client-supplied path is canonicalised and re-checked against that root, so
 * `..` traversal and symlink escapes are rejected rather than merely
 * discouraged.
 */
class FileOps(root: File) {

    val root: File = root.canonicalFile

    data class Entry(
        val name: String,
        val dir: Boolean,
        val size: Long,
        val modified: Long,
        val ext: String,
        val apk: Boolean
    )

    data class Stat(val file: File, val size: Long, val modified: Long)

    /** Resolves a client path relative to the root, or null if it escapes. */
    fun resolve(rel: String?): File? {
        val clean = (rel ?: "").trim().trim('/')
        if (clean.any { it.code == 0 }) return null
        val target = if (clean.isEmpty()) root else File(root, clean)
        val canonical = try { target.canonicalPath } catch (_: IOException) { return null }
        val base = root.path
        return when {
            canonical == base -> root
            canonical.startsWith(base + File.separator) -> File(canonical)
            else -> null
        }
    }

    /** Rejects names that would let a client escape or nest directories. */
    fun sanitizeName(name: String?): String? {
        val n = (name ?: "").trim()
        if (n.isEmpty() || n.length > 255) return null
        if (n == "." || n == "..") return null
        if (n.contains('/') || n.contains('\\') || n.contains(':')) return null
        if (n.any { it.code == 0 }) return null
        return n
    }

    fun list(rel: String?): Pair<String, List<Entry>>? {
        val dir = resolve(rel) ?: return null
        if (!dir.isDirectory) return null
        val children = try { dir.listFiles() } catch (_: Exception) { null } ?: return null
        val entries = children.map { f ->
            Entry(
                name = f.name,
                dir = f.isDirectory,
                size = if (f.isDirectory) 0L else f.length(),
                modified = f.lastModified(),
                ext = if (f.isDirectory) "" else (f.extension ?: "").lowercase(),
                apk = !f.isDirectory && f.extension.equals("apk", true)
            )
        }.sortedWith(compareByDescending<Entry> { it.dir }.thenBy { it.name.lowercase() })
        return Pair(dir.absolutePath, entries)
    }

    fun stat(rel: String?): Stat? {
        val f = resolve(rel) ?: return null
        if (!f.exists()) return null
        return Stat(f, f.length(), f.lastModified())
    }

    fun uniqueChild(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists() && i < 1000) {
            candidate = File(dir, "$base($i)$ext")
            i++
        }
        return candidate
    }

    /** Streams an upload into [dir] under a sanitised name. Returns bytes written. */
    fun upload(dirRel: String?, name: String?, src: InputStream, maxBytes: Long): Pair<File, Long>? {
        val dir = resolve(dirRel)?.takeIf { it.isDirectory } ?: return null
        val safe = sanitizeName(name) ?: return null
        val target = uniqueChild(dir, safe)
        var written = 0L
        try {
            target.outputStream().use { out: OutputStream ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    written += n
                    if (written > maxBytes) throw IOException("upload exceeds limit")
                    out.write(buf, 0, n)
                }
                out.flush()
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return Pair(target, written)
    }

    fun mkdir(rel: String?): Boolean {
        val dir = resolve(rel) ?: return false
        if (dir.exists()) return dir.isDirectory
        return dir.mkdirs()
    }

    fun delete(rel: String?): Boolean {
        val f = resolve(rel) ?: return false
        if (f.path == root.path) return false
        if (f.isDirectory) return f.deleteRecursively()
        return f.delete()
    }

    fun rename(rel: String?, newName: String?): Boolean {
        val f = resolve(rel) ?: return false
        val safe = sanitizeName(newName) ?: return false
        if (!f.exists() || f.path == root.path) return false
        val dest = File(f.parentFile, safe)
        return f.renameTo(dest)
    }

    /** Relative path of [f] with respect to the root, for URL building. */
    fun relative(f: File): String =
        if (f.path == root.path) "" else f.path.removePrefix(root.path + File.separator).replace(File.separatorChar, '/')

    fun freeSpace(): Long = try { root.usableSpace } catch (_: Exception) { 0L }
}