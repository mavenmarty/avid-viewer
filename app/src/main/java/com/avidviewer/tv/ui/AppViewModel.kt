package com.avidviewer.tv.ui

import android.app.Application
import android.text.format.DateFormat
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.avidviewer.tv.data.Channel
import com.avidviewer.tv.data.HistoryEntry
import com.avidviewer.tv.data.M3u
import com.avidviewer.tv.data.Playlist
import com.avidviewer.tv.data.Prefs
import com.avidviewer.tv.data.Program
import com.avidviewer.tv.data.Store
import com.avidviewer.tv.data.USER_AGENT
import com.avidviewer.tv.data.Episode
import com.avidviewer.tv.data.VodInfo
import com.avidviewer.tv.data.Updater
import com.avidviewer.tv.data.Scores
import com.avidviewer.tv.data.SportCat
import com.avidviewer.tv.data.SportEvent
import com.avidviewer.tv.data.VodItem
import com.avidviewer.tv.data.XtreamVod
import com.avidviewer.tv.data.Weather
import com.avidviewer.tv.data.WeatherService
import com.avidviewer.tv.data.Xmltv
import com.avidviewer.tv.data.Xtream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

enum class Screen { SEARCH, FAVORITES, LIVE, GUIDE, SPORTS, MOVIES, SERIES, MANAGE, PLAYLISTS, SETTINGS, PLAYER }

/** Where full-screen Up/Down and Channel Up/Down move: the group the user came from, or Favorites. */
enum class PlayContext { GROUP, FAVORITES, SPORTS }

/**
 * Left navigation depth.
 * FULL: menu with icons and labels. COLLAPSED: icons only (plus the playlist tree on Live TV / TV Guide).
 * GROUPS: menu hidden; the active playlist's categories are the left navigation.
 */
enum class NavMode { FULL, COLLAPSED, GROUPS }

data class GroupInfo(val raw: String, val name: String, val count: Int, val hidden: Boolean)

/** One search result. Channel and program hits tune [channelKey]; movie/episode hits play [vod]; series hits open episodes. */
data class Hit(val kind: String, val playlistId: String, val title: String, val detail: String, val logo: String?, val channelKey: String? = null, val vod: VodItem? = null)

data class PlaylistHits(val playlist: Playlist, val stations: List<Hit>, val programs: List<Hit>, val movies: List<Hit>, val series: List<Hit>) {
    val total get() = stations.size + programs.size + movies.size + series.size
}

