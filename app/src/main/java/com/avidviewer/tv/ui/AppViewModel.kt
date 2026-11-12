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
import com.avidviewer.tv.data.Catalog
import com.avidviewer.tv.data.EpgSource
import com.avidviewer.tv.data.GuideChannel
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
import com.avidviewer.tv.data.Game
import com.avidviewer.tv.data.ChannelMatch
import com.avidviewer.tv.data.VodItem
import com.avidviewer.tv.data.XtreamVod
import com.avidviewer.tv.data.Weather
import com.avidviewer.tv.data.WeatherService
import com.avidviewer.tv.data.Xmltv
import com.avidviewer.tv.data.Xtream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.withLock
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
    /** Movies and series of every playlist, on the device; screens read pages from it. */
    val catalog = com.avidviewer.tv.data.Catalog(app)
    /** Live channels and guide programmes of every playlist, in the same database. */
    val live = com.avidviewer.tv.data.LiveDb(catalog)
    /** One guide download per playlist at a time (declared before init, which can start one). */
    private val guideLocks = HashMap<String, kotlinx.coroutines.sync.Mutex>()
    /** Bumped whenever the catalog's contents or hidden flags change, so lists are read again. */
    var vodVersion by mutableStateOf(0); private set

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
    /** Where the highlight and scroll were in each guide category and Movies/Series category (by item key). */
    val nav = NavStore()
    /** The guide's place for a category of the playlist being watched. */
    fun guidePath(group: String?) = "guide/${activePlaylist()?.id}/$group"
    /** The Movies / Series grid's place for a category and search. */
    fun vodPath(kind: String, category: String, query: String) = "vod/$kind/$category|$query"
    /** Set when a title's page closes: the grid puts the highlight back on that title. */
    var vodFocusBack by mutableStateOf(0); private set

    /** Movies / Series category: each category keeps its own place (a new one starts at the top). */
    fun chooseVodCategory(kind: String, name: String) {
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
    var vodCategory by mutableStateOf(mapOf("movie" to "All", "series" to "All"))
    /** The player is actually playing right now (false while buffering or paused); screens redraw when it changes. */
    var playing by mutableStateOf(false); private set
    /** The viewer paused (as opposed to buffering). The full-screen bar stays up only while paused. */
    var paused by mutableStateOf(false); private set
    var vodDetail by mutableStateOf<VodItem?>(null); private set
    var vodInfo by mutableStateOf<VodInfo?>(null); private set
    var detailEpisodes by mutableStateOf<List<Episode>?>(null); private set
    var detailSeason by mutableStateOf<String?>(null)
    /** Whether anything on screen has the highlight; a remote key with nothing highlighted asks for it back. */
    var anyFocus = false
    /** Recent focus-placement steps, for test diagnostics. */
    val focusTrail = java.util.concurrent.ConcurrentLinkedDeque<String>()
    fun trail(s: String) { focusTrail.add(s); while (focusTrail.size > 40) focusTrail.pollFirst() }
    var focusRescue by mutableStateOf(0)
    /** The episode last started from a series page, so Back from the player lands on it again. */
    var lastEpisode: Episode? = null
    private var resume: Map<String, Long> = store.loadResume()
    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<PlaylistHits>?>(null); private set
    var searching by mutableStateOf(false); private set
    var seriesOpen by mutableStateOf<VodItem?>(null); private set
    var episodes by mutableStateOf<List<Episode>?>(null); private set
    /** Games from ESPN with the channels found for them (Sports screen). */
    var sportGames by mutableStateOf<List<SportGame>>(emptyList()); private set
    /** The league filter on the Sports screen (null = All). */
    var sportLeague by mutableStateOf<String?>(null)
    /** Why the list may be stale ("Couldn't reach ESPN…"), or null. */
    var sportsNote by mutableStateOf<String?>(null); private set
    var sportsLoading by mutableStateOf(false); private set
    var settingsSection by mutableStateOf("playlists")
    /** Search boxes on the TV Guide, Movies and Series screens (applied when OK/Search is pressed). */
    var guideQuery by mutableStateOf("")
    var vodQuery by mutableStateOf(mapOf("movie" to "", "series" to ""))
    var update by mutableStateOf<Updater.Release?>(null); internal set
    var updateStatus by mutableStateOf<String?>(null); private set
    var updateBusy by mutableStateOf(false); private set
    /** The downloaded update, when the installer needs a second way in ("Open installer"). */
    var updateFile by mutableStateOf<java.io.File?>(null); private set
    private var installAnswered = false
    var updatePromptDismissed by mutableStateOf(false)
    /**
     * The update dialog may open by itself only if the new version was found before the first remote
     * button press after launch. Found later, it waits: the menu shows "Update …" and Settings › Updates has it.
     */
    var updatePromptAllowed by mutableStateOf(false); private set
    val installed: Pair<Long, String> = runCatching { Updater.installedVersion(app) }.getOrDefault(0L to "")
    /** The game highlighted on the Sports screen. */
    var selectedGame by mutableStateOf<String?>(null)
    private var sportsPlayLeague: String? = null
    /** Matched channels of other playlists, so they can be shown and played (only those). */
    private var sportsChannels: Map<String, Channel> = emptyMap()
    private var sportsFetchedAt = 0L
    /**
     * Other playlists' channels that are in ★ Favorites or a list, with their guide: only those channels,
     * never whole playlists, so memory stays small however many playlists there are.
     */
    private val otherLive = HashMap<String, Pair<List<Channel>, Map<String, List<Program>>>>()
    /** Kept for call sites that drop a playlist's cache; nothing is cached here any more. */
    private val otherVod = HashMap<String, List<VodItem>>()
    /** Other playlists' channels found by the TV Guide's search box, with their guide; dropped when search ends. */
    private var searchLive: Map<String, Pair<List<Channel>, Map<String, List<Program>>>> = emptyMap()
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
                        com.avidviewer.tv.data.Backup.Contents(playlists, prefs, tmdbKey(), history, resume, System.currentTimeMillis(), installed.second, smbPassword(), backupRefs()), pin,
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
            withContext(Dispatchers.IO) { catalog.retainOnly(c.playlists.map { it.id }); live.retainOnly(c.playlists.map { it.id }) }
            vodVersion++
            // Point favorites, lists etc. at the right channels even if the provider's list has changed since the backup.
            placeRestoredRefs(c.refs)
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

    /** Other playlists' channels found by the TV Guide's search box (only the matches). */
    var searchPool by mutableStateOf<List<Channel>>(emptyList()); private set
    /** Bumped when other playlists' favorites finish loading, so the guide redraws. */
    var favDataTick by mutableStateOf(0); private set
    // Declared before init: startup code below already asks for these (a later declaration
    // would still be null when the saved channels load quickly).
    // ---- derived lists, computed once per data change (screens ask for them on every highlight move) ----
    /** Remembers one value until any of its inputs is replaced by a different object. */
    private class Memo<V> {
        private var inputs: Array<out Any?>? = null
        private var value: Any? = null
        @Suppress("UNCHECKED_CAST")
        @Synchronized fun get(vararg now: Any?, compute: () -> V): V {
            val last = inputs
            // Same object, or an equal string/number (those are often new instances with the same value).
            fun same(a: Any?, b: Any?) = a === b || ((a is String || a is Number) && a == b)
            if (last != null && last.size == now.size && last.indices.all { same(last[it], now[it]) }) return value as V
            val v = compute(); inputs = now; value = v
            return v
        }
    }
    private val groupsMemo = Memo<List<GroupInfo>>()
    private val groupsAllMemo = Memo<List<GroupInfo>>()
    private val numbersMemo = Memo<Map<String, Int>>()
    private val channelIndexMemo = Memo<Map<String, Channel>>()
    private val channelsInMemo = Memo<HashMap<String, List<Channel>>>()
    private val vodOfMemo = HashMap<String, Memo<List<VodItem>>>()
    private val vodCatsMemo = HashMap<String, Memo<List<Pair<String, Int>>>>()
    private val vodItemsMemo = HashMap<String, Memo<List<VodItem>>>()
    private val vodAllMemo = HashMap<String, Memo<List<VodItem>>>()
    private val matchCache = HashMap<String, VodItem?>()
    private val versionsCache = HashMap<String, List<VodItem>>()
    private var matchCacheFor = -1

    init {
        if (prefs.clearCacheOnExit) store.clearCache() // catches exits Android didn't report
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { paused = !playWhenReady }
            override fun onPlayerError(error: PlaybackException) {
                playerError = "Can't play this channel (${error.errorCodeName}). Try another channel, or switch Stream format in Settings."
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) playerError = null
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { tracksChanged(tracks) }
        })
        viewModelScope.launch {
            while (true) {
                now = System.currentTimeMillis()
                delay(15_000)
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                // Schedules and scores: every minute while Sports is on screen and a game is live, every
                // 5 minutes otherwise while it's on screen; in the background only every 30 minutes.
                val sportsVisible = screen == Screen.SPORTS || (screen == Screen.PLAYER && playContext == PlayContext.SPORTS)
                val age = System.currentTimeMillis() - sportsFetchedAt
                val due = if (sportsVisible) (if (sportGames.any { it.game.live }) 60_000L else 5 * 60_000L) else 30 * 60_000L
                if (sportsFetchedAt > 0 && age >= due) refreshSports()
            }
        }
        viewModelScope.launch {
            while (true) {
                refreshWeather()
                delay(30 * 60 * 1000L) // every 30 minutes
            }
        }
        // The guide downloads again on the chosen schedule even when the app stays open for days.
        viewModelScope.launch {
            delay(15 * 60 * 1000L)
            while (true) {
                runCatching {
                    activePlaylist()?.let { p ->
                        val at = withContext(Dispatchers.IO) { live.guideSavedAt(p.id) }
                        if (at > 0 && System.currentTimeMillis() - at > guideMs() && guideBusy == null) refreshGuide(p)
                    }
                    syncAllPlaylists(force = false)
                }
                delay(15 * 60 * 1000L)
            }
        }
        Updater.onStatus = { msg ->
            viewModelScope.launch {
                installAnswered = true
                updateBusy = false
                updateStatus = if (msg == Updater.NO_CONFIRM) "The installer screen didn't open. Choose Open installer to finish." else msg
            }
        }
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
            for (p in pls) {
                loadVodSource(p, refresh, sig)
                if (vodLoadedFor != sig) return@launch
                vod = allTitles(pls.map { it.id })
                vodVersion++
            }
            vodLoading = false
        }
    }

    /** Every title of these playlists (movies, episodes and series), read from the catalog a page at a time. */
    private fun allTitles(pids: List<String>): List<VodItem> {
        val a = com.avidviewer.tv.data.PagedTitles(catalog, com.avidviewer.tv.data.Catalog.Query("movie", pids, includeHidden = true))
        val b = com.avidviewer.tv.data.PagedTitles(catalog, com.avidviewer.tv.data.Catalog.Query("series", pids, includeHidden = true))
        return object : AbstractList<VodItem>(), RandomAccess {
            override val size get() = a.size + b.size
            override fun get(index: Int) = if (index < a.size) a[index] else b[index - a.size]
            // By identity, like PagedTitles: comparing items would read the whole catalog.
            override fun equals(other: Any?) = this === other
            override fun hashCode() = System.identityHashCode(this)
        }
    }

    /** Titles saved by versions before the catalog database: move them in once (any screen may need them). */
    private suspend fun importOldTitles(p: Playlist): Boolean = withContext(Dispatchers.IO) {
        var oldFormat = false
        if (!catalog.has(p.id)) {
            val saved = store.loadVod(p.id).distinctBy { it.key }
            if (saved.isNotEmpty()) {
                oldFormat = saved.all { it.category.isBlank() }
                catalog.replace(p.id, saved.asSequence()) { vodHiddenNow(it) }
            }
            store.deleteVodFile(p.id)
        }
        oldFormat
    }

    /** Hidden flag for a title being saved to the catalog. */
    private fun vodHiddenNow(v: VodItem) = isVodHidden(v)

    /** Loads (and downloads when needed) one playlist's movies & series; returns them. */
    private suspend fun loadVodSource(p: Playlist, refresh: Boolean, sig: String) {
        vodLoading = true
        val oldFormat = importOldTitles(p)
        if (vodLoadedFor != sig) return
        val empty = withContext(Dispatchers.IO) { !catalog.has(p.id) }
        // An M3U playlist with live TV off: its movies come from the same file, so read it just for them.
        if (!p.isXtream && (refresh || empty) && !p.liveOn) {
            status = "Loading movies from ${p.name}…"
            runCatching {
                val done = ingestVod(p.id) { add -> M3u.read(p, add) }
                status = "Loaded ${done.added} movies and series."
            }.onFailure { vodError = "Movies couldn't load: ${it.message}" }
        }
        // Re-download when asked, when nothing is saved, or when the saved list predates categories.
        if (p.isXtream && (refresh || empty || oldFormat)) {
            status = "Loading movies from ${p.name}…"
            val (movies, series, err) = ingestXtreamVod(p) { viewModelScope.launch { status = "Loading series…" } }
            vodError = err
            status = vodError ?: "Loaded $movies movies and $series series."
        }
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
            m3uUrl = m3u.trim(), liveOn = liveOn, vodOn = vodOn,
            // A guide address typed when adding becomes the playlist's first guide source (the provider's guide stays on too).
            epgSources = if (epg.isBlank()) emptyList() else listOf(EpgSource("src-" + System.currentTimeMillis().toString(36), "Guide address", epg.trim())),
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
            password = pass.ifEmpty { old.password }, m3uUrl = m3u.trim(),
            epgSources = if (epg.isBlank() || old.epgSources.any { it.url == epg.trim() }) old.epgSources
                else old.epgSources + EpgSource("src-" + System.currentTimeMillis().toString(36), "Guide address", epg.trim()),
        )
        viewModelScope.launch {
            busy = true
            status = "Checking ${p.name}…"
            val sourceChanged = p.type != old.type || p.server != old.server || p.username != old.username ||
                p.password != old.password || p.m3uUrl != old.m3uUrl
            val problem = if (!sourceChanged) null else withContext(Dispatchers.IO) {
                if (p.isXtream) Xtream.checkLogin(p) else if (p.m3uUrl.isBlank()) "Enter the playlist link." else null
            }
            if (problem != null) { status = problem; busy = false; return@launch }
            replacePlaylist(p)
            busy = false
            status = "Saved ${p.name}."
            playlistSaved++
            if (sourceChanged) {
                withContext(Dispatchers.IO) { store.deletePlaylistData(p.id); live.delete(p.id) }
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

    private fun rebuildSportEventsLater() { viewModelScope.launch { rematchSports() }; refreshSuggestions() }

    fun removePlaylist(id: String) {
        val wasActive = activePlaylist()?.id == id
        val gone = playlists.firstOrNull { it.id == id }?.name ?: "Playlist"
        playlists = playlists.filterNot { it.id == id }
        store.savePlaylists(playlists)
        store.deletePlaylistData(id)
        catalog.delete(id); live.delete(id); vodVersion++
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
                withContext(Dispatchers.IO) { catalog.markStale(id) }
                vodLoadedFor = null
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
            val cached = if (p.liveOn) withContext(Dispatchers.IO) { liveReady(); live.channels(p.id) } else emptyList()
            if (cached.isNotEmpty()) applyChannels(cached)
            if (p.liveOn && (refresh || download || cached.isEmpty())) {
                status = "Loading channels from ${p.name}…"
                try {
                    downloadLive(p)
                    val list = withContext(Dispatchers.IO) { live.channels(p.id) }
                    applyChannels(list)
                    status = "Loaded ${list.size} channels."
                    otherLive.remove(p.id)
                    if (!p.isXtream && p.vodOn) {
                        otherVod.remove(p.id)
                        if (vodLoadedFor?.split(',')?.contains(p.id) == true) vodLoadedFor = null
                    }
                } catch (e: Exception) {
                    status = if (cached.isEmpty()) "Couldn't load channels: ${e.message}" else "Couldn't refresh, showing saved channels."
                }
            }
            if (p.liveOn) {
                val (savedGuide, savedAt) = withContext(Dispatchers.IO) { guideWindow(p.id, null) to live.guideSavedAt(p.id) }
                if (savedGuide.isNotEmpty()) guide = savedGuide
                val stale = System.currentTimeMillis() - savedAt > guideMs()
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
            // One playlist at a time, so only one channel list is in memory.
            val found = ArrayList<Suggestion>()
            for (p in livePlaylists) {
                val chs = liveFor(p).first
                found.addAll(withContext(Dispatchers.Default) { LocalSuggest.find(listOf(p to chs), place, { raw -> groupName(raw) }) })
            }
            allSuggestions = found.take(40)
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
            val n = downloadGuide(p, channels.mapNotNull { epgIdOf(it) }.toHashSet()) ?: return
            guide = withContext(Dispatchers.IO) { guideWindow(p.id, null) }
            rematchSports()
            status = "Guide loaded for $n channels."
        } catch (e: Exception) {
            status = "Couldn't load the TV guide: ${e.message}"
        }
    }

    // ---------------- stable channel keys (saved choices follow a changed list) ----------------
    /** The guide times kept and shown: from 3 hours ago to 36 hours ahead. */
    private fun guideFrom() = System.currentTimeMillis() - 3 * 3600 * 1000L
    private fun guideTo() = System.currentTimeMillis() + 36 * 3600 * 1000L

    /** A playlist's saved programmes for [ids] (null = every channel) in the guide's time window. */
    private fun guideWindow(pid: String, ids: Collection<String>?) = live.guide(pid, ids, guideFrom(), guideTo())

    /** Moves channel and guide files of older versions into the database (once; cheap afterwards). Call off the main thread. */
    private fun liveReady() { if (live.hasOldFiles(store)) live.importFiles(store) }

    /**
     * Downloads a playlist's live channels straight into the database (an M3U file's movies & series are
     * saved from the same read when VOD is on), then moves favorites, lists, renames etc. onto changed
     * keys. The old list stays if the download fails. Returns how many channels came in.
     */
    private suspend fun downloadLive(p: Playlist): Int {
        withContext(Dispatchers.IO) { liveReady() }
        val wanted = with(com.avidviewer.tv.data.ChannelKeys) { prefs.referencedKeys(resume, history) }.filter { it.startsWith(p.id + ":") }
        val old = withContext(Dispatchers.IO) {
            live.channelRefs(p.id, wanted) + store.loadKeyRefs(p.id).filter { !it.isVod }
        }
        val fmt = prefs.streamFormat
        val count = if (!p.isXtream && p.vodOn) {
            // One read of the file fills both (the channels' save sits inside the titles' save).
            ingestVod(p.id) { addVod -> live.replacingChannels(p.id) { addCh -> M3u.read(p, addCh, addVod) }.added }.result
        } else withContext(Dispatchers.IO) {
            // An empty answer (an expired account, a server error page) keeps the saved list.
            live.replacingChannels(p.id, keep = { _: Int, n -> n > 0 }) { add ->
                if (p.isXtream) Xtream.liveChannels(p, fmt, add) else M3u.read(p, add, null)
            }.added
        }
        val m = withContext(Dispatchers.IO) {
            val map = live.remapChannels(p.id, old)
            store.saveKeyRefs(p.id, store.loadKeyRefs(p.id).filter { it.isVod })
            map
        }
        applyKeyRemap(m)
        return count
    }

    /** The same for a playlist's movies & series (resume points, My List, hidden titles, history). */
    /**
     * Saves a playlist's movies & series as [fill] hands them over (straight from the download,
     * nothing collected in memory), then moves saved choices to any changed keys. The old titles stay
     * if [fill] throws, or when [keep] says no; returns what [fill] returned and how many were added.
     */
    private suspend fun <T> ingestVod(
        playlistId: String,
        keep: (T, Int) -> Boolean = { _, _ -> true },
        fill: ((VodItem) -> Unit) -> T,
    ): com.avidviewer.tv.data.Ingest<T> {
        val wanted = with(com.avidviewer.tv.data.ChannelKeys) { prefs.referencedKeys(resume, history) }.filter { it.startsWith("$playlistId:") }
        val (done, m, leftover) = withContext(Dispatchers.IO) {
            val refs = store.loadKeyRefs(playlistId)
            // Only titles the user's choices point at need following to their new keys.
            val old = (if (wanted.isEmpty()) emptyList() else catalog.refs(playlistId, wanted)) + refs.filter { it.isVod }
            val done = catalog.replacing(playlistId, { vodHiddenNow(it) }, keep, fill)
            if (!done.committed) return@withContext Triple(done, emptyMap(), null)
            val map = catalog.remap(playlistId, old)
            store.deleteVodFile(playlistId)
            Triple(done, map, refs.filter { !it.isVod })
        }
        if (!done.committed) return done
        withContext(Dispatchers.IO) { store.saveKeyRefs(playlistId, leftover!!) }
        applyKeyRemap(m)
        vodVersion++
        return done
    }

    /** Downloads an Xtream playlist's movies and series into the catalog (one save); null error when both loaded. */
    private suspend fun ingestXtreamVod(p: Playlist, onSeries: () -> Unit = {}): Triple<Int, Int, String?> {
        var movies = 0; var series = 0
        val hadTitles = withContext(Dispatchers.IO) { catalog.has(p.id) }
        // A part that failed would drop its titles: keep the saved ones instead, unless there are none.
        val done = ingestVod(p.id, keep = { errs: List<String>, added -> added > 0 && (errs.isEmpty() || !hadTitles) }) { add ->
            val errs = ArrayList<String>()
            runCatching { movies = XtreamVod.movies(p, add) }.onFailure { errs.add("Movies couldn't load: ${it.message}") }
            onSeries()
            runCatching { series = XtreamVod.series(p, add) }.onFailure { errs.add("Series couldn't load: ${it.message}") }
            errs.toList()
        }
        val err = done.result.joinToString("\n").ifBlank { null }
            ?: if (!done.committed) "Nothing came back from ${p.name}." else null
        return Triple(movies, series, err?.let { if (!done.committed && hadTitles) "$it Showing saved titles." else it })
    }

    /** Moves every saved choice from old keys to new ones. */
    internal fun applyKeyRemap(m: Map<String, String>) {
        if (m.isEmpty()) return
        with(com.avidviewer.tv.data.ChannelKeys) { updatePrefs { it.remapped(m) } }
        if (resume.keys.any { it in m }) { resume = resume.mapKeys { (k, _) -> m[k] ?: k }; store.saveResume(resume) }
        if (history.any { it.key in m }) { history = history.map { h -> m[h.key]?.let { h.copy(key = it) } ?: h }; store.saveHistory(history) }
        currentKey?.let { k -> m[k]?.let { currentKey = it } }
        favDataTick++
    }

    /** For a backup: what each saved choice points at, so a restore can find it in a changed list. */
    internal fun backupRefs(): List<com.avidviewer.tv.data.ChannelKeys.Ref> {
        val wanted = with(com.avidviewer.tv.data.ChannelKeys) { prefs.referencedKeys(resume, history) }
        if (wanted.isEmpty()) return emptyList()
        val out = ArrayList<com.avidviewer.tv.data.ChannelKeys.Ref>()
        for (p in playlists) {
            if (wanted.none { it.startsWith(p.id + ":") }) continue
            val mine = wanted.filter { it.startsWith(p.id + ":") }
            liveReady()
            out.addAll(live.channelRefs(p.id, mine))
            out.addAll(catalog.refs(p.id, mine))
        }
        return out
    }

    /** After a restore: match the backup's references against lists already on this device, keep the rest for the next download. */
    private suspend fun placeRestoredRefs(refs: List<com.avidviewer.tv.data.ChannelKeys.Ref>) {
        val m = HashMap<String, String>()
        withContext(Dispatchers.IO) {
            refs.groupBy { it.key.substringBefore(':') }.forEach { (pid, rs) ->
                liveReady()
                val hasChs = live.channelCount(pid) > 0
                val hasVod = catalog.has(pid)
                val (rv, rc) = rs.partition { it.isVod }
                if (hasChs) m.putAll(live.remapChannels(pid, rc))
                if (hasVod) m.putAll(catalog.remap(pid, rv))
                store.saveKeyRefs(pid, (if (hasChs) emptyList() else rc) + (if (hasVod) emptyList() else rv))
            }
        }
        applyKeyRemap(m)
    }

    // ---------------- guide sources, matching and refresh ----------------
    fun guideMs() = prefs.guideRefreshHours * 3600 * 1000L

    /** The guide channel a channel uses: the one matched by hand, else the provider's (null = no guide). */
    fun epgIdOf(c: Channel): String? {
        val m = prefs.epgMap[c.key]
        return if (m != null) m.ifBlank { null } else c.epgId
    }

    /** Result of the last download of each guide source: "playlistId|sourceId" → message. */
    var guideSourceStatus by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var guideBusy by mutableStateOf<String?>(null); private set

    /** The guide sources a playlist downloads, in order: (status key, label, url, offset hours). */
    private suspend fun guideSourcesOf(p: Playlist): List<GuideSourceRef> {
        val out = ArrayList<GuideSourceRef>()
        if (p.providerGuide) {
            val url = p.providerGuideUrl().ifBlank { withContext(Dispatchers.IO) { runCatching { M3u.headerGuideUrl(p) }.getOrNull() } ?: "" }
            if (url.isNotBlank()) out.add(GuideSourceRef("provider", "Provider's guide", url, 0))
        }
        p.epgSources.filter { it.enabled && it.url.isNotBlank() }.forEach { out.add(GuideSourceRef(it.id, it.name.ifBlank { "Guide source" }, it.url.trim(), it.offsetHours)) }
        return out
    }

    data class GuideSourceRef(val id: String, val name: String, val url: String, val offsetHours: Int)

    /**
     * Downloads and saves a playlist's guide for [chs] from all its sources; null when it has none.
     * Sources are read in order and the first one with programmes for a channel wins. Every source's
     * channel list is saved too, for matching channels by hand.
     */
    private suspend fun downloadGuide(p: Playlist, wanted: Set<String>): Int? =
        guideLocks.getOrPut(p.id) { kotlinx.coroutines.sync.Mutex() }.withLock {
            // One download per playlist at a time, each with the sources as they are when it starts,
            // so a quick second change can't be overwritten by an older download finishing last.
            downloadGuideNow(playlists.firstOrNull { it.id == p.id } ?: p, wanted)
        }
    private suspend fun downloadGuideNow(p: Playlist, wanted: Set<String>): Int? {
        val sources = guideSourcesOf(p)
        if (sources.isEmpty()) return null
        val from = guideFrom()
        val to = guideTo()
        val directory = ArrayList<GuideChannel>()
        val statuses = HashMap(guideSourceStatus)
        val covered = HashSet<String>()
        // Programmes go straight into the database as each source is read; nothing is collected here.
        val done = withContext(Dispatchers.IO) {
            live.replacingGuide(p.id, keep = { ok: Boolean, _ -> ok }) { add ->
                var anyOk = false
                for (src in sources) {
                    val dir = ArrayList<Pair<String, String>>()
                    val seen = HashSet<String>()
                    runCatching {
                        Xmltv.read(src.url, wanted, from, to, src.offsetHours * 3600 * 1000L, dir) { id, pr ->
                            if (id !in covered) { add(id, pr); seen.add(id) }
                        }
                    }.onSuccess {
                        anyOk = true
                        dir.forEach { (id, name) -> directory.add(GuideChannel(id, name, src.name)) }
                        statuses[p.id + "|" + src.id] = "Updated ${fmtTime(System.currentTimeMillis())} · ${dir.size} guide channels · programmes for ${seen.size} of your channels"
                    }.onFailure { e ->
                        statuses[p.id + "|" + src.id] = "Couldn't download: ${e.message ?: e.javaClass.simpleName}"
                    }
                    covered.addAll(seen)
                }
                anyOk
            }
        }
        guideSourceStatus = statuses
        if (!done.committed) throw java.io.IOException("no guide source could be downloaded")
        withContext(Dispatchers.IO) { store.saveGuideChannels(p.id, directory.distinctBy { it.id }) }
        guideChannelCache.remove(p.id)
        return covered.size
    }

    /** The guide ids a playlist needs: its channels' own, plus any matched by hand. */
    private suspend fun guideIdsFor(p: Playlist): Set<String> {
        if (p.id == activePlaylist()?.id) return channels.mapNotNull { epgIdOf(it) }.toHashSet()
        val ids = withContext(Dispatchers.IO) { liveReady(); live.epgIds(p.id) }.toHashSet()
        prefs.epgMap.forEach { (k, v) -> if (k.startsWith(p.id + ":") && v.isNotBlank()) ids.add(v) }
        return ids
    }

    fun setGuideRefreshHours(h: Int) {
        if (h !in listOf(4, 8, 12, 24)) return
        updatePrefs { it.copy(guideRefreshHours = h) }
        status = "The TV guide now downloads again every $h hours."
    }

    /** Downloads a playlist's guide again now (null = every playlist with live TV). */
    fun refreshGuideNow(playlistId: String? = null) {
        viewModelScope.launch {
            val targets = livePlaylists.filter { playlistId == null || it.id == playlistId }
            for (p in targets) {
                guideBusy = p.id
                try {
                    if (p.id == activePlaylist()?.id) refreshGuide(p)
                    else {
                        runCatching { downloadGuide(p, guideIdsFor(p)) }.onFailure { status = "Couldn't load the TV guide for ${p.name}: ${it.message}" }
                        otherLive.remove(p.id)
                    }
                } finally { guideBusy = null }
            }
            favDataTick++
        }
    }

    fun guideSavedAt(playlistId: String): Long = live.guideSavedAt(playlistId)

    private fun updateSources(playlistId: String, f: (Playlist) -> Playlist) {
        val p = playlists.firstOrNull { it.id == playlistId } ?: return
        replacePlaylist(f(p))
    }

    fun setProviderGuide(playlistId: String, on: Boolean) { updateSources(playlistId) { it.copy(providerGuide = on) }; refreshGuideNow(playlistId) }

    fun addEpgSource(playlistId: String, name: String, url: String, offsetHours: Int) {
        val src = EpgSource("src-" + System.currentTimeMillis().toString(36), name.trim().ifBlank { "Guide source" }, url.trim(), true, offsetHours.coerceIn(-12, 12))
        updateSources(playlistId) { it.copy(epgSources = it.epgSources + src) }
        status = "Added ${src.name}. Downloading the guide…"
        refreshGuideNow(playlistId)
    }

    fun updateEpgSource(playlistId: String, id: String, name: String, url: String, offsetHours: Int) {
        updateSources(playlistId) { p -> p.copy(epgSources = p.epgSources.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }, url = url.trim(), offsetHours = offsetHours.coerceIn(-12, 12)) else it }) }
        refreshGuideNow(playlistId)
    }

    fun setEpgSourceEnabled(playlistId: String, id: String, on: Boolean) {
        updateSources(playlistId) { p -> p.copy(epgSources = p.epgSources.map { if (it.id == id) it.copy(enabled = on) else it }) }
        refreshGuideNow(playlistId)
    }

    fun moveEpgSource(playlistId: String, id: String, by: Int) = updateSources(playlistId) { p ->
        val l = p.epgSources.toMutableList(); val i = l.indexOfFirst { it.id == id }
        if (i < 0) return@updateSources p
        val j = (i + by).coerceIn(0, l.lastIndex); val x = l.removeAt(i); l.add(j, x)
        p.copy(epgSources = l)
    }.also { refreshGuideNow(playlistId) }

    fun removeEpgSource(playlistId: String, id: String) {
        updateSources(playlistId) { p -> p.copy(epgSources = p.epgSources.filterNot { it.id == id }) }
        guideSourceStatus = guideSourceStatus - (playlistId + "|" + id)
        refreshGuideNow(playlistId)
    }

    private val guideChannelCache = HashMap<String, List<GuideChannel>>()

    /** Channels listed in a playlist's guide sources (from the last download). */
    suspend fun guideChannels(playlistId: String): List<GuideChannel> =
        guideChannelCache[playlistId] ?: withContext(Dispatchers.IO) { store.loadGuideChannels(playlistId) }.also { guideChannelCache[playlistId] = it }

    /** The channel whose guide match is being chosen (Menu › Match guide…). */
    var matchingChannel by mutableStateOf<String?>(null)

    private var matchRefreshJob: kotlinx.coroutines.Job? = null

    /**
     * Matches a channel to a guide channel by hand: [epgId] = a guide channel id, "" = no guide,
     * null = back to the provider's match. The guide downloads again shortly after (once for several changes).
     */
    fun setEpgMatch(channelKey: String, epgId: String?) {
        updatePrefs { p -> p.copy(epgMap = if (epgId == null) p.epgMap - channelKey else p.epgMap + (channelKey to epgId)) }
        val pid = channelKey.substringBefore(':')
        matchRefreshJob?.cancel()
        matchRefreshJob = viewModelScope.launch {
            delay(2500)
            status = "Updating the guide for your matched channels…"
            refreshGuideNow(pid)
        }
    }

    fun epgMatchCount(): Int = prefs.epgMap.size
    fun clearEpgMatches() { updatePrefs { it.copy(epgMap = emptyMap()) }; refreshGuideNow() }

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
            val dayMs = 24 * 3600 * 1000L; val guideMs = guideMs()
            for (p in shownPlaylists) {
                val nowMs = System.currentTimeMillis()
                val isGuidePlaylist = p.id == activePlaylist()?.id
                if (p.liveOn && !isGuidePlaylist) runCatching {
                    val chAt = withContext(Dispatchers.IO) { liveReady(); live.channelsSavedAt(p.id) }
                    if (force || chAt == 0L || nowMs - chAt > dayMs) {
                        downloadLive(p)
                        otherLive.remove(p.id); playlistGroups = playlistGroups - p.id
                    }
                    if (force || nowMs - withContext(Dispatchers.IO) { live.guideSavedAt(p.id) } > guideMs) {
                        downloadGuide(p, guideIdsFor(p))
                        otherLive.remove(p.id)
                    }
                }
                if (p.vodOn && p.isXtream) runCatching {
                    val at = withContext(Dispatchers.IO) { catalog.savedAt(p.id) }
                    if (force || at == 0L || nowMs - at > dayMs) {
                        val (movies, series, _) = ingestXtreamVod(p)
                        if (movies + series > 0) {
                            otherVod.remove(p.id)
                            if (vodLoadedFor != null && p.id in vodLoadedFor!!.split(',')) { vodLoadedFor = null; if (screen == Screen.MOVIES || screen == Screen.SERIES || screen == Screen.DISCOVER) ensureVod() }
                        }
                    }
                }
            }
            if (prefs.showSuggestions) refreshSuggestions()
        }
    }


    /** What the TV Guide's search box found: channels, then programmes on now or later. */
    data class GuideHits(val query: String = "", val channels: List<Channel> = emptyList(), val programs: List<Pair<Channel, Program>> = emptyList())
    var guideSearchHits by mutableStateOf(GuideHits()); private set
    private var guideSearchJob: Job? = null

    /**
     * Searches the playlist being watched (in memory) and every other playlist with live TV (in the
     * database). Only the matches of other playlists, with their guide, are held, until [endGuideSearch].
     */
    fun runGuideSearch(query: String) {
        guideSearchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) { guideSearchHits = GuideHits(); return }
        guideSearchJob = viewModelScope.launch {
            delay(150) // typing: wait for a pause
            val nowMs = System.currentTimeMillis()
            val needle = q.lowercase()
            val hidden = prefs.hiddenChannels
            val others = HashMap<String, Pair<List<Channel>, Map<String, List<Program>>>>()
            for (p in livePlaylists) {
                if (p.id == activePlaylist()?.id || !p.enabled) continue
                others[p.id] = withContext(Dispatchers.IO) { liveHits(p, q, 60, 80, nowMs) }
            }
            val active = channels
            val activeGuide = guide
            val res = withContext(Dispatchers.Default) {
                val all = (active + others.values.flatMap { it.first }).distinctBy { it.key }.filter { it.key !in hidden }
                fun progs(c: Channel): List<Program> {
                    val id = epgIdOf(c) ?: return emptyList()
                    val pid = c.key.substringBefore(':')
                    return (if (pid == activePlaylist()?.id) activeGuide[id] else others[pid]?.second?.get(id)).orEmpty()
                }
                val chHits = all.filter { channelName(it).lowercase().contains(needle) }.take(60)
                val progHits = all.flatMap { c -> progs(c).filter { it.end > nowMs && (it.title.lowercase().contains(needle) || it.subtitle?.lowercase()?.contains(needle) == true) }.map { c to it } }
                    .sortedBy { it.second.start }.take(80)
                GuideHits(query, chHits, progHits)
            }
            searchLive = others
            searchPool = others.values.flatMap { it.first }
            guideSearchHits = res
            favDataTick++
        }
    }

    /** Search box closed: let go of the other playlists' matches. */
    fun endGuideSearch() {
        guideSearchJob?.cancel()
        searchLive = emptyMap(); searchPool = emptyList(); guideSearchHits = GuideHits()
    }

    /**
     * Another playlist's channels matching [q] by name (own or renamed) plus the channels of programmes
     * matching it, with those channels' guide — looked up in the database, nothing else loaded.
     */
    private fun liveHits(p: Playlist, q: String, maxChannels: Int, maxPrograms: Int, nowMs: Long): Pair<List<Channel>, Map<String, List<Program>>> {
        liveReady()
        val needle = q.lowercase()
        val renamed = prefs.channelNames.filter { (k, v) -> k.startsWith(p.id + ":") && v.lowercase().contains(needle) }.keys
        val byName = live.searchChannels(p.id, q, maxChannels) + live.channelsByKeys(renamed)
        val progs = live.searchPrograms(p.id, q, nowMs, maxPrograms * 2)
        val ids = progs.map { it.first }.toHashSet()
        val mapped = prefs.epgMap.filter { (k, v) -> k.startsWith(p.id + ":") && v in ids }.keys
        val byProg = (live.channelsWithEpg(p.id, ids) + live.channelsByKeys(mapped)).filter { epgIdOf(it) in ids }
        val chs = (byName + byProg).distinctBy { it.key }
        return chs to guideWindow(p.id, chs.mapNotNull { epgIdOf(it) })
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
        if (!startupPreviewDone && list.isNotEmpty()) {
            startupPreviewDone = true
            // At launch: the last channel watched, else the first favorite, else the first channel.
            val shownHere = shownChannel(prefs.lastChannel, list, gs)
            if (shownHere != null) {
                currentKey = shownHere.key; selectedGroup = shownHere.group
                preview(currentKey); guideFocusTick++
            } else {
                selectedGroup = selectedGroup?.takeIf { g -> gs.any { it.raw == g } } ?: gs.firstOrNull()?.raw
                viewModelScope.launch { startOnListedChannel() }
            }
            return
        }
        if (currentKey == null) currentKey = prefs.lastChannel?.takeIf { k -> list.any { it.key == k } } ?: list.firstOrNull()?.key
        // Open on the last channel's category in the guide.
        val lastGroup = channel(currentKey)?.group
        if (selectedGroup == null || gs.none { it.raw == selectedGroup }) {
            selectedGroup = lastGroup?.takeIf { g -> gs.any { it.raw == g } } ?: gs.firstOrNull()?.raw
        }
    }

    /** [key] if it's a channel of [list] the guide shows (not hidden, in a shown category). */
    private fun shownChannel(key: String?, list: List<Channel>, gs: List<GroupInfo>): Channel? {
        if (key == null || key in prefs.hiddenChannels) return null
        val c = list.firstOrNull { it.key == key } ?: return null
        return c.takeIf { gs.any { g -> g.raw == c.group } }
    }

    /**
     * Launch, when the last channel isn't in a shown category of this playlist: the last channel from
     * ★ Favorites or a list (it may be another playlist's), else the first favorite that can play,
     * else the first channel of the first category.
     */
    private suspend fun startOnListedChannel() {
        val last = prefs.lastChannel
        // Other playlists' favorites and list channels, so they can be found.
        val pids = (listOf(last) + prefs.favorites).filterNotNull().map { it.substringBefore(':') }.distinct().filter { it != activePlaylist()?.id }
        for (pid in pids) livePlaylists.firstOrNull { it.id == pid }?.let { runCatching { liveFavorites(it) } }
        favDataTick++
        fun ok(k: String?) = k != null && k !in prefs.hiddenChannels && channel(k) != null
        val (key, group) = when {
            ok(last) && last in prefs.favorites -> last to FAV_GROUP
            ok(last) && prefs.lists.any { !it.hidden && last in it.keys } -> last to (LIST_PREFIX + prefs.lists.first { !it.hidden && last in it.keys }.id)
            else -> prefs.favorites.firstOrNull { ok(it) }?.let { it to FAV_GROUP }
                ?: groups().firstNotNullOfOrNull { g -> channelsIn(g.raw).firstOrNull()?.let { it.key to g.raw } } ?: return
        }
        if (currentKey != null && lastKeyAt > 0) return // the viewer has already picked something
        currentKey = key; selectedGroup = group
        guideFocusMode = GuideFocus.CURRENT
        preview(key); guideFocusTick++
    }

    // ---------------- prefs ----------------
    fun updatePrefs(f: (Prefs) -> Prefs) {
        val before = prefs
        prefs = f(prefs)
        store.savePrefs(prefs)
        if (before.vodHiddenCats !== prefs.vodHiddenCats || before.vodHiddenTitles !== prefs.vodHiddenTitles) {
            // Right away, so the lists on screen (and the next one read) are already correct.
            runCatching { catalog.applyHiddenChange(before.vodHiddenCats, prefs.vodHiddenCats, before.vodHiddenTitles, prefs.vodHiddenTitles) }
            vodVersion++
        }
        if (before.favorites !== prefs.favorites || before.lists !== prefs.lists) {
            // A channel from another playlist was added to a list: reload that playlist's small cache.
            val added = listedKeys(prefs) - listedKeys(before)
            val stale = added.map { it.substringBefore(':') }.filter { it in otherLive }.toSet()
            if (stale.isNotEmpty()) { stale.forEach { otherLive.remove(it) }; ensureFavoriteData() }
        }
    }

    // ---------------- groups & channels ----------------
    fun groupName(raw: String) = when {
        raw == FAV_GROUP -> "★ Favorites"
        raw.startsWith(LIST_PREFIX) -> "☰ " + (userList(raw)?.name ?: "List")
        else -> prefs.groupNames[raw] ?: raw
    }
    fun channelName(c: Channel) = prefs.channelNames[c.key] ?: c.name

    fun groups(includeHidden: Boolean = false): List<GroupInfo> =
        (if (includeHidden) groupsAllMemo else groupsMemo).get(channels, prefs, activePlaylist()?.id) { computeGroups(includeHidden) }

    private fun computeGroups(includeHidden: Boolean): List<GroupInfo> {
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
        viewModelScope.launch { rematchSports() }
    }

    /** All channels of a playlist, hidden ones included, for the List Editor. */
    suspend fun editorChannels(pid: String): List<Channel> {
        val p = playlists.firstOrNull { it.id == pid } ?: return emptyList()
        if (!p.liveOn) return emptyList()
        if (p.id == activePlaylist()?.id) return channels
        return withContext(Dispatchers.IO) { liveReady(); live.channels(p.id) }
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
        val cache = channelsInMemo.get(channels, prefs, favDataTick, searchPool) { HashMap() }
        synchronized(cache) { cache[group]?.let { return it } }
        val list = computeChannelsIn(group)
        synchronized(cache) { cache[group] = list }
        return list
    }

    private fun computeChannelsIn(group: String): List<Channel> {
        if (isListGroup(group)) return listKeys(group).mapNotNull { channel(it) }
        val list = channels.filter { it.group == group && it.key !in prefs.hiddenChannels }
        val order = prefs.channelOrder[group] ?: return list
        val byKey = list.associateBy { it.key }
        return order.mapNotNull { byKey[it] } + list.filterNot { it.key in order }
    }

    /** Looks in the active playlist first, then in other playlists' saved channels (for combined Favorites). */
    fun channel(key: String?): Channel? {
        if (key == null) return null
        channelIndexMemo.get(channels) { channels.associateBy { it.key } }[key]?.let { return it }
        val pid = key.substringBefore(':')
        if (livePlaylists.none { it.id == pid }) return null
        return otherLive[pid]?.first?.firstOrNull { it.key == key } ?: searchLive[pid]?.first?.firstOrNull { it.key == key } ?: sportsChannels[key]
    }

    fun playlistName(c: Channel?): String = c?.let { ch -> playlists.firstOrNull { ch.key.startsWith(it.id + ":") }?.name } ?: ""


    /** Loads channels and guides of other playlists that hold favorites. */
    fun ensureFavoriteData() {
        val active = activePlaylist()?.id
        val pids = (prefs.favorites + prefs.lists.flatMap { it.keys }).map { it.substringBefore(':') }.distinct().filter { it != active && !otherLive.containsKey(it) }
        if (pids.isEmpty()) return
        viewModelScope.launch {
            pids.forEach { pid -> livePlaylists.firstOrNull { it.id == pid }?.let { liveFavorites(it) } }
            favDataTick++
        }
    }

    /** Channel numbers follow the user's group and channel order, skipping hidden groups. */
    fun numbers(): Map<String, Int> = numbersMemo.get(channels, prefs, activePlaylist()?.id) { computeNumbers() }

    private fun computeNumbers(): Map<String, Int> {
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
                val chs = liveFavorites(p).first.associateBy { it.key }
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
        val id = c?.let { epgIdOf(it) } ?: return emptyList()
        val pid = c.key.substringBefore(':')
        return if (pid == activePlaylist()?.id) guide[id] ?: emptyList() else otherLive[pid]?.second?.get(id) ?: searchLive[pid]?.second?.get(id) ?: emptyList()
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
        if (ctx == PlayContext.SPORTS) sportsPlayLeague = sportLeague
        if (ctx == PlayContext.GROUP && !isListGroup(selectedGroup)) selectedGroup = c.group
        if (screen != Screen.PLAYER) lastScreen = screen
        if (currentKey != key || player.mediaItemCount == 0 || playerError != null) start(c)
        screen = Screen.PLAYER
        flashOsd(contextLabel())
    }

    fun closeFullScreen() {
        osd = null
        avPanel = false
        if (vodPlaying != null) {
            saveResumePoint()
            player.stop()
            player.clearMediaItems()
            vodPlaying = null
            screen = lastScreen
            if (jumpedFrom != null) { jumpedFrom = null; navFocusKey = lastHit }
            return
        }
        // Opened from Search: Back goes back to the results, on the result that was chosen (NAV-12).
        val from = jumpedFrom
        if (from != null) {
            jumpedFrom = null
            screen = from; navMode = NavMode.COLLAPSED
            navFocusKey = lastHit
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
        val seeds = (prefs.myList + catalog.byKeys(resume.keys.filter { resumeOf(it) > 0 })).distinctBy { it.key }.take(6)
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
    /** The version of a Discover title to play by default: the first of [versionsOf] (English first). */
    fun matchVod(t: Title): VodItem? = versionsOf(t).firstOrNull()

    /**
     * Every version of a Discover title in your playlists (each language, quality and playlist), English
     * first, then unmarked, multi-language and other languages; titles you've hidden are left out.
     * One indexed lookup per title, remembered until the catalog changes.
     */
    fun versionsOf(t: Title): List<VodItem> {
        if (matchCacheFor != vodVersion) { versionsCache.clear(); matchCache.clear(); matchCacheFor = vodVersion }
        val k = t.type + "|" + Tmdb.normalize(t.name)
        versionsCache[k]?.let { return it }
        val pids = vodPlaylists.map { it.id }
        val found = runCatching { catalog.findAllByName(if (t.type == "tv") "series" else "movie", Tmdb.normalize(t.name), pids) }.getOrDefault(emptyList())
            .sortedBy { com.avidviewer.tv.data.Language.rank(com.avidviewer.tv.data.Language.of(it.name, it.category)) } // stable: list order within a language
        versionsCache[k] = found
        return found
    }

    fun playMatched(v: VodItem) { if (v.kind == "series") openVodDetail(v) else playVod(v) }

    // ---------------- app updates ----------------
    fun checkForUpdate(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) updateStatus = "Checking for updates…"
            val r = runCatching { withContext(Dispatchers.IO) { Updater.latest() } }
            r.onSuccess { rel ->
                update = rel?.takeIf { it.versionCode > installed.first }
                onUpdateFound()
                updateStatus = when {
                    rel == null -> if (quiet) null else "Couldn't read the latest release."
                    update != null -> "Version ${rel.versionName} is available."
                    else -> if (quiet) null else "You have the latest version."
                }
            }.onFailure { if (!quiet) updateStatus = "Couldn't check for updates: ${it.message}" }
        }
    }

    /** Decides whether a found update may open its dialog now (never in the middle of browsing). */
    internal fun onUpdateFound() {
        updatePromptAllowed = update != null && lastKeyAt == 0L
    }

    /** Second way to install: Android's own install screen for the downloaded file. */
    fun openInstaller() {
        val f = updateFile?.takeIf { it.exists() } ?: run { updateStatus = "Download the update again first."; updateFile = null; return }
        val ok = Updater.openInstaller(getApplication(), f)
        updateStatus = if (ok) "Follow the steps on the install screen." else "Couldn't open the install screen on this device."
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
            updateFile = null
            try {
                var downloading = true
                val file = withContext(Dispatchers.IO) {
                    Updater.download(ctx, rel.apkUrl, rel.size) { pct, note ->
                        viewModelScope.launch { if (downloading) updateStatus = "Downloading ${rel.versionName}… $pct%" + (note?.let { " · $it" } ?: "") }
                    }
                }
                downloading = false
                updateFile = file
                updateStatus = "Installing ${rel.versionName}…"
                installAnswered = false
                withContext(Dispatchers.IO) { Updater.install(ctx, file) }
                // Some Fire TVs never answer the installer request: after a while, offer the system install screen.
                delay(25_000)
                if (!installAnswered) {
                    updateBusy = false
                    updateStatus = "The installer hasn't responded. Choose Open installer to finish the update."
                }
            } catch (e: Exception) {
                updateStatus = "Update failed: ${e.message}"
                updateBusy = false
                if (updateFile != null) updateStatus += " You can also choose Open installer."
            }
        }
    }

    // ---------------- sports ----------------
    /** A game from ESPN and the channels that carry it, best first. */
    data class SportGame(val game: Game, val channels: List<ChannelMatch>)

    /** The leagues switched on, in the user's order. */
    fun sportLeagues(): List<com.avidviewer.tv.data.LeagueSetting> = com.avidviewer.tv.data.LeagueSetting.complete(prefs.sportLeagues)
    fun leaguesOn(): List<com.avidviewer.tv.data.League> = sportLeagues().filter { it.on }.mapNotNull { com.avidviewer.tv.data.League.of(it.id) }

    fun updateSportLeagues(list: List<com.avidviewer.tv.data.LeagueSetting>) {
        updatePrefs { it.copy(sportLeagues = list) }
        if (sportLeague != null && list.none { it.id == sportLeague && it.on }) sportLeague = null
        viewModelScope.launch { refreshSports() }
    }

    fun updateSportsOptions(showUnmatched: Boolean = prefs.sportsShowUnmatched, finalHours: Int = prefs.sportsFinalHours, showScores: Boolean = prefs.sportsShowScores) {
        updatePrefs { it.copy(sportsShowUnmatched = showUnmatched, sportsFinalHours = finalHours, sportsShowScores = showScores) }
        sportGames = sportGames.toList() // re-filter on screen
    }

    fun openSportsScreen() {
        screen = Screen.SPORTS
        // Recent enough (a minute) is fine; otherwise get the schedules again.
        if (System.currentTimeMillis() - sportsFetchedAt > 60_000L || sportGames.isEmpty()) viewModelScope.launch { refreshSports() }
    }

    private var sportsJob: Job? = null

    /** Each league's games per day (key "league|YYYYMMDD"), with when ESPN last answered for it. */
    private val sportsDays = HashMap<String, Pair<Long, List<Game>>>()

    /**
     * Gets yesterday's (late games, finals), today's and the next 2 days' games from ESPN, one day at a
     * time (ESPN refuses ranges), for each league switched on, then finds their channels. Today is asked
     * again every minute when needed, the other days every 30 minutes. A day ESPN can't answer keeps
     * its last answer (saved on the device).
     */
    suspend fun refreshSports() {
        if (sportsJob?.isActive == true) { sportsJob?.join(); return }
        val job = viewModelScope.launch {
            sportsLoading = true
            try {
                val zone = timeZone
                val t = System.currentTimeMillis()
                val days = com.avidviewer.tv.data.EspnFeed.days(t, zone)
                val today = days[1]
                val failed = LinkedHashSet<String>()
                val leagues = leaguesOn()
                val games = withContext(Dispatchers.IO) {
                    store.pruneSportsCache(days.toSet())
                    leagues.flatMap { l ->
                        days.flatMap { d ->
                            val k = l.id + "|" + d
                            val have = sportsDays[k]
                            val maxAge = if (d == today || d == days[0]) 55_000L else 30 * 60_000L
                            if (have != null && t - have.first < maxAge) return@flatMap have.second
                            val fresh = runCatching { com.avidviewer.tv.data.EspnFeed.fetchDay(l, d) }
                                .onSuccess { store.saveSportsCache(l.id, d, it) }
                                .getOrNull()
                                ?.let { runCatching { com.avidviewer.tv.data.EspnFeed.parse(l.id, it) }.getOrNull() }
                            if (fresh != null) { sportsDays[k] = t to fresh; fresh }
                            else {
                                failed.add(l.name)
                                have?.second ?: store.loadSportsCache(l.id, d)?.let { runCatching { com.avidviewer.tv.data.EspnFeed.parse(l.id, it) }.getOrNull() }.orEmpty()
                            }
                        }
                    }
                }
                sportsDays.keys.removeAll { k -> k.substringAfter('|') !in days || leagues.none { it.id == k.substringBefore('|') } }
                sportsNote = if (failed.isEmpty()) null else "Couldn't reach ESPN for ${failed.joinToString()}; showing the last list."
                sportsFetchedAt = System.currentTimeMillis()
                matchSports(games)
            } finally { sportsLoading = false }
        }
        sportsJob = job
        job.join()
    }

    /** The guide or channels changed: find channels again for the games already listed. */
    private suspend fun rematchSports() {
        if (sportGames.isNotEmpty()) matchSports(sportGames.map { it.game })
    }

    private suspend fun matchSports(games: List<Game>) {
        val zone = timeZone
        val t = System.currentTimeMillis()
        val endDay = java.util.Calendar.getInstance(zone).apply {
            timeInMillis = t; add(java.util.Calendar.DAY_OF_YEAR, 2)
            set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59); set(java.util.Calendar.SECOND, 59)
        }.timeInMillis
        val keepFinal = prefs.sportsFinalHours * 3600_000L
        // Today and the next 2 days, games on now, and finals of the last hours.
        val kept = games.distinctBy { it.id }.filter { g ->
            when { g.live -> true; g.final -> t - g.likelyEnd() < keepFinal; else -> g.start <= endDay && g.likelyEnd() > t - 3600_000L }
        }
        if (kept.isEmpty()) { sportGames = emptyList(); return }
        val hiddenKeys = prefs.hiddenChannels
        val epgMap = prefs.epgMap
        val pls = livePlaylists.filter { it.enabled }
        val (matched, chans) = withContext(Dispatchers.IO) {
            liveReady()
            val m = com.avidviewer.tv.data.SportsMatcher(kept) { it in hiddenKeys }
            val from = kept.minOf { it.start } - 2 * 3600_000L
            val to = kept.maxOf { it.likelyEnd() } + 3600_000L
            for (p in pls) {
                live.forEachChannel(p.id) { key, name, group, epg ->
                    if (!isGroupHidden(p.id, group)) m.addChannel(p.id, key, name, epg, epgMap[key], group)
                }
                live.forEachProgram(p.id, from, to) { e, s0, e0, title, sub -> m.addProgram(p.id, e, s0, e0, title, sub) }
                m.nextPlaylist()
            }
            val r = m.result()
            val keys = r.values.flatten().map { it.key }.distinct()
            r to live.channelsByKeys(keys).associateBy { it.key }
        }
        sportsChannels = chans
        sportGames = kept.sortedBy { it.start }.map { SportGame(it, matched[it.id].orEmpty()) }
    }

    /** The games the Sports screen lists (league filter, and the "no channel found" setting). */
    fun sportsVisibleGames(): List<SportGame> = sportGames.filter { g ->
        (sportLeague == null || g.game.league == sportLeague) && (prefs.sportsShowUnmatched || g.channels.isNotEmpty())
    }

    /** Sports' shortcut: the TV Guide's search, for a team. */
    fun searchGuideFor(text: String) {
        jumpedFrom = null
        screen = Screen.GUIDE
        navMode = NavMode.GROUPS
        guideQuery = text
    }

    /** A matched channel of any playlist (another playlist's matches are kept just for Sports, see [channel]). */
    fun sportsChannel(key: String?): Channel? = channel(key)

    // ---------------- search (all playlists) ----------------
    /** Live channels + guide of a playlist; nothing at all when its live TV is off. Never touches movies & series. */
    /** A playlist's whole channel list and guide, read from its saved files and not kept (the active one is in memory). */
    private suspend fun liveFor(p: Playlist): Pair<List<Channel>, Map<String, List<Program>>> {
        if (!p.liveOn || !p.enabled) return emptyList<Channel>() to emptyMap()
        if (p.id == activePlaylist()?.id) return channels to guide
        return withContext(Dispatchers.IO) { liveReady(); live.channels(p.id) to guideWindow(p.id, null) }
    }

    /** For tests: how many channels of each other playlist are held in memory. */
    internal fun otherLiveSizes(): Map<String, Int> = otherLive.mapValues { it.value.first.size }

    /** Keys in ★ Favorites or any list. */
    private fun listedKeys(pr: Prefs = prefs): Set<String> = (pr.favorites + pr.lists.flatMap { it.keys }).toHashSet()

    /** Another playlist's favorite/list channels and their guide, kept in memory (small). */
    private suspend fun liveFavorites(p: Playlist): Pair<List<Channel>, Map<String, List<Program>>> {
        if (!p.liveOn || !p.enabled) return emptyList<Channel>() to emptyMap()
        if (p.id == activePlaylist()?.id) return channels to guide
        otherLive[p.id]?.let { return it }
        val keys = listedKeys()
        val t = withContext(Dispatchers.IO) {
            liveReady()
            val chs = live.channelsByKeys(keys.filter { it.startsWith(p.id + ":") })
            chs to guideWindow(p.id, chs.mapNotNull { epgIdOf(it) })
        }
        otherLive[p.id] = t
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
            for (p in shownPlaylists) {
                if (!p.liveOn && !p.vodOn) continue
                val isActive = p.id == activePlaylist()?.id
                // The playlist being watched is in memory; the others are searched in the database.
                val (chs, g) = if (isActive || !p.liveOn || !p.enabled) liveFor(p) else withContext(Dispatchers.IO) { liveHits(p, q, 50, 60, nowMs) }
                // Titles: searched in the catalog database, not loaded.
                if (p.vodOn && p.enabled) importOldTitles(p)
                val vodHitList = if (!p.vodOn || !p.enabled) emptyList() else withContext(Dispatchers.IO) {
                    catalog.page(Catalog.Query("movie", listOf(p.id), nameContains = q, kind = "movie"), 0, 50) +
                        catalog.page(Catalog.Query("series", listOf(p.id), nameContains = q), 0, 50) +
                        catalog.page(Catalog.Query("movie", listOf(p.id), nameContains = q, kind = "episode"), 0, 50)
                }
                val res = withContext(Dispatchers.Default) {
                    val names = chs.associate { it.key to channelName(it) }
                    val stations = chs.filter { (names[it.key] ?: it.name).lowercase().contains(needle) }.take(50)
                        .map { Hit("channel", p.id, names[it.key] ?: it.name, groupName(it.group), it.logo, channelKey = it.key) }
                    val progs = ArrayList<Pair<Long, Hit>>()
                    for (c in chs) {
                        val list = epgIdOf(c)?.let { g[it] } ?: continue
                        for (pr in list) {
                            if (pr.end <= nowMs) continue
                            if (pr.title.lowercase().contains(needle) || pr.subtitle?.lowercase()?.contains(needle) == true) {
                                val whenText = if (pr.start <= nowMs) "On now" else "${dayLabel(pr.start)} ${fmtTime(pr.start)}"
                                progs.add(pr.start to Hit("program", p.id, pr.title + (pr.subtitle?.let { " · $it" } ?: ""), "${names[c.key] ?: c.name} · $whenText", c.logo, channelKey = c.key))
                            }
                        }
                    }
                    val vodHits = vodHitList
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
        rematchSports()
    }

    // ---------------- left navigation ----------------
    /** Set when a screen is picked in the left menu; that screen's first item takes the highlight once. */
    var entryFocusFor by mutableStateOf<Screen?>(null)
    /** When the last remote button was pressed (so a late automatic highlight never overrides the viewer). */
    var lastKeyAt = 0L

    fun navSelect(target: Screen) {
        jumpedFrom = null
        entryFocusFor = if (target == Screen.PLAYLISTS || target == Screen.MANAGE) Screen.SETTINGS else target
        val s = if (target == Screen.LIVE) Screen.GUIDE else target
        if (s == Screen.FAVORITES) {
            ensureFavoriteData()
            screen = Screen.GUIDE; selectedGroup = FAV_GROUP; navMode = NavMode.GROUPS; guideFocusTick++
            return
        }
        if (s == Screen.DISCOVER) { ensureVod(); openDiscover(); navMode = NavMode.COLLAPSED; return }
        if (s == Screen.MOVIES || s == Screen.SERIES) { closeVodDetail(returnFocus = false); ensureVod() }
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
            val counts = if (!p.liveOn || !p.enabled) emptyMap() else withContext(Dispatchers.IO) { liveReady(); live.groupCounts(p.id) }.toMap(LinkedHashMap())
            val list = counts.filterKeys { !isGroupHidden(p.id, it) }.map { (raw, n) -> GroupInfo(raw, groupName(raw), n, false) }
            playlistGroups = playlistGroups + (p.id to list)
        }
    }

    /** Picking a category: switch playlist if needed, hide the menu and show categories as the left nav. */
    fun chooseGroup(pid: String, raw: String) {
        viewModelScope.launch {
            val same = raw == selectedGroup && pid == activePlaylist()?.id
            switchPlaylist(pid)
            // A category seen before comes back on the channel it was on; a new one starts at its first channel.
            guideFocusMode = if (same || nav.has(guidePath(raw))) GuideFocus.KEEP else GuideFocus.TOP
            selectedGroup = raw
            navMode = NavMode.GROUPS
            guideFocusTick++
        }
    }

    /** Back walks up the navigation: categories → playlists → full menu → TV Guide. Returns false to let the app exit. */
    /** Which left-hand list has the highlight, so Back and Left know which layer the viewer is on. */
    var inGroupNav = false
    var inPlaylistTree = false
    /** Whether the highlight is inside a Settings section (not on the section list). */
    var settingsInContent = false
    /** Bumped when Back inside a Settings section should go to that section's row in the list. */
    var settingsBackTick by mutableStateOf(0); private set
    /** When Back was pressed at the top of the app; a second press within 2.5 s exits (NAV-11). */
    private var exitArmedAt = 0L
    /** A screen a jump started from (Search): Back from what it opened returns there (NAV-12). */
    var jumpedFrom: Screen? = null

    /** A category (raw name, ★ Favorites or a list) the left-hand list should highlight next; cleared once it has. */
    var navFocusKey by mutableStateOf<String?>(null)

    /**
     * Back, one layer at a time: guide channels → the current category in the category list →
     * the same category in the playlist list → the main menu.
     */
    fun navBack(): Boolean = if (movingGroup != null || movingChannel != null || vodMovingCat != null) { movingGroup = null; movingChannel = null; vodMovingCat = null; true }
    else if (screen == Screen.SETTINGS && settingsInContent) { settingsBackTick++; true }
    else when (navMode) {
        NavMode.GROUPS -> {
            if (!inGroupNav && (screen == Screen.GUIDE || screen == Screen.LIVE) && selectedGroup != null) {
                navFocusKey = selectedGroup
            } else {
                inGroupNav = false
                navMode = NavMode.COLLAPSED
                expandedPlaylist = activePlaylist()?.id
                loadPlaylistGroups(expandedPlaylist)
                navFocusKey = selectedGroup
            }
            true
        }
        NavMode.COLLAPSED -> { inPlaylistTree = false; navFocusKey = null; navMode = NavMode.FULL; true }
        NavMode.FULL -> if (screen != Screen.GUIDE && channels.isNotEmpty()) {
            screen = Screen.GUIDE; navMode = NavMode.GROUPS; guideFocusTick++; true
        } else exitPressed()
    }

    /** Identifies a search result, so Back can put the highlight back on it. */
    fun hitId(h: Hit) = "hit|${h.playlistId}|${h.kind}|${h.channelKey ?: h.vod?.key}|${h.title}"
    private var lastHit: String? = null

    fun openHit(h: Hit) {
        if (screen == Screen.SEARCH && h.kind in setOf("channel", "program", "movie", "episode")) { jumpedFrom = Screen.SEARCH; lastHit = hitId(h) }
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
        tracksChanged(androidx.media3.common.Tracks.EMPTY)
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
    /** Playlists whose titles the Movies and Series screens show, in order. */
    private fun vodPids(): List<String> = vodPlaylists.map { it.id }

    private fun titles(q: com.avidviewer.tv.data.Catalog.Query): List<VodItem> = com.avidviewer.tv.data.PagedTitles(catalog, q)

    /** Every movie (or series) of the switched-on playlists, hidden ones included; read a page at a time. */
    fun vodAll(kind: String): List<VodItem> = vodAllMemo.getOrPut(kind) { Memo() }.get(vodVersion, vodPids().joinToString(",")) {
        titles(com.avidviewer.tv.data.Catalog.Query(vodKindKey(kind), vodPids(), includeHidden = true))
    }

    /** Titles whose name contains [q] (the Movies / Series search). */
    fun vodSearch(kind: String, q: String): List<VodItem> =
        titles(com.avidviewer.tv.data.Catalog.Query(vodKindKey(kind), vodPids(), nameContains = q.trim()))

    /** List Editor: one playlist's categories of this kind with counts, hidden ones included. */
    fun vodEditorCategories(kind: String, pid: String): List<Pair<String, Int>> {
        @Suppress("UNUSED_VARIABLE") val v = vodVersion
        val rows = catalog.categories(vodKindKey(kind), listOf(pid), includeHidden = true)
        return orderVodCats(kind, rows.map { it.second }).map { c -> c to (rows.firstOrNull { it.second == c }?.third ?: 0) }
    }

    /** List Editor: the titles of one playlist's category, hidden ones included. */
    fun vodEditorTitles(kind: String, pid: String, cat: String, q: String = ""): List<VodItem> {
        @Suppress("UNUSED_VARIABLE") val v = vodVersion
        return titles(com.avidviewer.tv.data.Catalog.Query(vodKindKey(kind), listOf(pid), includeHidden = true, category = cat, nameContains = q.trim().ifBlank { null }))
    }

    /** "movie" or "series": the editor's name for a VOD kind. */
    fun vodKindKey(kind: String) = if (kind == "series") "series" else "movie"

    fun vodCatKey(kind: String, pid: String, cat: String) = vodKindKey(kind) + "|" + pid + "|" + cat

    fun isVodCatHidden(kind: String, pid: String, cat: String) = vodCatKey(kind, pid, cat) in prefs.vodHiddenCats

    fun isVodHidden(v: VodItem) = v.key in prefs.vodHiddenTitles || isVodCatHidden(v.kind, v.playlistId, v.category.ifBlank { "Other" })

    /** What the Movies / Series screens show: hidden titles and categories left out. */
    fun vodOf(kind: String): List<VodItem> = vodOfMemo.getOrPut(kind) { Memo() }.get(vodVersion, vodPids().joinToString(",")) {
        titles(com.avidviewer.tv.data.Catalog.Query(vodKindKey(kind), vodPids()))
    }

    fun vodCatName(kind: String, cat: String) = prefs.vodCatNames[vodKindKey(kind) + "|" + cat] ?: cat

    /** Orders category names by the user's order (new ones keep the provider's order, after). */
    fun orderVodCats(kind: String, names: List<String>): List<String> {
        val ord = prefs.vodCatOrder[vodKindKey(kind)] ?: return names
        return ord.filter { it in names } + names.filterNot { it in ord }
    }

    fun vodCategories(kind: String): List<Pair<String, Int>> = vodCatsMemo.getOrPut(kind) { Memo() }.get(vodVersion, vodPids().joinToString(","), prefs) { computeVodCategories(kind) }

    private fun computeVodCategories(kind: String): List<Pair<String, Int>> {
        // Same category name in several playlists: one entry, counts added, first appearance first.
        val counts = LinkedHashMap<String, Int>()
        catalog.categories(vodKindKey(kind), vodPids(), includeHidden = false).forEach { (_, c, n) -> counts[c] = (counts[c] ?: 0) + n }
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
        val keys = catalog.playlistsWithCategory(vodKindKey(kind), cat).map { vodCatKey(kind, it, cat) }.toSet()
        updatePrefs { it.copy(vodHiddenCats = it.vodHiddenCats + keys) }
        if (vodCategory[kind] == cat) vodCategory = vodCategory + (kind to "All")
    }

    /** List Editor: show or hide many movie/series categories (keys from [vodCatKey]). */
    fun setVodCatsHidden(keys: Collection<String>, hidden: Boolean) =
        updatePrefs { it.copy(vodHiddenCats = if (hidden) it.vodHiddenCats + keys else it.vodHiddenCats - keys.toSet()) }

    /** List Editor: show or hide many movies/series. */
    fun setVodTitlesHidden(keys: Collection<String>, hidden: Boolean) =
        updatePrefs { it.copy(vodHiddenTitles = if (hidden) it.vodHiddenTitles + keys else it.vodHiddenTitles - keys.toSet()) }

    fun vodItems(kind: String): List<VodItem> =
        vodItemsMemo.getOrPut(kind) { Memo() }.get(vodVersion, vodPids().joinToString(","), prefs, vodCategory[kind] ?: "All", resume) { computeVodItems(kind) }

    private fun computeVodItems(kind: String): List<VodItem> {
        val g = vodKindKey(kind)
        return when (val c = vodCategory[kind] ?: "All") {
            "All" -> vodOf(kind)
            "Recently added" -> titles(com.avidviewer.tv.data.Catalog.Query(g, vodPids(), newestFirst = true, limit = 200))
            "My List" -> prefs.myList.filter { if (kind == "series") it.kind == "series" else it.kind != "series" }
            "Continue watching" -> catalog.byKeys(resume.keys).filter { Catalog.group(it.kind) == g && !isVodHidden(it) }
            else -> titles(com.avidviewer.tv.data.Catalog.Query(g, vodPids(), category = c))
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

    /** Closes a title's page; [returnFocus] puts the highlight back on its poster (not when leaving via the menu). */
    fun closeVodDetail(returnFocus: Boolean = true) {
        val had = vodDetail != null
        vodDetail = null; vodInfo = null; detailEpisodes = null
        if (had && returnFocus) vodFocusBack++
    }

    fun playDetailEpisode(e: Episode, fromStart: Boolean = false) {
        val s = vodDetail ?: return
        lastEpisode = e
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
        tracksChanged(androidx.media3.common.Tracks.EMPTY) // this channel's own choices (captions on/off) from the start
        player.prepare()
        player.playWhenReady = true
        watchKey = c.key; watchStart = System.currentTimeMillis()
        updatePrefs { it.copy(lastChannel = c.key) }
    }

    // ---------------- picture & sound (Menu in full screen) ----------------
    /** The full-screen Picture & sound panel is open. */
    var avPanel by mutableStateOf(false)
    /** Audio and caption tracks of what's playing (from the player). */
    var audioTracks by mutableStateOf<List<com.avidviewer.tv.data.AvTrack>>(emptyList()); private set
    var captionTracks by mutableStateOf<List<com.avidviewer.tv.data.AvTrack>>(emptyList()); private set
    private var lastTracks: androidx.media3.common.Tracks = androidx.media3.common.Tracks.EMPTY

    /** What the panel's choices are saved under: the movie playing, the series of an episode, else the channel. */
    fun avKey(): String? = vodPlaying?.let { v -> if (v.kind == "episode") v.key.substringBeforeLast(':').substringBeforeLast(':') else v.key } ?: currentKey

    /** The choices in force for [key]: its own, then the default for all. */
    fun avFor(key: String?): com.avidviewer.tv.data.AvChoice =
        (key?.let { prefs.avChannels[it] } ?: com.avidviewer.tv.data.AvChoice()).over(prefs.avDefault)

    fun hasOwnAv(key: String?): Boolean = key != null && prefs.avChannels[key]?.isEmpty == false

    fun screenFormat(): com.avidviewer.tv.data.ScreenFormat =
        com.avidviewer.tv.data.ScreenFormat.of(avFor(avKey()).format) ?: com.avidviewer.tv.data.ScreenFormat.AUTO

    /** Saves a change for the channel playing, or for all channels (the panel's "Save for" choice). */
    private fun changeAv(f: (com.avidviewer.tv.data.AvChoice) -> com.avidviewer.tv.data.AvChoice) {
        val key = avKey()
        updatePrefs { p ->
            if (p.avForAll || key == null) p.copy(avDefault = f(p.avDefault))
            else {
                val mine = f(p.avChannels[key] ?: com.avidviewer.tv.data.AvChoice())
                p.copy(avChannels = if (mine.isEmpty) p.avChannels - key else p.avChannels + (key to mine))
            }
        }
        applyTracks()
    }

    fun setScreenFormat(f: com.avidviewer.tv.data.ScreenFormat) {
        changeAv { it.copy(format = f.id) }
        flashOsd("Screen format: ${f.label}")
    }

    /** [t] null = the stream's own choice. */
    fun setAudioTrack(t: com.avidviewer.tv.data.AvTrack?) {
        changeAv { it.copy(audio = t?.let { com.avidviewer.tv.data.Av.signature(it) }) }
        flashOsd("Audio: " + (t?.let { com.avidviewer.tv.data.Av.describe(it, audioTracks) } ?: "Automatic"))
    }

    /** Stands for "captions on, whichever track there is" (before any is found). */
    val anyCaptions = com.avidviewer.tv.data.AvTrack(audio = false, group = -1, index = -1, lang = null, label = null, channels = 0, mime = null, roles = 0, selected = false)

    /** [t] null = captions off; [anyCaptions] = on, the first track found. */
    fun setCaptions(t: com.avidviewer.tv.data.AvTrack?) {
        val sig = t?.takeIf { it !== anyCaptions }?.let { com.avidviewer.tv.data.Av.signature(it) }
        changeAv { it.copy(captions = t != null, captionTrack = sig) }
        flashOsd("Closed captions: " + when { t == null -> "Off"; t === anyCaptions -> "On"; else -> com.avidviewer.tv.data.Av.describe(t, captionTracks) })
    }

    fun setCaptionStyle(st: com.avidviewer.tv.data.CaptionStyle) = updatePrefs { it.copy(captionStyle = st) }

    fun setAvForAll(all: Boolean) = updatePrefs { it.copy(avForAll = all) }

    /** The channel playing goes back to the choices for all channels. */
    fun resetAvForChannel() {
        val key = avKey() ?: return
        updatePrefs { it.copy(avChannels = it.avChannels - key) }
        applyTracks()
        flashOsd("Back to the settings for all channels")
    }

    /** Reads the player's audio and caption tracks, then applies the saved choices to them. */
    internal fun tracksChanged(tracks: androidx.media3.common.Tracks) {
        lastTracks = tracks
        val audio = ArrayList<com.avidviewer.tv.data.AvTrack>()
        val text = ArrayList<com.avidviewer.tv.data.AvTrack>()
        tracks.groups.forEachIndexed { gi, g ->
            val type = g.type
            if (type != androidx.media3.common.C.TRACK_TYPE_AUDIO && type != androidx.media3.common.C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val t = com.avidviewer.tv.data.AvTrack(
                    audio = type == androidx.media3.common.C.TRACK_TYPE_AUDIO, group = gi, index = i,
                    lang = f.language, label = f.label, channels = f.channelCount, mime = f.sampleMimeType ?: f.codecs,
                    roles = f.roleFlags, selected = g.isTrackSelected(i), cc = f.accessibilityChannel,
                )
                if (t.audio) audio.add(t) else text.add(t)
            }
        }
        audioTracks = audio
        captionTracks = text
        applyTracks()
    }

    /** Puts the saved audio track and captions on the player (only when something would change). */
    @OptIn(UnstableApi::class)
    private fun applyTracks() {
        val want = avFor(avKey())
        val groups = lastTracks.groups
        val b = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
        com.avidviewer.tv.data.Av.pick(audioTracks, want.audio)?.let { t ->
            groups.getOrNull(t.group)?.let { b.setOverrideForType(androidx.media3.common.TrackSelectionOverride(it.mediaTrackGroup, t.index)) }
        }
        if (want.captions == true) {
            b.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
            val t = com.avidviewer.tv.data.Av.pick(captionTracks, want.captionTrack) ?: captionTracks.firstOrNull()
            t?.let { groups.getOrNull(it.group)?.let { g -> b.setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, it.index)) } }
        } else b.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
        val next = b.build()
        if (next != player.trackSelectionParameters) player.trackSelectionParameters = next
    }

    /** The audio track and captions playing now (for the panel). */
    fun currentAudio(): com.avidviewer.tv.data.AvTrack? =
        avFor(avKey()).audio?.let { com.avidviewer.tv.data.Av.pick(audioTracks, it) } ?: audioTracks.firstOrNull { it.selected }

    fun currentCaptions(): com.avidviewer.tv.data.AvTrack? =
        if (avFor(avKey()).captions != true) null else com.avidviewer.tv.data.Av.pick(captionTracks, avFor(avKey()).captionTrack) ?: captionTracks.firstOrNull()

    fun stop() {
        if (vodPlaying != null) saveResumePoint()
        recordWatch()
        player.stop()
    }

    private fun contextList(): List<Channel> =
        if (playContext == PlayContext.FAVORITES) favoriteChannels() else channelsIn(selectedGroup ?: channel(currentKey)?.group)

    /** Live games with a channel, for Channel Up/Down in full screen from Sports. */
    private fun liveGames(league: String?): List<SportGame> =
        sportGames.filter { (league == null || it.game.league == league) && it.game.live && it.channels.isNotEmpty() }

    private fun leagueLabel(id: String?): String = com.avidviewer.tv.data.League.of(id)?.name ?: "Sports"

    fun contextLabel(): String {
        if (playContext == PlayContext.SPORTS) {
            val live = liveGames(sportsPlayLeague)
            val i = live.indexOfFirst { g -> g.channels.any { it.key == currentKey } }
            return "Live ${leagueLabel(sportsPlayLeague)} games" + if (i >= 0) " · ${i + 1} of ${live.size}" else ""
        }
        val list = contextList()
        val i = list.indexOfFirst { it.key == currentKey }
        val name = if (playContext == PlayContext.FAVORITES) "Favorites" else groupName(selectedGroup ?: "")
        return if (i < 0) name else "$name · ${i + 1} of ${list.size}"
    }

    /** Remote Up / Channel Up = -1 (previous), Down / Channel Down = +1 (next). Wraps at both ends. */
    fun step(dir: Int) {
        if (playContext == PlayContext.SPORTS) {
            // Channel Up/Down moves between live games (each on its best channel).
            val live = liveGames(sportsPlayLeague)
            if (live.isEmpty()) { flashOsd("No live ${leagueLabel(sportsPlayLeague)} games with a channel"); return }
            var i = live.indexOfFirst { g -> g.channels.any { it.key == currentKey } }
            i = if (i < 0) 0 else (i + dir + live.size) % live.size
            val g = live[i]
            sportsChannel(g.channels.first().key)?.let { start(it) }
            flashOsd(contextLabel() + " · " + g.game.away.name + " at " + g.game.home.name)
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

    /** A short notice over any screen (e.g. "Press Back again to exit"). */
    var notice by mutableStateOf<String?>(null); private set
    private var noticeJob: Job? = null
    fun showNotice(text: String, ms: Long = 2500) {
        notice = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch { delay(ms); notice = null }
    }

    /** Back at the very top: the first press says how to exit, a second one within 2.5 s exits (false). */
    private fun exitPressed(): Boolean {
        val t = android.os.SystemClock.uptimeMillis()
        if (exitArmedAt > 0 && t - exitArmedAt < 2500) { exitArmedAt = 0; notice = null; return false }
        exitArmedAt = t
        showNotice("Press Back again to exit")
        return true
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
