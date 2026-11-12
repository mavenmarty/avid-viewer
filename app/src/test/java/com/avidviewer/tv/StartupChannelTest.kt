package com.avidviewer.tv

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.Store
import com.avidviewer.tv.ui.AppViewModel
import com.avidviewer.tv.ui.FAV_GROUP
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Which channel the app starts on: the last one watched, else the first favorite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StartupChannelTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val made = ArrayList<AppViewModel>()

    @After
    fun stop() {
        made.forEach { it.viewModelScope.cancel() }
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun idleUntil(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!ok()) {
            if (System.currentTimeMillis() > until) throw AssertionError("Timed out waiting for: $what")
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(20)
        }
    }

    private fun start(change: (com.avidviewer.tv.data.Prefs) -> com.avidviewer.tv.data.Prefs): AppViewModel {
        TestData.seed(app)
        val store = Store(app)
        store.savePrefs(change(store.loadPrefs()))
        return AppViewModel(app).also { made.add(it) }
    }

    @Test
    fun startsOnTheLastChannelWatched() {
        val vm = start { it.copy(lastChannel = "${TestData.P1}:c3-5") }
        idleUntil("a channel") { vm.currentKey != null }
        assertEquals("${TestData.P1}:c3-5", vm.currentKey)
        assertEquals("its own category", "VOD | EN - Movies 4K", vm.selectedGroup)
    }

    @Test
    fun withoutALastChannelStartsOnTheFirstFavorite() {
        val vm = start { it.copy(lastChannel = null) }
        idleUntil("a channel") { vm.currentKey != null }
        assertEquals("${TestData.P1}:c1-1", vm.currentKey)
        assertEquals(FAV_GROUP, vm.selectedGroup)
    }

    @Test
    fun aLastChannelThatIsGoneFallsBackToTheFirstFavorite() {
        val vm = start { it.copy(lastChannel = "${TestData.P1}:no-such-channel") }
        idleUntil("a channel") { vm.currentKey != null }
        assertEquals("${TestData.P1}:c1-1", vm.currentKey)
        assertEquals(FAV_GROUP, vm.selectedGroup)
    }

    @Test
    fun aLastChannelFromAnotherPlaylistsFavoritePlaysFromFavorites() {
        val vm = start { it.copy(lastChannel = "${TestData.P2}:f4", favorites = it.favorites + "${TestData.P2}:f4") }
        idleUntil("a channel") { vm.currentKey != null }
        assertEquals("${TestData.P2}:f4", vm.currentKey)
        assertEquals(FAV_GROUP, vm.selectedGroup)
    }

    @Test
    fun withNoFavoritesEitherStartsOnTheFirstChannelOfTheFirstCategory() {
        val vm = start { it.copy(lastChannel = null, favorites = emptyList()) }
        idleUntil("a channel") { vm.currentKey != null }
        val first = vm.groups().first().raw
        assertEquals(first, vm.selectedGroup)
        assertEquals(vm.channelsIn(first).first().key, vm.currentKey)
    }
}
