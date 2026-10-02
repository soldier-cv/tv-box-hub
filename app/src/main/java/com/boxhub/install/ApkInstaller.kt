package com.boxhub.install

import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.boxhub.Ctx
import java.io.File

/**
 * Hands a downloaded APK to the stock system installer.
 *
 * On Android 7 there is no PackageInstaller.Session (that arrived in API 26),
 * so the flow is the classic one: write the file somewhere we own, expose it
 * through our own FileProvider as a content:// URI, and fire
 * ACTION_INSTALL_PACKAGE with a temporary read grant. The user still confirms
 * on the TV, which is unavoidable without root.
 */
object ApkInstaller {

    private const val TAG = "boxhub"

    fun downloadDir(): File {
        val d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun authority(): String = Ctx.require().packageName + ".files"

    fun uriFor(file: File): Uri = Uri.parse("content://" + authority() + "/apk/" + Uri.encode(file.name))

    // ACTION_INSTALL_PACKAGE is deprecated from API 29 in favour of
    // ACTION_PACKAGE_INSTALL, but it is still the correct action on Android 7
    // and is what the stock installer on the box expects.
    @Suppress("DEPRECATION")
    fun install(file: File): Boolean {
        val ctx = Ctx.app ?: return false
        if (!file.exists() || !file.canRead()) return false
        if (!file.name.lowercase().endsWith(".apk")) return false
        return try {
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uriFor(file), "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Helps the installer show a sensible app name on some ROMs.
                putExtra(Intent.EXTRA_TITLE, file.nameWithoutExtension)
            }
            ctx.startActivity(intent)
            true
        } catch (t: Throwable) {
            App2.log("拉起安装器失败: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private object App2 {
        fun log(msg: String) = com.boxhub.App.log(msg)
    }
}