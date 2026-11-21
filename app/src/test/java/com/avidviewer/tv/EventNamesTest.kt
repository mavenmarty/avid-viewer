package com.avidviewer.tv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.Catalog
import com.avidviewer.tv.data.Channel
import com.avidviewer.tv.data.EventNames
import com.avidviewer.tv.data.LiveDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Event slots without a guide id get listings from their names. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EventNamesTest {
    // Sat 21 Nov 2026, 13:00 UTC
    private val now = utcMs("2026-11-21 13:00")

    private fun utcMs(s: String) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time
    private fun utc(ms: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)
    private fun ev(name: String, group: String = "") = EventNames.parse(name, group, now)

    @Test
    fun readsEachNameFormat() {
        val cases = mapOf(
            "LIVE | GRAND SLAM OF CURLING | Sat 21 Nov 06:30 EST (US) | 8K EXCLUSIVE | US: ESPN+ PPV 2" to ("GRAND SLAM OF CURLING" to "11-21 11:30"),
            "Next | Cheminots vs. Kouilou | Ligue 1 | 2026-11-21 | 13:00 (GMT) | 8K EXCLUSIVE | GB: DAZN PPV 22" to ("Cheminots vs. Kouilou" to "11-21 13:00"),
            "End | ST World Tour: Seoul 1 | all | 21-11-2026 | 00:00 (GMT) | 8K EXCLUSIVE | US: SOCCER PPV 1" to ("ST World Tour: Seoul 1" to "11-21 00:00"),
            "US (ESPN+ 209) | Real Madrid vs. Celta de Vigo Nov 21 2:30PM ET (2026-11-21 14:30:10)" to ("Real Madrid vs. Celta de Vigo" to "11-21 19:30"),
            "(US) (BTN+ 068) | Basketball (W): Penn at UCLA (2026-11-29 16:50:00)" to ("Basketball (W): Penn at UCLA" to "11-29 21:50"),
            "NCAAM: Siena vs Bucknell @ Nov 21 11:00 AM :NCAAB  02" to ("NCAAM: Siena vs Bucknell" to "11-21 16:00"),
            "LIVE EVENT 01 - 10am UFC Fight Night Prochazka v Stirling" to ("UFC Fight Night Prochazka v Stirling" to "11-21 15:00"),
        )
        for ((name, want) in cases) {
            val e = ev(name)!!
            assertEquals(name, want, e.title to utc(e.start))
        }
        assertEquals("11-21 12:30", utc(ev("LIVE EVENT 05 - 12:30pm Stein v Antonietti", "UK| EVENTS")!!.start))
    }

    @Test
    fun emptySlotsAndOrdinaryChannels() {
        for (n in listOf("- NO EVENT STREAMING - | 8K EXCLUSIVE | US: ESPN+ PPV 527", ":NCAAB  66", "US (WNBA 31) |  (2098-12-31 13:00:30)"))
            assertNull(n, ev(n)!!.title)
        for (n in listOf("US: ESPN HD", "24/7: 30 ROCK", "####### ESPN+ PPV #######", "BBC One HD", "Sky Sports News"))
            assertNull(n, ev(n))
    }

    @Test
    fun listingsCoverTheWindow() {
        val from = now - 4 * 3600_000L
        val to = now + 24 * 3600_000L
        val l = EventNames.programs(ev("NCAAM: Siena vs Bucknell @ Nov 21 11:00 AM :NCAAB  02")!!, from, to)
        assertEquals(listOf("Next: NCAAM: Siena vs Bucknell", "NCAAM: Siena vs Bucknell", "Ended: NCAAM: Siena vs Bucknell"), l.map { it.title })
        assertEquals(from, l.first().start); assertEquals(to, l.last().end)
        assertEquals(listOf("No event scheduled"), EventNames.programs(ev(":NCAAB  66")!!, from, to).map { it.title })
        // a slot still named after an event weeks ago is empty
        val old = ev("US (MiLB 001) | Comets @ Red Wings (2026-09-26 22:05:00)")!!
        assertEquals(listOf("No event scheduled"), EventNames.programs(old, from, to).map { it.title })
    }

    @Test
    fun theDatabaseAnswersEventIds() {
        val app: Application = ApplicationProvider.getApplicationContext()
        app.deleteDatabase("catalog.db")
        val catalog = Catalog(app)
        try {
            val live = LiveDb(catalog)
            val t = System.currentTimeMillis()
            val at = SimpleDateFormat("EEE d MMM HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(t + 3600_000L)
            val name = "LIVE | Big Fight | $at GMT (UK) | 8K EXCLUSIVE | UK: PPV 2"
            live.replacingChannels("p") { add ->
                add(Channel("p:a", name, "EVENTS", null, "http://x/live/u/p/1.ts", null))
                add(Channel("p:b", "News HD", "News", null, "http://x/live/u/p/2.ts", null))
                add(Channel("p:c", "LIVE EVENT 03 - 9pm Has a guide", "EVENTS", null, "http://x/live/u/p/3.ts", "Real.us"))
            }
            val all = live.guide("p", null, t - 3600_000L, t + 12 * 3600_000L)
            assertEquals(setOf("event:p:a"), all.keys)
            assertEquals("Big Fight", all.getValue("event:p:a").first { it.subtitle == "Live event" }.title)
            val some = live.guide("p", listOf("event:p:a", "event:p:b"), t - 3600_000L, t + 12 * 3600_000L)
            assertEquals(setOf("event:p:a"), some.keys)
        } finally { catalog.close(); app.deleteDatabase("catalog.db") }
    }
}
