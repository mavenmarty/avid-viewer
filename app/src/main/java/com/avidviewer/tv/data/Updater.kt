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
import java.util.concurrent.TimeUnit

/** Checks GitHub Releases for a newer Avid Viewer and installs it with Android's package installer. */
object Updater {
    const val REPO = "mavenmarty/avid-viewer"

    /** [publishedAt]: when GitHub published it (ms since 1970; 0 if unknown). */
    data class Release(val versionCode: Long, val versionName: String, val apkUrl: String, val notes: String, val size: Long = -1L, val publishedAt: Long = 0L)

    const val NO_CONFIRM = "The installer screen didn't open."

    /** Receives installer results so the UI can show them. */
    var onStatus: ((String) -> Unit)? = null

    fun installedVersion(ctx: Context): Pair<Long, String> {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info) to (info.versionName ?: "")
    }

    /** Beta builds (Nov 18, 2026): GitHub pre-releases tagged beta-v<version>-<n>.<build>. */
    private val BETA_TAG = Regex("""^beta-v(\d+\.\d+\.\d+)-(\d+)\.(\d+)$""")

    fun isBetaTag(tag: String) = BETA_TAG.matches(tag)

    /** True for a beta build's version name ("1.20.0 beta 2"). */
    fun isBetaVersion(versionName: String) = versionName.contains(" beta ")

    /** "v1.1.0.190" → "1.1.0"; "v1.0.179" → "1.0.179"; "beta-v1.20.0-2.360" → "1.20.0 beta 2". */
    fun displayVersion(tag: String): String {
        BETA_TAG.matchEntire(tag)?.let { return "${it.groupValues[1]} beta ${it.groupValues[2]}" }
        val parts = tag.removePrefix("v").split('.')
        return if (parts.size >= 4) parts.take(3).joinToString(".") else parts.joinToString(".")
    }

    /** The public release. Pre-releases (betas) are never returned by releases/latest. */
    fun latest(): Release? = parse(JSONObject(Net.text("https://api.github.com/repos/$REPO/releases/latest")))

    /** One release by tag: the beta build offered to this device. */
    fun byTag(tag: String): Release? =
        parse(JSONObject(Net.text("https://api.github.com/repos/$REPO/releases/tags/" + java.net.URLEncoder.encode(tag, "UTF-8"))))

    /**
     * What this device should be offered: the newest of the public release and (in the beta) the offered beta build.
     * Leaving the beta keeps the beta build until a public one is newer: public builds always have higher numbers.
     */
    fun pick(public: Release?, beta: Release?, installedCode: Long): Release? =
        listOfNotNull(public, beta).maxByOrNull { it.versionCode }?.takeIf { it.versionCode > installedCode }

    internal fun parse(o: JSONObject): Release? {
        // e.g. v1.0.179 (up to 1.0.179) or v1.1.0.190 (semantic version + build): the last part is the
        // version code. Shown as 1.0.179 / 1.1.0. Betas: beta-v1.20.0-2.360, shown as 1.20.0 beta 2.
        val tag = o.optString("tag_name")
        val code = tag.substringAfterLast('.').toLongOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        var url: String? = null
        var size = -1L
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", true)) { url = a.optString("browser_download_url"); size = a.optLong("size", -1L); break }
        }
        val published = runCatching { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(o.optString("published_at"))!!.time }.getOrDefault(0L)
        return Release(code, displayVersion(tag), url ?: return null, o.optString("body"), size, published)
    }

    /** No data for this long means the connection has stalled: pick up where it stopped. */
    private const val STALL_SECONDS = 15L
    private const val ATTEMPTS = 8

    /**
     * Downloads the update. A connection that stalls (common on Fire TV Wi-Fi, often near the end)
     * is dropped after [STALL_SECONDS] and resumed from the last byte received, up to [ATTEMPTS] times.
     * The finished file is checked (size, and that Android can read it as this app) before installing.
     */
    fun download(ctx: Context, url: String, expectedSize: Long = -1L, checkApk: Boolean = true, progress: (pct: Int, note: String?) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "AvidViewer-update.apk")
        out.delete()
        val client = Net.client.newBuilder().readTimeout(STALL_SECONDS, TimeUnit.SECONDS).build()
        var total = expectedSize
        var lastPct = -1
        var failure: IOException? = null
        for (attempt in 1..ATTEMPTS) {
            val have = if (out.exists()) out.length() else 0L
            if (total > 0 && have >= total) break
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "identity")
                .apply { if (have > 0) header("Range", "bytes=$have-") }
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Download failed (${resp.code})")
                    val body = resp.body ?: throw IOException("Empty download")
                    // 206 = the server is continuing from where we stopped; 200 = it sent the whole file again.
                    val append = have > 0 && resp.code == 206
                    if (!append && have > 0) out.delete()
                    val start = if (append) have else 0L
                    if (total <= 0) total = resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                        ?: body.contentLength().takeIf { it > 0 }?.plus(start) ?: -1L
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(out, append).use { os ->
                            val buf = ByteArray(64 * 1024)
                            var done = start
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                os.write(buf, 0, n); done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) { lastPct = pct; progress(pct, null) }
                                }
                            }
                        }
                    }
                }
                if (total <= 0 || out.length() >= total) { failure = null; break }
                failure = IOException("The connection closed early")
            } catch (e: IOException) {
                failure = e
            }
            if (attempt < ATTEMPTS) {
                progress(lastPct.coerceAtLeast(0), "connection slow, resuming (try ${attempt + 1} of $ATTEMPTS)")
                Thread.sleep(1500L * attempt)
            }
        }
        failure?.let { out.delete(); throw IOException("Download didn't finish: ${it.message ?: it.javaClass.simpleName}. Check the connection and try again.") }
        if (total > 0 && out.length() != total) { out.delete(); throw IOException("Download incomplete (${out.length()} of $total bytes). Try again.") }
        if (checkApk) verify(ctx, out)
        return out
    }

    /** The file must be a readable APK of this same app, or Android's installer stops part-way. */
    fun verify(ctx: Context, apk: File) {
        @Suppress("DEPRECATION")
        val info = runCatching { ctx.packageManager.getPackageArchiveInfo(apk.path, 0) }.getOrNull()
        if (info == null || info.packageName != ctx.packageName) {
            apk.delete()
            throw IOException("The downloaded update was damaged. Try again.")
        }
    }

    /** Fallback when the installer session gets no answer: Android's own install screen. */
    fun openInstaller(ctx: Context, apk: File): Boolean = runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", apk)
        @Suppress("DEPRECATION")
        val i = Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        ctx.startActivity(i)
        true
    }.getOrDefault(false)

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
                val shown = confirm != null && runCatching { context.startActivity(confirm) }.isSuccess
                Updater.onStatus?.invoke(if (shown) "Confirm the update on screen." else Updater.NO_CONFIRM)
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.onStatus?.invoke("Update installed.")
            else -> Updater.onStatus?.invoke("Update didn't install: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"))
        }
    }
}

