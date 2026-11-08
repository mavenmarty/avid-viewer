package com.avidviewer.tv.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A movie, series or single episode from a provider.
 * kind: "movie" and "episode" play [url] directly; "series" opens its episode list via [seriesId].
 */
data class VodItem(
    val key: String,
    val playlistId: String,
    val kind: String,
    val name: String,
    val logo: String?,
    val url: String,
    val seriesId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("k", key).put("p", playlistId).put("t", kind).put("n", name)
        .put("l", logo ?: "").put("u", url).put("s", seriesId ?: "")

    companion object {
        fun fromJson(o: JSONObject) = VodItem(
            key = o.getString("k"), playlistId = o.getString("p"), kind = o.getString("t"), name = o.getString("n"),
            logo = o.optString("l").ifBlank { null }, url = o.optString("u"), seriesId = o.optString("s").ifBlank { null },
        )
    }
}

data class Episode(val season: String, val number: Int, val title: String, val url: String)

object XtreamVod {
    private fun api(p: Playlist, action: String, extra: String = ""): String {
        val u = java.net.URLEncoder.encode(p.username, "UTF-8")
        val pw = java.net.URLEncoder.encode(p.password, "UTF-8")
        return "${p.xtreamBase()}/player_api.php?username=$u&password=$pw&action=$action$extra"
    }

    fun movies(p: Playlist): List<VodItem> {
        val a = JSONArray(Net.text(api(p, "get_vod_streams")))
        val base = p.xtreamBase()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val id = o.optString("stream_id").ifBlank { return@mapNotNull null }
            val ext = o.optString("container_extension").ifBlank { "mp4" }
            VodItem(
                key = "${p.id}:m$id", playlistId = p.id, kind = "movie", name = o.optString("name").trim(),
                logo = o.optString("stream_icon").ifBlank { null },
                url = "$base/movie/${p.username}/${p.password}/$id.$ext",
            )
        }
    }

    fun series(p: Playlist): List<VodItem> {
        val a = JSONArray(Net.text(api(p, "get_series")))
        return (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val id = o.optString("series_id").ifBlank { return@mapNotNull null }
            VodItem(
                key = "${p.id}:s$id", playlistId = p.id, kind = "series", name = o.optString("name").trim(),
                logo = o.optString("cover").ifBlank { null }, url = "", seriesId = id,
            )
        }
    }

    fun episodes(p: Playlist, seriesId: String): List<Episode> {
        val o = JSONObject(Net.text(api(p, "get_series_info", "&series_id=$seriesId")))
        val eps = o.optJSONObject("episodes") ?: return emptyList()
        val base = p.xtreamBase()
        val out = ArrayList<Episode>()
        eps.keys().asSequence().sortedBy { it.toIntOrNull() ?: 0 }.forEach { season ->
            val list = eps.optJSONArray(season) ?: return@forEach
            for (i in 0 until list.length()) {
                val e = list.getJSONObject(i)
                val id = e.optString("id")
                val ext = e.optString("container_extension").ifBlank { "mp4" }
                out.add(
                    Episode(
                        season = season, number = e.optInt("episode_num", i + 1),
                        title = e.optString("title").ifBlank { "Episode ${i + 1}" },
                        url = "$base/series/${p.username}/${p.password}/$id.$ext",
                    )
                )
            }
        }
        return out
    }
}
