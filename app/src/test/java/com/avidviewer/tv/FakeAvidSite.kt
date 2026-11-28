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
    /** Nov 20: the code was entered on an account that already has 4 devices. */
    @Volatile var full = false
    @Volatile var approved = false
    /** Beta channel: what /api/tv/me says (null: the "beta" field is left out, as before Nov 18, 2026). */
    @Volatile var beta: String? = null
    @Volatile var removed = false
    /** Reports sent to /api/tv/reports (with "auth" added), ids claimed, and whether /api/tv/me asks for a playback log. */
    val reports = mutableListOf<org.json.JSONObject>()
    val claimed = mutableListOf<String>()
    @Volatile var sendLog = false
    /** Nov 21, 2026: reseller addresses and renewals /api/tv/me hands out, website requests, and what devices sent. */
    @Volatile var resellerHosts: List<String> = emptyList()
    @Volatile var renew: List<Pair<String, String>> = emptyList()
    @Volatile var reloadChannels = false
    @Volatile var checkSubscription = false
    val resellerChecks = java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())
    val resellerRenews = java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())
    val loadStatuses = java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())
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
    /** 1.16.0: what devices sent to /api/tv/sync, and the next answer (then empty). */
    val syncs = java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())
    @Volatile var syncReply: org.json.JSONObject? = null
    /** 1.17.0: the history key locked for this device, what devices sent, and the account's locked entries. */
    @Volatile var historySealed: org.json.JSONObject? = null
    @Volatile var historyEnabled = true
    @Volatile var tmdbBlob: String? = null
    val historyPosts = java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())
    val historyItems = java.util.concurrent.ConcurrentHashMap<String, org.json.JSONObject>() // "profile|item"
    val discoverDocs = java.util.concurrent.ConcurrentHashMap<String, org.json.JSONObject>()
    fun putHistory(profile: String, item: String, blob: String?, at: Long) {
        historyItems["$profile|$item"] = org.json.JSONObject().put("item", item).put("blob", blob ?: org.json.JSONObject.NULL).put("at", at).put("seq", System.currentTimeMillis())
    }
    /** 1.15.1: the account's public key sent to devices, and the playlists devices reported. */
    @Volatile var accountKey: String? = null
    @Volatile var devicePlaylists: org.json.JSONArray? = null
    private fun profilesHash() = profiles?.toString()?.hashCode()?.toString(16) ?: ""
    /** One backup per TV: device id → (TV name, file text, PIN). */
    val backups = java.util.concurrent.ConcurrentHashMap<String, Triple<String, String, String>>()
    /** Nov 26, 2026: the postal code set on the website, as /api/tv/me sends it ({"code","at"}), or null. */
    @Volatile var postal: String? = null
    /** Nov 28, 2026: "Is it fixed?" items sent at check-in, and the TV's answers. */
    @Volatile var fixChecks: org.json.JSONArray? = null
    /** Nov 28, 2026: the account's playlist order for live TV (account playlist ids). */
    @Volatile var liveOrder: List<String>? = null
    val fixAnswers = java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())

    private fun meExtras(): String {
        val o = org.json.JSONObject()
        if (resellerHosts.isNotEmpty()) o.put("reseller", org.json.JSONObject().put("hosts", org.json.JSONArray(resellerHosts))
            .put("renew", org.json.JSONArray().apply { renew.forEach { put(org.json.JSONObject().put("id", it.first).put("localId", it.second)) } }))
        if (reloadChannels) { o.put("reloadChannels", true); reloadChannels = false }
        if (checkSubscription) { o.put("checkSubscription", true); checkSubscription = false }
        postal?.let { o.put("postal", org.json.JSONObject(it)) }
        fixChecks?.let { o.put("verify", org.json.JSONArray(it.toString())) }
        liveOrder?.let { o.put("liveOrder", org.json.JSONArray(it)) }
        return if (o.length() == 0) "" else "," + o.toString().removePrefix("{").removeSuffix("}")
    }
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
            path == "/api/tv/link/poll" -> 200 to (if (approved) """{"status":"approved","token":"TVTOKEN_abcdefghijklmnopqrstuvwxyz","email":"viewer@example.com","name":"Living room","deviceId":"d1"}""" else if (full) """{"status":"pending","full":true,"limit":4}""" else """{"status":"pending"}""")
            path == "/api/tv/link/claim" -> 200 to """{"status":"approved","token":"TVTOKEN_claimedabcdefghijklmnopqrst","email":"viewer@example.com","name":"Den TV","deviceId":"d2"}"""
            path == "/api/tv/me" -> if (removed || !auth.startsWith("Bearer TVTOKEN_")) 401 to """{"error":"This TV is not linked."}""" else 200 to """{"email":"viewer@example.com","name":"Living room"""" + (beta?.let { ""","beta":$it""" } ?: "") + (if (sendLog) ""","sendLog":true""" else "") + meExtras() + "}"
            path == "/api/tv/reports/verify" -> { fixAnswers += org.json.JSONObject(body); 200 to """{"ok":true}""" }
            path == "/api/tv/signout" -> 200 to """{"ok":true}"""
            path == "/api/tv/reseller/check" -> {
                val o = org.json.JSONObject(body); resellerChecks += o
                val a = o.getJSONArray("playlists")
                200 to org.json.JSONObject().put("results", org.json.JSONArray().apply { for (i in 0 until a.length()) put(org.json.JSONObject().put("localId", a.getJSONObject(i).getString("localId")).put("confirmed", true)) }).toString()
            }
            path == "/api/tv/reseller/renew" -> { resellerRenews += org.json.JSONObject(body); renew = emptyList(); 200 to """{"ok":true,"expiresAt":1}""" }
            path == "/api/tv/load-status" -> { loadStatuses += org.json.JSONObject(body); 200 to """{"ok":true}""" }
            path == "/api/tv/reports" -> {
                val linked = auth.startsWith("Bearer TVTOKEN_")
                synchronized(reports) { reports += org.json.JSONObject(body).put("auth", auth) }
                if (org.json.JSONObject(body).optString("kind") == "playback") sendLog = false
                200 to """{"ok":true,"id":"AVR-${"%08x".format(reports.size)}","linked":$linked}"""
            }
            path == "/api/tv/reports/form" -> {
                synchronized(reports) { reports += org.json.JSONObject(body).put("auth", auth).put("form", true) }
                200 to """{"code":"ABCDE23456","url":"$base/report/?c=ABCDE23456","expiresAt":${System.currentTimeMillis() + 86_400_000L}}"""
            }
            path == "/api/tv/reports/claim" -> {
                val ids = org.json.JSONObject(body).getJSONArray("ids")
                synchronized(claimed) { for (i in 0 until ids.length()) claimed += ids.getString(i) }
                200 to """{"ok":true}"""
            }
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
                    accountKey?.let { put("accountKey", it) }
                    if (path.substringAfter("ph=", "") != profilesHash()) put("profiles", profiles ?: org.json.JSONArray())
                }
            }.toString()
            path == "/api/tv/playlists" -> { devicePlaylists = org.json.JSONObject(body).getJSONArray("playlists"); 200 to """{"ok":true}""" }
            path == "/api/tv/history" -> {
                val b = org.json.JSONObject(body); historyPosts += b
                val now = System.currentTimeMillis()
                val out = org.json.JSONArray()
                val ps = b.optJSONArray("profiles") ?: org.json.JSONArray()
                for (i in 0 until ps.length()) {
                    val p = ps.getJSONObject(i); val id = p.getString("id")
                    p.optJSONArray("put")?.let { a -> for (j in 0 until a.length()) a.getJSONObject(j).let { x ->
                        val cur = historyItems["$id|${x.getString("item")}"]
                        if (historyEnabled && (cur == null || x.getLong("at") >= cur.getLong("at"))) putHistory(id, x.getString("item"), if (x.isNull("blob")) null else x.getString("blob"), x.getLong("at"))
                    } }
                    var discoverOk: Boolean? = null
                    p.optJSONObject("discover")?.let { d ->
                        if (d.has("base")) {
                            // As the site: saved only if unchanged since the TV last had it; the site's clock.
                            val base = d.optLong("base"); val stored = discoverDocs[id]?.optLong("at") ?: 0L
                            discoverOk = stored <= base
                            if (discoverOk == true) discoverDocs[id] = org.json.JSONObject().put("blob", d.getString("blob")).put("at", maxOf(now, base + 1))
                        } else if ((discoverDocs[id]?.optLong("at") ?: 0) <= d.getLong("at")) discoverDocs[id] = d
                    }
                    val since = p.optLong("since")
                    val items = org.json.JSONArray()
                    if (historyEnabled) historyItems.filter { it.key.startsWith("$id|") && it.value.getLong("seq") >= since }.values.sortedBy { it.getLong("seq") }
                        .forEach { items.put(org.json.JSONObject().put("item", it.getString("item")).put("blob", it.get("blob")).put("at", it.getLong("at"))) }
                    out.put(org.json.JSONObject().put("id", id).put("enabled", historyEnabled).put("seq", now).put("items", items).put("discover", discoverDocs[id] ?: org.json.JSONObject.NULL)
                        .apply { discoverOk?.let { put("discoverOk", it) } })
                }
                200 to org.json.JSONObject().put("enabled", historyEnabled).put("key", historySealed ?: org.json.JSONObject.NULL)
                    .put("tmdb", tmdbBlob?.let { org.json.JSONObject().put("blob", it).put("at", now) } ?: org.json.JSONObject.NULL).put("profiles", out).toString()
            }
            path == "/api/tv/sync" -> {
                syncs += org.json.JSONObject(body)
                val r = syncReply ?: org.json.JSONObject().put("profiles", org.json.JSONArray()).put("playlists", org.json.JSONArray())
                syncReply = null
                200 to r.toString()
            }
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
