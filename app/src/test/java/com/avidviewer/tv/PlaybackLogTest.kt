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

    /** Privacy (1.5.2): channels and titles are codes, the same each time on one TV, and old names are taken out. */
    @Test
    fun namesNeverReachTheLog() {
        val f = File(dir, "private.txt")
        f.writeText("1000\tSTALL\tESPN HD\tFroze 3 s waiting for data. Likely: unclear.\n")
        val log = PlaybackLog(f)
        assertEquals("Earlier stream", log.entries().single().what)
        assertFalse(f.readText().contains("ESPN"))
        val a = log.label("p1:123", live = true)
        assertTrue(a, Regex("^Live channel [0-9A-F]{4}$").matches(a))
        assertEquals(a, PlaybackLog(f).label("p1:123", live = true))
        assertTrue(log.label("p1:vod9", live = false).startsWith("Title "))
        assertFalse(log.text().contains("ESPN"))
    }

    /** Nov 15: when the app reconnects by itself. */
    @Test
    fun reconnectRules() {
        fun r(now: Long, pic: Long = 0, bytes: Long = now, buf: Long = 20_000, start: Long = 0, rec: Long = 0, ready: Boolean = true) =
            com.avidviewer.tv.ui.PlaybackMonitor.reconnectReason(now, start, rec, pic, bytes, buf, ready)
        assertEquals(null, r(60_000))
        // The picture still for 5 s (sound going on): reconnect; 4 s: not yet.
        assertTrue(r(60_000, pic = 55_000)!!.startsWith("the picture stood still for 5 s"))
        assertEquals(null, r(60_000, pic = 56_000))
        // No data for 4 s with under 8 s buffered: reconnect, before the screen freezes.
        assertTrue(r(60_000, bytes = 56_000, buf = 6_000)!!.startsWith("no data for 4 s"))
        // A lean but flowing channel (2 s buffered, data arriving) is left alone.
        assertEquals(null, r(60_000, bytes = 59_500, buf = 2_000))
        // Plenty buffered: a pause in the data isn't worth a reconnect yet.
        assertEquals(null, r(60_000, bytes = 50_000, buf = 15_000))
        // Not in the first 10 s of a channel, nor within 10 s of a reconnect, nor before it ever played.
        assertEquals(null, r(8_000, pic = 1_000))
        assertEquals(null, r(60_000, pic = 50_000, rec = 55_000))
        assertEquals(null, r(60_000, bytes = 50_000, buf = 1_000, ready = false))
    }

    @Test
    fun reconnectsAreCountedInTheSummary() {
        val log = PlaybackLog(File(dir, "recover.txt"))
        log.add(PlaybackLog.RECOVER, "Live channel 4F2A", "Reconnected: the picture stood still for 5 s.")
        log.add(PlaybackLog.RECOVER, "Live channel 4F2A", "Playing again 2 s after reconnecting (the picture stood still for 5 s).")
        assertTrue(log.summary(), log.summary().contains("1 reconnect"))
        assertTrue(log.unsent().any { it.kind == PlaybackLog.RECOVER })
    }

    @Test
    fun afterThreeReconnectsItKeepsTryingEvery30Seconds() {
        val m = com.avidviewer.tv.ui.PlaybackMonitor
        assertTrue(m.mayReconnect(100_000, listOf(10_000L, 50_000L)))
        // 3 in 3 minutes: not again straight away (BUG-058: it used to stop for good)…
        assertFalse(m.mayReconnect(100_000, listOf(10_000L, 50_000L, 90_000L)))
        // …but 30 s after the last one it tries again.
        assertTrue(m.mayReconnect(120_000, listOf(10_000L, 50_000L, 90_000L)))
    }

    @Test
    fun downloadErrorsAreNamedNeverByClassName() {
        // Release builds are obfuscated: an unknown exception's class name read as "t" (BUG-059).
        class T : java.io.IOException("x")
        val d: (java.io.IOException) -> String = { com.avidviewer.tv.ui.PlaybackMonitor.describe(it) }
        assertEquals("download failed", d(T()))
        assertEquals("secure connection failed (SSL)", d(java.io.IOException(javax.net.ssl.SSLHandshakeException("h"))))
        assertEquals("the connection ended early", d(java.io.EOFException()))
    }

    @Test
    fun aTrickleAndStutteringAreReasonsToReconnect() {
        fun r(now: Long, stalling: Long = 0, grew: Long = now, stops: Int = 0) =
            com.avidviewer.tv.ui.PlaybackMonitor.reconnectReason(now, 0, 0, 0, now, 5_000, true, stalling, grew, stops)
        // #23: waiting 6 s while a few bytes arrive (so "no data" never fires) but the buffer doesn't grow.
        assertTrue(r(60_000, stalling = 54_000, grew = 50_000)!!.startsWith("waiting 6 s with the buffer not growing"))
        assertEquals("still growing: not yet", null, r(60_000, stalling = 54_000, grew = 59_000))
        // #25: more than 5 stops in 30 s.
        assertTrue(r(60_000, stops = 6)!!.startsWith("stuttering"))
        assertEquals(null, r(60_000, stops = 5))
    }

    @Test
    fun manyShortStopsAreOneStutterEntry() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val log = PlaybackLog(File(dir, "stutter.txt"))
        var t = 1_000_000L
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(app).build()
        val m = PlaybackMonitor(player, androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(app).build(), log) { t }
        val ev = androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime(0, androidx.media3.common.Timeline.EMPTY, 0, null, 0, androidx.media3.common.Timeline.EMPTY, 0, null, 0, 0)
        m.started("p:ch1", live = true)
        m.onPlaybackStateChanged(ev, androidx.media3.common.Player.STATE_READY)
        // #25: 50 flips between waiting and playing, 250 ms apart (the log used to get 50 "Froze 0 s" entries).
        repeat(50) {
            t += 250; m.onPlaybackStateChanged(ev, androidx.media3.common.Player.STATE_BUFFERING)
            t += 100; m.onPlaybackStateChanged(ev, androidx.media3.common.Player.STATE_READY)
        }
        t += 5_000; m.sample()
        val stalls = log.entries().filter { it.kind == PlaybackLog.STALL }
        assertTrue("at most the first stop and one stutter entry: $stalls", stalls.size <= 2)
        assertTrue(stalls.any { it.text.startsWith("Stuttered") })
        player.release()
    }
}
