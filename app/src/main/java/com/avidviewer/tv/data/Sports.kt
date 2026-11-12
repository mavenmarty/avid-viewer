package com.avidviewer.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * Sports, ESPN first: games come from ESPN's public scoreboard feed (free, no account, unofficial),
 * then [SportsMatcher] finds the channels that carry them. US/Canada leagues only for now.
 */
data class League(val id: String, val name: String, val tag: String, val path: String, val color: Long, val season: String, val query: String = "") {
    companion object {
        val ALL = listOf(
            League("NFL", "NFL", "NFL", "football/nfl", 0xFF013369, "Sep – Feb"),
            League("NBA", "NBA", "NBA", "basketball/nba", 0xFFC9082A, "Oct – Jun"),
            League("NHL", "NHL", "NHL", "hockey/nhl", 0xFF3A3A3A, "Oct – Jun"),
            League("MLB", "MLB", "MLB", "baseball/mlb", 0xFF041E42, "Mar – Oct"),
            League("WNBA", "WNBA", "WNBA", "basketball/wnba", 0xFFE46A1B, "May – Oct"),
            League("CFL", "CFL", "CFL", "football/cfl", 0xFFB4121B, "Jun – Nov"),
            // College: FBS football (group 80) and Division I men's basketball (group 50).
            League("NCAAF", "College football", "NCAAF", "football/college-football", 0xFF3A4A5C, "Aug – Jan · FBS", "&groups=80"),
            League("NCAAM", "College basketball (men)", "NCAAM", "basketball/mens-college-basketball", 0xFF3A4A5C, "Nov – Apr · Division I", "&groups=50"),
        )
        fun of(id: String?) = ALL.firstOrNull { it.id == id }
    }
}

/** A league in Settings › Sports tab: shown or not, in the user's order. */
data class LeagueSetting(val id: String, val on: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("on", on)
    companion object {
        fun fromJson(o: JSONObject) = LeagueSetting(o.getString("id"), o.optBoolean("on", true))
        /** Every league, on, in the default order; settings saved earlier keep their order and switches. */
        fun complete(saved: List<LeagueSetting>): List<LeagueSetting> {
            val known = saved.filter { League.of(it.id) != null }.distinctBy { it.id }
            return known + League.ALL.filter { l -> known.none { it.id == l.id } }.map { LeagueSetting(it.id, true) }
        }
    }
}

data class Team(
    val abbr: String,
    /** Short name as shown on cards, e.g. "Red Wings". */
    val name: String,
    /** Place, e.g. "Detroit". */
    val location: String,
    val fullName: String,
    val color: Long?,
    val logo: String?,
    val record: String,
    val score: String,
) {
    /** Names a guide title or channel name may use for this team, longest first (lowercase). */
    val matchNames: List<String> get() = listOf(fullName, name, location)
        .map { it.lowercase().trim() }.filter { it.length >= 3 }.distinct().sortedByDescending { it.length }
}

/** A broadcast ESPN lists for a game: [kind] TV or Streaming; [market] national, home or away. */
data class Broadcast(val name: String, val kind: String, val market: String)

data class Game(
    val id: String,
    val league: String,
    val start: Long,
    /** pre, in or post. */
    val state: String,
    /** ESPN's status line, e.g. "2:30 - 2nd", "Final", "11/12 - 8:15 PM EST". */
    val detail: String,
    val period: Int,
    val clock: String,
    val venue: String,
    val city: String,
    val neutral: Boolean,
    val away: Team,
    val home: Team,
    val broadcasts: List<Broadcast>,
) {
    val live get() = state == "in"
    val final get() = state == "post"
    val national get() = broadcasts.filter { it.market == "national" }
    /** When it's reasonable to think the game is over, for guide matching (sport lengths vary). */
    fun likelyEnd(): Long = start + when (league) { "NFL", "CFL", "NCAAF" -> 4 * 3600_000L; "MLB" -> 3 * 3600_000L + 30 * 60_000L; else -> 3 * 3600_000L }
}

/** ESPN's public scoreboard feed. Unofficial: if it changes or is unreachable, the last list is kept. */
object EspnFeed {
    /** Overridden in tests (a local server). */
    var base = "https://site.api.espn.com/apis/site/v2/sports/"

    /** Days as ESPN's `dates` parameter wants them, YYYYMMDD, in [zone]. */
    fun day(ms: Long, zone: TimeZone): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
        return "%04d%02d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /**
     * One day's games of a league ([day] YYYYMMDD). ESPN answers a single day only: a range
     * ("20261110-20261113") is refused with 400 (checked against the live feed, Nov 2026).
     */
    fun dayUrl(l: League, day: String) = "$base${l.path}/scoreboard?dates=$day&limit=500${l.query}"

