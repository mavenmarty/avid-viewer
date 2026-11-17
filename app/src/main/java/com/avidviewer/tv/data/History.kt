package com.avidviewer.tv.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pick up where you left off (1.17.0). What a profile watched, its Discover settings and the account's TMDB key are
 * locked on the TV with the account's history key before they leave it; the website and its admin panel store them
 * without being able to read them. The key arrives once, locked for this device (like playlist logins), after the owner
 * unlocks with a passkey on the website.
 *
 * Blobs: AES-256-GCM, base64(iv(12) || ciphertext+tag), JSON inside, the same as the website's.
 * Item ids: HMAC-SHA256(key, "m|<title>|<year>" or "e|<series>|<season>|<episode>"), so the same title from another
 * playlist or provider is one entry, and the website can't tell which title it is.
 */
object History {
    fun lock(key: ByteArray, obj: JSONObject): String {
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = c.doFinal(obj.toString().toByteArray())
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    fun open(key: ByteArray, blob: String): JSONObject {
        val all = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        return JSONObject(String(c.doFinal(all.copyOfRange(12, all.size))))
    }

    fun itemId(key: ByteArray, match: String): String {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.encodeToString(m.doFinal(match.toByteArray()), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING).take(22)
    }

    fun movieMatch(name: String, year: String) = "m|" + Tmdb.normalize(name) + "|" + year.take(4)
    fun episodeMatch(series: String, season: String, episode: String) = "e|" + Tmdb.normalize(series) + "|" + season.trimStart('0') + "|" + episode.trimStart('0')

    /** The account's answer to [AccountApi.history]. */
    class Answer(val enabled: Boolean, val key: JSONObject?, val tmdb: JSONObject?, val profiles: JSONArray)
}
