package com.avidviewer.tv.data

import android.content.Context
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Linking this TV to an avidviewer.app account (1.11.0). See src/worker/devices.js in mavenmarty/avidviewer-site.
 *
 * Settings › Account › Link this TV asks for a code ([start]), shows it with a QR code for avidviewer.app/link, and
 * polls ([poll]) until the person approves it on their phone; the TV then gets its own token. A setup code made
 * while signed in carries a link token the TV trades for one ([claim]). The token is kept encrypted on the TV
 * ([AccountStore]); [me] checks it's still linked (a TV removed on the Account page gets 401).
 * Only the TV's name, model and app version are sent: never what's watched. Since 1.15.0 a device with account profiles
 * also sends each account playlist's categories and channel names (for choosing what profiles see) and, while playing,
 * which playlist and profile are in use (Watching now).
 */
object AccountApi {
    const val BASE = "https://avidviewer.app"

    class Problem(message: String) : Exception(message)

    data class Info(val name: String, val model: String, val appVersion: String)
    data class Start(val userCode: String, val deviceCode: String, val interval: Int, val expiresIn: Int, val qrUrl: String)
    data class Linked(val token: String, val email: String, val name: String, val deviceId: String)
    /** [beta]: this device is in the beta (chosen at avidviewer.app/admin); [betaTag]: the beta build offered to it. */
    data class Me(val email: String, val name: String, val beta: Boolean = false, val betaTag: String? = null, val sendLog: Boolean = false)
    /** One TV's backup in the account (each TV keeps only its most recent). */
    data class CloudBackup(
        val deviceId: String, val name: String, val model: String, val appVersion: String, val createdAt: Long,
        val size: Int, val playlists: Int?, val removed: Boolean, val thisTv: Boolean,
    )

    /** This TV's name (the one in Fire TV / Android settings when there is one), model and app version. */
    fun info(context: Context, appVersion: String): Info {
        val named = runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        val model = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ")
            .replaceFirstChar { it.uppercase() }
        return Info(named?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "TV", model, appVersion)
    }

    private fun call(base: String, path: String, body: JSONObject?, token: String? = null, appVersion: String? = null): Pair<Int, JSONObject> {
        val b = Request.Builder().url(base + path).header("User-Agent", USER_AGENT)
        token?.let { b.header("Authorization", "Bearer $it") }
        appVersion?.let { b.header("X-App-Version", it) }
        if (body != null) b.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        return try {
            Net.client.newCall(b.build()).execute().use { r -> r.code to runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrDefault(JSONObject()) }
        } catch (e: Exception) {
            throw Problem("Couldn't reach avidviewer.app. Check the TV's internet connection, then try again.")
        }
    }

    private fun Info.json() = JSONObject().put("name", name).put("model", model).put("appVersion", appVersion)

    private fun linked(o: JSONObject) = Linked(o.getString("token"), o.optString("email"), o.optString("name"), o.optString("deviceId"))

    fun start(info: Info, base: String = BASE): Start {
        val (code, o) = call(base, "/api/tv/link/start", info.json())
        if (code != 200) throw Problem(o.optString("error").ifBlank { "avidviewer.app answered $code. Try again in a moment." })
        return Start(o.getString("userCode"), o.getString("deviceCode"), o.optInt("interval", 5).coerceIn(2, 30),
            o.optInt("expiresIn", 600), o.optString("qrUrl").ifBlank { "$base/link/?c=${o.getString("userCode")}" })
    }

    /** null while waiting for approval; throws [Problem] when the code has expired. */
    fun poll(deviceCode: String, base: String = BASE): Linked? {
        val (code, o) = call(base, "/api/tv/link/poll", JSONObject().put("deviceCode", deviceCode))
        return when (o.optString("status")) {
            "approved" -> linked(o)
            "pending" -> null
            else -> throw Problem(if (code == 200) "The code expired before it was used. Choose Link this device for a new one." else "avidviewer.app answered $code.")
        }
    }

