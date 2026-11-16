package com.avidviewer.tv.data

import android.content.Context
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Linking this TV to an avidviewer.app account (1.11.0). See src/worker/devices.js in mavenmarty/avidviewer-site.
 *
 * Settings › Account › Link this TV asks for a code ([start]), shows it with a QR code for avidviewer.app/link, and
 * polls ([poll]) until the person approves it on their phone; the TV then gets its own token. A setup code made
 * while signed in carries a link token the TV trades for one ([claim]). The token is kept encrypted on the TV
 * ([AccountStore]); [me] checks it's still linked (a TV removed on the Account page gets 401).
 * Only the TV's name, model and app version are sent: never playlists, channels or what's watched.
 */
object AccountApi {
    const val BASE = "https://avidviewer.app"

    class Problem(message: String) : Exception(message)

    data class Info(val name: String, val model: String, val appVersion: String)
    data class Start(val userCode: String, val deviceCode: String, val interval: Int, val expiresIn: Int, val qrUrl: String)
    data class Linked(val token: String, val email: String, val name: String, val deviceId: String)
    data class Me(val email: String, val name: String)

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
            else -> throw Problem(if (code == 200) "The code expired before it was used. Choose Link this TV for a new one." else "avidviewer.app answered $code.")
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
            200 -> Me(o.optString("email"), o.optString("name"))
            401 -> null
            else -> throw Problem("avidviewer.app answered $code.")
        }
    }

    fun signOut(token: String, base: String = BASE) { runCatching { call(base, "/api/tv/signout", JSONObject(), token) } }
}

/** The account link, for the whole TV (all profiles): SharedPreferences "account", the token encrypted. */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    val token: String? get() = prefs.getString("token", null)?.let { runCatching { Crypto.decrypt(it) }.getOrNull() }?.takeIf { it.isNotBlank() }
    val email: String get() = prefs.getString("email", "").orEmpty()
    val name: String get() = prefs.getString("name", "").orEmpty()
    val linked: Boolean get() = token != null

    fun save(l: AccountApi.Linked) {
        prefs.edit().putString("token", Crypto.encrypt(l.token)).putString("email", l.email).putString("name", l.name)
            .putString("deviceId", l.deviceId).apply()
    }

    fun update(me: AccountApi.Me) { prefs.edit().putString("email", me.email).putString("name", me.name).apply() }

    fun clear() { prefs.edit().clear().apply() }
}
