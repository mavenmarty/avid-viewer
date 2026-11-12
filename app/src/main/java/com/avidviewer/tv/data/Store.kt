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
                Playlist.guideFrom(o, Playlist(
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
                ))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePlaylists(list: List<Playlist>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                it.guideJson(JSONObject()).put("id", it.id).put("name", it.name).put("type", it.type)
                    .put("server", it.server).put("username", it.username)
                    .put("password", Crypto.encrypt(it.password))
                    .put("m3uUrl", it.m3uUrl).put("epgUrl", it.epgUrl)
                    .put("enabled", it.enabled).put("liveOn", it.liveOn).put("vodOn", it.vodOn)
            )
        }
        writeAtomic(File(dir, "playlists.json"), a.toString())
    }

    // ---- prefs ----
    fun loadPrefs(): Prefs {
        val f = File(dir, "prefs.json")
        return try {
            if (f.exists()) Prefs.fromJson(JSONObject(f.readText())) else Prefs()
        } catch (e: Exception) {
            Prefs()
        }
    }

    fun savePrefs(p: Prefs) = writeAtomic(File(dir, "prefs.json"), p.toJson().toString())

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
    fun loadHistory(): List<HistoryEntry> {
        val f = File(dir, "history.json")
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

    fun saveHistory(list: List<HistoryEntry>) {
        val cutoff = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
        val a = JSONArray()
        list.filter { it.time >= cutoff }.takeLast(5000).forEach {
            a.put(JSONObject().put("k", it.key).put("t", it.time).put("s", it.seconds))
        }
        writeAtomic(File(dir, "history.json"), a.toString())
    }

    // ---- resume points for movies & episodes ----
    fun loadResume(): Map<String, Long> {
        val f = File(dir, "resume.json")
        if (!f.exists()) return emptyMap()
        return try {
            val o = JSONObject(f.readText()); o.keys().asSequence().associateWith { o.getLong(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveResume(m: Map<String, Long>) = writeAtomic(File(dir, "resume.json"), JSONObject(m).toString())

    fun clearCache() {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun cacheSizeBytes(): Long = context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
