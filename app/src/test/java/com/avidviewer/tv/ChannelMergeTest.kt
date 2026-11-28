package com.avidviewer.tv

import com.avidviewer.tv.data.Channel
import com.avidviewer.tv.data.ChannelMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nov 28, 2026: copies of a channel in one category show as one, with real names from providers' lists. */
class ChannelMergeTest {
    private fun ch(id: String, name: String, group: String = "USA | Sports", epg: String? = null) = Channel("p:$id", name, group, null, "http://x/$id.ts", epg)

    @Test
    fun namesLoseCountryAndQualityTags() {
        assertEquals("espn", ChannelMerge.key("US| ESPN FHD"))
        assertEquals("espn", ChannelMerge.key("ESPN HD"))
        assertEquals("espn", ChannelMerge.key("US - ESPN HD ◉"))
        assertEquals("espn", ChannelMerge.key("ESPN ᴿᴬᵂ"))
        assertEquals("espn", ChannelMerge.key("AM | USA | ESPN 1080p"))
        assertEquals("a+e east", ChannelMerge.key("US - A&E EAST HD ◉"))
        assertEquals("nbc [chicago]", ChannelMerge.key("NBC [Chicago] 1080p"))
        // A network's own name is not a country tag.
        assertEquals("abc news", ChannelMerge.key("ABC News"))
    }

    @Test
    fun copiesInACategoryBecomeOneChannelBestFirst() {
        val list = listOf(ch("1", "ESPN HD"), ch("2", "CNN"), ch("3", "US| ESPN FHD"), ch("4", "ESPN 4K"), ch("5", "ESPN SD"), ch("6", "ESPN"))
        val r = ChannelMerge.merge(list, uhd = false)
        assertEquals(listOf("US| ESPN FHD", "CNN"), r.channels.map { it.name })
        assertEquals(listOf("US| ESPN FHD", "ESPN HD", "ESPN", "ESPN SD", "ESPN 4K"), r.versions["p:3"]!!.map { it.name })
        assertEquals("p:3", r.shownAs["p:1"])
        // A 4K TV plays the 4K copy first.
        assertEquals("ESPN 4K", ChannelMerge.merge(list, uhd = true).channels.first().name)
    }

    @Test
    fun eastAndWestLocalsAndOtherCategoriesStayApart() {
        val list = listOf(
            ch("1", "US - A&E EAST HD ◉", "Entertainment"), ch("2", "US - A&E WEST HD", "Entertainment"),
            ch("3", "NBC [Chicago] 1080p", "Locals"), ch("4", "NBC [Scranton] 1080p", "Locals"),
            ch("5", "ESPN HD", "Sports"), ch("6", "ESPN FHD", "TV Guide (USA)"),
        )
        assertEquals(6, ChannelMerge.merge(list, uhd = false).channels.size)
    }

    @Test
    fun theSameGuideIdJoinsDifferentNames() {
        val r = ChannelMerge.merge(listOf(ch("1", "ESPN", epg = "ESPN.us"), ch("2", "ESPN (East) FHD", epg = "espn.us")), uhd = false)
        assertEquals(1, r.channels.size)
        assertEquals("ESPN (East) FHD", r.channels.single().name)
    }

    @Test
    fun eventChannelsAreNeverCombined() {
        assertTrue(ChannelMerge.isEvent("NFL 03: Bears @ Packers", "USA | NFL Sunday Ticket"))
        assertTrue(ChannelMerge.isEvent("UFC 312 FHD", "LIVE | PPV Events"))
        assertTrue(ChannelMerge.isEvent("Event 05 - No Event", "Sports"))
        assertFalse(ChannelMerge.isEvent("ESPN HD", "USA | Sports"))
        val list = listOf(ch("1", "PPV 01 HD", "LIVE | PPV Events"), ch("2", "PPV 01 FHD", "LIVE | PPV Events"))
        assertEquals(2, ChannelMerge.merge(list, uhd = false).channels.size)
    }
}
