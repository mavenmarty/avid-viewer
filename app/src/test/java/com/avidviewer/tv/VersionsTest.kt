package com.avidviewer.tv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.Catalog
import com.avidviewer.tv.data.Language
import com.avidviewer.tv.data.Tmdb
import com.avidviewer.tv.data.VodItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Discover lists every version of a title on your provider (each language and playlist), English first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VersionsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var catalog: Catalog

    @Before fun open() { app.deleteDatabase("catalog.db"); catalog = Catalog(app) }
    @After fun close() { catalog.close(); app.deleteDatabase("catalog.db") }

    @Test
    fun languagesAreReadFromTheNameOrCategory() {
        assertEquals("English", Language.of("EN | MobLand", null))
        assertEquals("French", Language.of("MobLand", "FR | Séries"))
        assertEquals("French", Language.of("MobLand (VF)", "Séries"))
        assertEquals("Multi-language", Language.of("MULTI - MobLand", null))
        assertEquals(null, Language.of("MobLand", "Drama"))
        assertEquals(listOf("English", null, "Multi-language", "French"),
            listOf("French", "Multi-language", null, "English").sortedBy { Language.rank(it) })
    }

    @Test
    fun everyVersionIsFoundEnglishFirstAndHiddenOnesLeftOut() {
        fun s(key: String, p: String, name: String, cat: String) = VodItem(key, p, "series", name, null, "", seriesId = key, category = cat)
        catalog.replace("a", sequenceOf(s("a:1", "a", "FR | MobLand", "FR | Séries"), s("a:2", "a", "EN | MobLand", "EN | Drama"), s("a:3", "a", "DE | MobLand", "DE | Serien"))) { it.key == "a:3" }
        catalog.replace("b", sequenceOf(s("b:1", "b", "MobLand", "Crime"), s("b:2", "b", "Other Show", "Crime"))) { false }
        val found = catalog.findAllByName("series", Tmdb.normalize("MobLand"), listOf("a", "b"))
            .sortedBy { Language.rank(Language.of(it.name, it.category)) }
        assertEquals(listOf("a:2", "b:1", "a:1"), found.map { it.key })
    }

    @Test
    fun moviesAndSeriesListEachTitleOnceWithItsEnglishCopy() {
        fun s(key: String, p: String, name: String, cat: String, added: Long = 0) = VodItem(key, p, "series", name, null, "", seriesId = key, category = cat, added = added)
        catalog.replace("a", sequenceOf(
            s("a:1", "a", "FR | MobLand", "FR | Séries", added = 5),
            s("a:2", "a", "Alpha Show", "Drama", added = 1),
            s("a:3", "a", "EN | MobLand (2025)", "EN | Drama", added = 2),
            s("a:4", "a", "DE | MobLand", "DE | Serien", added = 9),
        )) { it.key == "a:4" }
        catalog.replace("b", sequenceOf(s("b:1", "b", "MobLand 4K", "Crime", added = 3), s("b:2", "b", "Zeta Show", "Crime", added = 4))) { false }
        val q = Catalog.Query("series", listOf("a", "b"), grouped = true)
        assertEquals(3, catalog.count(q))
        // Each title once, in the list's order of its English copy (MobLand is represented by a:3, after Alpha).
        assertEquals(listOf("a:2", "a:3", "b:2"), catalog.page(q, 0, 10).map { it.key })
        assertEquals(listOf("a:3"), catalog.page(q, 1, 1).map { it.key })
        // Ungrouped still lists every copy (List Editor, hiding).
        assertEquals(5, catalog.count(Catalog.Query("series", listOf("a", "b"))))
        // Recently added: newest copy order, but one entry per title (the English copy's date).
        assertEquals(listOf("b:2", "a:3", "a:2"), catalog.page(Catalog.Query("series", listOf("a", "b"), newestFirst = true, grouped = true), 0, 10).map { it.key })
        // Inside one category, copies of the same title fold together too.
        catalog.replace("c", sequenceOf(s("c:1", "c", "Gamma HD", "Crime"), s("c:2", "c", "Gamma 4K", "Crime"), s("c:3", "c", "Delta", "Crime"))) { false }
        assertEquals(2, catalog.count(Catalog.Query("series", listOf("c"), category = "Crime", grouped = true)))
        // A title with no usable name doesn't swallow the others.
        catalog.replace("d", sequenceOf(s("d:1", "d", "EN |", "X"), s("d:2", "d", "FR |", "X"))) { false }
        assertEquals(2, catalog.count(Catalog.Query("series", listOf("d"), grouped = true)))
    }
}
