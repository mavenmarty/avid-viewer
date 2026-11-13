package com.avidviewer.tv.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * "Report issue" after a crash: sends the saved crash report to the crash relay ([Links.CRASH_RELAY],
 * a small web app that emails it to bugs@avidviewer.app), so a crash reaches the daily bug workflow
 * without the viewer writing an email.
 *
 * Sent: the app version, the device ([BugReport.device]) and the crash report, with web addresses,
 * host names, user names, passwords and tokens removed ([redact]). Never playlists, logins or keys.
 */
object CrashReport {
    /** What is sent, as JSON (the relay checks "app" and the size of each field). */
    fun payload(appVersion: String, crash: String): JSONObject {
        val clean = redact(crash)
        return JSONObject()
            .put("app", "avid-viewer")
            .put("version", appVersion)
            .put("device", BugReport.device())
            .put("summary", BugReport.crashSummary(clean).orEmpty())
            .put("crash", clean.take(16_000))
    }

    private val URL = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\s"'<>]+""")
    private val SECRET = Regex("""(?i)\b(username|user|password|pass|pwd|token|key|apikey|api_key)=[^\s&"']+""")
    private val HOST = Regex("""(?i)\b(host|hostname)( "|: |=)[^\s"']+""")
    private val IP = Regex("""\b\d{1,3}(\.\d{1,3}){3}(:\d+)?\b""")

    /**
     * Removes what could identify a viewer's provider or login from a crash report. Exception messages
     * sometimes carry a stream or guide address ("FileNotFoundException: http://…/get.php?username=…"),
     * a host the TV couldn't reach, or an IP address.
     */
    fun redact(text: String): String = text
        .replace(URL, "[address removed]")
        .replace(SECRET) { m -> m.groupValues[1] + "=[removed]" }
        .replace(HOST) { m -> m.groupValues[1] + m.groupValues[2] + "[host removed]" }
        .replace(IP, "[address removed]")

    /**
     * Sends the report; true when the relay says it was emailed. Apps Script answers a POST with a
     * redirect to the result, which OkHttp follows (as a GET).
     */
    fun send(appVersion: String, crash: String, relay: String = Links.CRASH_RELAY): Boolean {
        if (relay.isBlank()) return false
        val body = payload(appVersion, crash).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(relay).header("User-Agent", USER_AGENT).post(body).build()
        return runCatching {
            Net.client.newCall(req).execute().use { r ->
                r.isSuccessful && JSONObject(r.body?.string().orEmpty()).optBoolean("ok", false)
            }
        }.getOrDefault(false)
    }
}
