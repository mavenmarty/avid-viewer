package com.avidviewer.tv.ui

import android.app.Application
import android.text.format.DateFormat
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.avidviewer.tv.data.EventNames
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
import com.avidviewer.tv.data.Net
import com.avidviewer.tv.data.Profile
import com.avidviewer.tv.data.ProfileSet
import com.avidviewer.tv.data.Kids
import com.avidviewer.tv.data.Xmltv
import com.avidviewer.tv.data.Xtream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
enum class GuideFocus {
    TOP, KEEP, CURRENT,
    /** The first channel fully on screen (▶ from the category list). */
    VISIBLE,
}

/** How many days of guide a download keeps (the guide scrolls as far as its data goes, up to this). */
const val GUIDE_DAYS = 7
/** 1.18.0: the live card in Continue watching is a [VodItem] of this kind, keyed [LIVE_PREFIX] + channel key. */
const val LIVE_KIND = "live"
/** Movies and Series categories that are TMDB genres: "genre:28" (Nov 21, 2026). */
const val GENRE_CAT = "genre:"
/** The start-up screen stays at least this long, and at most [SPLASH_MAX_MS]. Nov 21, 2026. */
const val SPLASH_MIN_MS = 1_500L
const val SPLASH_MAX_MS = 5_000L
/** A quiet start ends once the video has played steadily this long… Nov 21, 2026. */
const val QUIET_PLAYING_MS = 30_000L
/** …or nothing has played for this long, or at the latest after [QUIET_MAX_MS]. */
const val QUIET_IDLE_MS = 30_000L
const val QUIET_MAX_MS = 120_000L

