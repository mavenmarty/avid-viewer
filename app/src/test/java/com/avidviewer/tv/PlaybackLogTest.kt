package com.avidviewer.tv

import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.CrashReport
import com.avidviewer.tv.data.PlaybackLog
import com.avidviewer.tv.ui.PlaybackMonitor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The playback log (1.5.0): kept on the TV, causes named, sent through the crash relay with addresses removed. */
@RunWith(RobolectricTestRunner::class)
class PlaybackLogTest {
    private val dir = File(ApplicationProvider.getApplicationContext<android.app.Application>().cacheDir, "pl-test").apply { deleteRecursively(); mkdirs() }

    @Test
    fun entriesAreKeptAcrossRestartsAndTrimmed() {
        var now = 1_000_000L
        val f = File(dir, "log.txt")
        val log = PlaybackLog(f, max = 5) { now }
        repeat(8) { log.add(PlaybackLog.STALL, "ESPN HD", "Froze ${it + 1} s waiting for data. Likely: the provider (HTTP 503 ×1)."); now += 1000 }
        assertEquals(5, log.entries().size)
        val again = PlaybackLog(f, max = 5) { now }
        assertEquals(5, again.entries().size)
        assertEquals("Froze 8 s waiting for data. Likely: the provider (HTTP 503 ×1).", again.entries().last().text)
        assertTrue(again.summary(), again.summary().startsWith("Playback log: 5 freezes (longest 8 s)"))
        assertTrue(again.text(), again.text().contains("Likely causes: the provider (HTTP 503 ×1) ×5"))
    }

    @Test
    fun sentProblemsAreNotSentAgain() {
        var now = 5_000L
        val f = File(dir, "sent.txt")
        val log = PlaybackLog(f) { now }
        log.add(PlaybackLog.START, "News", "live channel")
        log.add(PlaybackLog.STALL, "News", "Froze 3 s waiting for data. Likely: unclear.")
        assertEquals(1, log.unsent().size)
        log.markSent(log.entries().last().time, auto = true)
        assertEquals(0, PlaybackLog(f) { now }.unsent().size)
        now += 10
        log.add(PlaybackLog.ERROR, "News", "Player stopped: ERROR_CODE_IO_BAD_HTTP_STATUS.")
        assertEquals(1, log.unsent().size)
        assertEquals(5_000L, PlaybackLog(f) { now }.lastAutoSend)
    }

    @Test
    fun causesAreNamed() {
        assertTrue(PlaybackMonitor.cause(listOf("provider answered HTTP 503", "provider answered HTTP 503"), 4000, 20_000, 6_000).startsWith("the provider (HTTP 503 ×2)"))
        assertTrue(PlaybackMonitor.cause(listOf("connection timed out"), 4000, 0, 0).startsWith("the network or the provider's server (connection timed out ×1)"))
        assertEquals("the network or provider is too slow (getting 3.1 Mb/s, the stream needs 8.0 Mb/s)", PlaybackMonitor.cause(emptyList(), 500, 3_100, 8_000))
        assertTrue(PlaybackMonitor.cause(emptyList(), 200, 0, 0).startsWith("data stopped arriving"))
        assertTrue(PlaybackMonitor.cause(emptyList(), 9_000, 0, 0).startsWith("unclear"))
    }

    @Test
    fun theLogIsSentWithoutAddressesAndMarkedAsAPlaybackLog() {
        val text = "Nov 13 18:40:01  LOAD     ESPN HD  ·  http://my.provider.tv:8080/live/bob/hunter2/123.ts timed out\n"
        val p = CrashReport.logPayload("1.5.0", "Playback log: 1 freeze", text)
        assertEquals("playback", p.getString("kind"))
        assertEquals("avid-viewer", p.getString("app"))
        val crash = p.getString("crash")
        for (s in listOf("my.provider.tv", "hunter2", "bob")) assertFalse(crash, crash.contains(s))
        assertTrue(crash.contains("ESPN HD"))
        FakeRelay().use { relay ->
            assertTrue(CrashReport.sendLog("1.5.0", "Playback log: 1 freeze", text, relay.url))
            assertEquals("playback", JSONObject(relay.posts.single()).getString("kind"))
        }
        assertFalse(CrashReport.sendLog("1.5.0", "x", text, relay = ""))
    }
}
