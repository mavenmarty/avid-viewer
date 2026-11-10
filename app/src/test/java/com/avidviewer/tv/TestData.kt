package com.avidviewer.tv

import android.content.Context
import com.avidviewer.tv.data.Channel
import com.avidviewer.tv.data.ChannelList
import com.avidviewer.tv.data.Playlist
import com.avidviewer.tv.data.Prefs
import com.avidviewer.tv.data.Program
import com.avidviewer.tv.data.Store
import com.avidviewer.tv.data.VodItem

/**
 * Puts realistic sample data on the test device before the app starts: two playlists, several
 * categories with long provider-style names, a TV guide, movies and series (including a large
 * category), a custom list and a favorite. Nothing points at a real server, so nothing is downloaded.
 */
object TestData {
    const val P1 = "p1test"
    const val P2 = "p2test"

    fun seed(context: Context) {
        context.filesDir.deleteRecursively()
        context.filesDir.mkdirs()
        val store = Store(context)
        val dead = "http://127.0.0.1:9/none" // nothing listens here: any accidental download fails fast
        store.savePlaylists(
            listOf(
                Playlist(id = P1, name = "My provider", type = "m3u", m3uUrl = "$dead/p1.m3u", epgUrl = "$dead/p1.xml"),
                Playlist(id = P2, name = "Free channels", type = "m3u", m3uUrl = "$dead/p2.m3u", epgUrl = "$dead/p2.xml", vodOn = false),
            ),
        )
        val groups = listOf("US| LOCALS", "NEWS", "SPORTS | NFL", "VOD | EN - Movies 4K", "KIDS", "FLO Sports")
        val channels = ArrayList<Channel>()
        groups.forEachIndexed { gi, g ->
            for (i in 1..12) {
                val name = if (gi == 0) "US: WDAY ABC $i Fargo HD" else "$g Channel $i with a fairly long provider name"
                channels.add(Channel("$P1:c$gi-$i", name, g, null, "$dead/s$gi-$i.ts", "epg$gi-$i"))
            }
        }
        // A long category, to test scrolling far down and coming back.
        for (i in 1..80) channels.add(Channel("$P1:big$i", "BIG Channel $i with a fairly long provider name", "BIG", null, "$dead/big$i.ts", "epgbig$i"))
        store.saveChannels(P1, channels)
        store.saveChannels(P2, (1..8).map { Channel("$P2:f$it", "Free News $it", "Free News", null, "$dead/f$it.ts", null) })

        val now = System.currentTimeMillis()
        val half = 30 * 60 * 1000L
        val start = (now / half) * half - half
        store.saveGuide(P1, channels.associate { c ->
            c.epgId!! to (0 until 30).map { k ->
                val len = if (k % 3 == 0) 2 * half else half
                val s = start + k * half
                Program(s, s + len, "Program ${c.name.take(10)} $k", if (k % 2 == 0) "Episode $k" else null, k % 5 == 0)
            }
        })

        val vod = ArrayList<VodItem>()
        listOf("Drama" to 20, "Action" to 15, "VOD | EN - Movies 4K" to 1105).forEach { (cat, n) ->
            for (i in 1..n) vod.add(VodItem("$P1:m$cat$i", P1, "movie", "$cat Movie $i", null, "$dead/m$i.mp4", category = cat, added = i.toLong(), year = "${1990 + i % 30}"))
        }
        listOf("VOD | EV Anime" to 678, "Crime" to 10).forEach { (cat, n) ->
            for (i in 1..n) vod.add(VodItem("$P1:s$cat$i", P1, "series", "$cat Show $i", null, "", seriesId = "$cat$i", category = cat))
        }
        store.saveVod(P1, vod)

        store.savePrefs(
            Prefs(
                activePlaylist = P1,
                autoUpdateCheck = false,
                favorites = listOf("$P1:c1-1", "$P1:c0-2"),
                lists = listOf(ChannelList("l1", "Kids night", listOf("$P1:c4-1", "$P1:c4-2"))),
            ),
        )
    }
}
