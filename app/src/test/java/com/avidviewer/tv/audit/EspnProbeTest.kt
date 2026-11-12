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
        for (l in League.ALL) for (d in EspnFeed.days(t, z)) {
            val r = runCatching { EspnFeed.parse(l.id, EspnFeed.fetchDay(l, d)) }
            println("ESPNPROBE|${l.id} $d|" + r.fold({ g ->
                "OK ${g.size} games; " + g.take(2).joinToString(" ; ") { "${it.away.name} @ ${it.home.name} ${it.state} '${it.detail}' nat=" + it.national.joinToString(",") { b -> b.name } + " local=" + it.broadcasts.filter { b -> b.market != "national" }.joinToString(",") { b -> b.name } }
            }, { "FAIL ${it.message}" }))
        }
    }
}
