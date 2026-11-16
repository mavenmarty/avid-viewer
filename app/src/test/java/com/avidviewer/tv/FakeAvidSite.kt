package com.avidviewer.tv

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * A stand-in for avidviewer.app's TV API (accounts step 2) and setup codes: /api/tv/link/start|poll|claim,
 * /api/tv/me, /api/tv/signout, /api/setup/<id>. [approved] makes the next poll succeed; [removed] makes /api/tv/me
 * answer 401 (the TV was removed on the account page). Every request is recorded in [calls] as "METHOD path body".
 */
class FakeAvidSite : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val base get() = "http://127.0.0.1:${server.localPort}"
    val calls = java.util.Collections.synchronizedList(ArrayList<String>())
    @Volatile var approved = false
    @Volatile var removed = false
    /** Setup codes it serves: fingerprint → data. */
    val setups = java.util.concurrent.ConcurrentHashMap<String, String>()

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
            path.startsWith("/api/setup/") -> setups[path.removePrefix("/api/setup/")]?.let { 200 to """{"data":"$it"}""" } ?: (404 to """{"error":"Not found."}""")
            else -> 404 to """{"error":"Not found."}"""
        }
        val bytes = json.toByteArray()
        val head = "HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        conn.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }

    override fun close() { server.close() }
}
