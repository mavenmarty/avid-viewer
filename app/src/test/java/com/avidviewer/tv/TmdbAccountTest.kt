package com.avidviewer.tv

import android.app.Application
import com.avidviewer.tv.data.Tmdb
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.Collections

/** Nov 22, 2026: rating titles on TMDB with the profile's own TMDB account (link by approving a request token). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TmdbAccountTest {
    private lateinit var server: HttpServer
    private val seen: MutableList<String> = Collections.synchronizedList(ArrayList())
    @Volatile private var approved = false
    @Volatile private var rating: Double? = null
    private var oldBase = ""

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/3/") { ex ->
            val path = ex.requestURI.path.removePrefix("/3")
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen.add("${ex.requestMethod} $path ${ex.requestURI.query.orEmpty()} $body")
            val (code, out) = when {
                path == "/authentication/token/new" -> 200 to JSONObject().put("success", true).put("request_token", "tok123")
                path == "/authentication/session/new" -> if (approved) 200 to JSONObject().put("success", true).put("session_id", "sess9")
                    else 401 to JSONObject().put("status_message", "Authentication failed: request token not approved")
                path == "/account" -> 200 to JSONObject().put("id", 1).put("username", "evan")
                path == "/movie/603/account_states" -> 200 to JSONObject().put("id", 603).put("rated", rating?.let { JSONObject().put("value", it) } ?: false)
                path == "/movie/603/rating" && ex.requestMethod == "POST" -> { rating = JSONObject(body).getDouble("value"); 201 to JSONObject().put("success", true) }
                path == "/movie/603/rating" && ex.requestMethod == "DELETE" -> { rating = null; 200 to JSONObject().put("success", true) }
                else -> 404 to JSONObject().put("status_message", "not here")
            }
            val bytes = out.toString().toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        oldBase = Tmdb.base
        Tmdb.base = "http://127.0.0.1:${server.address.port}/3"
    }

    @After
    fun stop() { Tmdb.base = oldBase; server.stop(0) }

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
