package com.avidviewer.tv.data

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
    private const val BASE = "https://api.themoviedb.org/3"
    const val IMG = "https://image.tmdb.org/t/p/"

    private fun url(key: String, path: String, params: String = ""): String {
        // Long tokens are "API Read Access Tokens"; 32-character values are classic API keys.
        val auth = if (key.length > 40) "" else "api_key=$key&"
        return "$BASE$path?${auth}language=en-US$params"
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
                rating = o.optDouble("vote_average", 0.0),
                genres = if (g == null) emptyList() else List(g.length()) { g.getInt(it) },
                date = date,
            )
        }
    }

    private fun day(offsetDays: Int): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() + offsetDays * 86_400_000L))

    fun trending(key: String): List<Title> = titles(get(key, "/trending/all/week").optJSONArray("results"))

    fun inTheaters(key: String, region: String): List<Title> =
        titles(get(key, "/movie/now_playing", "&region=$region").optJSONArray("results"), "movie")

    /** New to streaming: recent releases available on subscription services in [region] (optionally only [providers]). */
    fun newMovies(key: String, region: String, providers: List<Int>): List<Title> {
        val prov = if (providers.isEmpty()) "" else "&with_watch_providers=" + providers.joinToString("|")
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
