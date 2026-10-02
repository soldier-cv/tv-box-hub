package com.boxhub.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.boxhub.install.ApkInstaller
import java.io.File

/**
 * A deliberately small FileProvider.
 *
 * The framework's androidx FileProvider is not available (BoxHub ships with no
 * third-party dependencies) and we only need to share one directory — the
 * public Download folder — with the system package installer.
 *
 * URI shape: content://<pkg>.files/apk/<url-encoded filename>
 */
class SimpleFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun baseDir(): File = ApkInstaller.downloadDir()

    private fun targetFor(uri: Uri): File? {
        val name = Uri.decode(uri.lastPathSegment ?: return null) ?: return null
        val base = baseDir()
        val f = File(base, name)
        // Defence in depth: the name comes from another process.
        return try {
            val canonicalBase = base.canonicalFile
            val canonicalFile = f.canonicalFile
            if (canonicalFile.parentFile?.canonicalPath == canonicalBase.path) canonicalFile else null
        } catch (_: Exception) {
            null
        }
    }

    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment?.endsWith(".apk", ignoreCase = true) == true)
            "application/vnd.android.package-archive"
        else "application/octet-stream"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (!mode.startsWith("r")) return null
        val f = targetFor(uri) ?: return null
        if (!f.exists() || !f.canRead()) return null
        return try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: Exception) {
            null
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val f = targetFor(uri)
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        cursor.addRow(arrayOf<Any?>(f?.name ?: uri.lastPathSegment, f?.length() ?: 0L))
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}