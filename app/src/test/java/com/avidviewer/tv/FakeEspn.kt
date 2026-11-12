package com.avidviewer.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/** A stand-in for ESPN's scoreboard feed: answers each league's path with the games given for it. */
class FakeEspn(private val byPath: Map<String, String>) : AutoCloseable {
    private val server = ServerSocket(0)
    val requests = java.util.Collections.synchronizedList(ArrayList<String>())
    val base get() = "http://127.0.0.1:${server.localPort}/sports/"

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    c.use { conn ->
                        val r = conn.getInputStream().bufferedReader()
                        val first = r.readLine() ?: return@use
                        while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                        val path = first.split(" ").getOrElse(1) { "" }
                        requests.add(path)
                        val body = (byPath.entries.firstOrNull { path.contains(it.key + "/scoreboard") }?.value ?: """{"events":[]}""").toByteArray()
                        conn.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                        }
                    }
                }
            }
        }
    }

    override fun close() = server.close()

    companion object {
        private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)

        data class T(val abbr: String, val short: String, val loc: String, val color: String = "112233", val record: String = "1-0", val score: String = "0")

        /** One event in ESPN's shape. [nets]: name to market ("national", "home", "away"); [kind] TV or Streaming. */
        fun event(id: String, start: Long, state: String, away: T, home: T, nets: List<Triple<String, String, String>>, detail: String = "", period: Int = 0, clock: String = ""): JSONObject {
            fun team(t: T, side: String) = JSONObject().put("homeAway", side).put("score", t.score)
                .put("team", JSONObject().put("abbreviation", t.abbr).put("shortDisplayName", t.short).put("name", t.short).put("location", t.loc)
                    .put("displayName", t.loc + " " + t.short).put("color", t.color).put("logo", "http://127.0.0.1:9/logo/${t.abbr}.png"))
                .put("records", JSONArray().put(JSONObject().put("summary", t.record)))
            val geo = JSONArray()
            nets.forEach { (name, market, kind) ->
                geo.put(JSONObject().put("media", JSONObject().put("shortName", name)).put("type", JSONObject().put("shortName", kind)).put("market", JSONObject().put("type", market.replaceFirstChar { it.uppercase() })))
            }
            val status = JSONObject().put("period", period).put("displayClock", clock).put("type", JSONObject().put("state", state).put("shortDetail", detail))
            val comp = JSONObject().put("date", iso(start)).put("status", status).put("geoBroadcasts", geo)
                .put("venue", JSONObject().put("fullName", "Test Arena").put("address", JSONObject().put("city", "Testville").put("state", "MN")))
                .put("competitors", JSONArray().put(team(home, "home")).put(team(away, "away")))
            return JSONObject().put("id", id).put("date", iso(start)).put("competitions", JSONArray().put(comp))
        }

        fun board(vararg events: JSONObject) = JSONObject().put("events", JSONArray().apply { events.forEach { put(it) } }).toString()
    }
}
