package com.avidviewer.tv

import com.avidviewer.tv.data.DeviceKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 1.14.0: a playlist login sealed by avidviewer.app/playlists' own script (public/playlists.js, AvidPlaylists.seal, run in
 * Chrome) for a fixed device key opens with that key here, and with no other.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceKeyTest {
    @Test
    fun aLoginSealedByTheWebsiteOpensOnTheDevice() {
        val key = DeviceKey.load(PUB, PKCS8)
        val o = DeviceKey.open(key, JSONObject(SEALED))
        assertEquals("Fixture", o.getString("name"))
        assertEquals("xtream", o.getString("type"))
        assertEquals("u1", o.getString("username"))
        assertEquals("p1", o.getString("password"))
        assertEquals("http://127.0.0.1:9/guide.xml", o.getString("epgUrl"))
    }

    @Test(expected = Exception::class)
    fun anotherDevicesKeyCantOpenIt() {
        val (pub, priv) = DeviceKey.generate()
        DeviceKey.open(DeviceKey.load(pub, priv), JSONObject(SEALED))
    }

    @Test
    fun aNewKeyIsARawP256Point() {
        val (pub, _) = DeviceKey.generate()
        val raw = android.util.Base64.decode(pub, android.util.Base64.NO_WRAP)
        assertEquals(65, raw.size); assertEquals(4.toByte(), raw[0])
    }

    companion object {
        const val PUB = "BJR5SgnVzdW/ggCkFA5JkkNighaltUb+G1Ln5be7ExL6Ue9sFSdbEvtqHOy1RPT8EgxAtuukqsiucy7CZ6bKjIw="
        const val PKCS8 = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgOZv2SpmYABg/Dg1xI8JUDxs+8Ddw+RlNwHsSHN8iGwahRANCAASUeUoJ1c3Vv4IApBQOSZJDYoIWpbVG/htS5+W3uxMS+lHvbBUnWxL7ahzstUT0/BIMQLbrpKrIrnMuwmemyoyM"
        const val SEALED = """{"v": 1, "epk": "BEPvQRID9DqSaaTQ9G1QDl8EztSHZmhgBfjsePdw7Dr4Be+cqqS3YJ11IH7uqoHabYg5wvIb3iIWhbzK7qyRWCA=", "iv": "LQBGm1VsKxC2XwFT", "ct": "wfGV+jhs+RxhJwtaHuusJtVWR5GXhGZqo/aXaGFZ4VBPUeWVfNNAPFIXQA55qcuhnLnxzF8r6Po0laxvXBLT1yEIGK3c5aN2dDoJNqgVZm2tON9nWGwALeFBj/Aw/DZaKuh3+3uRaVkaBmG4cCesYn+avXwKmmAdnb/Y/+k+2koD074wb82bT5dS9b+JOnSD32jPsGqXPck="}"""
    }
}
