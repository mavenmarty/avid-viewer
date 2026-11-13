package com.avidviewer.tv

import com.avidviewer.tv.data.Net
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** DATA-08: a busy server or a dropped connection is tried again (twice) before a download gives up. */
class NetRetryTest {
    /** Answers each request with the next of [codes] (the last one repeats). */
    private fun serve(vararg codes: Int, body: String = "hello", use: (String, () -> Int) -> Unit) {
        val socket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        var served = 0
        val t = Thread {
            while (!socket.isClosed) {
                val c = runCatching { socket.accept() }.getOrNull() ?: break
                c.use {
                    val r = it.getInputStream().bufferedReader()
                    while (true) { val line = r.readLine() ?: break; if (line.isEmpty()) break }
                    val code = codes[minOf(served, codes.size - 1)]; served++
                    val text = if (code == 200) body else "busy"
                    it.getOutputStream().write("HTTP/1.1 $code X\r\nContent-Length: ${text.length}\r\nConnection: close\r\n\r\n$text".toByteArray())
                }
            }
        }.apply { isDaemon = true; start() }
        try { use("http://127.0.0.1:${socket.localPort}/x") { served } } finally { socket.close(); t.join(1000) }
    }

    @Test
    fun aBusyServerIsTriedAgain() {
        Net.backoffMs = longArrayOf(5L, 5L)
        serve(503, 503, 200) { url, served ->
            assertEquals("hello", Net.text(url))
            assertEquals(3, served())
        }
    }

    @Test
    fun itGivesUpAfterThreeTries() {
        Net.backoffMs = longArrayOf(5L, 5L)
        serve(503) { url, served ->
            val e = runCatching { Net.text(url) }.exceptionOrNull()
            assertTrue("$e", e is IOException && e.message!!.contains("503"))
            assertEquals(3, served())
        }
    }

    @Test
    fun aNotFoundIsNotRetried() {
        Net.backoffMs = longArrayOf(5L, 5L)
        serve(404) { url, served ->
            assertTrue(runCatching { Net.text(url) }.isFailure)
            assertEquals(1, served())
        }
    }
}