@OptIn(UnstableApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)

    var playlists by mutableStateOf(store.loadPlaylists()); private set
    var prefs by mutableStateOf(store.loadPrefs()); private set
    var channels by mutableStateOf<List<Channel>>(emptyList()); private set
    var guide by mutableStateOf<Map<String, List<Program>>>(emptyMap()); private set
    var history by mutableStateOf(store.loadHistory()); private set

    var screen by mutableStateOf(Screen.GUIDE)
    var navMode by mutableStateOf(NavMode.GROUPS)
    var expandedPlaylist by mutableStateOf<String?>(null)
    var playlistGroups by mutableStateOf<Map<String, List<GroupInfo>>>(emptyMap()); private set
    var lastScreen by mutableStateOf(Screen.GUIDE)
    private var startupPreviewDone = false
    /** Bumped when the TV Guide should scroll to and focus the current channel. */
    var guideFocusTick by mutableStateOf(0)
    var selectedGroup by mutableStateOf<String?>(null)
    var currentKey by mutableStateOf<String?>(null); private set
    var playContext by mutableStateOf(PlayContext.GROUP)
    var osd by mutableStateOf<String?>(null); private set
    var status by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set
    var playerError by mutableStateOf<String?>(null); private set
    var now by mutableLongStateOf(System.currentTimeMillis()); private set
    var weather by mutableStateOf<Weather?>(null); private set
    var vod by mutableStateOf<List<VodItem>>(emptyList()); private set
    var vodPlaying by mutableStateOf<VodItem?>(null); private set
    var vodError by mutableStateOf<String?>(null); private set
    var vodCategory by mutableStateOf(mapOf("movie" to "All", "series" to "All"))
    var vodDetail by mutableStateOf<VodItem?>(null); private set
    var vodInfo by mutableStateOf<VodInfo?>(null); private set
    var detailEpisodes by mutableStateOf<List<Episode>?>(null); private set
    var detailSeason by mutableStateOf<String?>(null)
    private var resume: Map<String, Long> = store.loadResume()
    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<PlaylistHits>?>(null); private set
    var searching by mutableStateOf(false); private set
    var seriesOpen by mutableStateOf<VodItem?>(null); private set
    var episodes by mutableStateOf<List<Episode>?>(null); private set
    var sportEvents by mutableStateOf<List<SportEvent>>(emptyList()); private set
    var sportCat by mutableStateOf<String?>(null) // null = All
    var settingsSection by mutableStateOf("general")
    var update by mutableStateOf<Updater.Release?>(null); private set
    var updateStatus by mutableStateOf<String?>(null); private set
    var updateBusy by mutableStateOf(false); private set
    var updatePromptDismissed by mutableStateOf(false)
    val installed: Pair<Long, String> = runCatching { Updater.installedVersion(app) }.getOrDefault(0L to "")
    var selectedEvent by mutableStateOf<String?>(null)
    private var sportsPlayCat: String? = null
    private var scoreCache: Map<String, List<Scores.Game>> = emptyMap()
    private val otherData = HashMap<String, Triple<List<Channel>, Map<String, List<Program>>, List<VodItem>>>()
    var weatherError by mutableStateOf<String?>(null); private set
    var postalStatus by mutableStateOf<String?>(null); private set

    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(app).setDataSourceFactory(
                DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT).setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
            )
        )
        .build()

    private var osdJob: Job? = null
    private var watchStart = 0L
    private var watchKey: String? = null

    init {
        if (prefs.clearCacheOnExit) store.clearCache() // catches exits Android didn't report
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playerError = "Can't play this channel (${error.errorCodeName}). Try another channel, or switch Stream format in Settings."
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) playerError = null
            }
        })
        viewModelScope.launch {
            while (true) {
                now = System.currentTimeMillis()
                delay(15_000)
            }
        }
        viewModelScope.launch {
            var n = 0
            while (true) {
                // Scores every 90 s while Sports is on screen, otherwise every 6 minutes.
                val sportsVisible = screen == Screen.SPORTS || (screen == Screen.PLAYER && playContext == PlayContext.SPORTS)
                if (sportsVisible || n % 4 == 0) refreshScores()
                rebuildSportEvents()
                n++
                delay(90_000)
            }
        }
        viewModelScope.launch {
            while (true) {
                refreshWeather()
                delay(30 * 60 * 1000L) // every 30 minutes
            }
        }
        Updater.onStatus = { msg -> viewModelScope.launch { updateStatus = msg; updateBusy = false } }
        if (prefs.autoUpdateCheck) checkForUpdate(quiet = true)
        if (playlists.isEmpty()) { screen = Screen.PLAYLISTS; navMode = NavMode.COLLAPSED }
        activePlaylist()?.let { loadActive(refresh = false) }
    }

    // ---------------- time ----------------
    /** "auto" uses the postal code's zone when one is set, otherwise the device's zone. */
    val timeZone: TimeZone
        get() = when {
            prefs.timeZone == "device" -> TimeZone.getDefault()
            prefs.timeZone == "auto" && prefs.placeTz.isNotBlank() -> TimeZone.getTimeZone(prefs.placeTz)
            prefs.timeZone == "auto" -> TimeZone.getDefault()
            else -> TimeZone.getTimeZone(prefs.timeZone)
        }

    fun timeZoneSource(): String = when {
        prefs.timeZone == "auto" && prefs.placeTz.isNotBlank() -> "From postal code ${prefs.postalCode} (${prefs.placeName})"
        prefs.timeZone == "auto" || prefs.timeZone == "device" -> "From this device"
        else -> "Set manually"
    }

    // ---------------- postal code & weather ----------------
    fun setPostalCode(code: String) {
        val c = code.trim().uppercase()
        if (c.isEmpty()) {
            updatePrefs { it.copy(postalCode = "", placeName = "", placeTz = "", lat = 0.0, lon = 0.0) }
            weather = null
            postalStatus = "Postal code removed."
            return
        }
        viewModelScope.launch {
            postalStatus = "Looking up $c…"
            val place = try {
                withContext(Dispatchers.IO) { WeatherService.lookup(c) }
            } catch (e: Exception) {
                postalStatus = "Couldn't look up $c: ${e.message}"
                return@launch
            }
            if (place == null) {
                postalStatus = "No place found for $c. Check the code and try again."
                return@launch
            }
            updatePrefs { it.copy(postalCode = c, placeName = place.name, placeTz = place.timeZone, lat = place.lat, lon = place.lon) }
            postalStatus = "Found ${place.name}."
            refreshWeather()
        }
    }

    fun setUnits(u: String) {
        updatePrefs { it.copy(units = u) }
        viewModelScope.launch { refreshWeather() }
    }

    private suspend fun refreshWeather() {
        val p = prefs
        if (!p.showWeather || p.postalCode.isBlank() || (p.lat == 0.0 && p.lon == 0.0)) return
        try {
            weather = withContext(Dispatchers.IO) { WeatherService.current(p.lat, p.lon, p.units) }
            weatherError = null
        } catch (e: Exception) {
            weatherError = "Weather unavailable"
        }
    }

    fun toggleWeather() {
        updatePrefs { it.copy(showWeather = !it.showWeather) }
        if (prefs.showWeather) viewModelScope.launch { refreshWeather() }
    }

    /** e.g. "62°F · Partly cloudy", or null when weather is off or not set up. */
    fun weatherLine(): String? {
        if (!prefs.showWeather || prefs.postalCode.isBlank()) return null
        val w = weather ?: return weatherError
        return "${w.temp}°${w.units}" + if (w.condition.isNotBlank()) " · ${w.condition}" else ""
    }

    fun fmtTime(ms: Long): String {
        val pattern = if (DateFormat.is24HourFormat(getApplication())) "HH:mm" else "h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = this@AppViewModel.timeZone }.format(ms)
    }

    fun fmtDate(ms: Long): String =
        SimpleDateFormat("EEE, MMM d", Locale.getDefault()).apply { timeZone = this@AppViewModel.timeZone }.format(ms)

    fun zoneLabel(): String = timeZone.getDisplayName(timeZone.inDaylightTime(java.util.Date(now)), TimeZone.SHORT)

    // ---------------- playlists ----------------
    fun activePlaylist(): Playlist? = playlists.firstOrNull { it.id == prefs.activePlaylist } ?: playlists.firstOrNull()

    fun addPlaylist(name: String, type: String, server: String, user: String, pass: String, m3u: String, epg: String) {
        val p = Playlist(
            id = UUID.randomUUID().toString().take(8),
            name = name.ifBlank { if (type == "xtream") "My provider" else "M3U playlist" },
            type = type, server = server.trim(), username = user.trim(), password = pass,
            m3uUrl = m3u.trim(), epgUrl = epg.trim(),
        )
        viewModelScope.launch {
            busy = true
            status = "Checking ${p.name}…"
            val problem = withContext(Dispatchers.IO) {
                if (p.isXtream) Xtream.checkLogin(p) else if (p.m3uUrl.isBlank()) "Enter the playlist link." else null
            }
            if (problem != null) {
                status = problem
                busy = false
                return@launch
            }
            playlists = playlists + p
            store.savePlaylists(playlists)
            updatePrefs { it.copy(activePlaylist = p.id) }
            busy = false
            loadActive(refresh = true)
        }
    }

    fun removePlaylist(id: String) {
        playlists = playlists.filterNot { it.id == id }
        store.savePlaylists(playlists)
        store.deletePlaylistData(id)
        if (prefs.activePlaylist == id) {
            updatePrefs { it.copy(activePlaylist = playlists.firstOrNull()?.id) }
            channels = emptyList(); guide = emptyMap()
            if (playlists.isNotEmpty()) loadActive(refresh = false)
        }
    }

    fun refreshPlaylist(id: String) {
        if (prefs.activePlaylist != id) updatePrefs { it.copy(activePlaylist = id) }
        loadActive(refresh = true)
    }

    fun setActive(id: String) {
        updatePrefs { it.copy(activePlaylist = id) }
        loadActive(refresh = false)
    }

    /** Loads channels and guide from storage, downloading when missing, stale or [refresh] is set. */
    fun loadActive(refresh: Boolean) {
        val p = activePlaylist() ?: return
        viewModelScope.launch {
            busy = true
            val cached = withContext(Dispatchers.IO) { store.loadChannels(p.id) }
            if (cached.isNotEmpty()) applyChannels(cached)
            if (refresh || cached.isEmpty()) {
                status = "Loading channels from ${p.name}…"
                try {
                    val m3uVod = ArrayList<VodItem>()
                    val list = withContext(Dispatchers.IO) {
                        if (p.isXtream) Xtream.liveChannels(p, prefs.streamFormat) else M3u.channels(p, m3uVod)
                    }
                    withContext(Dispatchers.IO) { store.saveChannels(p.id, list) }
                    applyChannels(list)
                    status = "Loaded ${list.size} channels."
                    otherData.remove(p.id)
                    if (!p.isXtream) {
                        withContext(Dispatchers.IO) { store.saveVod(p.id, m3uVod) }
                        vod = m3uVod
                    }
                } catch (e: Exception) {
                    status = if (cached.isEmpty()) "Couldn't load channels: ${e.message}" else "Couldn't refresh, showing saved channels."
                }
            }
            val savedVod = withContext(Dispatchers.IO) { store.loadVod(p.id) }
            if (savedVod.isNotEmpty()) vod = savedVod
            // Re-download when asked, when nothing is saved, or when the saved list predates categories.
            val oldFormat = savedVod.isNotEmpty() && savedVod.all { it.category.isBlank() }
            if (p.isXtream && (refresh || savedVod.isEmpty() || oldFormat)) {
                status = "Loading movies…"
                val movies = withContext(Dispatchers.IO) { runCatching { XtreamVod.movies(p) } }
                status = "Loading series…"
                val series = withContext(Dispatchers.IO) { runCatching { XtreamVod.series(p) } }
                val list = movies.getOrDefault(emptyList()) + series.getOrDefault(emptyList())
                if (list.isNotEmpty()) {
                    withContext(Dispatchers.IO) { store.saveVod(p.id, list) }
                    vod = list
                    otherData.remove(p.id)
                }
                vodError = listOfNotNull(
                    movies.exceptionOrNull()?.let { "Movies couldn't load: ${it.message}" },
                    series.exceptionOrNull()?.let { "Series couldn't load: ${it.message}" },
                ).joinToString("\n").ifBlank { null }
                status = vodError ?: "Loaded ${movies.getOrDefault(emptyList()).size} movies and ${series.getOrDefault(emptyList()).size} series."
            }
            val (savedGuide, savedAt) = withContext(Dispatchers.IO) { store.loadGuide(p.id) }
            if (savedGuide.isNotEmpty()) guide = savedGuide
            val stale = System.currentTimeMillis() - savedAt > 8 * 3600 * 1000L
            if (refresh || stale || savedGuide.isEmpty()) refreshGuide(p)
            busy = false
        }
    }

    private suspend fun refreshGuide(p: Playlist) {
        val url = p.guideUrl().ifBlank { withContext(Dispatchers.IO) { M3u.headerGuideUrl(p) } ?: "" }
        if (url.isBlank()) return
        val wanted = channels.mapNotNull { it.epgId }.toSet()
        if (wanted.isEmpty()) return
        status = "Loading TV guide…"
        try {
            val from = System.currentTimeMillis() - 3 * 3600 * 1000L
            val to = System.currentTimeMillis() + 36 * 3600 * 1000L
            val g = withContext(Dispatchers.IO) { Xmltv.read(url, wanted, from, to) }
            withContext(Dispatchers.IO) { store.saveGuide(p.id, g) }
            guide = g
            rebuildSportEvents()
            status = "Guide loaded for ${g.size} channels."
        } catch (e: Exception) {
            status = "Couldn't load the TV guide: ${e.message}"
        }
    }

    private fun applyChannels(list: List<Channel>) {
        channels = list
        val gs = groups()
        if (currentKey == null) currentKey = prefs.lastChannel?.takeIf { k -> list.any { it.key == k } } ?: list.firstOrNull()?.key
        // Open on the last channel's category in the guide.
        val lastGroup = channel(currentKey)?.group
        if (selectedGroup == null || gs.none { it.raw == selectedGroup }) {
            selectedGroup = lastGroup?.takeIf { g -> gs.any { it.raw == g } } ?: gs.firstOrNull()?.raw
        }
        if (!startupPreviewDone && currentKey != null) {
            startupPreviewDone = true
            preview(currentKey) // the last channel plays in the guide's preview window
            guideFocusTick++
        }
    }

    // ---------------- prefs ----------------
    fun updatePrefs(f: (Prefs) -> Prefs) {
        prefs = f(prefs)
        store.savePrefs(prefs)
    }

    // ---------------- groups & channels ----------------
    fun groupName(raw: String) = prefs.groupNames[raw] ?: raw
    fun channelName(c: Channel) = prefs.channelNames[c.key] ?: c.name

    fun groups(includeHidden: Boolean = false): List<GroupInfo> {
        val counts = LinkedHashMap<String, Int>()
        channels.forEach { counts[it.group] = (counts[it.group] ?: 0) + 1 }
        val ordered = prefs.groupOrder.filter { it in counts } + counts.keys.filterNot { it in prefs.groupOrder }
        return ordered.map { GroupInfo(it, groupName(it), counts[it] ?: 0, it in prefs.hiddenGroups) }
            .filter { includeHidden || !it.hidden }
    }

    fun channelsIn(group: String?): List<Channel> {
        if (group == null) return emptyList()
        val list = channels.filter { it.group == group }
        val order = prefs.channelOrder[group] ?: return list
        val byKey = list.associateBy { it.key }
        return order.mapNotNull { byKey[it] } + list.filterNot { it.key in order }
    }

    fun channel(key: String?): Channel? = channels.firstOrNull { it.key == key }

    /** Channel numbers follow the user's group and channel order, skipping hidden groups. */
    fun numbers(): Map<String, Int> {
        val m = HashMap<String, Int>()
        var n = 1
        groups().forEach { g -> channelsIn(g.raw).forEach { m[it.key] = n++ } }
        return m
    }

    fun moveGroup(raw: String, dir: Int) {
        val order = groups(includeHidden = true).map { it.raw }.toMutableList()
        val i = order.indexOf(raw); val j = i + dir
        if (i < 0 || j !in order.indices) return
        order[i] = order[j].also { order[j] = order[i] }
        updatePrefs { it.copy(groupOrder = order) }
    }

    fun moveChannel(key: String, dir: Int) {
        val c = channel(key) ?: return
        val order = channelsIn(c.group).map { it.key }.toMutableList()
        val i = order.indexOf(key); val j = i + dir
        if (i < 0 || j !in order.indices) return
        order[i] = order[j].also { order[j] = order[i] }
        updatePrefs { it.copy(channelOrder = it.channelOrder + (c.group to order)) }
    }

    fun renameGroup(raw: String, name: String) = updatePrefs {
        it.copy(groupNames = if (name.isBlank() || name == raw) it.groupNames - raw else it.groupNames + (raw to name.trim()))
    }

    fun renameChannel(key: String, name: String) {
        val c = channel(key) ?: return
        updatePrefs { it.copy(channelNames = if (name.isBlank() || name == c.name) it.channelNames - key else it.channelNames + (key to name.trim())) }
    }

    fun toggleHidden(raw: String) = updatePrefs {
        it.copy(hiddenGroups = if (raw in it.hiddenGroups) it.hiddenGroups - raw else it.hiddenGroups + raw)
    }

    fun isFavorite(key: String?) = key != null && key in prefs.favorites

    fun toggleFavorite(key: String?) {
        key ?: return
        updatePrefs { it.copy(favorites = if (key in it.favorites) it.favorites - key else it.favorites + key) }
    }

    fun favoriteChannels(): List<Channel> = prefs.favorites.mapNotNull { channel(it) }

    /** Favorites from every playlist (a favorite added from search may belong to another playlist). */
    var otherFavorites by mutableStateOf<List<Pair<Playlist, Channel>>>(emptyList()); private set

    fun loadOtherFavorites() {
        val active = activePlaylist()?.id
        viewModelScope.launch {
            val out = ArrayList<Pair<Playlist, Channel>>()
            for (p in playlists) {
                if (p.id == active || prefs.favorites.none { it.startsWith(p.id + ":") }) continue
                val chs = dataFor(p).first.associateBy { it.key }
                prefs.favorites.filter { it.startsWith(p.id + ":") }.mapNotNull { chs[it] }.forEach { out.add(p to it) }
            }
            otherFavorites = out
        }
    }

    fun playFavoriteFrom(pid: String, key: String) {
        viewModelScope.launch {
            switchPlaylist(pid)
            openFullScreen(key, PlayContext.FAVORITES)
        }
    }

    // ---------------- My List (movies & series) ----------------
    fun inMyList(key: String?) = key != null && prefs.myList.any { it.key == key }

    fun toggleMyList(item: VodItem) = updatePrefs {
        it.copy(myList = if (it.myList.any { x -> x.key == item.key }) it.myList.filterNot { x -> x.key == item.key } else it.myList + item)
    }

    // ---------------- guide ----------------
    fun programs(c: Channel?): List<Program> = c?.epgId?.let { guide[it] } ?: emptyList()

    fun nowNext(c: Channel?): Pair<Program?, Program?> {
        val list = programs(c)
        val i = list.indexOfFirst { now >= it.start && now < it.end }
        if (i < 0) return null to list.firstOrNull { it.start > now }
        return list[i] to list.getOrNull(i + 1)
    }

    // ---------------- playback ----------------
    fun preview(key: String?) {
        val c = channel(key) ?: return
        if (currentKey == key && player.playbackState != Player.STATE_IDLE && player.mediaItemCount > 0 && playerError == null) return
        start(c)
    }

    /** True when [key] is the channel already playing in the preview. */
    fun isPreviewing(key: String?) = key != null && currentKey == key && vodPlaying == null && player.mediaItemCount > 0 && playerError == null

    /** First OK on a channel plays it in the preview window; OK again on the same channel goes full screen. */
    fun selectChannel(key: String?, ctx: PlayContext) {
        if (isPreviewing(key)) openFullScreen(key, ctx) else preview(key)
    }

    fun openFullScreen(key: String?, ctx: PlayContext) {
        val c = channel(key) ?: return
        playContext = ctx
        if (ctx == PlayContext.SPORTS) sportsPlayCat = sportCat
        if (ctx == PlayContext.GROUP) selectedGroup = c.group
        if (screen != Screen.PLAYER) lastScreen = screen
        if (currentKey != key || player.mediaItemCount == 0 || playerError != null) start(c)
        screen = Screen.PLAYER
        flashOsd(contextLabel())
    }

    fun closeFullScreen() {
        osd = null
        if (vodPlaying != null) {
            saveResumePoint()
            player.stop()
            player.clearMediaItems()
            vodPlaying = null
            screen = lastScreen
            return
        }
        if (playContext == PlayContext.GROUP) {
            // Back from live TV: the guide, on this channel's category, with the channel focused.
            channel(currentKey)?.let { selectedGroup = it.group }
            screen = Screen.GUIDE
            navMode = NavMode.GROUPS
            guideFocusTick++
        } else {
            screen = lastScreen
        }
    }

    // ---------------- app updates ----------------
    fun checkForUpdate(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) updateStatus = "Checking for updates…"
            val r = runCatching { withContext(Dispatchers.IO) { Updater.latest() } }
            r.onSuccess { rel ->
                update = rel?.takeIf { it.versionCode > installed.first }
                updateStatus = when {
                    rel == null -> if (quiet) null else "Couldn't read the latest release."
                    update != null -> "Version ${rel.versionName} is available."
                    else -> if (quiet) null else "You have the latest version."
                }
            }.onFailure { if (!quiet) updateStatus = "Couldn't check for updates: ${it.message}" }
        }
    }

    fun installUpdate() {
        val rel = update ?: return
        val ctx = getApplication<Application>()
        if (!Updater.canInstall(ctx)) {
            updateStatus = "Allow Avid Viewer to install apps, then choose Install again. On Fire TV: Settings › My Fire TV › Developer options › Install unknown apps › Avid Viewer."
            Updater.permissionIntent(ctx)?.let { runCatching { ctx.startActivity(it) } }
            return
        }
        viewModelScope.launch {
            updateBusy = true
            try {
                val file = withContext(Dispatchers.IO) { Updater.download(ctx, rel.apkUrl) { pct -> viewModelScope.launch { updateStatus = "Downloading ${rel.versionName}… $pct%" } } }
                updateStatus = "Installing…"
                withContext(Dispatchers.IO) { Updater.install(ctx, file) }
            } catch (e: Exception) {
                updateStatus = "Update failed: ${e.message}"
                updateBusy = false
            }
        }
    }

    // ---------------- sports ----------------
    fun enabledCats(): List<SportCat> = prefs.sportCats.filter { it.on }

    private val noEvent = Regex("(?i)no event|off air|offline|no programming|to be announced|\\btba\\b|coming soon|stay tuned|event schedule|no games?")
    private val matchup = Regex("(?i) at | vs\\.? | v\\. | @ ")

    private suspend fun refreshScores() {
        val cats = enabledCats().map { it.id }.filter { Scores.supports(it) }
        if (cats.isEmpty()) return
        val m = HashMap<String, List<Scores.Game>>(scoreCache)
        for (c in cats) {
            runCatching { withContext(Dispatchers.IO) { Scores.fetch(c) } }.onSuccess { m[c] = it }
        }
        scoreCache = m
    }

    private suspend fun rebuildSportEvents() {
        val cats = enabledCats(); val chs = channels; val g = guide; val scores = scoreCache
        if (chs.isEmpty()) { sportEvents = emptyList(); return }
        val names = chs.associate { it.key to channelName(it) }
        val groupsByRaw = chs.associate { it.group to groupName(it.group) }
        sportEvents = withContext(Dispatchers.Default) {
            val t = System.currentTimeMillis()
            val from = t - 12 * 3600 * 1000L; val to = t + 48 * 3600 * 1000L
            val out = LinkedHashMap<String, SportEvent>()
            for (cat in cats) {
                val kws = cat.keywords.map { it.lowercase() }.filter { it.isNotBlank() }
                if (kws.isEmpty()) continue
                fun hasKw(s: String) = kws.any { s.contains(it) }
                for (c in chs) {
                    val catChannel = hasKw(((groupsByRaw[c.group] ?: c.group) + " " + c.group + " " + (names[c.key] ?: c.name)).lowercase())
                    val progs = c.epgId?.let { g[it] } ?: emptyList()
                    if (catChannel && progs.isEmpty()) continue
                    for (p in progs) {
                        if (p.end <= from || p.start >= to) continue
                        val text = (p.title + " " + (p.subtitle ?: "")).lowercase()
                        val isEvent = if (catChannel) !noEvent.containsMatchIn(p.title) else hasKw(text) && (matchup.containsMatchIn(p.title) || matchup.containsMatchIn(p.subtitle ?: ""))
                        if (!isEvent) continue
                        val title = if (p.subtitle != null && matchup.containsMatchIn(p.subtitle) && !matchup.containsMatchIn(p.title)) p.subtitle else p.title
                        val key = cat.id + "|" + title.lowercase().replace(Regex("[^a-z0-9]"), "") + "|" + p.start / 60000
                        val prev = out[key]
                        out[key] = if (prev == null) SportEvent(key, cat.id, title, p.start, p.end, listOf(c.key), scores[cat.id]?.let { Scores.match(title, it) })
                        else prev.copy(channelKeys = prev.channelKeys + c.key)
                    }
                }
            }
            out.values.sortedBy { it.start }
        }
    }

    fun sportsVisibleEvents(): List<SportEvent> = sportEvents.filter { sportCat == null || it.catId == sportCat }

    fun updateSportCats(list: List<SportCat>) {
        updatePrefs { it.copy(sportCats = list) }
        if (sportCat != null && list.none { it.id == sportCat && it.on }) sportCat = null
        viewModelScope.launch { refreshScores(); rebuildSportEvents() }
    }

    fun openSportsScreen() {
        screen = Screen.SPORTS
        viewModelScope.launch { refreshScores(); rebuildSportEvents() }
    }

    // ---------------- search (all playlists) ----------------
    private suspend fun dataFor(p: Playlist): Triple<List<Channel>, Map<String, List<Program>>, List<VodItem>> {
        if (p.id == activePlaylist()?.id) return Triple(channels, guide, vod)
        otherData[p.id]?.let { return it }
        val t = withContext(Dispatchers.IO) { Triple(store.loadChannels(p.id), store.loadGuide(p.id).first, store.loadVod(p.id)) }
        otherData[p.id] = t
        return t
    }

    fun search(query: String) {
        val q = query.trim()
        searchQuery = query
        if (q.length < 2) { searchResults = null; return }
        viewModelScope.launch {
            searching = true
            val needle = q.lowercase()
            val nowMs = System.currentTimeMillis()
            val out = ArrayList<PlaylistHits>()
            for (p in playlists) {
                val (chs, g, v) = dataFor(p)
                val res = withContext(Dispatchers.Default) {
                    val names = if (p.id == activePlaylist()?.id) chs.associate { it.key to channelName(it) } else chs.associate { it.key to it.name }
                    val stations = chs.filter { (names[it.key] ?: it.name).lowercase().contains(needle) }.take(50)
                        .map { Hit("channel", p.id, names[it.key] ?: it.name, groupName(it.group), it.logo, channelKey = it.key) }
                    val progs = ArrayList<Pair<Long, Hit>>()
                    for (c in chs) {
                        val list = c.epgId?.let { g[it] } ?: continue
                        for (pr in list) {
                            if (pr.end <= nowMs) continue
                            if (pr.title.lowercase().contains(needle) || pr.subtitle?.lowercase()?.contains(needle) == true) {
                                val whenText = if (pr.start <= nowMs) "On now" else "${dayLabel(pr.start)} ${fmtTime(pr.start)}"
                                progs.add(pr.start to Hit("program", p.id, pr.title + (pr.subtitle?.let { " · $it" } ?: ""), "${names[c.key] ?: c.name} · $whenText", c.logo, channelKey = c.key))
                            }
                        }
                    }
                    val vodHits = v.filter { it.name.lowercase().contains(needle) }
                    PlaylistHits(
                        playlist = p,
                        stations = stations,
                        programs = progs.sortedBy { it.first }.take(60).map { it.second },
                        movies = vodHits.filter { it.kind == "movie" }.take(50).map { Hit("movie", p.id, it.name, "Movie", it.logo, vod = it) },
                        series = vodHits.filter { it.kind != "movie" }.take(50).map { Hit(it.kind, p.id, it.name, if (it.kind == "series") "Series" else "Episode", it.logo, vod = it) },
                    )
                }
                out.add(res)
            }
            searchResults = out
            searching = false
        }
    }

    fun dayLabel(ms: Long): String {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = this@AppViewModel.timeZone }
        val d = f.format(ms); val today = f.format(now); val tomorrow = f.format(now + 24 * 3600 * 1000L)
        return when (d) {
            today -> "Today"
            tomorrow -> "Tomorrow"
            else -> SimpleDateFormat("EEE", Locale.getDefault()).apply { timeZone = this@AppViewModel.timeZone }.format(ms)
        }
    }

    /** Tunes a channel from any playlist, switching the active playlist first if needed. */
    private suspend fun switchPlaylist(pid: String) {
        if (pid == activePlaylist()?.id) return
        val p = playlists.firstOrNull { it.id == pid } ?: return
        val (chs, g, v) = dataFor(p)
        updatePrefs { it.copy(activePlaylist = p.id) }
        channels = chs; guide = g; vod = v
        selectedGroup = null; currentKey = null
        applyChannels(chs)
        rebuildSportEvents()
    }

    // ---------------- left navigation ----------------
    fun navSelect(target: Screen) {
        val s = if (target == Screen.LIVE) Screen.GUIDE else target
        if (s == Screen.MOVIES || s == Screen.SERIES) closeVodDetail()
        if (s == Screen.SPORTS) openSportsScreen() else {
            if (s == Screen.SETTINGS) settingsSection = "general"
            if (s == Screen.MANAGE) { settingsSection = "manage"; screen = Screen.SETTINGS } else screen = s
        }
        navMode = NavMode.COLLAPSED
        if (s == Screen.LIVE || s == Screen.GUIDE) {
            expandedPlaylist = activePlaylist()?.id
            loadPlaylistGroups(expandedPlaylist)
        }
    }

    fun togglePlaylist(pid: String) {
        expandedPlaylist = if (expandedPlaylist == pid) null else pid
        loadPlaylistGroups(pid)
    }

    private fun loadPlaylistGroups(pid: String?) {
        val p = playlists.firstOrNull { it.id == pid } ?: return
        if (p.id == activePlaylist()?.id) { playlistGroups = playlistGroups + (p.id to groups()); return }
        viewModelScope.launch {
            val (chs, _, _) = dataFor(p)
            val counts = LinkedHashMap<String, Int>()
            chs.forEach { counts[it.group] = (counts[it.group] ?: 0) + 1 }
            val list = counts.filterKeys { it !in prefs.hiddenGroups }.map { (raw, n) -> GroupInfo(raw, groupName(raw), n, false) }
            playlistGroups = playlistGroups + (p.id to list)
        }
    }

    /** Picking a category: switch playlist if needed, hide the menu and show categories as the left nav. */
    fun chooseGroup(pid: String, raw: String) {
        viewModelScope.launch {
            switchPlaylist(pid)
            selectedGroup = raw
            navMode = NavMode.GROUPS
            guideFocusTick++
        }
    }

    /** Back walks up the navigation: categories → playlists → full menu → TV Guide. Returns false to let the app exit. */
    fun navBack(): Boolean = when (navMode) {
        NavMode.GROUPS -> {
            navMode = NavMode.COLLAPSED
            expandedPlaylist = activePlaylist()?.id
            loadPlaylistGroups(expandedPlaylist)
            true
        }
        NavMode.COLLAPSED -> { navMode = NavMode.FULL; true }
        NavMode.FULL -> if (screen != Screen.GUIDE && channels.isNotEmpty()) {
            screen = Screen.GUIDE; navMode = NavMode.GROUPS; guideFocusTick++; true
        } else false
    }

    fun openHit(h: Hit) {
        when (h.kind) {
            "channel", "program" -> viewModelScope.launch {
                switchPlaylist(h.playlistId)
                openFullScreen(h.channelKey, PlayContext.GROUP)
            }
            "movie", "episode" -> h.vod?.let { playVod(it) }
            "series" -> h.vod?.let { openVodDetail(it) }
        }
    }

    fun openSeries(item: VodItem) {
        seriesOpen = item
        episodes = null
        val p = playlists.firstOrNull { it.id == item.playlistId } ?: return
        viewModelScope.launch {
            episodes = try {
                withContext(Dispatchers.IO) { XtreamVod.episodes(p, item.seriesId ?: "") }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun closeSeries() { seriesOpen = null; episodes = null }

    fun playEpisode(e: Episode) {
        val s = seriesOpen ?: return
        playVod(VodItem("${s.key}:${e.season}:${e.number}", s.playlistId, "episode", "${s.name} · S${e.season} E${e.number} · ${e.title}", s.logo, e.url))
    }

    fun playVod(item: VodItem, fromStart: Boolean = false) {
        recordWatch()
        if (screen != Screen.PLAYER) lastScreen = screen
        vodPlaying = item
        playerError = null
        player.setMediaItem(MediaItem.fromUri(item.url), if (fromStart) 0L else resumeOf(item.key))
        player.prepare()
        player.playWhenReady = true
        screen = Screen.PLAYER
    }

    // ---------------- movies & series browsing ----------------
    fun resumeOf(key: String): Long = resume[key] ?: 0L

    /** Remembers where a movie or episode was stopped (cleared when nearly finished). */
    private fun saveResumePoint() {
        val item = vodPlaying ?: return
        val pos = player.currentPosition; val dur = player.duration
        val m = resume.toMutableMap()
        if (pos > 60_000 && (dur <= 0 || pos < dur * 0.95)) m[item.key] = pos else m.remove(item.key)
        resume = m
        store.saveResume(m)
    }

    fun vodOf(kind: String): List<VodItem> = vod.filter { if (kind == "series") it.kind == "series" else it.kind == "movie" || it.kind == "episode" }

    fun vodCategories(kind: String): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        vodOf(kind).forEach { counts[it.category.ifBlank { "Other" }] = (counts[it.category.ifBlank { "Other" }] ?: 0) + 1 }
        return counts.toList()
    }

    fun vodItems(kind: String): List<VodItem> {
        val all = vodOf(kind)
        return when (val c = vodCategory[kind] ?: "All") {
            "All" -> all
            "Recently added" -> all.sortedByDescending { it.added }.take(200)
            "My List" -> prefs.myList.filter { if (kind == "series") it.kind == "series" else it.kind != "series" }
            "Continue watching" -> all.filter { resume.containsKey(it.key) }
            else -> all.filter { it.category.ifBlank { "Other" } == c }
        }
    }

    fun openVodDetail(item: VodItem) {
        screen = if (item.kind == "series") Screen.SERIES else Screen.MOVIES
        if (navMode == NavMode.FULL || navMode == NavMode.GROUPS) navMode = NavMode.COLLAPSED
        vodDetail = item; vodInfo = null; detailEpisodes = null; detailSeason = null
        val p = playlists.firstOrNull { it.id == item.playlistId }
        if (p == null || !p.isXtream) { vodInfo = VodInfo(); detailEpisodes = emptyList(); return }
        viewModelScope.launch {
            try {
                if (item.kind == "series") {
                    val (info, eps) = withContext(Dispatchers.IO) { XtreamVod.seriesInfo(p, item.seriesId ?: "") }
                    vodInfo = info; detailEpisodes = eps; detailSeason = eps.firstOrNull()?.season
                } else {
                    vodInfo = withContext(Dispatchers.IO) { XtreamVod.movieInfo(p, item.streamId ?: "") }
                }
            } catch (e: Exception) {
                vodInfo = VodInfo(plot = "Details aren't available right now.")
                if (item.kind == "series") detailEpisodes = emptyList()
            }
        }
    }

    fun closeVodDetail() { vodDetail = null; vodInfo = null; detailEpisodes = null }

    fun playDetailEpisode(e: Episode, fromStart: Boolean = false) {
        val s = vodDetail ?: return
        playVod(VodItem(e.key.ifBlank { "${s.key}:${e.season}:${e.number}" }, s.playlistId, "episode", "${s.name} · S${e.season} E${e.number} · ${e.title}", s.logo, e.url), fromStart)
    }

    fun seek(ms: Long) {
        val d = player.duration
        val target = (player.currentPosition + ms).coerceAtLeast(0L)
        player.seekTo(if (d > 0) target.coerceAtMost(d - 1000) else target)
    }

    private fun start(c: Channel) {
        recordWatch()
        vodPlaying = null
        currentKey = c.key
        playerError = null
        val item = MediaItem.Builder().setUri(c.url).apply {
            if (c.url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
        watchKey = c.key; watchStart = System.currentTimeMillis()
        updatePrefs { it.copy(lastChannel = c.key) }
    }

    fun stop() {
        if (vodPlaying != null) saveResumePoint()
        recordWatch()
        player.stop()
    }

    private fun contextList(): List<Channel> =
        if (playContext == PlayContext.FAVORITES) favoriteChannels() else channelsIn(selectedGroup ?: channel(currentKey)?.group)

    private fun liveEvents(cat: String?): List<SportEvent> =
        sportEvents.filter { (cat == null || it.catId == cat) && now >= it.start && now < it.end && it.channelKeys.isNotEmpty() }

    fun catName(id: String?): String = if (id == null) "Sports" else prefs.sportCats.firstOrNull { it.id == id }?.name ?: "Sports"

    fun contextLabel(): String {
        if (playContext == PlayContext.SPORTS) {
            val live = liveEvents(sportsPlayCat)
            val i = live.indexOfFirst { currentKey in it.channelKeys }
            return "Live in ${catName(sportsPlayCat)}" + if (i >= 0) " · ${i + 1} of ${live.size}" else ""
        }
        val list = contextList()
        val i = list.indexOfFirst { it.key == currentKey }
        val name = if (playContext == PlayContext.FAVORITES) "Favorites" else groupName(selectedGroup ?: "")
        return if (i < 0) name else "$name · ${i + 1} of ${list.size}"
    }

    /** Remote Up / Channel Up = -1 (previous), Down / Channel Down = +1 (next). Wraps at both ends. */
    fun step(dir: Int) {
        if (playContext == PlayContext.SPORTS) {
            val live = liveEvents(sportsPlayCat)
            if (live.isEmpty()) { flashOsd("No live events in ${catName(sportsPlayCat)}"); return }
            var i = live.indexOfFirst { currentKey in it.channelKeys }
            i = if (i < 0) 0 else (i + dir + live.size) % live.size
            channel(live[i].channelKeys.first())?.let { start(it) }
            flashOsd(contextLabel() + " · " + live[i].title)
            return
        }
        val list = contextList()
        if (list.isEmpty()) return
        var i = list.indexOfFirst { it.key == currentKey }
        i = if (i < 0) 0 else (i + dir + list.size) % list.size
        start(list[i])
        val wrapped = (dir > 0 && i == 0) || (dir < 0 && i == list.size - 1)
        flashOsd(contextLabel() + if (wrapped && list.size > 1) " · back to ${if (dir > 0) "top" else "bottom"} of list" else "")
    }

    private fun flashOsd(text: String) {
        osd = text
        osdJob?.cancel()
        osdJob = viewModelScope.launch {
            delay(2500)
            osd = null
        }
    }

    // ---------------- watch history (on device only) ----------------
    private fun recordWatch() {
        val k = watchKey ?: return
        val secs = (System.currentTimeMillis() - watchStart) / 1000
        watchKey = null
        if (prefs.historyPaused || secs < 10) return
        history = history + HistoryEntry(k, watchStart, secs)
        store.saveHistory(history)
    }

    fun clearHistory() {
        history = emptyList()
        store.saveHistory(emptyList())
    }

    fun onAppExit() {
        recordWatch()
        if (prefs.clearCacheOnExit) store.clearCache()
    }

    override fun onCleared() {
        recordWatch()
        player.release()
        super.onCleared()
    }
}
