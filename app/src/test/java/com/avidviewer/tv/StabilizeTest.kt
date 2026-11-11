package com.avidviewer.tv

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.Playlist
import com.avidviewer.tv.data.Updater
import com.avidviewer.tv.data.Xtream
import com.avidviewer.tv.ui.AppViewModel
import com.avidviewer.tv.ui.FAV_GROUP
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.ServerSocket
import kotlin.concurrent.thread

/** The "Stabilize" changes from the menu audit (audit/MENU_AUDIT.md, work plan 1.1–1.4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StabilizeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val made = ArrayList<AppViewModel>()
    private fun newVm() = AppViewModel(app).also { made.add(it) }

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

    // ---- 1.1 the Xtream live list is read one channel at a time ----

    private fun serve(answers: Map<String, String>): ServerSocket {
        val s = ServerSocket(0)
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    c.use { conn ->
                        val r = conn.getInputStream().bufferedReader()
                        val line = r.readLine() ?: return@use
                        while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                        val action = Regex("action=([a-z_]+)").find(line)?.groupValues?.get(1) ?: ""
                        val b = (answers[action] ?: "[]").toByteArray()
                        conn.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(b); flush() }
                    }
                }
            }
        }
        return s
    }

    @Test
    fun xtreamLiveChannelsAreReadAsAStream() {
        val cats = """[{"category_id":"7","category_name":"News"},{"category_id":8,"category_name":"Sports"}]"""
        // Real providers mix numbers and strings, send null, leave fields out and add ones we don't use.
        val streams = """[
          {"num":1,"name":"  CNN HD ","stream_type":"live","stream_id":101,"stream_icon":"http://img/1.png","epg_channel_id":"cnn.us","category_id":"7","added":"1690000000","tv_archive":0,"direct_source":"","custom_sid":null},
          {"num":2,"name":"ESPN","stream_id":"102","stream_icon":"","epg_channel_id":null,"category_id":8,"extra":{"nested":[1,2,{"x":true}]}},
          {"num":3,"name":null,"stream_id":103,"category_id":"99"},
          {"num":4,"name":"No id"},
          "junk",
          {"num":5,"name":"Last","stream_id":105,"is_adult":"0","category_id":"7"}
        ]"""
        serve(mapOf("get_live_categories" to cats, "get_live_streams" to streams)).use { srv ->
            val p = Playlist("px", "Test", "xtream", server = "http://127.0.0.1:${srv.localPort}", username = "u", password = "p w")
            val chs = Xtream.liveChannels(p, "ts")
            assertEquals(listOf("px:101", "px:102", "px:103", "px:105"), chs.map { it.key })
            assertEquals("CNN HD", chs[0].name)
            assertEquals("News", chs[0].group)
            assertEquals("cnn.us", chs[0].epgId)
            assertEquals("http://img/1.png", chs[0].logo)
            assertEquals(null, chs[1].epgId) // JSON null is "no guide id", not the text "null"
            assertEquals(null, chs[1].logo)
            assertEquals("Sports", chs[1].group)
            assertEquals("Channel 103", chs[2].name)
            assertEquals("Uncategorized", chs[2].group)
            assertTrue(chs[0].url.endsWith("/live/u/p w/101.ts"))
        }
        // A provider that answers with an error object instead of a list: no channels, no crash.
        serve(mapOf("get_live_streams" to """{"user_info":{"auth":0}}""")).use { srv ->
            val p = Playlist("py", "Test", "xtream", server = "http://127.0.0.1:${srv.localPort}", username = "u", password = "p")
            assertTrue(Xtream.liveChannels(p, "ts").isEmpty())
        }
    }

    // ---- 1.2 other playlists: only their favorite channels stay in memory ----

    @Test
    fun otherPlaylistsKeepOnlyTheirFavoriteChannelsInMemory() {
        TestData.seed(app)
        val vm = newVm()
        idleUntil("channels") { vm.channels.isNotEmpty() }
        vm.toggleInList(FAV_GROUP, "${TestData.P2}:f3")
        vm.ensureFavoriteData()
        idleUntil("the other playlist's favorite") { vm.channel("${TestData.P2}:f3") != null }
        assertEquals("only the 1 favorite of 8 channels is held", 1, vm.otherLiveSizes()[TestData.P2])
        assertTrue(vm.channelsIn(FAV_GROUP).any { it.key == "${TestData.P2}:f3" })
        // Adding another of its channels refreshes that small set.
        vm.toggleInList(FAV_GROUP, "${TestData.P2}:f5")
        idleUntil("second favorite") { vm.channel("${TestData.P2}:f5") != null }
        assertEquals(2, vm.otherLiveSizes()[TestData.P2])
        // The guide's search box sees every playlist while searching, and lets go afterwards.
        vm.loadSearchPool()
        idleUntil("search pool") { vm.searchPool.isNotEmpty() }
        assertNotNull(vm.channel("${TestData.P2}:f1"))
        vm.dropSearchPool()
        assertEquals(null, vm.channel("${TestData.P2}:f1"))
        assertEquals(2, vm.otherLiveSizes()[TestData.P2])
    }

    // ---- 1.3 derived lists are computed once per change, and follow every change ----

    @Test
    fun derivedListsAreReusedUntilSomethingChanges() {
        TestData.seed(app)
        val vm = newVm()
        idleUntil("channels") { vm.channels.isNotEmpty() }
        val n1 = vm.numbers(); val g1 = vm.groups(); val k1 = vm.channelsIn("KIDS")
        assertSame("same numbers object while nothing changed", n1, vm.numbers())
        assertSame(g1, vm.groups())
        assertSame(k1, vm.channelsIn("KIDS"))
        // Hide a channel: lists, numbers and lookups follow at once.
        val hidden = k1[2].key
        vm.hideChannel(hidden)
        val k2 = vm.channelsIn("KIDS")
        assertNotSame(k1, k2)
        assertFalse(k2.any { it.key == hidden })
        assertFalse(vm.numbers().containsKey(hidden))
        // Re-order: numbers follow the new order.
        val last = k2.last().key
        vm.moveChannelTo(last, 0)
        assertEquals(last, vm.channelsIn("KIDS").first().key)
        assertTrue(vm.numbers()[last]!! < vm.numbers()[k2.first().key]!!)
        // Rename a category: groups follow.
        vm.renameGroup("KIDS", "Children")
        assertEquals("Children", vm.groups().first { it.raw == "KIDS" }.name)
        // Lookups by key.
        assertEquals(k2.first().key, vm.channel(k2.first().key)?.key)
        assertEquals(null, vm.channel("nope:1"))
    }

    @Test
    fun movieFiltersFollowHidingAndCategoryChanges() {
        TestData.seed(app)
        val vm = newVm()
        vm.ensureVod()
        idleUntil("movies") { vm.vod.isNotEmpty() }
        val all = vm.vodOf("movie")
        assertSame(all, vm.vodOf("movie"))
        val first = all.first()
        vm.setVodTitlesHidden(listOf(first.key), true)
        assertFalse(vm.vodOf("movie").any { it.key == first.key })
        val cat = vm.vodCategories("movie").first().first
        vm.chooseVodCategory("movie", cat)
        assertTrue(vm.vodItems("movie").all { it.category.ifBlank { "Other" } == cat })
        vm.chooseVodCategory("movie", "All")
        assertEquals(vm.vodOf("movie").size, vm.vodItems("movie").size)
    }

    // ---- 1.4 the update dialog never opens in the middle of browsing ----

    @Test
    fun updateDialogOnlyOpensBeforeTheFirstButtonPress() {
        TestData.seed(app)
        val vm = newVm()
        vm.update = Updater.Release(999_999, "9.9.9", "http://x/a.apk", "")
        vm.lastKeyAt = 0L
        vm.onUpdateFound()
        assertTrue("found at launch: the dialog may open", vm.updatePromptAllowed)
        vm.lastKeyAt = 12_345L
        vm.onUpdateFound()
        assertFalse("found while browsing: wait (menu badge and Settings have it)", vm.updatePromptAllowed)
    }
}
