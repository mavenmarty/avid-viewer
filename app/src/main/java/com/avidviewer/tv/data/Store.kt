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
    private val dataDir: File get() = File(dir, "data").apply { mkdirs() }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    // ---- playlists (passwords encrypted) ----
    fun loadPlaylists(): List<Playlist> {
        val f = File(dir, "playlists.json")
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            List(a.length()) {
                val o = a.getJSONObject(it)
                Playlist(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    type = o.getString("type"),
                    server = o.optString("server"),
                    username = o.optString("username"),
                    password = Crypto.decrypt(o.optString("password")),
                    m3uUrl = o.optString("m3uUrl"),
                    epgUrl = o.optString("epgUrl"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePlaylists(list: List<Playlist>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject().put("id", it.id).put("name", it.name).put("type", it.type)
                    .put("server", it.server).put("username", it.username)
                    .put("password", Crypto.encrypt(it.password))
                    .put("m3uUrl", it.m3uUrl).put("epgUrl", it.epgUrl)
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

    // ---- channels per playlist ----
    fun saveChannels(playlistId: String, list: List<Channel>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        writeAtomic(File(dataDir, "channels_$playlistId.json"), a.toString())
    }

    fun loadChannels(playlistId: String): List<Channel> {
        val f = File(dataDir, "channels_$playlistId.json")
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            List(a.length()) { Channel.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
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

    // ---- movies & series per playlist ----
    fun saveVod(playlistId: String, list: List<VodItem>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        writeAtomic(File(dataDir, "vod_$playlistId.json"), a.toString())
    }

    fun loadVod(playlistId: String): List<VodItem> {
        val f = File(dataDir, "vod_$playlistId.json")
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            List(a.length()) { VodItem.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deletePlaylistData(playlistId: String) {
        File(dataDir, "vod_$playlistId.json").delete()
        File(dataDir, "channels_$playlistId.json").delete()
        File(dataDir, "guide_$playlistId.json").delete()
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

    fun clearCache() {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun cacheSizeBytes(): Long = context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