/**
 * When to ask about an update (Nov 15): never as soon as it's found, but once it has been out for 24 hours
 * and still isn't installed. A newer build released in the meantime starts the 24 hours again (it's the
 * latest release that counts). "Later" waits another 24 hours. The menu's Update button and Settings ›
 * Updates & about offer it at any time.
 */
class UpdateReminder(context: Context) {
    private val prefs = context.getSharedPreferences("update-reminder", Context.MODE_PRIVATE)

    /** When the reminder for [rel] is due: 24 h after its release (or after this TV first saw it), or after "Later". */
    fun dueAt(rel: Updater.Release, now: Long): Long {
        if (prefs.getLong("seenCode", -1) != rel.versionCode) prefs.edit().putLong("seenCode", rel.versionCode).putLong("seenAt", now).apply()
        val since = if (rel.publishedAt > 0) rel.publishedAt else prefs.getLong("seenAt", now)
        val snooze = if (prefs.getLong("laterCode", -1) == rel.versionCode) prefs.getLong("laterUntil", 0L) else 0L
        return maxOf(since + WAIT, snooze)
    }

    fun later(rel: Updater.Release, now: Long) {
        prefs.edit().putLong("laterCode", rel.versionCode).putLong("laterUntil", now + WAIT).apply()
    }

    companion object { const val WAIT = 24 * 60 * 60 * 1000L }
}
