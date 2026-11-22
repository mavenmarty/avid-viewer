package com.avidviewer.tv

import android.app.Application
import com.avidviewer.tv.data.Tmdb
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.Collections

/** Nov 22, 2026: rating titles on TMDB with the profile's own TMDB account (link by approving a request token). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TmdbAccountTest {
    private lateinit var server: ServerSocket
    private val seen: MutableList<String> = Collections.synchronizedList(ArrayList())
    @Volatile private var approved = false
    @Volatile private var rating: Double? = null
    private var oldBase = ""

    private fun answer(method: String, path: String, body: String): Pair<Int, JSONObject> = when {
        path == "/authentication/token/new" -> 200 to JSONObject().put("success", true).put("request_token", "tok123")
        path == "/authentication/session/new" -> if (approved) 200 to JSONObject().put("success", true).put("session_id", "sess9")
            else 401 to JSONObject().put("status_message", "Authentication failed: request token not approved")
        path == "/account" -> 200 to JSONObject().put("id", 1).put("username", "evan")
        path == "/movie/603/account_states" -> 200 to JSONObject().put("id", 603).put("rated", rating?.let { JSONObject().put("value", it) } ?: false)
        path == "/movie/603/rating" && method == "POST" -> { rating = JSONObject(body).getDouble("value"); 201 to JSONObject().put("success", true) }
        path == "/movie/603/rating" && method == "DELETE" -> { rating = null; 200 to JSONObject().put("success", true) }
        else -> 404 to JSONObject().put("status_message", "not here")
    }

    @Before
    fun start() {
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                c.use { conn ->
                    val input = conn.getInputStream().bufferedReader()
                    val first = input.readLine() ?: return@use
                    var len = 0
                    while (true) { val l = input.readLine() ?: break; if (l.isEmpty()) break; if (l.startsWith("Content-Length:", true)) len = l.substringAfter(':').trim().toInt() }
                    val body = if (len > 0) CharArray(len).also { var n = 0; while (n < len) { val r = input.read(it, n, len - n); if (r < 0) break; n += r } }.concatToString() else ""
                    val (method, target) = first.split(" ").let { it[0] to it[1] }
                    val path = target.substringBefore('?').removePrefix("/3"); val query = target.substringAfter('?', "")
                    seen.add("$method $path $query $body")
                    val (code, out) = answer(method, path, body)
                    val bytes = out.toString().toByteArray()
                    conn.getOutputStream().apply {
                        write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush()
                    }
                }
            }
        }
        oldBase = Tmdb.base
        Tmdb.base = "http://127.0.0.1:${server.localPort}/3"
    }

    @After
    fun stop() { Tmdb.base = oldBase; server.close() }

    @Test
    fun linkThenRateAndRemoveTheRating() {
        val key = "0123456789abcdef0123456789abcdef"
        val token = Tmdb.newRequestToken(key)
        assertEquals("https://www.themoviedb.org/authenticate/tok123", Tmdb.approveUrl(token))
        assertNull("not approved yet: keep waiting", Tmdb.newSession(key, token))
        approved = true
        val session = Tmdb.newSession(key, token)!!
        assertEquals("sess9", session)
        assertEquals("evan", Tmdb.accountName(key, session))
        assertNull(Tmdb.myRating(key, "movie", 603, session))
        Tmdb.rate(key, "movie", 603, session, 8.3)
        assertEquals("rounded to TMDB's half steps", 8.5, Tmdb.myRating(key, "movie", 603, session)!!, 0.0)
        Tmdb.rate(key, "movie", 603, session, null)
        assertNull(Tmdb.myRating(key, "movie", 603, session))
        assertEquals("every account call carries the session", true, seen.filter { it.contains("/movie/603") }.all { it.contains("session_id=sess9") })
    }
}
