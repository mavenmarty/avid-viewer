package com.avidviewer.tv.data

import android.util.Base64
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Setup codes from avidviewer.app/setup (1.11.0).
 *
 * On the website a person enters their playlists and gets a code like K7M2-QX9P. The page
 * encrypts what was entered in the browser with a key made from the code, and sends the server only that ciphertext
 * and a fingerprint of the code ([id]), so the server never sees a login or the code itself. The TV types the code,
 * asks the server for the ciphertext by the fingerprint, and decrypts it with the same key.
 *
 * Must match public/setup.js in mavenmarty/avidviewer-site:
 * - code: 8 characters from [ALPHABET] (no 0/O, 1/I/L), shown as XXXX-XXXX
 * - id: hex SHA-256 of "avidviewer-setup-id:v1:" + code
 * - key: PBKDF2-HMAC-SHA256(code, "avidviewer-setup-key:v1", 100,000 rounds, 32 bytes)
 * - data: Base64 of 12-byte IV + AES-256-GCM ciphertext (128-bit tag) of the JSON payload
 * - payload: {"v":1,"playlists":[{"type":"xtream"|"m3u","name","server","username","password","m3u","epg"}]}
 *   plus "link" when the person was signed in: a one-time token for [AccountApi.claim]).
 *
 * Codes last 30 minutes. Later (accounts, Phase 2) the payload can also carry an account link and a license.
 */
object SetupCode {
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 8
    const val PAGE = "https://avidviewer.app/setup/"
    const val API = "https://avidviewer.app/api/setup/"
    private const val ROUNDS = 100_000

    data class Item(
        val type: String, val name: String, val server: String = "", val username: String = "",
        val password: String = "", val m3u: String = "", val epg: String = "",
    )
    /** [link]: a one-time token from a setup code made while signed in; the TV trades it for an account link. */
    data class Setup(val playlists: List<Item>, val link: String? = null)

    class Problem(message: String) : Exception(message)

    /** "k7m2 qx9p", "K7M2-QX9P" → "K7M2QX9P"; null until it's 8 valid characters. */
    fun normalize(input: String): String? {
        val s = input.uppercase().filter { it in ALPHABET }
        return if (s.length == LENGTH) s else null
    }

    fun pretty(code: String) = if (code.length == LENGTH) code.take(4) + "-" + code.drop(4) else code

    fun id(code: String): String =
        MessageDigest.getInstance("SHA-256").digest("avidviewer-setup-id:v1:$code".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun key(code: String): ByteArray = pbkdf2(code.toByteArray(), "avidviewer-setup-key:v1".toByteArray(), ROUNDS, 32)

    /** PBKDF2-HMAC-SHA256 by hand: the platform's PBKDF2WithHmacSHA256 needs Android 8, the app runs on 6. */
    internal fun pbkdf2(password: ByteArray, salt: ByteArray, rounds: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        val out = ByteArray(length)
        var block = 1; var done = 0
        while (done < length) {
            mac.update(salt); mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(rounds - 1) { u = mac.doFinal(u); for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte() }
            val n = minOf(t.size, length - done)
            System.arraycopy(t, 0, out, done, n)
            done += n; block++
        }
        return out
    }

    fun decrypt(code: String, data: String): String {
        val bytes = Base64.decode(data, Base64.DEFAULT)
        if (bytes.size < 13) throw Problem("The setup data is damaged. Make a new code.")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(code), "AES"), GCMParameterSpec(128, bytes, 0, 12))
        return try { String(c.doFinal(bytes, 12, bytes.size - 12)) } catch (e: Exception) { throw Problem("The setup data is damaged. Make a new code.") }
    }

    fun parse(json: String): Setup {
        val o = JSONObject(json)
        if (o.optInt("v", 0) != 1) throw Problem("This code is for a newer version of Avid Viewer. Update the app, then try again.")
        val list = o.optJSONArray("playlists")
        val items = (0 until (list?.length() ?: 0)).mapNotNull { i ->
            val p = list!!.getJSONObject(i)
            val type = p.optString("type")
            if (type != "xtream" && type != "m3u") null
            else Item(
                type = type, name = p.optString("name").trim().take(60), server = p.optString("server").trim(),
                username = p.optString("username").trim(), password = p.optString("password"),
                m3u = p.optString("m3u").trim(), epg = p.optString("epg").trim(),
            )
        }
        val link = o.optString("link").takeIf { it.isNotBlank() }
        if (items.isEmpty()) throw Problem("This code has nothing to set up. Make a new one on avidviewer.app/setup.")
        return Setup(items, link)
    }

    /** Fetches and opens a code's setup. Network errors and unknown codes come back as a [Problem] with a plain message. */
    fun fetch(code: String, api: String = API): Setup {
        val req = Request.Builder().url(api + id(code)).header("User-Agent", USER_AGENT).build()
        val data = try {
            Net.client.newCall(req).execute().use { r ->
                when {
                    r.code == 404 || r.code == 410 -> throw Problem("That code wasn't found. Codes last 30 minutes: check it, or make a new one on avidviewer.app/setup.")
                    r.code == 429 -> throw Problem("Too many tries. Wait a minute, then try again.")
                    !r.isSuccessful -> throw Problem("avidviewer.app answered ${r.code}. Try again in a moment.")
                    else -> JSONObject(r.body?.string().orEmpty()).getString("data")
                }
            }
        } catch (e: Problem) { throw e } catch (e: Exception) {
            throw Problem("Couldn't reach avidviewer.app. Check the TV's internet connection, then try again.")
        }
        return parse(decrypt(code, data))
    }
}
