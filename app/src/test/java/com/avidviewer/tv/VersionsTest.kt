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
}
