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
}
