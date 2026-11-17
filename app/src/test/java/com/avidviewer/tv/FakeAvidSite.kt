package com.avidviewer.tv

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * A stand-in for avidviewer.app's TV API (accounts step 2) and setup codes: /api/tv/link/start|poll|claim,
 * /api/tv/me, /api/tv/signout, /api/setup/<id>. [approved] makes the next poll succeed; [removed] makes /api/tv/me
 * answer 401 (the TV was removed on the account page). Every request is recorded in [calls] as "METHOD path body".
 * 1.12.0 backups: POST /api/tv/backup, GET /api/tv/backups, GET /api/tv/backup?device=; [backups] holds one per TV.
 */
class FakeAvidSite : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val base get() = "http://127.0.0.1:${server.localPort}"
    val calls = java.util.Collections.synchronizedList(ArrayList<String>())
    @Volatile var approved = false
    @Volatile var removed = false
    /** Setup codes it serves: fingerprint → data. */
    /** 1.14.0 playlists from the account: what the device last reported, what it should do, and its answers. */
    @Volatile var report: org.json.JSONObject? = null
    val inbox = mutableListOf<org.json.JSONObject>()
    val acks = java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())
    /** 1.15.0 account profiles: what /api/tv/report answers the first time (local id → account id), the device's profiles. */
    @Volatile var profileMap: Map<String, String> = emptyMap()
    @Volatile var managed = false
    @Volatile var profiles: org.json.JSONArray? = null
    val catalogs = java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())
    val playing = java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())
    /** The next Watching now report is told to stop (Stop stream on the website). */
    @Volatile var stopNext = false
    private fun profilesHash() = profiles?.toString()?.hashCode()?.toString(16) ?: ""
    /** One backup per TV: device id → (TV name, file text, PIN). */
    val backups = java.util.concurrent.ConcurrentHashMap<String, Triple<String, String, String>>()
    private fun deviceOf(auth: String) = if (auth.startsWith("Bearer TVTOKEN_claimed")) "d2" else "d1"

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { c.use { handle(it) } } }
            }
        }
    }

    private fun handle(conn: java.net.Socket) {
        val input = conn.getInputStream()
        fun line(): String {
            val sb = StringBuilder()
            while (true) { val b = input.read(); if (b < 0 || b == '\n'.code) break; sb.append(b.toChar()) }
            return sb.toString().trimEnd('\r')
        }
        val (method, path) = line().split(" ").let { it[0] to it.getOrElse(1) { "/" } }
        var length = 0; var auth = ""
        while (true) {
            val h = line(); if (h.isEmpty()) break
            val (k, v) = h.split(":", limit = 2).let { it[0].trim().lowercase() to it.getOrElse(1) { "" }.trim() }
            if (k == "content-length") length = v.toInt()
            if (k == "authorization") auth = v
        }
        val body = ByteArray(length).also { var n = 0; while (n < length) { val r = input.read(it, n, length - n); if (r < 0) break; n += r } }.toString(Charsets.UTF_8)
        calls += "$method $path $body"
        val (code, json) = when {
            path == "/api/tv/link/start" -> 200 to """{"userCode":"K7M2QX9P","deviceCode":"DEVICECODE","interval":1,"expiresIn":600,"qrUrl":"$base/link/?c=K7M2QX9P"}"""
            path == "/api/tv/link/poll" -> 200 to (if (approved) """{"status":"approved","token":"TVTOKEN_abcdefghijklmnopqrstuvwxyz","email":"viewer@example.com","name":"Living room","deviceId":"d1"}""" else """{"status":"pending"}""")
            path == "/api/tv/link/claim" -> 200 to """{"status":"approved","token":"TVTOKEN_claimedabcdefghijklmnopqrst","email":"viewer@example.com","name":"Den TV","deviceId":"d2"}"""
            path == "/api/tv/me" -> if (removed || !auth.startsWith("Bearer TVTOKEN_")) 401 to """{"error":"This TV is not linked."}""" else 200 to """{"email":"viewer@example.com","name":"Living room"}"""
            path == "/api/tv/signout" -> 200 to """{"ok":true}"""
            path == "/api/tv/backup" && method == "POST" -> {
                val o = org.json.JSONObject(body)
                val d = deviceOf(auth)
                backups[d] = Triple(if (d == "d1") "Living room" else "Den TV", o.getString("data"), o.optString("pin"))
                200 to """{"ok":true}"""
            }
            path == "/api/tv/backups" -> 200 to org.json.JSONObject().put("backups", org.json.JSONArray().apply {
                backups.forEach { (id, b) ->
                    val head = org.json.JSONObject(b.second)
                    put(org.json.JSONObject().put("deviceId", id).put("name", b.first).put("model", "Test").put("appVersion", head.optString("appVersion"))
                        .put("createdAt", head.optLong("created")).put("size", b.second.length).put("playlists", head.optJSONArray("playlistNames")?.length() ?: 0)
                        .put("removed", false).put("thisTv", id == deviceOf(auth)))
                }
            }).toString()
            path.startsWith("/api/tv/backup?device=") -> backups[path.substringAfter("device=")]?.let { 200 to org.json.JSONObject().put("data", it.second).toString() }
                ?: (404 to """{"error":"That backup is no longer in your account."}""")
            path == "/api/tv/report" -> {
                report = org.json.JSONObject(body)
                if (report!!.optInt("v") == 2) managed = true
                200 to org.json.JSONObject().put("ok", true).put("managed", managed).put("profileMap", org.json.JSONObject(profileMap)).toString()
            }
            path.startsWith("/api/tv/inbox") && method == "GET" -> 200 to org.json.JSONObject().put("items", org.json.JSONArray(synchronized(inbox) { inbox.toList() })).apply {
                if (managed) {
                    put("managed", true).put("profilesHash", profilesHash())
                    if (path.substringAfter("ph=", "") != profilesHash()) put("profiles", profiles ?: org.json.JSONArray())
                }
            }.toString()
            path == "/api/tv/catalog" -> { catalogs += org.json.JSONObject(body); 200 to """{"ok":true}""" }
            path == "/api/tv/playing" -> {
                playing += org.json.JSONObject(body)
                val stop = stopNext; stopNext = false
                200 to org.json.JSONObject().put("ok", true).put("stop", stop).toString()
            }
            path == "/api/tv/inbox/ack" -> {
                val a = org.json.JSONObject(body); acks += a
                synchronized(inbox) { inbox.removeAll { it.getString("playlistId") == a.getString("playlistId") && it.getInt("version") == a.getInt("version") } }
                200 to """{"ok":true}"""
            }
            else -> 404 to """{"error":"Not found."}"""
        }
        val bytes = json.toByteArray()
        val head = "HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        conn.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }

    override fun close() { server.close() }
}
