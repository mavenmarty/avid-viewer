package com.avidviewer.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saves everything in the app's private internal storage (survives restarts and app updates,
 * removed only by uninstall or "Clear storage"). Guide and channel lists live in files/, not
 * cache/, so "clear cache on exit" never removes them.
 */
class Store(private val context: Context) {
    private val dir: File get() = context.filesDir
    internal val dataDir: File get() = File(dir, "data").apply { mkdirs() }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    /**
     * Writes a big list one item at a time. Building the whole file as one string first needs
     * several times the file's size in memory, which a Fire TV Stick can't spare for a large catalog.
     */
    private fun <T> writeArrayAtomic(f: File, list: List<T>, toJson: (T) -> JSONObject) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.bufferedWriter(Charsets.UTF_8, 64 * 1024).use { w ->
            w.write("[")
            list.forEachIndexed { i, item -> if (i > 0) w.write(","); w.write(toJson(item).toString()) }
            w.write("]")
        }
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() } }
    }

    /** Reads a big list one item at a time (same file format as before), so memory stays small. */
    private fun <T> readArray(f: File, fromJson: (JSONObject) -> T): List<T> {
        if (!f.exists()) return emptyList()
        return try {
            android.util.JsonReader(f.bufferedReader(Charsets.UTF_8, 64 * 1024)).use { r ->
                val out = ArrayList<T>()
                r.beginArray()
                while (r.hasNext()) {
                    val o = readJsonValue(r)
                    if (o is JSONObject) runCatching { fromJson(o) }.getOrNull()?.let { out.add(it) }
                }
                r.endArray()
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun readJsonValue(r: android.util.JsonReader): Any? = when (r.peek()) {
        android.util.JsonToken.BEGIN_OBJECT -> JSONObject().also { o -> r.beginObject(); while (r.hasNext()) { val n = r.nextName(); o.put(n, readJsonValue(r) ?: JSONObject.NULL) }; r.endObject() }
        android.util.JsonToken.BEGIN_ARRAY -> JSONArray().also { a -> r.beginArray(); while (r.hasNext()) a.put(readJsonValue(r) ?: JSONObject.NULL); r.endArray() }
        android.util.JsonToken.STRING -> r.nextString()
        android.util.JsonToken.NUMBER -> r.nextString().let { s -> s.toLongOrNull() ?: s.toDoubleOrNull() ?: s }
        android.util.JsonToken.BOOLEAN -> r.nextBoolean()
        android.util.JsonToken.NULL -> { r.nextNull(); null }
        else -> { r.skipValue(); null }
    }

    // ---- playlists (passwords encrypted) ----
    fun loadPlaylists(): List<Playlist> {
        val f = File(dir, "playlists.json")
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            List(a.length()) {
                val o = a.getJSONObject(it)
                Playlist.extraFrom(o, Playlist.guideFrom(o, Playlist(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    type = o.getString("type"),
                    server = o.optString("server"),
                    username = o.optString("username"),
                    password = Crypto.decrypt(o.optString("password")),
                    m3uUrl = o.optString("m3uUrl"),
                    epgUrl = o.optString("epgUrl"),
                    enabled = o.optBoolean("enabled", true),
                    liveOn = o.optBoolean("liveOn", true),
                    vodOn = o.optBoolean("vodOn", true),
                )))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePlaylists(list: List<Playlist>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                Playlist.extraJson(it, it.guideJson(JSONObject())).put("id", it.id).put("name", it.name).put("type", it.type)
                    .put("server", it.server).put("username", it.username)
                    .put("password", Crypto.encrypt(it.password))
                    .put("m3uUrl", it.m3uUrl).put("epgUrl", it.epgUrl)
                    .put("enabled", it.enabled).put("liveOn", it.liveOn).put("vodOn", it.vodOn)
            )
        }
        writeAtomic(File(dir, "playlists.json"), a.toString())
    }

    // ---- profiles ----
    fun loadProfiles(): ProfileSet = runCatching {
        val f = File(dir, "profiles.json")
        if (f.exists()) ProfileSet.fromJson(JSONObject(f.readText())) else ProfileSet()
    }.getOrDefault(ProfileSet())

    /** Nothing saved yet: a new install (the app had no playlists, settings or profiles). */
    fun isNewInstall(): Boolean = listOf("prefs.json", "playlists.json", "profiles.json").none { File(dir, it).exists() }

    fun saveProfiles(s: ProfileSet) = writeAtomic(File(dir, "profiles.json"), s.toJson().toString())

    /**
     * Whose personal data the prefs, history, resume points and watched episodes below are. The owner's
     * stay where they always were; another profile's are under profiles/<id>/ ([Profile]).
     */
    @Volatile var profileId: String = loadProfiles().active

    private fun profileDir(id: String) = File(dir, "profiles/$id").apply { mkdirs() }
    private fun personalFile(name: String, id: String = profileId) = if (id == Profile.OWNER) File(dir, name) else File(profileDir(id), name)

    /** A profile's personal prefs (favorites, lists, hidden items…), or null if it has none yet. */
    fun loadPersonal(id: String): JSONObject? = onPrefsThread {
        runCatching {
            if (id == Profile.OWNER) personalOf(loadPrefsFile())
            else File(profileDir(id), "personal.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) }
        }.getOrNull()
    }

    fun savePersonal(id: String, personal: JSONObject) = onPrefsThread {
        if (id == Profile.OWNER) savePrefsFile(loadPrefsFile().withPersonal(personal))
        else writeAtomic(File(profileDir(id), "personal.json"), personal.toString())
    }

    /** Which profile's hidden marks the movie & series catalog holds now. */
    var catalogProfile: String
        get() = runCatching { File(dir, "catalog_profile.txt").readText().trim() }.getOrDefault(Profile.OWNER).ifEmpty { Profile.OWNER }
        set(v) { runCatching { File(dir, "catalog_profile.txt").writeText(v) } }

    /** Removes a profile's own files (not the owner's). */
    fun deleteProfileData(id: String) { if (id != Profile.OWNER) onPrefsThread { File(dir, "profiles/$id").deleteRecursively() } }

    // ---- prefs ----
    /**
     * #20: settings were encoded and written to flash on the screen's thread at every change (several a minute while
     * syncing), and the guide stopped answering for 4-10 s on Fire TV Sticks. Now every read and write of the
     * settings files runs on one background thread, in order: [savePrefs] only queues the write, and a read waits
     * for the writes queued before it, so it never sees older settings.
     */
    private fun <T> onPrefsThread(f: () -> T): T =
        if (Thread.currentThread() === prefsThread) f() else prefsIo.submit<T> { f() }.get()

    /** Waits until every queued settings write is on disk. */
    fun flushPrefs() { onPrefsThread { } }

    private fun loadPrefsFile(): Prefs = onPrefsThread { readPrefsFile() }

    private fun readPrefsFile(): Prefs {
        val f = File(dir, "prefs.json")
        return try {
            if (f.exists()) Prefs.fromJson(JSONObject(f.readText())) else Prefs()
        } catch (e: Exception) {
            Prefs()
        }
    }

    private fun savePrefsFile(p: Prefs) = writeAtomic(File(dir, "prefs.json"), p.toJson().toString())

    /** The shared settings with profile [id]'s own (the current profile by default) in place of the owner's. */
    fun loadPrefs(id: String = profileId): Prefs = onPrefsThread {
        val base = loadPrefsFile()
        if (id == Profile.OWNER) base else base.withPersonal(loadPersonal(id) ?: JSONObject())
    }

    /** Saves the shared settings for everyone, and the personal ones for the current profile only (queued, see [onPrefsThread]). */
    fun savePrefs(p: Prefs) {
        val id = profileId
        synchronized(pending) {
            // Saves in a row for the same profile (the guide saves the last channel at every move) write only the newest.
            if (pending.lastOrNull()?.first == id) { pending[pending.lastIndex] = id to p; return }
            pending.addLast(id to p)
        }
        prefsIo.execute {
            val (who, latest) = synchronized(pending) { pending.removeFirstOrNull() } ?: return@execute
            runCatching {
                if (who == Profile.OWNER) savePrefsFile(latest)
                else {
                    val owner = runCatching { personalOf(readPrefsFile()) }.getOrDefault(JSONObject())
                    savePrefsFile(latest.withPersonal(owner))
                    writeAtomic(File(profileDir(who), "personal.json"), personalOf(latest).toString())
                }
            }
        }
    }
    private val pending = ArrayDeque<Pair<String, Prefs>>()

    /** When a playlist's saved "channels", "guide" or "vod" file was written; 0 if it's missing or empty. */
    fun savedAt(kind: String, playlistId: String): Long {
        val f = File(dataDir, "${kind}_$playlistId.json")
        return if (f.exists() && f.length() > 2) f.lastModified() else 0L
    }

    // ---- channels per playlist ----
    fun saveChannels(playlistId: String, list: List<Channel>) {
        writeArrayAtomic(File(dataDir, "channels_$playlistId.json"), list) { it.toJson() }
    }

    fun loadChannels(playlistId: String): List<Channel> = readArray(File(dataDir, "channels_$playlistId.json")) { Channel.fromJson(it) }

    /** An older version's saved channel list, one channel at a time (for moving it into the database). */
    fun forEachSavedChannel(playlistId: String, emit: (Channel) -> Unit) {
        val f = File(dataDir, "channels_$playlistId.json")
        if (!f.exists()) return
        android.util.JsonReader(f.bufferedReader(Charsets.UTF_8, 64 * 1024)).use { r ->
            r.beginArray()
            while (r.hasNext()) {
                val o = readJsonValue(r)
                if (o is JSONObject) runCatching { Channel.fromJson(o) }.getOrNull()?.let(emit)
            }
            r.endArray()
        }
    }

    // ---- guide per playlist ----
    fun saveGuide(playlistId: String, guide: Map<String, List<Program>>) {
        val o = JSONObject()
        guide.forEach { (id, list) ->
            val a = JSONArray()
            list.forEach { p ->
                a.put(JSONArray().put(p.start).put(p.end).put(p.title).put(p.subtitle ?: "").put(if (p.isNew) 1 else 0))
            }
            o.put(id, a)
        }
        writeAtomic(File(dataDir, "guide_$playlistId.json"), JSONObject().put("saved", System.currentTimeMillis()).put("g", o).toString())
    }

    /** Returns the saved guide and when it was downloaded. */
    fun loadGuide(playlistId: String): Pair<Map<String, List<Program>>, Long> {
        val f = File(dataDir, "guide_$playlistId.json")
        if (!f.exists()) return emptyMap<String, List<Program>>() to 0L
        return try {
            val root = JSONObject(f.readText())
            val o = root.getJSONObject("g")
            val map = HashMap<String, List<Program>>()
            o.keys().forEach { id ->
                val a = o.getJSONArray(id)
                map[id] = List(a.length()) {
                    val r = a.getJSONArray(it)
                    Program(r.getLong(0), r.getLong(1), r.getString(2), r.getString(3).ifBlank { null }, r.getInt(4) == 1)
                }
            }
            map to root.optLong("saved", 0L)
        } catch (e: Exception) {
            emptyMap<String, List<Program>>() to 0L
        }
    }

    // ---- channels found in a playlist's guide sources (for matching by hand) ----
    fun saveGuideChannels(playlistId: String, list: List<GuideChannel>) =
        writeArrayAtomic(File(dataDir, "guidech_$playlistId.json"), list) { JSONObject().put("i", it.id).put("n", it.name).put("s", it.source) }

    fun loadGuideChannels(playlistId: String): List<GuideChannel> =
        readArray(File(dataDir, "guidech_$playlistId.json")) { GuideChannel(it.getString("i"), it.optString("n"), it.optString("s")) }

    // ---- channel references from a restored backup, used once to find them in the new list ----
    fun saveKeyRefs(playlistId: String, refs: List<ChannelKeys.Ref>) =
        if (refs.isEmpty()) { File(dataDir, "keyrefs_$playlistId.json").delete(); Unit }
        else writeArrayAtomic(File(dataDir, "keyrefs_$playlistId.json"), refs) { it.toJson() }

    fun loadKeyRefs(playlistId: String): List<ChannelKeys.Ref> = readArray(File(dataDir, "keyrefs_$playlistId.json")) { ChannelKeys.Ref.fromJson(it) }

    // ---- movies & series per playlist ----
    fun saveVod(playlistId: String, list: List<VodItem>) {
        writeArrayAtomic(File(dataDir, "vod_$playlistId.json"), list) { it.toJson() }
    }

    fun loadVod(playlistId: String): List<VodItem> = readArray(File(dataDir, "vod_$playlistId.json")) { VodItem.fromJson(it) }

    /** The old movies & series file, once its titles are in the catalog database. */
    fun deleteVodFile(playlistId: String) { File(dataDir, "vod_$playlistId.json").delete() }

    /**
     * Categories left out of a playlist's last channel download because every profile hides them (names, as the
     * provider spells them). The List Editor still lists them, and showing one downloads it again.
     */
    fun saveSkippedGroups(playlistId: String, names: Collection<String>) =
        writeAtomic(File(dataDir, "skipped_$playlistId.json"), org.json.JSONArray(names.toList()).toString())
    fun loadSkippedGroups(playlistId: String): Set<String> = runCatching {
        val a = org.json.JSONArray(File(dataDir, "skipped_$playlistId.json").readText())
        (0 until a.length()).map { a.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    /** Each playlist's last channel load (for the website's Troubleshooting), and when reseller lines were last checked. */
    fun saveLoadStatus(o: JSONObject) = writeAtomic(File(dir, "load-status.json"), o.toString())
    fun loadLoadStatus(): JSONObject = runCatching { JSONObject(File(dir, "load-status.json").readText()) }.getOrDefault(JSONObject())

    /** ESPN's last answer per league and day, for when it can't be reached. */
    fun saveSportsCache(league: String, day: String, text: String) = runCatching { File(dataDir, "sports_${league}_$day.json").writeText(text) }
    fun loadSportsCache(league: String, day: String): String? =
        runCatching { File(dataDir, "sports_${league}_$day.json").takeIf { it.exists() }?.readText() }.getOrNull()

    /** Drops saved ESPN days no longer shown (and the one-file-per-league cache of 1.0.141). */
    fun pruneSportsCache(keepDays: Set<String>) {
        dataDir.listFiles { f -> f.name.startsWith("sports_") && f.name.endsWith(".json") }?.forEach { f ->
            val day = f.name.removeSuffix(".json").substringAfterLast('_')
            if (day.length != 8 || day !in keepDays) f.delete()
        }
    }

    /** Deletes a playlist's saved files (its rows in the database are removed separately). */
    fun deletePlaylistData(playlistId: String) {
        File(dataDir, "vod_$playlistId.json").delete()
        File(dataDir, "channels_$playlistId.json").delete()
        File(dataDir, "guide_$playlistId.json").delete()
        File(dataDir, "guidech_$playlistId.json").delete()
    }

    // ---- watch history ----
    fun loadHistory(id: String = profileId): List<HistoryEntry> {
        val f = personalFile("history.json", id)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            List(a.length()) {
                val o = a.getJSONObject(it)
                HistoryEntry(o.getString("k"), o.getLong("t"), o.getLong("s"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveHistory(list: List<HistoryEntry>, id: String = profileId) {
        val cutoff = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
        val a = JSONArray()
        list.filter { it.time >= cutoff }.takeLast(5000).forEach {
            a.put(JSONObject().put("k", it.key).put("t", it.time).put("s", it.seconds))
        }
        writeAtomic(personalFile("history.json", id), a.toString())
    }

    // ---- resume points for movies & episodes ----
    fun loadResume(id: String = profileId): Map<String, Long> {
        val f = personalFile("resume.json", id)
        if (!f.exists()) return emptyMap()
        return try {
            val o = JSONObject(f.readText()); o.keys().asSequence().associateWith { o.getLong(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveResume(m: Map<String, Long>, id: String = profileId) = writeAtomic(personalFile("resume.json", id), JSONObject(m).toString())

    // ---- episodes watched to the end (the series page opens on the next one) ----
    fun loadWatched(id: String = profileId): Set<String> = runCatching {
        val f = personalFile("watched.json", id)
        if (!f.exists()) emptySet() else org.json.JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toCollection(LinkedHashSet()) }
    }.getOrDefault(emptySet())

    fun saveWatched(keys: Collection<String>, id: String = profileId) = writeAtomic(personalFile("watched.json", id), org.json.JSONArray(keys.toList().takeLast(5000)).toString())

    // ---- 1.17.0: about each place: the length, when it changed, and which TV it came from (null = this one) ----
    data class PlaceInfo(val dur: Long, val at: Long, val tv: String? = null)

    fun loadPlaceInfo(id: String = profileId): Map<String, PlaceInfo> = runCatching {
        val f = personalFile("placeinfo.json", id)
        if (!f.exists()) emptyMap() else JSONObject(f.readText()).let { o ->
            o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { PlaceInfo(it.optLong("d"), it.optLong("t"), it.optString("tv").ifBlank { null }) } }
        }
    }.getOrDefault(emptyMap())

    fun savePlaceInfo(m: Map<String, PlaceInfo>, id: String = profileId) = writeAtomic(personalFile("placeinfo.json", id), JSONObject().apply {
        m.entries.sortedByDescending { it.value.at }.take(6000).forEach { (k, v) -> put(k, JSONObject().put("d", v.dur).put("t", v.at).apply { v.tv?.let { put("tv", it) } }) }
    }.toString())

    /**
     * 1.17.1: the latest episode known for each series (season, episode), when it was checked, and when a newer one
     * than before was first seen (a new episode or season puts the series back in Continue watching). Shared.
     */
    /** [released]: the release time of the series' newest episode (Nov 22, 2026: Continue watching sorts by it). */
    data class SeriesLatest(val season: Int, val episode: Int, val checked: Long, val newAt: Long, val released: Long = 0L)

    fun loadSeriesLatest(): Map<String, SeriesLatest> = runCatching {
        val o = JSONObject(File(dir, "series-latest.json").readText())
        o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { SeriesLatest(it.optInt("s"), it.optInt("e"), it.optLong("c"), it.optLong("n"), it.optLong("r")) } }
    }.getOrDefault(emptyMap())

    fun saveSeriesLatest(m: Map<String, SeriesLatest>) = writeAtomic(File(dir, "series-latest.json"), JSONObject().apply {
        m.entries.sortedByDescending { it.value.checked }.take(3000).forEach { (k, v) -> put(k, JSONObject().put("s", v.season).put("e", v.episode).put("c", v.checked).put("n", v.newAt).put("r", v.released)) }
    }.toString())

    /** 1.17.0: what this TV last sent or received for each profile's history (see AppViewModel.syncHistory). */
    fun loadHistorySync(id: String): JSONObject = runCatching { JSONObject(personalFile("historysync.json", id).readText()) }.getOrDefault(JSONObject())
    fun saveHistorySync(o: JSONObject, id: String) = writeAtomic(personalFile("historysync.json", id), o.toString())

    /**
     * 1.18.0: the profile's last live channel. "mine": this TV's own (sent to the account, locked); "other": the
     * newest one from another TV (for the live card in Continue watching). Each: {pl, ch, name, epg, tv, at, until}.
     */
    // Nov 26, 2026: also "recent": the TV Guide's Recent list, up to 10 entries {key (this TV's), pl, ch, name, epg,
    // at, tv, dev}, newest first; and "recentGone": {entry id: when removed}, sent so other TVs remove it too.
    fun loadLive(id: String): JSONObject = runCatching { JSONObject(personalFile("live.json", id).readText()) }.getOrDefault(JSONObject())
    fun saveLive(o: JSONObject, id: String) = writeAtomic(personalFile("live.json", id), o.toString())

    /**
     * Every profile other than the owner, with its own files, for a backup: id → {"personal", "history",
     * "resume", "watched"} (raw JSON text of each file that exists).
     */
    fun exportProfileFiles(): JSONObject {
        val out = JSONObject()
        File(dir, "profiles").listFiles()?.filter { it.isDirectory }?.forEach { d ->
            val o = JSONObject()
            for (n in listOf("personal", "history", "resume", "watched", "placeinfo")) File(d, "$n.json").takeIf { it.exists() }?.let { o.put(n, it.readText()) }
            out.put(d.name, o)
        }
        return out
    }

    /** Replaces every non-owner profile's files with a backup's ([exportProfileFiles]). */
    fun importProfileFiles(o: JSONObject) {
        File(dir, "profiles").deleteRecursively()
        o.keys().forEach { id ->
            if (id == Profile.OWNER || !Regex("[A-Za-z0-9_-]{1,40}").matches(id)) return@forEach
            val files = o.optJSONObject(id) ?: return@forEach
            for (n in listOf("personal", "history", "resume", "watched", "placeinfo")) files.optString(n).takeIf { it.isNotEmpty() }?.let { writeAtomic(File(profileDir(id), "$n.json"), it) }
        }
    }

    fun clearCache() {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun cacheSizeBytes(): Long = context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        /** The one thread that reads and writes the settings files, for every Store (see [onPrefsThread]). */
        @Volatile private var prefsThread: Thread? = null
        private val prefsIo: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "avid-prefs").apply { isDaemon = true; prefsThread = this }
        }
    }

}
