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
    /** Use the guide the provider supplies (Xtream's guide, or the M3U file's url-tvg). */
    val providerGuide: Boolean = true,
    /** Guide (XMLTV) sources the user added for this playlist, in order: the first one with a channel wins. */
    val epgSources: List<EpgSource> = emptyList(),
) {
    val isXtream get() = type == "xtream"

    fun xtreamBase(): String {
        var s = server.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s
    }

    /** The provider's own guide address (Xtream); for M3U it comes from the file's header. */
    fun providerGuideUrl(): String = if (isXtream) "${xtreamBase()}/xmltv.php?username=${enc(username)}&password=${enc(password)}" else ""

    fun guideUrl(): String = when {
        epgUrl.isNotBlank() -> epgUrl.trim()
        else -> providerGuideUrl()
    }

    /** Older versions had one optional guide address that replaced the provider's: make it a source. */
    fun migrated(): Playlist = if (epgUrl.isBlank()) this else copy(
        epgUrl = "",
        providerGuide = if (epgSources.isEmpty()) false else providerGuide,
        epgSources = if (epgSources.any { it.url == epgUrl.trim() }) epgSources else listOf(EpgSource("src-legacy", "Guide address", epgUrl.trim())) + epgSources,
    )

    /** Fields shared by the playlist file and backups (password handled by the caller). */
    fun guideJson(o: JSONObject): JSONObject = o.put("providerGuide", providerGuide)
        .put("epgSources", JSONArray().apply { epgSources.forEach { put(it.toJson()) } })

    companion object {
        fun guideFrom(o: JSONObject, p: Playlist): Playlist = p.copy(
            providerGuide = o.optBoolean("providerGuide", true),
            epgSources = o.optJSONArray("epgSources")?.let { a -> List(a.length()) { EpgSource.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
        ).migrated()
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

/** A guide (XMLTV) address the user added to a playlist. [offsetHours] shifts its times (for guides in another time zone). */
data class EpgSource(val id: String, val name: String, val url: String, val enabled: Boolean = true, val offsetHours: Int = 0) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url).put("enabled", enabled).put("offset", offsetHours)

    companion object {
        fun fromJson(o: JSONObject) = EpgSource(o.getString("id"), o.optString("name"), o.optString("url"), o.optBoolean("enabled", true), o.optInt("offset", 0))
    }
}

/** A channel found in a guide source, for matching channels by hand. */
data class GuideChannel(val id: String, val name: String, val source: String)

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
    /** Sports leagues to show, in order (missing ones are added, on: [LeagueSetting.complete]). */
    val sportLeagues: List<LeagueSetting> = emptyList(),
    val sportsShowUnmatched: Boolean = true,
    val sportsFinalHours: Int = 12,
    val sportsShowScores: Boolean = true,
    val autoUpdateCheck: Boolean = true,
    /** Send the playback log (freezes and errors) to bugs@avidviewer.app automatically, at most once a day (1.5.0). */
    val autoSendPlaybackLog: Boolean = true,
    val myList: List<VodItem> = emptyList(),
    val tmdbKeyEnc: String = "",
    /** SAMBA backup share: address like \\server\share\folder, user, password (encrypted on this device). */
    val smbAddress: String = "",
    val smbUser: String = "",
    val smbPassEnc: String = "",
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
    /** How often the TV guide downloads again, in hours (4, 8, 12 or 24). */
    val guideRefreshHours: Int = 8,
    /** Channels matched to a guide channel by hand: channel key → guide channel id ("" = no guide). */
    val epgMap: Map<String, String> = emptyMap(),
    /** Picture & sound for every channel and title (what the player panel's "All channels" changes). */
    val avDefault: AvChoice = AvChoice(),
    /** Picture & sound remembered per channel (or movie/episode) key, over [avDefault]. */
    val avChannels: Map<String, AvChoice> = emptyMap(),
    val captionStyle: CaptionStyle = CaptionStyle(),
    /** The player panel saves changes for all channels (true) or only the one playing (false). */
    val avForAll: Boolean = false,
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
        o.put("sportLeagues", JSONArray().apply { sportLeagues.forEach { put(it.toJson()) } })
        o.put("sportsShowUnmatched", sportsShowUnmatched); o.put("sportsFinalHours", sportsFinalHours); o.put("sportsShowScores", sportsShowScores)
        o.put("autoUpdateCheck", autoUpdateCheck)
        o.put("autoSendPlaybackLog", autoSendPlaybackLog)
        o.put("myList", JSONArray().apply { myList.forEach { put(it.toJson()) } })
        o.put("tmdbKeyEnc", tmdbKeyEnc)
        o.put("smbAddress", smbAddress).put("smbUser", smbUser).put("smbPassEnc", smbPassEnc)
        o.put("myServices", JSONArray().apply { myServices.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("logo", it.logo ?: "")) } })
        vodPlaylist?.let { o.put("vodPlaylist", it) }
        o.put("vodHiddenCats", JSONArray(vodHiddenCats.toList()))
        o.put("vodHiddenTitles", JSONArray(vodHiddenTitles.toList()))
        o.put("vodCatNames", JSONObject(vodCatNames))
        o.put("vodCatOrder", JSONObject().apply { vodCatOrder.forEach { (k, l) -> put(k, JSONArray(l)) } })
        o.put("lists", JSONArray().apply { lists.forEach { put(it.toJson()) } })
        o.put("guideRefreshHours", guideRefreshHours)
        o.put("epgMap", JSONObject(epgMap))
        o.put("showSuggestions", showSuggestions)
        o.put("dismissedSuggestions", JSONArray(dismissedSuggestions.toList()))
        o.put("avDefault", avDefault.toJson())
        o.put("avChannels", JSONObject().apply { avChannels.forEach { (k, v) -> if (!v.isEmpty) put(k, v.toJson()) } })
        o.put("captionStyle", captionStyle.toJson())
        o.put("avForAll", avForAll)
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
                // Older versions' keyword categories ("sportCats") aren't used any more.
                sportLeagues = o.optJSONArray("sportLeagues")?.let { a -> List(a.length()) { LeagueSetting.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
                sportsShowUnmatched = o.optBoolean("sportsShowUnmatched", true),
                sportsFinalHours = o.optInt("sportsFinalHours", 12).let { if (it in listOf(6, 12, 24)) it else 12 },
                sportsShowScores = o.optBoolean("sportsShowScores", true),
                autoUpdateCheck = o.optBoolean("autoUpdateCheck", true),
                autoSendPlaybackLog = o.optBoolean("autoSendPlaybackLog", true),
                myList = o.optJSONArray("myList")?.let { a -> List(a.length()) { VodItem.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
                tmdbKeyEnc = o.optString("tmdbKeyEnc"),
                smbAddress = o.optString("smbAddress"), smbUser = o.optString("smbUser"), smbPassEnc = o.optString("smbPassEnc"),
                vodPlaylist = o.optString("vodPlaylist").ifBlank { null },
                vodHiddenCats = strList(o.optJSONArray("vodHiddenCats")).toSet(),
                vodHiddenTitles = strList(o.optJSONArray("vodHiddenTitles")).toSet(),
                vodCatNames = strMap(o.optJSONObject("vodCatNames")),
                vodCatOrder = o.optJSONObject("vodCatOrder")?.let { vo -> vo.keys().asSequence().associateWith { strList(vo.getJSONArray(it)) } } ?: emptyMap(),
                lists = o.optJSONArray("lists")?.let { a -> List(a.length()) { ChannelList.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
                guideRefreshHours = o.optInt("guideRefreshHours", 8).let { if (it in listOf(4, 8, 12, 24)) it else 8 },
                epgMap = strMap(o.optJSONObject("epgMap")),
                showSuggestions = o.optBoolean("showSuggestions", true),
                dismissedSuggestions = strList(o.optJSONArray("dismissedSuggestions")).toSet(),
                myServices = o.optJSONArray("myServices")?.let { a -> List(a.length()) { a.getJSONObject(it).let { s -> WatchProvider(s.getInt("id"), s.optString("name"), s.optString("logo").ifBlank { null }) } } } ?: emptyList(),
                avDefault = AvChoice.fromJson(o.optJSONObject("avDefault")),
                avChannels = o.optJSONObject("avChannels")?.let { m -> m.keys().asSequence().associateWith { AvChoice.fromJson(m.optJSONObject(it)) }.filterValues { !it.isEmpty } } ?: emptyMap(),
                captionStyle = CaptionStyle.fromJson(o.optJSONObject("captionStyle")),
                avForAll = o.optBoolean("avForAll", false),
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
