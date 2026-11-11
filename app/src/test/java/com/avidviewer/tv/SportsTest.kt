package com.avidviewer.tv

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.Catalog
import com.avidviewer.tv.data.Channel
import com.avidviewer.tv.data.Country
import com.avidviewer.tv.data.EspnFeed
import com.avidviewer.tv.data.MatchKind
import com.avidviewer.tv.data.Networks
import com.avidviewer.tv.data.PagedTitles
import com.avidviewer.tv.data.Program
import com.avidviewer.tv.data.SportsMatcher
import com.avidviewer.tv.ui.AppViewModel
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Sports, ESPN first: games from ESPN's feed, then the channels that carry them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SportsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val made = ArrayList<AppViewModel>()
    private val servers = ArrayList<FakeEspn>()

    @After
    fun stop() {
        made.forEach { it.viewModelScope.cancel() }
        servers.forEach { it.close() }
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

    private val wings = FakeEspn.Companion.T("DET", "Red Wings", "Detroit", "e30526", "7-8-3", "2")
    private val blues = FakeEspn.Companion.T("STL", "Blues", "St. Louis", "0070b9", "7-8-2", "1")

    private fun liveGame(now: Long) = FakeEspn.event(
        "401", now - 30 * 60_000L, "in", wings, blues,
        listOf(Triple("TNT", "national", "TV"), Triple("FDSN Detroit", "away", "TV")), "3:44 - 2nd", period = 2, clock = "3:44",
    )

    @Test
    fun espnGamesAreReadWithTeamsStatusVenueAndNetworks() {
        val now = System.currentTimeMillis()
        val games = EspnFeed.parse("NHL", FakeEspn.board(liveGame(now)))
        val g = games.single()
        assertEquals("NHL:401", g.id)
        assertTrue(g.live)
        assertEquals(2, g.period)
        assertEquals("Red Wings", g.away.name); assertEquals("Blues", g.home.name)
        assertEquals("7-8-3", g.away.record); assertEquals("2", g.away.score)
        assertEquals(0xFFE30526, g.away.color)
        assertEquals("Test Arena", g.venue); assertEquals("Testville, MN", g.city)
        assertEquals(listOf("TNT"), g.national.map { it.name })
        assertEquals("away", g.broadcasts.first { it.name == "FDSN Detroit" }.market)
        assertEquals((now - 30 * 60_000L) / 60_000 * 60_000, g.start)
        // A broken event is skipped, not fatal.
        assertTrue(EspnFeed.parse("NHL", """{"events":[{"id":"x"}]}""").isEmpty())
    }

    @Test
    fun channelNamesAreReadAsNetworksAndCountries() {
        assertEquals(setOf("espn2"), Networks.identities("US| ESPN 2 HD"))
        assertEquals(setOf("fox"), Networks.identities("FOX 9 KMSP"))
        assertEquals(setOf("tsn"), Networks.identities("TSN3 HD"))
        assertEquals(setOf("tnt"), Networks.identities("US: TNT East"))
        assertEquals(setOf("fs1"), Networks.identities("FOX Sports 1 FHD"))
        assertTrue("a numbered streaming feed isn't the service", Networks.identities("ESPN+ 07").isEmpty())
        assertTrue(Networks.identities("Fox News Channel").isEmpty())
        assertEquals("primevideo", Networks.canonical("Prime Video"))
        assertEquals("espnplus", Networks.canonical("ESPN+"))
        assertEquals("nflnetwork", Networks.canonical("NFL Net"))
        assertNull(Networks.canonical("Bucks App"))

        assertEquals("US", Country.of("US| TNT HD", null))
        assertEquals("CA", Country.of("CA: TSN 1", null))
        assertEquals("UK", Country.of("UK | Sky Sports Main Event", null))
        assertEquals("CA", Country.of("TNT", "CANADA SPORTS"))
        assertNull(Country.of("NHL 03: Red Wings @ Blues", "SPORTS | NHL"))
        assertFalse(Country.northAmerican("UK"))
    }

    @Test
    fun channelsAreFoundBestFirstUsThenCanadaAndNothingFromElsewhere() {
        val now = System.currentTimeMillis()
        val g = EspnFeed.parse("NHL", FakeEspn.board(liveGame(now))).single()
        val m = SportsMatcher(listOf(g)) { it == "p:hidden" }
        val chans = listOf(
            Triple("p:tnt", "US| TNT HD", "e1"), Triple("p:catnt", "CA| TNT", "e2"), Triple("p:uktnt", "UK| TNT Sports 1", "e3"),
            Triple("p:ev", "NHL 03: Red Wings @ Blues", null), Triple("p:tbs", "US| TBS", "tbs.us"), Triple("p:fdsn", "FanDuel Sports Network Detroit", "e5"),
            Triple("p:news", "Detroit Local News", "e6"), Triple("p:hidden", "US| TNT 2", "e7"), Triple("p:other", "Blues Brothers Movie Channel", "e8"),
        )
        chans.forEach { (k, n, e) -> m.addChannel("p", k, n, e, group = "SPORTS") }
        m.addProgram("p", "tbs.us", g.start - 5 * 60_000L, g.start + 3 * 3600_000L, "NHL Hockey", "Detroit Red Wings at St. Louis Blues")
        m.addProgram("p", "e6", g.start, g.start + 3600_000L, "Local News", "Red Wings coverage")
        m.addProgram("p", "e8", g.start, g.start + 3600_000L, "Blues Brothers", null)
        val found = m.result()[g.id].orEmpty()
        val keys = found.map { it.key }
        assertEquals("US national first", "p:tnt", keys.first())
        assertTrue("guide listing found", "p:tbs" in keys)
        assertTrue("event channel found", "p:ev" in keys)
        assertTrue("local network found", "p:fdsn" in keys)
        assertTrue("Canada as a backup, after US", keys.indexOf("p:catnt") > keys.indexOf("p:tnt") && keys.indexOf("p:catnt") > keys.indexOf("p:tbs"))
        assertFalse("outside North America: left out", "p:uktnt" in keys)
        assertFalse("hidden channel: left out", "p:hidden" in keys)
        assertFalse("one team named isn't the game", "p:news" in keys || "p:other" in keys)
        assertEquals(setOf(MatchKind.NATIONAL), found.first { it.key == "p:tnt" }.kinds)
        assertTrue(found.first { it.key == "p:tbs" }.reasons.single().startsWith("In the guide · NHL Hockey"))
        assertEquals("Red Wings local TV · FDSN Detroit", found.first { it.key == "p:fdsn" }.reasons.single())
    }

    @Test
    fun theSportsScreenListsEspnGamesWithTheirChannelsFromEveryPlaylist() {
        TestData.seed(app)
        val now = System.currentTimeMillis()
        val later = FakeEspn.event("402", now + 3 * 3600_000L, "pre", FakeEspn.Companion.T("COL", "Avalanche", "Colorado"), FakeEspn.Companion.T("TOR", "Maple Leafs", "Toronto"),
            listOf(Triple("ESPN+", "national", "Streaming")))
        val final = FakeEspn.event("400", now - 4 * 3600_000L, "post", FakeEspn.Companion.T("NJ", "Devils", "New Jersey", score = "4"), FakeEspn.Companion.T("WPG", "Jets", "Winnipeg", score = "3"),
            listOf(Triple("TNT", "national", "TV")))
        val espn = FakeEspn(mapOf("hockey/nhl" to FakeEspn.board(liveGame(now), later, final))).also { servers.add(it) }
        val vm = AppViewModel(app).also { made.add(it) }
        EspnFeed.base = espn.base
        idleUntil("channels") { vm.channels.isNotEmpty() }
        // Sports channels in the second playlist, with a guide listing for the live game.
        vm.live.replacingChannels(TestData.P2) { add ->
            add(Channel("${TestData.P2}:tnt", "US| TNT HD", "SPORTS", null, "http://127.0.0.1:9/tnt.ts", "tnt.us"))
            add(Channel("${TestData.P2}:uk", "UK| TNT Sports 1", "SPORTS", null, "http://127.0.0.1:9/uk.ts", "uk.tnt"))
            add(Channel("${TestData.P2}:ev", "NHL 03: Red Wings @ Blues", "SPORTS", null, "http://127.0.0.1:9/ev.ts", null))
        }
        vm.live.replacingGuide(TestData.P2) { add -> add("tnt.us", Program(now - 35 * 60_000L, now + 2 * 3600_000L, "NHL Hockey", "Red Wings at Blues", false)) }
        vm.openSportsScreen()
        idleUntil("games from ESPN (${vm.sportsNote})") { vm.sportGames.size == 3 }
        val live = vm.sportGames.first { it.game.id == "NHL:401" }
        assertEquals(listOf("${TestData.P2}:tnt", "${TestData.P2}:ev"), live.channels.map { it.key })
        assertEquals(setOf(MatchKind.NATIONAL, MatchKind.GUIDE), live.channels.first().kinds)
        assertNotNull("another playlist's match can be shown and played", vm.sportsChannel("${TestData.P2}:tnt"))
        assertTrue("today, tomorrow and the day after (and yesterday for finals)", espn.requests.any { it.contains("hockey/nhl/scoreboard?dates=") })
        assertTrue(espn.requests.any { it.contains("mens-college-basketball/scoreboard") && it.contains("groups=50") })
        // League filter and the "no channel" setting.
        vm.sportLeague = "NFL"
        assertTrue(vm.sportsVisibleGames().isEmpty())
        vm.sportLeague = null
        assertEquals(3, vm.sportsVisibleGames().size)
        vm.updateSportsOptions(showUnmatched = false)
        assertEquals("the ESPN+ game has no channel here", listOf("NHL:400", "NHL:401"), vm.sportsVisibleGames().map { it.game.id }.sorted())
        // Leagues off: not asked for.
        vm.updateSportLeagues(vm.sportLeagues().map { it.copy(on = it.id == "NHL") })
        espn.requests.clear()
        idleUntil("refetched") { espn.requests.isNotEmpty() && !vm.sportsLoading }
        assertTrue(espn.requests.all { it.contains("hockey/nhl") })
    }

    @Test
    fun whenEspnCantBeReachedTheLastListIsKept() {
        TestData.seed(app)
        val now = System.currentTimeMillis()
        val espn = FakeEspn(mapOf("hockey/nhl" to FakeEspn.board(liveGame(now)))).also { servers.add(it) }
        val vm = AppViewModel(app).also { made.add(it) }
        EspnFeed.base = espn.base
        idleUntil("channels") { vm.channels.isNotEmpty() }
        vm.openSportsScreen()
        idleUntil("games") { vm.sportGames.isNotEmpty() && !vm.sportsLoading }
        espn.close()
        EspnFeed.base = "http://127.0.0.1:9/espn/"
        vm.updateSportLeagues(vm.sportLeagues()) // refetch
        idleUntil("note") { vm.sportsNote != null && !vm.sportsLoading }
        assertTrue(vm.sportsNote!!.startsWith("Couldn't reach ESPN"))
        assertEquals(listOf("NHL:401"), vm.sportGames.map { it.game.id })
    }

    /** Discover froze and closed on a Fire TV: Compose compared paged title lists item by item. */
    @Test
    fun pagedTitleListsAreComparedWithoutReadingThem() {
        app.deleteDatabase("catalog.db")
        val c = Catalog(app)
        c.replace("p", (1..5000).asSequence().map { com.avidviewer.tv.data.VodItem("p:$it", "p", "movie", "Movie $it", null, "http://x/$it.mp4", category = "A") }) { false }
        val q = Catalog.Query("movie", listOf("p"))
        val a = PagedTitles(c, q); val b = PagedTitles(c, q)
        val t = System.nanoTime()
        assertFalse(a == b)
        a.hashCode()
        assertTrue("equals/hashCode must not read pages", (System.nanoTime() - t) / 1_000_000 < 20)
        assertEquals(a, a)
        c.close(); app.deleteDatabase("catalog.db")
    }
}
