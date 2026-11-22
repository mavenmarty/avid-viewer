package com.avidviewer.tv.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A movie or TV show from TMDB. */
data class Title(
    val id: Int,
    val type: String, // "movie" or "tv"
    val name: String,
    val year: String,
    val overview: String,
    val poster: String?,
    val backdrop: String?,
    val rating: Double,
    val genres: List<Int>,
    val date: String,
    /** How many people rated it on TMDB. */
    val votes: Int = 0,
) {
    val key get() = "$type:$id"
}

data class WatchProvider(val id: Int, val name: String, val logo: String?)

/** Where a title can be watched in one country. Data supplied to TMDB by JustWatch. */
data class WhereToWatch(val stream: List<WatchProvider>, val free: List<WatchProvider>, val rent: List<WatchProvider>, val buy: List<WatchProvider>)

/**
 * The Movie Database (TMDB) API. Free for personal, non-commercial use with the user's own key.
 * "This product uses the TMDB API but is not endorsed or certified by TMDB."
 * Streaming availability is provided to TMDB by JustWatch.
 */
object Tmdb {
    /** TMDB's API (tests point it at a stand-in). */
    @Volatile internal var base = "https://api.themoviedb.org/3"
    const val IMG = "https://image.tmdb.org/t/p/"

    private fun url(key: String, path: String, params: String = ""): String {
        // Long tokens are "API Read Access Tokens"; 32-character values are classic API keys.
        val auth = if (key.length > 40) "" else "api_key=$key&"
        return "$base$path?${auth}language=en-US$params"
    }

    private fun get(key: String, path: String, params: String = ""): JSONObject {
        val req = okhttp3.Request.Builder().url(url(key, path, params)).header("User-Agent", USER_AGENT).apply {
            if (key.length > 40) header("Authorization", "Bearer $key")
        }.build()
        Net.client.newCall(req).execute().use { r ->
            if (r.code == 401) throw java.io.IOException("TMDB didn't accept the API key")
            if (!r.isSuccessful) throw java.io.IOException("TMDB answered ${r.code}")
            return JSONObject(r.body?.string() ?: "{}")
        }
    }