    fun claim(linkToken: String, info: Info, base: String = BASE): Linked {
        val (code, o) = call(base, "/api/tv/link/claim", info.json().put("linkToken", linkToken))
        if (code != 200 || o.optString("status") != "approved") throw Problem(o.optString("error").ifBlank { "avidviewer.app answered $code." })
        return linked(o)
    }

    /** Who this TV is linked to; null when the account removed it (or was deleted). Network trouble throws. */
    fun me(token: String, appVersion: String, base: String = BASE): Me? {
        val (code, o) = call(base, "/api/tv/me", null, token, appVersion)
        return when (code) {
            200 -> o.optJSONObject("beta").let { b ->
                Me(o.optString("email"), o.optString("name"), b?.optBoolean("in") == true, b?.optString("tag")?.takeIf { it.isNotBlank() && it != "null" }, o.optBoolean("sendLog"))
            }
            401 -> null
            else -> throw Problem("avidviewer.app answered $code.")
        }
    }

    private fun failed(code: Int, o: JSONObject): Nothing = throw Problem(when (code) {
        401 -> "This device is no longer linked to your account. Link it again in Settings › Account."
        403 -> "Your Avid Viewer account is suspended. Email support@avidviewer.app for help."
        else -> o.optString("error").ifBlank { "avidviewer.app answered $code. Try again in a moment." }
    })

    /** After linking: the reports this device sent before it was linked now belong to the account (shown on its Reports page). */
    fun claimReports(token: String, ids: List<String>, base: String = BASE) {
        val (code, o) = call(base, "/api/tv/reports/claim", JSONObject().put("ids", org.json.JSONArray(ids)), token)
        if (code != 200) failed(code, o)
    }

    /** Sends this TV's backup, replacing the one it sent before, with its PIN (kept so the Account page can show it). */
    fun uploadBackup(token: String, data: String, pin: String, base: String = BASE) {
        val (code, o) = call(base, "/api/tv/backup", JSONObject().put("data", data).put("pin", pin), token)
        if (code != 200) failed(code, o)
    }

    /** The account's backups, newest first. */
    fun listBackups(token: String, base: String = BASE): List<CloudBackup> {
        val (code, o) = call(base, "/api/tv/backups", null, token)
        if (code != 200) failed(code, o)
        val a = o.optJSONArray("backups") ?: return emptyList()
        return List(a.length()) {
            val b = a.getJSONObject(it)
            CloudBackup(b.optString("deviceId"), b.optString("name", "Device"), b.optString("model"), b.optString("appVersion"),
                b.optLong("createdAt"), b.optInt("size"), if (b.isNull("playlists")) null else b.optInt("playlists"),
                b.optBoolean("removed"), b.optBoolean("thisTv"))
        }
    }

    /** One backup's file text (still locked with its PIN). */
    fun downloadBackup(token: String, deviceId: String, base: String = BASE): String {
        val (code, o) = call(base, "/api/tv/backup?device=" + java.net.URLEncoder.encode(deviceId, "UTF-8"), null, token)
        if (code != 200) failed(code, o)
        return o.optString("data").ifBlank { throw Problem("That backup came back empty.") }
    }

    // ---- playlists from avidviewer.app/playlists (1.14.0) ----
    /** A playlist the account wants added, changed or removed here. [payload] is sealed for this device (or null). */
    data class InboxItem(val playlistId: String, val action: String, val version: Int, val name: String, val type: String,
                         val profiles: List<String>?, val payload: JSONObject?,
                         /** 1.15.1: a playlist added on this device that the account now manages ("Add to account"). */
                         val localId: String? = null)

    /** Tells the account this device's key (for sealing logins to it) and its profiles (to choose on the website). */
    fun report(token: String, publicKey: String, profiles: List<Triple<String, String, Boolean>>, base: String = BASE) {
        val a = JSONArray().apply { profiles.forEach { (id, name, kids) -> put(JSONObject().put("id", id).put("name", name).put("kids", kids)) } }
        val (code, o) = call(base, "/api/tv/report", JSONObject().put("pubkey", publicKey).put("profiles", a), token)
        if (code != 200) failed(code, o)
    }

