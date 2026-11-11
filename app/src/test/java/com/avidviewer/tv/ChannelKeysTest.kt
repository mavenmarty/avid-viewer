package com.avidviewer.tv

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.Backup
import com.avidviewer.tv.data.ChannelKeys
import com.avidviewer.tv.data.M3u
import com.avidviewer.tv.data.Playlist
import com.avidviewer.tv.ui.AppViewModel
import com.avidviewer.tv.ui.FAV_GROUP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Reported: after restoring a backup, many favorites were one channel off. M3U channels were named by
 * their position in the file, so one channel added by the provider shifted every favorite after it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChannelKeysTest {

    private val made = ArrayList<AppViewModel>()
    private fun track(vm: AppViewModel) = vm.also { made.add(it) }

    /** Stops each test's background work (downloads, timers) so nothing runs into the next test. */
    @org.junit.After
    fun stopBackgroundWork() {
        made.forEach { vm -> vm.viewModelScope.cancel() }
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun m3u(names: List<String>, root: String) = "#EXTM3U\n" + names.joinToString("\n") { n ->
        val id = n.filter { it.isDigit() }.ifEmpty { n.length.toString() }
        "#EXTINF:-1 tvg-id=\"${n.lowercase()}.us\" group-title=\"US\",$n\n$root/live/u/p/${id}.ts"
    } + "\n#EXTINF:-1 group-title=\"Movies\",Some Movie\n$root/movie/u/p/9001.mkv\n"

    /** Serves whatever [body] returns at the time of each request. */
    private fun serve(body: () -> String): ServerSocket {
        val s = ServerSocket(0)
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    c.use { conn ->
                        val r = conn.getInputStream().bufferedReader()
                        r.readLine() ?: return@use
                        while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                        val b = body().toByteArray()
                        conn.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(b); flush() }
                    }
                }
            }
        }
        return s
    }

    private fun idleUntil(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 40_000
        while (!ok()) {
            if (System.currentTimeMillis() > until) throw AssertionError("Timed out waiting for: $what")
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(25)
        }
    }

    @Test
    fun keysFollowTheStreamNotThePosition() {
        assertEquals(ChannelKeys.token("http://h:80/live/u/p/123.ts"), ChannelKeys.token("http://h:80/live/u/NEWPASS/123.ts"))
        assertNotEquals(ChannelKeys.token("http://h/live/u/p/123.ts"), ChannelKeys.token("http://h/live/u/p/124.ts"))
        var names = listOf("Channel 101", "Channel 102", "Channel 103")
        serve { m3u(names, "http://x") }.use { srv ->
            val p = Playlist("pk", "Test", "m3u", m3uUrl = "http://127.0.0.1:${srv.localPort}/list.m3u")
            val before = M3u.channels(p).associate { it.name to it.key }
            names = listOf("Channel 100", "Channel 101", "Channel 102", "Channel 103") // the provider adds one at the top
            val after = M3u.channels(p).associate { it.name to it.key }
            listOf("Channel 101", "Channel 102", "Channel 103").forEach { assertEquals("$it keeps its key", before[it], after[it]) }
        }
    }

    @Test
    fun choicesSavedUnderOldPositionKeysMoveToTheRightChannels() {
        // Older versions: "pk:0", "pk:1", … by position. The list then gained a channel at the top.
        val old = listOf("A", "B", "C").mapIndexed { i, n -> ChannelKeys.Ref("pk:$i", ChannelKeys.token("http://h/live/u/p/${i + 1}.ts"), n, "US", null) }
        val new = listOf("New", "A", "B", "C").mapIndexed { i, n -> ChannelKeys.Ref("pk:u" + ChannelKeys.token("http://h/live/u/p/$i.ts"), ChannelKeys.token("http://h/live/u/p/$i.ts"), n, "US", null) }
        val m = ChannelKeys.remap(old, new)
        assertEquals(new[1].key, m["pk:0"]) // A stays A
        assertEquals(new[3].key, m["pk:2"]) // C stays C
    }

    @Test
    fun updatingFromPositionKeysKeepsFavoritesWhenTheListAlsoChanged() {
        var names = listOf("Channel 100", "Channel 101", "Channel 102", "Channel 103")
        serve { m3u(names, "http://stream.example") }.use { srv ->
            val url = "http://127.0.0.1:${srv.localPort}/list.m3u"
            // An existing install from before this fix: position keys "old:0".."old:2" for 101..103, favorites 102 and 103.
            val store = com.avidviewer.tv.data.Store(app)
            store.savePlaylists(listOf(Playlist("old", "Provider", "m3u", m3uUrl = url)))
            store.saveChannels("old", listOf("Channel 101", "Channel 102", "Channel 103").mapIndexed { i, n ->
                com.avidviewer.tv.data.Channel("old:$i", n, "US", null, "http://stream.example/live/u/p/${n.filter { it.isDigit() }}.ts", n.lowercase() + ".us")
            })
            store.savePrefs(com.avidviewer.tv.data.Prefs(activePlaylist = "old", favorites = listOf("old:1", "old:2"), channelNames = mapOf("old:2" to "My 103")))
            // The provider has since added Channel 100 at the top; the app downloads the list again.
            val vm = track(AppViewModel(app))
            idleUntil("saved channels") { vm.channels.isNotEmpty() }
            vm.refreshPlaylist("old")
            idleUntil("the new list (${vm.channels.map { it.key }})") { vm.channels.size == 4 }
            idleUntil("favorites moved") { vm.prefs.favorites.all { vm.channel(it) != null } }
            assertEquals(listOf("Channel 102", "Channel 103"), vm.prefs.favorites.map { vm.channel(it)!!.name })
            assertEquals("My 103", vm.channelName(vm.channel(vm.prefs.favorites[1])!!))
        }
    }

    @Test
    fun aRestoreAfterTheProviderAddedAChannelKeepsFavoritesOnTheSameChannels() {
        var names = listOf("Channel 101", "Channel 102", "Channel 103", "Channel 104")
        serve { m3u(names, "http://stream.example") }.use { srv ->
            val url = "http://127.0.0.1:${srv.localPort}/list.m3u"
            // Device 1: add the playlist, favorite 102 and 104, make a backup.
            val vm = track(AppViewModel(app))
            vm.addPlaylist("Provider", "m3u", "", "", "", url, "")
            idleUntil("channels on device 1") { vm.channels.size == 4 }
            val k102 = vm.channels.first { it.name == "Channel 102" }.key
            val k104 = vm.channels.first { it.name == "Channel 104" }.key
            vm.toggleInList(FAV_GROUP, k102); vm.toggleInList(FAV_GROUP, k104)
            vm.renameChannel(k104, "Local news")
            val text = Backup.write(Backup.Contents(vm.playlists, vm.prefs, "", vm.history, emptyMap(), System.currentTimeMillis(), "test", refs = vm.backupRefs()), "2468")

            // Later the provider adds a channel near the top, and the app is installed fresh on another TV.
            names = listOf("Channel 100", "Channel 101", "Channel 102", "Channel 103", "Channel 104")
            app.filesDir.deleteRecursively(); app.filesDir.mkdirs()
            vm.catalog.close(); app.deleteDatabase("catalog.db") // a fresh install has no database either
            val vm2 = track(AppViewModel(app))
            vm2.offerRestore(text)
            var ok = false
            vm2.restore("2468") { ok = it }
            idleUntil("restore and download on device 2") { ok && vm2.channels.size == 5 }
            idleUntil("favorites resolved (${vm2.prefs.favorites})") { vm2.prefs.favorites.all { k -> vm2.channel(k) != null } }
            val favNames = vm2.prefs.favorites.mapNotNull { vm2.channel(it)?.name }
            assertEquals(listOf("Channel 102", "Channel 104"), favNames)
            assertEquals("Local news", vm2.channelName(vm2.channel(vm2.prefs.favorites.last())!!))
        }
    }
}
