package com.avidviewer.tv

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.ui.AppViewModel
import com.avidviewer.tv.ui.NavMode
import com.avidviewer.tv.ui.Screen
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Nov 21, 2026: the live stream stops when no video is on screen (another screen for 10 s, or the main menu for
 * 1.5 s), so a slow TV isn't decoding video nobody sees; the guide then fills the screen until a channel is picked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StopWhenNoVideoTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val made = ArrayList<AppViewModel>()

    @After
    fun stop() {
        made.forEach { it.viewModelScope.cancel() }
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun idle(ms: Long) = shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    private fun idleUntil(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!ok()) {
            if (System.currentTimeMillis() > until) throw AssertionError("Timed out waiting for: $what")
            idle(100); Thread.sleep(20)
        }
    }

    @Test
    fun theStreamStopsWhenNoVideoIsOnScreenAndOnlyAChannelStartsItAgain() {
        TestData.seed(app)
        val vm = AppViewModel(app).also { made.add(it) }
        vm.stopWhenNoVideo = true
        idleUntil("channels") { vm.channels.isNotEmpty() }
        val key = vm.channels.first().key
        vm.preview(key); idle(200)
        assertTrue(vm.player.mediaItemCount > 0)

        // A quick look at Settings: still playing.
        vm.navSelect(Screen.SETTINGS); idle(4_000)
        vm.navSelect(Screen.GUIDE); vm.navMode = NavMode.GROUPS; idle(15_000)
        assertFalse(vm.streamParked)
        assertTrue(vm.player.mediaItemCount > 0)

        // Settings for longer: the stream stops, and coming back doesn't start it.
        vm.navSelect(Screen.SETTINGS); idle(12_000)
        assertTrue(vm.streamParked)
        assertEquals(0, vm.player.mediaItemCount)
        vm.navSelect(Screen.GUIDE); vm.navMode = NavMode.GROUPS; idle(15_000)
        assertTrue("the guide fills the screen until a channel is picked", vm.streamParked)
        assertEquals(key, vm.currentKey)

        // OK on a channel plays it in the preview again.
        vm.selectChannel(key, com.avidviewer.tv.ui.PlayContext.GROUP); idle(200)
        assertFalse(vm.streamParked)
        assertTrue(vm.player.mediaItemCount > 0)

        // The main menu over the guide stops it sooner.
        vm.navMode = NavMode.FULL; idle(3_000)
        assertTrue(vm.streamParked)
    }

    @Test
    fun searchesDontFollowYouIntoAnotherProfile() {
        // Beta report (Nov 20): text typed into the grown-up profile's Movies search was still there in the Kids profile.
        TestData.seed(app)
        val vm = AppViewModel(app).also { made.add(it) }
        idleUntil("channels") { vm.channels.isNotEmpty() }
        val kids = vm.addProfile("Kids", kids = true, color = 0)
        vm.vodQuery = mapOf("movie" to "horror", "series" to "crime")
        vm.guideQuery = "news"; vm.searchQuery = "fargo"
        vm.chooseProfile(kids)
        idleUntil("watching as Kids") { vm.kidsMode }
        assertEquals(mapOf("movie" to "", "series" to ""), vm.vodQuery)
        assertEquals("", vm.guideQuery)
        assertEquals("", vm.searchQuery)
    }
}
