package com.avidviewer.tv

import com.avidviewer.tv.data.Channel
import com.avidviewer.tv.data.OneLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One Live TV with names from real playlists (Nov 28, 2026): each channel once in the whole guide, in Avid Viewer's
 * common categories; the first playlist's copy plays first.
 */
class OneLiveTest {
    private var n = 0
    private fun ch(p: String, g: String, name: String, epg: String? = null) = Channel("$p:${n++}", name, g, null, "http://x/$n.ts", epg)

    private val first = listOf(
        ch("k", "🇺🇸 TV Guide (USA)", "### LOCAL NETWORKS ###"), ch("k", "🇺🇸 TV Guide (USA)", "NBC [Chicago] 1080p"),
        ch("k", "🇺🇸 TV Guide (USA)", "NBC [Scranton] 1080p"), ch("k", "🇺🇸 TV Guide (USA)", "ESPN"), ch("k", "🇺🇸 TV Guide (USA)", "ESPN (Backup)"),
        ch("k", "🇺🇸 TV Guide (USA)", "ESPN 2"), ch("k", "🇺🇸 TV Guide (USA)", "ESPN U"), ch("k", "🇺🇸 TV Guide (USA)", "Big Ten Network"),
        ch("k", "🇺🇸 TV Guide (USA)", "A&E"), ch("k", "🇨🇦 TV Guide (Canada)", "A&E"), ch("k", "🇦🇺 TV Guide (Australia)", "ESPN"),
        ch("k", "📺 NBC Network (Local Affiliates)", "NBC 11 (KVLY) FARGO (H) ᴿᴬᵂ"), ch("k", "📺 ABC Network (Local Affiliates)", "ABC (WDAY) FARGO ND"),
        ch("k", "🥏 DirecTV", "Espn"), ch("k", "🥏 DirecTV", "Espnu"), ch("k", "🥏 DirecTV", "A&e"), ch("k", "🥏 DirecTV", "Big Ten Network Alternate"),
        ch("k", "🌀 Sling TV", "Btn"), ch("k", "🌀 Sling TV", "Espn 2"), ch("k", "🏈 Sport Networks", "ESPN (720p)"),
        ch("k", "📺 Prime Channels", "Storage Wars By A&e"), ch("k", "🏈 NFL Sunday Ticket", "Monday Night Football [ESPN]"),
        ch("k", "UK| ESPN+ PPV", "UK: ESPN PLUS 01 PPV"), ch("k", "US| LATIN UNIMAS ᴿᴬᵂ ⁶⁰ᶠᵖˢ", "CA: LOS ANGELES: UNIMÁS 46 KFTR-DT ᴿᴬᵂ"),
        ch("k", "Canada ᴳᴬᴺᴶᴬ", "A&E"), ch("k", "🥏 DirecTV", "Espn Deportes"), ch("k", "🏈 Sport Networks", "ESPN News (720p)"),
        ch("k", "📰 News", "CNN"), ch("k", "🧸 Kids", "Cartoon Network"),
    )
    private val second = listOf(
        ch("b", "US | Entertainment", "US A&E"), ch("b", "US | Entertainment", "US ESPN"), ch("b", "US | Sports", "US ESPN 2 (HD)"),
        ch("b", "US | Sports", "US Big Ten Network"), ch("b", "US | ABC", "US ABC 6 (WDAY) Fargo"), ch("b", "US | NBC", "US NBC (KVLY) Fargo, ND"),
        ch("b", "US | Entertainment", "US ABC (WDAY - Fargo)"), ch("b", "CA | Sports", "CA BIG TEN"), ch("b", "LIVE | PPV Events", "DAZN PPV FHD"),
        ch("b", "US | Sports", "US BTN"), ch("b", "AF | Canal+", "AF | BTN RWANDA"),
    )
    private val third = listOf(
        ch("m", "AM | USA | ENTERTAINMENT", "##### ENTERTAINMENT #####"), ch("m", "AM | USA | ENTERTAINMENT", "US - A&E EAST HD ◉"),
        ch("m", "AM | USA | ENTERTAINMENT", "US - A&E WEST HD"), ch("m", "AM | USA | NEWS", "US - CNN HD"),
    )
    private val r = OneLive.build(listOf(first, second, third), listOf("8K Live", "B1G", "Marcos"), uhd = false)
    private val dump = r.channels.joinToString("\n") { c -> "${c.group} | ${c.name} [${c.key}]" + (r.versions[c.key]?.drop(1)?.joinToString(", ", "  + ") { "${it.name} [${it.key}]" } ?: "") }
    private val inputs = first + second + third
    /** The channel shown for the input channel called [name] in playlist [pid] (itself, or the copy it's shown as). */
    private fun shown(name: String, pid: String = "k"): Channel {
        val c = inputs.firstOrNull { it.name == name && it.key.startsWith("$pid:") } ?: error("no input $name")
        val k = r.shownAs[c.key] ?: c.key
        return r.channels.firstOrNull { it.key == k } ?: error("$name not shown\n$dump")
    }
    private fun same(vararg names: Pair<String, String>) = names.map { (p, n) -> shown(n, p).key }.distinct().size == 1
    private fun copies(name: String, pid: String = "k") = r.versions[shown(name, pid).key].orEmpty().map { it.name }.toSet()