    /** One day's games as ESPN sends them; throws on a network failure or a refusal. */
    fun fetchDay(l: League, day: String): String = Net.text(dayUrl(l, day))

    /** The days Sports shows: yesterday (late games and finals), today and the next 2 days, in [zone]. */
    fun days(now: Long, zone: TimeZone): List<String> = (-1..2).map { day(now + it * 86_400_000L, zone) }

    fun parse(league: String, text: String): List<Game> {
        val root = JSONObject(text)
        val events = root.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<Game>()
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            runCatching { game(league, ev) }.getOrNull()?.let(out::add)
        }
        return out
    }

    private fun game(league: String, ev: JSONObject): Game? {
        val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val teams = comp.optJSONArray("competitors") ?: return null
        var home: JSONObject? = null; var away: JSONObject? = null
        for (t in 0 until teams.length()) {
            val c = teams.optJSONObject(t) ?: continue
            if (c.optString("homeAway") == "home") home = c else away = c
        }
        if (home == null || away == null) return null
        val start = parseTime(comp.optString("date").ifBlank { ev.optString("date") }) ?: return null
        val status = comp.optJSONObject("status") ?: ev.optJSONObject("status")
        val type = status?.optJSONObject("type")
        val venue = comp.optJSONObject("venue")
        val addr = venue?.optJSONObject("address")
        val city = listOfNotNull(addr?.optString("city")?.ifBlank { null }, (addr?.optString("state")?.ifBlank { null } ?: addr?.optString("country")?.ifBlank { null })).joinToString(", ")
        return Game(
            id = league + ":" + ev.optString("id"),
            league = league,
            start = start,
            state = type?.optString("state").orEmpty().ifBlank { "pre" },
            detail = type?.optString("shortDetail").orEmpty(),
            period = status?.optInt("period") ?: 0,
            clock = status?.optString("displayClock").orEmpty(),
            venue = venue?.optString("fullName").orEmpty(),
            city = city,
            neutral = comp.optBoolean("neutralSite"),
            away = team(away),
            home = team(home),
            broadcasts = broadcasts(comp),
        )
    }

    private fun team(c: JSONObject): Team {
        val t = c.optJSONObject("team") ?: JSONObject()
        val rec = c.optJSONArray("records")?.optJSONObject(0)?.optString("summary").orEmpty()
        val name = t.optString("shortDisplayName").ifBlank { t.optString("name") }.ifBlank { t.optString("displayName") }
        return Team(
            abbr = t.optString("abbreviation"),
            name = name,
            location = t.optString("location"),
            fullName = t.optString("displayName").ifBlank { name },
            color = t.optString("color").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.let { 0xFF000000 or it.toLong(16) },
            logo = t.optString("logo").ifBlank { null },
            record = rec,
            score = c.optString("score"),
        )
    }

    private fun broadcasts(comp: JSONObject): List<Broadcast> {
        val out = ArrayList<Broadcast>()
        comp.optJSONArray("geoBroadcasts")?.let { a ->
            for (i in 0 until a.length()) {
                val g = a.optJSONObject(i) ?: continue
                val name = g.optJSONObject("media")?.optString("shortName").orEmpty()
                if (name.isBlank()) continue
                out.add(Broadcast(name, g.optJSONObject("type")?.optString("shortName").orEmpty().ifBlank { "TV" }, g.optJSONObject("market")?.optString("type").orEmpty().lowercase().ifBlank { "national" }))
            }
        }
        if (out.isEmpty()) comp.optJSONArray("broadcasts")?.let { a ->
            for (i in 0 until a.length()) {
                val b = a.optJSONObject(i) ?: continue
                val market = b.optString("market").lowercase().ifBlank { "national" }
                val names = b.optJSONArray("names") ?: JSONArray()
                for (j in 0 until names.length()) names.optString(j).ifBlank { null }?.let { out.add(Broadcast(it, "TV", market)) }
            }
        }
        return out.distinctBy { it.name.lowercase() + "|" + it.market }
    }

    /** ESPN dates look like "2026-11-13T01:15Z" (no seconds) or with seconds. */
    fun parseTime(s: String): Long? {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?""").find(s) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        val sec = m.groupValues[6].ifBlank { "0" }
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear(); c.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), sec.toInt())
        return c.timeInMillis
    }
}
