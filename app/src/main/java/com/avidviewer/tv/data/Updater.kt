package com.avidviewer.tv.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Checks GitHub Releases for a newer Avid Viewer and installs it with Android's package installer. */
object Updater {
    const val REPO = "mavenmarty/avid-viewer"

    data class Release(val versionCode: Long, val versionName: String, val apkUrl: String, val notes: String)

    /** Receives installer results so the UI can show them. */
    var onStatus: ((String) -> Unit)? = null

    fun installedVersion(ctx: Context): Pair<Long, String> {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info) to (info.versionName ?: "")
    }

    fun latest(): Release? {
        val o = JSONObject(Net.text("https://api.github.com/repos/$REPO/releases/latest"))
        val tag = o.optString("tag_name") // e.g. v1.0.12, where 12 is the version code
        val code = tag.substringAfterLast('.').toLongOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        var url: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", true)) { url = a.optString("browser_download_url"); break }
        }
        return Release(code, tag.removePrefix("v"), url ?: return null, o.optString("body"))
    }

    fun download(ctx: Context, url: String, progress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "AvidViewer-update.apk")
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        Net.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed (${resp.code})")
            val body = resp.body ?: throw IOException("Empty download")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L; var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n); done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; progress(pct) }
                        }
                    }
                }
            }
        }
        return out
    }

    /** True when Android lets this app install updates (Fire TV: Developer options › Install unknown apps). */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun permissionIntent(ctx: Context): Intent? =
        if (Build.VERSION.SDK_INT >= 26) Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else null

    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("avid-viewer", 0, apk.length()).use { os ->
                apk.inputStream().use { it.copyTo(os) }
                session.fsync(os)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java), flags)
            session.commit(pending.intentSender)
        }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) context.startActivity(confirm)
                Updater.onStatus?.invoke("Confirm the update on screen.")
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.onStatus?.invoke("Update installed.")
            else -> Updater.onStatus?.invoke("Update didn't install: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"))
        }
    }
}
