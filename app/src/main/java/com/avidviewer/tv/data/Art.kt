package com.avidviewer.tv.data

import org.json.JSONObject
import java.io.File

/**
 * Artwork and details for a movie or series in the user's playlists, looked up once on TMDB (Nov 18, 2026: the
 * hero style). Kept on the TV in art.json, by title, so a title is never looked up twice; "not found" is kept too,
 * and tried again after a week.
 */
data class Art(val backdrop: String?, val overview: String, val rating: Double, val votes: Int, val genres: List<Int> = emptyList()) {
    /** "7.9" and "4.2K votes", or null when TMDB has no rating for it. */
    val ratingText: String? get() = if (rating <= 0.0 || votes <= 0) null else String.format(java.util.Locale.US, "%.1f", rating)
    val votesText: String get() = when {
        votes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM votes", votes / 1_000_000.0)
        votes >= 1_000 -> String.format(java.util.Locale.US, "%.1fK votes", votes / 1_000.0)
        else -> "$votes votes"
    }

    companion object {
        /** The bigger TMDB backdrop, for a picture that fills most of the screen. */
        fun big(url: String?): String? = url?.replace("/w780/", "/w1280/")

        fun from(t: Title) = Art(big(t.backdrop), t.overview, t.rating, t.votes, t.genres)

        /** TMDB's genre names (movie and TV lists together). */
        private val GENRES = mapOf(28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary",
            18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music", 9648 to "Mystery",
            10749 to "Romance", 878 to "Sci-fi", 10770 to "TV movie", 53 to "Thriller", 10752 to "War", 37 to "Western",
            10759 to "Action", 10762 to "Kids", 10763 to "News", 10764 to "Reality", 10765 to "Sci-fi", 10766 to "Soap", 10767 to "Talk", 10768 to "War")
        fun genreName(id: Int): String? = GENRES[id]

        /** The name a genre has as a Movies or Series category (TMDB's TV genres in full). */
        fun genreLabel(id: Int): String = when (id) {
            10759 -> "Action & Adventure"; 10765 -> "Sci-fi & Fantasy"; 10768 -> "War & Politics"
            else -> GENRES[id] ?: "Genre $id"
        }
    }
}

class ArtStore(dir: File) {
    private val file = File(dir, "art.json")
    private val retryNotFoundMs = 7L * 24 * 3600 * 1000
    private val map: HashMap<String, Pair<Long, Art?>> = load()

    /** The key for a title: its kind and its name without provider tags, plus the year. */
    fun key(v: VodItem): String = v.kind + "|" + Tmdb.normalize(v.name) + "|" + v.year

    /** null = not looked up yet (or a "not found" a week old); Pair(.., null) = TMDB has no match. */
    @Synchronized fun get(key: String): Pair<Long, Art?>? = map[key]?.takeIf { it.second != null || System.currentTimeMillis() - it.first < retryNotFoundMs }

    @Synchronized fun put(key: String, art: Art?) {
        map[key] = System.currentTimeMillis() to art
        if (map.size > 4000) map.entries.sortedBy { it.value.first }.take(map.size - 3500).forEach { map.remove(it.key) }
        save()
    }

    @Synchronized fun all(): Map<String, Art?> = map.mapValues { it.value.second }

    private fun load(): HashMap<String, Pair<Long, Art?>> {
        val out = HashMap<String, Pair<Long, Art?>>()
        runCatching {
            if (!file.exists()) return out
            val o = JSONObject(file.readText())
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                val a = e.optJSONObject("a")?.let { j ->
                    Art(j.optString("b").ifBlank { null }, j.optString("o"), j.optDouble("r", 0.0), j.optInt("v"),
                        j.optJSONArray("g")?.let { g -> List(g.length()) { g.getInt(it) } } ?: emptyList())
                }
                out[k] = e.optLong("t") to a
            }
        }
        return out
    }

    private fun save() {
        runCatching {
            val o = JSONObject()
            map.forEach { (k, v) ->
                val e = JSONObject().put("t", v.first)
                v.second?.let { a -> e.put("a", JSONObject().put("b", a.backdrop ?: "").put("o", a.overview).put("r", a.rating).put("v", a.votes).put("g", org.json.JSONArray(a.genres))) }
                o.put(k, e)
            }
            val tmp = File(file.parentFile, "art.json.tmp"); tmp.writeText(o.toString()); tmp.renameTo(file)
        }
    }
}
