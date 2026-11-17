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

    /**
     * #16 (Nov 14): the app was closed hard (to the Fire TV home screen) with no report. Closed while on
     * screen now counts as a crash at the next launch, with the last steps and memory, never names or searches.
     */
    @Test
    fun closedWhileOnScreenIsReportedAtTheNextLaunch() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val log = com.avidviewer.tv.data.CrashLog
        log.clear(app)
        // Left normally (Home): nothing to report.
        log.inUse(app); log.note(app, "Screen: search"); log.leftNormally(app)
        Thread.sleep(300)
        assertFalse(log.adoptUncleanExit(app))
        assertEquals(null, log.read(app))
        // Closed while on screen: the next launch makes a report the dialog can send.
        log.inUse(app); log.note(app, "Screen: search"); log.note(app, "Search: 2 word(s), 10 letters")
        Thread.sleep(300)
        assertTrue(log.adoptUncleanExit(app))
        val report = log.read(app)!!
        assertTrue(report, report.contains("closed while in use") && report.contains("Search: 2 word(s)") && report.contains("Memory: app"))
        assertTrue(report, com.avidviewer.tv.data.BugReport.crashSummary(report)!!.startsWith("Error: Avid Viewer closed while in use"))
        // Once only.
        assertFalse(log.adoptUncleanExit(app))
        log.leftNormally(app); log.clear(app)
    }

    /** Nov 17 (AVR-b5a685ae): updating the app while it's on screen isn't a crash (the marker is from the old version). */
    @Test
    fun anUpdateWhileOnScreenIsNotReportedAsACrash() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val log = com.avidviewer.tv.data.CrashLog
        val keep = log.runningVersion
        log.clear(app)
        log.runningVersion = "1.15.2"
        log.inUse(app); log.note(app, "Screen: settings")
        Thread.sleep(300)
        log.runningVersion = "1.16.0" // the new version starts
        assertFalse(log.adoptUncleanExit(app))
        assertEquals(null, log.read(app))
        // The same version closed while on screen is still reported.
        log.inUse(app); log.note(app, "Screen: guide")
        Thread.sleep(300)
        assertTrue(log.adoptUncleanExit(app))
        log.leftNormally(app); log.clear(app); log.runningVersion = keep
    }

    /**
     * 1.13.1: the screen is marked "on screen" (onStart) before the view model may exist. Creating the view model then
     * must not report the run that's on screen right now as a crash (it did in 1.12.0–1.13.0, at every start).
     */
    @Test
    fun theRunOnScreenNowIsNeverReportedAsACrash() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val log = com.avidviewer.tv.data.CrashLog
        log.clear(app)
        log.inUse(app); log.note(app, "App on screen")
        Thread.sleep(300)
        val vm = com.avidviewer.tv.ui.AppViewModel(app)
        assertEquals(null, vm.lastCrash)
        assertEquals(null, log.read(app))
        log.leftNormally(app); log.clear(app)
    }
}
