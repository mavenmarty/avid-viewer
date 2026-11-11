package com.avidviewer.tv

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.GuideChannel
import com.avidviewer.tv.data.Store
import com.avidviewer.tv.ui.AppViewModel
import com.avidviewer.tv.ui.suggestMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/** Guide sources per playlist, the first source winning per channel, time offsets, matching by hand, refresh setting. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GuideSourcesTest {

    private val made = ArrayList<AppViewModel>()
    private fun track(vm: AppViewModel) = vm.also { made.add(it) }

    /** Stops each test's background work (downloads, timers) so nothing runs into the next test. */
    @org.junit.After
    fun stopBackgroundWork() {
        made.forEach { vm -> vm.viewModelScope.cancel() }
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val hour = 3600_000L
    private val base = (System.currentTimeMillis() / hour) * hour

    private fun prog(ch: String, title: String, startH: Int) =
        """<programme channel="$ch" start="${fmt.format(base + startH * hour)}" stop="${fmt.format(base + (startH + 1) * hour)}"><title>$title</title></programme>"""

    private val guideA = """<?xml version="1.0"?><tv>
        <channel id="epgbig1"><display-name>BIG One</display-name></channel>
        ${prog("epgbig1", "From A", 0)}
        </tv>"""
    private val guideB = """<?xml version="1.0"?><tv>
        <channel id="epgbig1"><display-name>BIG One</display-name></channel>
        <channel id="epgbig2"><display-name>BIG Two</display-name></channel>
        <channel id="extra.us"><display-name>Extra One</display-name></channel>
        ${prog("epgbig1", "From B", 0)}
        ${prog("epgbig2", "From B two", 0)}
        ${prog("extra.us", "Extra Show", 0)}
        </tv>"""

    private fun serve(): ServerSocket {
        val s = ServerSocket(0)
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    c.use { conn ->
                        val r = conn.getInputStream().bufferedReader()
                        val line = r.readLine() ?: return@use
                        while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                        val body = (if (line.contains("/b.xml")) guideB else guideA).toByteArray()
                        conn.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    }
                }
            }
        }
        return s
    }

    private fun idleUntil(what: String, timeoutMs: Long = 30_000, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!ok()) {
            if (System.currentTimeMillis() > until) throw AssertionError("Timed out waiting for: $what")
            // Move the test clock too, so short waits in the app (like the 2.5 s before re-downloading) pass.
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(25)
        }
    }

    @Test
    fun sourcesMergeInOrderWithOffsetsAndHandMatches() {
        TestData.seed(app)
        val vm = track(AppViewModel(app))
        idleUntil("channels") { vm.channels.isNotEmpty() }
        val p1 = vm.playlists.first { it.id == TestData.P1 }
        // An older single guide address becomes a source and keeps replacing the provider's guide, as before.
        assertFalse(p1.providerGuide)
        assertEquals(1, p1.epgSources.size)
        serve().use { srv ->
            val root = "http://127.0.0.1:${srv.localPort}"
            vm.removeEpgSource(TestData.P1, p1.epgSources.first().id)
            vm.addEpgSource(TestData.P1, "Source A", "$root/a.xml", 0)
            vm.addEpgSource(TestData.P1, "Source B", "$root/b.xml", 2)
            val pl = { vm.playlists.first { it.id == TestData.P1 } }
            assertEquals(listOf("Source A", "Source B"), pl().epgSources.map { it.name })
            idleUntil("both sources downloaded (${vm.guideSourceStatus})") {
                vm.guideBusy == null && pl().epgSources.all { s -> vm.guideSourceStatus[TestData.P1 + "|" + s.id]?.startsWith("Updated") == true } &&
                    vm.programs(vm.channel("${TestData.P1}:big2")).isNotEmpty()
            }
            val big1 = vm.programs(vm.channel("${TestData.P1}:big1"))
            assertEquals("the first source wins", "From A", big1.single().title)
            val big2 = vm.programs(vm.channel("${TestData.P1}:big2")).single()
            assertEquals("From B two", big2.title)
            assertEquals("Source B's +2 h offset", base + 2 * hour, big2.start)

            // Match a channel by hand to a guide channel the provider never linked.
            val list = kotlinx.coroutines.runBlocking { vm.guideChannels(TestData.P1) }
            assertTrue(list.any { it.id == "extra.us" && it.name == "Extra One" })
            assertEquals("extra.us", suggestMatches("Extra One HD", list).first().id)
            vm.setEpgMatch("${TestData.P1}:big3", "extra.us")
            idleUntil("hand match downloaded") { vm.programs(vm.channel("${TestData.P1}:big3")).firstOrNull()?.title == "Extra Show" }
            // "No guide", then back to the provider's match.
            vm.setEpgMatch("${TestData.P1}:big1", "")
            assertTrue(vm.programs(vm.channel("${TestData.P1}:big1")).isEmpty())
            vm.setEpgMatch("${TestData.P1}:big1", null)
            assertEquals("From A", vm.programs(vm.channel("${TestData.P1}:big1")).single().title)

            // Turning a source off and deleting one.
            val b = pl().epgSources.first { it.name == "Source B" }
            vm.setEpgSourceEnabled(TestData.P1, b.id, false)
            assertFalse(pl().epgSources.first { it.id == b.id }.enabled)
            vm.removeEpgSource(TestData.P1, b.id)
            assertEquals(listOf("Source A"), pl().epgSources.map { it.name })
        }
        // Saved with the playlist.
        val saved = Store(app).loadPlaylists().first { it.id == TestData.P1 }
        assertEquals(listOf("Source A"), saved.epgSources.map { it.name })
        assertEquals("extra.us", Store(app).loadPrefs().epgMap["${TestData.P1}:big3"])
    }

    @Test
    fun refreshEveryIsSavedAndUsed() {
        TestData.seed(app)
        val vm = track(AppViewModel(app))
        assertEquals(8, vm.prefs.guideRefreshHours)
        vm.setGuideRefreshHours(4)
        assertEquals(4 * hour, vm.guideMs())
        vm.setGuideRefreshHours(5) // not offered: ignored
        assertEquals(4, Store(app).loadPrefs().guideRefreshHours)
    }

    @Test
    fun suggestionsPreferTheSameName() {
        val list = listOf(GuideChannel("a", "ESPN 2", "s"), GuideChannel("b", "ESPN", "s"), GuideChannel("c", "CNN", "s"), GuideChannel("d", "ESPN News", "s"))
        val got = suggestMatches("US: ESPN HD", list).map { it.id }
        assertEquals("b", got.first())
        assertFalse(got.contains("c"))
    }
}
