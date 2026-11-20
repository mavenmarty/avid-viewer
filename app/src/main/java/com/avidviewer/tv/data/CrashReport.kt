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
    /**
     * What happened to a report. [id]: the report id avidviewer.app gave it (AVR-…); [linked]: the site knows which
     * account it belongs to; [viaSite]: false when it went through the email relay instead.
     */
    data class Sent(val ok: Boolean, val id: String? = null, val linked: Boolean = false, val viaSite: Boolean = false)

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
    fun send(appVersion: String, crash: String, relay: String = Links.CRASH_RELAY): Boolean = post(payload(appVersion, crash), relay)

    /**
     * From 1.22.0 reports go to avidviewer.app ([site] + /api/tv/reports, with the device's [token] when it's linked), where
     * the account follows them on its Reports page and the owner sorts them. The email relay is used only when the site
     * can't be reached (no answer, or a server error); a report the site turns down (too many, too big) isn't retried.
     */
    fun deliver(json: JSONObject, site: String, token: String?, relay: String): Sent {
        toSite(json, site, token)?.let { return it }
        return Sent(post(json, relay))
    }

    private fun toSite(json: JSONObject, site: String, token: String?): Sent? {
        if (site.isBlank()) return null
        val b = Request.Builder().url(site.trimEnd('/') + "/api/tv/reports").header("User-Agent", USER_AGENT)
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        token?.let { b.header("Authorization", "Bearer $it") }
        return try {
            Net.client.newCall(b.build()).execute().use { r ->
                if (r.code >= 500) return null
                val o = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrDefault(JSONObject())
                if (r.isSuccessful && o.optBoolean("ok")) Sent(true, o.optString("id").takeIf { it.startsWith("AVR-") }, o.optBoolean("linked"), true)
                else Sent(false, viaSite = true)
            }
        } catch (_: Exception) { null }
    }

    /**
     * The playback log (1.5.0) for the same relay: "kind" = "playback" (the relay titles the email "playback log";
     * an older relay ignores it and still emails it), the log's one-line summary, and the log with addresses removed.
     */
    fun logPayload(appVersion: String, summary: String, log: String): JSONObject = JSONObject()
        .put("app", "avid-viewer")
        .put("kind", "playback")
        .put("version", appVersion)
        .put("device", BugReport.device())
        .put("summary", redact(summary).take(300))
        // The newest part when it's long: the relay keeps 16,000 characters.
        .put("crash", redact(log).let { if (it.length > 16_000) it.take(600) + "\n…\n" + it.takeLast(15_300) else it })

    fun sendLog(appVersion: String, summary: String, log: String, relay: String = Links.CRASH_RELAY): Boolean =
        post(logPayload(appVersion, summary, log), relay)

    /**
     * A security alert (from freeRASP, see SecurityMonitor): "kind" = "security". The relay titles it "security alert"
     * once redeployed; an older relay emails it as a crash report.
     */
    fun securityPayload(appVersion: String, summary: String, findings: String): JSONObject = JSONObject()
        .put("app", "avid-viewer")
        .put("kind", "security")
        .put("version", appVersion)
        .put("device", BugReport.device())
        .put("summary", summary.take(300))
        .put("crash", findings.take(16_000))

    fun sendSecurity(appVersion: String, summary: String, findings: String, relay: String = Links.CRASH_RELAY): Boolean =
        post(securityPayload(appVersion, summary, findings), relay)

    private fun post(json: JSONObject, relay: String): Boolean {
        if (relay.isBlank()) return false
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(relay).header("User-Agent", USER_AGENT).post(body).build()
        // Once more only when the relay was never reached (couldn't connect, name not found): after a timeout the relay
        // may already have mailed the report, and a second try would send it twice (#27).
        repeat(2) { attempt ->
            try {
                return Net.client.newCall(req).execute().use { r -> r.isSuccessful && JSONObject(r.body?.string().orEmpty()).optBoolean("ok", false) }
            } catch (e: java.net.ConnectException) { if (attempt == 0) Thread.sleep(500) else return false }
            catch (e: java.net.UnknownHostException) { if (attempt == 0) Thread.sleep(500) else return false }
            catch (_: Exception) { return false }
        }
        return false
    }
}
