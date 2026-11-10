package com.avidviewer.tv

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.ui.AppRoot
import com.avidviewer.tv.ui.AppViewModel
import com.avidviewer.tv.ui.FAV_GROUP
import com.avidviewer.tv.ui.NavMode
import com.avidviewer.tv.ui.Screen
import com.avidviewer.tv.ui.holdSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Usability and crash tests: the real app UI on a TV-sized screen (960×540 dp), driven with the
 * remote's keys (arrows, OK, Back, Menu) against sample data. Runs on every build; a failure stops it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = Application::class)
class UsabilityTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var vm: AppViewModel

    @Before
    fun start() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        TestData.seed(app)
        vm = AppViewModel(app)
        rule.setContent { AppRoot(vm) }
        waitFor("channels to load") { vm.channels.isNotEmpty() }
    }

    /** Lets the app's short pauses (e.g. putting the highlight back after a move) run, then settles. */
    private fun settle(ms: Long = 250) {
        settle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
        settle()
    }

    /** Sends a key to the top-most window (a dialog when one is open). */
    private fun key(k: Key) = rule.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(k) }

    private fun press(keys: List<Key>) = keys.forEach { k -> key(k); settle() }

    /** Like waitUntil, but on timeout says what the app was doing (and where its worker threads were stuck). */
    private fun waitFor(what: String, ms: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            settle(100)
            if (cond()) return
            Thread.sleep(50)
        }
        val threads = Thread.getAllStackTraces().filter { (t, _) -> t.name.startsWith("DefaultDispatcher") || t.name == "main" || t.name.contains("Test worker") }
            .entries.joinToString("\n\n") { (t, st) -> t.name + " (" + t.state + ")\n  " + st.take(14).joinToString("\n  ") }
        throw AssertionError(
            "Timed out waiting for: $what\n" +
                "status=${vm.status} vodError=${vm.vodError} vodLoading=${vm.vodLoading} vod=${vm.vod.size} " +
                "vodPlaylists=${vm.vodPlaylists.map { it.id }} searching=${vm.searching} results=${vm.searchResults?.size}\n\n$threads",
        )
    }

    private fun go(s: Screen) { rule.runOnIdle { vm.navSelect(s) }; settle() }

    // ---------------------------------------------------------------- opening every screen

    @Test
    fun everyScreenAndSettingsSectionOpensWithoutCrashing() {
        listOf(Screen.GUIDE, Screen.SEARCH, Screen.DISCOVER, Screen.SPORTS, Screen.MOVIES, Screen.SERIES, Screen.SETTINGS).forEach { go(it) }
        listOf("playlists", "channels", "location", "display", "sports", "discover", "playback", "privacy", "storage", "about").forEach { sec ->
            rule.runOnIdle { vm.navSelect(Screen.SETTINGS); vm.settingsSection = sec }
            settle()
        }
        go(Screen.GUIDE)
        assertEquals(Screen.GUIDE, vm.screen)
    }

    @Test
    fun guideShowsChannelsFromSavedPlaylist() {
        go(Screen.GUIDE)
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "US| LOCALS") }
        waitFor("Fargo channel row") { rule.onAllNodesWithText("US: WDAY ABC 1 Fargo HD", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    // ---------------------------------------------------------------- remote navigation

    @Test
    fun dpadMovesThroughTheGuideAndBackLeavesCategories() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "NEWS") }
        settle()
        rule.onAllNodesWithText("NEWS Channel 1 with", substring = true).onFirst().requestFocus()
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionRight, Key.DirectionRight, Key.DirectionLeft, Key.DirectionUp))
        assertEquals(NavMode.GROUPS, vm.navMode)
        // Back (handled by the system's back dispatcher in the app) leaves the category list.
        rule.runOnIdle { vm.navBack() }
        assertNotEquals(NavMode.GROUPS, vm.navMode)
    }

    @Test
    fun menuReorderMovesTheChannelAndBackOnlyEndsReordering() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }
        settle()
        val first = vm.channelsIn("KIDS").first().key
        rule.runOnIdle { vm.movingChannel = first }
        settle()
        press(listOf(Key.DirectionDown, Key.DirectionDown))
        assertEquals(2, vm.channelsIn("KIDS").indexOfFirst { it.key == first })
        press(listOf(Key.DirectionRight)) // to the bottom
        assertEquals(vm.channelsIn("KIDS").size - 1, vm.channelsIn("KIDS").indexOfFirst { it.key == first })
        press(listOf(Key.DirectionLeft)) // to the top
        assertEquals(0, vm.channelsIn("KIDS").indexOfFirst { it.key == first })
        press(listOf(Key.Back))
        assertNull(vm.movingChannel)
        assertEquals(Screen.GUIDE, vm.screen)
        assertEquals(NavMode.GROUPS, vm.navMode) // Back ended re-ordering only
    }

    @Test
    fun categoryReorderJumpsToTopAndBottom() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "NEWS") }
        settle()
        rule.runOnIdle { vm.movingGroup = "KIDS" }
        settle()
        press(listOf(Key.DirectionLeft))
        assertEquals("KIDS", vm.groups().first().raw)
        press(listOf(Key.DirectionRight))
        assertEquals("KIDS", vm.groups().last().raw)
        press(listOf(Key.DirectionCenter))
        assertNull(vm.movingGroup)
    }

    @Test
    fun movieCategoryReorderAndRename() {
        go(Screen.MOVIES)
        waitFor("movies to load") { vm.vodOf("movie").isNotEmpty() }
        rule.runOnIdle { vm.vodMovingCat = "movie" to "Action" }
        settle()
        press(listOf(Key.DirectionLeft))
        assertEquals("Action", vm.vodCategories("movie").first().first)
        press(listOf(Key.Back))
        assertNull(vm.vodMovingCat)
        rule.runOnIdle { vm.renameVodCat("movie", "Action", "Action & Adventure") }
        settle()
        assertTrue(rule.onAllNodesWithText("Action & Adventure", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    // ---------------------------------------------------------------- List Editor (bulk show / hide)

    @Test
    fun listEditorHidesAndShowsMovieCategoriesInBulk() {
        rule.runOnIdle { vm.navSelect(Screen.SETTINGS); vm.settingsSection = "channels" }
        settle()
        rule.onNodeWithTag("le-tab-MOVIES").performClick()
        waitFor("movies to load in the List Editor") { vm.vodAll("movie").isNotEmpty() }
        settle()
        rule.onNodeWithTag("le-cats-hide").performClick()
        settle()
        assertTrue(vm.vodOf("movie").isEmpty())
        rule.onNodeWithTag("le-cats-show").performClick()
        settle()
        assertEquals(1140, vm.vodOf("movie").size)
    }

    @Test
    fun listEditorHidesLiveCategoriesPerPlaylist() {
        rule.runOnIdle { vm.navSelect(Screen.SETTINGS); vm.settingsSection = "channels" }
        settle()
        rule.onNodeWithTag("le-tab-LIVE").performClick()
        waitFor("live categories in the List Editor") { rule.onAllNodesWithText("NEWS", substring = false).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("le-cats-hide").performClick()
        settle()
        assertTrue(vm.groups().isEmpty())
        rule.onNodeWithTag("le-cats-show").performClick()
        settle()
        assertEquals(6, vm.groups().size)
    }

    @Test
    fun hiddenTitleLeavesMoviesAndSearch() {
        rule.runOnIdle { vm.ensureVod() }
        waitFor("movies to load for hiding") { vm.vod.isNotEmpty() }
        val t = vm.vodAll("movie").first { it.category == "Drama" }
        rule.runOnIdle { vm.setVodTitlesHidden(listOf(t.key), true) }
        assertTrue(vm.vodOf("movie").none { it.key == t.key })
    }

    // ---------------------------------------------------------------- search (previously crashed)

    @Test
    fun globalSearchFindsChannelsProgramsAndMoviesWithoutCrashing() {
        go(Screen.SEARCH)
        rule.runOnIdle { vm.search("Drama") }
        waitFor("search results") { vm.searchResults != null && !vm.searching }
        settle()
        val total = vm.searchResults!!.sumOf { it.total }
        assertTrue("search found nothing", total > 0)
    }

    @Test
    fun guideSearchCoversAllPlaylists() {
        go(Screen.GUIDE)
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "NEWS"); vm.guideQuery = "Free News" }
        waitFor("guide search results from the second playlist") { rule.onAllNodesWithText("Free News 1", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    // ---------------------------------------------------------------- favorites and lists

    @Test
    fun favoritesAndCustomListsShowInTheGuide() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, FAV_GROUP) }
        settle()
        assertEquals(2, vm.channelsIn(FAV_GROUP).size)
        rule.runOnIdle { vm.setListHidden(vm.allLists()[1].first, true) }
        assertTrue(vm.shownLists.isEmpty())
    }

    // ---------------------------------------------------------------- hold to accelerate

    @Test
    fun holdingAKeySpeedsUpInSteps() {
        fun ev(repeat: Int) = KeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_DOWN, repeat))
        assertEquals(1, holdSteps(ev(0)))
        assertEquals(1, holdSteps(ev(5)))
        assertEquals(3, holdSteps(ev(10)))
        assertEquals(6, holdSteps(ev(20)))
        assertEquals(12, holdSteps(ev(50)))
    }

    // ---------------------------------------------------------------- crash hunting

    /**
     * Presses 600 random remote keys (arrows, OK, Back, Menu), jumping to a different screen every
     * 60 presses. Any crash anywhere fails the test with its stack trace. Fixed seed, so a failure repeats.
     */
    @Test
    fun randomRemotePressesNeverCrash() {
        // Back is the system back action in the app, so it's simulated by calling what it calls.
        val keys = listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Back, Key.Menu)
        val weights = listOf(5, 5, 4, 4, 2, 1, 1)
        val screens = listOf(Screen.GUIDE, Screen.MOVIES, Screen.SETTINGS, Screen.SPORTS, Screen.SERIES, Screen.SEARCH, Screen.DISCOVER)
        val rnd = Random(20261109)
        for (i in 0 until 600) {
            if (i % 60 == 0) go(screens[(i / 60) % screens.size])
            var r = rnd.nextInt(weights.sum()); var k = 0
            while (r >= weights[k]) { r -= weights[k]; k++ }
            if (keys[k] == Key.Back) rule.runOnIdle { if (vm.screen == Screen.PLAYER) vm.closeFullScreen() else vm.navBack() }
            else key(keys[k])
            if (i % 10 == 0) settle()
            if (vm.screen == Screen.PLAYER && rnd.nextInt(4) == 0) rule.runOnIdle { vm.closeFullScreen() }
        }
        settle()
    }
}
