package com.avidviewer.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** A provider the user added: Xtream Codes login or an M3U link. */
data class Playlist(
    val id: String,
    val name: String,
    val type: String, // "xtream" or "m3u"
    val server: String = "",
    val username: String = "",
    val password: String = "", // kept in memory only; stored encrypted
    val m3uUrl: String = "",
    val epgUrl: String = "",
    /** Off: the playlist is kept but hidden everywhere (guide, search, favorites, sports). */
    val enabled: Boolean = true,
    /** Load live channels and the TV guide from this playlist. */
    val liveOn: Boolean = true,
    /** Load movies and series from this playlist. */
    val vodOn: Boolean = true,
) {
    val isXtream get() = type == "xtream"

    fun xtreamBase(): String {
        var s = server.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s
    }

    fun guideUrl(): String = when {
        epgUrl.isNotBlank() -> epgUrl.trim()
        isXtream -> "${xtreamBase()}/xmltv.php?username=${enc(username)}&password=${enc(password)}"
        else -> ""
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

/** One live channel. [key] is unique across playlists. */
data class Channel(
    val key: String,
    val name: String,
    val group: String,
    val logo: String?,
    val url: String,
    val epgId: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("k", key).put("n", name).put("g", group)
        .put("l", logo ?: "").put("u", url).put("e", epgId ?: "")

    companion object {
        fun fromJson(o: JSONObject) = Channel(
            key = o.getString("k"),
            name = o.getString("n"),
            group = o.optString("g", "Uncategorized"),
            logo = o.optString("l").ifBlank { null },
            url = o.getString("u"),
            epgId = o.optString("e").ifBlank { null },
        )
    }
}

/** One guide listing. Times are epoch milliseconds (UTC). */
data class Program(
    val start: Long,
    val end: Long,
    val title: String,
    val subtitle: String?,
    val isNew: Boolean,
)

/** Everything the user can change, saved to the app's private storage. */
data class Prefs(
    val activePlaylist: String? = null,
    val favorites: List<String> = emptyList(),
    val groupNames: Map<String, String> = emptyMap(),
    val channelNames: Map<String, String> = emptyMap(),
    val groupOrder: List<String> = emptyList(),
    val channelOrder: Map<String, List<String>> = emptyMap(),
    val hiddenGroups: Set<String> = emptySet(),
    val hiddenChannels: Set<String> = emptySet(),
    val showClock: Boolean = true,
    val timeZone: String = "auto",
    val streamFormat: String = "ts", // "ts" or "m3u8" for Xtream
    val clearCacheOnExit: Boolean = false,
    val historyPaused: Boolean = false,
    val lastChannel: String? = null,
    val postalCode: String = "",
    val placeName: String = "",
    val placeTz: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val units: String = "F",
    val showWeather: Boolean = true,
    val sportCats: List<SportCat> = SportCat.DEFAULTS,
    val autoUpdateCheck: Boolean = true,
    val myList: List<VodItem> = emptyList(),
    val tmdbKeyEnc: String = "",
    val myServices: List<WatchProvider> = emptyList(),
    /** Suggest local channels from the postal code at the top of Favorites. */
    val showSuggestions: Boolean = true,
    /** Channels the user dismissed from the suggestions. */
    val dismissedSuggestions: Set<String> = emptySet(),
    /** The user's own channel lists, shown after Favorites. */
    val lists: List<ChannelList> = emptyList(),
    /** Playlist that Movies, Series and Discover use (it must have movies & series switched on). */
    val vodPlaylist: String? = null,
    /** Hidden movie/series categories, per playlist: "movie|playlistId|Category" or "series|…". */
    val vodHiddenCats: Set<String> = emptySet(),
    /** Hidden individual movies/series (VodItem.key). */
    val vodHiddenTitles: Set<String> = emptySet(),
    /** Renamed movie/series categories: "movie|Category" → new name. */
    val vodCatNames: Map<String, String> = emptyMap(),
    /** Order of movie/series categories: "movie" or "series" → category names in order. */
    val vodCatOrder: Map<String, List<String>> = emptyMap(),
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("activePlaylist", activePlaylist ?: "")
        o.put("favorites", JSONArray(favorites))
        o.put("groupNames", JSONObject(groupNames))
        o.put("channelNames", JSONObject(channelNames))
        o.put("groupOrder", JSONArray(groupOrder))
        o.put("channelOrder", JSONObject().apply { channelOrder.forEach { (g, l) -> put(g, JSONArray(l)) } })
        o.put("hiddenGroups", JSONArray(hiddenGroups.toList()))
        o.put("hiddenChannels", JSONArray(hiddenChannels.toList()))
        o.put("showClock", showClock)
        o.put("timeZone", timeZone)
        o.put("streamFormat", streamFormat)
        o.put("clearCacheOnExit", clearCacheOnExit)
        o.put("historyPaused", historyPaused)
        o.put("lastChannel", lastChannel ?: "")
        o.put("postalCode", postalCode).put("placeName", placeName).put("placeTz", placeTz)
        o.put("lat", lat).put("lon", lon).put("units", units).put("showWeather", showWeather)
        o.put("sportCats", JSONArray().apply { sportCats.forEach { put(it.toJson()) } })
        o.put("autoUpdateCheck", autoUpdateCheck)
        o.put("myList", JSONArray().apply { myList.forEach { put(it.toJson()) } })
        o.put("tmdbKeyEnc", tmdbKeyEnc)
        o.put("myServices", JSONArray().apply { myServices.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("logo", it.logo ?: "")) } })
        vodPlaylist?.let { o.put("vodPlaylist", it) }
        o.put("vodHiddenCats", JSONArray(vodHiddenCats.toList()))
        o.put("vodHiddenTitles", JSONArray(vodHiddenTitles.toList()))
        o.put("vodCatNames", JSONObject(vodCatNames))
        o.put("vodCatOrder", JSONObject().apply { vodCatOrder.forEach { (k, l) -> put(k, JSONArray(l)) } })
        o.put("lists", JSONArray().apply { lists.forEach { put(it.toJson()) } })
        o.put("showSuggestions", showSuggestions)
        o.put("dismissedSuggestions", JSONArray(dismissedSuggestions.toList()))
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Prefs {
            fun strList(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }
            fun strMap(m: JSONObject?): Map<String, String> =
                if (m == null) emptyMap() else m.keys().asSequence().associateWith { m.getString(it) }
            val co = o.optJSONObject("channelOrder")
            return Prefs(
                activePlaylist = o.optString("activePlaylist").ifBlank { null },
                favorites = strList(o.optJSONArray("favorites")),
                groupNames = strMap(o.optJSONObject("groupNames")),
                channelNames = strMap(o.optJSONObject("channelNames")),
                groupOrder = strList(o.optJSONArray("groupOrder")),
                channelOrder = if (co == null) emptyMap() else co.keys().asSequence().associateWith { strList(co.getJSONArray(it)) },
                hiddenGroups = strList(o.optJSONArray("hiddenGroups")).toSet(),
                hiddenChannels = strList(o.optJSONArray("hiddenChannels")).toSet(),
                showClock = o.optBoolean("showClock", true),
                timeZone = o.optString("timeZone", "auto"),
                streamFormat = o.optString("streamFormat", "ts"),
                clearCacheOnExit = o.optBoolean("clearCacheOnExit", false),
                historyPaused = o.optBoolean("historyPaused", false),
                lastChannel = o.optString("lastChannel").ifBlank { null },
                postalCode = o.optString("postalCode"),
                placeName = o.optString("placeName"),
                placeTz = o.optString("placeTz"),
                lat = o.optDouble("lat", 0.0),
                lon = o.optDouble("lon", 0.0),
                units = o.optString("units", "F"),
                showWeather = o.optBoolean("showWeather", true),
                sportCats = o.optJSONArray("sportCats")?.let { a -> List(a.length()) { SportCat.fromJson(a.getJSONObject(it)) } } ?: SportCat.DEFAULTS,
                autoUpdateCheck = o.optBoolean("autoUpdateCheck", true),
                myList = o.optJSONArray("myList")?.let { a -> List(a.length()) { VodItem.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
                tmdbKeyEnc = o.optString("tmdbKeyEnc"),
                vodPlaylist = o.optString("vodPlaylist").ifBlank { null },
                vodHiddenCats = strList(o.optJSONArray("vodHiddenCats")).toSet(),
                vodHiddenTitles = strList(o.optJSONArray("vodHiddenTitles")).toSet(),
                vodCatNames = strMap(o.optJSONObject("vodCatNames")),
                vodCatOrder = o.optJSONObject("vodCatOrder")?.let { vo -> vo.keys().asSequence().associateWith { strList(vo.getJSONArray(it)) } } ?: emptyMap(),
                lists = o.optJSONArray("lists")?.let { a -> List(a.length()) { ChannelList.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
                showSuggestions = o.optBoolean("showSuggestions", true),
                dismissedSuggestions = strList(o.optJSONArray("dismissedSuggestions")).toSet(),
                myServices = o.optJSONArray("myServices")?.let { a -> List(a.length()) { a.getJSONObject(it).let { s -> WatchProvider(s.getInt("id"), s.optString("name"), s.optString("logo").ifBlank { null }) } } } ?: emptyList(),
            )
        }
    }
}

/** A channel collection the user made, like Favorites. [keys] can come from any playlist. */
data class ChannelList(val id: String, val name: String, val keys: List<String> = emptyList(), val hidden: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("keys", JSONArray(keys)).put("hidden", hidden)

    companion object {
        fun fromJson(o: JSONObject) = ChannelList(
            o.getString("id"), o.optString("name"),
            o.optJSONArray("keys")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
            o.optBoolean("hidden", false),
        )
    }
}

data class HistoryEntry(val key: String, val time: Long, val seconds: Long)
