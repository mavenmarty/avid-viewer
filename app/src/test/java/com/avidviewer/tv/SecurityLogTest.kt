package com.avidviewer.tv

import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.CrashReport
import com.avidviewer.tv.data.SecurityLog
import com.avidviewer.tv.data.SecurityMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** freeRASP findings (1.11.0): kept on the TV, alerts emailed once per kind, Fire TV's normal state never emailed. */
@RunWith(RobolectricTestRunner::class)
class SecurityLogTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun alertsAreSentOncePerKindAndInfoNever() {
        ctx.getSharedPreferences("security-log", 0).edit().clear().commit()
        var now = 1_000_000L
        val log = SecurityLog(ctx) { now }
        // Sideloading on a Fire TV: developer options, ADB, outside a store. Kept, not emailed.
        assertFalse(log.record("devMode")); assertFalse(log.record("adb")); assertFalse(log.record("unofficialStore"))
        assertTrue(log.pending().isEmpty())
        // A rooted TV: an alert, once per app start.
        assertTrue(log.record("root")); assertFalse(log.record("root"))
        assertEquals(listOf("root"), log.pending())
        assertEquals("Security: rooted device", log.summary())
        log.markSent(log.pending())
        assertTrue(log.pending().isEmpty())
        // Next app start: still counted, not emailed again; a new kind is.
        now += 3_600_000L
        val again = SecurityLog(ctx) { now }
        assertFalse(again.record("root"))
        assertTrue(again.record("hooks"))
        assertEquals(listOf("hooks"), again.pending())
        assertEquals(2, again.findings().first { it.kind == "root" }.count)
        assertTrue(again.text(), again.text().contains("rooted device: seen in 2 app start(s)"))
        assertTrue(again.text(), again.text().contains("ADB on (info)"))
    }

    @Test
    fun certificateHashAndPayload() {
        // SHA-256 of "abc" in hex and Base64, as BuildConfig holds it and as Talsec wants it.
        assertEquals("ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=",
            SecurityMonitor.hexToBase64("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertFalse("never started in tests", SecurityMonitor.started)
        val p = CrashReport.securityPayload("1.11.0", "Security: rooted device", "rooted device: seen in 1 app start(s)")
        assertEquals("security", p.getString("kind"))
        assertEquals("avid-viewer", p.getString("app"))
    }
}
