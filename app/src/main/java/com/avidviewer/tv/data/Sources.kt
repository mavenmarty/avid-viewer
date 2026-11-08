package com.avidviewer.tv.data

import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

const val USER_AGENT = "AvidViewer/1.0 (Android TV)"

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun <T> open(url: String, block: (InputStream) -> T): T {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Server answered ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response")
            val raw = BufferedInputStream(body.byteStream(), 64 * 1024)
            // Some guides are served gzipped without a header saying so.
            raw.mark(2)
            val b1 = raw.read(); val b2 = raw.read()
            raw.reset()
            val stream: InputStream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
            return block(stream)
        }
    }

    fun text(url: String): String = open(url) { it.readBytes().toString(Charsets.UTF_8) }
}

/** Xtream Codes "player_api" provider. */
object Xtream {
    private fun api(p: Playlist, action: String? = null): String {
        val u = java.net.URLEncoder.encode(p.username, "UTF-8")
        val pw = java.net.URLEncoder.encode(p.password, "UTF-8")
        return "${p.xtreamBase()}/player_api.php?username=$u&password=$pw" + (action?.let { "&action=$it" } ?: "")
    }

    /** Checks the login. Returns null when it works, or a message explaining the problem. */
    fun checkLogin(p: Playlist): String? = try {
        val o = JSONObject(Net.text(api(p)))
        val info = o.optJSONObject("user_info")
        when {
            info == null -> "The server didn't recognise this as an Xtream Codes login."
            info.optInt("auth", 1) == 0 -> "Username or password was not accepted."
            info.optString("status").equals("Expired", true) -> "This account has expired."
            else -> null
        }
    } catch (e: Exception) {
        "Couldn't reach the server: ${e.message}"
    }

    fun liveChannels(p: Playlist, format: String): List<Channel> {
        val cats = JSONArray(Net.text(api(p, "get_live_categories")))
        val catNames = HashMap<String, String>()
        for (i in 0 until cats.length()) {
            val c = cats.getJSONObject(i)
            catNames[c.optString("category_id")] = c.optString("category_name", "Uncategorized")
        }
        val streams = JSONArray(Net.text(api(p, "get_live_streams")))
        val ext = if (format == "m3u8") "m3u8" else "ts"
        val base = p.xtreamBase()
        val u = p.username; val pw = p.password
        val out = ArrayList<Channel>(streams.length())
        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            val id = s.optString("stream_id")
            if (id.isBlank()) continue
            out.add(
                Channel(
                    key = "${p.id}:$id",
                    name = s.optString("name", "Channel $id").trim(),
                    group = catNames[s.optString("category_id")] ?: "Uncategorized",
                    logo = s.optString("stream_icon").ifBlank { null },
                    url = "$base/live/$u/$pw/$id.$ext",
                    epgId = s.optString("epg_channel_id").ifBlank { null },
                )
            )
        }
        return out
    }
}

