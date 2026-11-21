package com.avidviewer.tv

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.Store
import com.avidviewer.tv.ui.AppViewModel
import com.avidviewer.tv.ui.GENRE_CAT
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import com.avidviewer.tv.data.Catalog.GenreFound as Found

/** Movies and Series by TMDB genre (Nov 21, 2026): genres replace the provider's categories when the profile asks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GenreSortTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val made = ArrayList<AppViewModel>()
    private val asked: MutableList<String> = Collections.synchronizedList(ArrayList())

    @Before
    fun setUp() {
        TestData.seed(app)
        val store = Store(app)
        store.savePrefs(store.loadPrefs().copy(tmdbKeyEnc = "test-only", autoUpdateCheck = false))
    }

    @After
    fun stop() {
        made.forEach { it.viewModelScope.cancel() }
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun newVm() = AppViewModel(app).also { vm ->
        made.add(vm)
        vm.tmdbKeyForTests = "k"; vm.genreDelayMs = 0
        vm.genreFinder = { _, name, type ->
            asked.add("$type|$name")
            when {
                name.startsWith("Action Movie 1 ") -> Found(listOf(28, 12), 6.1, 900)
                name.startsWith("Action") -> Found(listOf(28), 6.0, 900)
                name.startsWith("Drama Movie 7 ") -> Found(listOf(18), 8.6, 5000)
                name.startsWith("Drama") -> Found(listOf(18), 7.5, 1200)
                name.startsWith("Crime") -> Found(listOf(80), 8.0, 40)
                else -> null
            }
        }
        vm.trendingFinder = { _, type -> if (type == "movie") listOf("Not On Any Playlist", "Drama Movie 3", "Action Movie 2") else emptyList() }
    }

    private fun idleUntil(what: String, ms: Long = 60_000, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > until) throw AssertionError("Timed out waiting for: $what")
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(20)
        }
    }

    private fun names(vm: AppViewModel, kind: String) = vm.vodCategories(kind).map { vm.vodCatName(kind, it.first) }

    private fun sorted(vm: AppViewModel) {
        vm.ensureVod()
        idleUntil("movies") { vm.vod.isNotEmpty() }
        vm.setGenreSort(true)
        idleUntil("genre lookups") { !vm.genreLookupsRunning() && "Not sorted yet" !in names(vm, "movie") && "Not sorted yet" !in names(vm, "series") }
    }

    @Test
    fun genresReplaceTheProvidersCategoriesAndEachTitleIsLookedUpOnce() {
        val vm = newVm()
        vm.ensureVod()
        idleUntil("movies") { vm.vod.isNotEmpty() }
        assertTrue("on by default with a TMDB key", vm.genresOn())
        vm.setGenreSort(false)
        assertTrue("off: the provider's categories", "Drama" in names(vm, "movie"))
        sorted(vm)

        val movies = names(vm, "movie")
        assertEquals(listOf("Trending this week", "Top rated", "Action", "Adventure", "Drama", "Other (not on TMDB)"), movies)
        assertEquals("a title is looked up once, by name and year", asked.size, asked.toSet().size)
        assertTrue(asked.any { it.startsWith("movie|Drama Movie 3 (") })

        vm.chooseVodCategory("movie", GENRE_CAT + com.avidviewer.tv.data.Catalog.GENRE_TRENDING)
        assertEquals("trending titles on the playlists, in TMDB's order", listOf("Drama Movie 3", "Action Movie 2"), vm.vodItems("movie").map { it.name })
        vm.chooseVodCategory("movie", GENRE_CAT + com.avidviewer.tv.data.Catalog.GENRE_TOP)
        val top = vm.vodItems("movie").map { it.name }
        assertEquals("rated 7+ with enough votes, best first", 20, top.size)
        assertEquals("Drama Movie 7", top.first())
        vm.chooseVodCategory("movie", GENRE_CAT + 28)
        assertEquals(15, vm.vodItems("movie").size)
        vm.chooseVodCategory("movie", GENRE_CAT + 12)
        assertEquals("a title can be in more than one genre", listOf("Action Movie 1"), vm.vodItems("movie").map { it.name })
        vm.chooseVodCategory("movie", GENRE_CAT + com.avidviewer.tv.data.Catalog.GENRE_OTHER)
        assertTrue("titles TMDB doesn't know", vm.vodItems("movie").size > 1000)
        assertEquals("too few votes for Top rated", listOf("Crime", "Other (not on TMDB)"), names(vm, "series"))

        // Hide, rename and order work on genres.
        vm.hideVodCatEverywhere("movie", GENRE_CAT + 18)
        assertFalse("Drama" in names(vm, "movie"))
        vm.showHiddenGenres()
        assertTrue("Drama" in names(vm, "movie"))
        vm.renameVodCat("movie", GENRE_CAT + 18, "Dramas")
        vm.moveVodCatTo("movie", GENRE_CAT + 18, 0)
        assertEquals("Dramas", names(vm, "movie")[2])

        // Off: the provider's categories again; the lookups are kept for next time.
        vm.setGenreSort(false)
        assertTrue("Drama" in names(vm, "movie") && "Other (not on TMDB)" !in names(vm, "movie"))
        val before = asked.size
        vm.setGenreSort(true)
        idleUntil("lookups") { !vm.genreLookupsRunning() }
        assertEquals("nothing looked up twice", before, asked.size)
    }

    @Test
    fun aKidsProfileNeverSortsByGenreOrLooksAnythingUp() {
        val vm = newVm()
        val kids = vm.addProfile("Kids", kids = true, color = 0)
        vm.chooseProfile(kids)
        idleUntil("watching as Kids") { vm.kidsMode }
        vm.setGenreSort(true)
        vm.startGenreLookups()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertFalse(vm.genresOn())
        assertFalse(vm.genreLookupsRunning())
        assertTrue(asked.isEmpty())
    }
}
