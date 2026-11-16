package com.avidviewer.tv

import com.avidviewer.tv.data.SetupCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Setup codes (1.11.0). The fixture was made by public/setup.js in mavenmarty/avidviewer-site (Node's WebCrypto,
 * code K7M2QX9P, IV 1..12), so these tests check the TV opens exactly what the website seals.
 */
@RunWith(RobolectricTestRunner::class)
class SetupCodeTest {
    companion object {
        const val CODE = "K7M2QX9P"
        const val ID = "318c7607d3f72a738be4cd1efdaba0fad377df92aa4aa3f2505541d81f965f96"
        const val DATA = "AQIDBAUGBwgJCgsMi90SiCfeEzLPJE13lrmLvBcbNxV5QeAv8isg0ZVcJVb3LzTi9bBmxi09FeQl3bmVEn2jyZSak6mZ5For6hmLsVk/bmWEFfgIhrGQiWe9OuZ33c1wSPH8k/O+MyVRJSq0BDLhZZVd96X7c14U05ZAetqGJBTBCrEgYggUvpxrN6jI4FYJHntS5I6vEcmrZyog"
    }

    @Test
    fun codesAreNormalizedLikeTheWebsiteShowsThem() {
        assertEquals("K7M2QX9P", SetupCode.normalize("k7m2-qx9p"))
        assertEquals("K7M2QX9P", SetupCode.normalize(" K7M2 QX9P "))
        assertNull(SetupCode.normalize("K7M2-QX9"))
        assertEquals("K7M2-QX9P", SetupCode.pretty("K7M2QX9P"))
    }

    @Test
    fun pbkdf2MatchesTheStandardVector() {
        // RFC 7914 §11: PBKDF2-HMAC-SHA256("passwd", "salt", 1, 64), first 32 bytes.
        val k = SetupCode.pbkdf2("passwd".toByteArray(), "salt".toByteArray(), 1, 32).joinToString("") { "%02x".format(it) }
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", k)
    }

    @Test
    fun opensWhatTheWebsiteSealed() {
        assertEquals(ID, SetupCode.id(CODE))
        val s = SetupCode.parse(SetupCode.decrypt(CODE, DATA))
        assertEquals(1, s.playlists.size)
        val p = s.playlists[0]
        assertEquals("m3u", p.type); assertEquals("Living room", p.name)
        assertEquals("http://127.0.0.1:9/tv.m3u", p.m3u); assertEquals("http://127.0.0.1:9/guide.xml", p.epg)
    }

    @Test(expected = SetupCode.Problem::class)
    fun aWrongCodeCantOpenIt() { SetupCode.decrypt("K7M2QX9Q", DATA) }
}
