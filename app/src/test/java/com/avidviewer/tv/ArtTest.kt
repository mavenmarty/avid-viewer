package com.avidviewer.tv

import android.app.Application
import com.avidviewer.tv.data.Art
import com.avidviewer.tv.data.ArtStore
import com.avidviewer.tv.data.VodItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

/** Artwork and details from TMDB (Nov 18, 2026): looked up once per title and kept on the TV. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ArtTest {
    private fun movie(name: String, year: String = "") = VodItem("p:m1", "p", "movie", name, null, "u", year = year)

    @Test
    fun artIsKeptByTitleAndSurvivesARestart() {
        val dir = Files.createTempDirectory("art").toFile()
        val store = ArtStore(dir)
        val a = movie("EN | The Quiet Orbit 4K", "2024"); val b = movie("The Quiet Orbit", "2024")
        assertEquals("the provider's tags don't change the key", store.key(a), store.key(b))
        assertNull(store.get(store.key(a)))
        store.put(store.key(a), Art("https://image.tmdb.org/t/p/w1280/x.jpg", "Plot", 7.94, 4213, listOf(878)))
        store.put(store.key(movie("Nothing Like It")), null)
        val again = ArtStore(dir)
        val got = again.get(again.key(b))!!.second!!
        assertEquals("7.9", got.ratingText); assertEquals("4.2K votes", got.votesText); assertEquals("Sci-fi", Art.genreName(got.genres.first()))
        assertNotNull("a title with no match is remembered too", again.get(again.key(movie("Nothing Like It"))))
        assertNull(again.get(again.key(movie("Nothing Like It")))!!.second)
    }

    @Test
    fun theBiggerBackdropIsUsedAndUnratedTitlesShowNoRating() {
        assertEquals("https://image.tmdb.org/t/p/w1280/a.jpg", Art.big("https://image.tmdb.org/t/p/w780/a.jpg"))
        assertNull(Art(null, "", 0.0, 0).ratingText)
        assertEquals("1.2M votes", Art(null, "", 8.0, 1_234_567).votesText)
    }

    // ---- Nov 21, 2026: content ratings (R, PG-13, TV-14) ----

    @Test
    fun contentRatingsAreReadFromTmdbAndKeptWithTheArtwork() {
        val movie = org.json.JSONObject("""{"results":[{"iso_3166_1":"GB","release_dates":[{"certification":"15","type":3}]},
            {"iso_3166_1":"US","release_dates":[{"certification":"","type":1},{"certification":"PG-13","type":3},{"certification":"R","type":4}]}]}""")
        assertEquals("theatrical first", "PG-13", com.avidviewer.tv.data.Tmdb.movieCertification(movie, "US"))
        assertEquals("the US rating when the region has none", "PG-13", com.avidviewer.tv.data.Tmdb.movieCertification(movie, "CA"))
        val tv = org.json.JSONObject("""{"results":[{"iso_3166_1":"US","rating":"TV-14"}]}""")
        assertEquals("TV-14", com.avidviewer.tv.data.Tmdb.tvCertification(tv, "US"))
        assertEquals("", com.avidviewer.tv.data.Tmdb.tvCertification(org.json.JSONObject("{}"), "US"))

        val dir = Files.createTempDirectory("art").toFile()
        val store = ArtStore(dir)
        store.put("a", Art(null, "", 7.0, 10, emptyList(), 603, "R"))
        store.put("b", Art(null, "", 7.0, 10, emptyList(), 604, null))
        val again = ArtStore(dir)
        assertEquals("R", again.get("a")!!.second!!.cert); assertEquals(603, again.get("a")!!.second!!.tmdbId)
        assertNull("not looked up yet stays unknown", again.get("b")!!.second!!.cert)
    }

    @Test
    fun providerRatingsAreTidiedAndUnknownOnesLeftOut() {
        val c = com.avidviewer.tv.data.Certification
        assertEquals("PG-13", c.clean("Rated pg-13")); assertEquals("TV-14", c.clean("TV14")); assertEquals("TV-MA", c.clean(" tv-ma "))
        assertEquals("R", c.clean("R")); assertEquals("", c.clean("16+")); assertEquals("", c.clean("7.5"))
    }
}
