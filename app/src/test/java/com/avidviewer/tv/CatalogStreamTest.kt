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

    /**
     * Nov 12 (1.0.160): with a big catalog, the upgrade to version 4 worked out every title's language
     * inside the upgrade, holding the database for minutes; channels never loaded and Fire TV closed
     * the app. The upgrade now only adds the column; the languages are filled in later, in small steps.
     */
    @Test
    fun aBigVersionThreeCatalogUpgradesAtOnceAndGetsItsLanguagesLater() {
        catalog.close(); app.deleteDatabase("catalog.db")
        val f = app.getDatabasePath("catalog.db").also { it.parentFile?.mkdirs() }
        val n = 60_000
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("""CREATE TABLE title (k TEXT PRIMARY KEY, p TEXT NOT NULL, t TEXT NOT NULL, g TEXT NOT NULL, c TEXT NOT NULL,
               n TEXT NOT NULL, nn TEXT NOT NULL, l TEXT, u TEXT NOT NULL, s TEXT, a INTEGER NOT NULL DEFAULT 0,
               r TEXT NOT NULL DEFAULT '', y TEXT NOT NULL DEFAULT '', o INTEGER NOT NULL, h INTEGER NOT NULL DEFAULT 0, tk TEXT)""")
            db.execSQL("CREATE TABLE source (p TEXT PRIMARY KEY, saved INTEGER NOT NULL)")
            com.avidviewer.tv.data.LiveDb.create(db)
            db.beginTransaction()
            val st = db.compileStatement("INSERT INTO title (k, p, t, g, c, n, nn, u, o) VALUES (?, 'p', 'series', 'series', ?, ?, ?, 'http://x/s', ?)")
            for (i in 0 until n) {
                val (cat, name) = when (i % 3) { 0 -> "EN | Drama" to "EN | Show $i"; 1 -> "FR | Séries" to "FR | Show $i"; else -> "Crime" to "Show number $i with a long provider name" }
                st.clearBindings(); st.bindString(1, "p:$i"); st.bindString(2, cat); st.bindString(3, name); st.bindString(4, "show$i"); st.bindLong(5, i.toLong()); st.executeInsert()
            }
            db.setTransactionSuccessful(); db.endTransaction()
            db.execSQL("INSERT INTO source VALUES ('p', 1)")
            db.version = 3
        }
        val t0 = System.currentTimeMillis()
        catalog = Catalog(app)
        val live = com.avidviewer.tv.data.LiveDb(catalog)
        assertEquals(0, live.channelCount("p")) // live tables answer right away
        assertEquals(n, catalog.count(Catalog.Query("series", listOf("p"))))
        val upgradeMs = System.currentTimeMillis() - t0
        assertTrue("the upgrade took $upgradeMs ms", upgradeMs < 3_000)
        // Before the languages are in, grouped lists still work (unknown counts as unmarked).
        assertTrue(catalog.needsLanguages())
        assertEquals(10, catalog.page(Catalog.Query("series", listOf("p"), grouped = true), 0, 10).size)
        // Filled in small steps.
        val t1 = System.currentTimeMillis()
        assertEquals(n, catalog.fillLanguages(pause = {}))
        val fillMs = System.currentTimeMillis() - t1
        assertTrue("filling languages took $fillMs ms", fillMs < 30_000)
        assertFalse(catalog.needsLanguages())
        catalog.readableDatabase.rawQuery("SELECT lr, COUNT(*) FROM title GROUP BY lr ORDER BY lr", null).use { c ->
            val m = HashMap<Int, Int>(); while (c.moveToNext()) m[c.getInt(0)] = c.getInt(1)
            assertEquals(mapOf(0 to n / 3, 1 to n / 3, 3 to n / 3), m)
        }
        assertEquals(0, catalog.fillLanguages(pause = {}))
        // Each title once: worked out in one background pass, so reading a list page stays quick
        // (1.0.160–162 grouped inside every page read: seconds per page on a Fire TV).
        val t2 = System.currentTimeMillis()
        catalog.regroup(listOf("p"))
        val regroupMs = System.currentTimeMillis() - t2
        assertTrue("regrouping took $regroupMs ms", regroupMs < 20_000)
        val gq = Catalog.Query("series", listOf("p"), grouped = true)
        val t3 = System.currentTimeMillis()
        assertEquals(n, catalog.count(gq)) // every name is different here
        repeat(20) { catalog.page(gq, it * 2_000, 100) }
        val pageMs = System.currentTimeMillis() - t3
        assertTrue("20 pages took $pageMs ms", pageMs < 2_000)
        // The plan reads the list's index, with no grouping or temporary sort per page.
        val plan = catalog.readableDatabase.rawQuery("EXPLAIN QUERY PLAN SELECT k FROM title WHERE g = 'series' AND h = 0 AND rep = 1 AND p IN ('p') ORDER BY CASE p WHEN 'p' THEN 0 END, o LIMIT 100 OFFSET 0", null).use { c ->
            val l = ArrayList<String>(); while (c.moveToNext()) l.add(c.getString(c.columnCount - 1)); l.joinToString(" | ")
        }
        assertFalse("plan: $plan", plan.contains("GROUP BY"))
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
