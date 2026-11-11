package com.avidviewer.tv

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.Store
import com.avidviewer.tv.data.Title
import com.avidviewer.tv.data.VodItem
import com.avidviewer.tv.ui.AppViewModel
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Issue #1: Discover crashed a Fire TV after 10–15 s. Opening Discover loads the saved movie & series
 * catalog; reading that file as one big string and building the "On your provider" lookup on the
 * screen's thread ran a small device out of memory and froze navigation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DiscoverMemoryTest {

    private val made = ArrayList<AppViewModel>()
    private fun track(vm: AppViewModel) = vm.also { made.add(it) }

    /** Stops each test's background work (downloads, timers) so nothing runs into the next test. */
    @org.junit.After
    fun stopBackgroundWork() {
        made.forEach { vm -> vm.viewModelScope.cancel() }
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun catalog(n: Int) = List(n) { i ->
        VodItem("big:m$i", "big", if (i % 3 == 0) "series" else "movie", "EN - Title number $i [4K] (20${10 + i % 15})",
            if (i % 2 == 0) "http://img/$i.jpg" else null, "http://stream/$i", seriesId = if (i % 3 == 0) "$i" else null,
            category = "Cat ${i % 40}", added = 1_700_000_000L + i, rating = "7.${i % 10}", year = "20${10 + i % 15}")
    }

    @Test
    fun aLargeCatalogSavesAndLoadsItemByItem() {
        val store = Store(app)
        val list = catalog(60_000)
        store.saveVod("big", list)
        assertEquals(list, store.loadVod("big"))
    }

    @Test
    fun catalogsSavedByEarlierVersionsStillLoad() {
        val list = catalog(500)
        // The old format: one JSONArray written as a whole.
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        File(File(app.filesDir, "data").apply { mkdirs() }, "vod_old.json").writeText(a.toString())
        assertEquals(list, Store(app).loadVod("old"))
    }

    @Test
    fun aDamagedCatalogFileLoadsAsEmptyInsteadOfCrashing() {
        File(File(app.filesDir, "data").apply { mkdirs() }, "vod_bad.json").writeText("[{\"k\":\"a\",")
        assertTrue(Store(app).loadVod("bad").isEmpty())
    }

    @Test
    fun providerMatchingDoesNotBlockTheScreen() {
        val list = catalog(120_000) + VodItem("big:x", "big", "movie", "EN - The Matrix [4K]", null, "http://x")
        TestData.seed(app)
        Store(app).saveVod(TestData.P1, list)
        val vm = track(AppViewModel(app))
        vm.ensureVod()
        // Let the catalog load.
        val until = System.currentTimeMillis() + 60_000
        while (vm.vod.size < list.size && System.currentTimeMillis() < until) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(20) }
        assertTrue("catalog loaded (${vm.vod.size})", vm.vod.size >= list.size)
        val matrix = Title(603, "movie", "The Matrix", "1999", "", null, null, 8.2, emptyList(), "1999-03-31")
        // The first call on the screen's thread returns at once (the lookup builds in the background).
        val started = System.nanoTime()
        vm.matchVod(matrix)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("matchVod blocked for $ms ms", ms < 100)
        val done = System.currentTimeMillis() + 30_000
        while (vm.matchVod(matrix) == null && System.currentTimeMillis() < done) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(20) }
        assertNotNull("the provider's copy is found once the lookup is ready", vm.matchVod(matrix))
    }
}
