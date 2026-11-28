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

    private fun at(pid: String, id: String, name: String, group: String, epg: String? = null) = Channel("$pid:$id", name, group, null, "http://$pid/$id.ts", epg)

    @Test
    fun oneLiveTvJoinsCategoriesAndCopiesAcrossPlaylistsPrimaryFirst() {
        val primary = listOf(at("a", "1", "ESPN SD", "USA: Sports"), at("a", "2", "WDAY", "US| Locals", "wday.us"))
        val second = listOf(
            at("b", "1", "US| ESPN FHD", "US| SPORTS", "espn.us"), at("b", "2", "KVLY", "US| LOCALS", "kvly.us"),
            at("b", "3", "TSN 1", "CA| Sports"), at("b", "4", "WDAY HD", "US| Locals"),
        )
        val r = ChannelMerge.mergeAcross(listOf(primary, second), listOf("8K Live", "PDS Live"), uhd = false)
        // Categories: the primary's names, the country kept apart.
        assertEquals(listOf("USA: Sports", "US| Locals", "CA| Sports"), r.channels.map { it.group }.distinct())
        assertEquals(listOf("ESPN SD", "WDAY", "KVLY", "TSN 1"), r.channels.map { it.name })
        // The primary playlist's copy plays first even when another playlist's is better; then the other playlists'.
        assertEquals(listOf("a:1", "b:1"), r.versions["a:1"]!!.map { it.key })
        assertEquals(listOf("a:2", "b:4"), r.versions["a:2"]!!.map { it.key })
        // WDAY and KVLY stay apart.
        assertEquals(null, r.versions["b:2"])
        // ESPN has no guide in the primary playlist: the second playlist's guide is shown for it.
        assertEquals("b:1", r.guideFrom["a:1"]?.key)
        assertEquals("a:1", r.shownAs["b:1"])
        // Each shown category knows the playlists' own categories in it.
        assertEquals(listOf("a" to "USA: Sports", "b" to "US| SPORTS"), r.groupMembers["USA: Sports"])
    }

    @Test
    fun oneLiveTvKeepsEventChannelsApartPerPlaylist() {
        val primary = listOf(at("a", "1", "NFL 01: Bears @ Packers", "NFL Sunday Ticket"), at("a", "2", "NFL 02", "NFL Sunday Ticket"))
        val second = listOf(at("b", "1", "NFL 01: Bears @ Packers", "NFL SUNDAY TICKET"), at("b", "2", "PPV 1", "PPV"))
        val r = ChannelMerge.mergeAcross(listOf(primary, second), listOf("8K Live", "PDS Live"), uhd = false)
        assertEquals(4, r.channels.size)
        assertTrue(r.versions.isEmpty())
        assertEquals(listOf("NFL Sunday Ticket", "NFL SUNDAY TICKET (PDS Live)", "PPV"), r.channels.map { it.group }.distinct())
    }
}
