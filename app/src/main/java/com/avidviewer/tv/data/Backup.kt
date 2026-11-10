package com.avidviewer.tv.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A backup file for reinstalling the app: playlists (with their logins), every setting, favorites,
 * lists, names, order, hidden items, sports categories, location, the TMDB key, watch history and
 * resume points. Channels, guides and movie catalogs aren't included; they download again.
 *
 * The file is locked with a PIN the viewer chooses (AES-256-GCM, key from PBKDF2 with 120,000 rounds),
 * because it holds provider logins. Without the PIN it can't be read.
 */
object Backup {
    const val EXTENSION = "avbak"
    private const val ROUNDS = 120_000

    class Contents(
        val playlists: List<Playlist>,
        val prefs: Prefs,
        val tmdbKey: String,
        val smbPassword: String = "",
        val history: List<HistoryEntry>,
        val resume: Map<String, Long>,
        val created: Long,
        val appVersion: String,
    )

    class WrongPin : Exception("That PIN doesn't open this backup.")
    class NotABackup : Exception("This file isn't an Avid Viewer backup.")

    fun write(c: Contents, pin: String): String {
        val body = JSONObject()
            .put("created", c.created)
            .put("appVersion", c.appVersion)
            .put("playlists", JSONArray().apply {
                c.playlists.forEach { p ->
                    put(
                        JSONObject().put("id", p.id).put("name", p.name).put("type", p.type).put("server", p.server)
                            .put("username", p.username).put("password", p.password).put("m3uUrl", p.m3uUrl).put("epgUrl", p.epgUrl)
                            .put("enabled", p.enabled).put("liveOn", p.liveOn).put("vodOn", p.vodOn),
                    )
                }
            })
            .put("prefs", c.prefs.copy(tmdbKeyEnc = "", smbPassEnc = "").toJson())
            .put("tmdbKey", c.tmdbKey)
            .put("smbPassword", c.smbPassword)
            .put("history", JSONArray().apply { c.history.forEach { put(JSONObject().put("k", it.key).put("t", it.time).put("s", it.seconds)) } })
            .put("resume", JSONObject(c.resume))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val kdf = bestKdf()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(pin, salt, kdf))
        val data = cipher.doFinal(body.toString().toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("avidViewerBackup", 1)
            .put("created", c.created)
            .put("appVersion", c.appVersion)
            .put("playlistNames", JSONArray(c.playlists.map { it.name }))
            .put("kdf", kdf).put("salt", b64(salt)).put("iv", b64(cipher.iv)).put("data", b64(data))
            .toString()
    }

    /** What the file says about itself, readable without the PIN: (created, app version, playlist names). */
    fun peek(text: String): Triple<Long, String, List<String>> {
        val o = runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.optInt("avidViewerBackup") == 1 } ?: throw NotABackup()
        val names = o.optJSONArray("playlistNames")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
        return Triple(o.optLong("created"), o.optString("appVersion"), names)
    }

    fun read(text: String, pin: String): Contents {
        val o = runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.optInt("avidViewerBackup") == 1 } ?: throw NotABackup()
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(pin, unb64(o.getString("salt")), o.optString("kdf", "PBKDF2WithHmacSHA256")), GCMParameterSpec(128, unb64(o.getString("iv"))))
            String(cipher.doFinal(unb64(o.getString("data"))), Charsets.UTF_8)
        } catch (e: Exception) {
            throw WrongPin()
        }
        val b = JSONObject(plain)
        val pls = b.optJSONArray("playlists") ?: JSONArray()
        val playlists = List(pls.length()) {
            val p = pls.getJSONObject(it)
            Playlist(
                id = p.getString("id"), name = p.optString("name"), type = p.optString("type"), server = p.optString("server"),
                username = p.optString("username"), password = p.optString("password"), m3uUrl = p.optString("m3uUrl"),
                epgUrl = p.optString("epgUrl"), enabled = p.optBoolean("enabled", true), liveOn = p.optBoolean("liveOn", true),
                vodOn = p.optBoolean("vodOn", true),
            )
        }
        val hist = b.optJSONArray("history") ?: JSONArray()
        val history = List(hist.length()) { val h = hist.getJSONObject(it); HistoryEntry(h.getString("k"), h.optLong("t"), h.optLong("s")) }
        val r = b.optJSONObject("resume")
        val resume = r?.keys()?.asSequence()?.associateWith { r.optLong(it) } ?: emptyMap()
        return Contents(playlists, Prefs.fromJson(b.getJSONObject("prefs")), b.optString("tmdbKey"), history, resume, b.optLong("created"), b.optString("appVersion"), smbPassword = b.optString("smbPassword"))
    }

    /** SHA-256 where the device has it (Android 8+), SHA-1 on older devices; the file records which. */
    private fun bestKdf(): String =
        if (runCatching { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") }.isSuccess) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"

    private fun key(pin: String, salt: ByteArray, kdf: String): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ROUNDS, 256)
        val bytes = SecretKeyFactory.getInstance(kdf).generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
