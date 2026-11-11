package com.avidviewer.tv

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.Catalog
import com.avidviewer.tv.data.ChannelKeys
import com.avidviewer.tv.data.M3u
import com.avidviewer.tv.data.Playlist
import com.avidviewer.tv.data.VodItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Catalog step 2 (menu audit work plan 1.5): downloads stream straight into the database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CatalogStreamTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var catalog: Catalog

    @Before fun open() { app.deleteDatabase("catalog.db"); catalog = Catalog(app) }
    @After fun close() { catalog.close(); app.deleteDatabase("catalog.db") }

    private fun movie(key: String, name: String, url: String, cat: String = "Action") =
        VodItem(key, "p", "movie", name, null, url, category = cat)

    private fun keys() = catalog.page(Catalog.Query("movie", listOf("p")), 0, 100).map { it.key }

    @Test
    fun aFailedDownloadKeepsTheSavedTitles() {
        catalog.replace("p", sequenceOf(movie("p:a", "Alpha", "http://x/movie/1.mp4"))) { false }
        runCatching {
            catalog.replacing("p", { false }) { add ->
                add(movie("p:b", "Beta", "http://x/movie/2.mp4"))
                error("connection dropped")
            }
        }
        assertEquals(listOf("p:a"), keys())

        val kept = catalog.replacing("p", { false }, keep = { _: Unit, added -> added > 1 }) { add -> add(movie("p:c", "Gamma", "http://x/movie/3.mp4")) }
        assertFalse(kept.committed)
        assertEquals(listOf("p:a"), keys())

        val done = catalog.replacing("p", { false }) { add ->
            add(movie("p:b", "Beta", "http://x/movie/2.mp4"))
            add(movie("p:b", "Beta again", "http://x/movie/9.mp4"))   // same key: the first one wins
            add(movie("p:c", "Gamma", "http://x/movie/3.mp4"))
        }
        assertTrue(done.committed)
        assertEquals(2, done.added)
        assertEquals(listOf("p:b", "p:c"), keys())
        assertEquals("Beta", catalog.byKeys(listOf("p:b")).single().name)
    }

    @Test
    fun savedChoicesFollowTitlesToTheirNewKeys() {
        catalog.replace("p", sequenceOf(
            movie("p:vuh1", "Alpha", "http://x/movie/u/p/101.mp4"),
            movie("p:vuh2", "Beta", "http://x/movie/u/p/102.mp4"),
            movie("p:vuh3", "Gamma", "http://x/movie/u/p/103.mp4"),
            movie("p:vuh4", "Delta", "http://x/movie/u/p/104.mp4"),
        )) { false }
        val old = catalog.refs("p", listOf("p:vuh1", "p:vuh2", "p:vuh3", "p:vuh4"))
        catalog.replacing("p", { false }) { add ->
            add(movie("p:vuh1", "Alpha", "http://x/movie/u/p/101.mp4"))           // unchanged
            add(movie("p:new2", "Beta (2024)", "http://y/movie/u/p/102.mkv"))      // same stream id
            add(movie("p:new3", "Gamma", "http://y/movie/u/p/903.mp4"))           // same name and category
            add(movie("p:new4", "Delta", "http://y/movie/u/p/904.mp4", "Drama"))  // the only "Delta"
        }
        assertEquals(mapOf("p:vuh2" to "p:new2", "p:vuh3" to "p:new3", "p:vuh4" to "p:new4"), catalog.remap("p", old))
        // A name that now appears twice is too uncertain to follow.
        val again = catalog.refs("p", listOf("p:new4"))
        catalog.replacing("p", { false }) { add ->
            add(movie("p:d1", "Delta", "http://z/a.mp4", "Kids")); add(movie("p:d2", "Delta", "http://z/b.mp4", "Kids"))
        }
        assertEquals(emptyMap<String, String>(), catalog.remap("p", again))
        assertEquals("s101", ChannelKeys.token("http://x/movie/u/p/101.mp4"))
    }

    @Test
    fun aVersionOneCatalogIsUpgradedInPlace() {
        catalog.close(); app.deleteDatabase("catalog.db")
        val f = app.getDatabasePath("catalog.db").also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("""CREATE TABLE title (k TEXT PRIMARY KEY, p TEXT NOT NULL, t TEXT NOT NULL, g TEXT NOT NULL, c TEXT NOT NULL,
               n TEXT NOT NULL, nn TEXT NOT NULL, l TEXT, u TEXT NOT NULL, s TEXT, a INTEGER NOT NULL DEFAULT 0,
               r TEXT NOT NULL DEFAULT '', y TEXT NOT NULL DEFAULT '', o INTEGER NOT NULL, h INTEGER NOT NULL DEFAULT 0)""")
            db.execSQL("CREATE TABLE source (p TEXT PRIMARY KEY, saved INTEGER NOT NULL)")
            db.execSQL("INSERT INTO title (k, p, t, g, c, n, nn, u, o) VALUES ('p:a', 'p', 'movie', 'movie', 'Action', 'Alpha', 'alpha', 'http://x/movie/1.mp4', 0)")
            db.execSQL("INSERT INTO source VALUES ('p', 1)")
            db.version = 1
        }
        catalog = Catalog(app)
        assertEquals(listOf("p:a"), keys())
        catalog.replacing("p", { false }) { add -> add(movie("p:b", "Alpha", "http://y/movie/1.mp4")) }
        assertEquals(listOf("p:b"), keys())
        // Version 3's live tables were added too.
        val live = com.avidviewer.tv.data.LiveDb(catalog)
        live.replacingChannels("p") { add -> add(com.avidviewer.tv.data.Channel("p:c", "One", "News", null, "http://x/live/1.ts", null)) }
        assertEquals(1, live.channelCount("p"))
    }

    @Test
    fun anM3uFileHandsOverItsMoviesWithoutKeepingThem() {
        val body = (buildString {
            append("#EXTM3U\n")
            append("#EXTINF:-1 group-title=\"News\",News One\nhttp://x/live/u/p/1.ts\n")
            for (i in 1..500) append("#EXTINF:-1 group-title=\"Movies\",Film $i\nhttp://x/movie/u/p/$i.mp4\n")
        }).toByteArray()
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                c.use { conn ->
                    val r = conn.getInputStream().bufferedReader()
                    while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                    conn.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
                }
            }
        }
        val p = Playlist(id = "p", name = "File", type = "m3u", m3uUrl = "http://127.0.0.1:${server.localPort}/list.m3u")
        var seen = 0
        val live = M3u.read(p) { seen++ }
        assertEquals(1, live.size)
        assertEquals(500, seen)
        val done = catalog.replacing("p", { false }) { add -> M3u.read(p, add) }
        assertEquals(500, done.added)
        assertEquals(1, done.result.size)
        assertEquals(500, catalog.count(Catalog.Query("movie", listOf("p"))))
        server.close()
    }
}
