package com.avidviewer.tv.data

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader

/**
 * A movie, series or single episode from a provider.
 * kind: "movie" and "episode" play [url] directly; "series" opens its seasons via [seriesId].
 */
data class VodItem(
    val key: String,
    val playlistId: String,
    val kind: String,
    val name: String,
    val logo: String?,
    val url: String,
    val seriesId: String? = null,
    val category: String = "",
    val added: Long = 0L,
    val rating: String = "",
    val year: String = "",
) {
    /** Provider's id for this movie (Xtream), used to fetch details. */
    val streamId: String? get() = key.substringAfter(":m", "").ifBlank { null }

    fun toJson(): JSONObject = JSONObject().put("k", key).put("p", playlistId).put("t", kind).put("n", name)
        .put("l", logo ?: "").put("u", url).put("s", seriesId ?: "")
        .put("c", category).put("a", added).put("r", rating).put("y", year)

    companion object {
        fun fromJson(o: JSONObject) = VodItem(
            key = o.getString("k"), playlistId = o.getString("p"), kind = o.getString("t"), name = o.getString("n"),
            logo = o.optString("l").ifBlank { null }, url = o.optString("u"), seriesId = o.optString("s").ifBlank { null },
            category = o.optString("c"), added = o.optLong("a", 0L), rating = o.optString("r"), year = o.optString("y"),
        )
    }
}

data class Episode(val season: String, val number: Int, val title: String, val url: String, val key: String = "", val plot: String = "", val duration: String = "")

data class VodInfo(
    val plot: String = "",
    val genre: String = "",
    val year: String = "",
    val duration: String = "",
    val cast: String = "",
    val director: String = "",
    val rating: String = "",
    val image: String? = null,
)

private fun JsonReader.str(): String = when (peek()) {
    JsonToken.NULL -> { nextNull(); "" }
    JsonToken.STRING, JsonToken.NUMBER -> nextString()
    JsonToken.BOOLEAN -> nextBoolean().toString()
    else -> { skipValue(); "" }
}

object XtreamVod {
    private fun api(p: Playlist, action: String, extra: String = ""): String {
        val u = java.net.URLEncoder.encode(p.username, "UTF-8")
        val pw = java.net.URLEncoder.encode(p.password, "UTF-8")
        return "${p.xtreamBase()}/player_api.php?username=$u&password=$pw&action=$action$extra"
    }

    private fun categories(p: Playlist, action: String): Map<String, String> = try {
        val a = JSONArray(Net.text(api(p, action)))
        (0 until a.length()).associate { a.getJSONObject(it).let { c -> c.optString("category_id") to c.optString("category_name", "Other").trim() } }
    } catch (e: Exception) {
        emptyMap()
    }

    /** Reads the movie list as a stream, so very large catalogs don't run out of memory. */
    fun movies(p: Playlist): List<VodItem> = ArrayList<VodItem>().also { l -> movies(p) { l.add(it) } }

