package com.avidviewer.tv

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.AccountApi
import com.avidviewer.tv.data.Playlist
import com.avidviewer.tv.data.Store
import com.avidviewer.tv.data.Xtream
import com.avidviewer.tv.ui.AppViewModel
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Nov 21, 2026: categories nobody sees aren't downloaded (Xtream, one category at a time), each playlist's last
 * load is reported to the website, and Strong 8K lines are checked and renewed through the website.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Strong8kTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val made = ArrayList<AppViewModel>()
    private fun newVm() = AppViewModel(app).also { made.add(it) }

    @After
    fun stop() {
        made.forEach { it.viewModelScope.cancel() }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(1500); shadowOf(android.os.Looper.getMainLooper()).idle()
        val seen = synchronized(RecordingHandler.seen) { RecordingHandler.seen.toList().also { RecordingHandler.seen.clear() } }
        if (seen.isNotEmpty()) throw AssertionError("uncaught: " + seen.joinToString("\n----\n") { it.stackTraceToString().take(3000) })
    }

    private fun idleUntil(what: String, ms: Long = 20_000, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > until) throw AssertionError("Timed out waiting for: $what")
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            Thread.sleep(10)
        }
    }

    /** TestData's safe defaults (no real ESPN, no update checks, fast retries), with only these playlists. */
    private fun setUp(vararg playlists: Playlist) {
        TestData.seed(app)
        val store = Store(app)
        store.savePlaylists(playlists.toList())
        store.savePrefs(com.avidviewer.tv.data.Prefs(activePlaylist = playlists.first().id, autoUpdateCheck = false))
    }

    /** An Xtream provider: News (7), Adult (8), Sports (9). Records each request's query. */
    private class Provider : AutoCloseable {
        val s = ServerSocket(0)
        val requests = java.util.Collections.synchronizedList(ArrayList<String>())
        private val streams = mapOf(
            "7" to """{"name":"News 1","stream_id":1,"category_id":"7"},{"name":"News 2","stream_id":2,"category_id":"7"}""",
            "8" to """{"name":"A 1","stream_id":3,"category_id":"8"},{"name":"A 2","stream_id":4,"category_id":"8"},{"name":"A 3","stream_id":5,"category_id":"8"}""",
            "9" to """{"name":"S 1","stream_id":6,"category_id":"9"},{"name":"S 2","stream_id":7,"category_id":"9"}""",
        )
        init {
            thread(isDaemon = true) {
                while (!s.isClosed) {
                    val c = runCatching { s.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        c.use { conn ->
                            val r = conn.getInputStream().bufferedReader()
                            val line = r.readLine() ?: return@use
                            while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                            val q = line.substringAfter("?", "").substringBefore(" ")
                            val action = Regex("action=([a-z_]+)").find(q)?.groupValues?.get(1)
                            val cat = Regex("category_id=([0-9]+)").find(q)?.groupValues?.get(1)
                            requests += (action ?: "login") + (cat?.let { ":$it" } ?: "")
                            val body = when (action) {
                                null -> """{"user_info":{"auth":1,"status":"Active"}}"""
                                "get_live_categories" -> """[{"category_id":"7","category_name":"News"},{"category_id":"8","category_name":"Adult"},{"category_id":"9","category_name":"Sports"}]"""
                                "get_live_streams" -> "[" + (if (cat != null) streams[cat] ?: "" else streams.values.joinToString(",")) + "]"
                                else -> "[]"
                            }.toByteArray()
                            conn.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
                        }
                    }
                }
            }
        }
        val base get() = "http://127.0.0.1:${s.localPort}"
        override fun close() = s.close()
    }

    @Test
    fun xtreamDownloadsOnlyTheCategoriesLeft() {
        Provider().use { prov ->
            val p = Playlist("px", "Test", "xtream", server = prov.base, username = "u", password = "p")
            val skipped = HashSet<String>()
            val chs = ArrayList<com.avidviewer.tv.data.Channel>()
            Xtream.liveChannels(p, "ts", setOf("Adult", "Sports"), skipped) { chs.add(it) }
            assertEquals(listOf("px:1", "px:2"), chs.map { it.key })
            assertEquals(setOf("Adult", "Sports"), skipped)
            assertEquals(listOf("get_live_categories", "get_live_streams:7"), prov.requests.toList())
            // Nothing to skip: one download of everything, as before.
            prov.requests.clear()
            assertEquals(7, Xtream.liveChannels(p, "ts").size)
            assertEquals(listOf("get_live_categories", "get_live_streams"), prov.requests.toList())
        }
    }

    @Test
    fun hiddenCategoriesAreLeftOutAndComeBackWhenShown() {
        Provider().use { prov ->
            setUp(Playlist("px", "Provider", "xtream", server = prov.base, username = "u", password = "p", vodOn = false))
            val vm = newVm()
            idleUntil("first download (everything)") { vm.channels.size == 7 }
            assertTrue(prov.requests.contains("get_live_streams"))
            vm.setGroupsHidden("px", listOf("Adult", "Sports"), true)
            prov.requests.clear()
            vm.refreshPlaylist("px")
            idleUntil("downloaded again without the hidden categories") { vm.skippedGroups["px"] == setOf("Adult", "Sports") && vm.channels.size == 2 }
            assertEquals(listOf("get_live_categories", "get_live_streams:7"), prov.requests.filter { it.startsWith("get_live") })
            assertEquals(2, vm.channels.size)
            // Still listed (with no channels), so they can be shown again.
            val all = vm.groups(includeHidden = true)
            assertTrue(all.any { it.raw == "Adult" && it.hidden && it.count == 0 })
            // Showing one downloads it.
            prov.requests.clear()
            vm.setGroupsHidden("px", listOf("Adult"), false)
            idleUntil("Adult downloaded") { vm.channels.any { it.group == "Adult" } }
            assertEquals(setOf("Sports"), vm.skippedGroups["px"])
            assertFalse(prov.requests.contains("get_live_streams:9"))
        }
    }

    @Test
    fun strong8kLinesAreCheckedAndRenewedThroughTheWebsite() {
        Provider().use { prov ->
            FakeAvidSite().use { site ->
                setUp(
                    Playlist("px", "Strong", "xtream", server = prov.base, username = "alice", password = "pw1", vodOn = false),
                    Playlist("po", "Other", "m3u", m3uUrl = "http://other.example/get.php?username=bob&password=pw2", vodOn = false, enabled = false),
                )
                site.strong8kHosts = listOf("127.0.0.1")
                val vm = newVm()
                idleUntil("channels") { vm.channels.isNotEmpty() }
                vm.accountApi = site.base
                vm.account.save(AccountApi.Linked("TVTOKEN_abcdefghijklmnopqrstuvwxyz", "viewer@example.com", "Living room", "d1"))
                vm.checkAccount()
                idleUntil("Strong 8K playlist checked") { site.strong8kChecks.isNotEmpty() }
                val sent = site.strong8kChecks[0].getJSONArray("playlists")
                assertEquals(1, sent.length()) // only the playlist on the Strong 8K address; the other login never leaves
                assertEquals("px", sent.getJSONObject(0).getString("localId"))
                assertEquals("alice", sent.getJSONObject(0).getString("username"))
                // Checked again only after 12 hours, or when asked on the website.
                vm.checkAccount(); idleUntil("check-in") { site.calls.count { it.contains("/api/tv/me") } >= 2 }
                shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
                assertEquals(1, site.strong8kChecks.size)
                site.checkSubscription = true
                vm.checkAccount()
                idleUntil("checked when asked") { site.strong8kChecks.size == 2 }
                // A renewal the owner asked for on the website.
                site.renew = listOf("r1" to "px")
                vm.checkAccount()
                idleUntil("renewed") { site.strong8kRenews.isNotEmpty() }
                assertEquals("r1", site.strong8kRenews[0].getString("id"))
                assertEquals("pw1", site.strong8kRenews[0].getString("password"))
                // Reload channels now: the playlist downloads again and its load is reported (count, no names).
                prov.requests.clear()
                site.reloadChannels = true
                vm.checkAccount()
                idleUntil("downloaded again") { prov.requests.any { it.startsWith("get_live_streams") } }
                idleUntil("load reported", 40_000) { site.loadStatuses.any { it.getJSONArray("playlists").length() > 0 } }
                val last = site.loadStatuses.last().getJSONArray("playlists")
                val px = (0 until last.length()).map { last.getJSONObject(it) }.first { it.getString("localId") == "px" }
                assertTrue(px.getBoolean("ok")); assertEquals(7, px.getInt("channels"))
                assertFalse(site.loadStatuses.last().toString().contains("News 1"))
                assertFalse(site.loadStatuses.last().toString().contains("pw1"))
            }
        }
    }
}