    @Test
    fun aChannelShowsOnceInTheWholeGuideWithItsCopiesBehindIt() {
        // ESPN from four 8K categories and B1G: one channel; 8K's copies first (best quality first), then B1G's.
        assertTrue(dump, same("k" to "ESPN", "k" to "ESPN (Backup)", "k" to "Espn", "k" to "ESPN (720p)", "b" to "US ESPN"))
        val espn = r.versions[shown("ESPN").key]!!
        assertEquals(dump, listOf("k", "k", "k", "k", "b"), espn.map { it.key.substringBefore(':') })
        assertEquals(dump, "ESPN (720p)", espn.first().name)
        assertEquals(dump, OneLive.SPORTS, shown("ESPN").group)
        assertTrue(dump, same("k" to "ESPN 2", "k" to "Espn 2", "b" to "US ESPN 2 (HD)"))
        assertTrue(dump, same("k" to "ESPN U", "k" to "Espnu"))
        assertTrue(dump, same("k" to "Big Ten Network", "b" to "US Big Ten Network"))
        assertTrue(dump, same("k" to "Btn", "b" to "US BTN"))
        // A&E: the East feed joins, the West feed doesn't; Canada's are apart.
        assertTrue(dump, same("k" to "A&E", "k" to "A&e", "b" to "US A&E", "m" to "US - A&E EAST HD ◉"))
        assertEquals(dump, OneLive.ENTERTAINMENT, shown("A&E").group)
        assertTrue(dump, shown("US - A&E WEST HD", "m").key != shown("A&E").key)
        assertEquals(dump, "Canada", r.channels.first { it.key == r.shownAs[first[9].key] ?: first[9].key }.group)
        assertEquals(dump, r.shownAs[first[9].key] ?: first[9].key, r.shownAs[first[24].key] ?: first[24].key)
        assertEquals(dump, "Australia", r.channels.first { it.key == first[10].key }.group)
        assertTrue(dump, same("k" to "CNN", "m" to "US - CNN HD"))
        assertEquals(dump, OneLive.NEWS, shown("CNN").group)
        assertEquals(dump, OneLive.KIDS, shown("Cartoon Network").group)
    }

    @Test
    fun localStationsJoinByCallSignAndStayApartOtherwise() {
        assertTrue(dump, same("k" to "ABC (WDAY) FARGO ND", "b" to "US ABC 6 (WDAY) Fargo", "b" to "US ABC (WDAY - Fargo)"))
        assertTrue(dump, same("k" to "NBC 11 (KVLY) FARGO (H) ᴿᴬᵂ", "b" to "US NBC (KVLY) Fargo, ND"))
        assertTrue(dump, shown("ABC (WDAY) FARGO ND").key != shown("NBC 11 (KVLY) FARGO (H) ᴿᴬᵂ").key)
        assertEquals(dump, OneLive.LOCALS, shown("ABC (WDAY) FARGO ND").group)
        assertEquals(dump, OneLive.LOCALS, shown("NBC [Chicago] 1080p").group)
        assertTrue(dump, shown("NBC [Scranton] 1080p").key != shown("NBC [Chicago] 1080p").key)
        // A California station in a US category stays a US local, not Canada.
        assertEquals(dump, OneLive.LOCALS, shown("CA: LOS ANGELES: UNIMÁS 46 KFTR-DT ᴿᴬᵂ").group)
        // Local stations come first.
        assertEquals(dump, OneLive.LOCALS, r.channels.first().group)
    }

    @Test
    fun headingsEventsAndOtherCategories() {
        assertTrue(dump, r.channels.none { it.name.contains("#####") || it.name.contains("###") })
        assertEquals(dump, "🏈 NFL Sunday Ticket", shown("Monday Night Football [ESPN]").group)
        assertEquals(dump, "LIVE | PPV Events", shown("DAZN PPV FHD", "b").group)
        assertEquals(dump, OneLive.SPANISH, shown("Espn Deportes").group)
        assertEquals(dump, OneLive.ALWAYS_ON, shown("Storage Wars By A&e").group)
        assertEquals(dump, "Africa", shown("AF | BTN RWANDA", "b").group)
        // The playlist's own category is remembered for hiding and Kids rules.
        assertTrue(dump, r.rawGroups[shown("ESPN").key] in setOf("🇺🇸 TV Guide (USA)", "🏈 Sport Networks"))
        // Events after the common categories and countries.
        val groups = r.channels.map { it.group }.distinct()
        assertTrue(dump, groups.indexOf("🏈 NFL Sunday Ticket") > groups.indexOf("Canada"))
    }
}