    /** Hands each movie to [emit] as it's read; returns how many. */
    fun movies(p: Playlist, emit: (VodItem) -> Unit): Int {
        var n = 0
        val add: (VodItem) -> Unit = { emit(it); n++ }
        val cats = categories(p, "get_vod_categories")
        val base = p.xtreamBase()
        return Net.open(api(p, "get_vod_streams")) { input ->
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                if (r.peek() != JsonToken.BEGIN_ARRAY) return@use
                r.beginArray()
                while (r.hasNext()) {
                    var id = ""; var name = ""; var icon = ""; var ext = "mp4"; var cat = ""; var added = 0L; var rating = ""; var year = ""
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "stream_id" -> id = r.str()
                            "name" -> name = r.str()
                            "stream_icon" -> icon = r.str()
                            "container_extension" -> ext = r.str().ifBlank { "mp4" }
                            "category_id" -> cat = r.str()
                            "added" -> added = r.str().toLongOrNull() ?: 0L
                            "rating" -> rating = r.str()
                            "year" -> year = r.str()
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (id.isNotBlank()) add(
                        VodItem(
                            key = "${p.id}:m$id", playlistId = p.id, kind = "movie", name = name.trim(), logo = icon.ifBlank { null },
                            url = "$base/movie/${p.username}/${p.password}/$id.$ext",
                            category = cats[cat] ?: "Other", added = added, rating = rating, year = year,
                        )
                    )
                }
                r.endArray()
            }
            n
        }
    }

    fun series(p: Playlist): List<VodItem> = ArrayList<VodItem>().also { l -> series(p) { l.add(it) } }

    /** Hands each series to [emit] as it's read; returns how many. */
    fun series(p: Playlist, emit: (VodItem) -> Unit): Int {
        var n = 0
        val add: (VodItem) -> Unit = { emit(it); n++ }
        val cats = categories(p, "get_series_categories")
        return Net.open(api(p, "get_series")) { input ->
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                if (r.peek() != JsonToken.BEGIN_ARRAY) return@use
                r.beginArray()
                while (r.hasNext()) {
                    var id = ""; var name = ""; var cover = ""; var cat = ""; var added = 0L; var rating = ""; var year = ""
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "series_id" -> id = r.str()
                            "name" -> name = r.str()
                            "cover" -> cover = r.str()
                            "category_id" -> cat = r.str()
                            "last_modified" -> added = r.str().toLongOrNull() ?: 0L
                            "rating" -> rating = r.str()
                            "releaseDate", "release_date" -> year = r.str().take(4)
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (id.isNotBlank()) add(
                        VodItem(
                            key = "${p.id}:s$id", playlistId = p.id, kind = "series", name = name.trim(), logo = cover.ifBlank { null },
                            url = "", seriesId = id, category = cats[cat] ?: "Other", added = added, rating = rating, year = year,
                        )
                    )
                }
                r.endArray()
            }
            n
        }
    }

    private fun info(o: JSONObject?): VodInfo {
        if (o == null) return VodInfo()
        val backdrop = o.optJSONArray("backdrop_path")?.optString(0)?.ifBlank { null }
        return VodInfo(
            plot = o.optString("plot").ifBlank { o.optString("description") },
            genre = o.optString("genre"),
            year = (o.optString("releasedate").ifBlank { o.optString("releaseDate") }).take(4),
            duration = o.optString("duration"),
            cast = o.optString("cast").ifBlank { o.optString("actors") },
            director = o.optString("director"),
            rating = o.optString("rating"),
            image = o.optString("movie_image").ifBlank { o.optString("cover") }.ifBlank { null } ?: backdrop,
        )
    }

    fun movieInfo(p: Playlist, streamId: String): VodInfo =
        info(JSONObject(Net.text(api(p, "get_vod_info", "&vod_id=$streamId"))).optJSONObject("info"))

    fun seriesInfo(p: Playlist, seriesId: String): Pair<VodInfo, List<Episode>> {
        val o = JSONObject(Net.text(api(p, "get_series_info", "&series_id=$seriesId")))
        val base = p.xtreamBase()
        val out = ArrayList<Episode>()
        o.optJSONObject("episodes")?.let { eps ->
            eps.keys().asSequence().sortedBy { it.toIntOrNull() ?: 0 }.forEach { season ->
                val list = eps.optJSONArray(season) ?: return@forEach
                for (i in 0 until list.length()) {
                    val e = list.getJSONObject(i)
                    val id = e.optString("id")
                    val ext = e.optString("container_extension").ifBlank { "mp4" }
                    val ei = e.optJSONObject("info")
                    out.add(
                        Episode(
                            season = season, number = e.optInt("episode_num", i + 1),
                            title = e.optString("title").ifBlank { "Episode ${i + 1}" },
                            url = "$base/series/${p.username}/${p.password}/$id.$ext",
                            key = "${p.id}:e$id",
                            plot = ei?.optString("plot") ?: "",
                            duration = ei?.optString("duration") ?: "",
                        )
                    )
                }
            }
        }
        return info(o.optJSONObject("info")) to out
    }

    fun episodes(p: Playlist, seriesId: String): List<Episode> = seriesInfo(p, seriesId).second
}
