package com.avidviewer.tv

import org.robolectric.RobolectricTestRunner
import com.avidviewer.tv.data.CrashReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** "Report issue" never sends a provider's address or login, and an empty relay sends nothing. */
@RunWith(RobolectricTestRunner::class)
class CrashReportTest {
    @Test
    fun addressesHostsAndLoginsAreRemoved() {
        val raw = """
            java.io.FileNotFoundException: http://my.provider.tv:8080/get.php?username=bob&password=hunter2&type=m3u
            java.net.UnknownHostException: Unable to resolve host "epg.example.com": No address associated with hostname
            java.net.ConnectException: Failed to connect to /192.168.1.20:445
            Caused by: java.io.IOException: bad response for user=bob token=abc123
              at com.avidviewer.tv.data.Net.open(Sources.kt:60)
        """.trimIndent()
        val out = CrashReport.redact(raw)
        for (s in listOf("my.provider.tv", "bob", "hunter2", "epg.example.com", "192.168.1.20", "abc123", "http://")) assertFalse("kept $s:\n$out", out.contains(s))
        assertTrue(out, out.contains("java.net.UnknownHostException") && out.contains("Sources.kt:60"))
    }

    @Test
    fun thePayloadCarriesTheVersionDeviceAndError() {
        val p = CrashReport.payload("1.2.1", "Avid Viewer 1.2.1\n\njava.lang.IllegalStateException: boom\n  at x.y(Z.kt:1)")
        assertEquals("avid-viewer", p.getString("app"))
        assertEquals("1.2.1", p.getString("version"))
        assertTrue(p.getString("device").isNotBlank())
        assertTrue(p.getString("summary"), p.getString("summary").startsWith("java.lang.IllegalStateException: boom"))
    }

    @Test
    fun noRelayMeansNothingIsSent() {
        assertFalse(CrashReport.send("1.2.1", "boom", relay = ""))
    }
}