    private fun titles(a: JSONArray?, forceType: String? = null): List<Title> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val type = forceType ?: o.optString("media_type")
            if (type != "movie" && type != "tv") return@mapNotNull null
            val name = o.optString(if (type == "movie") "title" else "name")
            val date = o.optString(if (type == "movie") "release_date" else "first_air_date")
            val g = o.optJSONArray("genre_ids")
            Title(
                id = o.getInt("id"), type = type, name = name, year = date.take(4), overview = o.optString("overview"),
                poster = o.optString("poster_path").ifBlank { null }?.let { IMG + "w342" + it },
                backdrop = o.optString("backdrop_path").ifBlank { null }?.let { IMG + "w780" + it },
                votes = o.optInt("vote_count"),
                rating = o.optDouble("vote_average", 0.0),
                genres = if (g == null) emptyList() else List(g.length()) { g.getInt(it) },
                date = date,
            )
        }
    }

    private fun day(offsetDays: Int): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() + offsetDays * 86_400_000L))

    fun trending(key: String): List<Title> = titles(get(key, "/trending/all/week").optJSONArray("results"))

    /** This week's trending movies ("movie") or series ("tv"), the first [pages] pages (20 a page). */
    fun trendingWeek(key: String, type: String, pages: Int = 2): List<Title> =
        (1..pages).flatMap { titles(get(key, "/trending/$type/week", "&page=$it").optJSONArray("results"), type) }

    /** 1.17.0: titles for grown-ups off: movies rated PG-13 or lower (US ratings). */
    private const val FAMILY = "&certification_country=US&certification.lte=PG-13"

    /** Trending without movies rated R and up: popular movies rated PG-13 or lower, mixed with trending series. */
    fun trendingFamily(key: String): List<Title> {
        val m = titles(get(key, "/discover/movie", "&sort_by=popularity.desc$FAMILY").optJSONArray("results"), "movie")
        val s = titles(get(key, "/trending/tv/week").optJSONArray("results"), "tv")
        return m.zip(s).flatMap { listOf(it.first, it.second) } + m.drop(s.size) + s.drop(m.size)
    }

    fun inTheatersFamily(key: String, region: String): List<Title> = titles(
        get(key, "/discover/movie", "&region=$region&with_release_type=2|3&primary_release_date.gte=${day(-45)}&primary_release_date.lte=${day(0)}&sort_by=popularity.desc$FAMILY")
            .optJSONArray("results"), "movie",
    )

    fun inTheaters(key: String, region: String): List<Title> =
        titles(get(key, "/movie/now_playing", "&region=$region").optJSONArray("results"), "movie")

    /** New to streaming: recent releases available on subscription services in [region] (optionally only [providers]). */
    fun newMovies(key: String, region: String, providers: List<Int>, family: Boolean = false): List<Title> {
        val prov = (if (providers.isEmpty()) "" else "&with_watch_providers=" + providers.joinToString("|")) + if (family) FAMILY else ""
        return titles(
            get(key, "/discover/movie", "&watch_region=$region&with_watch_monetization_types=flatrate|free|ads$prov" +
                "&sort_by=primary_release_date.desc&primary_release_date.lte=${day(0)}&primary_release_date.gte=${day(-120)}&vote_count.gte=10")
                .optJSONArray("results"), "movie",
        )
    }

    fun newShows(key: String, region: String, providers: List<Int>): List<Title> {
        val prov = if (providers.isEmpty()) "" else "&with_watch_providers=" + providers.joinToString("|")
        return titles(
            get(key, "/discover/tv", "&watch_region=$region&with_watch_monetization_types=flatrate|free|ads$prov" +
                "&sort_by=popularity.desc&air_date.lte=${day(0)}&air_date.gte=${day(-14)}")
                .optJSONArray("results"), "tv",
        )
    }

    fun recommendations(key: String, t: Title): List<Title> =
        titles(get(key, "/${t.type}/${t.id}/recommendations").optJSONArray("results"), t.type)

    /**
     * The title's content rating in [region] ("R", "PG-13", "TV-14"): "" when TMDB has none there (US as a fallback).
     * Nov 21, 2026: shown with the year on posters and title pages.
     */
    fun certification(key: String, type: String, id: Int, region: String): String =
        if (type == "tv") tvCertification(get(key, "/tv/$id/content_ratings"), region)
        else movieCertification(get(key, "/movie/$id/release_dates"), region)

    /** From /movie/{id}/release_dates: the theatrical rating if there is one, else the first given. */
    fun movieCertification(o: JSONObject, region: String): String {
        val res = o.optJSONArray("results") ?: return ""
        fun forRegion(r: String): String {
            for (i in 0 until res.length()) {
                val e = res.getJSONObject(i)
                if (e.optString("iso_3166_1") != r) continue
                val dates = e.optJSONArray("release_dates") ?: continue
                val all = (0 until dates.length()).map { dates.getJSONObject(it) }.filter { it.optString("certification").isNotBlank() }
                return (all.firstOrNull { it.optInt("type") == 3 } ?: all.firstOrNull())?.optString("certification")?.trim() ?: ""
            }
            return ""
        }
        return forRegion(region).ifBlank { if (region != "US") forRegion("US") else "" }
    }

    /** From /tv/{id}/content_ratings. */
    fun tvCertification(o: JSONObject, region: String): String {
        val res = o.optJSONArray("results") ?: return ""
        fun forRegion(r: String) = (0 until res.length()).map { res.getJSONObject(it) }
            .firstOrNull { it.optString("iso_3166_1") == r }?.optString("rating")?.trim() ?: ""
        return forRegion(region).ifBlank { if (region != "US") forRegion("US") else "" }
    }

    // ---------------- the profile's TMDB account: rating titles (Nov 22, 2026) ----------------

    /** A POST or DELETE with a JSON body; the answer, or an IOException with TMDB's message. */
    private fun send(key: String, method: String, path: String, params: String, body: JSONObject?): JSONObject {
        val rb = (body ?: JSONObject()).toString().toRequestBody("application/json;charset=utf-8".toMediaType())
        val req = okhttp3.Request.Builder().url(url(key, path, params)).header("User-Agent", USER_AGENT).method(method, rb).apply {
            if (key.length > 40) header("Authorization", "Bearer $key")
        }.build()
        Net.client.newCall(req).execute().use { r ->
            val o = runCatching { JSONObject(r.body?.string() ?: "{}") }.getOrDefault(JSONObject())
            if (!r.isSuccessful) throw java.io.IOException(o.optString("status_message").ifBlank { "TMDB answered ${r.code}" }, AnswerCode(r.code))
            return o
        }
    }
    /** The HTTP status behind a refused call (401: the person hasn't approved the TV yet, or the session ended). */
    class AnswerCode(val code: Int) : Exception()

    /** Step 1 of linking a TMDB account: a request token the person approves at [approveUrl] (a QR code on the TV). */
    fun newRequestToken(key: String): String =
        get(key, "/authentication/token/new").optString("request_token").ifBlank { throw java.io.IOException("TMDB gave no token") }

    fun approveUrl(token: String) = "https://www.themoviedb.org/authenticate/$token"

    /** Step 2: the session once the person has approved the token; null while they haven't yet. */
    fun newSession(key: String, token: String): String? = try {
        send(key, "POST", "/authentication/session/new", "", JSONObject().put("request_token", token)).optString("session_id").ifBlank { null }
    } catch (e: java.io.IOException) {
        if ((e.cause as? AnswerCode)?.code == 401) null else throw e
    }

    /** The TMDB user name of a session ("Linked as …"). */
    fun accountName(key: String, session: String): String =
        get(key, "/account", "&session_id=$session").let { o -> o.optString("username").ifBlank { o.optString("name") } }

    /** The person's own rating of a title (0.5–10), or null when they haven't rated it. [type]: "movie" or "tv". */
    fun myRating(key: String, type: String, id: Int, session: String): Double? =
        get(key, "/$type/$id/account_states", "&session_id=$session").optJSONObject("rated")?.optDouble("value")?.takeIf { it > 0 }

    /** Rates a title (0.5–10 in steps of 0.5), or removes the rating when [value] is null. */
    fun rate(key: String, type: String, id: Int, session: String, value: Double?) {
        if (value == null) send(key, "DELETE", "/$type/$id/rating", "&session_id=$session", null)
        else send(key, "POST", "/$type/$id/rating", "&session_id=$session", JSONObject().put("value", (Math.round(value * 2) / 2.0).coerceIn(0.5, 10.0)))
    }

    /** Unlinking: ends the session on TMDB too. */
    fun endSession(key: String, session: String) { runCatching { send(key, "DELETE", "/authentication/session", "", JSONObject().put("session_id", session)) } }

    /** Finds the TMDB title for a name from the user's provider, e.g. "Dune: Part Two (2024)". */
    fun find(key: String, name: String, type: String): Title? {
        val year = Regex("\\((\\d{4})\\)").find(name)?.groupValues?.get(1)
        val q = java.net.URLEncoder.encode(cleanName(name), "UTF-8")
        val yearParam = if (year == null) "" else if (type == "movie") "&year=$year" else "&first_air_date_year=$year"
        return titles(get(key, "/search/$type", "&query=$q$yearParam").optJSONArray("results"), type).firstOrNull()
    }

    fun whereToWatch(key: String, t: Title, region: String): WhereToWatch {
        val r = get(key, "/${t.type}/${t.id}/watch/providers").optJSONObject("results")?.optJSONObject(region)
        fun list(n: String): List<WatchProvider> {
            val a = r?.optJSONArray(n) ?: return emptyList()
            return List(a.length()) { a.getJSONObject(it).let { p -> WatchProvider(p.getInt("provider_id"), p.optString("provider_name"), p.optString("logo_path").ifBlank { null }?.let { l -> IMG + "w92" + l }) } }
        }
        return WhereToWatch(list("flatrate"), list("free") + list("ads"), list("rent"), list("buy"))
    }

    /** Subscription services available in [region], most popular first. */
    fun services(key: String, region: String): List<WatchProvider> {
        val a = get(key, "/watch/providers/movie", "&watch_region=$region").optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
            .sortedBy { it.optJSONObject("display_priorities")?.optInt(region, 999) ?: it.optInt("display_priority", 999) }
            .take(40)
            .map { WatchProvider(it.getInt("provider_id"), it.optString("provider_name"), it.optString("logo_path").ifBlank { null }?.let { l -> IMG + "w92" + l }) }
    }

    /** Strips provider decorations like "EN - ", "[4K]", "(2024)" so names can be compared. */
    // Provider tags before the title, one or several: "EN | ", "NF - ", "4K-NF - ", "EN| AMZN: " (#14: a second tag
    // such as NF made "NF - Allen" a different title from "Allen", so Recently added showed both).
    private val PREFIX = Regex("^(?:\\s*(?:[A-Z]{2,3}\\+?|[48]K|UHD|FHD|HDR|DSNP|AMZN|HMAX|ATVP|PCOK)\\s*[-:|]\\s*)+")
    private val BRACKETS = Regex("\\[[^]]*]|\\([^)]*\\)")
    private val QUALITY = Regex("(?i)\\b(4k|uhd|fhd|hd|sd|hevc|multi|sub|dub)\\b")
    private val SPACES = Regex("\\s+")
    private val NON_ALNUM = Regex("[^a-z0-9]")

    fun cleanName(s: String): String = s
        .replace(PREFIX, "")
        .replace(BRACKETS, " ")
        .replace(QUALITY, " ")
        .replace(SPACES, " ").trim()

    fun normalize(s: String): String = cleanName(s).lowercase().replace(NON_ALNUM, "")
}
