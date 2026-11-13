package com.avidviewer.tv

import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * A stand-in for the crash relay (a Google Apps Script web app): a POST to /exec is recorded and
 * answered with a redirect to /result, which says whether the report was sent ([ok]).
 */
class FakeRelay : AutoCloseable {
    private val server = ServerSocket(0)
    val posts = java.util.Collections.synchronizedList(ArrayList<String>())
    @Volatile var ok = true
    val url get() = "http://127.0.0.1:${server.localPort}/exec"

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    c.use { conn ->
                        val input = conn.getInputStream()
                        fun line(): String? {
                            val sb = StringBuilder()
                            while (true) {
                                val b = input.read()
                                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                                sb.append(b.toChar())
                            }
                        }
                        val first = line() ?: return@use
                        var length = 0
                        while (true) {
                            val l = line() ?: break
                            if (l.isEmpty()) break
                            if (l.startsWith("Content-Length:", ignoreCase = true)) length = l.substringAfter(':').trim().toInt()
                        }
                        val body = ByteArray(length)
                        var n = 0
                        while (n < length) { val r = input.read(body, n, length - n); if (r < 0) break; n += r }
                        val out = conn.getOutputStream()
                        if (first.startsWith("POST") && first.contains("/exec")) {
                            posts.add(body.toString(Charsets.UTF_8))
                            out.write("HTTP/1.1 302 Found\r\nLocation: /result\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else {
                            val b = (if (ok) """{"ok":true,"code":200}""" else """{"ok":false,"code":429}""").toByteArray()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(b)
                        }
                        out.flush()
                    }
                }
            }
        }
    }

    override fun close() = server.close()
}
