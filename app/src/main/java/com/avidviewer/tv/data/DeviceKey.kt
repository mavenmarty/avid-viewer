package com.avidviewer.tv.data

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * This device's own key for playlists sent from avidviewer.app/playlists (1.14.0).
 *
 * The device makes a P-256 key pair once and reports the public half to the account. The website's page locks each
 * playlist login for this device in the browser: an ephemeral P-256 key, ECDH with this device's key, HKDF-SHA256
 * (salt = the ephemeral public key, info "avidviewer-playlist:v1") → AES-256-GCM. Only this device's private key can
 * open it; that key is kept encrypted with the Android Keystore ([Crypto]) and never leaves the device.
 * (ECDH through the Keystore itself needs Android 12, and Fire OS 7 is Android 9, so the key is a software key.)
 */
object DeviceKey {
    private const val INFO = "avidviewer-playlist:v1"

    class Pair(val publicRaw: ByteArray, val private: ECPrivateKey) {
        val publicB64: String get() = Base64.encodeToString(publicRaw, Base64.NO_WRAP)
    }

    /** A new key pair: the public key as the raw 65-byte point (what the browser imports), the private as PKCS#8. */
    fun generate(): kotlin.Pair<String, String> {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp = g.generateKeyPair()
        return Base64.encodeToString(raw(kp.public as ECPublicKey), Base64.NO_WRAP) to
            Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
    }

    fun load(publicB64: String, pkcs8B64: String): Pair {
        val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(pkcs8B64, Base64.NO_WRAP))) as ECPrivateKey
        return Pair(Base64.decode(publicB64, Base64.NO_WRAP), priv)
    }

    private fun raw(pub: ECPublicKey): ByteArray = byteArrayOf(4) + fixed(pub.w.affineX) + fixed(pub.w.affineY)

    private fun fixed(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    /** Opens an envelope {v:1, epk, iv, ct} sealed for [key]; throws if it wasn't made for this device or was changed. */
    fun open(key: Pair, envelope: JSONObject): JSONObject {
        require(envelope.optInt("v") == 1) { "Unknown envelope" }
        val epk = Base64.decode(envelope.getString("epk"), Base64.NO_WRAP)
        require(epk.size == 65 && epk[0] == 4.toByte()) { "Bad key" }
        val params = key.private.params
        val point = ECPoint(BigInteger(1, epk.copyOfRange(1, 33)), BigInteger(1, epk.copyOfRange(33, 65)))
        val eph = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        val z = KeyAgreement.getInstance("ECDH").run { init(key.private); doPhase(eph, true); generateSecret() }
        val aes = hkdf(salt = epk, ikm = z, info = INFO.toByteArray(Charsets.UTF_8), length = 32)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)))
        return JSONObject(String(c.doFinal(Base64.decode(envelope.getString("ct"), Base64.NO_WRAP)), Charsets.UTF_8))
    }

    /** RFC 5869 HKDF with HMAC-SHA256 (what WebCrypto's HKDF does). */
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(salt, "HmacSHA256")); doFinal(ikm) }
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0); var i = 1
        while (out.size() < length) {
            t = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(prk, "HmacSHA256")); update(t); update(info); update(i.toByte()); doFinal() }
            out.write(t); i++
        }
        return out.toByteArray().copyOf(length)
    }
}