/** No video on screen for this long (another screen): the stream stops. Nov 21, 2026. */
const val PARK_SCREEN_MS = 10_000L
/** The main menu open over the Guide or Sports for this long: the stream stops. */
const val PARK_MENU_MS = 1_500L
/** The categories above the provider's (or the genres) in Movies and Series. */
val VOD_SPECIALS = listOf("All", "Recently added", "Continue watching", "My List")
/** 1.18.0: how channels and lists are sorted. */
const val SORT_PROVIDER = "provider"
/** How long after start Sports' schedules and channels are loaded in the background. */
const val SPORTS_PRELOAD_AFTER = 120_000L
/** After hiding or showing channels, how long to wait for more changes before the guide is downloaded again. */
const val GUIDE_WANTED_SETTLE = 20_000L
/** #29: how long a channel the provider refused stays marked in the guide. */
const val FAILED_MARK_MS = 24 * 3600_000L
const val SORT_AZ = "az"
const val SORT_MANUAL = "manual"
const val LIVE_PREFIX = "live|"
/** A live channel is shared with the profile's other TVs after this long on it. */
const val LIVE_AFTER = 2 * 60_000L
/** What the live entry's item id is made from: one entry per profile. */
const val LIVE_MATCH = "l|live"

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

    // ---------------- profiles ----------------
    /**
     * At start ([ProfileSet.launch]): with a Kids profile on the device, Who's watching? (Kids behind it); otherwise the
     * last profile used, behind Who's watching? only when it's locked with its own PIN.
     */
    private val startup: Pair<ProfileSet, ProfileSet.Launch> = run {
        val existing = !store.isNewInstall()
        val s = store.loadProfiles().let { s0 -> s0.withThemes(existing)?.also { store.saveProfiles(it) } ?: s0 }
        val l = s.launch()
        store.profileId = l.id
        s.copy(active = l.id) to l
    }
    var profiles by mutableStateOf(startup.first); private set
    val profile: Profile get() = profiles.activeProfile
    /** The active profile's colour theme; every screen reads its colours through it ([C]). */
    val theme: Theme get() = Themes.of(profile.theme)
    init { C.source = { theme } }
    fun setTheme(id: String, p: Profile = profile) = profiles.get(p.id)?.let { updateProfile(it.copy(theme = id)) }
    /** Movies and Series layout for the profile: "grid", "featured" or "showcase". */
    // Nov 18, 2026: Featured is retired; the Grid now carries the highlighted title's artwork. A profile set to
    // Featured shows the Grid.
    val vodLayout: String get() = profile.vodLayout.let { if (it == "featured") "grid" else it }
    fun setVodLayout(l: String) = updateProfile(profile.copy(vodLayout = l))
    /** The profile in use is a Kids profile: only its categories, no Settings, Discover or Sports. */
    val kidsMode: Boolean get() = profile.kids
    /** Who's watching? is on screen; nothing plays until a profile is chosen. */
    var choosingProfile by mutableStateOf(startup.second.choose); private set
    /** 1.15.0: the 5-second "Watching as Kids" splash at start (only when the app opens on a Kids profile). */
    var splashProfile by mutableStateOf(if (startup.second.splash) startup.second.id else null); private set
    fun dismissSplash() { splashProfile = null }
    /** Change profile on the splash: Who's watching? (a grown-up profile then asks for its PIN). */
    fun changeFromSplash() { splashProfile = null; askWhoIsWatching() }
    /** The profile switcher (from the main menu) is open. */
    var profileSwitcher by mutableStateOf(false)
    /** A PIN is being asked for: why, and what to do with a right one. */
    data class PinAsk(val title: String, val note: String, val avatar: Profile?, val check: (String) -> Boolean, val then: () -> Unit)
    var pinAsk by mutableStateOf<PinAsk?>(null); private set
    private var pinFails = 0
    private var pinLockedUntil = 0L

    /** The PIN dialog's answer: right → its action; wrong → false (5 wrong in a row wait a minute). */
    fun answerPin(pin: String): String? {
        val ask = pinAsk ?: return null
        val nowMs = System.currentTimeMillis()
        if (nowMs < pinLockedUntil) return "Too many tries. Wait ${((pinLockedUntil - nowMs) / 1000 + 1)} s."
        if (ask.check(pin)) { pinFails = 0; pinAsk = null; ask.then(); return null }
        pinFails++
        if (pinFails >= 5) { pinFails = 0; pinLockedUntil = nowMs + 60_000L; return "Too many tries. Wait a minute." }
        return "That PIN isn't right."
    }
    fun cancelPin() { pinAsk = null }

    /** Shows Who's watching? again (as at start); nothing plays until a profile is chosen. */
    fun askWhoIsWatching() { if (profiles.multiple) { recordWatch(); player.stop(); choosingProfile = true } }

    /** Asks for a parent's PIN, then runs [then]. Without a parent PIN set yet, runs it straight away. */
    fun withParentPin(why: String, then: () -> Unit) {
        if (profiles.list.none { it.isParent && it.hasPin }) { then(); return }
        pinAsk = PinAsk("Parent PIN", why, null, { profiles.isParentPin(it) }, then)
    }

    /** Opens profile [id] (from Who's watching? or the switcher), asking for its PIN when it has to. */
    fun chooseProfile(id: String) {
        val p = profiles.get(id) ?: return
        profileSwitcher = false
        if (p.id == profile.id && !choosingProfile) return
        if (profiles.needsPin(p)) {
            pinAsk = PinAsk(
                if (p.hasPin) "Enter ${p.name}'s PIN" else "Parent PIN to open ${p.name}",
                if (profiles.hasKids) "A Kids profile is on this device, so ${p.name} needs a PIN" else "",
                p, { profiles.unlocks(p, it) }, { openProfile(p.id) },
            )
        } else openProfile(p.id)
    }

    /** Switches to profile [id]: its favorites, lists, hidden items, history and limits. */
    private fun openProfile(id: String) {
        val target = profiles.get(id) ?: return
        recordWatch()
        if (id != profile.id) {
            player.stop(); player.clearMediaItems(); vodPlaying = null
            store.profileId = id
            profiles = profiles.copy(active = id, lastKids = if (target.kids) id else profiles.lastKids).also { store.saveProfiles(it) }
            prefs = store.loadPrefs()
            reorderSportChannels()
            history = store.loadHistory(); resume = store.loadResume(); watched = LinkedHashSet(store.loadWatched()); placeInfo = store.loadPlaceInfo()
            applyDiscoverStart()
            otherLive.clear(); playlistGroups = emptyMap(); favDataTick++
            applyProfileVodLimits()
            // Playlists limited to some profiles: what this profile sees may differ (1.14.0).
            if (playlists.any { it.profiles != null }) { resetVod(); reloadAfterActiveChange() }
        }
        choosingProfile = false; splashProfile = null
        // Start like at launch, on this profile's last channel.
        screen = Screen.GUIDE; navMode = NavMode.GROUPS; vodDetail = null
        currentKey = null; selectedGroup = null; startupPreviewDone = false
        status = "Watching as ${target.name}"
        if (channels.isNotEmpty()) applyChannels(channels)
        ensureFavoriteData()
    }

    /**
     * The catalog's hidden marks for the profile in use: its own hidden titles and categories, and for a
     * Kids profile everything outside its chosen categories (and any adult category). Run on a switch and
     * when a Kids profile's categories change; a background pass then picks each title's copy again.
     */
    private fun applyProfileVodLimits() {
        val p = profile
        val cats = prefs.vodHiddenCats
        val titles = prefs.vodHiddenTitles
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { catalog.applyHidden(cats, titles, if (p.kids) kidsVodAllowed(p) else null) }
                store.catalogProfile = p.id
            }
            vodVersion++
            regroupLater()
        }
    }

    private fun kidsVodAllowed(p: Profile): Set<String> = p.allowedVodCats.filterNot { Kids.isAdult(it.substringAfterLast('|')) }.toSet()

    /** A Kids profile may see live category [raw] of playlist [pid]. */
    private fun kidsAllowsGroup(pid: String, raw: String) =
        (hiddenId(pid, raw) in profile.allowedGroups) && !Kids.isAdult(raw)

    /** [c] can be shown and played by the profile in use. */
    fun channelAllowed(c: Channel): Boolean = !kidsMode || kidsAllowsGroup(c.key.substringBefore(':'), c.group)

    // ---- managing profiles (Settings › Profiles) ----
    private fun newProfileId() = "p" + java.lang.Long.toString(System.currentTimeMillis(), 36)

    /** Adds a profile; returns its id. A Kids profile starts with the categories whose names look made for children. */
    fun addProfile(name: String, kids: Boolean, color: Int): String {
        val id = newProfileId()
        val p = Profile(id, name.trim().ifEmpty { if (kids) "Kids" else "Profile" }, color, kids = kids, theme = com.avidviewer.tv.data.THEME_DEFAULT,
            allowedGroups = if (kids) suggestedKidsGroups() else emptySet(), allowedVodCats = if (kids) suggestedKidsVodCats() else emptySet())
        // A new grown-up profile starts with a copy of the owner's favorites and lists; Kids starts empty.
        if (!kids) store.savePersonal(id, com.avidviewer.tv.data.personalOf(store.loadPrefs(Profile.OWNER)).apply { remove("lastChannel") })
        else store.savePersonal(id, org.json.JSONObject())
        saveProfiles(profiles.copy(list = profiles.list + p))
        return id
    }

    fun updateProfile(p: Profile) {
        val before = profiles.get(p.id)
        saveProfiles(profiles.copy(list = profiles.list.map { if (it.id == p.id) p else it }))
        if (p.id == profile.id && before != null && (before.allowedVodCats != p.allowedVodCats || before.kids != p.kids)) applyProfileVodLimits()
        if (p.id == profile.id) favDataTick++
    }

    fun deleteProfile(id: String) {
        if (id == Profile.OWNER || id == profile.id) return
        store.deleteProfileData(id)
        saveProfiles(profiles.copy(list = profiles.list.filterNot { it.id == id }))
    }

    fun setStartMode(mode: String) = saveProfiles(profiles.copy(startMode = mode))

    /** The profile being edited in Settings › Profiles. */
    var editingProfile by mutableStateOf<String?>(null)

    /** Asks for a new 4-digit PIN twice, then gives it to [done]. */
    fun askNewPin(p: Profile, done: (String) -> Unit) {
        var first = ""
        pinAsk = PinAsk("New PIN for ${p.name}", "4 digits. You'll enter it twice.", p, { first = it; true }) {
            pinAsk = PinAsk("Enter the new PIN again", "", p, { it == first }) { done(first) }
        }
    }

    fun setProfilePin(id: String, pin: String?) { profiles.get(id)?.let { updateProfile(it.withPin(pin)) } }

    /**
     * Adds a Kids profile. Kids opens without a PIN and every other profile needs one, so when no parent
     * has a PIN yet the owner sets one first.
     */
    fun addKidsProfile(name: String, color: Int, done: (String) -> Unit = {}) {
        val owner = profiles.owner
        if (profiles.list.none { it.isParent && it.hasPin }) {
            askNewPin(owner) { pin -> setProfilePin(owner.id, pin); done(addProfile(name, true, color)) }
        } else done(addProfile(name, true, color))
    }

    private fun saveProfiles(s: ProfileSet) { profiles = s; store.saveProfiles(s) }

    /** Live categories of every live playlist that look made for children (for a new Kids profile). */
    private fun suggestedKidsGroups(): Set<String> = livePlaylists.flatMap { pl ->
        runCatching { live.groupCounts(pl.id).map { it.first } }.getOrDefault(emptyList()).filter { Kids.looksForKids(it) }.map { hiddenId(pl.id, it) }
    }.toSet()

    private fun suggestedKidsVodCats(): Set<String> = listOf("movie", "series").flatMap { k ->
        catalog.categories(k, vodPids(), includeHidden = true).filter { Kids.looksForKids(it.second) }.map { (pid, c, _) -> vodCatKey(k, pid, c) }
    }.toSet()

    /** Every live category of every live playlist, for choosing a Kids profile's: (playlist, raw name, channels). */
    fun allLiveCategories(): List<Triple<Playlist, String, Int>> {
        val key = livePlaylists.map { it.id } to System.identityHashCode(channels)
        liveCatsCache?.let { (k, v) -> if (k == key) return v }
        val v = livePlaylists.flatMap { pl -> runCatching { live.groupCounts(pl.id) }.getOrDefault(emptyList()).map { (g, n) -> Triple(pl, g, n) } }
        liveCatsCache = key to v
        return v
    }
    private var liveCatsCache: Pair<Pair<List<String>, Int>, List<Triple<Playlist, String, Int>>>? = null
    private var vodCatsCache: Pair<Pair<List<String>, Int>, List<Triple<String, String, Int>>>? = null

    /** Every movie and series category, for choosing a Kids profile's: (key, label, titles). */
    fun allVodCategories(): List<Triple<String, String, Int>> {
        val key = vodPids() to vodVersion
        vodCatsCache?.let { (k, v) -> if (k == key) return v }
        val v = listOf("movie" to "Movies", "series" to "Series").flatMap { (k, label) ->
            runCatching { catalog.categories(k, vodPids(), includeHidden = true) }.getOrDefault(emptyList()).map { (pid, c, n) ->
                val pl = playlists.firstOrNull { it.id == pid }?.name.orEmpty()
                Triple(vodCatKey(k, pid, c), "$label · $c" + if (vodPids().size > 1) " · $pl" else "", n)
            }
        }
        vodCatsCache = key to v
        return v
    }

    /** Screens the profile in use can open: a Kids profile has no Discover, Sports or Settings. */
    fun screenAllowed(s: Screen) = !kidsMode || (s != Screen.DISCOVER && s != Screen.SPORTS && s != Screen.SETTINGS && s != Screen.PLAYLISTS)

    fun liveCategoryId(pid: String, raw: String) = hiddenId(pid, raw)
    fun isAdultCategory(name: String) = Kids.isAdult(name)

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
    var vodFocusBack by mutableStateOf(0)

    /** Movies / Series category: each category keeps its own place (a new one starts at the top). */
    fun chooseVodCategory(kind: String, name: String) {
        vodCategory = vodCategory + (kind to name)
    }

    /** Movies / Series: the highlight is on a poster (not the category list). */
    var vodOnPoster = false
    /** Movies / Series: put the highlight on the current category (Back or Left from the posters). */
    var vodCatFocus by mutableIntStateOf(0)
    var selectedGroup by mutableStateOf<String?>(null)
    var currentKey by mutableStateOf<String?>(null); private set
    var playContext by mutableStateOf(PlayContext.GROUP)
    var osd by mutableStateOf<String?>(null); private set
    var status by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set
    var playerError by mutableStateOf<String?>(null); private set
    /** Screenshots for the user guide: the stand-in stream never plays, so its error would cover the picture. */
    internal fun clearPlayerErrorForTests() { player.stop(); player.clearMediaItems(); playerError = null }
    var now by mutableLongStateOf(System.currentTimeMillis()); private set
    var weather by mutableStateOf<Weather?>(null); private set
    var vod by mutableStateOf<List<VodItem>>(emptyList()); private set
    var vodPlaying by mutableStateOf<VodItem?>(null); private set
    var vodError by mutableStateOf<String?>(null); private set
    // Discover (TMDB)
    var discoverTab by mutableStateOf("new")
    var onlyMyServices by mutableStateOf(false)
    /** Discover shows only titles your own playlists have (the "▶ ON YOUR PROVIDER" ones), issue #13. */
    var onlyOnProvider by mutableStateOf(false)
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
    /** Episodes (and movies) played to the end, oldest first. */
    private var watched: LinkedHashSet<String> = LinkedHashSet(store.loadWatched())
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

    // Beta channel (Nov 18, 2026). Declared here, before init runs checkForUpdate.
    private val betaPrefs = app.getSharedPreferences("beta", android.content.Context.MODE_PRIVATE)
    /** This device is in the beta (avidviewer.app chose it), as of the last check-in. */
    var inBeta by mutableStateOf(betaPrefs.getBoolean("in", false)); private set
    /** The beta build offered to this device, e.g. beta-v1.20.0-2.360. */
    private var betaTag: String? = betaPrefs.getString("tag", null)
    /** The installed version is a beta build ("1.20.0 beta 2"). */
    val betaInstalled: Boolean get() = Updater.isBetaVersion(installed.second)

    /** Keeps what the account said about the beta; null when the device isn't linked (public versions only). */
    internal fun noteBeta(m: com.avidviewer.tv.data.AccountApi.Me?) {
        inBeta = m?.beta == true
        betaTag = if (inBeta) m?.betaTag else null
        betaPrefs.edit().putBoolean("in", inBeta).putString("tag", betaTag).apply()
    }

    var updateStatus by mutableStateOf<String?>(null); private set
    var updateBusy by mutableStateOf(false); private set
    /** The downloaded update, when the installer needs a second way in ("Open installer"). */
    var updateFile by mutableStateOf<java.io.File?>(null); private set
    private var installAnswered = false
    var updatePromptDismissed by mutableStateOf(false)
    /**
     * Whether the "Update available" dialog may open (Nov 15): only once the latest release has been out for
     * 24 hours without being installed (a newer build starts the 24 hours again), or 24 hours after "Later".
     * Before that the menu shows "Update …" and Settings › Updates & about has it. Never over full screen.
     */
    var updatePromptAllowed by mutableStateOf(false); private set
    private val updateReminder = com.avidviewer.tv.data.UpdateReminder(app)
    private var lastUpdateCheck = 0L
    private val startedAt = System.currentTimeMillis()

    // ---------------- avidviewer.app account (1.11.0; data/Account.kt) ----------------
    val account = com.avidviewer.tv.data.AccountStore(app)
    /** Where the account API lives; tests point it at a fake server. */
    private companion object { const val ACCOUNT_GUIDE = "acct-guide"; const val LINK_REMINDER_EVERY = 7L * 24 * 60 * 60_000 }

    // Tests never reach the real site: under Robolectric the default address goes nowhere (each test sets a fake).
    internal var accountApi = if (android.os.Build.FINGERPRINT == "robolectric") "http://127.0.0.1:9" else com.avidviewer.tv.data.AccountApi.BASE
    var accountEmail by mutableStateOf(account.email.takeIf { account.linked }); private set
    var accountTvName by mutableStateOf(account.name); private set
    /** The code on screen while linking: the 8 characters, the QR code's address, when it runs out. */
    data class LinkCode(val code: String, val qrUrl: String, val expiresAt: Long)
    var linkCode by mutableStateOf<LinkCode?>(null); private set
    var accountStatus by mutableStateOf<String?>(null); private set
    private var linkJob: Job? = null
    /**
     * The live stream was stopped because no video was on screen (another screen, or the main menu, for a while).
     * The guide then fills the screen until a channel is picked; nothing restarts on its own.
     */
    var streamParked by mutableStateOf(false); private set
    /**
     * Nov 21, 2026: the start-up screen, over everything while the channels and guide saved on this TV are read
     * (at least [SPLASH_MIN_MS], at most [SPLASH_MAX_MS]); keys are ignored meanwhile. Downloads go on afterwards.
     * Off in tests.
     */
    var startSplash by mutableStateOf(android.os.Build.FINGERPRINT != "robolectric"); private set

    // ---------------- a quiet start (Nov 21, 2026) ----------------
    /**
     * Done once the start is over: the video has played steadily for [QUIET_PLAYING_MS], or nothing has played for
     * [QUIET_IDLE_MS], or [QUIET_MAX_MS] have passed. Until then only the guide and the preview run; downloads,
     * the account sync, catalog work, update and episode checks wait. At once without playlists, and in tests.
     */
    internal val quietStartDone = kotlinx.coroutines.CompletableDeferred<Unit>()
    /** Start-up work that waits for [quietStartDone] runs one job at a time, in turn. */
    private val backgroundTurn = kotlinx.coroutines.sync.Mutex()
    /** A low-priority thread for the catalog's heavy work, so it never competes with the video or the remote. */
    private val backgroundThread: kotlinx.coroutines.CoroutineDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "avid-background").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    /** Start-up timings for the playback log: (label, ms after start). Never names. */
    private val startupMarks = java.util.Collections.synchronizedList(ArrayList<Pair<String, Long>>())
    @Volatile private var firstPictureAt = 0L
    private val startedUptime = android.os.SystemClock.uptimeMillis()
    /** Off in tests, which turn it on where they check it. */
    internal var stopWhenNoVideo = android.os.Build.FINGERPRINT != "robolectric"
    /** Something is playing (read by background work off the screen's thread, which waits while it is). */
    @Volatile internal var videoBusy = false
    /** The screen open, for the playback log ("Settings"); never what's on it. */
    @Volatile private var screenNow = "TV Guide"

    private var lastAccountCheck = 0L
    // Declared up here: start-up (init) already uses them. Nov 21, 2026.
    /** Per playlist: categories left out of the last download because every profile hides them. */
    var skippedGroups by mutableStateOf<Map<String, Set<String>>>(emptyMap()); private set
    /** Each playlist's last channel load (website Troubleshooting) and when reseller lines were last checked. */
    private val loadStatus: JSONObject by lazy { store.loadLoadStatus() }
    private var loadReportJob: Job? = null
    private var loadsReported = false
    /**
     * Nov 21, 2026 (owner): when each playlist's subscription ends, as its provider says (Xtream exp_date; M3U links on
     * an Xtream server too). Reminders at 30, 14 and 2 days (each dismissed once); playing from an expired one says so.
     */
    var expiries by mutableStateOf<Map<String, Long>>(emptyMap()); private set
    /** The reminder on screen: (playlist id, stage in days: 30, 14 or 2). */
    var expiryReminder by mutableStateOf<Pair<String, Int>?>(null); private set
    /** "Service has expired", for this playlist id. */
    var expiredNotice by mutableStateOf<String?>(null)

    private fun accountInfo() = com.avidviewer.tv.data.AccountApi.info(getApplication(), installed.second.ifBlank { com.avidviewer.tv.BuildConfig.VERSION_NAME })

    private fun linkedNow(l: com.avidviewer.tv.data.AccountApi.Linked) {
        account.save(l)
        accountEmail = l.email; accountTvName = l.name; linkCode = null; linkFull = 0
        accountStatus = "Linked to ${l.email} as \"${l.name}\"."
        if (linkReminder) { linkReminder = false; status = "This device is linked to ${l.email}. Manage its playlists at avidviewer.app." }
        trail("account linked")
        syncAccountPlaylists()
        claimReports()
        bugFormUrl = null; bugFormExpires = 0L
        if (reportUnlinkedNotice) { reportUnlinkedNotice = false; status = "This device is linked. Its reports show at avidviewer.app › Reports." }
    }

    /** Settings › Account › Link this TV: shows a code + QR code and waits (polling) for approval on the phone. */
    fun startLinking() {
        linkJob?.cancel()
        linkFull = 0
        accountStatus = "Getting a code…"
        linkJob = viewModelScope.launch {
            val start = try {
                withContext(Dispatchers.IO) { com.avidviewer.tv.data.AccountApi.start(accountInfo(), accountApi) }
            } catch (e: Exception) { accountStatus = e.message; return@launch }
            linkCode = LinkCode(start.userCode, start.qrUrl, System.currentTimeMillis() + start.expiresIn * 1000L)
            accountStatus = null
            while (true) {
                delay(start.interval * 1000L)
                val l = try {
                    withContext(Dispatchers.IO) { com.avidviewer.tv.data.AccountApi.poll(start.deviceCode, accountApi) }
                } catch (e: com.avidviewer.tv.data.AccountApi.Problem) {
                    if (System.currentTimeMillis() > (linkCode?.expiresAt ?: 0L)) {
                        linkCode = null
                        // The welcome screen always shows a working code: an expired one is replaced.
                        if (showWelcome || linkRequired) { startLinking(); return@launch }
                        accountStatus = e.message; return@launch
                    }
                    continue // a network blip: keep trying until the code runs out
                }
                linkFull = l.full
                l.linked?.let { linkedNow(it); return@launch }
            }
        }
    }

    fun cancelLinking() { linkJob?.cancel(); linkJob = null; linkCode = null; accountStatus = null; linkFull = 0 }

    /**
     * Nov 20, 2026: an account can have at most 4 devices. When this device's code was entered on a full account,
     * the website says so on the next poll and every link screen shows [LinkFullNotice]; 0 when it isn't.
     */
    var linkFull by mutableStateOf(0); private set

    /** Settings › Account › Sign out of this TV: forgets the link here and tells the account (best effort). */
    fun signOutOfAccount() {
        val token = account.token
        account.clear(); backupPinSet = false; lastBackupAt = 0L; cloudBackups = null; accountEmail = null; accountTvName = ""; linkCode = null; noteBeta(null)
        accountStatus = "This device is no longer linked to an account. Its playlists and settings stay."
        if (token != null) viewModelScope.launch(Dispatchers.IO) { com.avidviewer.tv.data.AccountApi.signOut(token, accountApi) }
    }

    /** Is this TV still linked? A TV removed on the account page unlinks here; network trouble changes nothing. */
    fun checkAccount() {
        val token = account.token ?: return
        lastAccountCheck = System.currentTimeMillis()
        viewModelScope.launch {
            val me = runCatching { withContext(Dispatchers.IO) { com.avidviewer.tv.data.AccountApi.me(token, accountInfo().appVersion, accountApi) } }
            me.onSuccess { m ->
                if (m == null) {
                    account.clear(); backupPinSet = false; lastBackupAt = 0L; cloudBackups = null; accountEmail = null; accountTvName = ""; noteBeta(null)
                    accountStatus = "This device was removed from your account on avidviewer.app. Link it again any time."
                } else {
                    account.update(m); accountEmail = m.email; accountTvName = m.name; noteBeta(m)
                    // "It's still happening" on one of this device's reports: send a new playback log now.
                    if (m.sendLog && playbackLog.entries().isNotEmpty()) sendPlaybackLog(auto = true)
                    if (unclaimedReports().isNotEmpty()) claimReports()
                    if (m.reloadChannels) reloadAllLive()
                    resellerAfterCheckIn(token, m)
                    // Once per run (playlists may have changed while it was off); after that, each download reports itself.
                    if (!loadsReported) { loadsReported = true; reportLoadsLater() }
                }
            }
        }
    }

    val installed: Pair<Long, String> = runCatching { Updater.installedVersion(app) }.getOrDefault(0L to "")
    /** The game highlighted on the Sports screen. */
    var selectedGame by mutableStateOf<String?>(null)
    private var sportsPlayLeague: String? = null
    /** The game whose channels Channel Up/Down steps through in full screen from Sports (issue #12). */
    private var sportsPlayGame: String? = null
    /** Set when Back leaves full screen for Sports: the screen puts the highlight back on the game (issue #11). */
    var sportsReturnPending by mutableStateOf(false)
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

    /** Download speed while streaming, for Stream info and the playback log. */
    private val bandwidth = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(app).build()

    /** #30, #32: which video decoder each stream uses (the TV's own, or Android's software one after trouble). */
    internal val decoders = com.avidviewer.tv.data.Decoders(app.getSharedPreferences("decoders", android.content.Context.MODE_PRIVATE))

    /**
     * Nov 20, 2026: a lighter start-up on ~2 GB devices (DeviceClass): the preview waits [DeviceClass.PREVIEW_DELAY_MS]
     * after the guide appears and the player's buffer is capped. Tests set it themselves.
     */
    var lightStart = com.avidviewer.tv.data.DeviceClass.isSmall(app)

    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setBandwidthMeter(bandwidth)
        .apply {
            if (lightStart) setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setTargetBufferBytes(com.avidviewer.tv.data.DeviceClass.BUFFER_BYTES)
                    .build()
            )
        }
        .setRenderersFactory(
            androidx.media3.exoplayer.DefaultRenderersFactory(app)
                .setMediaCodecSelector(decoders.selector)
                // A decoder that can't start hands over to the next one on the list instead of failing.
                .setEnableDecoderFallback(true)
        )
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(app).setDataSourceFactory(
                DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT).setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
                    // When data last arrived, for reconnecting a stream that stopped (PlaybackMonitor).
                    .setTransferListener(PlaybackMonitor.transfer)
            )
        )
        .build()

    // ---------------- playback log (1.5.0) ----------------
    val playbackLog = com.avidviewer.tv.data.PlaybackLog(java.io.File(app.filesDir, "playback-log.txt"))
    val playback = PlaybackMonitor(player, bandwidth, playbackLog)
    var playbackLogBusy by mutableStateOf(false); private set
    var playbackLogStatus by mutableStateOf<String?>(null)
    /** Changes whenever the log does, so Settings redraws its summary. */
    var playbackLogRev by mutableStateOf(0); private set
    internal var playbackRelay: String = com.avidviewer.tv.data.Links.CRASH_RELAY

    /**
     * Sends the playback log to avidviewer.app (else through the email relay) ([auto]: the once-a-day send, or the one the
     * account asked for after "It's still happening").
     */
    fun sendPlaybackLog(auto: Boolean = false) {
        if (playbackLogBusy) return
        val newest = playbackLog.entries().lastOrNull()?.time ?: return
        playbackLogBusy = true
        if (!auto) playbackLogStatus = "Sending…"
        viewModelScope.launch {
            val sent = withContext(Dispatchers.IO) {
                val c = com.avidviewer.tv.data.CrashReport
                c.deliver(c.logPayload(installed.second, playbackLog.summary(), playbackLog.text()), reportsSite, account.token, playbackRelay)
            }
            playbackLogBusy = false
            if (sent.ok) { playbackLog.markSent(newest, auto); noteReportSent(sent, show = !auto) }
            if (!auto) playbackLogStatus = if (!sent.ok) "Couldn't send the log. Check the connection and try again."
                else if (sent.viaSite && sent.linked) "Sent. Follow it at avidviewer.app › Reports. Thanks."
                else "Sent. Thanks."
            playbackLogRev++
        }
    }

    /** Where reports go first (avidviewer.app; nowhere in tests, so they use the relay as before). */
    internal var reportsSite: String = if (android.os.Build.FINGERPRINT == "robolectric") "" else com.avidviewer.tv.data.AccountApi.BASE

    /**
     * Shown after a report is sent from a device that isn't linked: it can't be followed on avidviewer.app until the
     * device is linked (owner, Nov 20), with the link code and QR code to do it now.
     */
    var reportUnlinkedNotice by mutableStateOf(false); private set
    private val reportPrefs by lazy { app.getSharedPreferences("reports", android.content.Context.MODE_PRIVATE) }

    /** Reports this device sent before it was linked, to hand to the account once it is (see [claimReports]). */
    internal fun unclaimedReports(): List<String> = reportPrefs.getString("unclaimed", "").orEmpty().split(',').filter { it.startsWith("AVR-") }

    private fun noteReportSent(sent: com.avidviewer.tv.data.CrashReport.Sent, show: Boolean) {
        if (!sent.viaSite || sent.linked || sent.id == null) return
        reportPrefs.edit().putString("unclaimed", (unclaimedReports() + sent.id).takeLast(50).joinToString(",")).apply()
        if (show && !account.linked) { reportUnlinkedNotice = true; if (linkCode == null) startLinking() }
    }

    fun closeReportUnlinkedNotice() {
        reportUnlinkedNotice = false
        if (!account.linked) cancelLinking()
    }

    /**
     * Report a bug (Settings › Updates & about): the QR code opens avidviewer.app/report/?c=… (owner, Nov 20), where the
     * problem is written on a phone; the code tells the site which device it's about. Null until the site answers (or
     * when it can't be reached): the block then shows the email QR code as before.
     */
    var bugFormUrl by mutableStateOf<String?>(null); private set
    private var bugFormExpires = 0L
    private var bugFormBusy = false
    /** Nov 20: avidviewer.app couldn't be reached for the Report a problem page (no email fallback any more). */
    var bugFormFailed by mutableStateOf(false); private set

    fun loadBugForm() {
        if (bugFormBusy || (bugFormUrl != null && System.currentTimeMillis() < bugFormExpires - 3_600_000L)) return
        bugFormBusy = true; bugFormFailed = false
        val token = account.token
        val info = accountInfo()
        val crash = com.avidviewer.tv.data.BugReport.crashSummary(lastCrash)
        viewModelScope.launch {
            val form = runCatching { withContext(Dispatchers.IO) { com.avidviewer.tv.data.AccountApi.reportForm(token, info, com.avidviewer.tv.data.BugReport.device(), crash, accountApi) } }.getOrNull()
            bugFormBusy = false
            if (form != null) { bugFormUrl = form.url; bugFormExpires = form.expiresAt } else if (bugFormUrl == null) bugFormFailed = true
        }
    }

    /** After linking: the reports sent before now show on the account's Reports page. */
    private fun claimReports() {
        val token = account.token ?: return
        val ids = unclaimedReports().ifEmpty { return }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { com.avidviewer.tv.data.AccountApi.claimReports(token, ids, accountApi) }
                .onSuccess { reportPrefs.edit().remove("unclaimed").apply() }
        }
    }

    fun clearPlaybackLog() { playbackLog.clear(); playbackLogStatus = null; playbackLogRev++ }

    /**
     * freeRASP alerts (a rooted TV, hooking, a tampered copy…; see SecurityMonitor) go to avidviewer.app (Admin › Reports):
     * each kind once, at most one a day.
     */
    private var securityBusy = false
    private fun maybeSendSecurityAlert() {
        val log = com.avidviewer.tv.data.SecurityMonitor.log ?: return
        if (securityBusy || android.os.Build.FINGERPRINT == "robolectric") return
        val kinds = log.pending().ifEmpty { return }
        if (System.currentTimeMillis() - log.lastSent < 24 * 3_600_000L) return
        securityBusy = true
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val c = com.avidviewer.tv.data.CrashReport
                c.deliver(c.securityPayload(installed.second, log.summary(kinds), log.text()), reportsSite, account.token, com.avidviewer.tv.data.Links.CRASH_RELAY).ok
            }
            if (ok) log.markSent(kinds)
            securityBusy = false
        }
    }

    /** At most once a day, when freezes or errors happened since the last send ([Prefs.autoSendPlaybackLog]). */
    private fun maybeAutoSendPlaybackLog() {
        if (!prefs.autoSendPlaybackLog || playbackLogBusy) return
        if (android.os.Build.FINGERPRINT == "robolectric") return // never from tests
        if (playbackLog.unsent().isEmpty()) return
        if (System.currentTimeMillis() - playbackLog.lastAutoSend < 24 * 3_600_000L) return
        sendPlaybackLog(auto = true)
    }

    /**
     * The welcome screen: shown at start while the app has no playlists. It links the device to an account; playlists
     * are then added at avidviewer.app. Restoring a backup is in Settings › Updates & about, not here.
     */
    var showWelcome by mutableStateOf(false)

    private var osdJob: Job? = null
    private var watchStart = 0L
    private var watchKey: String? = null

    // ---------------- backup & restore: in the avidviewer.app account (1.12.0) ----------------
    // Only a linked TV backs up or restores. Each TV keeps one backup in the account, its most recent; the PIN is
    // chosen at the first backup, remembered here (encrypted) for the daily ones, and shown on the Account page.
    var backupStatus by mutableStateOf<String?>(null); private set
    var backupBusy by mutableStateOf(false); private set
    var backupPinSet by mutableStateOf(account.backupPin != null); private set
    var lastBackupAt by mutableStateOf(account.lastBackupAt); private set
    var autoBackup by mutableStateOf(account.autoBackup); private set
    /** The account's backups, after Restore from your account. */
    var cloudBackups by mutableStateOf<List<com.avidviewer.tv.data.AccountApi.CloudBackup>?>(null); private set
    /** A backup chosen for restoring, waiting for its PIN. */
    var pendingRestore by mutableStateOf<String?>(null); private set
    var pendingRestoreInfo by mutableStateOf(""); private set
    private var lastBackupCheck = 0L

    private fun backupContents() = com.avidviewer.tv.data.Backup.Contents(
        playlists,
        // The owner's settings and history, whichever profile is in use; the others go in profileFiles.
        if (profile.isOwner) prefs else store.loadPrefs(Profile.OWNER),
        tmdbKey(),
        if (profile.isOwner) history else store.loadHistory(Profile.OWNER),
        if (profile.isOwner) resume else store.loadResume(Profile.OWNER),
        System.currentTimeMillis(), installed.second, "", backupRefs(),
        profiles = profiles.copy(active = Profile.OWNER).toJson(),
        profileFiles = store.exportProfileFiles(),
    )

    /**
     * Back up now (or the daily automatic backup, [auto]). [newPin] sets or changes the PIN first; without a PIN
     * the screen asks for one. The automatic backup skips sending when nothing changed since the last one.
     */
    fun backUp(newPin: String? = null, auto: Boolean = false) {
        val token = account.token ?: run { if (!auto) backupStatus = "Link this device to your account first (Settings › Account)."; return }
        if (newPin != null) {
            if (newPin.length < 4 || newPin.any { !it.isDigit() }) { backupStatus = "Choose a PIN of at least 4 digits."; return }
            account.backupPin = newPin; backupPinSet = true
        }
        val pin = account.backupPin ?: return
        if (backupBusy) return
        viewModelScope.launch {
            if (!auto) { backupBusy = true; backupStatus = "Backing up to your account…" }
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val c = backupContents()
                    val print = com.avidviewer.tv.data.Backup.fingerprint(c)
                    if (auto && print == account.backupPrint) null
                    else Pair(com.avidviewer.tv.data.Backup.write(c, pin), print)
                }?.also { (text, _) -> withContext(Dispatchers.IO) { com.avidviewer.tv.data.AccountApi.uploadBackup(token, text, pin, accountApi) } }
            }
            backupBusy = false
            result.onSuccess { sent ->
                if (sent != null) {
                    account.backupPrint = sent.second
                    val now = System.currentTimeMillis()
                    account.lastBackupAt = now; lastBackupAt = now
                    trail(if (auto) "auto backup sent" else "backup sent")
                }
                if (!auto) backupStatus = "Backed up to your account. If you forget the PIN, it's on avidviewer.app/account."
            }.onFailure { if (!auto) backupStatus = "Couldn't back up: ${it.message}" else trail("auto backup failed") }
        }
    }

    fun changeAutoBackup(on: Boolean) { account.autoBackup = on; autoBackup = on }

    /** From the ticker: once a day, and two minutes after start, back up if something changed. */
    internal fun autoBackupIfDue(now: Long) {
        if (!account.linked || !autoBackup || account.backupPin == null || backupBusy) return
        if (now - startedAt < 2 * 60_000L) return
        if (lastBackupCheck != 0L && now - lastBackupCheck < 24 * 60 * 60 * 1000L) return
        if (lastBackupCheck == 0L && now - account.lastBackupAt < 24 * 60 * 60 * 1000L) { lastBackupCheck = account.lastBackupAt; return }
        lastBackupCheck = now
        backUp(auto = true)
    }

    /** Restore from your account: lists every TV's backup, so a new TV can copy another's setup. */
    fun loadCloudBackups() {
        val token = account.token ?: run { backupStatus = "Link this device to your account first (Settings › Account)."; return }
        viewModelScope.launch {
            backupBusy = true; backupStatus = "Looking for backups in your account…"
            runCatching { withContext(Dispatchers.IO) { com.avidviewer.tv.data.AccountApi.listBackups(token, accountApi) } }
                .onSuccess { cloudBackups = it; backupStatus = if (it.isEmpty()) "There are no backups in your account yet. Choose Back up now on a linked device to make one." else null }
                .onFailure { backupStatus = it.message }
            backupBusy = false
        }
    }

    fun closeCloudBackups() { cloudBackups = null }

    fun chooseCloudBackup(b: com.avidviewer.tv.data.AccountApi.CloudBackup) {
        val token = account.token ?: return
        viewModelScope.launch {
            backupBusy = true
            runCatching { withContext(Dispatchers.IO) { com.avidviewer.tv.data.AccountApi.downloadBackup(token, b.deviceId, accountApi) } }
                .onSuccess { offerRestore(it, from = if (b.thisTv) "This device" else b.name) }
                .onFailure { backupStatus = it.message }
            backupBusy = false
        }
    }

    internal fun offerRestore(text: String, from: String? = null) {
        runCatching { com.avidviewer.tv.data.Backup.peek(text) }.onSuccess { (created, ver, names) ->
            pendingRestore = text
            pendingRestoreInfo = (if (from != null) "$from · made " else "Made ") + SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(created) +
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
            // A restore comes back as the owner; the backup's profiles replace these (older backups have none).
            if (!profile.isOwner) {
                player.stop(); store.profileId = Profile.OWNER
                profiles = profiles.copy(active = Profile.OWNER).also { store.saveProfiles(it) }
                prefs = store.loadPrefs(); watched = LinkedHashSet(store.loadWatched())
            }
            c.profiles?.let { j ->
                val set = ProfileSet.fromJson(j).copy(active = Profile.OWNER)
                withContext(Dispatchers.IO) { store.saveProfiles(set); c.profileFiles?.let { store.importProfileFiles(it) } }
                profiles = set
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
                    smbPassEnc = "", // network-share backups ended in 1.12.0
                )
            }
            otherLive.clear(); otherVod.clear(); playlistGroups = emptyMap()
            vod = emptyList(); vodLoadedFor = null
            withContext(Dispatchers.IO) { catalog.retainOnly(c.playlists.map { it.id }); live.retainOnly(c.playlists.map { it.id }) }
            vodVersion++
            // Point favorites, lists etc. at the right channels even if the provider's list has changed since the backup.
            placeRestoredRefs(c.refs)
            applyProfileVodLimits()
            pendingRestore = null; cloudBackups = null
            // A TV restored this way backs up with the same PIN from now on (if it didn't have one).
            if (account.linked && account.backupPin == null) { account.backupPin = pin; backupPinSet = true }
            backupBusy = false
            backupStatus = "Restored ${c.playlists.size} playlist(s) and your settings. Channels and guides are downloading again now."
            done(true)
            reloadAfterActiveChange()
            if (prefs.postalCode.isNotBlank()) refreshWeather()
            syncAllPlaylists(force = false)
        }
    }

    /** Details of the last crash, if the app closed unexpectedly (shown once at start and in Settings › About). */
    // Closed while on screen last time with nothing caught (the TV freed memory, a native crash): that counts too.
    var lastCrash by mutableStateOf(com.avidviewer.tv.data.CrashLog.read(app)); private set // adopted in AvidViewerApp.onCreate
    var crashNoticeSeen by mutableStateOf(false)

    fun clearCrash() { com.avidviewer.tv.data.CrashLog.clear(getApplication()); lastCrash = null }

    /** For tests: reads the saved crash report again (as the next launch would). */
    internal fun reloadCrash() { lastCrash = com.avidviewer.tv.data.CrashLog.read(getApplication()) }

    /** "Report issue" is sending the crash report. */
    var crashReportBusy by mutableStateOf(false); private set
    /** What the last "Report issue" did ("sent" or why it couldn't), shown in the dialog and in Settings. */
    var crashReportStatus by mutableStateOf<String?>(null)
    /** The report went out: the crash dialog shows a thank-you, then closes. */
    var crashReportSent by mutableStateOf(false); private set

    /** For tests: where reports go (the deployed relay by default). */
    internal var crashRelay: String = com.avidviewer.tv.data.Links.CRASH_RELAY

    /**
     * "Report issue": sends the saved crash report to avidviewer.app (the relay only passes it on there) and,
     * once it's sent, clears it. If it can't be sent, the report stays (Settings › Updates & about) and
     * the reason is shown; Cancel leaves it as it is.
     */
    fun reportCrash() {
        val crash = lastCrash ?: return
        if (crashReportBusy) return
        crashReportBusy = true
        crashReportStatus = "Sending…"
        viewModelScope.launch {
            val sent = withContext(Dispatchers.IO) {
                val c = com.avidviewer.tv.data.CrashReport
                c.deliver(c.payload(installed.second, crash), reportsSite, account.token, crashRelay)
            }
            val ok = sent.ok
            crashReportBusy = false
            if (ok) {
                clearCrash()
                crashReportSent = true
                crashReportStatus = if (sent.viaSite && sent.linked) "Thanks. The report was sent; follow it at avidviewer.app › Reports." else "Thanks. The report was sent."
                noteReportSent(sent, show = true)
                kotlinx.coroutines.delay(2500)
                if (crashReportSent) closeCrashNotice()
            } else {
                crashReportStatus = "Couldn't send the report. Check the connection and try again; it's kept in Settings › Updates & about."
            }
        }
    }

    /** Closes the crash dialog (Cancel, Back, or after the thank-you). The report stays unless it was sent. */
    fun closeCrashNotice() {
        crashNoticeSeen = true
        if (crashReportSent) { crashReportSent = false; crashReportStatus = null }
    }

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
    private val channelsByGroupMemo = Memo<Map<String, List<Channel>>>()
    private val channelsInMemo = Memo<HashMap<String, List<Channel>>>()
    private val vodOfMemo = HashMap<String, Memo<List<VodItem>>>()
    private val vodCatsMemo = HashMap<String, Memo<List<Pair<String, Int>>>>()
    private val vodItemsMemo = HashMap<String, Memo<List<VodItem>>>()
    private val vodAllMemo = HashMap<String, Memo<List<VodItem>>>()
    private val matchCache = HashMap<String, VodItem?>()
    private val versionsCache = HashMap<String, List<VodItem>>()
    private var matchCacheFor = -1

    init {
        loadExpiries() // before anything plays: an expired playlist says so instead
        if (prefs.clearCacheOnExit) store.clearCache() // catches exits Android didn't report
        // Memory in use, every 30 s while on screen, so a hard close shows what it was just before (CrashLog).
        if (android.os.Build.FINGERPRINT != "robolectric") viewModelScope.launch {
            // #20: every 5 s for the first 2 minutes (when most hard closes happened), then every 30 s.
            while (true) { delay(if (System.currentTimeMillis() - startedAt < 120_000L) 5_000 else 30_000); com.avidviewer.tv.data.CrashLog.tick(getApplication()) }
        }
        viewModelScope.launch {
            // Playlists sent from avidviewer.app/playlists: once a minute while the app is on screen (1.14.0).
            delay(5_000)
            afterQuietStart() // Nov 21, 2026: after the quiet start (at once on a TV with no playlists yet)
            while (true) { if (onScreen && account.linked) syncAccountPlaylists(); delay(60_000) }
        }
        viewModelScope.launch {
            // Watching now (1.15.0): which playlist and profile are playing, every 15 s, and a Stop from the account.
            delay(3_000)
            while (true) { if (account.linked) runCatching { reportPlaying() }; runCatching { noteLive() }; delay(15_000) }
        }
        viewModelScope.launch {
            while (true) { delay(10_000); runCatching { idleToFullScreen() } }
        }
        // The catalog's hidden marks belong to one profile: put this profile's in place if another's are there,
        // or (a Kids profile) its categories may have changed.
        if (store.catalogProfile != profile.id || kidsMode) applyProfileVodLimits()
        // Titles saved before 1.0.160 get their language in the background, a few hundred at a time,
        // a little after start-up (channels and the guide come first); then Movies & Series regroup.
        viewModelScope.launch {
            // Which copy stands for each title, worked out in the background (not while a list is read).
            // Nov 21, 2026: after the quiet start, in turn, on the low-priority thread.
            delay(3_000)
            afterQuietStart()
            inTurn("catalog") {
                // #14 (1.5.1): titles saved with the older name rules get them again once, so "NF - Allen" and
                // "Allen" count as one title.
                val marker = java.io.File(getApplication<android.app.Application>().filesDir, "catalog-names-2")
                if (!marker.exists()) withContext(backgroundThread) { runCatching { catalog.renormalize(); marker.writeText("1") } }
                val pids = vodPlaylists.map { it.id }
                if (withContext(backgroundThread) { runCatching { catalog.regroup(pids) }.isSuccess }) { groupedFor = pids; vodVersion++ }
                val n = withContext(backgroundThread) { runCatching { if (catalog.needsLanguages()) catalog.fillLanguages() else 0 }.getOrDefault(0) }
                if (n > 0) regroupLater(0) // English copies first now that languages are known
            }
        }
        watchQuietStart()
        player.addAnalyticsListener(playback)
        if (startSplash) viewModelScope.launch {
            val t0 = android.os.SystemClock.uptimeMillis()
            fun waited() = android.os.SystemClock.uptimeMillis() - t0
            while (waited() < SPLASH_MAX_MS) {
                delay(100) // first, so everything this reads exists before it's read (the view model is still starting)
                val ready = playlists.none { it.enabled } || (channels.isNotEmpty() && guide.isNotEmpty())
                if (ready && waited() >= SPLASH_MIN_MS) break
            }
            trail("start-up screen closed after ${waited()} ms (channels ${channels.size}, guide ${guide.size})")
            startSplash = false
            markStart("guide on screen")
            guideFocusTick++
        }
        val load = com.avidviewer.tv.data.DeviceLoad(app)
        playback.deviceLoad = { load.text() }
        playback.screenName = { screenNow }
        watchVideoOnScreen()
        // Nov 15: the stream is reopened when the picture sticks or the data stops (PlaybackMonitor.checkReconnect).
        playback.onReconnect = { live, full, other -> reconnectStream(live, full, other) }
        viewModelScope.launch {
            var tick = 0
            while (true) {
                delay(1_000)
                val before = playbackLog.entries().size
                playback.sample()
                if (playbackLog.entries().size != before) playbackLogRev++
                // Not while a stream is starting; checked every minute.
                if (++tick % 60 == 30) maybeAutoSendPlaybackLog()
                if (tick % 60 == 45) maybeSendSecurityAlert()
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { paused = !playWhenReady }
            override fun onPlayerError(error: PlaybackException) = playerFailed(error)

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                videoBusy = isPlaying
                if (isPlaying) { playerError = null; decoderRetried = null; if (vodPlaying == null) currentKey?.let { noteFailedChannel(it, false) } }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { tracksChanged(tracks) }
            override fun onRenderedFirstFrame() { if (firstPictureAt == 0L) { firstPictureAt = android.os.SystemClock.uptimeMillis() - startedUptime } }
        })
        viewModelScope.launch {
            while (true) {
                now = System.currentTimeMillis()
                // The update reminder comes due while the app stays open; a TV left on for days still hears
                // of new versions (checked every 6 hours when automatic checks are on).
                if (update != null) onUpdateFound(now)
                checkExpiryReminders(now)
                if (prefs.autoUpdateCheck && lastUpdateCheck > 0 && now - lastUpdateCheck > 6 * 60 * 60 * 1000L) checkForUpdate(quiet = true)
                // Linked to an account: check in a minute after start, then every 10 minutes (removed TVs unlink; Reload
                // channels and Check my subscription on the website are picked up). Was every 6 hours before Nov 21, 2026.
                autoBackupIfDue(now)
                if (account.linked && now - lastAccountCheck > (if (lastAccountCheck == 0L) 60_000L else 10 * 60 * 1000L) && now - startedAt > 60_000L) checkAccount()
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
                // Nov 18: Sports is ready before it's opened: 2 minutes after start (channels and guide in), on every
                // TV with live TV and a league switched on; not for a Kids profile (it has no Sports).
                else if (sportsFetchedAt == 0L && quietStartDone.isCompleted && System.currentTimeMillis() - startedAt > SPORTS_PRELOAD_AFTER && !profile.kids &&
                    livePlaylists.any { it.enabled } && leaguesOn().isNotEmpty() && onScreen) refreshSports()
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
        if (prefs.autoUpdateCheck) viewModelScope.launch { afterQuietStart(); checkForUpdate(quiet = true) }
        if (activePlaylist() == null) { screen = Screen.SETTINGS; settingsSection = "playlists"; navMode = NavMode.COLLAPSED }
        // First time (1.6.0): with no playlists at all, a welcome screen comes first.
        if (playlists.isEmpty()) showWelcome = true
        activePlaylist()?.let { loadActive(refresh = false) }
        maybeRemindToLink()
        applyDiscoverStart()
        // 1.17.1: series being watched are checked for new episodes (twice a day), so they come back to Continue watching.
        if (android.os.Build.FINGERPRINT != "robolectric") viewModelScope.launch {
            delay(45_000)
            afterQuietStart()
            while (true) { checkSeriesForNewEpisodes(); delay(6 * 3600_000L) }
        }
    }

    // ---------------- linking for licensing (1.16.1) ----------------
    /**
     * Devices are tied to an account. One that has playlists but was never linked keeps working; a few seconds after
     * start it shows "Link this device to your account" with its code, which Not now puts off for 7 days.
     */
    var linkReminder by mutableStateOf(false); private set

    /**
     * 1.22.0 (owner, Nov 20): a device that isn't linked can't be used until it is. A full-screen "Link this device"
     * covers everything (no Not now; Back leaves the app) and nothing plays. New installs link from the welcome screen.
     * A device removed from its account, or signed out, gets it again. Off in tests, which turn it on where they check it.
     */
    var requireLink by mutableStateOf(android.os.Build.FINGERPRINT != "robolectric")
    val linkRequired: Boolean get() = requireLink && accountEmail == null && !showWelcome

    /** Shown with Link this device: stops what's playing and keeps a code on screen. */
    fun linkRequiredShown() {
        if (player.isPlaying || player.playWhenReady) { player.playWhenReady = false; player.stop() }
        if (linkCode == null && linkJob?.isActive != true) startLinking()
    }
    /** Off in tests, which turn it on where they check it. */
    var remindToLink = android.os.Build.FINGERPRINT != "robolectric"
    internal fun maybeRemindToLink(wait: Long = 4000) {
        if (!remindToLink || requireLink || account.linked || playlists.isEmpty()) return
        if (System.currentTimeMillis() - account.linkReminderAt < LINK_REMINDER_EVERY) return
        viewModelScope.launch {
            delay(wait)
            if (!account.linked && !showWelcome) { linkReminder = true; if (linkCode == null) startLinking() }
        }
    }
    fun remindLinkLater() {
        account.linkReminderAt = System.currentTimeMillis()
        linkReminder = false
        if (linkCode != null && screen != Screen.SETTINGS) cancelLinking()
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
    /**
     * Playlists that are switched on and that the profile in use may see (1.14.0: a playlist sent from avidviewer.app can
     * be limited to some profiles); the others are kept but hidden everywhere.
     */
    val shownPlaylists: List<Playlist> get() = playlists.filter { isOn(it) }
    fun isOn(p: Playlist) = p.enabled && p.visibleTo(profile)

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
            epgSources = if (epg.isBlank()) emptyList() else listOf(EpgSource(newSourceId(), "Guide address", epg.trim())),
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
                else old.epgSources + EpgSource(newSourceId(), "Guide address", epg.trim()),
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
        otherLive.remove(id); otherVod.remove(id)
        // Its channels, guide and movies can be hundreds of thousands of rows, and a download may still be writing them:
        // delete them in the background (1.15.2: doing it here froze the app until Fire OS closed it). Once more a minute
        // later, for anything a download that was still running wrote meanwhile.
        viewModelScope.launch(Dispatchers.IO) {
            repeat(2) { round ->
                if (round == 1) delay(60_000)
                if (playlists.none { it.id == id }) runCatching { store.deletePlaylistData(id); catalog.delete(id); live.delete(id) }
            }
            withContext(Dispatchers.Main) { vodVersion++ }
        }
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
            val skippedNow = withContext(Dispatchers.IO) { store.loadSkippedGroups(p.id) }
            skippedGroups = skippedGroups + (p.id to skippedNow)
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
                refreshGuideEnd()
                val stale = System.currentTimeMillis() - savedAt > guideMs()
                if (refresh || download || savedGuide.isEmpty()) refreshGuide(p)
                // Out of date but there: downloaded after the quiet start, in turn (Nov 21, 2026).
                else if (stale) { if (quietStartDone.isCompleted) refreshGuide(p) else viewModelScope.launch { afterQuietStart(); inTurn("guide of the playlist in use") { refreshGuide(p) } } }
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

    /** For tests: suggestions without a postal code lookup. */
    internal fun setSuggestionsForTests(l: List<com.avidviewer.tv.data.Suggestion>) { allSuggestions = l }

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
            val n = downloadGuide(p, guideIdsFor(p)) ?: return
            guide = withContext(Dispatchers.IO) { guideWindow(p.id, null) }
            refreshGuideEnd()
            rematchSports()
            status = "Guide loaded for $n channels."
        } catch (e: Exception) {
            status = "Couldn't load the TV guide: ${e.message}"
        }
    }

    // ---------------- stable channel keys (saved choices follow a changed list) ----------------
    /**
     * The guide times held in memory for every channel: from 3 hours ago to 36 hours ahead. Downloads keep
     * up to [GUIDE_DAYS] days in the database; later times are read for the category on screen (GUIDE-01).
     */
    private fun guideFrom() = System.currentTimeMillis() - 3 * 3600 * 1000L
    private fun guideTo() = System.currentTimeMillis() + 36 * 3600 * 1000L
    private fun guideKeepTo() = System.currentTimeMillis() + GUIDE_DAYS * 24 * 3600 * 1000L

    /** Where the saved guide of the active playlist ends (0 = no guide), at most [GUIDE_DAYS] days ahead. */
    var guideLastEnd by mutableStateOf(0L); private set
    private suspend fun refreshGuideEnd() {
        val p = activePlaylist() ?: run { guideLastEnd = 0L; return }
        val end = withContext(Dispatchers.IO) { runCatching { live.guideEnd(p.id) }.getOrDefault(0L) }
        guideLastEnd = minOf(end, guideKeepTo())
        guideLaterSpan = 0L to 0L // a new download: read the later times again
    }

    /** Programmes for times past what is held in memory, by "playlist|guide id", and the span they cover. */
    private var guideLater by mutableStateOf<Map<String, List<Program>>>(emptyMap())
    private var guideLaterSpan = 0L to 0L
    private var guideLaterIds: Set<String> = emptySet()
    private var guideLaterJob: Job? = null

    /**
     * The guide on screen shows [from]–[to] for [shown] channels: if that runs past the times held in memory,
     * read those channels' programmes for a few hours around it (in the background) so the guide can scroll days ahead.
     */
    fun guideViewing(shown: List<Channel>, from: Long, to: Long) {
        if (to <= guideTo() - 30 * 60 * 1000L) return
        val ids = shown.mapNotNull { c -> epgIdOf(c)?.let { c.key.substringBefore(':') + "|" + it } }.toHashSet()
        val (a, b) = guideLaterSpan
        if (from >= a && to <= b && guideLaterIds.containsAll(ids)) return
        val spanFrom = from - 3 * 3600 * 1000L
        val spanTo = to + 6 * 3600 * 1000L
        guideLaterJob?.cancel()
        guideLaterJob = viewModelScope.launch {
            val out = HashMap<String, List<Program>>()
            withContext(Dispatchers.IO) {
                liveReady()
                ids.groupBy({ it.substringBefore('|') }, { it.substringAfter('|') }).forEach { (pid, eids) ->
                    live.guide(pid, eids, spanFrom, spanTo).forEach { (e, list) -> out["$pid|$e"] = list }
                }
            }
            guideLater = out; guideLaterSpan = spanFrom to spanTo; guideLaterIds = ids
        }
    }

    /** A channel's programmes overlapping [from]–[to]: from memory, or (days ahead) from what [guideViewing] read. */
    fun programsAt(c: Channel?, from: Long, to: Long): List<Program> {
        if (c == null) return emptyList()
        if (to <= guideTo() - 30 * 60 * 1000L) return programs(c)
        val id = epgIdOf(c) ?: return emptyList()
        return guideLater[c.key.substringBefore(':') + "|" + id] ?: programs(c)
    }

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
        // A cancelled download (the app moving on, a newer download) isn't a failed load: nothing is recorded.
        val r = try { Result.success(downloadLiveNow(p)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
        noteLoad(p, r)
        return r.getOrThrow()
    }

    private suspend fun downloadLiveNow(p: Playlist): Int {
        withContext(Dispatchers.IO) { liveReady() }
        val wanted = with(com.avidviewer.tv.data.ChannelKeys) { prefs.referencedKeys(resume, history) }.filter { it.startsWith(p.id + ":") }
        val old = withContext(Dispatchers.IO) {
            live.channelRefs(p.id, wanted) + store.loadKeyRefs(p.id).filter { !it.isVod }
        }
        val fmt = prefs.streamFormat
        val skip = groupsToSkip(p, wanted)
        val skipped = HashSet<String>()
        val count = if (!p.isXtream && p.vodOn) {
            // One read of the file fills both (the channels' save sits inside the titles' save).
            ingestVod(p.id) { addVod -> live.replacingChannels(p.id) { addCh -> M3u.read(p, addCh, addVod) }.added }.result
        } else withContext(Dispatchers.IO) {
            // An empty answer (an expired account, a server error page) keeps the saved list.
            live.replacingChannels(p.id, keep = { _: Int, n -> n > 0 }) { add ->
                if (p.isXtream) Xtream.liveChannels(p, fmt, skip, skipped, add) else M3u.read(p, add, null)
            }.added
        }
        if (count > 0) {
            withContext(Dispatchers.IO) { store.saveSkippedGroups(p.id, skipped) }
            skippedGroups = skippedGroups + (p.id to skipped.toSet())
        }
        val m = withContext(Dispatchers.IO) {
            val map = live.remapChannels(p.id, old)
            store.saveKeyRefs(p.id, store.loadKeyRefs(p.id).filter { it.isVod })
            map
        }
        applyKeyRemap(m)
        return count
    }

    // ---------------- categories nobody sees aren't downloaded (Nov 21, 2026) ----------------

    /**
     * Categories of [p] that no profile sees (hidden everywhere, not allowed in any Kids profile) and that hold no
     * favorite, list channel, last channel or channel in history. Only for Xtream playlists, and only when they hold
     * at least 40% of the channels (or were already left out last time); otherwise one full download is cheaper.
     */
    private suspend fun groupsToSkip(p: Playlist, wanted: Collection<String>): Set<String> = withContext(Dispatchers.IO) {
        if (!p.isXtream) return@withContext emptySet()
        val pid = p.id
        val views = profiles.list.map { prof -> prof to (if (prof.id == profile.id) prefs else runCatching { store.loadPrefs(prof.id) }.getOrDefault(prefs)) }
        val kept = HashSet<String>(wanted)
        views.forEach { (_, pr) -> (pr.favorites + pr.lists.flatMap { it.keys } + listOfNotNull(pr.lastChannel)).forEach { if (it.startsWith("$pid:")) kept.add(it) } }
        fun seen(raw: String) = views.any { (prof, pr) ->
            raw !in pr.hiddenGroups && hiddenId(pid, raw) !in pr.hiddenGroups &&
                (!prof.kids || (hiddenId(pid, raw) in prof.allowedGroups && !com.avidviewer.tv.data.Kids.isAdult(raw)))
        }
        val counts = HashMap<String, Int>(); val keptIn = HashSet<String>()
        live.forEachChannel(pid) { key, _, raw, _ -> counts[raw] = (counts[raw] ?: 0) + 1; if (key in kept) keptIn.add(raw) }
        val before = store.loadSkippedGroups(pid)
        val skip = (counts.keys + before).filter { it !in keptIn && !seen(it) }.toSet()
        val total = counts.values.sum()
        val left = skip.sumOf { counts[it] ?: 0 }
        if (skip.isEmpty() || (total == 0 && before.isEmpty())) emptySet()
        else if (before.isNotEmpty() || left * 10 >= total * 4) skip else emptySet()
    }

    /** Showing a category that wasn't downloaded: download the playlist again so its channels come in. */
    private fun afterShowing(pid: String, raws: Collection<String>) {
        if (raws.none { it in skippedGroups[pid].orEmpty() }) return
        val p = playlists.firstOrNull { it.id == pid } ?: return
        status = "Loading the category's channels…"
        if (p.id == activePlaylist()?.id) loadActive(refresh = true)
        else viewModelScope.launch { runCatching { downloadLive(p) }; otherLive.remove(p.id); playlistGroups = playlistGroups - p.id }
    }

    // ---------------- each playlist's last channel load, for the website (Nov 21, 2026) ----------------

    /** Records how [p]'s download went (no channel names, no addresses) and tells the website a little later. */
    private suspend fun noteLoad(p: Playlist, r: Result<Int>) {
        val n = r.getOrNull() ?: 0
        refreshExpiry(p)
        val problem = if (n > 0) null else withContext(Dispatchers.IO) {
            val m = if (p.isXtream) Xtream.checkLogin(p) else r.exceptionOrNull()?.let { "Couldn't reach the server" }
            when {
                m == null -> if (r.isSuccess) "empty" else "other"
                m.contains("not accepted", true) || m.contains("didn't recognise", true) -> "login"
                m.contains("expired", true) -> "expired"
                m.contains("Couldn't reach", true) -> "unreachable"
                else -> "other"
            }
        }
        withContext(Dispatchers.IO) {
            synchronized(loadStatus) {
                val loads = loadStatus.optJSONObject("loads") ?: JSONObject().also { loadStatus.put("loads", it) }
                loads.put(p.id, JSONObject().put("ok", problem == null).put("channels", n).put("problem", problem ?: JSONObject.NULL).put("at", System.currentTimeMillis()))
                store.saveLoadStatus(loadStatus)
            }
        }
        reportLoadsLater()
    }

    private fun reportLoadsLater() {
        val token = account.token ?: return
        loadReportJob?.cancel()
        loadReportJob = viewModelScope.launch {
            delay(20_000)
            val arr = JSONArray()
            synchronized(loadStatus) {
                val loads = loadStatus.optJSONObject("loads") ?: JSONObject()
                playlists.forEach { p ->
                    loads.optJSONObject(p.id)?.let { o ->
                        arr.put(JSONObject().put("localId", p.id).put("name", p.name).put("ok", o.optBoolean("ok")).put("channels", o.optInt("channels"))
                            .put("problem", o.opt("problem") ?: JSONObject.NULL).put("at", o.optLong("at"))
                            .put("expiresAt", expiries[p.id] ?: JSONObject.NULL))
                    }
                }
            }
            withContext(Dispatchers.IO) { runCatching { com.avidviewer.tv.data.AccountApi.loadStatus(token, arr, accountApi) } }
        }
    }

    // ---------------- subscription end dates (Nov 21, 2026) ----------------
    private fun loadExpiries() {
        val o = synchronized(loadStatus) { loadStatus.optJSONObject("expiry") } ?: return
        expiries = o.keys().asSequence().associateWith { o.optLong(it) }.filterValues { it > 0 }
    }

    /** Asks [p]'s provider when its subscription ends (one small request) and remembers it. */
    private suspend fun refreshExpiry(p: Playlist): Result<Long?> {
        val login = Xtream.loginOf(p) ?: return Result.success(null)
        val r = withContext(Dispatchers.IO) { runCatching { Xtream.expiry(login) } }
        r.onSuccess { setExpiry(p.id, it) }
        return r
    }

    private suspend fun setExpiry(pid: String, at: Long?) {
        val before = expiries[pid]
        if (before == at) return
        expiries = if (at == null) expiries - pid else expiries + (pid to at)
        withContext(Dispatchers.IO) {
            synchronized(loadStatus) {
                val o = loadStatus.optJSONObject("expiry") ?: JSONObject().also { loadStatus.put("expiry", it) }
                if (at == null) o.remove(pid) else o.put(pid, at)
                // Renewed (a later end date): its reminders start over.
                if (at == null || (before != null && at > before)) loadStatus.optJSONObject("expiryShown")?.remove(pid)
                store.saveLoadStatus(loadStatus)
            }
        }
        checkExpiryReminders()
    }

    private fun expiryStage(at: Long, now: Long): Int? {
        if (at <= now) return null
        val days = Math.ceil((at - now) / 86_400_000.0).toInt()
        return when { days <= 2 -> 2; days <= 14 -> 14; days <= 30 -> 30; else -> null }
    }

    /** Shows the next reminder that's due and not yet dismissed (checked every 15 s and when a date changes). */
    fun checkExpiryReminders(now: Long = System.currentTimeMillis()) {
        if (expiryReminder != null) return
        val shown = synchronized(loadStatus) { loadStatus.optJSONObject("expiryShown") }
        for (p in playlists) {
            if (!p.enabled) continue
            val stage = expiryStage(expiries[p.id] ?: continue, now) ?: continue
            val last = shown?.optInt(p.id, 0) ?: 0
            if (last == 0 || stage < last) { expiryReminder = p.id to stage; return }
        }
    }

    fun dismissExpiryReminder() {
        val (pid, stage) = expiryReminder ?: return
        expiryReminder = null
        synchronized(loadStatus) {
            val o = loadStatus.optJSONObject("expiryShown") ?: JSONObject().also { loadStatus.put("expiryShown", it) }
            o.put(pid, stage)
        }
        viewModelScope.launch(Dispatchers.IO) { synchronized(loadStatus) { store.saveLoadStatus(loadStatus) } }
    }

    /**
     * True (and "Service has expired" on screen) when [pid]'s subscription has ended. The provider is asked again
     * at once: if it was renewed, the notice closes and [retry] plays what was chosen.
     */
    private fun expiredBlocks(pid: String, retry: () -> Unit): Boolean {
        val at = expiries[pid] ?: return false
        if (at > System.currentTimeMillis()) return false
        expiredNotice = pid
        val p = playlists.firstOrNull { it.id == pid } ?: return true
        viewModelScope.launch {
            val r = refreshExpiry(p)
            val e = r.getOrNull()
            if (r.isSuccess && (e == null || e > System.currentTimeMillis())) {
                if (expiredNotice == pid) expiredNotice = null
                retry()
            }
        }
        return true
    }

    // ---------------- reseller lines (Nov 21, 2026) ----------------
    /** [p]'s server and login, when it's on one of the reseller addresses: (server, username, password). */
    private fun resellerLogin(p: Playlist, hosts: List<String>): Triple<String, String, String>? {
        if (hosts.isEmpty()) return null
        val (server, u, pw) = if (p.isXtream) Triple(p.xtreamBase(), p.username, p.password) else {
            val uri = runCatching { android.net.Uri.parse(p.m3uUrl.trim()) }.getOrNull() ?: return null
            Triple(p.m3uUrl.trim(), uri.getQueryParameter("username").orEmpty(), uri.getQueryParameter("password").orEmpty())
        }
        val host = runCatching { java.net.URI(if (server.contains("://")) server else "http://$server").host?.lowercase() }.getOrNull() ?: return null
        if (host !in hosts || u.isBlank() || pw.isBlank()) return null
        return Triple(server, u, pw)
    }

    /**
     * After a check-in: confirms this device's reseller playlists with the website every 12 hours (or when asked on
     * the website), then carries out renewals the owner asked for. Logins go to the website for that one call only.
     */
    private suspend fun resellerAfterCheckIn(token: String, m: com.avidviewer.tv.data.AccountApi.Me) {
        if (m.resellerHosts.isEmpty()) return
        val now = System.currentTimeMillis()
        val checked = synchronized(loadStatus) { loadStatus.optJSONObject("reseller") ?: JSONObject().also { loadStatus.put("reseller", it) } }
        val due = JSONArray()
        playlists.forEach { p ->
            val l = resellerLogin(p, m.resellerHosts) ?: return@forEach
            if (m.checkSubscription || now - checked.optLong(p.id) > 12 * 3600 * 1000L) {
                due.put(JSONObject().put("localId", p.id).put("name", p.name).put("server", l.first).put("username", l.second).put("password", l.third))
            }
        }
        if (due.length() > 0) {
            val res = withContext(Dispatchers.IO) { runCatching { com.avidviewer.tv.data.AccountApi.resellerCheck(token, due, accountApi) }.getOrDefault(emptyMap()) }
            withContext(Dispatchers.IO) { synchronized(loadStatus) { res.keys.forEach { checked.put(it, now) }; store.saveLoadStatus(loadStatus) } }
        }
        for ((id, localId) in m.renewals) {
            val p = playlists.firstOrNull { it.id == localId } ?: continue
            val l = resellerLogin(p, m.resellerHosts) ?: continue
            val ok = withContext(Dispatchers.IO) { runCatching { com.avidviewer.tv.data.AccountApi.resellerRenew(token, id, localId, l.second, l.third, accountApi) }.getOrDefault(false) }
            com.avidviewer.tv.data.CrashLog.note(getApplication(), if (ok) "Reseller line renewed from the website" else "Reseller line renewal not done")
        }
    }

    /** Account › Troubleshooting › Reload channels now: every switched-on live playlist downloads its channels again. */
    private fun reloadAllLive() {
        if (activePlaylist()?.liveOn == true) loadActive(refresh = true)
        viewModelScope.launch {
            for (p in shownPlaylists) {
                if (!p.liveOn || p.id == activePlaylist()?.id) continue
                runCatching { downloadLive(p) }
                otherLive.remove(p.id); playlistGroups = playlistGroups - p.id
            }
        }
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
        regroupLater()
        return done
    }

    private var regroupJob: Job? = null
    /** The playlists the catalog's "each title once" marks were last worked out for. */
    private var groupedFor: List<String>? = null

    /**
     * Works out again, in the background, which copy stands for each title in Movies and Series
     * (after a download, hidden titles changing, languages filled in, or the playlists changing),
     * then refreshes the lists. Several calls close together do the work once.
     */
    fun regroupLater(delayMs: Long = 400) {
        regroupJob?.cancel()
        regroupJob = viewModelScope.launch {
            delay(delayMs)
            val pids = vodPlaylists.map { it.id }
            val ok = withContext(Dispatchers.IO) { runCatching { catalog.regroup(pids) }.isSuccess }
            if (ok) { groupedFor = pids; vodVersion++ }
        }
    }

    /** The lists are about to be read: if the playlists changed since the last regroup, do it again. */
    private fun ensureGrouped() {
        val pids = vodPlaylists.map { it.id }
        if (groupedFor != null && groupedFor != pids && regroupJob?.isActive != true) regroupLater(0)
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

    /**
     * The guide channel a channel uses: the one matched by hand, else the provider's, else (an event
     * slot without a guide id) listings read from its name, see [EventNames] (null = no guide).
     */
    fun epgIdOf(c: Channel): String? {
        val m = prefs.epgMap[c.key]
        if (m != null) return m.ifBlank { null }
        if (!c.epgId.isNullOrEmpty()) return c.epgId
        return if (EventNames.parse(c.name, c.group) != null) EventNames.ID_PREFIX + c.key else null
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
        val to = guideKeepTo()
        val directory = ArrayList<GuideChannel>()
        // This playlist's lines only: downloads of other playlists run at the same time, and copying the whole map
        // here would let one finishing later put back what another had replaced (a source's status vanished).
        val statuses = HashMap<String, String>()
        val covered = HashSet<String>()
        // DATA-05: when every source says it hasn't changed since the last download (and the same channels are
        // wanted), the saved guide is kept and nothing is downloaded again.
        val wantedSig = wanted.sorted().hashCode().toString()
        val known = sources.map { guideValidators(p.id, it.url) }
        if (known.all { it != null && it.first.any && it.second == wantedSig } && live.guideSavedAt(p.id) > 0) {
            val same = withContext(Dispatchers.IO) { sources.zip(known).all { (src, k) -> runCatching { Net.unchanged(src.url, k!!.first) }.getOrDefault(false) } }
            if (same) {
                withContext(Dispatchers.IO) { live.guideChecked(p.id) }
                sources.forEach { statuses[p.id + "|" + it.id] = "Checked ${fmtTime(System.currentTimeMillis())} · unchanged" }
                putGuideStatus(p.id, statuses)
                return known.first()!!.third
            }
        }
        val got = HashMap<String, Net.Validators>()
        // Programmes go straight into the database as each source is read; nothing is collected here.
        val done = withContext(Dispatchers.IO) {
            live.replacingGuide(p.id, keep = { ok: Boolean, _ -> ok }) { add ->
                var anyOk = false
                for (src in sources) {
                    val dir = ArrayList<Pair<String, String>>()
                    val seen = HashSet<String>()
                    runCatching {
                        Xmltv.read(src.url, wanted, from, to, src.offsetHours * 3600 * 1000L, dir, onResponse = { r -> got[src.url] = Net.Validators.of(r) }) { id, pr ->
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
        putGuideStatus(p.id, statuses)
        if (!done.committed) throw java.io.IOException("no guide source could be downloaded")
        // Remember what each source said, for asking "changed?" next time (only when every source was read).
        sources.forEach { src -> saveGuideValidators(p.id, src.url, got[src.url]?.takeIf { got.size == sources.size }, wantedSig, covered.size) }
        validatorPrefs.edit().putString("kept|${p.id}", wantedSig).apply()
        withContext(Dispatchers.IO) { store.saveGuideChannels(p.id, directory.distinctBy { it.id }) }
        guideChannelCache.remove(p.id)
        return covered.size
    }

    /** Replaces one playlist's guide source lines in [guideSourceStatus], leaving the other playlists' alone. */
    private fun putGuideStatus(pid: String, lines: Map<String, String>) {
        guideSourceStatus = guideSourceStatus.filterKeys { !it.startsWith("$pid|") } + lines
    }

    private val validatorPrefs by lazy { getApplication<Application>().getSharedPreferences("guide_validators", android.content.Context.MODE_PRIVATE) }
    /** A guide source's ETag / Last-Modified from its last download, the channels asked for then, and how many got programmes. */
    private fun guideValidators(pid: String, url: String): Triple<Net.Validators, String, Int>? =
        validatorPrefs.getString("$pid|$url", null)?.split('\n')?.takeIf { it.size == 4 }?.let { (e, m, w, n) ->
            Triple(Net.Validators(e.ifBlank { null }, m.ifBlank { null }), w, n.toIntOrNull() ?: 0)
        }
    private fun saveGuideValidators(pid: String, url: String, v: Net.Validators?, wanted: String, count: Int) {
        val e = validatorPrefs.edit()
        if (v == null || !v.any) e.remove("$pid|$url") else e.putString("$pid|$url", listOf(v.etag ?: "", v.lastModified ?: "", wanted, count.toString()).joinToString("\n"))
        e.apply()
    }

    /** The guide ids a playlist needs: its channels' own, plus any matched by hand. */
    /**
     * The guide ids whose programmes [p]'s guide keeps (Nov 18): the channels some profile on this TV can see, plus
     * any channel in a profile's favorites or lists or its last channel. Channels hidden on every profile (or in a
     * category hidden on every profile) aren't kept, so the guide is smaller and searching it and Sports are quicker.
     * Showing them again downloads the guide again ([guideWantedChanged]).
     */
    private suspend fun guideIdsFor(p: Playlist): Set<String> = withContext(Dispatchers.IO) {
        liveReady()
        val pid = p.id
        val views = profiles.list.map { prof -> prof to (if (prof.id == profile.id) prefs else runCatching { store.loadPrefs(prof.id) }.getOrDefault(prefs)) }
        val kept = HashSet<String>()
        views.forEach { (_, pr) -> (pr.favorites + pr.lists.flatMap { it.keys } + listOfNotNull(pr.lastChannel)).forEach { if (it.startsWith("$pid:")) kept.add(it) } }
        fun seen(key: String, raw: String) = views.any { (prof, pr) ->
            key !in pr.hiddenChannels && raw !in pr.hiddenGroups && hiddenId(pid, raw) !in pr.hiddenGroups &&
                (!prof.kids || (hiddenId(pid, raw) in prof.allowedGroups && !com.avidviewer.tv.data.Kids.isAdult(raw)))
        }
        val ids = HashSet<String>()
        live.forEachChannel(pid) { key, _, raw, epg ->
            if (key in kept || seen(key, raw)) {
                val e = prefs.epgMap[key]?.let { m -> m.ifBlank { null } } ?: epg
                if (!e.isNullOrBlank()) ids.add(e)
            }
        }
        ids
    }

    /**
     * Hidden channels or categories, favorites or lists changed (here or from the account): once things settle, each
     * playlist whose kept guide ids changed downloads its guide again (a different set never counts as "unchanged").
     */
    fun guideWantedChanged() {
        guideWantedJob?.cancel()
        guideWantedJob = viewModelScope.launch {
            delay(GUIDE_WANTED_SETTLE)
            for (p in livePlaylists.filter { it.enabled && it.liveOn }) {
                val ids = guideIdsFor(p)
                val sig = ids.sorted().hashCode().toString()
                // What the saved guide was downloaded for (none yet: a download now uses the new set anyway).
                if (validatorPrefs.getString("kept|${p.id}", null) == sig) continue
                trail("guide: the channels kept changed, downloading again")
                if (p.id == activePlaylist()?.id) refreshGuide(p) else runCatching { downloadGuide(p, ids) }
            }
        }
    }
    private var guideWantedJob: Job? = null

    // ---------------- #29: channels that didn't play ----------------
    /**
     * What to tell the viewer when a stream won't play, and whether the provider refused it (an HTTP 4xx answer, or
     * its server can't be found), which marks the channel in the guide for a day.
     */
    fun playErrorText(error: PlaybackException): Pair<String, Boolean> {
        val chain = generateSequence<Throwable>(error) { it.cause }.toList()
        val http = chain.firstNotNullOfOrNull { it as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException }?.responseCode
        return when {
            http != null && http in 400..499 -> "The provider refused this channel (HTTP $http). It may not be part of your subscription, or it's off the air. Try another channel." to true
            http != null -> "The provider's server had a problem with this channel (HTTP $http). Try again in a moment." to false
            chain.any { it is java.net.UnknownHostException } -> "Can't find the provider's server. Check the TV's internet connection (or DNS) and try again." to true
            chain.any { it is java.net.SocketTimeoutException || it is java.net.ConnectException } -> "The provider's server isn't answering. Try again in a moment." to false
            else -> "Can't play this channel (${error.errorCodeName}). Try another channel, or switch Stream format in Settings." to false
        }
    }

    /** Channel key → when it last failed to play; kept a day, cleared when it plays. */
    private val failedPrefs by lazy { getApplication<Application>().getSharedPreferences("failed_channels", android.content.Context.MODE_PRIVATE) }
    var failedChannels by mutableStateOf<Map<String, Long>>(runCatching {
        val now = System.currentTimeMillis()
        failedPrefs.all.mapNotNull { (k, v) -> (v as? Long)?.takeIf { now - it < FAILED_MARK_MS }?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())); private set
    internal fun noteFailedChannel(key: String, failed: Boolean) {
        if (!failed && key !in failedChannels) return
        failedChannels = if (failed) failedChannels + (key to System.currentTimeMillis()) else failedChannels - key
        failedPrefs.edit().apply { if (failed) putLong(key, System.currentTimeMillis()) else remove(key) }.apply()
    }
    /** [key] didn't play the last time it was tried, in the last day. */
    fun failedRecently(key: String): Boolean = failedChannels[key]?.let { System.currentTimeMillis() - it < FAILED_MARK_MS } == true
    internal suspend fun guideIdsForTests(pid: String): Set<String> = guideIdsFor(playlists.first { it.id == pid })
    internal fun guideKeptSigForTests(pid: String): String? = validatorPrefs.getString("kept|$pid", null)

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
        val src = EpgSource(newSourceId(), name.trim().ifBlank { "Guide source" }, url.trim(), true, offsetHours.coerceIn(-12, 12))
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
            if (!force) { delay(10_000); afterQuietStart() } // let the first screen settle (Nov 21, 2026: the quiet start)
            val dayMs = 24 * 3600 * 1000L; val guideMs = guideMs()
            val all = shownPlaylists
            for ((n, p) in all.withIndex()) inTurn("playlist ${n + 1} of ${all.size}", force) {
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
            logStartupWork()
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
            com.avidviewer.tv.data.CrashLog.note(getApplication(), "TV Guide search: ${com.avidviewer.tv.data.SearchText.words(q).size} word(s)")
            val nowMs = System.currentTimeMillis()
            val sq = com.avidviewer.tv.data.SearchText.prepare(q)
            val hidden = prefs.hiddenChannels
            val others = HashMap<String, Pair<List<Channel>, Map<String, List<Program>>>>()
            for (p in livePlaylists) {
                if (p.id == activePlaylist()?.id || !p.enabled) continue
                others[p.id] = withContext(Dispatchers.IO) { liveHits(p, q, 60, 80, nowMs) }
            }
            val active = channels
            val activeGuide = guide
            val res = withContext(Dispatchers.Default) {
                val all = (active + others.values.flatMap { it.first }).distinctBy { it.key }.filter { it.key !in hidden && channelAllowed(it) }
                fun progs(c: Channel): List<Program> {
                    val id = epgIdOf(c) ?: return emptyList()
                    val pid = c.key.substringBefore(':')
                    return (if (pid == activePlaylist()?.id) activeGuide[id] else others[pid]?.second?.get(id)).orEmpty()
                }
                val chHits = bestChannels(all, sq, 60)
                val progHits = bestPrograms(all, sq, nowMs, 80) { progs(it) }
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
     * The [limit] channels whose names match [sq] best ([SearchText.score]), in list order within each rank.
     * Stops when cancelled (a newer search started), so searches never pile up.
     */
    private suspend fun bestChannels(chs: List<Channel>, sq: com.avidviewer.tv.data.SearchText.Prepared, limit: Int): List<Channel> {
        val byRank = Array(5) { ArrayList<Channel>() }
        for ((i, c) in chs.withIndex()) {
            if (i and 255 == 0) currentCoroutineContext().ensureActive()
            val s = com.avidviewer.tv.data.SearchText.score(channelName(c), sq)
            if (s > 0 && byRank[s].size < limit) byRank[s].add(c)
        }
        return (4 downTo 1).flatMap { byRank[it] }.take(limit)
    }

    /**
     * The [limit] programmes (on now or later) that match [sq] best, soonest first within each rank.
     * Only [limit] are kept per rank while the guide is read, however many match: a short word such as
     * "the" matches much of a week's guide, and collecting all of it ran a 1 GB Fire TV out of memory (#16).
     */
    private suspend fun bestPrograms(
        chs: List<Channel>, sq: com.avidviewer.tv.data.SearchText.Prepared, nowMs: Long, limit: Int, progs: (Channel) -> List<Program>,
    ): List<Pair<Channel, Program>> {
        val cmp = compareByDescending<Pair<Channel, Program>> { it.second.start } // the head is the latest: dropped first
        val byRank = Array(5) { java.util.PriorityQueue(limit + 1, cmp) }
        var n = 0
        // A programme title repeats across a week: score each title once.
        val seen = HashMap<String, Int>()
        for (c in chs) {
            for (pr in progs(c)) {
                if (n++ and 1023 == 0) currentCoroutineContext().ensureActive()
                if (pr.end <= nowMs) continue
                val text = pr.title + " " + pr.subtitle.orEmpty()
                val s = seen[text] ?: com.avidviewer.tv.data.SearchText.score(text, sq).also { if (seen.size < 20_000) seen[text] = it }
                if (s <= 0) continue
                val q = byRank[s]
                if (q.size < limit) q.add(c to pr)
                else if (pr.start < q.peek()!!.second.start) { q.poll(); q.add(c to pr) }
            }
        }
        return (4 downTo 1).flatMap { r -> byRank[r].sortedBy { it.second.start } }.take(limit)
    }

    /**
     * Another playlist's channels matching [q] by name (own or renamed) plus the channels of programmes
     * matching it, with those channels' guide — looked up in the database, nothing else loaded.
     */
    private fun liveHits(p: Playlist, q: String, maxChannels: Int, maxPrograms: Int, nowMs: Long): Pair<List<Channel>, Map<String, List<Program>>> {
        liveReady()
        val sq = com.avidviewer.tv.data.SearchText.prepare(q)
        val renamed = prefs.channelNames.filter { (k, v) -> k.startsWith(p.id + ":") && com.avidviewer.tv.data.SearchText.near(v, sq) }.keys
        // Every word first, then near matches (a typo, or a word left out); the screen ranks them.
        val byName = live.searchChannels(p.id, q, maxChannels) + live.nearChannels(p.id, q, maxChannels) + live.channelsByKeys(renamed)
        val progs = live.searchPrograms(p.id, q, nowMs, maxPrograms * 2) + live.nearPrograms(p.id, q, nowMs, maxPrograms)
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
                launchPreview(currentKey); guideFocusTick++
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
        launchPreview(key); guideFocusTick++
    }

    /**
     * The preview at launch. On a small device ([lightStart]) it waits [DeviceClass.PREVIEW_DELAY_MS] so the guide and
     * its logos are on screen before the video decoder starts, and doesn't start at all if the viewer has already
     * picked something (or is on another screen) by then.
     */
    internal fun launchPreview(key: String?) {
        if (!lightStart) { preview(key); return }
        val pressedBefore = lastKeyAt
        com.avidviewer.tv.data.CrashLog.note(getApplication(), "Light start-up: preview in ${com.avidviewer.tv.data.DeviceClass.PREVIEW_DELAY_MS / 1000} s")
        viewModelScope.launch {
            delay(com.avidviewer.tv.data.DeviceClass.PREVIEW_DELAY_MS)
            if (lastKeyAt != pressedBefore || currentKey != key || screen != Screen.GUIDE || player.mediaItemCount > 0) return@launch
            preview(key)
        }
    }

    // ---------------- prefs ----------------
    fun updatePrefs(f: (Prefs) -> Prefs) {
        val before = prefs
        prefs = f(prefs)
        store.savePrefs(prefs)
        if (before.hiddenGroups != prefs.hiddenGroups || before.hiddenChannels != prefs.hiddenChannels ||
            before.favorites != prefs.favorites || before.lists != prefs.lists) guideWantedChanged()
        if (before.favorites != prefs.favorites || before.lists != prefs.lists) reorderSportChannels()
        if (before.vodHiddenCats !== prefs.vodHiddenCats || before.vodHiddenTitles !== prefs.vodHiddenTitles) {
            // Right away, so the lists on screen (and the next one read) are already correct.
            runCatching { catalog.applyHiddenChange(before.vodHiddenCats, prefs.vodHiddenCats, before.vodHiddenTitles, prefs.vodHiddenTitles) }
            vodVersion++
            regroupLater() // a hidden copy may have stood for its title
        }
        if (!applyingAccount && account.token != null) {
            if (before.favorites != prefs.favorites || before.lists != prefs.lists) runCatching {
                account.listsChanged = org.json.JSONObject(account.listsChanged).put(profile.id, System.currentTimeMillis()).toString()
            }
            if (before.favSort != prefs.favSort) runCatching {
                account.listsChanged = org.json.JSONObject(account.listsChanged).put(profile.id, System.currentTimeMillis()).toString()
            }
            if (before.groupOrder != prefs.groupOrder || before.groupNames != prefs.groupNames || before.channelOrder != prefs.channelOrder || before.channelNames != prefs.channelNames || before.channelSort != prefs.channelSort)
                account.orderChangedAt = System.currentTimeMillis()
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
        // Categories left out of the download (hidden everywhere) are still listed, so they can be shown again.
        if (includeHidden) skippedGroups[activePlaylist()?.id].orEmpty().forEach { if (it !in counts) counts[it] = 0 }
        val inOrder = prefs.groupOrder.toHashSet()
        val ordered = prefs.groupOrder.filter { it in counts } + counts.keys.filterNot { it in inOrder }
        val pid = activePlaylist()?.id ?: ""
        return ordered.map { GroupInfo(it, groupName(it), counts[it] ?: 0, isGroupHidden(pid, it)) }
            .filter { includeHidden || !it.hidden }
    }

    // ---------------- hiding (List Editor) ----------------
    /** Hidden categories are stored per playlist as "playlistId\u0001name"; a bare name (older versions) hides it everywhere. */
    private fun hiddenId(pid: String, raw: String) = pid + "\u0001" + raw

    fun isGroupHidden(pid: String, raw: String) = raw in prefs.hiddenGroups || hiddenId(pid, raw) in prefs.hiddenGroups ||
        (kidsMode && raw != FAV_GROUP && !raw.startsWith(LIST_PREFIX) && !kidsAllowsGroup(pid, raw))

    /** Shows or hides many categories of one playlist at once. */
    fun setGroupsHidden(pid: String, raws: Collection<String>, hidden: Boolean) {
        updatePrefs { p ->
            val ids = raws.map { hiddenId(pid, it) }
            p.copy(hiddenGroups = if (hidden) p.hiddenGroups + ids else p.hiddenGroups - ids.toSet() - raws.toSet())
        }
        afterHidingChanged(pid)
        if (!hidden) afterShowing(pid, raws)
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
        val skippedNow = withContext(Dispatchers.IO) { store.loadSkippedGroups(p.id) }
        skippedGroups = skippedGroups + (p.id to skippedNow)
        return withContext(Dispatchers.IO) { liveReady(); live.channels(p.id) }
    }

    /** Categories of [chs] in the user's order: (name, channel count). */
    fun editorGroups(chs: List<Channel>, pid: String? = null): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        chs.forEach { counts[it.group] = (counts[it.group] ?: 0) + 1 }
        if (pid != null) skippedGroups[pid].orEmpty().forEach { if (it !in counts) counts[it] = 0 }
        val inOrder = prefs.groupOrder.toHashSet()
        val ordered = prefs.groupOrder.filter { it in counts } + counts.keys.filterNot { it in inOrder }
        return ordered.map { it to (counts[it] ?: 0) }
    }

    /** Channels of one category in the user's order, hidden ones included. */
    fun editorChannelsIn(chs: List<Channel>, raw: String): List<Channel> = sortedIn(chs.filter { it.group == raw }, raw)

    // ---------------- sorting (1.18.0, design boards 25–27, note 20) ----------------
    /** How [pid]'s channels are sorted: the setting, else Manual when it has an order of its own, else the provider's. */
    fun channelSortOf(pid: String): String = prefs.channelSort[pid]
        ?: if (prefs.channelOrder.values.any { l -> l.any { it.startsWith("$pid:") } }) SORT_MANUAL else SORT_PROVIDER

    /** A–Z by the name exactly as shown (renames count, nothing skipped), case ignored. */
    private val byShownName: Comparator<Channel> = compareBy<Channel, String>(String.CASE_INSENSITIVE_ORDER) { channelName(it) }.thenBy { channelName(it) }

    /** One category's channels ([list], in the provider's order) as its playlist is sorted. */
    private fun sortedIn(list: List<Channel>, group: String): List<Channel> {
        val pid = list.firstOrNull()?.key?.substringBefore(':') ?: return list
        return when (channelSortOf(pid)) {
            SORT_AZ -> list.sortedWith(byShownName)
            SORT_PROVIDER -> list
            else -> { val order = prefs.channelOrder[group] ?: return list; val byKey = list.associateBy { it.key }; val inOrder = order.toHashSet(); order.mapNotNull { byKey[it] } + list.filterNot { it.key in inOrder } }
        }
    }

    /** Sets how [pid]'s channels are sorted, for every category, profile and device. Manual starts from the order shown now. */
    fun setChannelSort(pid: String, mode: String) {
        if (channelSortOf(pid) == mode) return
        updatePrefs { p ->
            val order = if (mode == SORT_MANUAL) p.channelOrder + manualSnapshot(pid) else p.channelOrder
            p.copy(channelSort = p.channelSort + (pid to mode), channelOrder = order)
        }
        status = "Channels in ${playlists.firstOrNull { it.id == pid }?.name ?: "this playlist"}: " + sortLabel(mode) + "."
    }

    fun sortLabel(mode: String) = when (mode) { SORT_AZ -> "A–Z"; SORT_PROVIDER -> "Provider’s order"; else -> "Manual" }

    /** Every category of [pid] (when it's the playlist in memory) in the order shown now, to start Manual from. */
    private fun manualSnapshot(pid: String): Map<String, List<String>> {
        if (channels.firstOrNull()?.key?.substringBefore(':') != pid) return emptyMap()
        return channels.groupBy { it.group }.mapValues { (g, l) -> sortedIn(l, g).map { it.key } }
    }

    /** ★ Favorites or a list sorted "az" or "manual" (its own order). Lists belong to the profile. */
    fun listSortOf(group: String): String = if (group == FAV_GROUP) prefs.favSort else userList(group)?.sort ?: SORT_MANUAL

    fun setListSort(group: String, mode: String) {
        if (listSortOf(group) == mode) return
        // Manual starts from the A–Z order on screen.
        val shown = if (mode == SORT_MANUAL) listKeys(group).let { ks -> sortKeysAz(ks) } else null
        updatePrefs { p ->
            if (group == FAV_GROUP) p.copy(favSort = mode, favorites = shown ?: p.favorites)
            else p.copy(lists = p.lists.map { if (LIST_PREFIX + it.id == group) it.copy(sort = mode, keys = shown ?: it.keys) else it })
        }
        status = (if (group == FAV_GROUP) "Favorites" else userList(group)?.name ?: "List") + ": " + sortLabel(mode) + "."
    }

    /** [keys] in A–Z order by the channels' shown names (channels not found keep their place at the end). */
    private fun sortKeysAz(keys: List<String>): List<String> {
        val found = keys.mapNotNull { k -> channel(k) }
        return found.sortedWith(byShownName).map { it.key } + keys.filter { k -> found.none { it.key == k } }
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
        if (isListGroup(group)) return listKeys(group).mapNotNull { channel(it) }.let { l -> if (listSortOf(group) == SORT_AZ) l.sortedWith(byShownName) else l }
        // #20: this used to go through every channel of the playlist for each category, so numbering the channels
        // (every category in turn) after any settings change went through the whole list once per category on the
        // screen's thread: millions of checks with a big playlist, and the guide stopped answering for seconds.
        val inGroup = channelsByGroupMemo.get(channels) { channels.groupBy { it.group } }[group] ?: return emptyList()
        val hidden = prefs.hiddenChannels
        return sortedIn(inGroup.filter { it.key !in hidden && channelAllowed(it) }, group)
    }

    /** Looks in the active playlist first, then in other playlists' saved channels (for combined Favorites). */
    fun channel(key: String?): Channel? = anyChannel(key)?.takeIf { channelAllowed(it) }

    /** [key]'s channel whether or not the profile in use may watch it. */
    private fun anyChannel(key: String?): Channel? {
        if (key == null) return null
        channelIndexMemo.get(channels) { channels.associateBy { it.key } }[key]?.let { return it }
        val pid = key.substringBefore(':')
        if (livePlaylists.none { it.id == pid }) return null
        return otherLive[pid]?.first?.firstOrNull { it.key == key } ?: searchLive[pid]?.first?.firstOrNull { it.key == key } ?: sportsChannels[key] ?: liveCardChannels[key]
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
        ensureManual(g, key)
        val order = channelsIn(g).map { it.key }.toMutableList()
        if (!order.remove(key)) return
        order.add(index.coerceIn(0, order.size), key)
        when {
            g == FAV_GROUP -> updatePrefs { p -> p.copy(favorites = order + p.favorites.filter { it !in order }) }
            g.startsWith(LIST_PREFIX) -> updatePrefs { p -> p.copy(lists = p.lists.map { if (LIST_PREFIX + it.id == g) it.copy(keys = order + it.keys.filter { k -> k !in order }) else it }) }
            else -> updatePrefs { it.copy(channelOrder = it.channelOrder + (g to order)) }
        }
    }

    /** Re-ordering in a list or playlist sorted A–Z (or the provider's order) switches it to Manual, from the order shown. */
    private fun ensureManual(group: String, key: String) {
        if (isListGroup(group)) { if (listSortOf(group) != SORT_MANUAL) setListSort(group, SORT_MANUAL) }
        else { val pid = key.substringBefore(':'); if (channelSortOf(pid) != SORT_MANUAL) setChannelSort(pid, SORT_MANUAL) }
    }

    fun moveChannelBy(key: String, delta: Int) {
        val i = channelsIn(selectedGroup).indexOfFirst { it.key == key }
        if (i >= 0) moveChannelTo(key, i + delta)
    }

    fun moveChannel(key: String, dir: Int) {
        val c = channel(key) ?: return
        ensureManual(c.group, key)
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

    fun toggleHidden(raw: String) {
        val pid = activePlaylist()?.id ?: ""
        val wasHidden = raw in prefs.hiddenGroups || hiddenId(pid, raw) in prefs.hiddenGroups
        updatePrefs {
            val id = hiddenId(pid, raw)
            it.copy(hiddenGroups = if (raw in it.hiddenGroups || id in it.hiddenGroups) it.hiddenGroups - raw - id else it.hiddenGroups + id)
        }
        if (wasHidden) afterShowing(pid, listOf(raw))
    }

    fun isFavorite(key: String?) = key != null && key in prefs.favorites

    // ---------------- TV Guide options menus (Menu key) ----------------
    var channelMenu by mutableStateOf<String?>(null)
    var groupMenu by mutableStateOf<String?>(null)

    fun moveFavorite(key: String, dir: Int) {
        ensureManual(FAV_GROUP, key)
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
        leavingGuide(key, group) {
            updatePrefs { p -> p.copy(lists = p.lists.map { if (LIST_PREFIX + it.id == group) it.copy(keys = if (key in it.keys) it.keys - key else it.keys + key) else it }) }
        }
    }

    /**
     * #19: [change] may take [key] out of the TV Guide category on screen ([group]: removed from this list, or
     * hidden). The row that had the highlight is then gone and the highlight went to the top-left of the
     * screen; instead it goes to the channel that took its place (the next one, or the one before when it was
     * the last), or to the category in the category list when the list is now empty.
     */
    private fun leavingGuide(key: String, group: String?, change: () -> Unit) {
        val shown = screen == Screen.GUIDE && group != null && group == selectedGroup && guideQuery.isBlank() &&
            channelsIn(group).any { it.key == key }
        change()
        if (shown && channelsIn(group).none { it.key == key }) focusGuideList()
    }

    /** Puts the highlight back on the guide's channels where it was (or its neighbour), or on the category when it's empty. */
    fun focusGuideList() {
        val g = selectedGroup ?: return
        if (channelsIn(g).isEmpty()) navFocusKey = g
        else { guideFocusMode = GuideFocus.KEEP; guideFocusTick++ }
    }

    /**
     * #18: a local suggestion whose ＋ Add to List (or ✕) was pressed, and the suggestion next to it: once the
     * picker closes, the highlight goes back to it if it's still suggested, else to that neighbour.
     */
    var suggestionReturn by mutableStateOf<Pair<String, String?>?>(null)
    /** When the suggestions row last took the highlight back after the picker or ✕ (BUG-033). */
    var suggestionLandedAt = 0L
    /** Whether the highlight is in the Favorites suggestions row (the guide doesn't take it away on its own). */
    var suggestionsFocused = false

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
        ensureManual(group, key)
        val lst = userList(group) ?: return
        val l = lst.keys.toMutableList(); val i = l.indexOf(key); val j = i + dir
        if (i < 0 || j !in l.indices) return
        l[i] = l[j].also { l[j] = l[i] }
        updatePrefs { p -> p.copy(lists = p.lists.map { if (it.id == lst.id) it.copy(keys = l) else it }) }
    }

    fun hideChannel(key: String) = leavingGuide(key, selectedGroup) { updatePrefs { it.copy(hiddenChannels = it.hiddenChannels + key) } }
    fun showChannel(key: String) = updatePrefs { it.copy(hiddenChannels = it.hiddenChannels - key) }

    fun toggleFavorite(key: String?) {
        key ?: return
        viewModelScope.launch { ensureFavoriteData() }
        leavingGuide(key, FAV_GROUP) { updatePrefs { it.copy(favorites = if (key in it.favorites) it.favorites - key else it.favorites + key) } }
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
        if (ctx == PlayContext.SPORTS) {
            sportsPlayLeague = sportLeague
            sportsPlayGame = (sportGames.firstOrNull { g -> g.game.id == selectedGame && g.channels.any { it.key == key } }
                ?: sportGames.firstOrNull { g -> g.channels.any { it.key == key } })?.game?.id ?: selectedGame
        }
        if (ctx == PlayContext.GROUP && !isListGroup(selectedGroup)) selectedGroup = c.group
        if (screen != Screen.PLAYER) lastScreen = screen
        if (currentKey != key || player.mediaItemCount == 0 || playerError != null) start(c)
        screen = Screen.PLAYER
        flashOsd(contextLabel())
    }

    // ---------------- playlists and profiles from avidviewer.app (1.14.0, profiles 1.15.0) ----------------
    private var reported = ""
    private var accountSyncBusy = false
    private var accountSyncAgain = false

    /**
     * Reports this device's key and profiles (when they changed; the first time the account takes the profiles in),
     * applies the account's profiles for this device, then adds, changes or removes the playlists it sent, and tells the
     * account how each went. Offline or unlinked: nothing happens until the next minute.
     */
    fun syncAccountPlaylists() {
        val token = account.token ?: return
        // One at a time; a request during a sync runs right after it, so nothing asked for is lost.
        if (accountSyncBusy) { accountSyncAgain = true; return }
        accountSyncBusy = true; accountSyncAgain = false
        viewModelScope.launch {
            try {
                val key = withContext(Dispatchers.IO) { account.deviceKey() } ?: return@launch
                val prof = reportProfiles()
                val sig = key.publicB64 + prof + profiles.managed
                if (sig != reported) {
                    val vis = if (profiles.list.any { it.remoteId.isNotEmpty() }) null else withContext(Dispatchers.IO) { runCatching { reportVisibility() }.getOrNull() }
                    val map = withContext(Dispatchers.IO) { com.avidviewer.tv.data.AccountApi.report2(token, key.publicB64, prof, vis, accountApi) }
                    if (!profiles.managed || map.isNotEmpty()) {
                        // The account has these profiles now: remember which account profile each one is.
                        saveProfiles(profiles.copy(managed = true, list = profiles.list.map { p -> map[p.id]?.takeIf { p.remoteId.isEmpty() }?.let { p.copy(remoteId = it) } ?: p }))
                        account.profilesHash = ""
                    }
                    reported = key.publicB64 + reportProfiles() + profiles.managed
                }
                // Until the one-time repair has run, ask for the profiles even if they haven't changed.
                val box = withContext(Dispatchers.IO) { com.avidviewer.tv.data.AccountApi.inbox2(token, account.profilesHash.ifEmpty { null }?.takeIf { account.hidesRepaired }, accountApi) }
                box.profiles?.let { list ->
                    account.profilesJson = list.toString()
                    applyAccountProfiles(list)
                    account.profilesHash = box.profilesHash
                }
                var changed = false
                for (item in box.items) {
                    val problem = runCatching { applyAccountPlaylist(item, key) }.getOrElse { it.message ?: "Something went wrong on the device." }
                    trail("account playlist ${item.action}: " + (problem?.let { "failed" } ?: "done"))
                    if (problem == null) changed = true
                    // Say so on the TV too (the website lists it under Needs your attention).
                    else if (item.action != "remove") status = "Couldn't add ${item.name.ifBlank { "a playlist" }} from your account: ${problem.trimEnd('.')}. Details at avidviewer.app/playlists."
                    runCatching { withContext(Dispatchers.IO) { com.avidviewer.tv.data.AccountApi.ack(token, item, problem, accountApi) } }
                }
                // A playlist that just arrived: map the profiles' hidden categories onto it.
                if (changed && box.profiles == null && account.profilesJson.isNotEmpty()) {
                    runCatching { applyAccountProfiles(org.json.JSONArray(account.profilesJson)) }
                }
                runCatching { mergeDuplicateAccountPlaylists() }
                if (box.managed) runCatching { sendDevicePlaylists(token, box.accountKey) }
                if (box.managed) runCatching { sendCatalogs(token) }
                if (box.managed) runCatching { syncHistory(token) }.onFailure { e ->
                    trail("history sync: failed")
                    val why = e.message.orEmpty()
                    if (why.isNotEmpty() && why != historyProblem) { historyProblem = why; status = "Couldn't share where you stopped with your account: $why" }
                }
                if (box.managed) runCatching { syncLists(token) }.onFailure { e ->
                    trail("lists sync: failed")
                    val why = e.message.orEmpty()
                    if (why.isNotEmpty() && why != listsProblem) { listsProblem = why; status = "Couldn't sync favorites and lists with your account: $why" }
                }
            } catch (_: Exception) {
                // Offline, or the site is busy: try again at the next check.
            } finally {
                accountSyncBusy = false
                if (accountSyncAgain) { accountSyncAgain = false; syncAccountPlaylists() }
            }
        }
    }

    /** This device's profiles as the account sees them (the PIN only as its salted hash). */
    private fun reportProfiles(): org.json.JSONArray = org.json.JSONArray().apply {
        profiles.list.forEach { p ->
            put(org.json.JSONObject().put("id", p.id).put("name", p.name).put("kids", p.kids).put("parent", p.isParent)
                .put("color", p.color).put("pin", p.pinHash))
        }
    }

    /**
     * For the first report: what each profile hid (or, Kids, may see) in the playlists that came from the account, so the
     * account starts from what this device already does. Keyed by local profile id, then account playlist id.
     */
    private fun reportVisibility(): org.json.JSONObject {
        val acct = playlists.filter { it.remoteId.isNotEmpty() }.associateBy { it.id }
        val out = org.json.JSONObject()
        if (acct.isEmpty()) return out
        for (p in profiles.list) {
            val pr = if (p.id == profile.id) prefs else store.loadPrefs(p.id)
            val per = HashMap<String, org.json.JSONObject>()
            fun vis(pid: String) = per.getOrPut(acct.getValue(pid).remoteId) {
                org.json.JSONObject().put("hidden", org.json.JSONObject().put("live", org.json.JSONArray()).put("movie", org.json.JSONArray()).put("series", org.json.JSONArray()))
                    .put("allowed", org.json.JSONObject().put("live", org.json.JSONArray()).put("movie", org.json.JSONArray()).put("series", org.json.JSONArray()))
                    .put("channels", org.json.JSONArray())
            }
            fun liveId(id: String) = id.split('\u0001', limit = 2).takeIf { it.size == 2 && it[0] in acct }
            fun vodKey(k: String) = k.split('|', limit = 3).takeIf { it.size == 3 && it[1] in acct }
            for (g in pr.hiddenGroups) liveId(g)?.let { (pid, raw) -> vis(pid).getJSONObject("hidden").getJSONArray("live").put(raw) }
            for (c in pr.hiddenChannels) c.substringBefore(':').takeIf { it in acct && c.contains(':') }?.let { pid -> vis(pid).getJSONArray("channels").put(c.substringAfter(':')) }
            for (v in pr.vodHiddenCats) vodKey(v)?.let { (k, pid, cat) -> vis(pid).getJSONObject("hidden").getJSONArray(k).put(cat) }
            if (p.kids) {
                for (g in p.allowedGroups) liveId(g)?.let { (pid, raw) -> vis(pid).getJSONObject("allowed").getJSONArray("live").put(raw) }
                for (v in p.allowedVodCats) vodKey(v)?.let { (k, pid, cat) -> vis(pid).getJSONObject("allowed").getJSONArray(k).put(cat) }
            }
            if (per.isNotEmpty()) out.put(p.id, org.json.JSONObject().apply { per.forEach { (k, v) -> put(k, v) } })
        }
        return out
    }

    /**
     * The account's profiles for this device (1.15.0), in the account's order: they replace the device's own. A profile
     * the device already has keeps its favorites, lists and history; one taken off the device just isn't shown (its data
     * stays, in case it comes back). With none, the device goes back to one profile of its own and shows no profiles.
     */
    internal fun applyAccountProfiles(list: org.json.JSONArray) {
        val cur = profiles.list
        val acctPl = playlists.filter { it.remoteId.isNotEmpty() }
        val byRemote = acctPl.associateBy { it.remoteId }
        val acctLocal = acctPl.map { it.id }.toSet()
        if (list.length() == 0) {
            if (cur.none { it.remoteId.isNotEmpty() }) { if (!profiles.managed) saveProfiles(profiles.copy(managed = true)); return }
            val me = (cur.firstOrNull { it.isOwner } ?: Profile(Profile.OWNER, "Me", theme = com.avidviewer.tv.data.THEME_DEFAULT))
            clearAccountHides(me)
            val single = me.copy(remoteId = "", kids = false, parent = false, pinHash = "", acctHidden = emptySet(), allowedGroups = emptySet(), allowedVodCats = emptySet())
            val wasActive = profile.id
            saveProfiles(ProfileSet(listOf(single), single.id, profiles.startMode, managed = true))
            if (wasActive != single.id) openProfile(single.id) else { favDataTick++; applyProfileVodLimits() }
            return
        }
        // 1.17.2: once, every profile's hidden items in the account's playlists become exactly the account's (1.17.0–1.17.1
        // could write one profile's settings onto another during a sync; the account has each profile's own).
        val repair = !account.hidesRepaired
        val used = HashSet<String>()
        val out = ArrayList<Profile>()
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            val rid = a.getString("id")
            val old = cur.firstOrNull { it.remoteId == rid }
                ?: cur.firstOrNull { it.isOwner && it.remoteId.isEmpty() && it.id !in used }
                ?: cur.firstOrNull { it.id == rid }
            val kids = a.optBoolean("kids")
            var p = (old ?: Profile(rid, "", theme = com.avidviewer.tv.data.THEME_DEFAULT)).copy(
                remoteId = rid, name = a.optString("name", "Profile"), kids = kids, parent = !kids && a.optBoolean("parent"),
                color = a.optInt("color", 0).coerceIn(0, com.avidviewer.tv.data.PROFILE_COLORS.lastIndex), pinHash = if (kids) "" else a.optString("pin"),
            )
            used += p.id
            // What it may see in each account playlist that's on this device.
            val vis = a.optJSONObject("visibility") ?: org.json.JSONObject()
            val hidden = HashSet<String>()
            val allowLive = HashSet<String>(); val allowVod = HashSet<String>()
            for (rk in vis.keys()) {
                val pl = byRemote[rk] ?: continue
                val v = vis.getJSONObject(rk)
                val h = v.optJSONObject("hidden"); val al = v.optJSONObject("allowed")
                fun arr(o: org.json.JSONObject?, k: String) = o?.optJSONArray(k)?.let { x -> List(x.length()) { x.getString(it) } } ?: emptyList()
                arr(h, "live").forEach { hidden += "g:" + hiddenId(pl.id, it) }
                listOf("movie", "series").forEach { k -> arr(h, k).forEach { hidden += "v:" + vodCatKey(k, pl.id, it) } }
                v.optJSONArray("channels")?.let { x -> for (j in 0 until x.length()) hidden += "c:" + pl.id + ":" + x.getString(j) }
                if (kids) {
                    arr(al, "live").forEach { allowLive += hiddenId(pl.id, it) }
                    listOf("movie", "series").forEach { k -> arr(al, k).forEach { allowVod += vodCatKey(k, pl.id, it) } }
                }
            }
            if (kids) {
                // Categories of the device's own playlists stay as they were; the account's playlists follow the account.
                p = p.copy(allowedGroups = p.allowedGroups.filterNot { it.substringBefore('\u0001') in acctLocal }.toSet() + allowLive,
                    allowedVodCats = p.allowedVodCats.filterNot { it.split('|').getOrNull(1) in acctLocal }.toSet() + allowVod)
            }
            if (repair || p.acctHidden != hidden) { putAccountHides(p, p.acctHidden, hidden, if (repair) acctLocal else null); p = p.copy(acctHidden = hidden) }
            out += p
        }
        val before = profile
        val active = out.firstOrNull { it.id == profiles.active }?.id
        val set = profiles.copy(list = out, active = active ?: out.first().id, managed = true)
        saveProfiles(set)
        if (repair) { account.hidesRepaired = true; trail("hidden items matched to the account") }
        // Another profile's hidden items (or a Kids profile's categories) may have changed what the guide keeps.
        guideWantedChanged()
        val now = profile
        when {
            active == null -> { splashProfile = null; val l = set.launch(); openProfile(l.id); if (l.choose) askWhoIsWatching() }
            before.kids != now.kids || before.allowedVodCats != now.allowedVodCats || before.allowedGroups != now.allowedGroups -> { applyProfileVodLimits(); favDataTick++ }
            else -> favDataTick++
        }
    }

    /**
     * Replaces what the account hid before ([old]) with what it hides now ([new]) in [p]'s own hidden lists. With
     * [exact] (the account's playlists on this device), everything hidden in those playlists is replaced, not just the
     * account's earlier hides.
     */
    private fun putAccountHides(p: Profile, old: Set<String>, new: Set<String>, exact: Set<String>? = null) {
        fun part(set: Set<String>, k: String) = set.filter { it.startsWith(k) }.map { it.substring(2) }.toSet()
        fun inAcct(pid: String?) = exact != null && pid != null && pid in exact
        val f: (Prefs) -> Prefs = { pr ->
            pr.copy(hiddenGroups = pr.hiddenGroups.filterNot { it.contains('\u0001') && inAcct(it.substringBefore('\u0001')) }.toSet() - part(old, "g:") + part(new, "g:"),
                hiddenChannels = pr.hiddenChannels.filterNot { it.contains(':') && inAcct(it.substringBefore(':')) }.toSet() - part(old, "c:") + part(new, "c:"),
                vodHiddenCats = pr.vodHiddenCats.filterNot { inAcct(it.split('|').getOrNull(1)) }.toSet() - part(old, "v:") + part(new, "v:"))
        }
        if (p.id == profile.id) updatePrefs(f)
        else store.savePersonal(p.id, com.avidviewer.tv.data.personalOf(f(store.loadPrefs(p.id))))
    }
    private fun clearAccountHides(p: Profile) { if (p.acctHidden.isNotEmpty()) putAccountHides(p, p.acctHidden, emptySet()) }

    /**
     * Sends each account playlist's categories and channel names to the account (for choosing what profiles see on the
     * website) when they changed, or once a day. Names and counts only; never what's watched.
     */
    private suspend fun sendCatalogs(token: String) {
        val sent = runCatching { org.json.JSONObject(account.catalogSent) }.getOrDefault(org.json.JSONObject())
        val nowMs = System.currentTimeMillis()
        for (pl in playlists.filter { it.remoteId.isNotEmpty() && it.enabled }) {
            val (was, at) = sent.optString(pl.remoteId).split('@').let { it.getOrElse(0) { "" } to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
            // BUG-057: the catalog (every channel name, up to ~1.8 MB) was rebuilt and turned into text on the screen's
            // thread at every account check (each minute), long enough on a Fire TV for "not responding". Now it's only
            // rebuilt when the playlist's channels or movie/series catalog changed, or once a day, and always off it.
            val stamp = withContext(Dispatchers.IO) {
                runCatching { live.channelsSavedAt(pl.id) }.getOrDefault(0L).toString() + "/" + runCatching { catalog.savedAt(pl.id) }.getOrDefault(0L) + "/" + pl.liveOn + pl.vodOn
            }
            if (catalogStamp[pl.id] == stamp && nowMs - at < 24 * 60 * 60_000L) continue
            catalogBuilds++
            val built = withContext(Dispatchers.IO) { catalogOf(pl)?.let { b -> b to b.toString().hashCode().toString(16) } }
            catalogStamp[pl.id] = stamp
            val (body, sig) = built ?: continue
            if (was == sig && nowMs - at < 24 * 60 * 60_000L) continue
            withContext(Dispatchers.IO) { com.avidviewer.tv.data.AccountApi.catalog(token, body, accountApi) }
            sent.put(pl.remoteId, "$sig@$nowMs")
            account.catalogSent = sent.toString()
        }
    }
    /** What each playlist's catalog was last built from (channels saved at, catalog version), so it isn't rebuilt each minute. */
    private val catalogStamp = HashMap<String, String>()
    /** How many times a catalog was built (tests: not at every account check). */
    internal var catalogBuilds = 0; private set

    /**
     * Tells the account every playlist on this device (1.15.1), when something changed: names, types and switches, and,
     * once the account has its own key, each login locked so only the owner's browser can open it (to add a TV's
     * playlist to the account, or keep a login the account doesn't have yet, without typing it).
     */
    private suspend fun sendDevicePlaylists(token: String, accountKey: String?) {
        val logins = playlists.joinToString("|") { listOf(it.id, it.server, it.username, it.password, it.m3uUrl, it.epgUrl).joinToString("\u0001") }
        val sig = (playlists.joinToString("|") { listOf(it.id, it.name, it.type, it.enabled, it.liveOn, it.vodOn, it.remoteId).joinToString(",") } +
            "#" + (accountKey ?: "") + "#" + logins.hashCode()).hashCode().toString(16)
        if (sig == account.playlistsSent) return
        val list = withContext(Dispatchers.Default) {
            org.json.JSONArray().apply {
                playlists.forEach { p ->
                    val o = org.json.JSONObject().put("id", p.id).put("name", p.name).put("type", p.type).put("enabled", p.enabled)
                        .put("live", p.liveOn).put("vod", p.vodOn).put("remoteId", p.remoteId)
                    if (accountKey != null) runCatching {
                        val epg = p.epgSources.firstOrNull { it.id == ACCOUNT_GUIDE }?.url ?: p.epgSources.firstOrNull()?.url ?: p.epgUrl
                        val login = org.json.JSONObject().put("name", p.name).put("type", p.type).put("server", p.server)
                            .put("username", p.username).put("password", p.password).put("m3uUrl", p.m3uUrl).put("epgUrl", epg)
                        o.put("sealed", com.avidviewer.tv.data.DeviceKey.seal(accountKey, login))
                    }
                    put(o)
                }
            }
        }
        withContext(Dispatchers.IO) { com.avidviewer.tv.data.AccountApi.devicePlaylists(token, list, accountApi) }
        account.playlistsSent = sig
    }

    // ---------------- pick up where you left off (1.17.0) ----------------
    // Places in movies and episodes, episodes finished, My List and Discover settings follow the profile to every TV,
    // locked with the account's history key (data/History.kt). Live TV never leaves the TV. Matching: the same account
    // playlist exactly; otherwise movies by title and year, episodes by series, season and episode (decision 13).

    private var placeInfo: Map<String, com.avidviewer.tv.data.Store.PlaceInfo> = store.loadPlaceInfo()
    private val EPISODE_KEY = Regex(":(\\d+):(\\d+)$")
    private var historyProblem = ""

    /** "Stopped at 34:12 on Living room, today 8:40 PM" for a place another TV sent; null when it's this TV's own. */
    fun placeLine(key: String): String? {
        val info = placeInfo[key] ?: return null
        val tv = info.tv ?: return null
        val pos = resumeOf(key).takeIf { it > 0 } ?: return null
        return "Stopped at ${fmtClock(pos)} on $tv, ${whenText(info.at)}"
    }
    /** The latest episode of [series] another TV stopped in, with its line (for the series page). */
    fun seriesPlaceLine(series: VodItem): String? {
        val k = resume.keys.filter { it.startsWith(series.key + ":") }.maxByOrNull { placeInfo[it]?.at ?: 0 } ?: return null
        val m = EPISODE_KEY.find(k) ?: return null
        return placeLine(k)?.let { "S${m.groupValues[1]} E${m.groupValues[2]} · $it" }
    }
    /** For Continue watching: "from Living room" when the latest place for this title came from another TV. */
    fun continueFrom(item: VodItem): String? = if (item.kind == LIVE_KIND) liveOther?.let { o -> "from ${o.optString("tv", "another TV")} · " + clockText(o.optLong("at")) } else
        resume.keys.filter { it == item.key || it.startsWith(item.key + ":") }.maxByOrNull { placeInfo[it]?.at ?: 0 }
            ?.let { placeInfo[it]?.tv }?.let { "from $it" }
    private fun fmtClock(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s / 60) % 60; val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }
    private fun whenText(t: Long): String {
        val cal = java.util.Calendar.getInstance(); val now = cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.timeInMillis = t
        val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(t))
        val d = now - cal.get(java.util.Calendar.DAY_OF_YEAR)
        return when { d == 0 -> "today $time"; d == 1 -> "yesterday $time"; else -> java.text.SimpleDateFormat("EEE MMM d", java.util.Locale.US).format(java.util.Date(t)) }
    }
    private var seriesLatest: Map<String, com.avidviewer.tv.data.Store.SeriesLatest> = store.loadSeriesLatest()
    private fun seriesOf(k: String): String? = EPISODE_KEY.find(k)?.let { k.substring(0, it.range.first) }

    /**
     * Continue watching, newest first (1.17.1). Movies while they're part-way. A series stays until its latest
     * episode has been watched; a new episode or season puts it back (the time it was found counts as activity).
     * Menu › remove hides a title until something new happens to it.
     */
    private fun continueKeys(): List<String> {
        val act = HashMap<String, Long>()
        fun bump(k: String, t: Long) { act[k] = maxOf(act[k] ?: 0L, t) }
        for (k in resume.keys) bump(seriesOf(k) ?: k, placeInfo[k]?.at ?: 1L)
        watched.mapNotNull { k -> seriesOf(k)?.let { it to k } }.groupBy({ it.first }, { it.second }).forEach { (series, eps) ->
            val latest = seriesLatest[series]
            val finished = latest != null && "$series:${latest.season}:${latest.episode}" in watched &&
                resume.keys.none { it.startsWith("$series:") }
            if (!finished) bump(series, maxOf(eps.maxOf { placeInfo[it]?.at ?: 1L }, latest?.newAt ?: 0L))
        }
        // A new episode found for a series in progress counts as activity too.
        act.keys.toList().forEach { k -> seriesLatest[k]?.newAt?.let { bump(k, it) } }
        return act.entries.filter { (k, t) -> (placeInfo["hide|$k"]?.at ?: 0L) < t }.sortedByDescending { it.value }.map { it.key }
    }

    // ---------------- live TV in pick up where you left off (1.18.0, design boards 23–24) ----------------
    /** The newest live channel this profile watched on another TV (from the account), for the live card. */
    private var liveOther: org.json.JSONObject? = null
    private var liveOtherFor: String? = null
    /** Channels the live card found in a playlist not loaded in memory, so they can be played. */
    private val liveCardChannels = HashMap<String, Channel>()

    private fun liveState(): org.json.JSONObject = store.loadLive(profile.id)
    private fun clockText(t: Long) = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(t))

    /**
     * Every 15 s: after 2 minutes on a live channel, the profile's last live channel is saved here and sent locked
     * with the next history sync: the playlist, the channel, its guide id, and when the program on now ends (a time,
     * never its name; the next one's end when under 10 minutes remain; 1 hour on when the channel has no guide).
     * One entry per profile, overwritten. Never for Kids profiles, or while history is paused.
     */
    internal fun noteLive(now: Long = System.currentTimeMillis()) {
        if (profile.kids || prefs.historyPaused || vodPlaying != null || !onScreen || !isStreaming()) return
        val key = currentKey ?: return
        if (watchKey != key || now - watchStart < LIVE_AFTER) return
        val c = channel(key) ?: return
        val st = liveState()
        val mine = st.optJSONObject("mine")
        val until = liveUntil(c, now)
        if (mine != null && mine.optString("key") == key && mine.optLong("until") == until && now - mine.optLong("at") < 10 * 60_000L) return
        val pl = playlists.firstOrNull { it.id == key.substringBefore(':') }
        val o = org.json.JSONObject().put("key", key).put("pl", pl?.remoteId ?: "").put("ch", key.substringAfter(':'))
            .put("name", c.name).put("epg", epgIdOf(c) ?: "").put("at", now).put("until", until)
        st.put("mine", o)
        store.saveLive(st, profile.id)
    }

    private fun liveUntil(c: Channel, now: Long): Long {
        val (cur, next) = nowNext(c).let { (a, b) -> if (a != null) a to b else liveGuideNow(c, now) }
        val end = cur?.end ?: return now + 3600_000L
        return if (end - now < 10 * 60_000L) next?.end ?: end else end
    }

    /** What's on [c] at [now] and next, read from the saved guide (the channel's playlist may not be in memory). */
    private fun liveGuideNow(c: Channel, now: Long): Pair<Program?, Program?> {
        val id = epgIdOf(c) ?: return null to null
        val list = runCatching { live.guide(c.key.substringBefore(':'), listOf(id), now - 6 * 3600_000L, now + 12 * 3600_000L)[id] }.getOrNull().orEmpty().sortedBy { it.start }
        val i = list.indexOfFirst { now >= it.start && now < it.end }
        return if (i < 0) null to list.firstOrNull { it.start > now } else list[i] to list.getOrNull(i + 1)
    }

    /** Words providers add round a channel's name ("US|", "HD", "ᴿᴬᵂ 60fps"…) are ignored when names are matched. */
    private fun liveName(n: String): String = n.lowercase()
        .replace(Regex("^[a-z]{2,3}\\s*[|:\\-]\\s*"), "")
        .replace(Regex("[\\u1d00-\\u1dbf\\u2070-\\u209f\\u1d2c-\\u1d6a]+"), " ")
        .replace(Regex("\\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|raw|60fps|50fps|backup|vip)\\b"), " ")
        .replace(Regex("[^a-z0-9]"), "")

    /** This TV's copy of the channel in [o]: the same playlist and channel, else the same guide id, else the same name. */
    private fun liveMatch(o: org.json.JSONObject): Channel? {
        fun ok(c: Channel?) = c?.takeIf { channelAllowed(it) && it.key !in prefs.hiddenChannels && !isGroupHidden(it.key.substringBefore(':'), it.group) }
        val pids = livePlaylists.map { it.id }
        playlists.firstOrNull { it.remoteId.isNotEmpty() && it.remoteId == o.optString("pl") && it.id in pids }?.let { p ->
            ok(runCatching { live.channelsByKeys(listOf(p.id + ":" + o.optString("ch"))) }.getOrNull()?.firstOrNull())?.let { return it }
        }
        val epg = o.optString("epg")
        if (epg.isNotBlank()) for (pid in pids) ok(runCatching { live.channelsWithEpg(pid, listOf(epg)) }.getOrNull()?.firstOrNull())?.let { return it }
        val want = liveName(o.optString("name")); if (want.length < 2) return null
        val word = o.optString("name").split(Regex("[^A-Za-z0-9]+")).filter { it.length >= 3 && it.uppercase() !in setOf("HD", "FHD", "UHD", "RAW", "FPS") }.maxByOrNull { it.length } ?: return null
        for (pid in pids) runCatching { live.searchChannels(pid, word, 200) }.getOrNull().orEmpty().firstOrNull { liveName(it.name) == want }?.let { c -> ok(c)?.let { return it } }
        return null
    }

    /** The live card for Continue watching, or null: from another TV, until the program ends, never for Kids. */
    private fun liveCard(now: Long = System.currentTimeMillis()): Pair<VodItem, Boolean>? {
        if (profile.kids || prefs.historyPaused || !account.linked) return null
        val st = liveState()
        val o = st.optJSONObject("other") ?: return null
        if (now >= o.optLong("until")) return null
        val c = liveMatch(o) ?: return null
        liveCardChannels[c.key] = c
        liveOther = o; liveOtherFor = c.key
        val on = liveGuideNow(c, now).first?.title
        val item = VodItem(LIVE_PREFIX + c.key, c.key.substringBefore(':'), LIVE_KIND, c.name, c.logo, "", category = on ?: "",
            added = o.optLong("at"))
        // If this TV's own last channel is newer, the card stays but comes second.
        val second = (st.optJSONObject("mine")?.optLong("at") ?: 0L) > o.optLong("at")
        return item to second
    }

    private fun withLiveCard(list: List<VodItem>): List<VodItem> {
        val (card, second) = runCatching { liveCard() }.getOrNull() ?: return list
        return if (second && list.isNotEmpty()) listOf(list[0], card) + list.drop(1) else listOf(card) + list
    }

    /** OK on the live card: the channel, full screen, on this TV. */
    private fun watchLiveCard(item: VodItem) {
        val key = item.key.removePrefix(LIVE_PREFIX)
        trail("live card: watching")
        openFullScreen(key, PlayContext.GROUP)
    }

    /** Menu on the live card: off Continue watching here and, through the account, on every TV. */
    private fun removeLiveCard() {
        val st = liveState()
        st.remove("other"); st.remove("mine"); st.put("removedAt", System.currentTimeMillis())
        store.saveLive(st, profile.id)
        liveOther = null
        vodVersion++
        status = "Removed the live channel from Continue watching" + if (account.token != null) " on every TV." else "."
    }

    /** For tests: the live card's channel key and whether it comes second, or null. */
    internal fun liveCardForTests(): Pair<String, Boolean>? = liveCard()?.let { (it.first.key.removePrefix(LIVE_PREFIX)) to it.second }

    /** For tests: Continue watching's titles, newest first. */
    internal fun continueKeysForTests() = continueKeys()

    /** A series page loaded its episodes: remember the latest one, and move places saved under the provider's ids. */
    private fun noteSeriesEpisodes(series: VodItem, eps: List<Episode>) {
        val last = eps.maxWithOrNull(compareBy<Episode>({ seasonNum(it.season) }, { it.number })) ?: return
        val now = System.currentTimeMillis()
        val old = seriesLatest[series.key]
        val s = seasonNum(last.season); val e = last.number
        val newer = old != null && (s > old.season || (s == old.season && e > old.episode))
        seriesLatest = seriesLatest + (series.key to com.avidviewer.tv.data.Store.SeriesLatest(s, e, now, if (newer) now else old?.newAt ?: 0L))
        store.saveSeriesLatest(seriesLatest)
        val rm = resume.toMutableMap(); val inf = placeInfo.toMutableMap(); var moved = false
        for (x in eps) {
            if (x.key.isBlank()) continue
            val nk = episodeKey(series, x)
            rm.remove(x.key)?.let { if (nk !in rm) rm[nk] = it; moved = true }
            if (watched.remove(x.key)) { watched.add(nk); moved = true }
            inf.remove(x.key)?.let { if (nk !in inf) inf[nk] = it; moved = true }
        }
        if (moved) { resume = rm; placeInfo = inf; store.saveResume(rm); store.saveWatched(watched); store.savePlaceInfo(inf) }
        if (moved || newer) vodVersion++
    }

    /**
     * Series in Continue watching (or with finished episodes) whose latest episode hasn't been checked for 12 hours:
     * reads their episodes in the background, so a new episode or season puts them back. A few at a time.
     */
    private var checkingSeries = false
    fun checkSeriesForNewEpisodes(limit: Int = 8) {
        if (checkingSeries) return
        val now = System.currentTimeMillis()
        val keys = (watched.mapNotNull { seriesOf(it) } + resume.keys.mapNotNull { seriesOf(it) }).distinct()
            .filter { now - (seriesLatest[it]?.checked ?: 0L) > 12 * 3600_000L }.take(limit)
        if (keys.isEmpty()) return
        checkingSeries = true
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { runCatching { catalog.byKeys(keys) }.getOrDefault(emptyList()) }
                for (item in items) {
                    val p = playlists.firstOrNull { it.id == item.playlistId && it.isXtream } ?: continue
                    val eps = withContext(Dispatchers.IO) { runCatching { XtreamVod.episodes(p, item.seriesId ?: "") }.getOrNull() } ?: continue
                    noteSeriesEpisodes(item, eps)
                }
            } finally { checkingSeries = false }
        }
    }

    /** Menu on a poster in Continue watching: off the list here and, through the account, on every TV. */
    fun removeFromContinue(item: VodItem) {
        if (item.kind == LIVE_KIND) { removeLiveCard(); return }
        val gone = resume.keys.filter { it == item.key || it.startsWith(item.key + ":") }.toSet()
        resume = resume - gone; store.saveResume(resume)
        // A series also stays off until something new happens to it (a new place, or a new episode found).
        placeInfo = placeInfo - gone + if (item.kind == "series") mapOf("hide|${item.key}" to com.avidviewer.tv.data.Store.PlaceInfo(0, System.currentTimeMillis())) else emptyMap()
        store.savePlaceInfo(placeInfo)
        vodVersion++
        status = "Removed ${item.name} from Continue watching" + if (account.token != null) " on every TV." else "."
    }

    private fun notePlace(key: String, dur: Long) {
        placeInfo = placeInfo + (key to com.avidviewer.tv.data.Store.PlaceInfo(dur.coerceAtLeast(0), System.currentTimeMillis(), null))
        store.savePlaceInfo(placeInfo)
    }

    /** One local entry, as the account sees it (account playlist ids), or null if the title isn't known here. */
    private class LocalEntry(val key: String, val match: String, val v: org.json.JSONObject, val at: Long)

    private fun localEntries(resumeMap: Map<String, Long>, watchedSet: Set<String>, info: Map<String, com.avidviewer.tv.data.Store.PlaceInfo>): List<LocalEntry> {
        val hides = info.keys.filter { it.startsWith("hide|") }
        val keys = (resumeMap.keys + watchedSet).distinct().filter { EPISODE_KEY.find(it) != null || !Regex(":e\\d+$").containsMatchIn(it) } + hides
        val seriesKeys = (keys.mapNotNull { k -> EPISODE_KEY.find(k)?.let { k.substring(0, it.range.first) } } + hides.map { it.removePrefix("hide|") }).distinct()
        val known = runCatching { catalog.byKeys(keys.filter { EPISODE_KEY.find(it) == null } + seriesKeys) }.getOrDefault(emptyList()).associateBy { it.key }
        val remote = playlists.associate { it.id to it.remoteId }
        return keys.mapNotNull { k ->
            if (k.startsWith("hide|")) {
                val series = known[k.removePrefix("hide|")] ?: return@mapNotNull null
                val v = org.json.JSONObject().put("t", "h").put("series", series.name).put("tv", accountTvName.ifBlank { "A TV" })
                return@mapNotNull LocalEntry(k, "h|" + com.avidviewer.tv.data.Tmdb.normalize(series.name), v, info[k]?.at ?: 1L)
            }
            val m = EPISODE_KEY.find(k)
            val title = known[if (m != null) k.substring(0, m.range.first) else k] ?: return@mapNotNull null
            val pid = k.substringBefore(':'); val rest = k.substringAfter(':')
            val done = k in watchedSet && k !in resumeMap
            val pos = resumeMap[k] ?: 0L
            val i = info[k]
            val v = org.json.JSONObject().put("pos", if (done) 0 else pos).put("done", done).put("dur", i?.dur ?: 0)
                .put("tv", i?.tv ?: accountTvName.ifBlank { "A TV" }).put("id", rest)
            remote[pid]?.takeIf { it.isNotEmpty() }?.let { v.put("pl", it) }
            val match = if (m != null) {
                v.put("t", "e").put("series", title.name).put("s", m.groupValues[1].toInt()).put("e", m.groupValues[2].toInt())
                // The latest episode this TV knows of: lets the website tell a finished series from one with more to watch.
                seriesLatest[title.key]?.let { l -> v.put("lastS", l.season).put("lastE", l.episode) }
                com.avidviewer.tv.data.History.episodeMatch(title.name, m.groupValues[1], m.groupValues[2])
            } else {
                v.put("t", "m").put("title", title.name).put("year", title.year)
                com.avidviewer.tv.data.History.movieMatch(title.name, title.year)
            }
            LocalEntry(k, match, v, i?.at ?: 1L)
        }
    }

    /** Where an entry from the account is on this TV: the same account playlist, else by title. Null: not here. */
    private fun resolveEntry(v: org.json.JSONObject): String? {
        if (v.optString("t") == "h") {
            val pids = playlists.filter { it.vodOn }.map { it.id }
            val s = runCatching { catalog.findByName(vodKindKey("series"), com.avidviewer.tv.data.Tmdb.normalize(v.optString("series")), pids) }.getOrNull() ?: return null
            return "hide|${s.key}"
        }
        val local = playlists.firstOrNull { it.remoteId.isNotEmpty() && it.remoteId == v.optString("pl") }?.id
        if (local != null) {
            val k = "$local:" + v.optString("id")
            val base = EPISODE_KEY.find(k)?.let { k.substring(0, it.range.first) } ?: k
            if (runCatching { catalog.byKeys(listOf(base)) }.getOrDefault(emptyList()).isNotEmpty()) return k
        }
        val pids = playlists.filter { it.vodOn }.map { it.id }
        return if (v.optString("t") == "e") {
            val s = runCatching { catalog.findByName(vodKindKey("series"), com.avidviewer.tv.data.Tmdb.normalize(v.optString("series")), pids) }.getOrNull() ?: return null
            "${s.key}:${v.optInt("s")}:${v.optInt("e")}"
        } else {
            val year = v.optString("year")
            runCatching { catalog.findAllByName(vodKindKey("movie"), com.avidviewer.tv.data.Tmdb.normalize(v.optString("title")), pids, includeHidden = true, limit = 20) }
                .getOrDefault(emptyList()).firstOrNull { year.isBlank() || it.year.isBlank() || it.year.take(4) == year.take(4) }?.key
        }
    }

    /** My List and Discover settings, as the account sees them (no stream addresses). */
    private fun discoverDoc(p: Prefs, keep: org.json.JSONArray?): org.json.JSONObject {
        val remote = playlists.associate { it.id to it.remoteId }
        val list = org.json.JSONArray()
        p.myList.forEach { x ->
            val o = org.json.JSONObject().put("t", if (x.kind == "series") "series" else "movie").put("name", x.name).put("year", x.year)
                .put("id", x.key.substringAfter(':')).put("cat", x.category).put("rating", x.rating).put("poster", x.logo ?: "").put("added", x.added)
            remote[x.playlistId]?.takeIf { it.isNotEmpty() }?.let { o.put("pl", it) }
            list.put(o)
        }
        // Titles from the account this TV doesn't have stay in the account's list.
        if (keep != null) for (i in 0 until keep.length()) list.put(keep.getJSONObject(i))
        return org.json.JSONObject().put("myList", list)
            .put("services", org.json.JSONArray().apply { p.myServices.forEach { put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("logo", it.logo ?: "")) } })
            .put("start", p.discoverStart).put("grownUps", p.discoverGrownUps).put("genres", p.genreSort)
    }

    private fun withDiscoverDoc(p: Prefs, d: org.json.JSONObject): Pair<Prefs, org.json.JSONArray> {
        val keep = org.json.JSONArray()
        val list = ArrayList<VodItem>()
        val a = d.optJSONArray("myList") ?: org.json.JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val series = o.optString("t") == "series"
            val local = playlists.firstOrNull { it.remoteId.isNotEmpty() && it.remoteId == o.optString("pl") }?.id
            val found = local?.let { runCatching { catalog.byKeys(listOf("$it:" + o.optString("id"))) }.getOrDefault(emptyList()).firstOrNull() }
                ?: runCatching { catalog.findAllByName(vodKindKey(if (series) "series" else "movie"), com.avidviewer.tv.data.Tmdb.normalize(o.optString("name")), playlists.filter { it.vodOn }.map { it.id }, includeHidden = true, limit = 20) }
                    .getOrDefault(emptyList()).firstOrNull { val y = o.optString("year"); y.isBlank() || it.year.isBlank() || it.year.take(4) == y.take(4) }
            if (found != null) list.add(found) else keep.put(o)
        }
        val sv = d.optJSONArray("services") ?: org.json.JSONArray()
        val services = (0 until sv.length()).map { sv.getJSONObject(it) }.map { WatchProvider(it.optInt("id"), it.optString("name"), it.optString("logo").ifBlank { null }) }
        return p.copy(myList = list.distinctBy { it.key }, myServices = services, discoverStart = d.optString("start", "all").ifBlank { "all" }, discoverGrownUps = d.optBoolean("grownUps", true), genreSort = d.optBoolean("genres", p.genreSort)) to keep
    }

    /**
     * Sends this TV's changes for every account profile on it and applies what other TVs and the website changed
     * (the most recent change wins). Runs with the account check while the TV is managed.
     */
    private suspend fun syncHistory(token: String) {
        // Kids profiles keep their places on each TV and have no Discover: nothing of theirs is sent (privacy).
        val acc = profiles.list.filter { it.remoteId.isNotEmpty() && !it.kids }
        if (acc.isEmpty()) return
        val H = account.historyKey
        val discSent = HashSet<String>()
        val body = org.json.JSONObject(); val arr = org.json.JSONArray(); body.put("profiles", arr)
        val states = HashMap<String, org.json.JSONObject>()
        val sent = HashMap<String, Map<String, String>>()
        val sentDisc = HashMap<String, String>()
        // BUG-057: off the screen's thread (an HMAC for every place, AES for each one sent).
        withContext(Dispatchers.Default) { for (p in acc) {
            val st = withContext(Dispatchers.IO) { store.loadHistorySync(p.id) }; states[p.id] = st
            val o = org.json.JSONObject().put("id", p.remoteId).put("since", st.optLong("since"))
            if (H != null) {
                val hk: ByteArray = H
                val cur = p.id == profile.id
                val pr = if (cur) prefs else withContext(Dispatchers.IO) { store.loadPrefs(p.id) }
                val rm = if (cur) resume else withContext(Dispatchers.IO) { store.loadResume(p.id) }
                val ws = if (cur) watched else withContext(Dispatchers.IO) { store.loadWatched(p.id) }
                val inf = if (cur) placeInfo else withContext(Dispatchers.IO) { store.loadPlaceInfo(p.id) }
                val items = st.optJSONObject("items") ?: org.json.JSONObject()
                val put = org.json.JSONArray(); val sigs = HashMap<String, String>()
                if (!pr.historyPaused) {
                    val local = withContext(Dispatchers.IO) { localEntries(rm, ws, inf) }
                    val seen = HashSet<String>()
                    for (e in local) {
                        val id = com.avidviewer.tv.data.History.itemId(hk, e.match); seen.add(id)
                        val sig = (e.v.optLong("pos") / 10_000).toString() + e.v.optBoolean("done") + e.v.optInt("lastS") + ":" + e.v.optInt("lastE") + (if (e.key.startsWith("hide|")) e.at.toString() else "")
                        sigs[id] = e.key + "\u0001" + sig
                        if (items.optJSONObject(id)?.optString("s") != sig) put.put(org.json.JSONObject().put("item", id).put("blob", com.avidviewer.tv.data.History.lock(hk, e.v)).put("at", e.at))
                    }
                    // 1.18.0: the profile's last live channel, one entry overwritten; Menu on the live card removes it everywhere.
                    val ls = withContext(Dispatchers.IO) { store.loadLive(p.id) }
                    val lid = com.avidviewer.tv.data.History.itemId(hk, LIVE_MATCH); seen.add(lid)
                    val mine = ls.optJSONObject("mine"); val gone = ls.optLong("removedAt")
                    if (mine != null && mine.optLong("at") > gone) {
                        val v = org.json.JSONObject().put("t", "l").put("pl", mine.optString("pl")).put("ch", mine.optString("ch"))
                            .put("name", mine.optString("name")).put("epg", mine.optString("epg")).put("until", mine.optLong("until"))
                            .put("tv", accountTvName.ifBlank { "A TV" })
                        val sig = "l" + mine.optLong("at") + ":" + mine.optLong("until")
                        sigs[lid] = LIVE_PREFIX + "\u0001" + sig
                        if (items.optJSONObject(lid)?.optString("s") != sig) put.put(org.json.JSONObject().put("item", lid).put("blob", com.avidviewer.tv.data.History.lock(hk, v)).put("at", mine.optLong("at")))
                    } else if (gone > 0) {
                        val sig = "x$gone"
                        sigs[lid] = LIVE_PREFIX + "\u0001" + sig
                        if (items.optJSONObject(lid)?.optString("s") != sig) put.put(org.json.JSONObject().put("item", lid).put("blob", org.json.JSONObject.NULL).put("at", gone))
                    }
                    // Removed here (Continue watching › Menu): removed everywhere.
                    items.keys().forEach { id -> val k = items.optJSONObject(id)?.optString("k").orEmpty(); if (id !in seen && k.isNotEmpty() && !k.startsWith("hide|") && !k.startsWith(LIVE_PREFIX) && k !in rm && k !in ws) put.put(org.json.JSONObject().put("item", id).put("blob", org.json.JSONObject.NULL).put("at", System.currentTimeMillis())) }
                }
                if (put.length() > 0) o.put("put", put)
                sent[p.id] = sigs
                val doc = discoverDoc(pr, st.optJSONArray("keep"))
                val dsig = doc.toString().hashCode().toString(16)
                sentDisc[p.id] = dsig
                if (st.has("disc") && st.optString("disc") != dsig) { o.put("discover", org.json.JSONObject().put("blob", com.avidviewer.tv.data.History.lock(hk, doc)).put("at", System.currentTimeMillis())); discSent.add(p.id) }
            }
            arr.put(o)
        } }
        val res = withContext(Dispatchers.IO) { com.avidviewer.tv.data.AccountApi.history(token, body, accountApi) }
        if (H == null) {
            val sealed = res.key ?: return
            val k = withContext(Dispatchers.IO) { account.deviceKey() }?.let { dk -> runCatching { com.avidviewer.tv.data.DeviceKey.open(dk, sealed) }.getOrNull() }
            val got = k?.optString("historyKey")?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }
            if (got != null) { account.historyKey = got; trail("history key received"); accountSyncAgain = true } // send this TV's places next
            return
        }
        val HK: ByteArray = H
        // The account's TMDB key.
        res.tmdb?.optString("blob")?.takeIf { it.isNotEmpty() }?.let { b ->
            val k = runCatching { com.avidviewer.tv.data.History.open(HK, b).optString("tmdb") }.getOrNull()
            if (!k.isNullOrBlank() && k != account.tmdbFromAccount) runCatching {
                val enc = Crypto.encrypt(k)
                applyingAccount = true; try { updatePrefs { it.copy(tmdbKeyEnc = enc) } } finally { applyingAccount = false }
                account.tmdbFromAccount = k
                discoverLists = emptyMap(); discoverLoadedAt.clear()
            }
        }
        val onMain = ArrayList<() -> Unit>()
        var liveChanged = false
        withContext(Dispatchers.IO) { for (i in 0 until res.profiles.length()) {
            val o = res.profiles.getJSONObject(i)
            val p = acc.firstOrNull { it.remoteId == o.optString("id") } ?: continue
            val st = states[p.id] ?: org.json.JSONObject()
            val items = st.optJSONObject("items") ?: org.json.JSONObject()
            // What was just sent is now known to the account.
            sent[p.id]?.forEach { (id, ks) -> items.put(id, org.json.JSONObject().put("k", ks.substringBefore('\u0001')).put("s", ks.substringAfter('\u0001'))) }
            val cur = p.id == profile.id
            val rm = (if (cur) resume else store.loadResume(p.id)).toMutableMap()
            val ws = LinkedHashSet(if (cur) watched else store.loadWatched(p.id))
            val inf = (if (cur) placeInfo else store.loadPlaceInfo(p.id)).toMutableMap()
            var changed = false
            if (o.optBoolean("enabled")) {
                val got = o.optJSONArray("items") ?: org.json.JSONArray()
                for (j in 0 until got.length()) {
                    val it = got.getJSONObject(j); val id = it.optString("item"); val at = it.optLong("at")
                    if (it.isNull("blob") && items.optJSONObject(id)?.optString("k") == LIVE_PREFIX) {
                        // The live card was removed on another TV (or on the website).
                        val ls = store.loadLive(p.id)
                        if ((ls.optJSONObject("other")?.optLong("at") ?: 0L) <= at) { ls.remove("other"); store.saveLive(ls, p.id); liveChanged = true }
                        continue
                    }
                    if (it.isNull("blob")) {
                        val k = items.optJSONObject(id)?.optString("k")
                        if (!k.isNullOrEmpty() && (inf[k]?.at ?: 0) <= at) { rm.remove(k); inf.remove(k); changed = true }
                        items.put(id, org.json.JSONObject().put("x", true))
                        continue
                    }
                    val v = runCatching { com.avidviewer.tv.data.History.open(HK, it.optString("blob")) }.getOrNull() ?: continue
                    if (v.optString("t") == "l") {
                        // Another TV's live channel: kept for the live card (this TV's own comes back too: ignored).
                        items.put(id, org.json.JSONObject().put("k", LIVE_PREFIX).put("s", "l" + at + ":" + v.optLong("until")))
                        val ls = store.loadLive(p.id)
                        if (v.optString("tv") != accountTvName && at > (ls.optJSONObject("other")?.optLong("at") ?: 0L) && at > ls.optLong("removedAt")) {
                            ls.put("other", org.json.JSONObject(v.toString()).put("at", at)); store.saveLive(ls, p.id); liveChanged = true
                        }
                        continue
                    }
                    val k = resolveEntry(v) ?: continue
                    if ((inf[k]?.at ?: 0) > at) continue // changed here since: this TV's is newer
                    val tvName = v.optString("tv").takeIf { n -> n.isNotBlank() && n != accountTvName }
                    if (k.startsWith("hide|")) Unit
                    else if (v.optBoolean("done")) { rm.remove(k); ws.remove(k); ws.add(k) } else { rm[k] = v.optLong("pos"); }
                    inf[k] = com.avidviewer.tv.data.Store.PlaceInfo(v.optLong("dur"), at, tvName)
                    items.put(id, org.json.JSONObject().put("k", k).put("s", (v.optLong("pos") / 10_000).toString() + v.optBoolean("done") + v.optInt("lastS") + ":" + v.optInt("lastE") + (if (k.startsWith("hide|")) at.toString() else "")))
                    changed = true
                }
                st.put("since", o.optLong("seq"))
            }
            if (changed) {
                store.saveResume(rm, p.id); store.saveWatched(ws, p.id); store.savePlaceInfo(inf, p.id)
                // Nov 17: decided when it's applied, not when it was read: the profile in use may have changed since.
                onMain.add { if (p.id == profile.id) { resume = rm; watched = ws; placeInfo = inf; vodVersion++ } }
            }
            st.put("items", items)
            // Discover settings and My List: the first time, the account's and this TV's join; after that the newer wins.
            val d = o.optJSONObject("discover")
            val first = !st.has("disc")
            fun applyDoc(doc: org.json.JSONObject) {
                val pr = if (cur) prefs else store.loadPrefs(p.id)
                val (np, keep) = withDiscoverDoc(pr, doc)
                // Nov 17 (Evan's TV showed Maven's categories): only the Discover fields change, on the profile the doc
                // is for, read when it's applied. Writing the whole settings read earlier put one profile's hidden
                // categories, favorites and My List on another when the profile in use changed during the sync.
                onMain.add {
                    if (p.id == profile.id) { applyingAccount = true; try { updatePrefs { withDiscoverDoc(it, doc).first } } finally { applyingAccount = false }; applyDiscoverStart() }
                    else store.savePersonal(p.id, com.avidviewer.tv.data.personalOf(withDiscoverDoc(store.loadPrefs(p.id), doc).first))
                }
                st.put("keep", keep)
                st.put("disc", if (first) "" else discoverDoc(np, keep).toString().hashCode().toString(16))
            }
            when {
                first -> {
                    if (d != null) runCatching { com.avidviewer.tv.data.History.open(HK, d.optString("blob")) }.getOrNull()?.let { doc ->
                        val mine = discoverDoc(if (cur) prefs else store.loadPrefs(p.id), null)
                        val l = doc.optJSONArray("myList") ?: org.json.JSONArray().also { doc.put("myList", it) }
                        val names = (0 until l.length()).map { n -> l.getJSONObject(n).optString("name").lowercase() }.toSet()
                        val ml = mine.optJSONArray("myList")!!
                        for (n in 0 until ml.length()) if (ml.getJSONObject(n).optString("name").lowercase() !in names) l.put(ml.getJSONObject(n))
                        val sv = doc.optJSONArray("services") ?: org.json.JSONArray().also { doc.put("services", it) }
                        val ids = (0 until sv.length()).map { n -> sv.getJSONObject(n).optInt("id") }.toSet()
                        val ms = mine.optJSONArray("services")!!
                        for (n in 0 until ms.length()) if (ms.getJSONObject(n).optInt("id") !in ids) sv.put(ms.getJSONObject(n))
                        applyDoc(doc)
                        st.put("discAt", d.optLong("at"))
                    }
                    st.put("disc", "") // send the joined (or this TV's) next time
                }
                p.id in discSent -> st.put("disc", sentDisc[p.id]).put("discAt", System.currentTimeMillis())
                d != null && d.optLong("at") > st.optLong("discAt") ->
                    runCatching { com.avidviewer.tv.data.History.open(HK, d.optString("blob")) }.getOrNull()?.let { doc -> applyDoc(doc); st.put("discAt", d.optLong("at")) }
            }
            store.saveHistorySync(st, p.id)
        } }
        onMain.forEach { it() }
        if (liveChanged) vodVersion++
        historyProblem = ""
    }

    /** Discover opens the way the profile chose (on your provider / my services / everything). */
    fun applyDiscoverStart() {
        onlyOnProvider = prefs.discoverStart == "provider"
        onlyMyServices = prefs.discoverStart == "services"
    }

    // ---------------- favorites, lists, order and names with the account (1.16.0) ----------------
    // Favorites and lists belong to a profile and follow it to every device; category and channel order and names
    // belong to a playlist. Either side can change them; the most recent change wins. The account uses its own
    // playlist ids in keys ("<account playlist>:<channel id>"); this device maps them to its own playlist ids.

    private var applyingAccount = false
    private var listsProblem = ""

    private fun lj(a: List<String>) = org.json.JSONArray().apply { a.forEach { put(it) } }
    private fun ls(a: org.json.JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    private fun sigOf(o: org.json.JSONObject) = o.toString().hashCode().toString(16)

    /** [p]'s favorites and lists as the account sees them (account playlists only; others stay on this device). */
    private fun listsDocOf(p: Prefs, toRemote: Map<String, String>, accountDoc: org.json.JSONObject?): org.json.JSONObject {
        fun map(keys: List<String>) = keys.mapNotNull { k -> toRemote[k.substringBefore(':')]?.let { it + ":" + k.substringAfter(':') } }
        // Keys for account playlists this device doesn't have are kept as the account has them.
        val mine = toRemote.values.toSet()
        fun keep(keys: List<String>) = keys.filter { it.substringBefore(':') !in mine }
        val accLists = accountDoc?.optJSONArray("lists")
        fun accKeys(id: String): List<String> { if (accLists == null) return emptyList(); for (i in 0 until accLists.length()) { val l = accLists.getJSONObject(i); if (l.optString("id") == id) return keep(ls(l.optJSONArray("keys"))) }; return emptyList() }
        return org.json.JSONObject()
            .put("favorites", lj(map(p.favorites) + keep(ls(accountDoc?.optJSONArray("favorites")))))
            .put("favSort", p.favSort)
            .put("lists", org.json.JSONArray().apply {
                p.lists.forEach { l -> put(org.json.JSONObject().put("id", l.id).put("name", l.name).put("hidden", l.hidden).put("sort", l.sort).put("keys", lj(map(l.keys) + accKeys(l.id)))) }
            })
    }

    /** [p] with the account's favorites and lists; this device's channels from playlists not in the account stay. */
    private fun withAccountLists(p: Prefs, doc: org.json.JSONObject, toLocal: Map<String, String>, toRemote: Map<String, String>): Prefs {
        fun map(keys: List<String>) = keys.mapNotNull { k -> toLocal[k.substringBefore(':')]?.let { it + ":" + k.substringAfter(':') } }
        fun localOnly(keys: List<String>) = keys.filter { it.substringBefore(':') !in toRemote }
        val lists = doc.optJSONArray("lists") ?: org.json.JSONArray()
        val out = (0 until lists.length()).map { i ->
            val l = lists.getJSONObject(i); val id = l.optString("id")
            val old = p.lists.firstOrNull { it.id == id }
            ChannelList(id = id, name = l.optString("name"), hidden = l.optBoolean("hidden"), keys = (map(ls(l.optJSONArray("keys"))) + localOnly(old?.keys.orEmpty())).distinct(),
                sort = when (l.optString("sort")) { SORT_AZ -> SORT_AZ; SORT_MANUAL -> SORT_MANUAL; else -> old?.sort ?: SORT_MANUAL })
        } + p.lists.filter { o -> out0(lists).none { it == o.id } && o.keys.isNotEmpty() && o.keys.all { it.substringBefore(':') !in toRemote } }
        val fs = when (doc.optString("favSort")) { SORT_AZ -> SORT_AZ; SORT_MANUAL -> SORT_MANUAL; else -> p.favSort }
        return p.copy(favorites = (map(ls(doc.optJSONArray("favorites"))) + localOnly(p.favorites)).distinct(), lists = out, favSort = fs)
    }
    private fun out0(a: org.json.JSONArray) = (0 until a.length()).map { a.getJSONObject(it).optString("id") }

    /** One account playlist's live order and names, in the account's terms (channel ids without the playlist prefix). */
    private fun orderDocOf(p: Prefs, pl: Playlist, groups: Set<String>): org.json.JSONObject {
        val pre = pl.id + ":"
        return org.json.JSONObject()
            .put("groupOrder", lj(p.groupOrder.filter { it in groups }))
            .put("groupNames", org.json.JSONObject().apply { p.groupNames.filterKeys { it in groups }.toSortedMap().forEach { (k, v) -> put(k, v) } })
            .put("channelOrder", org.json.JSONObject().apply {
                p.channelOrder.filterKeys { it in groups }.toSortedMap().forEach { (g, keys) -> keys.filter { it.startsWith(pre) }.map { it.removePrefix(pre) }.takeIf { it.isNotEmpty() }?.let { put(g, lj(it)) } }
            })
            .put("channelNames", org.json.JSONObject().apply { p.channelNames.filterKeys { it.startsWith(pre) }.toSortedMap().forEach { (k, v) -> put(k.removePrefix(pre), v) } })
            .apply { p.channelSort[pl.id]?.let { put("channelSort", it) } }
    }

    private fun withAccountOrder(p: Prefs, doc: org.json.JSONObject, pl: Playlist, groups: Set<String>): Prefs {
        val pre = pl.id + ":"
        val order = ls(doc.optJSONArray("groupOrder")).filter { it in groups }
        val gn = doc.optJSONObject("groupNames") ?: org.json.JSONObject()
        val co = doc.optJSONObject("channelOrder") ?: org.json.JSONObject()
        val cn = doc.optJSONObject("channelNames") ?: org.json.JSONObject()
        val names = p.groupNames.filterKeys { it !in groups } + gn.keys().asSequence().filter { it in groups }.associateWith { gn.optString(it) }
        val chOrder = p.channelOrder.mapValues { (g, keys) -> if (g in groups) keys.filterNot { it.startsWith(pre) } else keys }.toMutableMap()
        co.keys().forEach { g -> if (g in groups) chOrder[g] = ls(co.optJSONArray(g)).map { pre + it } + chOrder[g].orEmpty() }
        val chNames = p.channelNames.filterKeys { !it.startsWith(pre) } + cn.keys().asSequence().associate { pre + it to cn.optString(it) }
        // This playlist's categories first in the account's order (the rest keep theirs); unlisted ones follow the provider.
        val groupOrder = if (order.isEmpty()) p.groupOrder.filter { it !in groups } else order + p.groupOrder.filter { it !in groups }
        val sort = doc.optString("channelSort").takeIf { it == SORT_PROVIDER || it == SORT_AZ || it == SORT_MANUAL }
        return p.copy(groupOrder = groupOrder, groupNames = names, channelOrder = chOrder.filterValues { it.isNotEmpty() }, channelNames = chNames,
            channelSort = if (sort == null) p.channelSort else p.channelSort + (pl.id to sort))
    }

    /** Sends this device's changes to favorites, lists, order and names, and applies the account's newer ones. */
    private suspend fun syncLists(token: String) {
        // BUG-057: the documents (every channel key of a playlist's order, each profile's lists) are built, compared and
        // read back off the screen's thread; only the writes to the profile's settings run on it.
        val acctPls = playlists.filter { it.remoteId.isNotEmpty() }
        val toRemote = acctPls.associate { it.id to it.remoteId }
        val toLocal = acctPls.associate { it.remoteId to it.id }
        val state = withContext(Dispatchers.Default) { runCatching { org.json.JSONObject(account.listsState) }.getOrDefault(org.json.JSONObject()) }
        val ps = state.optJSONObject("p") ?: org.json.JSONObject().also { state.put("p", it) }
        val os = state.optJSONObject("o") ?: org.json.JSONObject().also { state.put("o", it) }
        val changed = runCatching { org.json.JSONObject(account.listsChanged) }.getOrDefault(org.json.JSONObject())
        val body = org.json.JSONObject()
        val known = org.json.JSONObject(); val kp = org.json.JSONObject(); val ko = org.json.JSONObject()
        known.put("profiles", kp).put("playlists", ko); body.put("known", known)
        val accProfiles = profiles.list.filter { it.remoteId.isNotEmpty() }
        val sendP = org.json.JSONArray()
        for (p in accProfiles) {
            val st = ps.optJSONObject(p.remoteId)
            val pr = if (p.id == profile.id) prefs else withContext(Dispatchers.IO) { store.loadPrefs(p.id) }
            val (doc, sig) = withContext(Dispatchers.Default) { listsDocOf(pr, toRemote, st?.optJSONObject("d")).let { it to sigOf(it) } }
            st?.optString("h")?.takeIf { it.isNotEmpty() }?.let { kp.put(p.remoteId, it) }
            if (st == null || st.optString("s") != sig) {
                val at = changed.optLong(p.id, 0L).takeIf { it > 0 } ?: if (st == null) 1L else System.currentTimeMillis()
                sendP.put(org.json.JSONObject().put("id", p.remoteId).put("changedAt", at).put("data", doc))
            }
        }
        body.put("profiles", sendP)
        val groupsOf = HashMap<String, Set<String>>()
        val sendO = org.json.JSONArray()
        for (pl in acctPls) {
            val groups = withContext(Dispatchers.IO) { HashSet<String>().also { g -> runCatching { live.forEachChannel(pl.id) { _, _, grp, _ -> g.add(grp) } } } }
            if (groups.isEmpty()) continue
            groupsOf[pl.remoteId] = groups
            val st = os.optJSONObject(pl.remoteId)
            st?.optString("h")?.takeIf { it.isNotEmpty() }?.let { ko.put(pl.remoteId, it) }
            val snap = prefs
            val (doc, sig) = withContext(Dispatchers.Default) { orderDocOf(snap, pl, groups).let { it to sigOf(it) } }
            if (st == null || st.optString("s") != sig) {
                // Never changed here and never synced: only send it if there is something to say.
                val empty = doc.optJSONArray("groupOrder")!!.length() == 0 && doc.optJSONObject("groupNames")!!.length() == 0 && doc.optJSONObject("channelOrder")!!.length() == 0 && doc.optJSONObject("channelNames")!!.length() == 0 && !doc.has("channelSort")
                if (st == null && empty) { os.put(pl.remoteId, org.json.JSONObject().put("s", sig)); continue }
                val at = account.orderChangedAt.takeIf { it > 0 } ?: 1L
                sendO.put(org.json.JSONObject().put("id", pl.remoteId).put("changedAt", at).put("data", doc))
            }
        }
        body.put("playlists", sendO)
        val res = withContext(Dispatchers.IO) { com.avidviewer.tv.data.AccountApi.listsSync(token, body, accountApi) }
        // What was sent is now this device's last-known version.
        withContext(Dispatchers.Default) {
            for (i in 0 until sendP.length()) { val o = sendP.getJSONObject(i); ps.put(o.getString("id"), (ps.optJSONObject(o.getString("id")) ?: org.json.JSONObject()).put("s", sigOf(o.getJSONObject("data")))) }
            for (i in 0 until sendO.length()) { val o = sendO.getJSONObject(i); os.put(o.getString("id"), (os.optJSONObject(o.getString("id")) ?: org.json.JSONObject()).put("s", sigOf(o.getJSONObject("data")))) }
        }
        // The account's versions this device doesn't have yet.
        var changedHere = false
        res.optJSONArray("profiles")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val id = o.optString("id"); val doc = o.optJSONObject("data") ?: continue
                val p = accProfiles.firstOrNull { it.remoteId == id } ?: continue
                // Read and written in one step for the profile [doc] is for (never another profile's settings).
                val next: Prefs
                if (p.id == profile.id) {
                    val snap = prefs
                    val made = withContext(Dispatchers.Default) { withAccountLists(snap, doc, toLocal, toRemote) }
                    // Worked out from the settings as they were; if they changed meanwhile, again from the new ones.
                    applyingAccount = true; try { updatePrefs { pr -> if (pr === snap) made else withAccountLists(pr, doc, toLocal, toRemote) } } finally { applyingAccount = false }; next = prefs
                } else { next = withContext(Dispatchers.IO) { withAccountLists(store.loadPrefs(p.id), doc, toLocal, toRemote).also { store.savePersonal(p.id, com.avidviewer.tv.data.personalOf(it)) } } }
                val sig = withContext(Dispatchers.Default) { sigOf(listsDocOf(next, toRemote, doc)) }
                ps.put(id, org.json.JSONObject().put("h", o.optString("hash")).put("d", doc).put("s", sig))
                changedHere = true
            }
        }
        res.optJSONArray("playlists")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val id = o.optString("id"); val doc = o.optJSONObject("data") ?: continue
                val pl = acctPls.firstOrNull { it.remoteId == id } ?: continue
                val groups = groupsOf[id] ?: continue
                val snap = prefs
                val made = withContext(Dispatchers.Default) { withAccountOrder(snap, doc, pl, groups) }
                applyingAccount = true; try { updatePrefs { pr -> if (pr === snap) made else withAccountOrder(pr, doc, pl, groups) } } finally { applyingAccount = false }
                val next = prefs
                val sig = withContext(Dispatchers.Default) { sigOf(orderDocOf(next, pl, groups)) }
                os.put(id, org.json.JSONObject().put("h", o.optString("hash")).put("s", sig))
                changedHere = true
            }
        }
        account.listsState = withContext(Dispatchers.Default) { state.toString() }
        if (changedHere) { trail("lists sync: applied the account's changes"); regroupLater(); favDataTick++ }
        listsProblem = ""
    }

    /** One playlist's categories, with each live category's channels (id after the playlist's prefix, and name). */
    private fun catalogOf(pl: Playlist): org.json.JSONObject? {
        val groups = LinkedHashMap<String, org.json.JSONArray>()
        if (pl.liveOn) live.forEachChannel(pl.id) { key, name, group, _ ->
            groups.getOrPut(group) { org.json.JSONArray() }.put(org.json.JSONArray().put(key.substringAfter(':')).put(name))
        }
        fun vod(k: String) = org.json.JSONArray().apply {
            if (pl.vodOn) runCatching { catalog.categories(k, listOf(pl.id), includeHidden = true) }.getOrDefault(emptyList())
                .forEach { (_, c, n) -> put(org.json.JSONArray().put(c).put(n)) }
        }
        val movie = vod("movie"); val series = vod("series")
        if (groups.isEmpty() && movie.length() == 0 && series.length() == 0) return null
        var withChannels = true
        while (true) {
            val liveA = org.json.JSONArray()
            groups.forEach { (g, ch) -> liveA.put(org.json.JSONObject().put("n", g).put("c", ch.length()).put("ch", if (withChannels) ch else org.json.JSONArray())) }
            val o = org.json.JSONObject().put("playlistId", pl.remoteId).put("live", liveA).put("movie", movie).put("series", series)
            // The website keeps up to about 1.9 MB: a very big playlist sends its categories without channel names.
            if (!withChannels || o.toString().length < 1_800_000) return o
            withChannels = false
        }
    }

    // ---------------- Watching now (1.15.0) ----------------
    /** When the current stream started (for "playing for 42 min" on the website). */
    private var playingSince = 0L
    private var playingReported = false
    /** The account stopped this stream from avidviewer.app ("This stream was stopped from your account"). */
    var streamStopped by mutableStateOf(false); private set
    private var stoppedKey: String? = null
    private var stoppedVod: VodItem? = null

    /** Something is playing (or about to): a channel, a preview, a movie or an episode. */
    private fun isStreaming(): Boolean = player.mediaItemCount > 0 && player.playWhenReady && playerError == null &&
        player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED

    /** Every 15 s while linked: tells the account which playlist and profile are playing (never the channel or title). */
    internal suspend fun reportPlaying() {
        val token = account.token ?: return
        val on = isStreaming() && onScreen
        if (!on && !playingReported) { playingSince = 0L; return }
        val data = org.json.JSONObject().put("playing", on)
        if (on) {
            if (playingSince == 0L) playingSince = System.currentTimeMillis()
            val pid = vodPlaying?.playlistId ?: currentKey?.substringBefore(':')
            val pl = playlists.firstOrNull { it.id == pid }
            data.put("playlistId", pl?.remoteId ?: "").put("playlistName", pl?.name ?: "A playlist").put("startedAt", playingSince)
            if (profiles.multiple) data.put("profileId", profile.accountKey).put("profileName", profile.name)
        } else playingSince = 0L
        val stop = runCatching { withContext(Dispatchers.IO) { com.avidviewer.tv.data.AccountApi.playing(token, data, accountApi) } }.getOrNull() ?: return
        playingReported = on
        if (stop && on) stopFromAccount()
    }

    /** The account asked this device to stop (Watching now › Stop stream). */
    private fun stopFromAccount() {
        trail("stream stopped from the account")
        stoppedKey = currentKey; stoppedVod = vodPlaying
        if (screen == Screen.PLAYER) closeFullScreen()
        player.stop()
        playingSince = 0L; playingReported = false
        streamStopped = true
    }
    /** "Back to the guide" on the stopped message. */
    fun dismissStopped() { streamStopped = false }
    /** "Watch again": starts the same stream again. */
    fun watchAgain() {
        streamStopped = false
        val v = stoppedVod
        if (v != null) { playVod(v); return }
        val c = channel(stoppedKey) ?: return
        start(c)
    }

    /** The same provider login: same type, and the same server and username, or the same M3U link. */
    private fun sameLogin(p: Playlist, login: org.json.JSONObject): Boolean {
        val type = login.optString("type", p.type)
        if (type != p.type) return false
        fun host(u: String) = u.trim().lowercase().removeSuffix("/").let { if ("://" in it) it else "http://$it" }
        return if (type == "m3u") login.optString("m3uUrl").trim().isNotEmpty() && login.optString("m3uUrl").trim() == p.m3uUrl.trim()
        else host(login.optString("server")) == host(p.server) && login.optString("username").trim() == p.username.trim() && p.username.isNotBlank()
    }
    private fun loginOf(p: Playlist) = org.json.JSONObject().put("type", p.type).put("server", p.server).put("username", p.username).put("m3uUrl", p.m3uUrl)

    /**
     * 1.15.2: a playlist from the account that was also typed on this device (two copies of one provider) becomes one:
     * the device's own copy, with its favorites and history, takes the account's place, and the account's copy goes.
     */
    private fun mergeDuplicateAccountPlaylists() {
        val pairs = playlists.filter { it.remoteId.isNotEmpty() }.mapNotNull { acct ->
            playlists.firstOrNull { it.remoteId.isEmpty() && it.id != acct.id && sameLogin(it, loginOf(acct)) }?.let { acct to it }
        }
        if (pairs.isEmpty()) return
        for ((acct, mine) in pairs) {
            trail("merged a duplicate account playlist")
            playlists = playlists.map { if (it.id == mine.id) it.copy(remoteId = acct.remoteId, profiles = acct.profiles) else it }
            store.savePlaylists(playlists)
            if (prefs.activePlaylist == acct.id) updatePrefs { it.copy(activePlaylist = mine.id) }
            removePlaylist(acct.id)
            status = "${mine.name} was on this device twice; kept one copy."
        }
        account.playlistsSent = ""
        playlistSaved++
    }

    /** One instruction from the account; returns why it couldn't be done, or null. */
    private suspend fun applyAccountPlaylist(item: com.avidviewer.tv.data.AccountApi.InboxItem, key: com.avidviewer.tv.data.DeviceKey.Pair): String? {
        var existing = playlists.firstOrNull { it.remoteId == item.playlistId }
            // 1.15.1 "Add to account": a playlist added here becomes that account playlist, keeping everything.
            ?: item.localId?.let { lid -> playlists.firstOrNull { it.id == lid && it.remoteId.isEmpty() } }
        if (item.action == "remove") {
            existing?.let { removePlaylist(it.id) }
            return null
        }
        val login = item.payload?.let { env ->
            runCatching { withContext(Dispatchers.Default) { com.avidviewer.tv.data.DeviceKey.open(key, env) } }
                .getOrElse { return "This device couldn't open the login. Enter it again on the website." }
        }
        if (login == null && existing == null) return "The login is missing. Enter it again on the website."
        // 1.15.2: the same playlist typed on this device before becomes the account's one, instead of a second copy.
        if (existing == null && login != null) existing = playlists.firstOrNull { it.remoteId.isEmpty() && sameLogin(it, login) }
        var p = (existing ?: Playlist(id = UUID.randomUUID().toString().take(8), name = item.name, type = item.type))
            .copy(name = item.name.ifBlank { existing?.name ?: "My provider" }, profiles = item.profiles, remoteId = item.playlistId)
        if (login != null) {
            val epg = login.optString("epgUrl").trim()
            val others = p.epgSources.filterNot { it.id == ACCOUNT_GUIDE }
            p = p.copy(
                type = login.optString("type", item.type), server = login.optString("server").trim(), username = login.optString("username").trim(),
                password = login.optString("password"), m3uUrl = login.optString("m3uUrl").trim(),
                epgSources = if (epg.isBlank()) others else listOf(EpgSource(ACCOUNT_GUIDE, "Guide address", epg)) + others,
            )
            val problem = withContext(Dispatchers.IO) {
                if (p.isXtream) Xtream.checkLogin(p) else if (p.m3uUrl.isBlank()) "No playlist link." else null
            }
            if (problem != null) return problem
        }
        val first = playlists.isEmpty()
        playlists = if (existing == null) playlists + p else playlists.map { if (it.id == p.id) p else it }
        store.savePlaylists(playlists)
        playlistSaved++
        when {
            existing == null && first -> {
                updatePrefs { it.copy(activePlaylist = p.id) }
                showWelcome = false
                status = "Added ${p.name} from your account."
                loadActive(refresh = true)
            }
            existing == null -> { status = "Added ${p.name} from your account."; syncAllPlaylists(force = false) }
            login != null -> refreshPlaylist(p.id)
            else -> if (!p.visibleTo(profile) || existing.profiles != p.profiles) { resetVod(); reloadAfterActiveChange() }
        }
        return null
    }

    // ---------------- burn-in guard (1.14.0) ----------------
    /** How long the menus may stay on screen over a playing preview with no button pressed. */
    internal var idleFullScreenMs = 5 * 60_000L
    private var idleFrom = android.os.SystemClock.uptimeMillis()
    /** Counts from now (the app came back on screen), not from a button pressed before it left. */
    fun resetIdle() { idleFrom = android.os.SystemClock.uptimeMillis(); onScreen = true }
    /** The app is on screen (between onStart and onStop). */
    var onScreen = true; private set

    /**
     * Owner request (Nov 16): with a channel playing in a preview and the menus on screen, 5 minutes without a button
     * press goes to full screen, so still menus don't burn into the screen. Back returns to where the viewer was.
     */
    internal fun idleToFullScreen(nowUptime: Long = android.os.SystemClock.uptimeMillis()) {
        if (screen == Screen.PLAYER || showWelcome || vodPlaying != null) return
        val key = currentKey ?: return
        if (player.mediaItemCount == 0 || !player.playWhenReady || playerError != null) return
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return
        if (pendingRestore != null) return
        if (nowUptime - maxOf(lastKeyAt, idleFrom) < idleFullScreenMs) return
        trail("menus idle: full screen")
        openFullScreen(key, if (screen == Screen.SPORTS) PlayContext.SPORTS else PlayContext.GROUP)
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
            if (playContext == PlayContext.SPORTS && lastScreen == Screen.SPORTS) {
                // Back from a game: Sports, with the game (and the channel playing) highlighted again.
                sportsPlayGame?.let { selectedGame = it }
                sportsReturnPending = true
            }
            screen = lastScreen
        }
    }

    // ---------------- Discover (TMDB) ----------------
    fun tmdbKey(): String = tmdbKeyForTests ?: if (prefs.tmdbKeyEnc.isBlank()) "" else Crypto.decrypt(prefs.tmdbKeyEnc)
    /** Tests: a TMDB key without the device's key store. */
    internal var tmdbKeyForTests: String? = null

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
            val region = region(); val services = prefs.myServices.map { it.id }; val family = !prefs.discoverGrownUps
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    when (tab) {
                        "new" -> {
                            val m = Tmdb.newMovies(key, region, services, family); val s = Tmdb.newShows(key, region, services)
                            (m.zip(s).flatMap { listOf(it.first, it.second) } + m.drop(s.size) + s.drop(m.size))
                        }
                        "trending" -> if (family) Tmdb.trendingFamily(key) else Tmdb.trending(key)
                        "theaters" -> if (family) Tmdb.inTheatersFamily(key, region) else Tmdb.inTheaters(key, region)
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

    /** A short note at the bottom of Discover (e.g. after Menu on a title); cleared after a few seconds. */
    var discoverHint by mutableStateOf<String?>(null)

    /**
     * Menu on a Discover title (issue #10): adds it to My List, or takes it off, as Menu does on the
     * Movies and Series screens. Only titles on your provider can go in My List; for the others the
     * note says why and how to find them.
     */
    fun discoverMenu(t: Title) {
        val match = matchVod(t)
        discoverHint = if (match == null) {
            "“${t.name}” isn't on your provider, so it can't go in My List. Press OK for where to watch."
        } else {
            val adding = !inMyList(match.key)
            toggleMyList(match)
            if (adding) "Added “${t.name}” to My List." else "Removed “${t.name}” from My List."
        }
    }

    /** The same title in the user's own provider catalog, if there is one. */
    /** The version of a Discover title to play by default: the first of [versionsOf] (English first). */
    fun matchVod(t: Title): VodItem? = versionsOf(t).firstOrNull()

    /**
     * Every version of a Discover title in your playlists (each language, quality and playlist), English
     * first, then unmarked, multi-language and other languages; titles you've hidden are left out.
     * One indexed lookup per title, remembered until the catalog changes.
     */
    fun versionsOf(t: Title): List<VodItem> = versionsByName(if (t.type == "tv") "series" else "movie", t.name)

    /** The other copies of a Movies / Series title (each listed once): its languages, qualities and playlists. */
    fun versionsOfItem(v: VodItem): List<VodItem> = versionsByName(Catalog.group(v.kind), v.name).ifEmpty { listOf(v) }

    private fun versionsByName(group: String, name: String): List<VodItem> {
        if (matchCacheFor != vodVersion) { versionsCache.clear(); matchCache.clear(); matchCacheFor = vodVersion }
        val k = group + "|" + Tmdb.normalize(name)
        versionsCache[k]?.let { return it }
        val pids = vodPlaylists.map { it.id }
        val found = runCatching { catalog.findAllByName(group, Tmdb.normalize(name), pids) }.getOrDefault(emptyList())
            .sortedBy { com.avidviewer.tv.data.Language.rank(com.avidviewer.tv.data.Language.of(it.name, it.category)) } // stable: list order within a language
        versionsCache[k] = found
        return found
    }

    /** The title page's "versions" list is open for this title. */
    var versionPicker by mutableStateOf<VodItem?>(null)

    /** Another copy chosen on the title page: the page shows that one (its episodes, resume point, My List). */
    fun chooseVersion(v: VodItem) {
        versionPicker = null
        if (v.key != vodDetail?.key) openVodDetail(v)
    }

    fun playMatched(v: VodItem) { if (v.kind == "series") openVodDetail(v) else playVod(v) }

    /** A guide source's id: the time plus a random part, so two added in the same millisecond never share one. */
    private fun newSourceId() = "src-" + System.currentTimeMillis().toString(36) + java.util.UUID.randomUUID().toString().take(4)

    // ---------------- artwork and details from TMDB (Nov 18, 2026: the hero style) ----------------
    /** ▶ from the Movies/Series categories: a poster takes the highlight (the Grid's details sit above the posters). */
    var vodPosterFocus by mutableStateOf(0)

    private val artStore by lazy { com.avidviewer.tv.data.ArtStore(app.filesDir) }
    /** Looked-up artwork by [com.avidviewer.tv.data.ArtStore.key]; a key with a null value has no TMDB match. */
    var art by mutableStateOf<Map<String, com.avidviewer.tv.data.Art?>>(emptyMap()); private set
    private var artJob: Job? = null

    /** The title's TMDB artwork and details, if looked up. */
    fun artOf(v: VodItem): com.avidviewer.tv.data.Art? = art[artStore.key(v)] ?: artStore.get(artStore.key(v))?.second

    /**
     * Looks up a title on TMDB once (the highlighted poster, a title page). Needs the profile's TMDB key; never for a
     * Kids profile, whose titles aren't sent anywhere. One lookup at a time: a newer title replaces a waiting one.
     */
    fun wantArt(v: VodItem) {
        val k = artStore.key(v)
        // Done: artwork with its content rating looked up, or no match on TMDB.
        fun done(a: com.avidviewer.tv.data.Art?) = a == null || a.cert != null
        if (art.containsKey(k) && done(art[k])) return
        val saved = artStore.get(k)
        if (saved != null && (done(saved.second) || kidsMode)) { art = art + (k to saved.second); return }
        if (kidsMode) return
        val key = tmdbKey(); if (key.isBlank()) return
        val type = if (v.kind == "series") "tv" else "movie"
        artJob?.cancel()
        artJob = viewModelScope.launch {
            val had = saved?.second
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    // Saved before content ratings (Nov 21, 2026) with TMDB's id: only the rating is looked up.
                    if (had != null && had.tmdbId > 0) {
                        had.copy(cert = runCatching { com.avidviewer.tv.data.Tmdb.certification(key, type, had.tmdbId, region()) }.getOrNull())
                    } else {
                        val name = if (v.year.isNotBlank() && !v.name.contains("(" + v.year + ")")) v.name + " (" + v.year + ")" else v.name
                        com.avidviewer.tv.data.Tmdb.find(key, name, type)?.let { t ->
                            com.avidviewer.tv.data.Art.from(t, runCatching { com.avidviewer.tv.data.Tmdb.certification(key, type, t.id, region()) }.getOrNull())
                        }
                    }
                }
            }
            if (found.isFailure) return@launch // offline or refused: try again next time
            val a = found.getOrNull()
            withContext(Dispatchers.IO) { artStore.put(k, a) }
            art = art + (k to a)
        }
    }

    /** The content rating to show: the provider's when it gives one, else TMDB's. */
    fun certificationOf(v: VodItem, info: VodInfo? = null): String? =
        info?.certification?.ifBlank { null } ?: artOf(v)?.cert?.let { com.avidviewer.tv.data.Certification.clean(it).ifBlank { it.takeIf { c -> c.length in 1..6 } } }

    /** Tests: puts artwork in place without TMDB. */
    internal fun putArtForTests(v: VodItem, a: com.avidviewer.tv.data.Art?) { artStore.put(artStore.key(v), a); art = art + (artStore.key(v) to a) }

    // ---------------- app updates ----------------
    fun checkForUpdate(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) updateStatus = "Checking for updates…"
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    // Ask the account first whether this device is in the beta (and which build it's offered).
                    account.token?.let { t -> runCatching { com.avidviewer.tv.data.AccountApi.me(t, accountInfo().appVersion, accountApi) }.getOrNull()?.let { m -> withContext(Dispatchers.Main) { noteBeta(m) } } }
                    val pub = Updater.latest()
                    val beta = betaTag?.takeIf { inBeta }?.let { tag -> runCatching { Updater.byTag(tag) }.getOrNull() }
                    pub to beta
                }
            }
            lastUpdateCheck = System.currentTimeMillis()
            r.map { (pub, beta) -> Updater.pick(pub, beta, installed.first) ?: pub }.onSuccess { rel ->
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

    /** Decides whether the update dialog is due now (see [updatePromptAllowed]). */
    internal fun onUpdateFound(at: Long = System.currentTimeMillis()) {
        val rel = update
        val due = rel != null && at >= updateReminder.dueAt(rel, at)
        if (due && !updatePromptAllowed) updatePromptDismissed = false
        updatePromptAllowed = due
    }

    /** "Later" (or Back) on the update dialog: ask again in 24 hours. */
    fun remindAboutUpdateLater(at: Long = System.currentTimeMillis()) {
        update?.let { updateReminder.later(it, at) }
        updatePromptDismissed = true
        updatePromptAllowed = false
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
    /**
     * A game from ESPN and the channels that carry it, in the order shown ([channels]: see [orderForProfile]) and as
     * found ([found], best match first).
     */
    data class SportGame(val game: Game, val channels: List<ChannelMatch>, val found: List<ChannelMatch> = channels)

    /**
     * Nov 21, 2026 (owner): Where to watch lists, for the profile in use, ★ Favorites first, then the teams' own
     * channels (their local or regional networks), then channels in the profile's lists, then everything else. Within
     * each, the best match first, as found (national TV, the guide, event channels, local TV; US before Canada).
     */
    internal fun orderForProfile(found: List<ChannelMatch>): List<ChannelMatch> {
        val favs = prefs.favorites.toHashSet()
        val inLists = prefs.lists.flatMap { it.keys }.toHashSet()
        fun tier(m: ChannelMatch) = when {
            m.key in favs -> 0
            com.avidviewer.tv.data.MatchKind.LOCAL in m.kinds && m.reasons.any { it.contains(" local TV · ") } -> 1
            m.key in inLists -> 2
            else -> 3
        }
        return found.withIndex().sortedWith(compareBy({ tier(it.value) }, { it.index })).map { it.value }.take(12) // Where to watch lists 12
    }

    private fun sportGame(g: Game, found: List<ChannelMatch>) = SportGame(g, orderForProfile(found), found)

    /** Favorites, lists or the profile changed: Where to watch follows. */
    private fun reorderSportChannels() {
        if (sportGames.isNotEmpty()) sportGames = sportGames.map { sportGame(it.game, it.found) }
    }

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
        if (sportGames.isEmpty()) return
        // A match is already running (a guide download often lands while one does): ask for one more
        // pass after it instead of starting another alongside it.
        if (sportsMatchLock.isLocked) { sportsRematchWanted = true; return }
        matchSports(sportGames.map { it.game })
    }

    /** One channel match at a time; requests that come in meanwhile are folded into one more pass. */
    private val sportsMatchLock = kotlinx.coroutines.sync.Mutex()
    private var sportsRematchWanted = false

    /**
     * Games whose channels have been looked for at least once. Until then a game shows "Finding
     * channels…" (and is listed even when games with no channel are hidden), so the schedule appears
     * as soon as ESPN answers instead of after the whole guide has been read.
     */
    var sportsMatchedIds by mutableStateOf<Set<String>>(emptySet()); private set

    private suspend fun matchSports(games: List<Game>) {
        sportsMatchLock.withLock { matchSportsNow(games) }
        if (sportsRematchWanted && sportGames.isNotEmpty()) {
            sportsRematchWanted = false
            matchSports(sportGames.map { it.game })
        }
    }

    private suspend fun matchSportsNow(games: List<Game>) {
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
        // List the games now, with the channels already found for them; matching fills in the rest.
        val before = sportGames.associateBy { it.game.id }
        sportGames = kept.sortedBy { it.start }.map { g -> sportGame(g, before[g.id]?.found.orEmpty()) }
        val hiddenKeys = prefs.hiddenChannels
        val epgMap = prefs.epgMap
        val pls = livePlaylists.filter { it.enabled }
        // Reading the guide can still fail if it is replaced under us (issue #9): try once more, and if
        // that fails too, keep the games and channels already shown. Matching is never worth a crash.
        val found = runCatching { matchChannels(kept, hiddenKeys, epgMap, pls) }
            .recoverCatching { e -> if (e is CancellationException) throw e; matchChannels(kept, hiddenKeys, epgMap, pls) }
        found.exceptionOrNull()?.let { e -> if (e is CancellationException) throw e }
        val (matched, chans) = found.getOrNull() ?: run {
            sportsMatchedIds = kept.mapTo(HashSet()) { it.id } // keep the channels already shown; stop "Finding channels…"
            return
        }
        sportsChannels = chans
        sportGames = kept.sortedBy { it.start }.map { sportGame(it, matched[it.id].orEmpty()) }
        sportsMatchedIds = kept.mapTo(HashSet()) { it.id }
    }

    private suspend fun matchChannels(
        kept: List<Game>,
        hiddenKeys: Set<String>,
        epgMap: Map<String, String>,
        pls: List<Playlist>,
    ): Pair<Map<String, List<com.avidviewer.tv.data.ChannelMatch>>, Map<String, Channel>> =
        withContext(Dispatchers.IO) {
            liveReady()
            val m = com.avidviewer.tv.data.SportsMatcher(kept) { it in hiddenKeys }
            val from = kept.minOf { it.start } - 2 * 3600_000L
            val to = kept.maxOf { it.likelyEnd() } + 3600_000L
            for (p in pls) {
                // Hidden channels and channels in hidden categories are left out from the start, and only the guide
                // listings of channels that can be shown are looked at (Nov 18: most of the time went on the rest).
                live.forEachChannel(p.id) { key, name, group, epg ->
                    if (key !in hiddenKeys && !isGroupHidden(p.id, group)) m.addChannel(p.id, key, name, epg, epgMap[key], group)
                }
                if (m.anyGuide()) live.forEachProgram(p.id, from, to) { e, s0, e0, title, sub -> if (m.wantsGuide(p.id, e)) m.addProgram(p.id, e, s0, e0, title, sub) }
                m.nextPlaylist()
            }
            val r = m.result(max = 40) // ordered for the profile and cut to 12 in sportGame
            val keys = r.values.flatten().map { it.key }.distinct()
            r to live.channelsByKeys(keys).associateBy { it.key }
        }

    /** [games] in the order the Sports list shows them: live, then upcoming by start, then finals newest first (#24). */
    fun sportsListOrder(games: List<SportGame>): List<SportGame> =
        games.filter { it.game.live } + games.filter { !it.game.live && it.game.state == "pre" }.sortedBy { it.game.start } +
            games.filter { !it.game.live && it.game.final }.sortedByDescending { it.game.start }

    /** The games the Sports screen lists (league filter, and the "no channel found" setting). */
    fun sportsVisibleGames(): List<SportGame> = sportGames.filter { g ->
        (sportLeague == null || g.game.league == sportLeague) && (prefs.sportsShowUnmatched || g.channels.isNotEmpty() || g.game.id !in sportsMatchedIds)
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
        if (!p.liveOn || !isOn(p)) return emptyList<Channel>() to emptyMap()
        if (p.id == activePlaylist()?.id) return channels to guide
        return withContext(Dispatchers.IO) { liveReady(); live.channels(p.id) to guideWindow(p.id, null) }
    }

    /** For tests: how many channels of each other playlist are held in memory. */
    internal fun otherLiveSizes(): Map<String, Int> = otherLive.mapValues { it.value.first.size }

    /** Keys in ★ Favorites or any list. */
    private fun listedKeys(pr: Prefs = prefs): Set<String> = (pr.favorites + pr.lists.flatMap { it.keys }).toHashSet()

    /** Another playlist's favorite/list channels and their guide, kept in memory (small). */
    private suspend fun liveFavorites(p: Playlist): Pair<List<Channel>, Map<String, List<Program>>> {
        if (!p.liveOn || !isOn(p)) return emptyList<Channel>() to emptyMap()
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

    /** What the results on the Search screen are for (the box may already hold something newer). */
    var searchedFor by mutableStateOf(""); private set
    private var searchJob: Job? = null

    private var typingJob: Job? = null

    /** The Search box changed: search after a short pause in typing. */
    fun typeSearch(text: String) {
        searchQuery = text
        typingJob?.cancel()
        typingJob = viewModelScope.launch { delay(450); search(text) }
    }

    /** The TV Guide's search box changed: show its results after a short pause in typing. */
    fun typeGuideQuery(text: String) {
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            delay(450)
            val t = text.trim()
            if (t != guideQuery && (t.length >= 2 || t.isEmpty())) guideQuery = t
        }
    }

    fun search(query: String) {
        val q = query.trim()
        searchQuery = query
        if (q.length < 2) { searchJob?.cancel(); searchResults = null; searchedFor = ""; searching = false; return }
        if (q == searchedFor && searchResults != null && searchJob?.isActive != true) return
        searchJob?.cancel()
        // Not what was searched: only its shape, in case the app is closed (CrashLog).
        com.avidviewer.tv.data.CrashLog.note(getApplication(), "Search: ${com.avidviewer.tv.data.SearchText.words(q).size} word(s), ${q.length} letters")
        searchJob = viewModelScope.launch {
            searching = true
            val sq = com.avidviewer.tv.data.SearchText.prepare(q)
            val nowMs = System.currentTimeMillis()
            val out = ArrayList<PlaylistHits>()
            for (p in shownPlaylists) {
                if (!p.liveOn && !p.vodOn) continue
                ensureActive()
                val isActive = p.id == activePlaylist()?.id
                // The playlist being watched is in memory; the others are searched in the database.
                val (chsAll, g) = if (isActive || !p.liveOn || !isOn(p)) liveFor(p) else withContext(Dispatchers.IO) { liveHits(p, q, 50, 60, nowMs) }
                val chs = chsAll.filter { channelAllowed(it) } // a Kids profile: only its categories
                // Titles: searched in the catalog database, not loaded. Every word first, then near matches.
                if (p.vodOn && isOn(p)) importOldTitles(p)
                val vodHitList = if (!p.vodOn || !isOn(p)) emptyList() else withContext(Dispatchers.IO) {
                    fun find(query: Catalog.Query) = (catalog.page(query, 0, 50) + catalog.near(query, 50)).distinctBy { it.key }
                    find(Catalog.Query("movie", listOf(p.id), nameContains = q, kind = "movie")) +
                        find(Catalog.Query("series", listOf(p.id), nameContains = q)) +
                        find(Catalog.Query("movie", listOf(p.id), nameContains = q, kind = "episode"))
                }
                val res = withContext(Dispatchers.Default) {
                    val names = HashMap<String, String>()
                    fun nameOf(c: Channel) = names.getOrPut(c.key) { channelName(c) }
                    val stations = bestChannels(chs, sq, 50)
                        .map { Hit("channel", p.id, nameOf(it), groupName(it.group), it.logo, channelKey = it.key) }
                    val progs = bestPrograms(chs, sq, nowMs, 60) { c -> epgIdOf(c)?.let { g[it] }.orEmpty() }.map { (c, pr) ->
                        val whenText = if (pr.start <= nowMs) "On now" else "${dayLabel(pr.start)} ${fmtTime(pr.start)}"
                        Hit("program", p.id, pr.title + (pr.subtitle?.let { " · $it" } ?: ""), "${nameOf(c)} · $whenText", c.logo, channelKey = c.key)
                    }
                    // Best matches first (provider order kept within each).
                    val vodHits = vodHitList.map { it to com.avidviewer.tv.data.SearchText.score(it.name, sq) }
                        .filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
                    PlaylistHits(
                        playlist = p,
                        stations = stations,
                        programs = progs,
                        movies = vodHits.filter { it.kind == "movie" }.take(50).map { Hit("movie", p.id, it.name, "Movie", it.logo, vod = it) },
                        series = vodHits.filter { it.kind != "movie" }.take(50).map { Hit(it.kind, p.id, it.name, if (it.kind == "series") "Series" else "Episode", it.logo, vod = it) },
                    )
                }
                out.add(res)
            }
            searchResults = out
            searchedFor = q
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
        if (!screenAllowed(target) || (target == Screen.MANAGE && kidsMode)) return
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
            val counts = if (!p.liveOn || !isOn(p)) emptyMap() else withContext(Dispatchers.IO) { liveReady(); live.groupCounts(p.id) }.toMap(LinkedHashMap())
            val list = counts.filterKeys { !isGroupHidden(p.id, it) }.map { (raw, n) -> GroupInfo(raw, groupName(raw), n, false) }
            playlistGroups = playlistGroups + (p.id to list)
        }
    }

    /** Picking a category: switch playlist if needed, hide the menu and show categories as the left nav. */
    /** ▶ from the category list: into the guide on the first channel on screen, or another category as OK opens it. */
    fun intoGuide(group: String) {
        if (group == FAV_GROUP || isListGroup(group)) ensureFavoriteData()
        if (group != selectedGroup) { chooseGroup(activePlaylist()?.id ?: "", group); return }
        if (channelsIn(group).isEmpty()) return
        guideFocusMode = GuideFocus.VISIBLE
        guideFocusTick++
    }

    fun chooseGroup(pid: String, raw: String) {
        // BUG-045: OK on a category comes from the category list, not the suggestions row. A flag left set when
        // that row went away made the guide keep the highlight off the channels, so OK seemed to do nothing.
        suggestionsFocused = false
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
    /**
     * The viewer is in the TV Guide itself (channels, programmes, preview): it then fills the screen. Set when the guide
     * takes the highlight; cleared only when the main menu, the playlist list or the category list takes it (Left/Back
     * also set [navFocusKey]). Leaving for full screen or a dialog doesn't clear it, so coming back doesn't first draw
     * the menu and categories and then fold them away (Nov 18 redraw audit, R-1). Starts set: the app opens in the guide.
     */
    var guideHasFocus by mutableStateOf(true)
    /** The guide is drawn full width (main menu and category list hidden): 2 hours across the screen. Worked out in the
     *  same frame as the layout, so the guide's time scale never lags a frame behind it (R-2). */
    val guideFullLayout: Boolean
        get() = (screen == Screen.LIVE || screen == Screen.GUIDE) && navMode == NavMode.GROUPS && selectedGroup != null &&
            guideHasFocus && navFocusKey == null
    /** The guide lost the highlight only for a moment: full screen, or a dialog about a channel (R-1). */
    fun guideAwayBriefly(): Boolean = screen == Screen.PLAYER || channelMenu != null || matchingChannel != null || listPickerKey != null
    /** The width of the guide's programme area last measured, full width and not (R-3: the first frame uses it). */
    var guideGridWidthFull = 0f
    var guideGridWidthNarrow = 0f

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
    // Movies / Series: Back from the posters goes to the category list first (on the current category),
    // then the main menu. (It used to skip the category list.)
    else if ((screen == Screen.MOVIES || screen == Screen.SERIES) && vodDetail == null && vodOnPoster) { vodOnPoster = false; vodCatFocus++; true }
    else when (navMode) {
        NavMode.GROUPS -> {
            if (!inGroupNav && (screen == Screen.GUIDE || screen == Screen.LIVE) && selectedGroup != null) {
                navFocusKey = selectedGroup
            } else if ((screen == Screen.GUIDE || screen == Screen.LIVE) && livePlaylists.size <= 1) {
                // With one playlist the playlist list only repeats the same name, so Back from the categories goes
                // straight to the main menu: every Back press now visibly moves up a level (BUG-019, #7).
                inGroupNav = false; inPlaylistTree = false; navFocusKey = null; navMode = NavMode.FULL
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
                withContext(Dispatchers.IO) { XtreamVod.episodes(p, item.seriesId ?: "") }.also { noteSeriesEpisodes(item, it) }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun closeSeries() { seriesOpen = null; episodes = null }

    fun playEpisode(e: Episode) {
        val s = seriesOpen ?: return
        playVod(VodItem(episodeKey(s, e), s.playlistId, "episode", "${s.name} · S${e.season} E${e.number} · ${e.title}", s.logo, e.url))
    }

    fun playVod(item: VodItem, fromStart: Boolean = false) {
        if (linkRequired) return // nothing plays until the device is linked (1.22.0)
        if (expiredBlocks(item.playlistId) { playVod(item, fromStart) }) return
        recordWatch()
        if (screen != Screen.PLAYER) lastScreen = screen
        vodPlaying = item
        playerError = null
        playback.started(item.key, live = false)
        decoders.startingNow(item.key); decoderRetried = null
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
        if (pos > 60_000) notePlace(item.key, dur) // 1.17.0: when it changed and how long it is, for the other TVs
        // Played to (nearly) the end: watched, so the series page opens on the next episode (BUG-020, #8).
        if (dur > 0 && pos >= dur * 0.95) { watched.remove(item.key); watched.add(item.key); store.saveWatched(watched) }
    }

    /** The key an episode of [series] is played (and remembered) under. */
    /**
     * An episode's key for its place and "watched": series, season, episode. (Up to 1.17.0 the series page used the
     * provider's episode id, which says nothing about the series: such episodes never reached Continue watching or
     * the account. Old keys move over when the series page loads, see [noteSeriesEpisodes].)
     */
    fun episodeKey(series: VodItem, e: Episode) = "${series.key}:${seasonNum(e.season)}:${e.number}"
    private fun seasonNum(s: String) = s.trim().toIntOrNull() ?: Regex("\\d+").find(s)?.value?.toIntOrNull() ?: 0

    fun isWatched(key: String) = key in watched

    /** For tests: marks an episode watched to the end, as the player does. */
    internal fun markWatched(key: String) { watched.add(key); store.saveWatched(watched); notePlace(key, 0) }

    /**
     * Where a series page starts: Season 1, Episode 1 if nothing has been watched; otherwise the episode after
     * the last one watched to the end, or the one part-watched if that came later.
     */
    fun nextUpEpisode(series: VodItem, eps: List<Episode>): Episode? {
        if (eps.isEmpty()) return null
        var at = -1
        eps.forEachIndexed { i, e -> val k = episodeKey(series, e); if (k in watched || resumeOf(k) > 0) at = i }
        if (at < 0) return eps.first()
        val e = eps[at]
        return if (episodeKey(series, e) in watched) eps.getOrNull(at + 1) ?: e else e
    }

    /** Every movie (or series) of the switched-on playlists, hidden ones included. */
    /** Playlists whose titles the Movies and Series screens show, in order. */
    private fun vodPids(): List<String> = vodPlaylists.map { it.id }

    private fun titles(q: com.avidviewer.tv.data.Catalog.Query): List<VodItem> = com.avidviewer.tv.data.PagedTitles(catalog, q)

    /** Every movie (or series) of the switched-on playlists, hidden ones included; read a page at a time. */
    fun vodAll(kind: String): List<VodItem> = vodAllMemo.getOrPut(kind) { Memo() }.get(vodVersion, vodPids().joinToString(",")) {
        titles(com.avidviewer.tv.data.Catalog.Query(vodKindKey(kind), vodPids(), includeHidden = true))
    }

    /**
     * Titles whose name contains every word of [q] (the Movies / Series search). When few do, near
     * matches follow once [loadVodNear] has found them: a typo ("godfathr") or a word left out.
     */
    fun vodSearch(kind: String, q: String): List<VodItem> {
        val t = q.trim()
        vodNear[kind]?.takeIf { it.first == t && it.third == vodVersion }?.second?.let { return it }
        return titles(vodSearchQuery(kind, t))
    }

    private fun vodSearchQuery(kind: String, t: String) =
        com.avidviewer.tv.data.Catalog.Query(vodKindKey(kind), vodPids(), nameContains = t, grouped = true)

    /** Per kind: the search, its titles with near matches (null: enough exact ones), and the catalog version. */
    private val vodNear = androidx.compose.runtime.mutableStateMapOf<String, Triple<String, List<VodItem>?, Int>>()

    /** The Movies / Series search box settled on [q]: look for near matches when fewer than 30 titles hold every word. */
    suspend fun loadVodNear(kind: String, q: String) {
        val t = q.trim()
        if (t.length < 2) return
        val version = vodVersion
        if (vodNear[kind]?.let { it.first == t && it.third == version } == true) return
        val query = vodSearchQuery(kind, t)
        val list = withContext(Dispatchers.IO) {
            runCatching {
                if (catalog.count(query) >= 30) null
                else {
                    val sq = com.avidviewer.tv.data.SearchText.prepare(t)
                    (catalog.page(query, 0, 30) + catalog.near(query, 100)).distinctBy { it.key }
                        .map { it to com.avidviewer.tv.data.SearchText.score(it.name, sq) }
                        .sortedByDescending { it.second }.map { it.first }
                }
            }.getOrNull()
        }
        vodNear[kind] = Triple(t, list, version)
    }

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

    fun isVodCatHidden(kind: String, pid: String, cat: String) = vodCatKey(kind, pid, cat).let { k ->
        k in prefs.vodHiddenCats || (kidsMode && (k !in profile.allowedVodCats || Kids.isAdult(cat)))
    }

    fun isVodHidden(v: VodItem) = v.key in prefs.vodHiddenTitles || isVodCatHidden(v.kind, v.playlistId, v.category.ifBlank { "Other" })

    /** What the Movies / Series screens show: hidden titles and categories left out. */
    fun vodOf(kind: String): List<VodItem> = vodOfMemo.getOrPut(kind) { Memo() }.get(vodVersion, vodPids().joinToString(",")) {
        titles(com.avidviewer.tv.data.Catalog.Query(vodKindKey(kind), vodPids(), grouped = true))
    }

    fun vodCatName(kind: String, cat: String) = prefs.vodCatNames[vodKindKey(kind) + "|" + cat] ?: genreOf(cat)?.let { genreCatName(it) } ?: cat

    /** Orders category names by the user's order (new ones keep the provider's order, after). */
    fun orderVodCats(kind: String, names: List<String>): List<String> {
        val ord = prefs.vodCatOrder[vodKindKey(kind)] ?: return names
        return ord.filter { it in names } + names.filterNot { it in ord }
    }

    fun vodCategories(kind: String): List<Pair<String, Int>> = vodCatsMemo.getOrPut(kind) { Memo() }.get(vodVersion, vodPids().joinToString(","), prefs, genreVersion, kidsMode) {
        if (genresOn()) computeGenreCategories(kind) else computeVodCategories(kind)
    }

    // ---------------- Movies and Series by TMDB genre (Nov 21, 2026) ----------------
    /** Whether this profile lists TMDB genres in place of the provider's categories (never on a Kids profile). */
    fun genresOn(): Boolean = prefs.genreSort && !kidsMode && prefs.tmdbKeyEnc.isNotBlank()

    /** The genre id of a genre category ("genre:28"), or null for a provider category. */
    fun genreOf(cat: String): Int? = if (cat.startsWith(GENRE_CAT)) cat.removePrefix(GENRE_CAT).toIntOrNull() else null

    private fun genreCatName(id: Int) = when (id) {
        com.avidviewer.tv.data.Catalog.GENRE_OTHER -> "Other (not on TMDB)"
        com.avidviewer.tv.data.Catalog.GENRE_PENDING -> "Not sorted yet"
        com.avidviewer.tv.data.Catalog.GENRE_TRENDING -> "Trending this week"
        com.avidviewer.tv.data.Catalog.GENRE_TOP -> "Top rated"
        else -> com.avidviewer.tv.data.Art.genreLabel(id)
    }

    /**
     * Trending this week and Top rated (titles on the user's playlists), then the genres by name (or the user's
     * order), then Other, then titles not looked up yet. Each title counted once.
     */
    private fun computeGenreCategories(kind: String): List<Pair<String, Int>> {
        val g = vodKindKey(kind)
        val counts = runCatching { catalog.genreCounts(g, vodPids()) }.getOrDefault(emptyMap())
        val hidden = prefs.vodHiddenGenres
        val named = counts.filter { it.key > 0 && it.value > 0 && "$g|${it.key}" !in hidden }
            .map { GENRE_CAT + it.key }.sortedBy { vodCatName(kind, it).lowercase() }
        val other = com.avidviewer.tv.data.Catalog.GENRE_OTHER; val pending = com.avidviewer.tv.data.Catalog.GENRE_PENDING
        val head = listOf(com.avidviewer.tv.data.Catalog.GENRE_TRENDING, com.avidviewer.tv.data.Catalog.GENRE_TOP)
            .filter { (counts[it] ?: 0) > 0 && "$g|$it" !in hidden }.map { GENRE_CAT + it }
        val tail = listOfNotNull(
            (counts[other] ?: 0).takeIf { it > 0 && "$g|$other" !in hidden }?.let { GENRE_CAT + other },
            (counts[pending] ?: 0).takeIf { it > 0 }?.let { GENRE_CAT + pending },
        )
        return (head + orderVodCats(kind, named) + tail).map { it to (counts[genreOf(it)] ?: 0) }
    }

    /** Bumped as lookups are saved, so the genre list and counts follow. */
    var genreVersion by mutableStateOf(0); private set
    /** (titles sorted, all titles) while genres are being looked up; null when nothing is running. */
    var genreProgress by mutableStateOf<Pair<Int, Int>?>(null); private set
    private var genreJob: Job? = null
    /** Pause between TMDB lookups (tests: 0). */
    internal var genreDelayMs = 250L
    /** Looks a title up on TMDB: its genre ids, or null when TMDB has no match. Tests replace it. */
    internal var genreFinder: (key: String, name: String, type: String) -> com.avidviewer.tv.data.Catalog.GenreFound? = { key, name, type ->
        com.avidviewer.tv.data.Tmdb.find(key, name, type)?.let { com.avidviewer.tv.data.Catalog.GenreFound(it.genres, it.rating, it.votes) }
    }
    /** This week's trending titles' names, for "movie" or "tv". Tests replace it. */
    internal var trendingFinder: (key: String, type: String) -> List<String> = { key, type ->
        com.avidviewer.tv.data.Tmdb.trendingWeek(key, type).map { it.name }
    }
    private var trendingAt = 0L

    /** Turns sorting by genre on or off for this profile; on starts the lookups. */
    fun setGenreSort(on: Boolean) {
        updatePrefs { it.copy(genreSort = on) }
        vodCategory = vodCategory.mapValues { (_, c) -> if (c in VOD_SPECIALS) c else "All" }
        if (on) startGenreLookups() else { genreJob?.cancel(); genreProgress = null }
    }

    fun showHiddenGenres() = updatePrefs { it.copy(vodHiddenGenres = emptySet()) }

    /**
     * Looks up, newest first, every movie and series not sorted yet: one title at a time with a short pause, each
     * title once for all its copies. Only the title's name and year are sent, with the profile's own TMDB key; never
     * from a Kids profile. Stops when offline or refused, and carries on the next time Movies or Series opens.
     */
    fun startGenreLookups() {
        if (genreJob?.isActive == true || !genresOn()) return
        val key = tmdbKey(); if (key.isBlank()) return
        val pids = vodPids(); if (pids.isEmpty()) return
        genreJob = viewModelScope.launch {
            try {
                afterQuietStart()
                // Trending this week, at most every 6 hours (matched to the playlists by name).
                if (System.currentTimeMillis() - trendingAt > 6 * 3600_000L) withContext(Dispatchers.IO) {
                    runCatching {
                        val keys = listOf("movie" to "movie", "tv" to "series").flatMap { (type, g) ->
                            trendingFinder(key, type).map { g + "|" + com.avidviewer.tv.data.Tmdb.normalize(it) }.filter { !it.endsWith("|") }
                        }
                        catalog.saveTrending(keys); trendingAt = System.currentTimeMillis()
                    }
                }
                genreVersion++
                while (genresOn() && vodPids() == pids) {
                    ensureActive()
                    genreProgress = withContext(Dispatchers.IO) { runCatching { catalog.genreProgress(pids) }.getOrNull() }
                    // A batch at a time off the screen's thread; stops when offline or the key is refused.
                    val more = withContext(backgroundThread) {
                        val batch = runCatching { catalog.pendingGenres(pids, 40) }.getOrDefault(emptyList())
                        for (t in batch) {
                            ensureActive()
                            while (videoBusy) delay(5_000) // the TV's processor goes to the video first
                            val name = if (t.year.isNotBlank() && !t.name.contains("(" + t.year.take(4) + ")")) t.name + " (" + t.year.take(4) + ")" else t.name
                            val found = runCatching { genreFinder(key, name, if (t.group == "series") "tv" else "movie") }
                            if (found.isFailure) return@withContext false // try again next time
                            runCatching { catalog.saveGenres(t.gk, found.getOrNull()) }
                            if (genreDelayMs > 0) delay(genreDelayMs)
                        }
                        batch.isNotEmpty()
                    }
                    genreVersion++
                    if (!more) break
                }
            } finally {
                genreVersion++
                genreProgress = null
            }
        }
    }

    /** Tests: whether lookups are running. */
    internal fun genreLookupsRunning() = genreJob?.isActive == true

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
        genreOf(cat)?.let { id ->
            updatePrefs { it.copy(vodHiddenGenres = it.vodHiddenGenres + (vodKindKey(kind) + "|" + id)) }
            if (vodCategory[kind] == cat) vodCategory = vodCategory + (kind to "All")
            return
        }
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

    fun vodItems(kind: String): List<VodItem> = vodItemsNow(kind).also { ensureGrouped() }

    private fun vodItemsNow(kind: String): List<VodItem> =
        vodItemsMemo.getOrPut(kind) { Memo() }.get(vodVersion, vodPids().joinToString(","), prefs, vodCategory[kind] ?: "All", resume, genreVersion) { computeVodItems(kind) }
    private fun computeVodItems(kind: String): List<VodItem> {
        val g = vodKindKey(kind)
        return when (val c = vodCategory[kind] ?: "All") {
            "All" -> vodOf(kind)
            "Recently added" -> titles(com.avidviewer.tv.data.Catalog.Query(g, vodPids(), newestFirst = true, limit = 200, grouped = true))
            "My List" -> prefs.myList.filter { if (kind == "series") it.kind == "series" else it.kind != "series" }
            "Continue watching" -> continueKeys().let { ks -> val at = ks.withIndex().associate { it.value to it.index }
                withLiveCard(catalog.byKeys(ks).filter { Catalog.group(it.kind) == g && !isVodHidden(it) }.sortedBy { at[it.key] ?: Int.MAX_VALUE }) }
            else -> if (genreOf(c) != null) titles(com.avidviewer.tv.data.Catalog.Query(g, vodPids(), grouped = true, genre = genreOf(c)))
                else vodPrefetched.remove(prefetchKey(kind, c)) ?: titles(com.avidviewer.tv.data.Catalog.Query(g, vodPids(), category = c, grouped = true))
        }
    }

    // DATA-06: a category that keeps the highlight for a moment has its count and first page of posters read in the
    // background, so OK shows it at once. Moving on cancels it; only the last few are kept.
    private val vodPrefetched = object : LinkedHashMap<String, List<VodItem>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<VodItem>>?) = size > 3
    }
    private var vodPrefetchJob: Job? = null
    private fun prefetchKey(kind: String, cat: String) = "$kind|$cat|$vodVersion|${vodPids().joinToString(",")}|${prefs.vodHiddenTitles.size}"

    fun prefetchVodCategory(kind: String, cat: String) {
        vodPrefetchJob?.cancel()
        if (genreOf(cat) != null) return
        val key = prefetchKey(kind, cat)
        if (key in vodPrefetched) return
        val q = com.avidviewer.tv.data.Catalog.Query(vodKindKey(kind), vodPids(), category = cat, grouped = true)
        vodPrefetchJob = viewModelScope.launch {
            delay(250)
            val list = withContext(Dispatchers.IO) { titles(q).also { if (it.size > 0) it[0] } }
            if (prefetchKey(kind, cat) == key) vodPrefetched[key] = list
        }
    }

    /** For tests: whether a category's first page has been read ahead. */
    internal fun vodPrefetchedFor(kind: String, cat: String) = prefetchKey(kind, cat) in vodPrefetched

    fun openVodDetail(item: VodItem) {
        if (item.kind == LIVE_KIND) { watchLiveCard(item); return }
        screen = if (item.kind == "series") Screen.SERIES else Screen.MOVIES
        if (navMode == NavMode.FULL || navMode == NavMode.GROUPS) navMode = NavMode.COLLAPSED
        vodDetail = item; vodInfo = null; detailEpisodes = null; detailSeason = null
        val p = playlists.firstOrNull { it.id == item.playlistId }
        if (p == null || !p.isXtream) { vodInfo = VodInfo(); detailEpisodes = emptyList(); return }
        viewModelScope.launch {
            try {
                if (item.kind == "series") {
                    val (info, eps) = withContext(Dispatchers.IO) { XtreamVod.seriesInfo(p, item.seriesId ?: "") }
                    noteSeriesEpisodes(item, eps)
                    vodInfo = info; detailEpisodes = eps
                    // Coming back from an episode keeps its season; opening the page goes to the next one up.
                    val back = lastEpisode?.takeIf { l -> eps.any { it.key == l.key && it.season == l.season && it.number == l.number } }
                    detailSeason = (back ?: nextUpEpisode(item, eps))?.season ?: eps.firstOrNull()?.season
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
        playVod(VodItem(episodeKey(s, e), s.playlistId, "episode", "${s.name} · S${e.season} E${e.number} · ${e.title}", s.logo, e.url), fromStart)
    }

    fun seek(ms: Long) {
        val d = player.duration
        val target = (player.currentPosition + ms).coerceAtLeast(0L)
        player.seekTo(if (d > 0) target.coerceAtMost(d - 1000) else target)
    }

    // ---------------- no video on screen, no stream (Nov 21, 2026) ----------------
    /** Whether video is on screen: full screen, or the Guide or Sports with the preview (not with the main menu open). */
    private fun videoOnScreen(): Boolean = when (screen) {
        Screen.PLAYER -> true
        Screen.GUIDE, Screen.SPORTS -> navMode != NavMode.FULL
        else -> false
    }

    /** Twice a second: once no video has been on screen long enough, the live stream stops. */
    private fun watchVideoOnScreen() {
        viewModelScope.launch {
            var hiddenSince = 0L
            while (true) {
                delay(500)
                screenNow = (if (navMode == NavMode.FULL && screen != Screen.PLAYER) "the main menu over " else "") + screen.name.lowercase().replace('_', ' ')
                if (!stopWhenNoVideo || videoOnScreen() || streamParked || vodPlaying != null) { hiddenSince = 0L; continue }
                val now = android.os.SystemClock.uptimeMillis()
                if (hiddenSince == 0L) hiddenSince = now
                val menu = (screen == Screen.GUIDE || screen == Screen.SPORTS) && navMode == NavMode.FULL
                if (now - hiddenSince >= (if (menu) PARK_MENU_MS else PARK_SCREEN_MS)) { hiddenSince = 0L; parkStream() }
            }
        }
    }

    /** Waits for the end of the quiet start (Nov 21, 2026). */
    internal suspend fun afterQuietStart() = quietStartDone.await()

    private fun markStart(label: String) { startupMarks.add(label to android.os.SystemClock.uptimeMillis() - startedUptime) }

    /**
     * Start-up work, one job at a time ([backgroundTurn]); its time goes in the playback log's start-up entry while
     * the first round after the quiet start runs. [now]: a download the viewer asked for doesn't wait its turn.
     */
    private suspend fun inTurn(label: String, now: Boolean = false, block: suspend () -> Unit) {
        if (now) { block(); return }
        backgroundTurn.withLock {
            val t = android.os.SystemClock.uptimeMillis()
            try { block() } finally {
                if (!startupLogged) startupJobs.add("$label ${secs(android.os.SystemClock.uptimeMillis() - t)}")
            }
        }
    }
    private val startupJobs = java.util.Collections.synchronizedList(ArrayList<String>())
    @Volatile private var startupLogged = false
    private fun secs(ms: Long) = if (ms < 10_000) String.format(java.util.Locale.US, "%.1f s", ms / 1000.0) else "${ms / 1000} s"

    /** Ends the quiet start once the video has played steadily, nothing has played for a while, or at the latest. */
    private fun watchQuietStart() {
        if (android.os.Build.FINGERPRINT == "robolectric" || playlists.none { it.enabled }) { quietStartDone.complete(Unit); return }
        viewModelScope.launch {
            var playingFrom = 0L
            var why: String? = null
            while (why == null) {
                delay(500)
                val now = android.os.SystemClock.uptimeMillis(); val since = now - startedUptime
                val steady = player.isPlaying && playerError == null
                playingFrom = if (steady) (if (playingFrom == 0L) now else playingFrom) else 0L
                val nothing = player.mediaItemCount == 0 || streamParked || choosingProfile || linkRequired
                why = when {
                    playingFrom > 0 && now - playingFrom >= QUIET_PLAYING_MS -> "the video played steadily for ${QUIET_PLAYING_MS / 1000} s"
                    nothing && since >= QUIET_IDLE_MS -> "nothing was playing"
                    since >= QUIET_MAX_MS -> "${QUIET_MAX_MS / 1000} s passed"
                    else -> null
                }
            }
            markStart("quiet start over")
            quietStartDone.complete(Unit)
            val marks = startupMarks.toList().joinToString(" · ") { "${it.first} ${secs(it.second)}" }
            val pic = if (firstPictureAt > 0) " · first picture ${secs(firstPictureAt)}" else ""
            playbackLog.add(com.avidviewer.tv.data.PlaybackLog.DEVICE, "", "Start-up: $marks$pic ($why)")
        }
    }

    /** After the first round of downloads: how long each start-up job took, in the playback log. */
    private fun logStartupWork() {
        if (startupLogged || !quietStartDone.isCompleted) return
        startupLogged = true
        val jobs = startupJobs.toList()
        if (jobs.isEmpty()) return
        playbackLog.add(com.avidviewer.tv.data.PlaybackLog.DEVICE, "", "After start-up, one at a time: " + jobs.joinToString(" · ") +
            " · all done ${secs(android.os.SystemClock.uptimeMillis() - startedUptime)} after start")
    }

    /** Stops the live stream (the channel is remembered, so OK on it plays it again). */
    private fun parkStream() {
        if (player.mediaItemCount == 0 && !isStreaming()) return
        trail("stream stopped: no video on screen ($screenNow)")
        recordWatch()
        playback.finished()
        player.stop(); player.clearMediaItems()
        streamParked = true
    }

    private fun start(c: Channel) {
        streamParked = false
        if (choosingProfile || linkRequired) { currentKey = c.key; return } // nothing plays behind Who's watching? or Link this device
        if (!channelAllowed(c)) return
        if (expiredBlocks(c.key.substringBefore(':')) { start(c) }) { currentKey = c.key; return }
        recordWatch()
        vodPlaying = null
        currentKey = c.key
        playerError = null
        val item = MediaItem.Builder().setUri(c.url).apply {
            if (c.url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        playback.started(c.key, live = true)
        decoders.startingNow(c.key); decoderRetried = null
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

    /**
     * Reopens the stream: a live channel at the live point, a movie or episode where it was. The picture is back
     * in a second or two, instead of staying frozen.
     */
    private fun reconnectStream(live: Boolean, full: Boolean = false, otherDecoder: Boolean = false) {
        if (otherDecoder) playingKey()?.let { decoders.switch(it) }
        val pos = player.currentPosition
        player.stop()
        // #23: a second reconnect within a minute starts the player afresh with the same stream (decoders and all).
        if (full) player.currentMediaItem?.let { item -> player.clearMediaItems(); player.setMediaItem(item) }
        player.prepare()
        if (live) player.seekToDefaultPosition() else player.seekTo(pos)
        player.playWhenReady = true
        showNotice("Reconnecting…", 2000)
    }

    private fun playerFailed(error: PlaybackException) {
        if (recoverFromDecoderError(error)) return
        val (text, refused) = playErrorText(error)
        playerError = text
        // #29: a live channel the provider refused (or whose server can't be found) is marked in the guide.
        if (refused && vodPlaying == null) currentKey?.let { noteFailedChannel(it, true) }
    }
    internal fun playerFailedForTests(error: PlaybackException) = playerFailed(error)

    /** The key of what's playing: the movie or episode, else the channel. */
    private fun playingKey(): String? = vodPlaying?.key ?: currentKey

    /** #32: the stream that a decoding error was already retried for (with the other decoder) since it started. */
    private var decoderRetried: String? = null

    /**
     * #32: a decoding error (ERROR_CODE_DECODING_FAILED and the like) used to stop the channel for good: every start
     * failed the same way, for 12 hours in one log. Now the stream restarts once on the TV's other video decoder,
     * which is kept for it. If that fails too, the error shows, and the next try starts on the decoder it began with.
     */
    private fun recoverFromDecoderError(error: PlaybackException): Boolean {
        if (!com.avidviewer.tv.data.Decoders.isDecoderError(error.errorCode)) return false
        val key = playingKey() ?: return false
        if (decoderRetried == key) { decoders.switch(key); decoderRetried = null; return false }
        decoderRetried = key
        val other = decoders.switch(key)
        playback.decoderError(error.errorCodeName, other)
        reconnectStream(live = vodPlaying == null, full = true)
        return true
    }

    fun stop() {
        playback.finished()
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

    /** The game playing from Sports, with its channels (best first), if it's still listed. */
    private fun playingGame(): SportGame? = sportGames.firstOrNull { it.game.id == sportsPlayGame }

    fun contextLabel(): String {
        if (playContext == PlayContext.SPORTS) {
            playingGame()?.takeIf { it.channels.isNotEmpty() }?.let { g ->
                val i = g.channels.indexOfFirst { it.key == currentKey }
                val name = g.game.away.name + " at " + g.game.home.name
                return name + if (i >= 0) " · channel ${i + 1} of ${g.channels.size}" else ""
            }
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
            // Channel Up/Down goes through the channels found for the game being watched (issue #12).
            playingGame()?.takeIf { it.channels.isNotEmpty() }?.let { g ->
                val chans = g.channels
                if (chans.size == 1) { flashOsd(contextLabel() + " · the only channel found for this game"); return }
                var i = chans.indexOfFirst { it.key == currentKey }
                i = if (i < 0) 0 else (i + dir + chans.size) % chans.size
                sportsChannel(chans[i].key)?.let { start(it) }
                flashOsd(contextLabel())
                return
            }
            // No game to stay on (it dropped off the list): move between live games, each on its best channel.
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
        onScreen = false
        recordWatch()
        if (prefs.clearCacheOnExit) store.clearCache()
    }

    override fun onCleared() {
        recordWatch()
        player.release()
        super.onCleared()
    }
}
