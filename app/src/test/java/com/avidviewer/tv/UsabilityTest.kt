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
        rule.waitUntil(15_000) { vm.channels.isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(keys: List<Key>) = keys.forEach { k -> rule.onRoot().performKeyInput { pressKey(k) }; rule.waitForIdle() }

    private fun go(s: Screen) { rule.runOnIdle { vm.navSelect(s) }; rule.waitForIdle() }

    // ---------------------------------------------------------------- opening every screen

    @Test
    fun everyScreenAndSettingsSectionOpensWithoutCrashing() {
        listOf(Screen.GUIDE, Screen.SEARCH, Screen.DISCOVER, Screen.SPORTS, Screen.MOVIES, Screen.SERIES, Screen.SETTINGS).forEach { go(it) }
        listOf("playlists", "channels", "location", "display", "sports", "discover", "playback", "privacy", "storage", "about").forEach { sec ->
            rule.runOnIdle { vm.navSelect(Screen.SETTINGS); vm.settingsSection = sec }
            rule.waitForIdle()
        }
        go(Screen.GUIDE)
        assertEquals(Screen.GUIDE, vm.screen)
    }

    @Test
    fun guideShowsChannelsFromSavedPlaylist() {
        go(Screen.GUIDE)
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "US| LOCALS") }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("US: WDAY ABC 1 Fargo HD", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    // ---------------------------------------------------------------- remote navigation

    @Test
    fun dpadMovesThroughTheGuideAndBackLeavesCategories() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "NEWS") }
        rule.waitForIdle()
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
        rule.waitForIdle()
        val first = vm.channelsIn("KIDS").first().key
        rule.runOnIdle { vm.movingChannel = first }
        rule.waitForIdle()
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
        rule.waitForIdle()
        rule.runOnIdle { vm.movingGroup = "KIDS" }
        rule.waitForIdle()
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
        rule.waitUntil(10_000) { vm.vodOf("movie").isNotEmpty() }
        rule.runOnIdle { vm.vodMovingCat = "movie" to "Action" }
        rule.waitForIdle()
        press(listOf(Key.DirectionLeft))
        assertEquals("Action", vm.vodCategories("movie").first().first)
        press(listOf(Key.Back))
        assertNull(vm.vodMovingCat)
        rule.runOnIdle { vm.renameVodCat("movie", "Action", "Action & Adventure") }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Action & Adventure", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    // ---------------------------------------------------------------- List Editor (bulk show / hide)

    @Test
    fun listEditorHidesAndShowsMovieCategoriesInBulk() {
        rule.runOnIdle { vm.navSelect(Screen.SETTINGS); vm.settingsSection = "channels" }
        rule.waitForIdle()
        rule.onNodeWithTag("le-tab-MOVIES").performClick()
        rule.waitUntil(10_000) { vm.vodAll("movie").isNotEmpty() }
        rule.waitForIdle()
        rule.onNodeWithTag("le-cats-hide").performClick()
        rule.waitForIdle()
        assertTrue(vm.vodOf("movie").isEmpty())
        rule.onNodeWithTag("le-cats-show").performClick()
        rule.waitForIdle()
        assertEquals(1140, vm.vodOf("movie").size)
    }

    @Test
    fun listEditorHidesLiveCategoriesPerPlaylist() {
        rule.runOnIdle { vm.navSelect(Screen.SETTINGS); vm.settingsSection = "channels" }
        rule.waitForIdle()
        rule.onNodeWithTag("le-tab-LIVE").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("NEWS", substring = false).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("le-cats-hide").performClick()
        rule.waitForIdle()
        assertTrue(vm.groups().isEmpty())
        rule.onNodeWithTag("le-cats-show").performClick()
        rule.waitForIdle()
        assertEquals(6, vm.groups().size)
    }

    @Test
    fun hiddenTitleLeavesMoviesAndSearch() {
        rule.waitUntil(10_000) { vm.vod.isNotEmpty() || run { vm.ensureVod(); false } }
        val t = vm.vodAll("movie").first { it.category == "Drama" }
        rule.runOnIdle { vm.setVodTitlesHidden(listOf(t.key), true) }
        assertTrue(vm.vodOf("movie").none { it.key == t.key })
    }

    // ---------------------------------------------------------------- search (previously crashed)

    @Test
    fun globalSearchFindsChannelsProgramsAndMoviesWithoutCrashing() {
        go(Screen.SEARCH)
        rule.runOnIdle { vm.search("Drama") }
        rule.waitUntil(15_000) { vm.searchResults != null && !vm.searching }
        rule.waitForIdle()
        val total = vm.searchResults!!.sumOf { it.total }
        assertTrue("search found nothing", total > 0)
    }

    @Test
    fun guideSearchCoversAllPlaylists() {
        go(Screen.GUIDE)
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "NEWS"); vm.guideQuery = "Free News" }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Free News 1", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    // ---------------------------------------------------------------- favorites and lists

    @Test
    fun favoritesAndCustomListsShowInTheGuide() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, FAV_GROUP) }
        rule.waitForIdle()
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
            else rule.onRoot().performKeyInput { pressKey(keys[k]) }
            if (i % 10 == 0) rule.waitForIdle()
            if (vm.screen == Screen.PLAYER && rnd.nextInt(4) == 0) rule.runOnIdle { vm.closeFullScreen() }
        }
        rule.waitForIdle()
    }
}
