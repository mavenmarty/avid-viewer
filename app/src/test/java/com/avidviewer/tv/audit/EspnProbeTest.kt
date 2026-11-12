package com.avidviewer.tv.audit

import android.app.Application
import com.avidviewer.tv.data.EspnFeed
import com.avidviewer.tv.data.League
import com.avidviewer.tv.data.Net
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/** Test-only branch: asks the real ESPN feed the way the app does, and prints what comes back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EspnProbeTest {
    private fun probe(label: String, url: String) {
        val r = runCatching { Net.text(url) }
        val msg = r.fold({ t -> "OK ${t.length} bytes, events=" + Regex("\"competitions\"").findAll(t).count() }, { e -> "FAIL ${e.javaClass.simpleName}: ${e.message}" })
        println("ESPNPROBE|$label|$msg|$url")
    }

    @Test
    fun probe() {
        val base = "https://site.api.espn.com/apis/site/v2/sports/"
        val z = TimeZone.getTimeZone("America/Chicago")
        val t = System.currentTimeMillis()
        val from = EspnFeed.day(t - 86_400_000L, z); val to = EspnFeed.day(t + 2 * 86_400_000L, z); val today = EspnFeed.day(t, z)
        val tomorrow = EspnFeed.day(t + 86_400_000L, z)
        probe("nhl today", base + "hockey/nhl/scoreboard?dates=$today")
        probe("nhl today limit500", base + "hockey/nhl/scoreboard?dates=$today&limit=500")
        probe("nfl sunday", base + "football/nfl/scoreboard?dates=" + EspnFeed.day(t + 4 * 86_400_000L, z) + "&limit=500")
        probe("ncaam tomorrow g50 l500", base + "basketball/mens-college-basketball/scoreboard?dates=$tomorrow&limit=500&groups=50")
        probe("ncaam tomorrow g50", base + "basketball/mens-college-basketball/scoreboard?dates=$tomorrow&groups=50")
        probe("ncaaf sat g80 l500", base + "football/college-football/scoreboard?dates=" + EspnFeed.day(t + 3 * 86_400_000L, z) + "&limit=500&groups=80")
        probe("nba today l500", base + "basketball/nba/scoreboard?dates=$today&limit=500")
        probe("cfl today l500", base + "football/cfl/scoreboard?dates=$today&limit=500")
    }
}
