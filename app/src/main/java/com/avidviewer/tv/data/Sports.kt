package com.avidviewer.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** A Sports-tab category. Built-ins match by league; custom ones by the user's keywords. */
/** [groups]: provider categories (raw names) whose channels all count as sports, e.g. "FLO Sports". */
data class SportCat(val id: String, val name: String, val keywords: List<String>, val on: Boolean, val builtin: Boolean, val groups: List<String> = emptyList()) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kw", JSONArray(keywords)).put("on", on).put("b", builtin).put("g", JSONArray(groups))

    companion object {
        fun fromJson(o: JSONObject): SportCat {
            val a = o.optJSONArray("kw") ?: JSONArray()
            val g = o.optJSONArray("g") ?: JSONArray()
            return SportCat(o.getString("id"), o.getString("name"), List(a.length()) { a.getString(it) }, o.optBoolean("on", true), o.optBoolean("b", false), List(g.length()) { g.getString(it) })
        }

        val DEFAULTS = listOf(
            SportCat("NFL", "NFL", listOf("nfl"), true, true),
            SportCat("NHL", "NHL", listOf("nhl"), true, true),
            SportCat("ESPN+", "ESPN+", listOf("espn+", "espn plus"), true, true),
            SportCat("PPV", "PPV / UFC", listOf("ppv", "ufc", "boxing", "pay per view", "pay-per-view"), true, true),
            SportCat("NBA", "NBA", listOf("nba"), false, true),
            SportCat("MLB", "MLB", listOf("mlb"), false, true),
            SportCat("NCAA", "College sports", listOf("ncaa", "college football", "college basketball"), false, true),
            SportCat("SOCCER", "Soccer", listOf("soccer", "premier league", "epl", "mls", "champions league", "la liga"), false, true),
        )
    }
}

/** A game or event found in the guide, merged across every channel that carries it. */
data class SportEvent(
    val key: String,
    val catId: String,
    val title: String,
    val start: Long,
    val end: Long,
    val channelKeys: List<String>,
    val score: Score? = null,
)

data class Score(val awayAbbr: String, val away: String, val homeAbbr: String, val home: String, val state: String, val detail: String)

/**
 * Live scores from ESPN's public scoreboard feed (free, no account). Unofficial: if it changes or
 * is unavailable, the Sports tab still works, just without scores.
 */
object Scores {
    private val LEAGUES = mapOf(
        "NFL" to "football/nfl", "NHL" to "hockey/nhl", "NBA" to "basketball/nba",
        "MLB" to "baseball/mlb", "NCAA" to "football/college-football",
    )

    data class Game(val names: List<Set<String>>, val score: Score)

    fun supports(catId: String) = catId in LEAGUES

    fun fetch(catId: String): List<Game> {
        val path = LEAGUES[catId] ?: return emptyList()
        val root = JSONObject(Net.text("https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard"))
        val events = root.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<Game>()
        for (i in 0 until events.length()) {
            val ev = events.getJSONObject(i)
            val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: continue
            val teams = comp.optJSONArray("competitors") ?: continue
            var home: JSONObject? = null; var away: JSONObject? = null
            for (t in 0 until teams.length()) {
                val c = teams.getJSONObject(t)
                if (c.optString("homeAway") == "home") home = c else away = c
            }
            if (home == null || away == null) continue
            fun names(c: JSONObject): Set<String> {
                val t = c.optJSONObject("team") ?: return emptySet()
                return listOf("shortDisplayName", "name", "displayName", "location", "abbreviation")
                    .map { t.optString(it).lowercase() }.filter { it.length >= 3 }.toSet()
            }
            val st = (comp.optJSONObject("status") ?: ev.optJSONObject("status"))?.optJSONObject("type")
            fun abbr(c: JSONObject) = c.optJSONObject("team")?.optString("abbreviation") ?: ""
            out.add(
                Game(
                    names = listOf(names(away), names(home)),
                    score = Score(
                        awayAbbr = abbr(away), away = away.optString("score"),
                        homeAbbr = abbr(home), home = home.optString("score"),
                        state = st?.optString("state") ?: "", detail = st?.optString("shortDetail") ?: "",
                    ),
                )
            )
        }
        return out
    }

    /** Finds the game whose two teams both appear in the guide title, e.g. "Packers at Patriots". */
    fun match(title: String, games: List<Game>): Score? {
        val t = title.lowercase()
        return games.firstOrNull { g -> g.names.size == 2 && g.names.all { set -> set.any { n -> Regex("\\b" + Regex.escape(n) + "\\b").containsMatchIn(t) } } }?.score
    }
}