/** Plain M3U / M3U Plus playlists. */
object M3u {
    private val attr = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    /** Live channels; movies and series entries go into [vodOut] when given. */
    fun channels(p: Playlist, vodOut: MutableList<VodItem>? = null): List<Channel> = Net.open(p.m3uUrl.trim()) { input ->
        val out = ArrayList<Channel>()
        var pending: Triple<String, Map<String, String>, String?>? = null
        var n = 0
        input.bufferedReader().forEachLine { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", true) -> {
                    val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val name = line.substringAfterLast(',').trim().ifBlank { attrs["tvg-name"] ?: "Channel" }
                    pending = Triple(name, attrs, attrs["group-title"])
                }
                line.startsWith("#EXTGRP:", true) -> pending = pending?.let { Triple(it.first, it.second, line.substringAfter(':').trim()) }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val pnd = pending
                    if (pnd != null) {
                        val url = line
                        val isMovie = url.contains("/movie/"); val isEpisode = url.contains("/series/")
                        if (isMovie || isEpisode) {
                            vodOut?.add(
                                VodItem(
                                    key = "${p.id}:v${n++}", playlistId = p.id, kind = if (isMovie) "movie" else "episode",
                                    name = pnd.first, logo = pnd.second["tvg-logo"]?.ifBlank { null }, url = url,
                                )
                            )
                        } else {
                            out.add(
                                Channel(
                                    key = "${p.id}:${n++}",
                                    name = pnd.first,
                                    group = pnd.third?.ifBlank { null } ?: "Uncategorized",
                                    logo = pnd.second["tvg-logo"]?.ifBlank { null },
                                    url = url,
                                    epgId = pnd.second["tvg-id"]?.ifBlank { null },
                                )
                            )
                        }
                    }
                    pending = null
                }
            }
        }
        out
    }

    /** M3U Plus lists often name their guide in the header: url-tvg="...". */
    fun headerGuideUrl(p: Playlist): String? = try {
        Net.open(p.m3uUrl.trim()) { input ->
            val first = input.bufferedReader().readLine() ?: ""
            attr.findAll(first).firstOrNull { it.groupValues[1].lowercase() in setOf("url-tvg", "x-tvg-url") }?.groupValues?.get(2)
        }
    } catch (e: Exception) {
        null
    }
}

/** Streaming XMLTV reader that keeps only the channels we have and a time window. */
object Xmltv {
    private val fmtZ = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    }
    private val fmtNoZ = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    fun parseTime(s: String): Long? = try {
        val t = s.trim()
        if (t.length > 14 && (t.contains('+') || t.contains('-'))) fmtZ.get()!!.parse(t)?.time
        else fmtNoZ.get()!!.parse(t.take(14))?.time
    } catch (e: Exception) {
        null
    }

    fun read(url: String, wanted: Set<String>, from: Long, to: Long): Map<String, List<Program>> = Net.open(url) { input ->
        val wantedLower = wanted.associateBy { it.lowercase() }
        val out = HashMap<String, MutableList<Program>>()
        val xp = Xml.newPullParser()
        xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        xp.setInput(input, null)
        var event = xp.eventType
        var chId: String? = null; var start = 0L; var end = 0L
        var title: String? = null; var sub: String? = null; var isNew = false
        var tag: String? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (xp.name) {
                    "programme" -> {
                        val id = xp.getAttributeValue(null, "channel")
                        val key = id?.let { wantedLower[it.lowercase()] }
                        val s = xp.getAttributeValue(null, "start")?.let { parseTime(it) }
                        val e = xp.getAttributeValue(null, "stop")?.let { parseTime(it) }
                        if (key != null && s != null && e != null && e > from && s < to) {
                            chId = key; start = s; end = e; title = null; sub = null; isNew = false
                        } else chId = null
                    }
                    "title", "sub-title" -> tag = if (chId != null) xp.name else null
                    "new" -> if (chId != null) isNew = true
                    "premiere" -> if (chId != null) isNew = true
                }
                XmlPullParser.TEXT -> when (tag) {
                    "title" -> if (title == null) title = xp.text
                    "sub-title" -> if (sub == null) sub = xp.text
                }
                XmlPullParser.END_TAG -> when (xp.name) {
                    "title", "sub-title" -> tag = null
                    "programme" -> {
                        val id = chId
                        if (id != null) {
                            out.getOrPut(id) { ArrayList() }.add(Program(start, end, clean(title ?: "Untitled"), sub?.let { clean(it) }, isNew))
                        }
                        chId = null
                    }
                }
            }
            event = xp.next()
        }
        out.mapValues { (_, v) -> v.sortedBy { it.start } }
    }

    // Some community guides put emoji and [TAGS] in titles; keep titles tidy on a TV.
    private val tagRx = Regex("""\[[^\]]{1,20}]""")
    private fun clean(s: String): String =
        s.replace(tagRx, " ").filter { !it.isSurrogate() && it.code !in 0x2190..0x2BFF && it != '\uFE0F' }.replace(Regex("\\s+"), " ").trim()
}
