package com.avidviewer.tv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.Catalog
import com.avidviewer.tv.data.Channel
import com.avidviewer.tv.data.ChannelKeys
import com.avidviewer.tv.data.LiveDb
import com.avidviewer.tv.data.Program
import com.avidviewer.tv.data.Store
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Catalog step 3 (menu audit work plan 1.5): live channels and the guide in the database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LiveDbTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var catalog: Catalog
    private lateinit var live: LiveDb

    @Before fun open() { app.deleteDatabase("catalog.db"); catalog = Catalog(app); live = LiveDb(catalog) }
    @After fun close() { catalog.close(); app.deleteDatabase("catalog.db") }

    private fun ch(key: String, name: String, url: String, group: String = "News", epg: String? = null) = Channel(key, name, group, null, url, epg)

    @Test
    fun aFailedOrEmptyDownloadKeepsTheSavedChannels() {
        live.replacingChannels("p") { add -> add(ch("p:a", "Alpha", "http://x/live/u/p/1.ts")) }
        runCatching { live.replacingChannels("p") { add -> add(ch("p:b", "Beta", "http://x/live/u/p/2.ts")); error("dropped") } }
        assertEquals(listOf("p:a"), live.channels("p").map { it.key })
        val empty = live.replacingChannels("p", keep = { _: Unit, n -> n > 0 }) { }
        assertFalse(empty.committed)
        assertEquals(listOf("p:a"), live.channels("p").map { it.key })
        val done = live.replacingChannels("p") { add ->
            add(ch("p:b", "Beta", "http://x/live/u/p/2.ts")); add(ch("p:b", "Beta copy", "http://x/live/u/p/9.ts")); add(ch("p:c", "Gamma", "http://x/live/u/p/3.ts", "Sports"))
        }
        assertEquals(2, done.added)
        assertEquals(listOf("Beta", "Gamma"), live.channels("p").map { it.name })
        assertEquals(listOf("News" to 1, "Sports" to 1), live.groupCounts("p"))
        assertEquals(listOf("p:c"), live.searchChannels("p", "gam", 10).map { it.key })
        assertEquals(2, live.searchChannels("p", "a_", 10).size)   // punctuation is ignored ("a_" looks for "a"), not a wildcard
        assertTrue(live.searchChannels("p", "%", 10).isEmpty())
        assertTrue(live.channelsSavedAt("p") > 0)
    }

    @Test
    fun savedChoicesFollowChannelsToTheirNewKeys() {
        live.replacingChannels("p") { add ->
            add(ch("p:1", "Alpha", "http://x/live/u/p/101.ts"))
            add(ch("p:2", "Beta", "http://x/live/u/p/102.ts", epg = "beta.us"))
            add(ch("p:3", "Gamma", "http://x/live/u/p/103.ts"))
            add(ch("p:4", "Delta", "http://x/live/u/p/104.ts"))
        }
        val old = live.channelRefs("p", listOf("p:1", "p:2", "p:3", "p:4"))
        live.replacingChannels("p") { add ->
            add(ch("p:1", "Alpha", "http://x/live/u/p/101.ts"))                         // unchanged
            add(ch("p:n2", "Beta", "http://y/live/u/p/902.ts", "Other", "beta.us"))     // guide id + name
            add(ch("p:n3", "Gamma", "http://y/live/u/p/903.ts"))                        // name + category
            add(ch("p:n4", "Delta", "http://y/live/u/p/904.ts", "Kids"))                // the only "Delta"
            add(ch("p:n1", "Alpha copy", "http://y/live/u/p/101.ts"))
        }
        assertEquals(mapOf("p:2" to "p:n2", "p:3" to "p:n3", "p:4" to "p:n4"), live.remapChannels("p", old))
        // Same address beats everything.
        val moved = listOf(ChannelKeys.Ref("p:gone", ChannelKeys.token("http://y/live/u/p/904.ts"), "Something else", "News", null))
        assertEquals(mapOf("p:gone" to "p:n4"), live.remapChannels("p", moved))
    }

    @Test
    fun theGuideIsSavedAndReadByTimeWindow() {
        val h = 3600_000L
        live.replacingGuide("p") { add ->
            for (i in 0 until 10) add("a.us", Program(i * h, (i + 1) * h, "News at $i", if (i == 3) "Weather" else null, i == 2))
            add("b.us", Program(0, 5 * h, "Movie", null, false))
        }
        val g = live.guide("p", null, 2 * h, 5 * h)
        assertEquals(listOf(2, 3, 4).map { "News at $it" }, g["a.us"]!!.map { it.title })
        assertEquals(1, g["b.us"]!!.size)
        assertEquals(setOf("b.us"), live.guide("p", listOf("b.us"), 0, 10 * h).keys)
        assertEquals(listOf("a.us" to "News at 3"), live.searchPrograms("p", "weath", 0, 10).map { it.first to it.second.title })
        assertEquals(listOf("News at 8", "News at 9"), live.searchPrograms("p", "news", 8 * h, 10).map { it.second.title })
        // A failed download keeps the saved guide.
        val failed = live.replacingGuide("p", keep = { ok: Boolean, _ -> ok }) { add -> add("a.us", Program(0, h, "Partial", null, false)); false }
        assertFalse(failed.committed)
        assertEquals(10, live.guide("p", listOf("a.us"), 0, 10 * h)["a.us"]!!.size)
        assertEquals(2, live.guideChannelCount("p"))
        live.delete("p")
        assertTrue(live.guide("p", null, 0, 10 * h).isEmpty())
        assertEquals(0L, live.guideSavedAt("p"))
    }

    @Test
    fun filesFromOlderVersionsMoveIntoTheDatabase() {
        val store = Store(app)
        store.saveChannels("old", listOf(ch("old:1", "One", "http://x/live/u/p/1.ts", epg = "one.us"), ch("old:2", "Two", "http://x/live/u/p/2.ts")))
        store.saveGuide("old", mapOf("one.us" to listOf(Program(1000, 2000, "Show", "Ep", true))))
        assertTrue(live.hasOldFiles(store))
        live.importFiles(store)
        assertFalse(live.hasOldFiles(store))
        assertEquals(listOf("One", "Two"), live.channels("old").map { it.name })
        assertEquals("Show", live.guide("old", null, 0, 5000)["one.us"]!!.single().title)
        assertTrue(live.guideSavedAt("old") > 0)
        assertEquals(setOf("one.us"), live.epgIds("old"))
    }

    /**
     * Issue #9: Sports matching read the whole guide through one cursor while a guide refresh replaced
     * the same rows, and Android threw "Couldn't read row N from CursorWindow". The readers now go a
     * page at a time, so every row is still read, and a refresh part-way through can't break the read.
     */
    @Test
    fun bigGuidesAreReadInPagesAndSurviveARefreshPartWay() {
        val h = 3_600_000L
        val total = LiveDb.PAGE * 3 + 7
        live.replacingGuide("p") { add -> for (i in 0 until total) add("ch$i.us", Program(i * 1000L, i * 1000L + h, "Show $i", null, false)) }
        live.replacingChannels("p") { add -> for (i in 0 until total) add(ch("p:$i", "Channel $i", "http://x/live/u/p/$i.ts", epg = "ch$i.us")) }

        val seen = HashSet<String>()
        live.forEachProgram("p", 0, Long.MAX_VALUE / 2) { e, _, _, _, _ -> seen.add(e) }
        assertEquals(total, seen.size)
        var channels = 0
        live.forEachChannel("p") { _, _, _, _ -> channels++ }
        assertEquals(total, channels)
        assertEquals(total, live.guide("p", null, 0, Long.MAX_VALUE / 2).size)
        assertEquals(listOf(0L, 1000L), live.guide("p", null, 0, 2000).let { g -> listOf(g["ch0.us"]!!.single().start, g["ch1.us"]!!.single().start) })

        // A refresh that shrinks the guide while the reader is part-way through: no crash, and the read ends.
        var read = 0
        var refreshed = false
        live.forEachProgram("p", 0, Long.MAX_VALUE / 2) { _, _, _, _, _ ->
            read++
            if (read == LiveDb.PAGE + 10 && !refreshed) {
                refreshed = true
                live.replacingGuide("p") { add -> for (i in 0 until 50) add("new$i.us", Program(0, h, "New $i", null, false)) }
            }
        }
        assertTrue(refreshed)
        assertTrue(read in LiveDb.PAGE + 10..total)
        assertEquals(50, live.guide("p", null, 0, Long.MAX_VALUE / 2).size)
    }
}
