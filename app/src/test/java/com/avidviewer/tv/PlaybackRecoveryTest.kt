package com.avidviewer.tv

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.CrashLog
import com.avidviewer.tv.data.Decoders
import com.avidviewer.tv.data.PlaybackLog
import com.avidviewer.tv.data.Store
import com.avidviewer.tv.ui.AppViewModel
import com.avidviewer.tv.ui.PlaybackMonitor
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 1.21.1: decoder trouble (#30, #31, #32), 0-second freezes (#34), hangs (#20, #33). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaybackRecoveryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val made = ArrayList<AppViewModel>()

    @After
    fun stop() {
        made.forEach { it.viewModelScope.cancel() }
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun idleUntil(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!ok()) {
            if (System.currentTimeMillis() > until) throw AssertionError("Timed out waiting for: $what")
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(20)
        }
    }

    @Test
    fun aDecodingErrorRestartsTheChannelOnTheOtherDecoderOnce() {
        TestData.seed(app)
        val vm = AppViewModel(app).also { made.add(it) }
        idleUntil("a channel") { vm.currentKey != null }
        val key = vm.currentKey!!
        assertFalse(vm.decoders.usesOther(key))
        val err = PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        // #32: the first decoding error restarts the stream on the other (software) decoder instead of stopping.
        vm.playerFailedForTests(err)
        assertTrue("switched to the other decoder", vm.decoders.usesOther(key))
        assertTrue(vm.playbackLog.entries().any { it.kind == PlaybackLog.RECOVER && it.text.startsWith("Restarted after a decoding error") })
        // The other decoder fails too: the error shows, and the stream goes back to the decoder it began with.
        vm.playerFailedForTests(err)
        assertNotNull(vm.playerError)
        assertTrue(vm.playerError!!, vm.playerError!!.contains("DECODING_FAILED"))
        assertFalse(vm.decoders.usesOther(key))
    }

    @Test
    fun theOtherDecoderIsRememberedPerStream() {
        var t = 1_000_000L
        val d = Decoders(null) { t }
        assertFalse(d.usesOther("p:a"))
        assertTrue(d.switch("p:a"))
        d.startingNow("p:a"); assertTrue(d.softwareFirst)
        d.startingNow("p:b"); assertFalse("another channel keeps the TV's decoder", d.softwareFirst)
        t += Decoders.KEEP_MS + 1
        assertFalse("forgotten after a week", d.usesOther("p:a"))
        assertTrue(Decoders.isSoftware("c2.android.avc.decoder"))
        assertTrue(Decoders.isSoftware("OMX.google.h264.decoder"))
        assertFalse(Decoders.isSoftware("OMX.MTK.VIDEO.DECODER.AVC"))
        assertTrue(Decoders.isDecoderError(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertFalse(Decoders.isDecoderError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
    }

    @Test
    fun onlyStreamsSoftwareCanDecodeAreSwitchedForAFrozenPicture() {
        assertTrue(Decoders.softwareCanKeepUp(1280, 720, 60f, "video/avc"))
        assertTrue(Decoders.softwareCanKeepUp(1920, 1080, 30f, "video/avc"))
        assertTrue(Decoders.softwareCanKeepUp(720, 576, 25f, "video/mpeg2"))
        assertFalse("1080p60 is too much", Decoders.softwareCanKeepUp(1920, 1080, 60f, "video/avc"))
        assertFalse("4K HEVC", Decoders.softwareCanKeepUp(3840, 2160, 50f, "video/hevc"))
        assertFalse(Decoders.softwareCanKeepUp(0, 0, 0f, "video/avc"))
    }

    @Test
    fun aFullBufferIsTheDecoderNotTheData() {
        fun r(stalling: Long = 0, grew: Long = 60_000, stops: Int = 0, buf: Long) =
            PlaybackMonitor.reconnectReason(60_000, 0, 0, 0, 60_000, buf, true, stalling, grew, stops)
        // #31: 50 s buffered and the player stops every few seconds: the decoder falling behind, not "stuttering".
        assertTrue(r(stops = 6, buf = 50_000)!!.startsWith(PlaybackMonitor.DECODER_BEHIND))
        assertTrue(r(stops = 6, buf = 5_000)!!.startsWith("stuttering"))
        // A full buffer doesn't grow because it's full: not a reason to reconnect.
        assertNull(r(stalling = 54_000, grew = 50_000, buf = 50_000))
        assertTrue(r(stalling = 54_000, grew = 50_000, buf = 5_000)!!.startsWith("waiting 6 s with the buffer not growing"))
        // The log names the decoder, not "unclear".
        val why = PlaybackMonitor.cause(emptyList(), 52_000, 0, 0, 120)
        assertTrue(why, why.startsWith("the TV's video decoder fell behind, not the download (52 s was buffered, no download errors, 120 frames dropped)"))
        assertTrue(PlaybackMonitor.cause(emptyList(), 9_000, 0, 0).startsWith("unclear"))
    }

    @Test
    fun aStillPictureIsRetriedEvery15SecondsOnceReconnectsPileUp() {
        val recent = listOf(10_000L, 50_000L, 90_000L)
        assertFalse(PlaybackMonitor.mayReconnect(100_000, recent, PlaybackMonitor.SLOW_PICTURE_MS))
        assertTrue(PlaybackMonitor.mayReconnect(105_000, recent, PlaybackMonitor.SLOW_PICTURE_MS))
        assertFalse("other reasons still wait 30 s", PlaybackMonitor.mayReconnect(105_000, recent))
    }

    @Test
    fun aStopUnderASecondIsNeverAFreezeOnItsOwn() {
        val log = PlaybackLog(java.io.File(app.filesDir, "blips.txt").apply { delete() })
        var t = 1_000_000L
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(app).build()
        val m = PlaybackMonitor(player, androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(app).build(), log) { t }
        val ev = androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime(0, androidx.media3.common.Timeline.EMPTY, 0, null, 0, androidx.media3.common.Timeline.EMPTY, 0, null, 0, 0)
        m.started("p:ch1", live = true)
        m.onPlaybackStateChanged(ev, androidx.media3.common.Player.STATE_READY)
        // #34: single 0-second stops, 5 s apart (each used to be a "Froze 0 s" entry and a freeze in the summary).
        repeat(10) {
            t += 5_000; m.onPlaybackStateChanged(ev, androidx.media3.common.Player.STATE_BUFFERING)
            t += 200; m.onPlaybackStateChanged(ev, androidx.media3.common.Player.STATE_READY)
            m.sample()
        }
        t += 5_000; m.sample()
        assertEquals(emptyList<PlaybackLog.Entry>(), log.entries().filter { it.kind == PlaybackLog.STALL })
        // A real freeze is still logged.
        m.onPlaybackStateChanged(ev, androidx.media3.common.Player.STATE_BUFFERING)
        t += 3_000; m.onPlaybackStateChanged(ev, androidx.media3.common.Player.STATE_READY)
        assertTrue(log.entries().any { it.kind == PlaybackLog.STALL && it.text.startsWith("Froze 3 s") })
        player.release()
    }

    @Test
    fun aBlockedScreenThreadSaysWhereItWasStuck() {
        val stack = arrayOf(
            StackTraceElement("java.util.ArrayList", "contains", "ArrayList.java", 10),
            StackTraceElement("kotlin.collections.CollectionsKt", "filter", "C.kt", 2),
            StackTraceElement("androidx.compose.runtime.Recomposer", "run", "R.kt", 3),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 4),
            StackTraceElement("androidx.compose.ui.Foo", "bar", "F.kt", 5),
            StackTraceElement("com.avidviewer.tv.ui.AppViewModel", "computeNumbers", "AppViewModel.kt", 2440),
            StackTraceElement("a3", "b", "SourceFile", 7),
        )
        val line = CrashLog.stackLine(stack)
        assertTrue(line, line.startsWith("ArrayList.contains:10 < CollectionsKt.filter:2"))
        assertTrue(line, line.contains("… < AppViewModel.computeNumbers:2440 < a3.b:7"))
        assertFalse(line, line.contains("Foo.bar"))
        // #33: Android 11's ANR record: the "main" thread's stack.
        val trace = "----- pid 123 -----\n\"Signal Catcher\" daemon\n  at x\n\n\"main\" prio=5 tid=1 Native\n  at a.b(c:1)\n  at d.e(f:2)\n\n\"other\"\n  at z\n"
        assertEquals("  \"main\" prio=5 tid=1 Native\n  at a.b(c:1)\n  at d.e(f:2)", CrashLog.anrMainThread(trace))
        assertNull(CrashLog.anrMainThread("nothing here"))
    }

    @Test
    fun settingsSavedInARowAreAllOnDiskForTheNextRead() {
        TestData.seed(app)
        val store = Store(app)
        val base = store.loadPrefs()
        // #20: saves are queued off the screen's thread; a read (here by another Store) waits for them.
        repeat(30) { i -> store.savePrefs(base.copy(lastChannel = "${TestData.P1}:c1-$i")) }
        assertEquals("${TestData.P1}:c1-29", Store(app).loadPrefs().lastChannel)
    }

    // ---- Nov 22, 2026: from the 1.27.0 playback logs (Cube and Fire Stick 4K Max) ----

    @Test
    fun noDataIsOnlyAReasonWhenTheBufferRunsDown() {
        fun why(buf: Long, drained: Long) = PlaybackMonitor.reconnectReason(60_000, 0, 0, 0, 40_000, buf, true, drainedMs = drained)
        assertEquals("a buffer steady at 7 s for 20 s isn't being played: not the data", null, why(7_000, 0))
        assertEquals(null, why(7_000, 1_500))
        assertTrue("a buffer running down with no data: reconnect", why(3_000, 5_000)!!.startsWith("no data for 20 s"))
    }

    @Test
    fun theSoftwareDecoderIsOnlyChosenWhenItCanKeepUp() {
        assertTrue(Decoders.softwareCanKeepUp(1280, 720, 30f, "video/avc"))
        assertTrue(Decoders.softwareCanKeepUp(1920, 1080, 30f, "video/avc"))
        assertFalse("1080p at 60 fps: the Cube's software decoder dropped nearly every frame", Decoders.softwareCanKeepUp(1920, 1080, 60f, "video/avc"))
        assertFalse("HEVC: never", Decoders.softwareCanKeepUp(1280, 720, 30f, "video/hevc"))
    }

    @Test
    fun aDashChannelGetsADashSourceInsteadOfCrashing() {
        // AVR-efddbf19: a .mpd channel crashed the app (ClassNotFoundException: DashMediaSource\$Factory).
        val f = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(app)
        val src = f.createMediaSource(androidx.media3.common.MediaItem.Builder().setUri("http://127.0.0.1:9/live/manifest.mpd").setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD).build())
        assertTrue(src.javaClass.name, src.javaClass.name.contains("DashMediaSource"))
    }
}