    fun inbox(token: String, base: String = BASE): List<InboxItem> = inbox2(token, null, base).items

    // ---- account profiles (1.15.0) ----
    /** What the account wants on this device: playlist changes, and its profiles when they changed since [profilesHash]. */
    data class Inbox(val items: List<InboxItem>, val managed: Boolean, val profilesHash: String, val profiles: JSONArray?,
                     /** 1.15.1: the account's public key (raw P-256, base64), to lock this device's logins for the owner's browser. */
                     val accountKey: String? = null)

    fun inbox2(token: String, profilesHash: String?, base: String = BASE): Inbox {
        val q = profilesHash?.let { "?ph=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
        val (code, o) = call(base, "/api/tv/inbox$q", null, token)
        if (code != 200) failed(code, o)
        val a = o.optJSONArray("items") ?: JSONArray()
        val items = List(a.length()) {
            val x = a.getJSONObject(it)
            InboxItem(x.getString("playlistId"), x.optString("action"), x.optInt("version"), x.optString("name"), x.optString("type"),
                x.optJSONArray("profiles")?.let { p -> List(p.length()) { i -> p.getString(i) } }, x.optJSONObject("payload"),
                x.optString("localId").ifEmpty { null })
        }
        return Inbox(items, o.optBoolean("managed"), o.optString("profilesHash"), o.optJSONArray("profiles"),
            o.optString("accountKey").ifEmpty { null })
    }

    /**
     * Reports this device's key and its profiles (1.15.0 form). The first time, the account takes them in (same name =
     * same profile) and answers which account profile each one now is: local id → account id.
     */
    fun report2(token: String, publicKey: String, profiles: JSONArray, visibility: JSONObject?, base: String = BASE): Map<String, String> {
        val b = JSONObject().put("v", 2).put("pubkey", publicKey).put("profiles", profiles)
        visibility?.let { b.put("visibility", it) }
        val (code, o) = call(base, "/api/tv/report", b, token)
        if (code != 200) failed(code, o)
        val m = o.optJSONObject("profileMap") ?: JSONObject()
        return m.keys().asSequence().associateWith { m.getString(it) }
    }

    /**
     * Every playlist on this device (1.15.1): name, type, what's switched on, and which account playlist it is, so the
     * website can list playlists added on the TV. A login goes only locked for the account's key ([DeviceKey.seal]).
     */
    fun devicePlaylists(token: String, list: JSONArray, base: String = BASE) {
        val (code, o) = call(base, "/api/tv/playlists", JSONObject().put("playlists", list), token)
        if (code != 200) failed(code, o)
    }

    /**
     * 1.16.0: favorites and lists (per account profile) and category/channel order and names (per account playlist):
     * sends what changed here, answers what the account has that this device doesn't ([known] hashes).
     */
    fun listsSync(token: String, data: JSONObject, base: String = BASE): JSONObject {
        val (code, o) = call(base, "/api/tv/sync", data, token)
        if (code != 200) failed(code, o)
        return o
    }

    /** 1.17.0: locked places, Discover settings and the TMDB key (see [History]). */
    fun history(token: String, data: JSONObject, base: String = BASE): History.Answer {
        val (code, o) = call(base, "/api/tv/history", data, token)
        if (code != 200) failed(code, o)
        return History.Answer(o.optBoolean("enabled"), o.optJSONObject("key"), o.optJSONObject("tmdb"), o.optJSONArray("profiles") ?: JSONArray())
    }

    /** A playlist's categories and channels (names and counts only), for choosing what each profile sees on the website. */
    fun catalog(token: String, data: JSONObject, base: String = BASE) {
        val (code, o) = call(base, "/api/tv/catalog", data, token)
        if (code != 200) failed(code, o)
    }

    /**
     * Watching now: which playlist and profile are playing (never the channel or title), or nothing. Answers true when
     * the account asked this device to stop.
     */
    fun playing(token: String, data: JSONObject, base: String = BASE): Boolean {
        val (code, o) = call(base, "/api/tv/playing", data, token)
        if (code != 200) failed(code, o)
        return o.optBoolean("stop")
    }

    fun ack(token: String, item: InboxItem, error: String?, base: String = BASE) {
        val (code, o) = call(base, "/api/tv/inbox/ack", JSONObject().put("playlistId", item.playlistId).put("version", item.version)
            .put("ok", error == null).put("error", error ?: JSONObject.NULL), token)
        if (code != 200) failed(code, o)
    }

    /** "K7M2QX9P" → "K7M2-QX9P", as the link code is shown. */
    fun pretty(code: String) = if (code.length == 8) code.substring(0, 4) + "-" + code.substring(4) else code

    fun signOut(token: String, base: String = BASE) { runCatching { call(base, "/api/tv/signout", JSONObject(), token) } }
}

/** The account link, for the whole TV (all profiles): SharedPreferences "account", the token encrypted. */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    val token: String? get() = prefs.getString("token", null)?.let { stored ->
        if (TESTS && stored.startsWith("test:")) stored.removePrefix("test:") else runCatching { Crypto.decrypt(stored) }.getOrNull()
    }?.takeIf { it.isNotBlank() }
    val email: String get() = prefs.getString("email", "").orEmpty()
    val name: String get() = prefs.getString("name", "").orEmpty()
    val linked: Boolean get() = token != null

