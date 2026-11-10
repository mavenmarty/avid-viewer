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
import com.avidviewer.tv.data.ChannelList
import com.avidviewer.tv.data.LocalSuggest
import com.avidviewer.tv.data.Suggestion
import com.avidviewer.tv.data.HistoryEntry
import com.avidviewer.tv.data.M3u
import com.avidviewer.tv.data.Playlist
import com.avidviewer.tv.data.Prefs
import com.avidviewer.tv.data.Program
import com.avidviewer.tv.data.Store
import com.avidviewer.tv.data.USER_AGENT
import com.avidviewer.tv.data.Episode
import com.avidviewer.tv.data.Crypto
import com.avidviewer.tv.data.Tmdb
import com.avidviewer.tv.data.Title
import com.avidviewer.tv.data.WatchProvider
import com.avidviewer.tv.data.WhereToWatch
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

enum class Screen { SEARCH, DISCOVER, FAVORITES, LIVE, GUIDE, SPORTS, MOVIES, SERIES, MANAGE, PLAYLISTS, SETTINGS, PLAYER }

/** Where full-screen Up/Down and Channel Up/Down move: the group the user came from, or Favorites. */
enum class PlayContext { GROUP, FAVORITES, SPORTS }

/**
 * Left navigation depth.
 * FULL: menu with icons and labels. COLLAPSED: icons only (plus the playlist tree on Live TV / TV Guide).
 * GROUPS: menu hidden; the active playlist's categories are the left navigation.
 */
enum class NavMode { FULL, COLLAPSED, GROUPS }

/** The combined Favorites category shown first in the TV Guide. */
enum class GuideFocus { TOP, KEEP, CURRENT }

const val FAV_GROUP = "\u2605favorites"
/** Group id prefix for the user's own channel lists: LIST_PREFIX + list id. */
const val LIST_PREFIX = "\u2605list:"