    fun save(l: AccountApi.Linked) {
        // Robolectric has no Android Keystore: only the tests keep the token unencrypted.
        val enc = runCatching { Crypto.encrypt(l.token) }.getOrElse { if (TESTS) "test:${l.token}" else throw it }
        prefs.edit().putString("token", enc).putString("email", l.email).putString("name", l.name)
            .putString("deviceId", l.deviceId).apply()
    }

    val deviceId: String get() = prefs.getString("deviceId", "").orEmpty()

    /** The backup PIN for cloud backups, set once on this TV (encrypted like the token). */
    var backupPin: String?
        get() = prefs.getString("backupPin", null)?.let { stored ->
            if (TESTS && stored.startsWith("test:")) stored.removePrefix("test:") else runCatching { Crypto.decrypt(stored) }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        set(v) {
            if (v == null) { prefs.edit().remove("backupPin").remove("backupPrint").apply(); return }
            val enc = runCatching { Crypto.encrypt(v) }.getOrElse { if (TESTS) "test:$v" else throw it }
            // A new PIN means the next backup must be sent again, locked with it.
            prefs.edit().putString("backupPin", enc).remove("backupPrint").apply()
        }
    /** Fingerprint of what was last sent, so an unchanged TV isn't backed up again. */
    var backupPrint: String
        get() = prefs.getString("backupPrint", "").orEmpty()
        set(v) { prefs.edit().putString("backupPrint", v).apply() }
    var lastBackupAt: Long
        get() = prefs.getLong("lastBackupAt", 0L)
        set(v) { prefs.edit().putLong("lastBackupAt", v).apply() }
    /** 1.16.1: when "Link this device" was last put off with Not now (devices with playlists that aren't linked). */
    var linkReminderAt: Long
        get() = prefs.getLong("linkReminderAt", 0L)
        set(v) { prefs.edit().putLong("linkReminderAt", v).apply() }
    /** 1.17.2: each profile's hidden items in account playlists were set once to exactly the account's (repair). */
    var hidesRepaired: Boolean
        get() = prefs.getBoolean("hidesRepaired", false)
        set(v) { prefs.edit().putBoolean("hidesRepaired", v).apply() }
    /** The automatic daily backup (on once a PIN is set; can be turned off). */
    var autoBackup: Boolean
        get() = prefs.getBoolean("autoBackup", true)
        set(v) { prefs.edit().putBoolean("autoBackup", v).apply() }

    /** This device's key for playlists from avidviewer.app (made once; the private half encrypted like the token). */
    fun deviceKey(): DeviceKey.Pair? = runCatching {
        var pub = prefs.getString("devPub", null)
        var priv = prefs.getString("devKey", null)?.let { s ->
            if (TESTS && s.startsWith("test:")) s.removePrefix("test:") else Crypto.decrypt(s)
        }
        if (pub == null || priv.isNullOrBlank()) {
            val (p, k) = DeviceKey.generate()
            val enc = runCatching { Crypto.encrypt(k) }.getOrElse { if (TESTS) "test:$k" else throw it }
            prefs.edit().putString("devPub", p).putString("devKey", enc).apply()
            pub = p; priv = k
        }
        DeviceKey.load(pub!!, priv!!)
    }.getOrNull()

    /** 1.15.0: the hash of the account profiles last applied here, and that list (to map it again when playlists arrive). */
    var profilesHash: String
        get() = prefs.getString("profilesHash", "").orEmpty()
        set(v) { prefs.edit().putString("profilesHash", v).apply() }
    var profilesJson: String
        get() = prefs.getString("profilesJson", "").orEmpty()
        set(v) { prefs.edit().putString("profilesJson", v).apply() }
    /** What this device last told the account about its playlists (a signature), so unchanged lists aren't sent again. */
    var playlistsSent: String
        get() = prefs.getString("playlistsSent", "").orEmpty()
        set(v) { prefs.edit().putString("playlistsSent", v).apply() }
    /**
     * 1.16.0: favorites/lists and order sync state: {"p":{accountProfileId:{"h":hash,"s":sig,"d":lastAccountDoc}},
     * "o":{accountPlaylistId:{"h","s"}}} — the account's version last received (hash), and this device's own version
     * last sent or applied (signature), so only real changes go up.
     */
    var listsState: String
        get() = prefs.getString("listsState", "{}").orEmpty()
        set(v) { prefs.edit().putString("listsState", v).apply() }
    /** When favorites or lists last changed on this device, per local profile id; when order or names last changed. */
    var listsChanged: String
        get() = prefs.getString("listsChanged", "{}").orEmpty()
        set(v) { prefs.edit().putString("listsChanged", v).apply() }
    /** 1.17.0: the account's history key (opened from what the website locked for this device), kept encrypted. */
    var historyKey: ByteArray?
        get() = prefs.getString("historyKey", null)?.let { stored ->
            if (TESTS && stored.startsWith("test:")) stored.removePrefix("test:") else runCatching { Crypto.decrypt(stored) }.getOrNull()
        }?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }
        set(v) {
            if (v == null) { prefs.edit().remove("historyKey").apply(); return }
            val b = android.util.Base64.encodeToString(v, android.util.Base64.NO_WRAP)
            val enc = runCatching { Crypto.encrypt(b) }.getOrElse { if (TESTS) "test:$b" else throw it }
            prefs.edit().putString("historyKey", enc).apply()
        }
    /** The TMDB key last received from the account (to tell a new one from the same one). */
    var tmdbFromAccount: String
        get() = prefs.getString("tmdbFromAccount", "").orEmpty()
        set(v) { prefs.edit().putString("tmdbFromAccount", v).apply() }
    var orderChangedAt: Long
        get() = prefs.getLong("orderChangedAt", 0L)
        set(v) { prefs.edit().putLong("orderChangedAt", v).apply() }

    /** Each account playlist's catalog as last sent: playlist id → "signature@time". */
    var catalogSent: String
        get() = prefs.getString("catalogSent", "{}").orEmpty()
        set(v) { prefs.edit().putString("catalogSent", v).apply() }

    fun update(me: AccountApi.Me) { prefs.edit().putString("email", me.email).putString("name", me.name).apply() }

    fun clear() { prefs.edit().clear().apply() }

    private companion object { val TESTS = Build.FINGERPRINT == "robolectric" }
}