/** True for Favorites and the user's own lists (collections of channels from any playlist). */
fun isListGroup(g: String?) = g != null && (g == FAV_GROUP || g.startsWith(LIST_PREFIX))

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
    /** What the guide does on the next [guideFocusTick]: jump to the top, keep its place, or find the current channel. */
    var guideFocusMode = GuideFocus.CURRENT
    /** Scroll position of the guide list, remembered per category: (category, first item, offset). */
    var guideScroll: Triple<String?, Int, Int> = Triple(null, 0, 0)
    /** Scroll position of the Movies / Series grid per kind: kind → (category, first item, offset). */
    val vodScroll = HashMap<String, Triple<String, Int, Int>>()

    /** Movies / Series category: a different category starts at the top; the same one keeps its place. */
    fun chooseVodCategory(kind: String, name: String) {
        if ((vodCategory[kind] ?: "All") != name) vodScroll[kind] = Triple(name, 0, 0)
        vodCategory = vodCategory + (kind to name)
    }
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
    // Discover (TMDB)
    var discoverTab by mutableStateOf("new")
    var onlyMyServices by mutableStateOf(false)
    var discoverLists by mutableStateOf<Map<String, List<Title>>>(emptyMap()); private set
    var discoverLoading by mutableStateOf(false); private set
    var discoverError by mutableStateOf<String?>(null); private set
    var discoverNote by mutableStateOf<String?>(null); private set
    var discoverDetail by mutableStateOf<Title?>(null)
    var whereCache by mutableStateOf<Map<String, WhereToWatch>>(emptyMap()); private set
    var availableServices by mutableStateOf<List<WatchProvider>>(emptyList()); private set
    var tmdbStatus by mutableStateOf<String?>(null); private set
    private var discoverLoadedAt = HashMap<String, Long>()
    private var vodIndexFor: List<VodItem>? = null
    private var vodIndex: Map<String, VodItem> = emptyMap()
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
    var settingsSection by mutableStateOf("playlists")
    /** Search boxes on the TV Guide, Movies and Series screens (applied when OK/Search is pressed). */
    var guideQuery by mutableStateOf("")
    var vodQuery by mutableStateOf(mapOf("movie" to "", "series" to ""))
    var update by mutableStateOf<Updater.Release?>(null); private set
    var updateStatus by mutableStateOf<String?>(null); private set
    var updateBusy by mutableStateOf(false); private set
    var updatePromptDismissed by mutableStateOf(false)
    val installed: Pair<Long, String> = runCatching { Updater.installedVersion(app) }.getOrDefault(0L to "")
    var selectedEvent by mutableStateOf<String?>(null)
    private var sportsPlayCat: String? = null
    private var scoreCache: Map<String, List<Scores.Game>> = emptyMap()
    /** Live channels + guide of playlists other than the active one, loaded only when needed. */
    private val otherLive = HashMap<String, Pair<List<Channel>, Map<String, List<Program>>>>()
    /** Movies & series of playlists other than the VOD source, loaded only for search. */
    private val otherVod = HashMap<String, List<VodItem>>()
    /** Which playlist [vod] currently holds (null = not loaded yet; VOD loads only when a VOD screen needs it). */
    private var vodLoadedFor: String? = null
    var vodLoading by mutableStateOf(false); private set
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

    // ---------------- backup & restore ----------------
    /** What only the screen can do (ask for storage permission, open the file picker); set by MainActivity. */
    interface BackupHost {
        fun requestStorage(write: Boolean, then: (Boolean) -> Unit)
        fun canPick(): Boolean
        /** Opens the file picker; gives the chosen file's text, or null (cancelled or no picker). */
        fun pickFile(then: (String?) -> Unit)
    }
    var backupHost: BackupHost? = null
    var backupStatus by mutableStateOf<String?>(null); private set
    var backupBusy by mutableStateOf(false); private set
    var foundBackups by mutableStateOf<List<com.avidviewer.tv.data.BackupFiles.Found>>(emptyList()); private set
    /** A backup file chosen for restoring, waiting for its PIN. */
    var pendingRestore by mutableStateOf<String?>(null); private set
    var pendingRestoreInfo by mutableStateOf(""); private set

    fun backUp(pin: String, toShare: Boolean = false) {
        if (pin.length < 4) { backupStatus = "Choose a PIN of at least 4 digits."; return }
        viewModelScope.launch {
            backupBusy = true
            backupStatus = "Making the backup…"
            val app = getApplication<Application>()
            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(System.currentTimeMillis())
            val name = "AvidViewer-backup-$stamp.${com.avidviewer.tv.data.Backup.EXTENSION}"
            val text = runCatching {
                withContext(Dispatchers.Default) {
                    com.avidviewer.tv.data.Backup.write(
                        com.avidviewer.tv.data.Backup.Contents(playlists, prefs, tmdbKey(), history, resume, System.currentTimeMillis(), installed.second, smbPassword()), pin,
                    )
                }
            }.getOrElse { backupBusy = false; backupStatus = "Couldn't make the backup: ${it.message}"; return@launch }
            if (toShare) {
                backupStatus = "Saving to ${prefs.smbAddress}…"
                backupStatus = runCatching { withContext(Dispatchers.IO) { com.avidviewer.tv.data.Smb.write(smbTarget(), name, text) } }
                    .fold({ "Saved $name to ${smbTarget().display}. Keep your PIN somewhere safe: the backup can't be opened without it." },
                        { com.avidviewer.tv.data.Smb.explain(it) })
                backupBusy = false
                return@launch
            }
            suspend fun save() {
                backupStatus = runCatching { withContext(Dispatchers.IO) { com.avidviewer.tv.data.BackupFiles.save(app, name, text) } }
                    .fold({ "Saved to $it. It stays there if the app is deleted. Keep your PIN somewhere safe: the backup can't be opened without it." },
                        { "Couldn't save the backup: ${it.message}" })
                backupBusy = false
            }
            val host = backupHost
            if (com.avidviewer.tv.data.BackupFiles.needsWritePermission() && host != null) {
                host.requestStorage(true) { ok ->
                    viewModelScope.launch { if (ok) save() else { backupBusy = false; backupStatus = "Avid Viewer needs permission to save to the Download folder. Choose Back up again and allow it." } }
                }
            } else save()
        }
    }

    // ---- SAMBA share ----
    var smbStatus by mutableStateOf<String?>(null); private set
    var shareBackups by mutableStateOf<List<Pair<String, Long>>>(emptyList()); private set

    fun smbPassword(): String = if (prefs.smbPassEnc.isBlank()) "" else Crypto.decrypt(prefs.smbPassEnc)

    fun smbTarget() = com.avidviewer.tv.data.Smb.target(prefs.smbAddress, prefs.smbUser, smbPassword())

    val smbReady get() = prefs.smbAddress.isNotBlank()

    /** Saves the share details and checks them (connects, makes the folder if needed, counts backups). */
    fun saveShare(address: String, user: String, password: String?) {
        updatePrefs {
            it.copy(smbAddress = address.trim(), smbUser = user.trim(),
                smbPassEnc = when { password == null -> it.smbPassEnc; password.isEmpty() -> ""; else -> Crypto.encrypt(password) })
        }
        if (address.isBlank()) { smbStatus = "Share removed."; shareBackups = emptyList(); return }
        viewModelScope.launch {
            smbStatus = "Connecting to ${address.trim()}…"
            smbStatus = runCatching { withContext(Dispatchers.IO) { com.avidviewer.tv.data.Smb.test(smbTarget()) } }
                .fold({ n -> "Connected to ${smbTarget().display}. " + if (n == 0) "No backups there yet." else "$n backup(s) there." },
                    { com.avidviewer.tv.data.Smb.explain(it) })
        }
    }

    fun listShareBackups() {
        viewModelScope.launch {
            smbStatus = "Looking for backups on ${prefs.smbAddress}…"
            runCatching { withContext(Dispatchers.IO) { com.avidviewer.tv.data.Smb.list(smbTarget()) } }
                .onSuccess { shareBackups = it; smbStatus = if (it.isEmpty()) "No backups on the share yet." else null }
                .onFailure { shareBackups = emptyList(); smbStatus = com.avidviewer.tv.data.Smb.explain(it) }
        }
    }

    fun chooseShareBackup(name: String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { com.avidviewer.tv.data.Smb.read(smbTarget(), name) } }
                .onSuccess { offerRestore(it) }.onFailure { smbStatus = com.avidviewer.tv.data.Smb.explain(it) }
        }
    }

    /** Restore: the file picker if the device has one, otherwise backups found in the Download folder. */
    fun startRestore() {
        backupStatus = null
        val host = backupHost
        if (host != null && host.canPick()) host.pickFile { text -> if (text != null) offerRestore(text) else scanForBackups() }
        else scanForBackups()
    }

    fun scanForBackups() {
        val app = getApplication<Application>()
        val run = {
            viewModelScope.launch {
                foundBackups = withContext(Dispatchers.IO) { com.avidviewer.tv.data.BackupFiles.findInDownloads(app) }
                if (foundBackups.isEmpty()) backupStatus =
                    "No backup found in the Download folder. If the file is on this device, a file manager app (for example X-plore) lets you pick it: install one, then choose Restore again."
            }
        }
        val host = backupHost
        if (android.os.Build.VERSION.SDK_INT <= 32 && host != null) host.requestStorage(false) { run() } else run()
    }

    fun chooseFoundBackup(f: com.avidviewer.tv.data.BackupFiles.Found) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { f.read() } }.onSuccess { offerRestore(it) }.onFailure { backupStatus = "Couldn't open ${f.name}: ${it.message}" }
        }
    }

    internal fun offerRestore(text: String) {
        runCatching { com.avidviewer.tv.data.Backup.peek(text) }.onSuccess { (created, ver, names) ->
            pendingRestore = text
            pendingRestoreInfo = "Made " + SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(created) +
                (if (ver.isNotBlank()) " with version $ver" else "") +
                " · " + (if (names.isEmpty()) "no playlists" else names.joinToString(", "))
        }.onFailure { backupStatus = it.message ?: "That file isn't an Avid Viewer backup." }
    }

    fun cancelRestore() { pendingRestore = null }

    /** Replaces playlists and settings with the backup's, then downloads channels and guides again. */
    fun restore(pin: String, done: (Boolean) -> Unit) {
        val text = pendingRestore ?: return
        viewModelScope.launch {
            backupBusy = true
            val c = try {
                withContext(Dispatchers.Default) { com.avidviewer.tv.data.Backup.read(text, pin) }
            } catch (e: Exception) {
                backupBusy = false; backupStatus = e.message; done(false); return@launch
            }
            withContext(Dispatchers.IO) {
                store.savePlaylists(c.playlists)
                store.saveHistory(c.history)
                store.saveResume(c.resume)
            }
            playlists = c.playlists
            history = c.history
            resume = c.resume
            updatePrefs {
                c.prefs.copy(
                    tmdbKeyEnc = if (c.tmdbKey.isBlank()) "" else Crypto.encrypt(c.tmdbKey),
                    smbPassEnc = if (c.smbPassword.isBlank()) "" else Crypto.encrypt(c.smbPassword),
                )
            }
            otherLive.clear(); otherVod.clear(); playlistGroups = emptyMap()
            vod = emptyList(); vodLoadedFor = null
            pendingRestore = null
            backupBusy = false
            backupStatus = "Restored ${c.playlists.size} playlist(s) and your settings. Channels and guides are downloading again now."
            done(true)
            reloadAfterActiveChange()
            if (prefs.postalCode.isNotBlank()) refreshWeather()
            syncAllPlaylists(force = false)
        }
    }

    /** Details of the last crash, if the app closed unexpectedly (shown once at start and in Settings › About). */
    var lastCrash by mutableStateOf(com.avidviewer.tv.data.CrashLog.read(app)); private set
    var crashNoticeSeen by mutableStateOf(false)

    fun clearCrash() { com.avidviewer.tv.data.CrashLog.clear(getApplication()); lastCrash = null }

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
        if (activePlaylist() == null) { screen = Screen.SETTINGS; settingsSection = "playlists"; navMode = NavMode.COLLAPSED }
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
            refreshSuggestions()
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
    /** Playlists that are switched on; disabled ones are kept but hidden everywhere. */
    val shownPlaylists: List<Playlist> get() = playlists.filter { it.enabled }

    /** Playlist that feeds the TV Guide and Sports: switched on, with live TV on (any switched-on one as a last resort). */
    fun activePlaylist(): Playlist? =
        shownPlaylists.firstOrNull { it.id == prefs.activePlaylist && it.liveOn } ?: shownPlaylists.firstOrNull { it.liveOn }
            ?: shownPlaylists.firstOrNull { it.id == prefs.activePlaylist } ?: shownPlaylists.firstOrNull()

    /** Playlists the TV Guide lists: switched on with live TV on. */
    val livePlaylists: List<Playlist> get() = shownPlaylists.filter { it.liveOn }

    /** Playlists Movies, Series, Discover and search can use: switched on with movies & series on. */
    val vodPlaylists: List<Playlist> get() = shownPlaylists.filter { it.vodOn }

    /** First playlist with movies & series (used only for messages). */
    fun vodPlaylist(): Playlist? = vodPlaylists.firstOrNull()

    /**
     * Movies, Series and Discover show movies & series from every playlist that has them switched on, merged.
     * They load the first time one of those screens opens (or again with [refresh]).
     */
    fun ensureVod(refresh: Boolean = false) {
        val pls = vodPlaylists
        if (pls.isEmpty()) { vod = emptyList(); vodLoadedFor = null; return }
        val sig = pls.joinToString(",") { it.id }
        if (!refresh && vodLoadedFor == sig) return
        vodLoadedFor = sig
        viewModelScope.launch {
            val parts = LinkedHashMap<String, List<VodItem>>()
            for (p in pls) {
                parts[p.id] = loadVodSource(p, refresh, sig)
                if (vodLoadedFor != sig) return@launch
                vod = parts.values.flatten()
            }
            vodLoading = false
        }
    }

    /** Loads (and downloads when needed) one playlist's movies & series; returns them. */
    private suspend fun loadVodSource(p: Playlist, refresh: Boolean, sig: String): List<VodItem> {
        vodLoading = true
        val savedVod = withContext(Dispatchers.IO) { store.loadVod(p.id).distinctBy { it.key } }
        if (vodLoadedFor != sig) return savedVod
        var result = savedVod
        // An M3U playlist with live TV off: its movies come from the same file, so read it just for them.
        if (!p.isXtream && (refresh || savedVod.isEmpty()) && !p.liveOn) {
            status = "Loading movies from ${p.name}…"
            runCatching {
                val m3uVod = ArrayList<VodItem>()
                withContext(Dispatchers.IO) { M3u.channels(p, m3uVod); store.saveVod(p.id, m3uVod) }
                result = m3uVod.distinctBy { it.key }
                status = "Loaded ${m3uVod.size} movies and series."
            }.onFailure { vodError = "Movies couldn't load: ${it.message}" }
        }
        // Re-download when asked, when nothing is saved, or when the saved list predates categories.
        val oldFormat = savedVod.isNotEmpty() && savedVod.all { it.category.isBlank() }
        if (p.isXtream && (refresh || savedVod.isEmpty() || oldFormat)) {
            status = "Loading movies from ${p.name}…"
            val movies = withContext(Dispatchers.IO) { runCatching { XtreamVod.movies(p) } }
            status = "Loading series…"
            val series = withContext(Dispatchers.IO) { runCatching { XtreamVod.series(p) } }
            val list = (movies.getOrDefault(emptyList()) + series.getOrDefault(emptyList())).distinctBy { it.key }
            if (list.isNotEmpty()) {
                withContext(Dispatchers.IO) { store.saveVod(p.id, list) }
                result = list
                otherVod.remove(p.id)
            }
            vodError = listOfNotNull(
                movies.exceptionOrNull()?.let { "Movies couldn't load: ${it.message}" },
                series.exceptionOrNull()?.let { "Series couldn't load: ${it.message}" },
            ).joinToString("\n").ifBlank { null }
            status = vodError ?: "Loaded ${movies.getOrDefault(emptyList()).size} movies and ${series.getOrDefault(emptyList()).size} series."
        }
        return result
    }

    /** Forget loaded movies & series (after a playlist change); they load again when a VOD screen opens. */
    private fun resetVod() {
        vod = emptyList(); vodLoadedFor = null; otherVod.clear()
        if (screen == Screen.MOVIES || screen == Screen.SERIES || screen == Screen.DISCOVER) ensureVod()
    }

    fun addPlaylist(name: String, type: String, server: String, user: String, pass: String, m3u: String, epg: String, liveOn: Boolean = true, vodOn: Boolean = true) {
        val p = Playlist(
            id = UUID.randomUUID().toString().take(8),
            name = name.ifBlank { if (type == "xtream") "My provider" else "M3U playlist" },
            type = type, server = server.trim(), username = user.trim(), password = pass,
            m3uUrl = m3u.trim(), epgUrl = epg.trim(), liveOn = liveOn, vodOn = vodOn,
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
            status = "Added ${p.name}."
            playlistSaved++
            loadActive(refresh = true)
        }
    }

    /** Bumped after a playlist is added or edited successfully, so the form can close. */
    var playlistSaved by mutableStateOf(0); private set

    /** Saves changes to a playlist. A blank [pass] keeps the current password. Logins are checked first. */
    fun updatePlaylist(id: String, name: String, type: String, server: String, user: String, pass: String, m3u: String, epg: String) {
        val old = playlists.firstOrNull { it.id == id } ?: return
        val p = old.copy(
            name = name.ifBlank { old.name }, type = type, server = server.trim(), username = user.trim(),
            password = pass.ifEmpty { old.password }, m3uUrl = m3u.trim(), epgUrl = epg.trim(),
        )
        viewModelScope.launch {
            busy = true
            status = "Checking ${p.name}…"
            val sourceChanged = p.type != old.type || p.server != old.server || p.username != old.username ||
                p.password != old.password || p.m3uUrl != old.m3uUrl || p.epgUrl != old.epgUrl
            val problem = if (!sourceChanged) null else withContext(Dispatchers.IO) {
                if (p.isXtream) Xtream.checkLogin(p) else if (p.m3uUrl.isBlank()) "Enter the playlist link." else null
            }
            if (problem != null) { status = problem; busy = false; return@launch }
            replacePlaylist(p)
            busy = false
            status = "Saved ${p.name}."
            playlistSaved++
            if (sourceChanged) {
                withContext(Dispatchers.IO) { store.deletePlaylistData(p.id) }
                if (p.enabled) refreshPlaylist(p.id)
            }
        }
    }

    private fun replacePlaylist(p: Playlist) {
        playlists = playlists.map { if (it.id == p.id) p else it }
        store.savePlaylists(playlists)
        otherLive.remove(p.id); otherVod.remove(p.id)
        playlistGroups = playlistGroups - p.id
    }

    /** Turns a whole playlist on or off. Its data and favorites are kept for when it's turned back on. */
    fun setPlaylistEnabled(id: String, on: Boolean) {
        val p = playlists.firstOrNull { it.id == id } ?: return
        val wasActive = activePlaylist()?.id == id
        replacePlaylist(p.copy(enabled = on))
        status = if (on) "${p.name} is on." else "${p.name} is off. It's hidden from the guide, search and Sports until you turn it back on."
        if (on && (wasActive || shownPlaylists.size == 1)) {
            updatePrefs { it.copy(activePlaylist = id) }
            loadActive(refresh = false)
        } else if (!on && wasActive) reloadAfterActiveChange()
        resetVod()
        rebuildSportEventsLater()
    }

    /** Chooses whether a playlist supplies live TV (with its guide) and movies & series. */
    fun setPlaylistContent(id: String, liveOn: Boolean, vodOn: Boolean) {
        val p = playlists.firstOrNull { it.id == id } ?: return
        val liveTurnedOn = liveOn && !p.liveOn
        val wasActive = activePlaylist()?.id == id
        replacePlaylist(p.copy(liveOn = liveOn, vodOn = vodOn))
        status = "${p.name}: " + when {
            liveOn && vodOn -> "live TV and movies & series"
            liveOn -> "live TV only"
            vodOn -> "movies & series only"
            else -> "nothing is loaded until you turn live TV or movies & series back on"
        } + "."
        when {
            // Live TV switched off on the guide's playlist: move the guide to another playlist with live TV.
            wasActive && !liveOn -> reloadAfterActiveChange()
            wasActive -> loadActive(refresh = false, download = liveTurnedOn)
            activePlaylist()?.id == id -> reloadAfterActiveChange()
        }
        if (vodOn != p.vodOn) resetVod()
        rebuildSportEventsLater()
    }

    private fun reloadAfterActiveChange() {
        updatePrefs { it.copy(activePlaylist = activePlaylist()?.id) }
        applyChannels(emptyList()); guide = emptyMap()
        selectedGroup = null; currentKey = null
        if (activePlaylist() != null) loadActive(refresh = false)
    }

    private fun rebuildSportEventsLater() { viewModelScope.launch { rebuildSportEvents() }; refreshSuggestions() }

    fun removePlaylist(id: String) {
        val wasActive = activePlaylist()?.id == id
        val gone = playlists.firstOrNull { it.id == id }?.name ?: "Playlist"
        playlists = playlists.filterNot { it.id == id }
        store.savePlaylists(playlists)
        store.deletePlaylistData(id)
        otherLive.remove(id); otherVod.remove(id)
        playlistGroups = playlistGroups - id
        if (wasActive || prefs.activePlaylist == id) reloadAfterActiveChange()
        if (vodLoadedFor?.split(',')?.contains(id) == true) resetVod()
        status = "Removed $gone."
        rebuildSportEventsLater()
    }

    fun refreshPlaylist(id: String) {
        val p = playlists.firstOrNull { it.id == id } ?: return
        if (!p.enabled) { status = "Turn ${p.name} on to refresh it."; return }
        if (!p.liveOn && !p.vodOn) { status = "Turn on live TV or movies & series for ${p.name} first."; return }
        if (p.liveOn) {
            if (prefs.activePlaylist != id) updatePrefs { it.copy(activePlaylist = id) }
            loadActive(refresh = true)
        }
        if (p.vodOn && p.isXtream) {
            // Download the movies & series again, then show them if a VOD screen is open.
            viewModelScope.launch {
                withContext(Dispatchers.IO) { store.saveVod(id, emptyList()) }
                otherVod.remove(id); vodLoadedFor = null
                if (screen == Screen.MOVIES || screen == Screen.SERIES || screen == Screen.DISCOVER) ensureVod(refresh = true)
                else syncAllPlaylists(force = false)
            }
        }
    }

    fun setActive(id: String) {
        updatePrefs { it.copy(activePlaylist = id) }
        loadActive(refresh = false)
    }

    /** Loads channels and guide from storage, downloading when missing, stale or [refresh] is set. */
    fun loadActive(refresh: Boolean, download: Boolean = false) {
        val p = activePlaylist() ?: return
        viewModelScope.launch {
            busy = true
            if (!p.liveOn) { applyChannels(emptyList()); guide = emptyMap() }
            val cached = if (p.liveOn) withContext(Dispatchers.IO) { store.loadChannels(p.id) } else emptyList()
            if (cached.isNotEmpty()) applyChannels(cached)
            if (p.liveOn && (refresh || download || cached.isEmpty())) {
                status = "Loading channels from ${p.name}…"
                try {
                    val m3uVod = ArrayList<VodItem>()
                    val list = withContext(Dispatchers.IO) {
                        if (p.isXtream) Xtream.liveChannels(p, prefs.streamFormat) else M3u.channels(p, m3uVod)
                    }
                    withContext(Dispatchers.IO) { store.saveChannels(p.id, list) }
                    applyChannels(list)
                    status = "Loaded ${list.size} channels."
                    otherLive.remove(p.id)
                    // An M3U playlist lists its movies alongside its channels: save them for when VOD is used.
                    if (!p.isXtream && p.vodOn) {
                        withContext(Dispatchers.IO) { store.saveVod(p.id, m3uVod) }
                        otherVod.remove(p.id)
                        if (vodLoadedFor?.split(',')?.contains(p.id) == true) vodLoadedFor = null
                    }
                } catch (e: Exception) {
                    status = if (cached.isEmpty()) "Couldn't load channels: ${e.message}" else "Couldn't refresh, showing saved channels."
                }
            }
            if (p.liveOn) {
                val (savedGuide, savedAt) = withContext(Dispatchers.IO) { store.loadGuide(p.id) }
                if (savedGuide.isNotEmpty()) guide = savedGuide
                val stale = System.currentTimeMillis() - savedAt > 8 * 3600 * 1000L
                if (refresh || download || stale || savedGuide.isEmpty()) refreshGuide(p)
            }
            ensureFavoriteData()
            busy = false
            refreshSuggestions()
            syncAllPlaylists(force = false)
        }
    }

    // ---------------- local suggestions (postal code) ----------------
    /** Everything that looks local; favorites and dismissed channels are filtered out when shown. */
    private var allSuggestions by mutableStateOf<List<Suggestion>>(emptyList())

    /** What the Favorites category shows: local channels not yet favorited or dismissed. */
    fun suggestions(): List<Suggestion> =
        if (!prefs.showSuggestions) emptyList()
        else allSuggestions.filter { !inAnyList(it.key) && it.key !in prefs.dismissedSuggestions && channel(it.key) != null }.take(12)

    fun suggestionPlace(): String = LocalSuggest.parsePlace(prefs.placeName).let { (city, _, abbr) -> if (abbr != null) "$city, $abbr" else city }

    fun refreshSuggestions() {
        val place = prefs.placeName
        if (place.isBlank()) { allSuggestions = emptyList(); return }
        viewModelScope.launch {
            val data = livePlaylists.map { p -> p to liveFor(p).first }
            allSuggestions = withContext(Dispatchers.Default) {
                LocalSuggest.find(data, place, { raw -> groupName(raw) })
            }
            favDataTick++
        }
    }

    fun dismissSuggestion(key: String) = updatePrefs { it.copy(dismissedSuggestions = it.dismissedSuggestions + key) }

    fun setShowSuggestions(on: Boolean) {
        updatePrefs { it.copy(showSuggestions = on) }
        if (on) refreshSuggestions()
    }

    fun restoreSuggestions() {
        updatePrefs { it.copy(dismissedSuggestions = emptySet()) }
        refreshSuggestions()
    }

    fun dismissedSuggestionCount() = prefs.dismissedSuggestions.size

    private suspend fun refreshGuide(p: Playlist) {
        status = "Loading TV guide…"
        try {
            val g = downloadGuide(p, channels) ?: return
            guide = g
            rebuildSportEvents()
            status = "Guide loaded for ${g.size} channels."
        } catch (e: Exception) {
            status = "Couldn't load the TV guide: ${e.message}"
        }
    }

    /** Downloads and saves a playlist's guide for [chs]; null when it has no guide. */
    private suspend fun downloadGuide(p: Playlist, chs: List<Channel>): Map<String, List<Program>>? {
        val url = p.guideUrl().ifBlank { withContext(Dispatchers.IO) { M3u.headerGuideUrl(p) } ?: "" }
        if (url.isBlank()) return null
        val wanted = chs.mapNotNull { it.epgId }.toSet()
        if (wanted.isEmpty()) return null
        val from = System.currentTimeMillis() - 3 * 3600 * 1000L
        val to = System.currentTimeMillis() + 36 * 3600 * 1000L
        val g = withContext(Dispatchers.IO) { Xmltv.read(url, wanted, from, to) }
        withContext(Dispatchers.IO) { store.saveGuide(p.id, g) }
        return g
    }

    // ---------------- every playlist kept current (no "active" playlist for the user) ----------------
    private var syncJob: kotlinx.coroutines.Job? = null

    /**
     * In the background, one playlist at a time, downloads what's missing or out of date for every switched-on
     * playlist — live channels and guide where live TV is on, movies & series where they're on — so search,
     * the guide, Movies and Series cover all playlists. [force] downloads everything again.
     */
    fun syncAllPlaylists(force: Boolean = false) {
        if (syncJob?.isActive == true && !force) return
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            if (!force) delay(10_000) // let the first screen settle on slow devices
            val dayMs = 24 * 3600 * 1000L; val guideMs = 8 * 3600 * 1000L
            for (p in shownPlaylists) {
                val nowMs = System.currentTimeMillis()
                val isGuidePlaylist = p.id == activePlaylist()?.id
                if (p.liveOn && !isGuidePlaylist) runCatching {
                    var chs: List<Channel>? = null
                    if (force || withContext(Dispatchers.IO) { store.savedAt("channels", p.id) } == 0L ||
                        nowMs - withContext(Dispatchers.IO) { store.savedAt("channels", p.id) } > dayMs) {
                        val m3uVod = ArrayList<VodItem>()
                        val list = withContext(Dispatchers.IO) {
                            if (p.isXtream) Xtream.liveChannels(p, prefs.streamFormat) else M3u.channels(p, m3uVod)
                        }.distinctBy { it.key }
                        withContext(Dispatchers.IO) {
                            store.saveChannels(p.id, list)
                            if (!p.isXtream && p.vodOn) store.saveVod(p.id, m3uVod)
                        }
                        chs = list
                        otherLive.remove(p.id); playlistGroups = playlistGroups - p.id
                    }
                    if (force || nowMs - withContext(Dispatchers.IO) { store.savedAt("guide", p.id) } > guideMs) {
                        val list = chs ?: withContext(Dispatchers.IO) { store.loadChannels(p.id) }
                        downloadGuide(p, list)
                        otherLive.remove(p.id)
                    }
                }
                if (p.vodOn && p.isXtream) runCatching {
                    val at = withContext(Dispatchers.IO) { store.savedAt("vod", p.id) }
                    if (force || at == 0L || nowMs - at > dayMs) {
                        val list = withContext(Dispatchers.IO) {
                            (runCatching { XtreamVod.movies(p) }.getOrDefault(emptyList()) + runCatching { XtreamVod.series(p) }.getOrDefault(emptyList())).distinctBy { it.key }
                        }
                        if (list.isNotEmpty()) {
                            withContext(Dispatchers.IO) { store.saveVod(p.id, list) }
                            otherVod.remove(p.id)
                            if (vodLoadedFor != null && p.id in vodLoadedFor!!.split(',')) { vodLoadedFor = null; if (screen == Screen.MOVIES || screen == Screen.SERIES || screen == Screen.DISCOVER) ensureVod() }
                        }
                    }
                }
            }
            if (prefs.showSuggestions) refreshSuggestions()
        }
    }

    /** Live channels of every playlist with live TV on, for the TV Guide's search box. */
    var searchPool by mutableStateOf<List<Channel>>(emptyList()); private set

    fun loadSearchPool() {
        viewModelScope.launch {
            searchPool = livePlaylists.flatMap { liveFor(it).first }
            favDataTick++
        }
    }

    /** Tunes a channel from any playlist: the guide moves to that playlist and category, then previews it. */
    fun tuneAnywhere(key: String) {
        val pid = key.substringBefore(':')
        if (pid == activePlaylist()?.id) { selectChannel(key, PlayContext.GROUP); return }
        viewModelScope.launch {
            switchPlaylist(pid)
            val c = channel(key) ?: return@launch
            selectedGroup = c.group
            guideFocusMode = GuideFocus.CURRENT
            preview(key)
            guideFocusTick++
        }
    }

    private fun applyChannels(raw: List<Channel>) {
        val list = raw.distinctBy { it.key }
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
    fun groupName(raw: String) = when {
        raw == FAV_GROUP -> "★ Favorites"
        raw.startsWith(LIST_PREFIX) -> "☰ " + (userList(raw)?.name ?: "List")
        else -> prefs.groupNames[raw] ?: raw
    }
    fun channelName(c: Channel) = prefs.channelNames[c.key] ?: c.name

    fun groups(includeHidden: Boolean = false): List<GroupInfo> {
        val counts = LinkedHashMap<String, Int>()
        channels.forEach { counts[it.group] = (counts[it.group] ?: 0) + 1 }
        val ordered = prefs.groupOrder.filter { it in counts } + counts.keys.filterNot { it in prefs.groupOrder }
        val pid = activePlaylist()?.id ?: ""
        return ordered.map { GroupInfo(it, groupName(it), counts[it] ?: 0, isGroupHidden(pid, it)) }
            .filter { includeHidden || !it.hidden }
    }

    // ---------------- hiding (List Editor) ----------------
    /** Hidden categories are stored per playlist as "playlistId\u0001name"; a bare name (older versions) hides it everywhere. */
    private fun hiddenId(pid: String, raw: String) = pid + "\u0001" + raw

    fun isGroupHidden(pid: String, raw: String) = raw in prefs.hiddenGroups || hiddenId(pid, raw) in prefs.hiddenGroups

    /** Shows or hides many categories of one playlist at once. */
    fun setGroupsHidden(pid: String, raws: Collection<String>, hidden: Boolean) {
        updatePrefs { p ->
            val ids = raws.map { hiddenId(pid, it) }
            p.copy(hiddenGroups = if (hidden) p.hiddenGroups + ids else p.hiddenGroups - ids.toSet() - raws.toSet())
        }
        afterHidingChanged(pid)
    }

    /** Shows or hides many channels at once. */
    fun setChannelsHidden(keys: Collection<String>, hidden: Boolean) {
        updatePrefs { p -> p.copy(hiddenChannels = if (hidden) p.hiddenChannels + keys else p.hiddenChannels - keys.toSet()) }
        keys.firstOrNull()?.substringBefore(':')?.let { afterHidingChanged(it) }
    }

    private fun afterHidingChanged(pid: String) {
        playlistGroups = playlistGroups - pid
        if (expandedPlaylist == pid) loadPlaylistGroups(pid)
        viewModelScope.launch { rebuildSportEvents() }
    }

    /** All channels of a playlist, hidden ones included, for the List Editor. */
    suspend fun editorChannels(pid: String): List<Channel> {
        val p = playlists.firstOrNull { it.id == pid } ?: return emptyList()
        if (!p.liveOn) return emptyList()
        if (p.id == activePlaylist()?.id) return channels
        return otherLive[p.id]?.first ?: withContext(Dispatchers.IO) { store.loadChannels(p.id) }.distinctBy { it.key }
    }

    /** Categories of [chs] in the user's order: (name, channel count). */
    fun editorGroups(chs: List<Channel>): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        chs.forEach { counts[it.group] = (counts[it.group] ?: 0) + 1 }
        val ordered = prefs.groupOrder.filter { it in counts } + counts.keys.filterNot { it in prefs.groupOrder }
        return ordered.map { it to (counts[it] ?: 0) }
    }

    /** Channels of one category in the user's order, hidden ones included. */
    fun editorChannelsIn(chs: List<Channel>, raw: String): List<Channel> {
        val list = chs.filter { it.group == raw }
        val order = prefs.channelOrder[raw] ?: return list
        val byKey = list.associateBy { it.key }
        return order.mapNotNull { byKey[it] } + list.filterNot { it.key in order }
    }

    fun channelsIn(group: String?): List<Channel> {
        if (group == null) return emptyList()
        if (isListGroup(group)) return listKeys(group).mapNotNull { channel(it) }
        val list = channels.filter { it.group == group && it.key !in prefs.hiddenChannels }
        val order = prefs.channelOrder[group] ?: return list
        val byKey = list.associateBy { it.key }
        return order.mapNotNull { byKey[it] } + list.filterNot { it.key in order }
    }

    /** Looks in the active playlist first, then in other playlists' saved channels (for combined Favorites). */
    fun channel(key: String?): Channel? {
        if (key == null) return null
        channels.firstOrNull { it.key == key }?.let { return it }
        val pid = key.substringBefore(':')
        if (livePlaylists.none { it.id == pid }) return null
        return otherLive[pid]?.first?.firstOrNull { it.key == key }
    }

    fun playlistName(c: Channel?): String = c?.let { ch -> playlists.firstOrNull { ch.key.startsWith(it.id + ":") }?.name } ?: ""

    /** Bumped when other playlists' favorites finish loading, so the guide redraws. */
    var favDataTick by mutableStateOf(0); private set

    /** Loads channels and guides of other playlists that hold favorites. */
    fun ensureFavoriteData() {
        val active = activePlaylist()?.id
        val pids = (prefs.favorites + prefs.lists.flatMap { it.keys }).map { it.substringBefore(':') }.distinct().filter { it != active && !otherLive.containsKey(it) }
        if (pids.isEmpty()) return
        viewModelScope.launch {
            pids.forEach { pid -> livePlaylists.firstOrNull { it.id == pid }?.let { liveFor(it) } }
            favDataTick++
        }
    }

    /** Channel numbers follow the user's group and channel order, skipping hidden groups. */
    fun numbers(): Map<String, Int> {
        val m = HashMap<String, Int>()
        var n = 1
        groups().forEach { g -> channelsIn(g.raw).forEach { m[it.key] = n++ } }
        return m
    }

    /** The category being re-ordered in the TV Guide's category list (null = not re-ordering). */
    var movingGroup by mutableStateOf<String?>(null)

    /** Moves a category to [index] among the visible categories (hidden ones keep their places). */
    fun moveGroupTo(raw: String, index: Int) {
        val all = groups(includeHidden = true).map { it.raw }.toMutableList()
        val visible = groups().map { it.raw }.filter { it != raw }
        if (raw !in all) return
        val i = index.coerceIn(0, visible.size)
        all.remove(raw)
        val at = if (i >= visible.size) all.size else all.indexOf(visible[i])
        all.add(at, raw)
        updatePrefs { it.copy(groupOrder = all) }
    }

    /** Moves a category [delta] places among the visible categories. */
    fun moveGroupBy(raw: String, delta: Int) {
        val i = groups().indexOfFirst { it.raw == raw }
        if (i >= 0) moveGroupTo(raw, i + delta)
    }

    fun moveGroup(raw: String, dir: Int) {
        val order = groups(includeHidden = true).map { it.raw }.toMutableList()
        val i = order.indexOf(raw); val j = i + dir
        if (i < 0 || j !in order.indices) return
        order[i] = order[j].also { order[j] = order[i] }
        updatePrefs { it.copy(groupOrder = order) }
    }

    /** The channel being re-ordered in the TV Guide (null = not re-ordering). */
    var movingChannel by mutableStateOf<String?>(null)

    /** Moves a channel to [index] in the category or list the guide is showing. */
    fun moveChannelTo(key: String, index: Int) {
        val g = selectedGroup ?: return
        val order = channelsIn(g).map { it.key }.toMutableList()
        if (!order.remove(key)) return
        order.add(index.coerceIn(0, order.size), key)
        when {
            g == FAV_GROUP -> updatePrefs { p -> p.copy(favorites = order + p.favorites.filter { it !in order }) }
            g.startsWith(LIST_PREFIX) -> updatePrefs { p -> p.copy(lists = p.lists.map { if (LIST_PREFIX + it.id == g) it.copy(keys = order + it.keys.filter { k -> k !in order }) else it }) }
            else -> updatePrefs { it.copy(channelOrder = it.channelOrder + (g to order)) }
        }
    }

    fun moveChannelBy(key: String, delta: Int) {
        val i = channelsIn(selectedGroup).indexOfFirst { it.key == key }
        if (i >= 0) moveChannelTo(key, i + delta)
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
        val id = hiddenId(activePlaylist()?.id ?: "", raw)
        it.copy(hiddenGroups = if (raw in it.hiddenGroups || id in it.hiddenGroups) it.hiddenGroups - raw - id else it.hiddenGroups + id)
    }

    fun isFavorite(key: String?) = key != null && key in prefs.favorites

    // ---------------- TV Guide options menus (Menu key) ----------------
    var channelMenu by mutableStateOf<String?>(null)
    var groupMenu by mutableStateOf<String?>(null)

    fun moveFavorite(key: String, dir: Int) {
        val l = prefs.favorites.toMutableList(); val i = l.indexOf(key); val j = i + dir
        if (i < 0 || j !in l.indices) return
        l[i] = l[j].also { l[j] = l[i] }
        updatePrefs { it.copy(favorites = l) }
    }

    /** Moves a channel within the category being shown (Favorites has its own order). */
    fun moveInGuide(key: String, dir: Int) = when {
        selectedGroup == FAV_GROUP -> moveFavorite(key, dir)
        selectedGroup?.startsWith(LIST_PREFIX) == true -> moveInList(selectedGroup!!, key, dir)
        else -> moveChannel(key, dir)
    }

    // ---------------- channel lists (Favorites + the user's own) ----------------
    fun userList(group: String): ChannelList? = prefs.lists.firstOrNull { LIST_PREFIX + it.id == group }

    fun listKeys(group: String): List<String> = if (group == FAV_GROUP) prefs.favorites else userList(group)?.keys ?: emptyList()

    /** Every list as (group id, name), Favorites first. */
    fun allLists(): List<Pair<String, String>> = listOf(FAV_GROUP to "★ Favorites") + prefs.lists.map { (LIST_PREFIX + it.id) to (it.name + if (it.hidden) " (hidden)" else "") }

    fun inList(group: String, key: String) = key in listKeys(group)

    fun inAnyList(key: String) = key in prefs.favorites || prefs.lists.any { key in it.keys }

    /** Names of the lists a channel is in, e.g. "Favorites, Kids". */
    fun listsFor(key: String): String = allLists().filter { inList(it.first, key) }.joinToString(", ") { it.second.removePrefix("★ ") }

    /** The channel whose "Add to List" picker is open. */
    var listPickerKey by mutableStateOf<String?>(null)
    /** The list whose Menu-key options are open. */
    var listMenu by mutableStateOf<String?>(null)

    fun toggleInList(group: String, key: String) {
        if (group == FAV_GROUP) return toggleFavorite(key)
        viewModelScope.launch { ensureFavoriteData() }
        updatePrefs { p -> p.copy(lists = p.lists.map { if (LIST_PREFIX + it.id == group) it.copy(keys = if (key in it.keys) it.keys - key else it.keys + key) else it }) }
    }

    /** Makes a new list, optionally starting with one channel. Returns its group id. */
    fun createList(name: String, firstKey: String? = null): String {
        val id = UUID.randomUUID().toString().take(8)
        val n = name.trim().ifBlank { "My list ${prefs.lists.size + 1}" }
        updatePrefs { it.copy(lists = it.lists + ChannelList(id, n, listOfNotNull(firstKey))) }
        if (firstKey != null) viewModelScope.launch { ensureFavoriteData() }
        return LIST_PREFIX + id
    }

    /** Hidden lists keep their channels but don't appear in the TV Guide's menus. */
    fun setListHidden(group: String, hidden: Boolean) {
        updatePrefs { p -> p.copy(lists = p.lists.map { if (LIST_PREFIX + it.id == group) it.copy(hidden = hidden) else it }) }
        if (hidden && selectedGroup == group) { selectedGroup = FAV_GROUP; guideFocusTick++ }
    }

    /** Lists shown in the TV Guide's menus. */
    val shownLists get() = prefs.lists.filter { !it.hidden }

    fun renameList(group: String, name: String) {
        if (name.isBlank()) return
        updatePrefs { p -> p.copy(lists = p.lists.map { if (LIST_PREFIX + it.id == group) it.copy(name = name.trim()) else it }) }
    }

    fun deleteList(group: String) {
        updatePrefs { p -> p.copy(lists = p.lists.filterNot { LIST_PREFIX + it.id == group }) }
        if (selectedGroup == group) { selectedGroup = FAV_GROUP; guideFocusTick++ }
    }

    fun moveList(group: String, dir: Int) {
        val l = prefs.lists.toMutableList(); val i = l.indexOfFirst { LIST_PREFIX + it.id == group }; val j = i + dir
        if (i < 0 || j !in l.indices) return
        l[i] = l[j].also { l[j] = l[i] }
        updatePrefs { it.copy(lists = l) }
    }

    private fun moveInList(group: String, key: String, dir: Int) {
        val lst = userList(group) ?: return
        val l = lst.keys.toMutableList(); val i = l.indexOf(key); val j = i + dir
        if (i < 0 || j !in l.indices) return
        l[i] = l[j].also { l[j] = l[i] }
        updatePrefs { p -> p.copy(lists = p.lists.map { if (it.id == lst.id) it.copy(keys = l) else it }) }
    }

    fun hideChannel(key: String) = updatePrefs { it.copy(hiddenChannels = it.hiddenChannels + key) }
    fun showChannel(key: String) = updatePrefs { it.copy(hiddenChannels = it.hiddenChannels - key) }

    fun toggleFavorite(key: String?) {
        key ?: return
        viewModelScope.launch { ensureFavoriteData() }
        updatePrefs { it.copy(favorites = if (key in it.favorites) it.favorites - key else it.favorites + key) }
    }

    fun favoriteChannels(): List<Channel> = prefs.favorites.mapNotNull { channel(it) }

    /** Favorites from every playlist (a favorite added from search may belong to another playlist). */
    var otherFavorites by mutableStateOf<List<Pair<Playlist, Channel>>>(emptyList()); private set

    fun loadOtherFavorites() {
        val active = activePlaylist()?.id
        viewModelScope.launch {
            val out = ArrayList<Pair<Playlist, Channel>>()
            for (p in shownPlaylists) {
                if (p.id == active || prefs.favorites.none { it.startsWith(p.id + ":") }) continue
                val chs = liveFor(p).first.associateBy { it.key }
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
    fun programs(c: Channel?): List<Program> {
        val id = c?.epgId ?: return emptyList()
        val pid = c.key.substringBefore(':')
        return if (pid == activePlaylist()?.id) guide[id] ?: emptyList() else otherLive[pid]?.second?.get(id) ?: emptyList()
    }

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
        if (ctx == PlayContext.GROUP && !isListGroup(selectedGroup)) selectedGroup = c.group
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
            if (!isListGroup(selectedGroup)) channel(currentKey)?.let { selectedGroup = it.group }
            screen = Screen.GUIDE
            navMode = NavMode.GROUPS
            guideFocusTick++
        } else {
            screen = lastScreen
        }
    }

    // ---------------- Discover (TMDB) ----------------
    fun tmdbKey(): String = if (prefs.tmdbKeyEnc.isBlank()) "" else Crypto.decrypt(prefs.tmdbKeyEnc)

    /** Country for release dates and streaming services, from the postal code (US by default). */
    fun region(): String = if (Regex("^[A-Za-z]\\d").containsMatchIn(prefs.postalCode)) "CA" else "US"

    fun setTmdbKey(key: String) {
        val k = key.trim()
        viewModelScope.launch {
            if (k.isEmpty()) { updatePrefs { it.copy(tmdbKeyEnc = "") }; tmdbStatus = "Key removed."; return@launch }
            tmdbStatus = "Checking the key…"
            val ok = runCatching { withContext(Dispatchers.IO) { Tmdb.trending(k) } }
            ok.onSuccess {
                updatePrefs { p -> p.copy(tmdbKeyEnc = Crypto.encrypt(k)) }
                tmdbStatus = "Key saved. Discover is ready."
                discoverLists = emptyMap(); discoverLoadedAt.clear()
                loadServices()
            }.onFailure { tmdbStatus = "That key didn't work: ${it.message}" }
        }
    }

    fun loadServices() {
        val key = tmdbKey(); if (key.isBlank()) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { Tmdb.services(key, region()) } }.onSuccess { availableServices = it }
        }
    }

    fun toggleService(s: WatchProvider) {
        updatePrefs { p -> p.copy(myServices = if (p.myServices.any { it.id == s.id }) p.myServices.filterNot { it.id == s.id } else p.myServices + s) }
        discoverLoadedAt.remove("new")
    }

    fun openDiscover() {
        screen = Screen.DISCOVER
        loadDiscover(discoverTab)
    }

    fun chooseDiscoverTab(t: String) {
        discoverTab = t
        loadDiscover(t)
    }

    fun loadDiscover(tab: String, force: Boolean = false) {
        val key = tmdbKey()
        if (key.isBlank()) { discoverError = null; return }
        val fresh = System.currentTimeMillis() - (discoverLoadedAt[tab] ?: 0L) < 6 * 3600 * 1000L
        if (!force && fresh && discoverLists[tab] != null) return
        viewModelScope.launch {
            discoverLoading = true; discoverError = null; discoverNote = null
            val region = region(); val services = prefs.myServices.map { it.id }
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    when (tab) {
                        "new" -> {
                            val m = Tmdb.newMovies(key, region, services); val s = Tmdb.newShows(key, region, services)
                            (m.zip(s).flatMap { listOf(it.first, it.second) } + m.drop(s.size) + s.drop(m.size))
                        }
                        "trending" -> Tmdb.trending(key)
                        "theaters" -> Tmdb.inTheaters(key, region)
                        else -> forYou(key)
                    }
                }
            }
            res.onSuccess { list ->
                discoverLists = discoverLists + (tab to list.distinctBy { it.key })
                discoverLoadedAt[tab] = System.currentTimeMillis()
                fetchWhere(list.take(36))
            }.onFailure { discoverError = it.message }
            discoverLoading = false
        }
    }

    /** Blends what you watch here (My List, titles you started) with trending titles in the same genres. */
    private fun forYou(key: String): List<Title> {
        val seeds = (prefs.myList + vod.filter { resumeOf(it.key) > 0 }).distinctBy { it.key }.take(6)
        val trending = Tmdb.trending(key)
        if (seeds.isEmpty()) {
            discoverNote = "Add titles to My List or start watching movies and series, and these picks will follow your taste. Showing what's trending for now."
            return trending
        }
        val found = seeds.mapNotNull { v -> runCatching { Tmdb.find(key, v.name, if (v.kind == "series") "tv" else "movie") }.getOrNull() }
        val score = HashMap<String, Pair<Title, Double>>()
        found.forEach { seed ->
            runCatching { Tmdb.recommendations(key, seed) }.getOrDefault(emptyList()).forEachIndexed { i, t ->
                val prev = score[t.key]
                score[t.key] = t to ((prev?.second ?: 0.0) + 2.0 - i * 0.05)
            }
        }
        val likedGenres = found.flatMap { it.genres }.groupingBy { it }.eachCount()
        trending.forEach { t ->
            val g = t.genres.sumOf { likedGenres[it] ?: 0 }
            if (g > 0) { val prev = score[t.key]; score[t.key] = t to ((prev?.second ?: 0.0) + 0.5 * g) }
        }
        val seedKeys = found.map { it.key }.toSet()
        discoverNote = "Based on " + seeds.take(3).joinToString(", ") { Tmdb.cleanName(it.name) } + (if (seeds.size > 3) " and more" else "") + ", plus what's trending."
        return score.values.filter { it.first.key !in seedKeys }.sortedByDescending { it.second }.map { it.first }.take(60)
    }

    private fun fetchWhere(list: List<Title>) {
        val key = tmdbKey(); val region = region()
        viewModelScope.launch {
            for (t in list) {
                if (whereCache.containsKey(t.key)) continue
                runCatching { withContext(Dispatchers.IO) { Tmdb.whereToWatch(key, t, region) } }.onSuccess { whereCache = whereCache + (t.key to it) }
            }
        }
    }

    fun openTitle(t: Title) {
        discoverDetail = t
        if (!whereCache.containsKey(t.key)) fetchWhere(listOf(t))
    }

    /** The same title in the user's own provider catalog, if there is one. */
    fun matchVod(t: Title): VodItem? {
        if (vodIndexFor !== vod) {
            vodIndex = HashMap<String, VodItem>().apply {
                vod.forEach { v -> putIfAbsent((if (v.kind == "series") "tv|" else "movie|") + Tmdb.normalize(v.name), v) }
            }
            vodIndexFor = vod
        }
        return vodIndex[t.type + "|" + Tmdb.normalize(t.name)]
    }

    fun playMatched(v: VodItem) { if (v.kind == "series") openVodDetail(v) else playVod(v) }

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
                if (kws.isEmpty() && cat.groups.isEmpty()) continue
                fun hasKw(s: String) = kws.isNotEmpty() && kws.any { s.contains(it) }
                for (c in chs) {
                    val catChannel = c.group in cat.groups || hasKw(((groupsByRaw[c.group] ?: c.group) + " " + c.group + " " + (names[c.key] ?: c.name)).lowercase())
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

    /** Adds a playlist category (e.g. "FLO Sports") to the Sports tab. */
    fun addGroupToSports(raw: String) {
        if (prefs.sportCats.any { raw in it.groups }) return
        updateSportCats(prefs.sportCats + SportCat("g:$raw", groupName(raw), emptyList(), true, false, listOf(raw)))
    }

    fun removeGroupFromSports(raw: String) = updateSportCats(prefs.sportCats.filterNot { raw in it.groups && !it.builtin })

    fun isSportsGroup(raw: String) = prefs.sportCats.any { raw in it.groups }

    fun openSportsScreen() {
        screen = Screen.SPORTS
        viewModelScope.launch { refreshScores(); rebuildSportEvents() }
    }

    // ---------------- search (all playlists) ----------------
    /** Live channels + guide of a playlist; nothing at all when its live TV is off. Never touches movies & series. */
    private suspend fun liveFor(p: Playlist): Pair<List<Channel>, Map<String, List<Program>>> {
        if (!p.liveOn || !p.enabled) return emptyList<Channel>() to emptyMap()
        if (p.id == activePlaylist()?.id) return channels to guide
        otherLive[p.id]?.let { return it }
        val t = withContext(Dispatchers.IO) { store.loadChannels(p.id).distinctBy { it.key } to store.loadGuide(p.id).first }
        otherLive[p.id] = t
        return t
    }

    /** Movies & series of a playlist; nothing when they're off. Only search uses other playlists' catalogs. */
    private suspend fun vodFor(p: Playlist): List<VodItem> {
        if (!p.vodOn || !p.enabled) return emptyList()
        if (vodLoadedFor?.split(',')?.contains(p.id) == true) return vod.filter { it.playlistId == p.id }
        otherVod[p.id]?.let { return it }
        val v = withContext(Dispatchers.IO) { store.loadVod(p.id).distinctBy { it.key } }
        otherVod[p.id] = v
        return v
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
            for (p in shownPlaylists) {
                if (!p.liveOn && !p.vodOn) continue
                val (chs, g) = liveFor(p)
                val v = vodFor(p)
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
                    val vodHits = v.filter { !isVodHidden(it) && it.name.lowercase().contains(needle) }
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
        val p = shownPlaylists.firstOrNull { it.id == pid } ?: return
        if (!p.liveOn) return
        val (chs, g) = liveFor(p)
        updatePrefs { it.copy(activePlaylist = p.id) }
        channels = chs; guide = g
        selectedGroup = null; currentKey = null
        applyChannels(chs)
        rebuildSportEvents()
    }

    // ---------------- left navigation ----------------
    /** Set when a screen is picked in the left menu; that screen's first item takes the highlight once. */
    var entryFocusFor by mutableStateOf<Screen?>(null)
    /** When the last remote button was pressed (so a late automatic highlight never overrides the viewer). */
    var lastKeyAt = 0L

    fun navSelect(target: Screen) {
        entryFocusFor = if (target == Screen.PLAYLISTS || target == Screen.MANAGE) Screen.SETTINGS else target
        val s = if (target == Screen.LIVE) Screen.GUIDE else target
        if (s == Screen.FAVORITES) {
            ensureFavoriteData()
            screen = Screen.GUIDE; selectedGroup = FAV_GROUP; navMode = NavMode.GROUPS; guideFocusTick++
            return
        }
        if (s == Screen.DISCOVER) { ensureVod(); openDiscover(); navMode = NavMode.COLLAPSED; return }
        if (s == Screen.MOVIES || s == Screen.SERIES) { closeVodDetail(); ensureVod() }
        if (s == Screen.SPORTS) openSportsScreen() else {
            when (s) {
                Screen.SETTINGS -> { settingsSection = "playlists"; screen = s }
                Screen.MANAGE -> { settingsSection = "channels"; screen = Screen.SETTINGS }
                Screen.PLAYLISTS -> { settingsSection = "playlists"; screen = Screen.SETTINGS }
                else -> screen = s
            }
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
            val (chs, _) = liveFor(p)
            val counts = LinkedHashMap<String, Int>()
            chs.forEach { counts[it.group] = (counts[it.group] ?: 0) + 1 }
            val list = counts.filterKeys { !isGroupHidden(p.id, it) }.map { (raw, n) -> GroupInfo(raw, groupName(raw), n, false) }
            playlistGroups = playlistGroups + (p.id to list)
        }
    }

    /** Picking a category: switch playlist if needed, hide the menu and show categories as the left nav. */
    fun chooseGroup(pid: String, raw: String) {
        viewModelScope.launch {
            val same = raw == selectedGroup && pid == activePlaylist()?.id
            switchPlaylist(pid)
            // A newly chosen category starts at its first channel; choosing the same one keeps the scroll position.
            guideFocusMode = if (same) GuideFocus.KEEP else GuideFocus.TOP
            if (!same) guideScroll = Triple(raw, 0, 0)
            selectedGroup = raw
            navMode = NavMode.GROUPS
            guideFocusTick++
        }
    }

    /** Back walks up the navigation: categories → playlists → full menu → TV Guide. Returns false to let the app exit. */
    fun navBack(): Boolean = if (movingGroup != null || movingChannel != null || vodMovingCat != null) { movingGroup = null; movingChannel = null; vodMovingCat = null; true } else when (navMode) {
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

    /** Every movie (or series) of the switched-on playlists, hidden ones included. */
    fun vodAll(kind: String): List<VodItem> = vod.filter { if (kind == "series") it.kind == "series" else it.kind == "movie" || it.kind == "episode" }

    /** "movie" or "series": the editor's name for a VOD kind. */
    fun vodKindKey(kind: String) = if (kind == "series") "series" else "movie"

    fun vodCatKey(kind: String, pid: String, cat: String) = vodKindKey(kind) + "|" + pid + "|" + cat

    fun isVodCatHidden(kind: String, pid: String, cat: String) = vodCatKey(kind, pid, cat) in prefs.vodHiddenCats

    fun isVodHidden(v: VodItem) = v.key in prefs.vodHiddenTitles || isVodCatHidden(v.kind, v.playlistId, v.category.ifBlank { "Other" })

    /** What the Movies / Series screens show: hidden titles and categories left out. */
    fun vodOf(kind: String): List<VodItem> = vodAll(kind).filter { !isVodHidden(it) }

    fun vodCatName(kind: String, cat: String) = prefs.vodCatNames[vodKindKey(kind) + "|" + cat] ?: cat

    /** Orders category names by the user's order (new ones keep the provider's order, after). */
    fun orderVodCats(kind: String, names: List<String>): List<String> {
        val ord = prefs.vodCatOrder[vodKindKey(kind)] ?: return names
        return ord.filter { it in names } + names.filterNot { it in ord }
    }

    fun vodCategories(kind: String): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        vodOf(kind).forEach { counts[it.category.ifBlank { "Other" }] = (counts[it.category.ifBlank { "Other" }] ?: 0) + 1 }
        return orderVodCats(kind, counts.keys.toList()).map { it to (counts[it] ?: 0) }
    }

    // ---- Movies / Series category Menu: rename, re-order, hide ----
    var vodCatMenu by mutableStateOf<Pair<String, String>?>(null)   // kind to category
    var vodMovingCat by mutableStateOf<Pair<String, String>?>(null)

    fun renameVodCat(kind: String, cat: String, name: String) = updatePrefs {
        val k = vodKindKey(kind) + "|" + cat
        it.copy(vodCatNames = if (name.isBlank() || name == cat) it.vodCatNames - k else it.vodCatNames + (k to name.trim()))
    }

    fun moveVodCatTo(kind: String, cat: String, index: Int) {
        val names = vodCategories(kind).map { it.first }.toMutableList()
        if (!names.remove(cat)) return
        names.add(index.coerceIn(0, names.size), cat)
        val k = vodKindKey(kind)
        val old = prefs.vodCatOrder[k] ?: emptyList()
        updatePrefs { it.copy(vodCatOrder = it.vodCatOrder + (k to (names + old.filterNot { n -> n in names }))) }
    }

    fun moveVodCatBy(kind: String, cat: String, delta: Int) {
        val i = vodCategories(kind).indexOfFirst { it.first == cat }
        if (i >= 0) moveVodCatTo(kind, cat, i + delta)
    }

    /** Hides a category everywhere it appears (in every playlist that has it). */
    fun hideVodCatEverywhere(kind: String, cat: String) {
        val keys = vodAll(kind).filter { it.category.ifBlank { "Other" } == cat }.map { vodCatKey(kind, it.playlistId, cat) }.toSet()
        updatePrefs { it.copy(vodHiddenCats = it.vodHiddenCats + keys) }
        if (vodCategory[kind] == cat) vodCategory = vodCategory + (kind to "All")
    }

    /** List Editor: show or hide many movie/series categories (keys from [vodCatKey]). */
    fun setVodCatsHidden(keys: Collection<String>, hidden: Boolean) =
        updatePrefs { it.copy(vodHiddenCats = if (hidden) it.vodHiddenCats + keys else it.vodHiddenCats - keys.toSet()) }

    /** List Editor: show or hide many movies/series. */
    fun setVodTitlesHidden(keys: Collection<String>, hidden: Boolean) =
        updatePrefs { it.copy(vodHiddenTitles = if (hidden) it.vodHiddenTitles + keys else it.vodHiddenTitles - keys.toSet()) }

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
