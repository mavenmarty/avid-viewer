package com.avidviewer.tv

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollTo
import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.avidviewer.tv.ui.PlayContext
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.printToString
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.ui.AppRoot
import com.avidviewer.tv.ui.AppViewModel
import com.avidviewer.tv.ui.FAV_GROUP
import com.avidviewer.tv.ui.LIVE_KIND
import com.avidviewer.tv.ui.LIVE_MATCH
import com.avidviewer.tv.ui.LIVE_PREFIX
import com.avidviewer.tv.ui.RECENT_MATCH
import com.avidviewer.tv.ui.OPEN_ON_CONTINUE_IN_TESTS
import com.avidviewer.tv.ui.SORT_AZ
import com.avidviewer.tv.ui.SORT_MANUAL
import com.avidviewer.tv.ui.SORT_PROVIDER
import com.avidviewer.tv.data.VodItem
import com.avidviewer.tv.ui.NavMode
import com.avidviewer.tv.ui.Screen
import com.avidviewer.tv.ui.holdSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var vm: AppViewModel

    @Before
    fun start() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        TestData.seed(app)
        vm = AppViewModel(app)
        rule.setContent { AppRoot(vm) }
        rule.runOnUiThread { rule.activity.window.decorView.requestFocus() }
        waitFor("channels to load") { vm.channels.isNotEmpty() }
    }

    /** Lets the app's short pauses (e.g. putting the highlight back after a move) run, then settles. */
    private fun settle(ms: Long = 250) {
        rule.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
        // Some of the app's short pauses run on a real-time timer, so give them real time too.
        Thread.sleep(90)
        rule.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
        rule.waitForIdle()
    }

    /** The text of whatever has the remote's highlight (for failure messages). */
    private fun focused(): String = runCatching {
        rule.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().joinToString(" / ") { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString(" ") { it.text } ?: "(no text)"
        }.ifBlank { "(nothing focused)" }
    }.getOrDefault("(unknown)")

    /** Whether what has the highlight is the row you are in (the "active path": selected for TalkBack too). */
    private fun focusedSelected(): Boolean = runCatching {
        rule.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().any { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Selected) == true
        }
    }.getOrDefault(false)

    /**
     * Presses a remote button the way the Fire TV does: a key down and key up delivered to the app's
     * window (or to an open dialog's window), so the app's own key handling and Back all run for real.
     */
    private fun key(k: Key) {
        val code = k.nativeKeyCode
        rule.runOnUiThread {
            val t = android.os.SystemClock.uptimeMillis()
            val down = AndroidKeyEvent(t, t, AndroidKeyEvent.ACTION_DOWN, code, 0)
            val up = AndroidKeyEvent(t, t + 40, AndroidKeyEvent.ACTION_UP, code, 0)
            val target = topWindowView()
            if (target != null) { target.dispatchKeyEvent(down); target.dispatchKeyEvent(up) }
            else { rule.activity.dispatchKeyEvent(down); rule.activity.dispatchKeyEvent(up) }
        }
    }

    /** The decor view of a dialog, if one is showing on top of the app. */
    private fun topWindowView(): android.view.View? {
        val views: List<android.view.View> = runCatching {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            val wmg = cls.getMethod("getInstance").invoke(null)
            (cls.getDeclaredField("mViews").apply { isAccessible = true }.get(wmg) as List<*>).filterIsInstance<android.view.View>()
        }.getOrDefault(emptyList())
        return views.lastOrNull()?.takeIf { it !== rule.activity.window.decorView }
    }

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
            "Timed out waiting for: $what\nfocus now: ${focused()} · screen=${vm.screen} nav=${vm.navMode} group=${vm.selectedGroup}\n" +
                "guide=${vm.focusTrail.toList().takeLast(12)} status=${vm.status} vodError=${vm.vodError} vodLoading=${vm.vodLoading} vod=${vm.vod.size} " +
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
    fun pickingAScreenHighlightsItsFirstItem() {
        go(Screen.SETTINGS)
        waitFor("Settings › Playlists to take the highlight (focus: ${focused()})") { focused().contains("Playlists") }
        go(Screen.MOVIES)
        waitFor("movies to load") { vm.vodOf("movie").isNotEmpty() }
        waitFor("Movies › All to take the highlight (focus: ${focused()})") { focused().startsWith("▶ All") || focused().startsWith("All") }
    }

    @Test
    fun dpadMovesThroughTheGuideAndBackLeavesCategories() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "NEWS") }
        settle()
        rule.onAllNodes(androidx.compose.ui.test.hasText("NEWS Channel 1 with", substring = true) and androidx.compose.ui.test.hasClickAction()).onFirst().requestFocus()
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionRight, Key.DirectionRight, Key.DirectionLeft, Key.DirectionUp))
        assertEquals(NavMode.GROUPS, vm.navMode)
        press(listOf(Key.Back)) // first to the category list
        waitFor("Back from a channel highlights the current category (focus: ${focused()})") { vm.inGroupNav && focused().contains("NEWS") }
        press(listOf(Key.Back))
        assertNotEquals("Back should leave the category list", NavMode.GROUPS, vm.navMode)
    }

    private fun focusKidsProgramme() {
        rule.onAllNodes(androidx.compose.ui.test.hasText("Program KIDS Chann", substring = true) and androidx.compose.ui.test.hasClickAction()).onFirst().requestFocus()
        settle()
    }

    @Test
    fun guideColumnsSitOnLocalHalfHours() {
        // Kathmandu is UTC+5:45: the columns must be at local :00 and :30, not UTC's (:15 / :45 there).
        val ktm = java.util.TimeZone.getTimeZone("Asia/Kathmandu")
        val t = java.util.GregorianCalendar(ktm).apply { set(2026, 10, 12, 20, 14, 0) }.timeInMillis
        val base = com.avidviewer.tv.ui.localHalfHour(t, ktm)
        val local = java.util.GregorianCalendar(ktm).apply { timeInMillis = base }
        assertEquals(20, local.get(java.util.Calendar.HOUR_OF_DAY)); assertEquals(0, local.get(java.util.Calendar.MINUTE))
        // Across a daylight-saving change (US Central falls back on 1 Nov 2026: 1:00–2:00 happens twice).
        val chi = java.util.TimeZone.getTimeZone("America/Chicago")
        val d = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 10, 1, 7, 40, 0) }.timeInMillis // 1:40 CST, the second 1:40 that night
        val b2 = java.util.GregorianCalendar(chi).apply { timeInMillis = com.avidviewer.tv.ui.localHalfHour(d, chi) }
        assertEquals(1, b2.get(java.util.Calendar.HOUR_OF_DAY)); assertEquals(30, b2.get(java.util.Calendar.MINUTE))
        assertEquals(10 * 60_000L, d - com.avidviewer.tv.ui.localHalfHour(d, chi))
    }

    @Test
    fun guideScrollsDaysAheadWithTheDayStripAndTwoHoursBackDimmed() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }
        settle()
        waitFor("the guide to know it runs past tomorrow") { vm.guideLastEnd > System.currentTimeMillis() + 48 * 3600_000L }
        // Where you are: the category is named over the times (NAV-14).
        waitFor("the category named in the time row") { rule.onAllNodesWithText("KIDS · Today", substring = true).fetchSemanticsNodes().isNotEmpty() }
        waitFor("the day strip") { rule.onAllNodesWithText("Tomorrow").fetchSemanticsNodes().isNotEmpty() }
        val base = com.avidviewer.tv.ui.localHalfHour(vm.now, vm.timeZone)
        rule.onAllNodesWithText("Tomorrow").onFirst().performClick()
        settle()
        waitFor("the window on tomorrow (start ${vm.nav.guideWindowStart})") { vm.nav.guideWindowStart >= base + 20 * 3600_000L }
        waitFor("tomorrow's programmes read for the category") {
            rule.onAllNodesWithText("Program KIDS Chann", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithText("No guide information").fetchSemanticsNodes().isEmpty()
        }
        waitFor("the time row says Tomorrow") { rule.onAllNodesWithText("KIDS · Tomorrow", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // Two days ahead is past what is held in memory: those programmes are read for the category on screen.
        val d2 = vm.dayLabel(vm.now + 48 * 3600_000L)
        rule.onAllNodesWithText(d2).onFirst().performClick()
        settle()
        waitFor("the window two days ahead") { vm.nav.guideWindowStart >= base + 44 * 3600_000L }
        waitFor("programmes two days ahead") {
            rule.onAllNodesWithText("Program KIDS Chann", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithText("No guide information").fetchSemanticsNodes().isEmpty()
        }
        // Holding Left on a later window jumps back to now (NAV-15).
        focusKidsProgramme()
        hold(Key.DirectionLeft, 6)
        waitFor("held Left back at now (start ${vm.nav.guideWindowStart}, base $base)") { vm.nav.guideWindowStart == base }
        // Nov 15: the guide never scrolls into the past. Left from the first programme goes to the channel name
        // (the window stays on now), and Left again leaves the guide for the categories.
        focusKidsProgramme()
        press(listOf(Key.DirectionLeft))
        assertEquals("not scrolled back (focus: ${focused()})", base, vm.nav.guideWindowStart)
        waitFor("Left again leaves the guide for the categories (focus: ${focused()})") {
            if (vm.guideHasFocus) press(listOf(Key.DirectionLeft))
            !vm.guideHasFocus
        }
        assertEquals("still on now", base, vm.nav.guideWindowStart)
        // Now, in the day strip, comes back.
        rule.onAllNodesWithText("Now").onFirst().performClick()
        settle()
        assertEquals(base, vm.nav.guideWindowStart)
    }

    @Test
    fun withOnePlaylistBackFromTheCategoriesGoesStraightToTheMainMenu() {
        rule.runOnIdle { vm.setPlaylistEnabled(TestData.P2, false) }
        waitFor("one live playlist") { vm.livePlaylists.size == 1 }
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "NEWS") }
        settle()
        rule.onAllNodes(androidx.compose.ui.test.hasText("NEWS Channel 1 with", substring = true) and androidx.compose.ui.test.hasClickAction()).onFirst().requestFocus()
        settle()
        press(listOf(Key.Back))
        waitFor("Back from a channel highlights the category") { vm.inGroupNav && focused().contains("NEWS") }
        press(listOf(Key.Back))
        assertEquals("Back from the categories opens the main menu (no playlist-list step)", NavMode.FULL, vm.navMode)
    }

    /** FOCUS-02: closing a dialog (Done, Cancel, Save or Back) puts the highlight back on what opened it. */
    @Test
    fun theHighlightComesBackToTheChannelAfterItsMenuAndRename() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }
        settle()
        fun onCh3() = focused().contains("KIDS Channel 3 with")
        rule.onAllNodes(androidx.compose.ui.test.hasText("KIDS Channel 3 with", substring = true) and androidx.compose.ui.test.hasClickAction()).onFirst().requestFocus()
        settle()
        assertTrue(focused(), onCh3())
        for (close in listOf("Done", "Back")) {
            press(listOf(Key.Menu))
            waitFor("channel options") { vm.channelMenu != null }
            if (close == "Done") rule.onAllNodesWithText("Done").onFirst().performClick() else press(listOf(Key.Back))
            waitFor("the highlight back on channel 3 after $close (focus: ${focused()})") { vm.channelMenu == null && onCh3() }
        }
        for (close in listOf("Cancel", "Save")) {
            press(listOf(Key.Menu))
            waitFor("channel options") { vm.channelMenu != null }
            rule.onAllNodesWithText("Rename", substring = true).onFirst().performClick()
            waitFor("the rename box") { rule.onAllNodesWithText(close).fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodesWithText(close).onFirst().performClick()
            waitFor("the highlight back on channel 3 after Rename › $close (focus: ${focused()})") { vm.channelMenu == null && onCh3() }
        }
    }

    /** DATA-06: a highlighted movie category has its first page read ahead; OK then shows it straight away. */
    @Test
    fun aHighlightedMovieCategoryIsReadAhead() {
        go(Screen.MOVIES)
        waitFor("movies") { vm.vodOf("movie").isNotEmpty() }
        val drama = vm.vodCategories("movie").first { it.first == "Drama" }.first
        rule.onAllNodes(androidx.compose.ui.test.hasText(vm.vodCatName("movie", drama), substring = true) and androidx.compose.ui.test.hasClickAction()).onFirst().requestFocus()
        settle()
        waitFor("Drama read ahead") { vm.vodPrefetchedFor("movie", drama) }
        press(listOf(Key.DirectionCenter))
        waitFor("Drama shown") { vm.vodCategory["movie"] == drama && vm.vodItems("movie").size == 20 }
        assertTrue("the read-ahead list was used", !vm.vodPrefetchedFor("movie", drama))
    }

    /**
     * Profiles: each keeps its own favorites; a Kids profile sees only its categories, opens without a PIN and
     * has no Settings; every other profile then needs a PIN (its own or a parent's).
     */
    @Test
    fun profilesKeepTheirOwnFavoritesAndKidsSeesOnlyItsCategories() {
        waitFor("channels") { vm.channelsIn("NEWS").isNotEmpty() && vm.channelsIn("KIDS").isNotEmpty() }
        val ownerGroups = vm.groups().map { it.raw }
        val news = vm.channelsIn("NEWS").first().key
        val kidsCh = vm.channelsIn("KIDS").first().key
        rule.runOnIdle { if (news !in vm.prefs.favorites) vm.toggleFavorite(news) }
        waitFor("owner's favorite") { news in vm.prefs.favorites }
        var sam = ""
        var kids = ""
        rule.runOnIdle { sam = vm.addProfile("Sam", kids = false, color = 1) }
        // The owner chooses a PIN; Sam doesn't. Nov 22, 2026: adding a Kids profile no longer requires PINs.
        rule.runOnIdle { vm.setProfilePin(com.avidviewer.tv.data.Profile.OWNER, "1234") }
        rule.runOnIdle { vm.addKidsProfile("Kids", 2) { kids = it } }
        waitFor("Kids profile added") { kids.isNotEmpty() && vm.profiles.get(kids)?.kids == true }
        assertEquals("no PIN asked for", null, vm.pinAsk)
        rule.runOnIdle {
            val k = vm.profiles.get(kids)!!
            vm.updateProfile(k.copy(allowedGroups = setOf(vm.liveCategoryId(TestData.P1, "KIDS"))))
        }
        // Sam starts with a copy of the owner's favorites.
        rule.runOnIdle { vm.chooseProfile(sam) }
        waitFor("watching as Sam (no PIN of its own, so none asked)") { vm.profile.id == sam && vm.pinAsk == null }
        assertTrue(news in vm.prefs.favorites)
        rule.runOnIdle { vm.toggleFavorite(news) } // Sam's own change
        waitFor("Sam's favorite removed") { news !in vm.prefs.favorites }

        // Kids: no PIN, only KIDS, no Settings / Discover / Sports.
        rule.runOnIdle { vm.chooseProfile(kids) }
        waitFor("watching as Kids") { vm.profile.id == kids && vm.pinAsk == null }
        settle()
        assertTrue(vm.kidsMode)
        assertEquals(listOf("KIDS"), vm.groups().map { it.raw })
        assertEquals(null, vm.channel(news))
        assertTrue(vm.channel(kidsCh) != null)
        assertTrue(vm.prefs.favorites.isEmpty())
        assertTrue("no Settings for Kids", !vm.screenAllowed(Screen.SETTINGS) && !vm.screenAllowed(Screen.DISCOVER) && !vm.screenAllowed(Screen.SPORTS))
        rule.runOnIdle { vm.navSelect(Screen.SETTINGS) }
        assertTrue(vm.screen != Screen.SETTINGS)
        rule.runOnIdle { vm.toggleFavorite(news) } // a channel it can't see can't be watched either
        rule.runOnIdle { vm.selectChannel(news, PlayContext.GROUP) }
        assertTrue("nothing outside KIDS plays", vm.currentKey != news)

        // Back to the owner: PIN needed; the owner's favorites are as they were.
        rule.runOnIdle { vm.chooseProfile(com.avidviewer.tv.data.Profile.OWNER) }
        waitFor("owner asks for the PIN") { vm.pinAsk != null }
        // Typed on the remote's number buttons in the PIN dialog.
        waitFor("PIN dialog") { rule.onAllNodesWithTag("pin-dialog").fetchSemanticsNodes().isNotEmpty() }
        press(listOf(Key.One, Key.Two, Key.Three, Key.Four))
        waitFor("watching as the owner (pin=${vm.pinAsk?.title})") { vm.profile.isOwner }
        assertTrue("owner's favorites kept", news in vm.prefs.favorites)
        assertTrue("every category is back (${vm.groups().map { it.raw }})", vm.groups().map { it.raw }.containsAll(ownerGroups))
    }

    @Test
    fun menuReorderMovesTheChannelAndBackOnlyEndsReordering() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }
        settle()
        val first = vm.channelsIn("KIDS").first().key
        // As a viewer would: highlight the channel name, press Menu, choose Re-order.
        rule.onAllNodes(androidx.compose.ui.test.hasText("KIDS Channel 1 with", substring = true) and androidx.compose.ui.test.hasClickAction()).onFirst().requestFocus()
        settle()
        press(listOf(Key.Menu))
        waitFor("channel options to open") { rule.onAllNodesWithText("Re-order", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Re-order", substring = true).onFirst().performClick()
        waitFor("re-order mode with the channel highlighted (focus: ${focused()})") { vm.movingChannel == first && focused().contains("⇅") }
        press(listOf(Key.DirectionDown))
        assertEquals("after 1st Down, focus on: " + focused(), 1, vm.channelsIn("KIDS").indexOfFirst { it.key == first })
        press(listOf(Key.DirectionDown))
        assertEquals("after 2nd Down, focus on: " + focused(), 2, vm.channelsIn("KIDS").indexOfFirst { it.key == first })
        waitFor("highlight back on the moving channel") { focused().contains("⇅") }
        press(listOf(Key.DirectionRight)) // to the bottom
        waitFor("highlight on the channel after jumping to the bottom (focus: ${focused()})") { focused().contains("⇅") }
        assertEquals(vm.channelsIn("KIDS").size - 1, vm.channelsIn("KIDS").indexOfFirst { it.key == first })
        press(listOf(Key.DirectionLeft)) // to the top
        waitFor("highlight on the channel after jumping to the top") { focused().contains("⇅") }
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
        showCategories()
        // Highlight the category in the left list, press Menu, choose Re-order.
        rule.onNode(androidx.compose.ui.test.hasText("KIDS") and androidx.compose.ui.test.hasClickAction()).requestFocus()
        settle()
        press(listOf(Key.Menu))
        waitFor("category options to open") { rule.onAllNodesWithText("Re-order", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Re-order", substring = true).onFirst().performClick()
        waitFor("re-order mode with the category highlighted (focus: ${focused()})") { vm.movingGroup == "KIDS" && focused().contains("⇅") }
        press(listOf(Key.DirectionLeft))
        assertEquals("focus on: " + focused(), "KIDS", vm.groups().first().raw)
        press(listOf(Key.DirectionRight))
        assertEquals("KIDS", vm.groups().last().raw)
        press(listOf(Key.DirectionCenter))
        assertNull(vm.movingGroup)
    }

    @Test
    fun movieCategoryReorderAndRename() {
        go(Screen.MOVIES)
        waitFor("movies to load") { vm.vodOf("movie").isNotEmpty() }
        waitFor("the first category to take the highlight (focus: ${focused()}, entryFocusFor=${vm.entryFocusFor}, All rows=${rule.onAllNodesWithText("All").fetchSemanticsNodes().size}, screen=${vm.screen}, detail=${vm.vodDetail?.name})") { vm.entryFocusFor == null }
        rule.onNode(androidx.compose.ui.test.hasText("Action") and androidx.compose.ui.test.hasClickAction()).requestFocus()
        settle()
        press(listOf(Key.Menu))
        waitFor("movie category options to open") { rule.onAllNodesWithText("Re-order", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Re-order", substring = true).onFirst().performClick()
        waitFor("re-order mode on Action (focus: ${focused()})") { vm.vodMovingCat == ("movie" to "Action") && focused().contains("⇅") }
        press(listOf(Key.DirectionLeft))
        assertEquals("focus on: " + focused(), "Action", vm.vodCategories("movie").first().first)
        press(listOf(Key.Back))
        assertNull(vm.vodMovingCat)
        rule.runOnIdle { vm.renameVodCat("movie", "Action", "Action & Adventure") }
        settle()
        assertTrue(rule.onAllNodesWithText("Action & Adventure", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    // ---------------------------------------------------------------- List Editor (bulk show / hide)

    @Test
    fun listEditorHidesAndShowsMovieCategoriesInBulk() {
        go(Screen.SETTINGS)
        waitFor("Settings' first section to take the highlight") { vm.entryFocusFor == null }
        rule.runOnIdle { vm.settingsSection = "channels" }
        settle()
        rule.onNodeWithTag("le-tab-MOVIES").performClick()
        waitFor("movies to load in the List Editor") { vm.vodAll("movie").isNotEmpty() }
        settle()
        rule.onNodeWithTag("le-cats-hide").performClick()
        settle()
        assertTrue(vm.vodOf("movie").isEmpty())
        val shows = rule.onAllNodes(androidx.compose.ui.test.hasTestTag("le-cats-show")).fetchSemanticsNodes()
        if (shows.isEmpty()) {
            val texts = rule.onAllNodes(androidx.compose.ui.test.hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
                .flatMap { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().map { it.text } }
            throw AssertionError("Show-all button gone after Hide all. screen=${vm.screen} section=${vm.settingsSection}. Text on screen:\n" + texts.joinToString(" | ").take(3000))
        }
        rule.onNodeWithTag("le-cats-show").performClick()
        settle()
        assertEquals(1140, vm.vodOf("movie").size)
    }

    @Test
    fun listEditorHidesLiveCategoriesPerPlaylist() {
        go(Screen.SETTINGS)
        waitFor("Settings' first section to take the highlight") { vm.entryFocusFor == null }
        rule.runOnIdle { vm.settingsSection = "channels" }
        settle()
        rule.onNodeWithTag("le-tab-LIVE").performClick()
        waitFor("live categories in the List Editor") { rule.onAllNodesWithText("NEWS", substring = false).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("le-cats-hide").performClick()
        settle()
        assertTrue(vm.groups().isEmpty())
        rule.onNodeWithTag("le-cats-show").performClick()
        settle()
        assertEquals(7, vm.groups().size)
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

    // ---------------------------------------------------------------- backup & restore

    @Test
    fun backupRestoresEverythingAndRefusesTheWrongPin() {
        val before = vm.prefs
        val text = com.avidviewer.tv.data.Backup.write(
            com.avidviewer.tv.data.Backup.Contents(vm.playlists, vm.prefs, "", vm.history, emptyMap(), System.currentTimeMillis(), "test"), "2468",
        )
        // Wrong PIN: refused, nothing changes.
        try { com.avidviewer.tv.data.Backup.read(text, "1111"); throw AssertionError("wrong PIN was accepted") }
        catch (e: com.avidviewer.tv.data.Backup.WrongPin) { }
        // Change things, then restore through the Settings flow.
        rule.runOnIdle { vm.updatePrefs { it.copy(favorites = emptyList(), lists = emptyList()) } }
        rule.runOnIdle { vm.offerRestore(text) }
        assertTrue(vm.pendingRestoreInfo.contains("My provider"))
        var ok = false
        rule.runOnIdle { vm.restore("2468") { ok = it } }
        waitFor("the restore to finish") { ok }
        assertEquals(before.favorites, vm.prefs.favorites)
        assertEquals(before.lists, vm.prefs.lists)
        assertEquals(listOf(TestData.P1, TestData.P2), vm.playlists.map { it.id })
    }

    /** 1.12.0: backups live in the account, so an unlinked TV is offered Link this TV instead. */
    @Test
    fun backupNeedsALinkedTv() {
        go(Screen.SETTINGS)
        rule.runOnIdle { vm.settingsSection = "about" }
        settle()
        assertTrue(rule.onAllNodesWithText("Backup & restore").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag("backup-now").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("backup-link").performClick()
        settle()
        assertEquals("account", vm.settingsSection)
    }

    private fun linkForTest(site: FakeAvidSite, token: String = "TVTOKEN_abcdefghijklmnopqrstuvwxyz") {
        rule.runOnIdle {
            vm.accountApi = site.base
            vm.account.save(com.avidviewer.tv.data.AccountApi.Linked(token, "viewer@example.com", "Living room", "d1"))
            vm.checkAccount()
        }
        waitFor("linked") { vm.accountEmail == "viewer@example.com" }
    }

    /** 1.12.0: Back up now asks for a PIN once, sends it with the backup; the daily one skips an unchanged TV; restore from the account. */
    @Test
    fun backUpToTheAccountAndRestore() {
        FakeAvidSite().use { site ->
            linkForTest(site)
            go(Screen.SETTINGS)
            rule.runOnIdle { vm.settingsSection = "about" }
            settle()
            waitFor("Back up now") { vm.settingsSection == "about" && rule.onAllNodesWithTag("backup-now").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("backup-now").performScrollTo().performClick()
            settle()
            waitFor("PIN asked") { rule.onAllNodesWithText("Choose a backup PIN").fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodesWithText("Cancel").onLast().performClick()
            waitFor("PIN dialog closed") { rule.onAllNodesWithText("Choose a backup PIN").fetchSemanticsNodes().isEmpty() }
            rule.runOnIdle { vm.backUp(newPin = "2468") }
            waitFor("sent (${vm.backupStatus})") { site.backups["d1"] != null && !vm.backupBusy }
            assertEquals("2468", site.backups["d1"]!!.third)
            assertTrue("locked: no playlist address in the clear", !site.backups["d1"]!!.second.contains("p1.m3u"))
            assertTrue(vm.lastBackupAt > 0)
            // The daily backup doesn't send an unchanged TV again, but does once something changes.
            val sends = { site.calls.count { it.startsWith("POST /api/tv/backup ") } }
            rule.runOnIdle { vm.backUp(auto = true) }
            settle(800); assertEquals(1, sends())
            val before = vm.prefs
            rule.runOnIdle { vm.updatePrefs { it.copy(favorites = emptyList(), lists = emptyList()) } }
            rule.runOnIdle { vm.backUp(auto = true) }
            waitFor("changed TV sent again") { sends() == 2 }
            // Restore the first one from a copy (another TV's backup in the account).
            site.backups["d9"] = Triple("Bedroom", com.avidviewer.tv.data.Backup.write(
                com.avidviewer.tv.data.Backup.Contents(vm.playlists, before, "", vm.history, emptyMap(), System.currentTimeMillis(), "test"), "1357"), "1357")
            rule.onNodeWithTag("backup-restore").performClick()
            waitFor("backups listed") { vm.cloudBackups?.size == 2 }
            waitFor("Bedroom shown") { rule.onAllNodesWithText("Bedroom", substring = true).fetchSemanticsNodes().isNotEmpty() }
            // The list sits below the fold of Settings; choose it as its button does.
            rule.runOnIdle { vm.chooseCloudBackup(vm.cloudBackups!!.first { it.name == "Bedroom" }) }
            waitFor("PIN asked to restore") { vm.pendingRestore != null }
            assertTrue(vm.pendingRestoreInfo.startsWith("Bedroom"))
            var ok: Boolean? = null
            rule.runOnIdle { vm.restore("0000") { ok = it } }
            waitFor("wrong PIN refused") { ok == false }
            rule.runOnIdle { vm.restore("1357") { ok = it } }
            waitFor("restored") { ok == true }
            assertEquals(before.favorites, vm.prefs.favorites)
            // Signing out forgets the backup PIN too.
            rule.runOnIdle { vm.signOutOfAccount() }
            assertFalse(vm.backupPinSet)
        }
    }

    /** Linking from the welcome screen says what's next (add playlists on the website) and offers no backups. */
    @Test
    fun linkingFromTheWelcomeScreenPointsToTheWebsiteAndOffersNoBackups() {
        FakeAvidSite().use { site ->
            site.backups["d9"] = Triple("Bedroom", com.avidviewer.tv.data.Backup.write(
                com.avidviewer.tv.data.Backup.Contents(vm.playlists, vm.prefs, "", vm.history, emptyMap(), System.currentTimeMillis(), "test"), "1357"), "1357")
            rule.runOnIdle { vm.accountApi = site.base; vm.showWelcome = true }
            waitFor("code shown") { rule.onAllNodesWithText("K7M2-QX9P").fetchSemanticsNodes().isNotEmpty() }
            site.approved = true
            waitFor("linked") { vm.accountEmail != null }
            waitFor("next step shown") { rule.onAllNodesWithTag("welcome-next").fetchSemanticsNodes().isNotEmpty() }
            settle()
            assertTrue("no backup offered", rule.onAllNodesWithText("Restore a backup").fetchSemanticsNodes().isEmpty())
            assertTrue("no backup list", rule.onAllNodesWithText("Bedroom", substring = true).fetchSemanticsNodes().isEmpty())
            assertNull(vm.cloudBackups)
        }
    }

    /** 1.14.0: a preview with the menus on screen goes full screen after 5 minutes without a button press. */
    @Test
    fun idleMenusOverAPlayingPreviewGoFullScreen() {
        go(Screen.GUIDE)
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "NEWS") }
        waitFor("channels") { vm.channelsIn("NEWS").isNotEmpty() }
        val key = vm.channelsIn("NEWS").first().key
        rule.runOnIdle { vm.preview(key) }
        settle()
        val t = android.os.SystemClock.uptimeMillis()
        // 4 minutes: nothing happens.
        rule.runOnIdle { vm.idleToFullScreen(t + 4 * 60_000L) }
        assertEquals(Screen.GUIDE, vm.screen)
        // 5 minutes without a button: full screen on the channel that was playing.
        rule.runOnIdle { vm.idleToFullScreen(t + 5 * 60_000L + 1) }
        assertEquals(Screen.PLAYER, vm.screen)
        assertEquals(key, vm.currentKey)
        // Back returns to the guide; a button press restarts the 5 minutes.
        rule.runOnIdle { vm.closeFullScreen() }
        waitFor("back in the guide") { vm.screen == Screen.GUIDE }
        rule.runOnIdle { vm.lastKeyAt = t + 5 * 60_000L; vm.idleToFullScreen(t + 6 * 60_000L) }
        assertEquals(Screen.GUIDE, vm.screen)
        // Nothing playing: the menus stay.
        rule.runOnIdle { vm.stop(); vm.idleToFullScreen(t + 60 * 60_000L) }
        assertEquals(Screen.GUIDE, vm.screen)
    }

    /** Seals a login for the device's reported key the way avidviewer.app/playlists does (DeviceKeyTest checks the real page's output). */
    private fun sealFor(pubB64: String, login: org.json.JSONObject): org.json.JSONObject {
        val b64 = { b: ByteArray -> android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP) }
        val raw = android.util.Base64.decode(pubB64, android.util.Base64.NO_WRAP)
        val g = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }
        val eph = g.generateKeyPair()
        val params = (eph.public as java.security.interfaces.ECPublicKey).params
        val dev = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.ECPublicKeySpec(
            java.security.spec.ECPoint(java.math.BigInteger(1, raw.copyOfRange(1, 33)), java.math.BigInteger(1, raw.copyOfRange(33, 65))), params))
        val z = javax.crypto.KeyAgreement.getInstance("ECDH").run { init(eph.private); doPhase(dev, true); generateSecret() }
        val w = (eph.public as java.security.interfaces.ECPublicKey).w
        fun fixed(n: java.math.BigInteger) = n.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        val epk = byteArrayOf(4) + fixed(w.affineX) + fixed(w.affineY)
        val mac = { k: ByteArray, d: ByteArray -> javax.crypto.Mac.getInstance("HmacSHA256").run { init(javax.crypto.spec.SecretKeySpec(k, "HmacSHA256")); doFinal(d) } }
        val prk = mac(epk, z)
        val aes = mac(prk, "avidviewer-playlist:v1".toByteArray() + byteArrayOf(1)).copyOf(32)
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val ct = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(aes, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
            doFinal(login.toString().toByteArray())
        }
        return org.json.JSONObject().put("v", 1).put("epk", b64(epk)).put("iv", b64(iv)).put("ct", b64(ct))
    }

    /**
     * 1.15.2: a playlist from the account that this device already has (typed with the remote) doesn't arrive as a second
     * copy: the device's own becomes the account's. Two copies already there become one, keeping the device's own.
     */
    @Test
    fun theSamePlaylistFromTheAccountIsNotAddedTwice() {
        FakeAvidSite().use { site ->
            linkForTest(site)
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("key reported") { site.report != null }
            val pub = site.report!!.getString("pubkey")
            val before = vm.playlists.size
            val mine = vm.playlists.first { it.id == TestData.P1 }
            val login = org.json.JSONObject().put("name", "My provider").put("type", "m3u").put("m3uUrl", mine.m3uUrl)
            synchronized(site.inbox) {
                site.inbox += org.json.JSONObject().put("playlistId", "acctP1").put("action", "add").put("version", 1).put("name", "My provider")
                    .put("type", "m3u").put("profiles", org.json.JSONObject.NULL).put("payload", sealFor(pub, login))
            }
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("acknowledged (acks: ${site.acks})") { site.acks.any { it.getString("playlistId") == "acctP1" } }
            assertTrue(site.acks.first { it.getString("playlistId") == "acctP1" }.getBoolean("ok"))
            assertEquals("no second copy", before, vm.playlists.size)
            assertEquals("acctP1", vm.playlists.first { it.id == TestData.P1 }.remoteId)
        }
    }

    /** 1.15.2: removing a big playlist doesn't hold up the screen (its rows go in the background). */
    @Test
    fun removingAPlaylistReturnsAtOnce() {
        rule.runOnIdle {
            val t = System.nanoTime()
            vm.removePlaylist(TestData.P2)
            assertTrue("removed in the list", vm.playlists.none { it.id == TestData.P2 })
            assertTrue("returned quickly", System.nanoTime() - t < 500_000_000L)
        }
    }

    /**
     * 1.14.0: a playlist sent from avidviewer.app/playlists arrives on a linked device, is limited to the profiles chosen
     * there (another profile doesn't see it), follows a change of profiles without a new login, and goes when removed.
     */
    @Test
    fun aPlaylistFromTheAccountArrivesForTheChosenProfiles() {
        FakeAvidSite().use { site ->
            linkForTest(site)
            val before = vm.playlists.size
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("key and profiles reported") { site.report != null }
            val pub = site.report!!.getString("pubkey")
            assertEquals("Me", site.report!!.getJSONArray("profiles").getJSONObject(0).getString("name"))
            val login = org.json.JSONObject().put("name", "From the web").put("type", "m3u").put("m3uUrl", "http://127.0.0.1:9/web.m3u")
                .put("epgUrl", "http://127.0.0.1:9/web.xml")
            synchronized(site.inbox) {
                site.inbox += org.json.JSONObject().put("playlistId", "pl1").put("action", "add").put("version", 1).put("name", "From the web")
                    .put("type", "m3u").put("profiles", org.json.JSONArray().put("someone-else")).put("payload", sealFor(pub, login))
            }
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("added and acknowledged (acks: ${site.acks})") { vm.playlists.any { it.remoteId == "pl1" } && site.acks.isNotEmpty() }
            assertTrue(site.acks.first().getBoolean("ok"))
            val p = vm.playlists.first { it.remoteId == "pl1" }
            assertEquals("http://127.0.0.1:9/web.m3u", p.m3uUrl)
            assertEquals("http://127.0.0.1:9/web.xml", p.epgSources.single().url)
            assertEquals(before + 1, vm.playlists.size)
            // Meant for another profile: the owner's profile doesn't see it anywhere.
            assertFalse(vm.shownPlaylists.any { it.remoteId == "pl1" })
            // The website changes the profiles (no new login): now the owner sees it.
            synchronized(site.inbox) {
                site.inbox += org.json.JSONObject().put("playlistId", "pl1").put("action", "update").put("version", 2).put("name", "Renamed on the web")
                    .put("type", "m3u").put("profiles", org.json.JSONObject.NULL).put("payload", org.json.JSONObject.NULL)
            }
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("updated") { vm.playlists.any { it.remoteId == "pl1" && it.name == "Renamed on the web" && it.profiles == null } }
            assertTrue(vm.shownPlaylists.any { it.remoteId == "pl1" })
            assertEquals("the login was kept", "http://127.0.0.1:9/web.m3u", vm.playlists.first { it.remoteId == "pl1" }.m3uUrl)
            // Removed on the website: gone from the device.
            synchronized(site.inbox) {
                site.inbox += org.json.JSONObject().put("playlistId", "pl1").put("action", "remove").put("version", 3).put("name", "Renamed on the web").put("type", "m3u")
            }
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("removed") { vm.playlists.none { it.remoteId == "pl1" } && site.acks.size == 3 }
            // A login sealed for another device can't be opened: the device says so instead of adding it.
            val (otherPub, _) = com.avidviewer.tv.data.DeviceKey.generate()
            synchronized(site.inbox) {
                site.inbox += org.json.JSONObject().put("playlistId", "pl2").put("action", "add").put("version", 1).put("name", "Wrong key")
                    .put("type", "m3u").put("payload", sealFor(otherPub, login))
            }
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("refused") { site.acks.size == 4 }
            assertFalse(site.acks.last().getBoolean("ok"))
            assertTrue(vm.playlists.none { it.remoteId == "pl2" })
        }
    }

    /** A new app start on the same device, after changing what's stored ([change]); the screen keeps the old one. */
    private fun restartWith(change: (com.avidviewer.tv.data.Store) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        change(com.avidviewer.tv.data.Store(app))
        vm = AppViewModel(app)
        waitFor("channels to load again") { vm.channels.isNotEmpty() }
    }

    private fun pinHash(pin: String): String {
        val salt = "a1b2c3d4"
        return salt + ":" + java.security.MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * 1.15.0: the first report merges the device's profiles into the account; the account's profiles then replace the
     * device's own (the first profile keeps its data as Dad), with their PINs, Kids' categories and what each one hides,
     * and follow later changes. The playlist's categories and channel names go to the account for the website.
     */
    @Test
    fun accountProfilesReplaceTheDevicesOwnAndFollowTheWebsite() {
        FakeAvidSite().use { site ->
            // My provider was sent from the account (1.14.0).
            restartWith { st -> st.savePlaylists(st.loadPlaylists().map { if (it.id == TestData.P1) it.copy(remoteId = "acctP1") else it }) }
            linkForTest(site)
            site.profileMap = mapOf("main" to "acctDad")
            fun vis(hidden: List<String> = emptyList(), allowed: List<String> = emptyList(), channels: List<String> = emptyList()) =
                org.json.JSONObject().put("acctP1", org.json.JSONObject()
                    .put("hidden", org.json.JSONObject().put("live", org.json.JSONArray(hidden)).put("movie", org.json.JSONArray()).put("series", org.json.JSONArray()))
                    .put("allowed", org.json.JSONObject().put("live", org.json.JSONArray(allowed)).put("movie", org.json.JSONArray()).put("series", org.json.JSONArray()))
                    .put("channels", org.json.JSONArray(channels)))
            val newsKey = vm.channelsIn("NEWS").firstOrNull()?.key ?: vm.channels.first { it.group == "NEWS" }.key
            site.profiles = org.json.JSONArray()
                .put(org.json.JSONObject().put("id", "acctDad").put("name", "Dad").put("parent", true).put("color", 0).put("pin", pinHash("1234"))
                    .put("playlists", org.json.JSONArray().put("acctP1")).put("visibility", vis(hidden = listOf("SPORTS | NFL"), channels = listOf(newsKey.substringAfter(':')))))
                .put(org.json.JSONObject().put("id", "acctKids").put("name", "Kids").put("kids", true).put("color", 2).put("pin", "")
                    .put("playlists", org.json.JSONArray().put("acctP1")).put("visibility", vis(allowed = listOf("KIDS"))))
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("account profiles applied (${vm.profiles.list.map { it.name }})") { vm.profiles.list.size == 2 && vm.profiles.managed }
            // The report: 1.15.0 form, the device's own profile, its PIN only as a hash.
            assertEquals(2, site.report!!.getInt("v"))
            assertEquals("Me", site.report!!.getJSONArray("profiles").getJSONObject(0).getString("name"))
            val dad = vm.profiles.list[0]; val kids = vm.profiles.list[1]
            assertEquals("the first profile became Dad, keeping its data", com.avidviewer.tv.data.Profile.OWNER, dad.id)
            assertEquals("Dad", dad.name); assertEquals("acctDad", dad.remoteId)
            assertTrue(dad.pinMatches("1234")); assertTrue(dad.isParent)
            assertEquals("acctKids", kids.id); assertTrue(kids.kids)
            assertEquals(setOf(vm.liveCategoryId(TestData.P1, "KIDS")), kids.allowedGroups)
            // What the account hides for Dad (the profile in use) is hidden here, on top of what Dad hid himself.
            assertTrue(vm.isGroupHidden(TestData.P1, "SPORTS | NFL"))
            assertTrue(newsKey in vm.prefs.hiddenChannels)
            // A Kids profile is on the device: the next start shows Who's watching?, Kids behind it.
            assertEquals(com.avidviewer.tv.data.ProfileSet.Launch("acctKids", choose = true), vm.profiles.launch())
            // The website shows SPORTS again and takes Kids off this device: one profile, so no profiles at all.
            site.profiles = org.json.JSONArray()
                .put(org.json.JSONObject().put("id", "acctDad").put("name", "Dad").put("parent", true).put("color", 0).put("pin", pinHash("1234"))
                    .put("playlists", org.json.JSONArray().put("acctP1")).put("visibility", org.json.JSONObject()))
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("Kids taken off") { vm.profiles.list.size == 1 }
            assertFalse(vm.profiles.multiple)
            assertFalse(vm.isGroupHidden(TestData.P1, "SPORTS | NFL"))
            assertFalse(newsKey in vm.prefs.hiddenChannels)
            // The playlist's categories and channel names went to the account (names and counts only).
            waitFor("catalog sent") { site.catalogs.any { it.getString("playlistId") == "acctP1" } }
            val cat = site.catalogs.last { it.getString("playlistId") == "acctP1" }
            val news = (0 until cat.getJSONArray("live").length()).map { cat.getJSONArray("live").getJSONObject(it) }.first { it.getString("n") == "NEWS" }
            assertTrue(news.getInt("c") > 0)
            assertEquals(newsKey.substringAfter(':'), news.getJSONArray("ch").getJSONArray(0).getString(0))
            // BUG-057: the next account check doesn't build the catalog again when nothing changed (it froze Fire TVs).
            val builds = vm.catalogBuilds; val sentBefore = site.catalogs.size
            val checksBefore = site.syncs.size
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("another account check") { site.syncs.size > checksBefore }
            assertEquals(builds, vm.catalogBuilds)
            assertEquals(sentBefore, site.catalogs.size)
        }
    }

    /** Nov 17: changing what one profile sees on the website must not change what another profile on the TV sees. */
    @Test
    fun hidingCategoriesForOneProfileLeavesTheOthersAlone() {
        FakeAvidSite().use { site ->
            restartWith { st -> st.savePlaylists(st.loadPlaylists().map { if (it.id == TestData.P1) it.copy(remoteId = "acctP1") else it }) }
            linkForTest(site)
            site.profileMap = mapOf("main" to "acctEvan")
            fun vis(vararg hidden: String) = org.json.JSONObject().put("acctP1", org.json.JSONObject()
                .put("hidden", org.json.JSONObject().put("live", org.json.JSONArray(hidden.toList())).put("movie", org.json.JSONArray()).put("series", org.json.JSONArray()))
                .put("allowed", org.json.JSONObject().put("live", org.json.JSONArray()).put("movie", org.json.JSONArray()).put("series", org.json.JSONArray()))
                .put("channels", org.json.JSONArray()))
            fun send(evan: org.json.JSONObject, maven: org.json.JSONObject) {
                site.profiles = org.json.JSONArray()
                    .put(org.json.JSONObject().put("id", "acctEvan").put("name", "Evan").put("color", 0).put("pin", "")
                        .put("playlists", org.json.JSONArray().put("acctP1")).put("visibility", evan))
                    .put(org.json.JSONObject().put("id", "acctMaven").put("name", "Maven").put("color", 1).put("pin", "")
                        .put("playlists", org.json.JSONArray().put("acctP1")).put("visibility", maven))
                rule.runOnIdle { vm.syncAccountPlaylists() }
            }
            val st = com.avidviewer.tv.data.Store(ApplicationProvider.getApplicationContext<Application>())
            fun hiddenFor(name: String): Set<String> {
                val p = vm.profiles.list.first { it.name == name }
                return if (p.id == vm.profile.id) vm.prefs.hiddenGroups else st.loadPrefs(p.id).hiddenGroups
            }
            val nfl = vm.liveCategoryId(TestData.P1, "SPORTS | NFL"); val news = vm.liveCategoryId(TestData.P1, "NEWS")
            send(vis("SPORTS | NFL"), vis())
            waitFor("two profiles") { vm.profiles.list.size == 2 && vm.profiles.list.all { it.remoteId.isNotEmpty() } }
            waitFor("Evan's hides") { nfl in hiddenFor("Evan") }
            assertFalse(nfl in hiddenFor("Maven"))
            // Maven's change on the website, while Evan is in use.
            send(vis("SPORTS | NFL"), vis("NEWS"))
            waitFor("Maven's hides") { news in hiddenFor("Maven") }
            assertEquals("Evan in use: unchanged", setOf(nfl), hiddenFor("Evan").filter { it.startsWith(TestData.P1) }.toSet())
            // Again with Maven in use.
            rule.runOnIdle { vm.chooseProfile(vm.profiles.list.first { it.name == "Maven" }.id) }
            waitFor("Maven in use") { vm.profile.name == "Maven" }
            send(vis("SPORTS | NFL"), vis())
            waitFor("Maven shows NEWS again") { news !in hiddenFor("Maven") }
            assertEquals("Maven in use: Evan unchanged", setOf(nfl), hiddenFor("Evan").filter { it.startsWith(TestData.P1) }.toSet())
            send(vis(), vis("NEWS"))
            waitFor("Maven hides NEWS") { news in hiddenFor("Maven") }
            assertEquals("Evan's own change applied only to Evan", emptySet<String>(), hiddenFor("Evan").filter { it.startsWith(TestData.P1) }.toSet())
            assertEquals(setOf(news), hiddenFor("Maven").filter { it.startsWith(TestData.P1) }.toSet())
            // A TV that already had another profile's hides written onto Maven (1.17.0–1.17.1): the first sync after
            // the update makes them exactly the account's again; other playlists keep what was hidden on the TV.
            val mavenId = vm.profiles.list.first { it.name == "Maven" }.id
            rule.runOnIdle { vm.chooseProfile(vm.profiles.list.first { it.name == "Evan" }.id) }
            waitFor("Evan in use") { vm.profile.name == "Evan" }
            val other = vm.channels.firstOrNull { !it.key.startsWith(TestData.P1 + ":") }
            val otherHide = other?.let { vm.liveCategoryId(it.key.substringBefore(':'), it.group) }
            val bad = st.loadPrefs(mavenId).let { it.copy(hiddenGroups = it.hiddenGroups + nfl + vm.liveCategoryId(TestData.P1, "KIDS") + listOfNotNull(otherHide)) }
            st.savePersonal(mavenId, com.avidviewer.tv.data.personalOf(bad))
            com.avidviewer.tv.data.AccountStore(ApplicationProvider.getApplicationContext<Application>()).hidesRepaired = false
            send(vis(), vis("NEWS"))
            waitFor("repaired") { com.avidviewer.tv.data.AccountStore(ApplicationProvider.getApplicationContext<Application>()).hidesRepaired }
            assertEquals(setOf(news), hiddenFor("Maven").filter { it.startsWith(TestData.P1) }.toSet())
            otherHide?.let { assertTrue("hides in the TV's own playlists stay", it in hiddenFor("Maven")) }
        }
    }

    /**
     * Nov 28, 2026 (owner): someone who reported a problem is asked "Is it fixed?" once the TV has the fixed version,
     * on the 2nd or 3rd opening (not the 1st, so updating and getting back to watching stays quick); Ask me later on
     * the 2nd asks again on the 3rd; after that, not on the TV. The answer goes to the account.
     */
    @Test
    fun aReporterIsAskedWhetherTheFixWorksOnTheSecondOrThirdOpening() {
        FakeAvidSite().use { site ->
            linkForTest(site)
            val now = vm.accountInfoVersionForTests()
            site.fixChecks = org.json.JSONArray().put(org.json.JSONObject().put("id", "AVR-1").put("text", "All the hidden categories came back").put("fixedIn", now))
            rule.runOnIdle { vm.checkAccount() }
            waitFor("list kept") { vm.account.fixChecks.contains("AVR-1") }
            val t0 = System.currentTimeMillis()
            rule.runOnIdle { vm.account.opensVersion = now; vm.account.opens = 1 }
            // Back after 10 minutes: the same opening. Not asked.
            rule.runOnIdle { vm.account.awaySince = t0; vm.onAppOpened(t0 + 10 * 60_000L) }
            assertEquals(1, vm.account.opens)
            Thread.sleep(4_500); rule.waitForIdle()
            assertNull("not on the first opening", vm.fixCheck)
            // The next opening: asked; Ask me later.
            rule.runOnIdle { vm.account.awaySince = t0; vm.onAppOpened(t0 + 40 * 60_000L) }
            assertEquals(2, vm.account.opens)
            waitFor("asked") { vm.fixCheck != null }
            rule.onNodeWithTag("fix-check-dialog").assertExists()
            rule.onNodeWithText("You reported: \u201cAll the hidden categories came back\u201d").assertExists()
            rule.onNodeWithTag("fix-later").performClick()
            waitFor("later sent") { site.fixAnswers.any { it.optString("answer") == "later" } }
            // The third: asked again; Yes, it's fixed.
            rule.runOnIdle { vm.account.awaySince = t0; vm.onAppOpened(t0 + 80 * 60_000L) }
            waitFor("asked again") { vm.fixCheck != null }
            rule.onNodeWithTag("fix-yes").performClick()
            waitFor("fixed sent") { site.fixAnswers.any { it.optString("id") == "AVR-1" && it.optString("answer") == "fixed" } }
            assertTrue(vm.status.orEmpty().contains("fixed"))
            // A 4th opening: never again.
            rule.runOnIdle { vm.account.awaySince = t0; vm.onAppOpened(t0 + 120 * 60_000L) }
            Thread.sleep(4_500); rule.waitForIdle()
            assertNull(vm.fixCheck)
        }
    }

    /**
     * Nov 28, 2026 (#46: after a night the app froze and the TV was switched off, every category the profile hid was
     * shown again, while the website still had them hidden). A settings file left unreadable is read from the copy
     * saved before it; with nothing left, the TV says so and puts back what the account hides, at the next check.
     * Before, the TV kept showing everything until a hide was changed on the website.
     */
    @Test
    fun hiddenCategoriesComeBackFromTheAccountWhenTheSettingsFileIsLost() {
        FakeAvidSite().use { site ->
            restartWith { st -> st.savePlaylists(st.loadPlaylists().map { if (it.id == TestData.P1) it.copy(remoteId = "acctP1") else it }) }
            linkForTest(site)
            site.profileMap = mapOf("main" to "acctEvan")
            val vis = org.json.JSONObject().put("acctP1", org.json.JSONObject()
                .put("hidden", org.json.JSONObject().put("live", org.json.JSONArray().put("SPORTS | NFL").put("NEWS")).put("movie", org.json.JSONArray()).put("series", org.json.JSONArray()))
                .put("allowed", org.json.JSONObject().put("live", org.json.JSONArray()).put("movie", org.json.JSONArray()).put("series", org.json.JSONArray()))
                .put("channels", org.json.JSONArray()))
            site.profiles = org.json.JSONArray().put(org.json.JSONObject().put("id", "acctEvan").put("name", "Evan").put("color", 0).put("pin", "")
                .put("playlists", org.json.JSONArray().put("acctP1")).put("visibility", vis))
            rule.runOnIdle { vm.syncAccountPlaylists() }
            val nfl = vm.liveCategoryId(TestData.P1, "SPORTS | NFL"); val news = vm.liveCategoryId(TestData.P1, "NEWS")
            waitFor("hidden from the account") { nfl in vm.prefs.hiddenGroups && news in vm.prefs.hiddenGroups }
            rule.runOnIdle { vm.toggleFavorite(vm.channels.first().key) } // one more save after the hides
            val app = ApplicationProvider.getApplicationContext<Application>()
            val dir = app.filesDir
            fun quiet() { rule.waitForIdle(); vm.store.flushPrefs(); Thread.sleep(300); rule.waitForIdle(); vm.store.flushPrefs() }
            // The power went while the last save was still on its way: an empty file, the save before it kept.
            quiet()
            restartWith { java.io.File(dir, "prefs.json").writeText("") }
            assertTrue("noticed", vm.store.settingsRecovered)
            assertTrue("read from the copy before (${vm.prefs.hiddenGroups})", nfl in vm.prefs.hiddenGroups && news in vm.prefs.hiddenGroups)
            // Nothing left at all: the TV notices, puts back what the account hides at the next check, and says so.
            quiet()
            restartWith { java.io.File(dir, "prefs.json").writeText("{\"hiddenGr"); java.io.File(dir, "prefs.json.bak").delete() }
            assertTrue("noticed again", vm.store.settingsRecovered)
            rule.runOnIdle { vm.accountApi = site.base; vm.syncAccountPlaylists() }
            waitFor("put back from the account (${vm.prefs.hiddenGroups})") { nfl in vm.prefs.hiddenGroups && news in vm.prefs.hiddenGroups }
            waitFor("said (${vm.status})") { vm.status.orEmpty().contains("put back from your account") }
        }
    }

    /**
     * 1.15.1: a device with account profiles tells the account every playlist it has, with each login locked for the
     * account's own key (only the owner's browser opens it); "Add to account" then makes a playlist typed on the TV that
     * account playlist, keeping it.
     */
    @Test
    fun theDeviceListsItsPlaylistsForTheAccountAndAddToAccountKeepsThem() {
        FakeAvidSite().use { site ->
            val (pub, priv) = com.avidviewer.tv.data.DeviceKey.generate()
            val browser = com.avidviewer.tv.data.DeviceKey.load(pub, priv)
            linkForTest(site)
            site.accountKey = pub
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("playlists reported") { site.devicePlaylists != null }
            val list = site.devicePlaylists!!
            assertEquals(vm.playlists.size, list.length())
            val mine = (0 until list.length()).map { list.getJSONObject(it) }.first { it.getString("id") == TestData.P1 }
            assertEquals("My provider", mine.getString("name"))
            assertFalse("the login is never sent readable", mine.has("server") || mine.has("password") || mine.has("m3uUrl"))
            val login = com.avidviewer.tv.data.DeviceKey.open(browser, mine.getJSONObject("sealed"), com.avidviewer.tv.data.DeviceKey.ACCOUNT_INFO)
            val p1 = vm.playlists.first { it.id == TestData.P1 }
            assertEquals(p1.type, login.getString("type"))
            assertEquals(p1.server, login.getString("server")); assertEquals(p1.m3uUrl, login.getString("m3uUrl"))
            // Add to account: the account names the device's own playlist; nothing is downloaded again.
            site.inbox += org.json.JSONObject().put("playlistId", "acctMine").put("action", "update").put("version", 1)
                .put("name", "My provider").put("type", p1.type).put("profiles", org.json.JSONArray().put("main")).put("localId", TestData.P1)
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("adopted") { vm.playlists.first { it.id == TestData.P1 }.remoteId == "acctMine" }
            assertEquals(1, vm.playlists.count { it.name == "My provider" })
            assertTrue(site.acks.any { it.getString("playlistId") == "acctMine" && it.getBoolean("ok") })
        }
    }

    /**
     * 1.15.0 Watching now: while something plays, the device tells the account which playlist (never the channel or
     * title); Stop stream on the website stops it and says so, and Watch again starts it again.
     */
    @Test
    fun watchingNowReportsThePlaylistAndStopsFromTheAccount() {
        FakeAvidSite().use { site ->
            linkForTest(site)
            go(Screen.GUIDE)
            rule.runOnIdle { vm.chooseGroup(TestData.P1, "NEWS") }
            waitFor("channels") { vm.channelsIn("NEWS").isNotEmpty() }
            val ch = vm.channelsIn("NEWS").first()
            rule.runOnIdle { vm.preview(ch.key) }
            settle()
            kotlinx.coroutines.runBlocking { vm.reportPlaying() }
            waitFor("reported") { site.playing.isNotEmpty() }
            val r = site.playing.last()
            assertTrue(r.getBoolean("playing"))
            assertEquals("My provider", r.getString("playlistName"))
            assertFalse("never the channel", r.toString().contains(ch.name))
            assertFalse("one profile: no profile sent", r.has("profileName"))
            site.stopNext = true
            kotlinx.coroutines.runBlocking { vm.reportPlaying() }
            waitFor("stopped") { vm.streamStopped }
            settle()
            assertTrue(rule.onAllNodesWithText("This stream was stopped from your account").fetchSemanticsNodes().isNotEmpty())
            rule.runOnIdle { vm.watchAgain() }
            settle()
            assertFalse(vm.streamStopped)
            assertEquals(ch.key, vm.currentKey)
        }
    }

    /**
     * 1.16.0: favorites and lists follow the profile through the account; category and channel order and names follow
     * the playlist. This device's changes go up; the account's newer ones come down; nothing is sent when nothing changed.
     */
    @Test
    fun favoritesListsAndOrderFollowTheAccount() {
        FakeAvidSite().use { site ->
            restartWith { st -> st.savePlaylists(st.loadPlaylists().map { if (it.id == TestData.P1) it.copy(remoteId = "acctP1") else it }) }
            linkForTest(site)
            site.profileMap = mapOf("main" to "acctDad")
            site.profiles = org.json.JSONArray()
                .put(org.json.JSONObject().put("id", "acctDad").put("name", "Dad").put("parent", true).put("color", 0).put("pin", "")
                    .put("playlists", org.json.JSONArray().put("acctP1")).put("visibility", org.json.JSONObject()))
            waitFor("channels") { vm.channels.any { it.group == "NEWS" } }
            val news = vm.channels.filter { it.group == "NEWS" }
            val sports = vm.channels.first { it.group != "NEWS" }
            val id0 = news[0].key.substringAfter(':'); val id1 = news.getOrElse(1) { news[0] }.key.substringAfter(':')
            // Changed here before the first sync: a favorite and a renamed category.
            rule.runOnIdle { vm.updatePrefs { it.copy(favorites = listOf(news[0].key), groupNames = mapOf("NEWS" to "Headlines")) } }
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("lists sent") { site.syncs.any { it.getJSONArray("profiles").length() > 0 } }
            val sent = site.syncs.last { it.getJSONArray("profiles").length() > 0 }
            val dad = sent.getJSONArray("profiles").getJSONObject(0)
            assertEquals("acctDad", dad.getString("id"))
            assertEquals("acctP1:$id0", dad.getJSONObject("data").getJSONArray("favorites").getString(0))
            assertTrue("a real change time", dad.getLong("changedAt") > 1L)
            val order = sent.getJSONArray("playlists").getJSONObject(0)
            assertEquals("acctP1", order.getString("id"))
            assertEquals("Headlines", order.getJSONObject("data").getJSONObject("groupNames").getString("NEWS"))
            // Nothing changed: nothing sent again.
            val n = site.syncs.size
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("synced again") { site.syncs.size > n }
            assertEquals(0, site.syncs.last().getJSONArray("profiles").length())
            assertEquals(0, site.syncs.last().getJSONArray("playlists").length())
            // Changed on the website: favorites, a list, the category order and names, a channel's name.
            site.syncReply = org.json.JSONObject()
                .put("profiles", org.json.JSONArray().put(org.json.JSONObject().put("id", "acctDad").put("hash", "h2").put("data", org.json.JSONObject()
                    .put("favorites", org.json.JSONArray().put("acctP1:$id1").put("acctOther:9"))
                    .put("lists", org.json.JSONArray().put(org.json.JSONObject().put("id", "w1").put("name", "Evening").put("hidden", false)
                        .put("keys", org.json.JSONArray().put(sports.key.replaceBefore(':', "acctP1"))))))))
                .put("playlists", org.json.JSONArray().put(org.json.JSONObject().put("id", "acctP1").put("hash", "o2").put("data", org.json.JSONObject()
                    .put("groupOrder", org.json.JSONArray().put(sports.group).put("NEWS"))
                    .put("groupNames", org.json.JSONObject().put("NEWS", "News today"))
                    .put("channelOrder", org.json.JSONObject().put("NEWS", org.json.JSONArray().put(id1).put(id0)))
                    .put("channelNames", org.json.JSONObject().put(id0, "My channel")))))
            val m = site.syncs.size
            rule.runOnIdle { vm.syncAccountPlaylists() }
            // The lists and the order are applied one after the other (worked out off the screen's thread, BUG-057).
            waitFor("applied") { vm.prefs.favorites == listOf(news.getOrElse(1) { news[0] }.key) && vm.groupName("NEWS") == "News today" }
            assertEquals("Evening", vm.prefs.lists.single().name)
            assertEquals(listOf(sports.key), vm.prefs.lists.single().keys)
            assertEquals("News today", vm.groupName("NEWS"))
            assertEquals(sports.group, vm.prefs.groupOrder.first())
            assertEquals("My channel", vm.channelName(news[0]))
            assertEquals(listOf(news.getOrElse(1) { news[0] }.key, news[0].key).distinct(), vm.prefs.channelOrder["NEWS"]!!.distinct())
            // Applying the account's version isn't a change here; the account's hashes are sent as known.
            val k = site.syncs.size
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("synced after applying") { site.syncs.size > k }
            val last = site.syncs.last()
            assertEquals(0, last.getJSONArray("profiles").length())
            assertEquals(0, last.getJSONArray("playlists").length())
            assertEquals("h2", last.getJSONObject("known").getJSONObject("profiles").getString("acctDad"))
            assertEquals("o2", last.getJSONObject("known").getJSONObject("playlists").getString("acctP1"))
            assertTrue(m > 0)
            // A change on the TV goes up, with the channel of the playlist this device doesn't have kept.
            rule.runOnIdle { vm.updatePrefs { it.copy(favorites = listOf(news[0].key)) } }
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("change sent") { site.syncs.last().getJSONArray("profiles").length() > 0 }
            val fav = site.syncs.last().getJSONArray("profiles").getJSONObject(0).getJSONObject("data").getJSONArray("favorites")
            assertEquals(listOf("acctP1:$id0", "acctOther:9"), (0 until fav.length()).map { fav.getString(it) })
        }
    }

    /**
     * 1.17.0: pick up where you left off. Places and finished episodes go to the account locked with the history key
     * (never readable there); places from other TVs come back by playlist or by title, with the TV's name; My List and
     * Discover settings follow the profile; the account's TMDB key arrives; Remove on the TV removes it everywhere.
     */
    @Test
    fun placesMyListAndDiscoverFollowTheProfileBetweenTvs() {
        FakeAvidSite().use { site ->
            val now = System.currentTimeMillis()
            restartWith { st ->
                st.savePlaylists(st.loadPlaylists().map { if (it.id == TestData.P1) it.copy(remoteId = "acctP1") else it })
                st.saveResume(mapOf("${TestData.P1}:mDrama1" to 300_000L))
                st.savePlaceInfo(mapOf("${TestData.P1}:mDrama1" to com.avidviewer.tv.data.Store.PlaceInfo(3_600_000L, now - 60_000L)))
                st.saveWatched(listOf("${TestData.P1}:sCrime1:1:1"))
            }
            go(Screen.MOVIES)
            waitFor("movies") { vm.vodOf("movie").isNotEmpty() }
            linkForTest(site)
            site.profileMap = mapOf("main" to "acctDad")
            site.profiles = org.json.JSONArray().put(org.json.JSONObject().put("id", "acctDad").put("name", "Dad").put("parent", true).put("color", 0).put("pin", "")
                .put("playlists", org.json.JSONArray().put("acctP1")).put("visibility", org.json.JSONObject()))
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("managed") { vm.profiles.managed && site.report?.has("pubkey") == true }
            // The owner unlocked on the website: the history key, locked for this device.
            val h = ByteArray(32).also { java.util.Random(7).nextBytes(it) }
            // Already in the account: My List and Discover settings from the website, and the TMDB key.
            site.discoverDocs["acctDad"] = org.json.JSONObject().put("at", now).put("blob", com.avidviewer.tv.data.History.lock(h, org.json.JSONObject()
                .put("myList", org.json.JSONArray().put(org.json.JSONObject().put("t", "movie").put("name", "Drama Movie 3").put("year", "1993")))
                .put("services", org.json.JSONArray().put(org.json.JSONObject().put("id", 8).put("name", "Netflix")))
                .put("start", "provider").put("grownUps", false)))
            site.tmdbBlob = com.avidviewer.tv.data.History.lock(h, org.json.JSONObject().put("tmdb", "abcdef0123456789abcdef0123456789"))
            site.historySealed = sealFor(site.report!!.getString("pubkey"), org.json.JSONObject().put("historyKey", android.util.Base64.encodeToString(h, android.util.Base64.NO_WRAP)))
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("places sent (${site.historyPosts.size})") { site.historyItems.size >= 2 }
            val sent = site.historyItems.values.map { com.avidviewer.tv.data.History.open(h, it.getString("blob")) }
            val movie = sent.first { it.optString("t") == "m" }
            assertEquals("Drama Movie 1", movie.getString("title")); assertEquals("1991", movie.getString("year"))
            assertEquals(300_000L, movie.getLong("pos")); assertEquals("acctP1", movie.getString("pl")); assertEquals("mDrama1", movie.getString("id"))
            assertEquals("Living room", movie.getString("tv"))
            val ep = sent.first { it.optString("t") == "e" }
            assertTrue(ep.getBoolean("done")); assertEquals("Crime Show 1", ep.getString("series")); assertEquals(1, ep.getInt("s"))
            assertFalse("never a stream address", site.historyPosts.toString().contains("mp4"))
            // Another TV stopped in a copy of Action Movie 2 on another provider: matched by title and year.
            site.putHistory("acctDad", com.avidviewer.tv.data.History.itemId(h, com.avidviewer.tv.data.History.movieMatch("Action Movie 2", "1992")),
                com.avidviewer.tv.data.History.lock(h, org.json.JSONObject().put("t", "m").put("title", "Action Movie 2").put("year", "1992").put("pos", 600_000).put("dur", 5_400_000)
                    .put("done", false).put("tv", "Bedroom").put("pl", "acctOther").put("id", "m999")), now)
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("place from Bedroom") { vm.resumeOf("${TestData.P1}:mAction2") == 600_000L }
            assertTrue(vm.placeLine("${TestData.P1}:mAction2")!!.contains("on Bedroom"))
            val action2 = vm.vodOf("movie").first { it.key == "${TestData.P1}:mAction2" }
            assertEquals("from Bedroom", vm.continueFrom(action2))
            waitFor("My List from the account") { vm.prefs.myList.any { it.key == "${TestData.P1}:mDrama3" } }
            assertEquals("provider", vm.prefs.discoverStart); assertFalse(vm.prefs.discoverGrownUps)
            assertTrue(vm.onlyOnProvider)
            assertEquals(listOf(8), vm.prefs.myServices.map { it.id })
            assertEquals("abcdef0123456789abcdef0123456789", vm.tmdbKey())
            // Removed from Continue watching here: removed in the account.
            val drama1 = vm.vodOf("movie").first { it.key == "${TestData.P1}:mDrama1" }
            rule.runOnIdle { vm.removeFromContinue(drama1) }
            assertEquals(0L, vm.resumeOf(drama1.key))
            val id1 = com.avidviewer.tv.data.History.itemId(h, com.avidviewer.tv.data.History.movieMatch("Drama Movie 1", "1991"))
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("removal sent") { site.historyItems["acctDad|$id1"]?.isNull("blob") == true }
        }
    }

    /**
     * Nov 27, 2026 (Evan: "my list in movies and series are not syncing across devices"): each TV's My List changes are
     * joined into the account's list. A TV that saves while another TV changed the list since gets the newer list
     * back, keeps its own additions and removals, shows the joined list and sends it; removals elsewhere arrive.
     * Before, the TV that saved last replaced the whole list, so a title added on one TV could vanish everywhere.
     */
    @Test
    fun myListChangesOnTwoTvsAreJoinedNotReplaced() {
        FakeAvidSite().use { site ->
            val now = System.currentTimeMillis()
            restartWith { st -> st.savePlaylists(st.loadPlaylists().map { if (it.id == TestData.P1) it.copy(remoteId = "acctP1") else it }) }
            go(Screen.MOVIES)
            waitFor("movies") { vm.vodOf("movie").isNotEmpty() }
            linkForTest(site)
            site.profileMap = mapOf("main" to "acctDad")
            site.profiles = org.json.JSONArray().put(org.json.JSONObject().put("id", "acctDad").put("name", "Dad").put("parent", true).put("color", 0).put("pin", "")
                .put("playlists", org.json.JSONArray().put("acctP1")).put("visibility", org.json.JSONObject()))
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("managed") { vm.profiles.managed && site.report?.has("pubkey") == true }
            val h = ByteArray(32).also { java.util.Random(13).nextBytes(it) }
            fun entry(name: String, year: String) = org.json.JSONObject().put("t", "movie").put("name", name).put("year", year)
            fun siteList(vararg e: org.json.JSONObject, at: Long) {
                site.discoverDocs["acctDad"] = org.json.JSONObject().put("at", at).put("blob", com.avidviewer.tv.data.History.lock(h, org.json.JSONObject()
                    .put("myList", org.json.JSONArray().apply { e.forEach { put(it) } }).put("services", org.json.JSONArray()).put("start", "all").put("grownUps", true)))
            }
            fun names(): List<String> {
                val d = com.avidviewer.tv.data.History.open(h, site.discoverDocs["acctDad"]!!.getString("blob")).getJSONArray("myList")
                return (0 until d.length()).map { d.getJSONObject(it).getString("name") }.sorted()
            }
            fun mine() = vm.prefs.myList.map { it.key.substringAfter(':') }.sorted()
            siteList(entry("Drama Movie 3", "1993"), at = now - 60_000L)
            site.historySealed = sealFor(site.report!!.getString("pubkey"), org.json.JSONObject().put("historyKey", android.util.Base64.encodeToString(h, android.util.Base64.NO_WRAP)))
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("My List from the account (${mine()})") { mine() == listOf("mDrama3") }
            // The joined list goes up once, saved because nobody changed it since.
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("sent") { site.historyPosts.any { it.toString().contains("\"base\"") } }
            val savedAt = site.discoverDocs["acctDad"]!!.getLong("at")

            // Another TV adds Action Movie 2 while this TV adds Drama Movie 1 (its list doesn't have Action Movie 2 yet).
            siteList(entry("Drama Movie 3", "1993"), entry("Action Movie 2", "1992"), at = savedAt + 5_000L)
            val drama1 = vm.vodOf("movie").first { it.key == "${TestData.P1}:mDrama1" }
            rule.runOnIdle { vm.toggleMyList(drama1) }
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("both kept here (${mine()})") { mine() == listOf("mAction2", "mDrama1", "mDrama3") }
            waitFor("both kept in the account (${names()})") { names() == listOf("Action Movie 2", "Drama Movie 1", "Drama Movie 3") }

            // Removed on the other TV: removed here; removed here: removed in the account, the other TV's change kept.
            val at2 = site.discoverDocs["acctDad"]!!.getLong("at") + 5_000L
            siteList(entry("Action Movie 2", "1992"), entry("Drama Movie 1", "1991"), at = at2)
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("removal arrived (${mine()})") { mine() == listOf("mAction2", "mDrama1") }
            siteList(entry("Action Movie 2", "1992"), entry("Drama Movie 1", "1991"), entry("Drama Movie 2", "1992"), at = at2 + 5_000L)
            rule.runOnIdle { vm.toggleMyList(drama1) }
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("joined again (${names()})") { names() == listOf("Action Movie 2", "Drama Movie 2") }
            waitFor("here too (${mine()})") { mine() == listOf("mAction2", "mDrama2") }
        }
    }

    /**
     * Nov 26, 2026: the TV Guide's Recent list. This TV's channels are sent locked (only the channel, never a show or a
     * stream address); other TVs' channels arrive, told apart by device even when two TVs have the same name (why the
     * 1.18.0 live card could stay empty); an older TV's single last channel shows too; newest first; Menu › Remove
     * takes one off everywhere; and Continue watching has no live card any more.
     */
    @Test
    fun recentChannelsFollowTheProfileToItsOtherTvs() {
        FakeAvidSite().use { site ->
            val now = System.currentTimeMillis()
            restartWith { st -> st.savePlaylists(st.loadPlaylists().map { if (it.id == TestData.P1) it.copy(remoteId = "acctP1") else it }) }
            rule.runOnIdle { vm.chooseGroup(TestData.P1, "NEWS") }
            waitFor("channels") { vm.channelsIn("NEWS").size >= 3 }
            val (a, b, c) = vm.channelsIn("NEWS").take(3)
            linkForTest(site)
            site.profileMap = mapOf("main" to "acctDad")
            site.profiles = org.json.JSONArray().put(org.json.JSONObject().put("id", "acctDad").put("name", "Dad").put("parent", true).put("color", 0).put("pin", "")
                .put("playlists", org.json.JSONArray().put("acctP1")).put("visibility", org.json.JSONObject()))
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("managed") { vm.profiles.managed && site.report?.has("pubkey") == true }
            val h = ByteArray(32).also { java.util.Random(11).nextBytes(it) }
            site.historySealed = sealFor(site.report!!.getString("pubkey"), org.json.JSONObject().put("historyKey", android.util.Base64.encodeToString(h, android.util.Base64.NO_WRAP)))
            rule.runOnIdle { vm.syncAccountPlaylists() }
            val ctx = ApplicationProvider.getApplicationContext<Application>()
            waitFor("history key") { com.avidviewer.tv.data.AccountStore(ctx).historyKey != null }
            val me = com.avidviewer.tv.data.AccountStore(ctx).deviceId.ifBlank { "here" }
            val st = com.avidviewer.tv.data.Store(ctx)
            val pid = vm.profile.id
            fun rid(ch: com.avidviewer.tv.data.Channel) = com.avidviewer.tv.data.History.itemId(h, RECENT_MATCH + "acctP1:" + ch.key.substringAfter(':'))

            // This TV watched channel A: sent locked, as this TV.
            st.saveLive(org.json.JSONObject().put("recent", org.json.JSONArray().put(org.json.JSONObject().put("key", a.key).put("pl", "acctP1").put("ch", a.key.substringAfter(':'))
                .put("name", a.name).put("epg", a.epgId ?: "").put("at", now - 60_000L).put("tv", "Living room").put("dev", me))), pid)
            rule.runOnIdle { vm.refreshRecent(); vm.syncAccountPlaylists() }
            waitFor("A sent") { site.historyItems["acctDad|${rid(a)}"]?.isNull("blob") == false }
            val sent = com.avidviewer.tv.data.History.open(h, site.historyItems["acctDad|${rid(a)}"]!!.getString("blob"))
            assertEquals("r", sent.getString("t")); assertEquals(a.name, sent.getString("name")); assertEquals(me, sent.getString("dev"))
            assertFalse("no stream address", sent.toString().contains("://"))
            assertFalse("no show", sent.has("title") || sent.has("program"))

            // Another TV with the same name ("Living room") watched B later; an older app on Bedroom sends its last channel, C, by name.
            site.putHistory("acctDad", rid(b), com.avidviewer.tv.data.History.lock(h, org.json.JSONObject().put("t", "r").put("pl", "acctP1").put("ch", b.key.substringAfter(':'))
                .put("name", b.name).put("epg", "").put("tv", "Living room").put("dev", "dev-other")), now)
            site.putHistory("acctDad", com.avidviewer.tv.data.History.itemId(h, LIVE_MATCH), com.avidviewer.tv.data.History.lock(h, org.json.JSONObject().put("t", "l").put("pl", "acctOther").put("ch", "999")
                .put("name", "US| " + c.name + " HD").put("epg", "").put("until", now + 3_600_000L).put("tv", "Bedroom")), now - 30_000L)
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("B and C in Recent (${vm.recent})") { vm.recent.map { it.key }.containsAll(listOf(a.key, b.key, c.key)) }
            assertEquals("newest first", listOf(b.key, c.key, a.key), vm.recent.map { it.key })
            assertTrue(vm.recentNote(b.key), vm.recentNote(b.key).startsWith("Living room · "))
            assertTrue(vm.recentNote(a.key), vm.recentNote(a.key).startsWith("This TV · "))
            assertTrue(vm.recentNote(c.key), vm.recentNote(c.key).startsWith("Bedroom · "))


            // Menu › Remove from Recent: off here and removed in the account for every TV.
            rule.runOnIdle { vm.removeRecent(b.key) }
            assertEquals(listOf(c.key, a.key), vm.recent.map { it.key })
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("removal sent") { site.historyItems["acctDad|${rid(b)}"]?.isNull("blob") == true }
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("stays removed") { vm.recent.none { it.key == b.key } }

            // Continue watching is movies and episodes only now.
            assertTrue(vm.continueKeysForTests().none { it.startsWith(LIVE_PREFIX) })
        }
    }

    /**
     * Nov 26, 2026 (owner, board 51): Continue watching on the main menu, after Search. Rows for Live TV, Series and
     * Movies; OK on a channel plays it and Back comes back here; OK on a movie opens its page and Back comes back
     * here; Menu › Clear Live TV history empties the Live TV row. Movies has no Continue watching category any more.
     */
    @Test
    fun continueWatchingIsOnTheMainMenuWithRowsForLiveTvSeriesAndMovies() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "NEWS") }
        waitFor("channels") { vm.channelsIn("NEWS").size >= 2 }
        val (a, b) = vm.channelsIn("NEWS").take(2)
        rule.runOnIdle { vm.navSelect(Screen.MOVIES) }
        waitFor("movies") { vm.vodOf("movie").any { it.key == "${TestData.P1}:mDrama1" } }
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val st = com.avidviewer.tv.data.Store(ctx); val pid = vm.profile.id; val now = System.currentTimeMillis()
        st.saveLive(org.json.JSONObject().put("recent", org.json.JSONArray()
            .put(org.json.JSONObject().put("key", b.key).put("pl", "").put("ch", b.key.substringAfter(':')).put("name", b.name).put("at", now - 60_000L).put("tv", "").put("dev", "here"))
            .put(org.json.JSONObject().put("key", a.key).put("pl", "").put("ch", a.key.substringAfter(':')).put("name", a.name).put("at", now - 600_000L).put("tv", "").put("dev", "here"))), pid)
        rule.runOnIdle { vm.resumeForTests("${TestData.P1}:mDrama1", 300_000L) }
        // The menu, in the owner's order.
        rule.runOnIdle { vm.navMode = NavMode.FULL }
        settle()
        val order = listOf("Search", "Continue watching", "Discover", "Sports", "Live TV", "Series", "Movies", "Settings")
        val tops = order.map { t -> rule.onAllNodes(androidx.compose.ui.test.hasText(t) and androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top ?: -1f }
        assertTrue("menu order ${order.zip(tops)}", tops.all { it >= 0 } && tops.zipWithNext().all { (x, y) -> x < y })
        // Continue watching: Live TV first, newest channel first, then Movies.
        rule.runOnIdle { vm.navSelect(Screen.CONTINUE) }
        waitFor("rows") { vm.recent.size == 2 && vm.continueTitles("movie").isNotEmpty() }
        assertEquals(listOf(b.key, a.key), vm.recent.map { it.key })
        rule.onNodeWithTag("cw-live", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("cw-movies", useUnmergedTree = true).assertExists()
        waitFor("the newest channel has the highlight (focus: ${focused()})") { focused().contains(vm.channelName(b)) || focused().contains("LIVE") }
        // OK on it: full screen on that channel; Back: Continue watching again.
        press(listOf(Key.DirectionCenter))
        waitFor("full screen") { vm.screen == Screen.PLAYER && vm.currentKey == b.key }
        press(listOf(Key.Back))
        waitFor("back on Continue watching (screen ${vm.screen})") { vm.screen == Screen.CONTINUE }
        // OK on the movie: its page; Back: Continue watching.
        val drama = vm.continueTitles("movie").first { it.key == "${TestData.P1}:mDrama1" }
        rule.runOnIdle { vm.openFromContinue(drama) }
        waitFor("its page") { vm.vodDetail?.key == drama.key }
        rule.runOnIdle { vm.closeVodDetail() }
        waitFor("back on Continue watching") { vm.screen == Screen.CONTINUE }
        // Menu › Clear Live TV history.
        rule.runOnIdle { vm.clearRecent() }
        assertTrue(vm.recent.isEmpty())
        assertEquals(0, st.loadLive(pid).optJSONArray("recent")?.length() ?: 0)
        assertTrue("both marked gone for the other TVs", (st.loadLive(pid).optJSONObject("recentGone")?.length() ?: 0) >= 2)
        // Movies' own categories: no Continue watching.
        rule.runOnIdle { vm.navSelect(Screen.MOVIES) }
        settle()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("Continue watching") and androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().size <= 1)
    }

    /** Nov 26, 2026 (owner): with something to continue, the app opens on Continue watching, and nothing plays until Live TV is opened. */
    @Test
    fun theAppOpensOnContinueWatchingWhenItHasSomething() {
        OPEN_ON_CONTINUE_IN_TESTS = true
        try {
            restartWith { st -> st.saveResume(mapOf("${TestData.P1}:mDrama1" to 300_000L)) }
            waitFor("on Continue watching (screen ${vm.screen})") { vm.screen == Screen.CONTINUE }
            Thread.sleep(500)
            assertEquals("nothing plays on Continue watching", 0, rule.runOnIdle { vm.player.mediaItemCount })
            rule.runOnIdle { vm.navSelect(Screen.GUIDE) }
            waitFor("the guide's preview starts") { vm.player.mediaItemCount > 0 }
            // Nothing to continue: Live TV, as before.
            restartWith { st -> st.saveResume(emptyMap()); st.saveLive(org.json.JSONObject(), st.profileId) }
            Thread.sleep(500)
            assertEquals(Screen.GUIDE, vm.screen)
        } finally { OPEN_ON_CONTINUE_IN_TESTS = false }
    }

    /** 1.15.0: with 2+ profiles, the info bar over video shows the profile in use; with one, it doesn't. */
    @Test
    fun theInfoBarShowsTheProfileInUse() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "NEWS") }
        waitFor("channels") { vm.channelsIn("NEWS").isNotEmpty() }
        val key = vm.channelsIn("NEWS").first().key
        rule.runOnIdle { vm.openFullScreen(key, PlayContext.GROUP) }
        settle()
        assertTrue("the bar is up", rule.onAllNodesWithText("Up next", substring = true).fetchSemanticsNodes().isNotEmpty() || vm.screen == Screen.PLAYER)
        assertTrue("one profile: no chip", rule.onAllNodesWithTag("osd-profile").fetchSemanticsNodes().isEmpty())
        rule.runOnIdle { vm.addProfile("Sam", kids = false, color = 1) }
        settle(100)
        assertTrue("two profiles: the chip", rule.onAllNodesWithTag("osd-profile", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    /** Nov 20: on a ~2 GB device the preview at start-up waits 3 s after the guide, and not at all once the viewer has pressed a key. */
    @Test
    fun aSmallDeviceStartsThePreviewAFewSecondsAfterTheGuide() {
        lateinit var key: String
        rule.runOnIdle { vm.lightStart = true; vm.stop(); vm.player.clearMediaItems(); key = vm.currentKey ?: vm.channels.first().key; vm.screen = Screen.GUIDE }
        rule.runOnIdle { vm.launchPreview(key) }
        settle()
        assertTrue("not yet", vm.player.mediaItemCount == 0)
        waitFor("the preview after about 3 s") { vm.player.mediaItemCount > 0 }
        // A key pressed meanwhile: the viewer has picked something, so the delayed preview doesn't start.
        rule.runOnIdle { vm.stop(); vm.player.clearMediaItems(); vm.launchPreview(key); vm.lastKeyAt = System.currentTimeMillis() }
        Thread.sleep(3_600); settle()
        assertTrue("nothing after a key press", vm.player.mediaItemCount == 0)
    }

    /** 1.22.0 (owner, Nov 20): a device that isn't linked can't be used: full screen, nothing plays, closes once linked. */
    @Test
    fun aDeviceThatIsNotLinkedMustBeLinkedFirst() {
        FakeAvidSite().use { site ->
            rule.runOnIdle { vm.accountApi = site.base; vm.requireLink = true }
            waitFor("Link this device covers the app") { rule.onAllNodesWithTag("link-required").fetchSemanticsNodes().isNotEmpty() }
            waitFor("its code") { rule.onAllNodesWithText("K7M2-QX9P").fetchSemanticsNodes().isNotEmpty() }
            assertTrue("no Not now", rule.onAllNodesWithText("Not now").fetchSemanticsNodes().isEmpty())
            assertTrue(rule.onAllNodesWithTag("link-required-exit").fetchSemanticsNodes().isNotEmpty())
            waitFor("playback stopped") { !vm.player.playWhenReady }
            // Nothing can start while it's up.
            rule.runOnIdle { vm.channels.firstOrNull()?.let { vm.preview(it.key) } }
            settle()
            assertTrue("nothing plays", !vm.player.playWhenReady)
            // Nov 20: the code was entered on an account that already has 4 devices: the screen says so and keeps the code.
            site.full = true
            waitFor("told the account is full") { rule.onAllNodesWithTag("link-full").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(rule.onAllNodesWithText("already has 4 devices", substring = true).fetchSemanticsNodes().isNotEmpty())
            assertTrue("the code stays", rule.onAllNodesWithText("K7M2-QX9P").fetchSemanticsNodes().isNotEmpty())
            site.full = false
            site.approved = true
            waitFor("linked") { vm.account.linked }
            assertEquals(0, vm.linkFull)
            waitFor("the screen is gone") { rule.onAllNodesWithTag("link-required").fetchSemanticsNodes().isEmpty() }
            assertTrue(!vm.linkRequired)
        }
    }

    /** Nov 20: the Report a bug QR code opens avidviewer.app/report/?c=… for this device; email only when the site can't be reached. */
    @Test
    fun reportABugQrOpensTheWebsitesFormForThisDevice() {
        FakeAvidSite().use { site ->
            rule.runOnIdle { vm.accountApi = site.base }
            go(Screen.SETTINGS)
            rule.runOnIdle { vm.settingsSection = "about" }
            waitFor("the form's address") { vm.bugFormUrl != null }
            assertEquals(site.base + "/report/?c=ABCDE23456", vm.bugFormUrl)
            val asked = synchronized(site.reports) { site.reports.first { it.optBoolean("form") } }
            assertEquals("avid-viewer", asked.getString("app"))
            assertTrue(asked.getString("device").isNotBlank())
            for (p in vm.playlists) assertTrue("leaks ${p.name}", !asked.toString().contains(p.name))
            waitFor("the website text") { rule.onAllNodesWithText("Report a problem").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(rule.onAllNodesWithTag("bug-qr").fetchSemanticsNodes().isNotEmpty())
            assertTrue("no email address once the site answers", rule.onAllNodesWithText("bugs@avidviewer.app").fetchSemanticsNodes().isEmpty())
        }
    }

    /** Nov 20: no email. When avidviewer.app can't be reached, Report a bug says so and keeps trying (no QR, no address). */
    @Test
    fun reportABugWithoutTheWebsiteSaysSoAndKeepsTrying() {
        rule.runOnIdle { vm.accountApi = "http://127.0.0.1:9" }
        go(Screen.SETTINGS)
        rule.runOnIdle { vm.settingsSection = "about" }
        waitFor("can't reach") { vm.bugFormFailed }
        settle()
        assertTrue(rule.onAllNodesWithText("Report a bug").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("App version:", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Device:", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("can't be reached", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("no QR without the website", rule.onAllNodesWithTag("bug-qr").fetchSemanticsNodes().isEmpty())
        assertTrue("no email address", rule.onAllNodesWithText("bugs@avidviewer.app", substring = true).fetchSemanticsNodes().isEmpty())
    }

    /**
     * The crash dialog's Report issue sends the saved report to the crash relay (which emails bugs@),
     * then clears it; Cancel keeps it and sends nothing; a report that can't be sent is kept.
     * The relay here is a stand-in that answers like Apps Script: a redirect to the result.
     */
    /**
     * 1.22.0 (owner, Nov 20): reports go to avidviewer.app, not the email relay. A device that isn't linked is told it
     * can't follow the report and is offered the link code; once linked, the report is handed to the account. A device
     * asked for a new playback log ("It's still happening" on the website) sends one at its next check-in.
     */
    @Test
    fun reportsGoToTheWebsiteAndAnUnlinkedDeviceLinksAndClaimsThem() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        FakeAvidSite().use { site ->
            FakeRelay().use { relay ->
                java.io.File(app.filesDir, "last_crash.txt").writeText("Avid Viewer 1.22.0 · 2026-11-20 05:00:00\nDevice: Amazon AFTKRT · Android 11\n\nError: Avid Viewer closed while in use; no error reached the app.")
                rule.runOnIdle { vm.accountApi = site.base; vm.reportsSite = site.base; vm.crashRelay = relay.url; vm.reloadCrash(); vm.crashNoticeSeen = false; vm.crashReportStatus = null }
                settle()
                rule.onNodeWithTag("crash-report").performClick()
                waitFor("sent to the website") { site.reports.size == 1 && vm.lastCrash == null }
                assertEquals("nothing through the relay", 0, relay.posts.size)
                assertEquals("no token from a device that isn't linked", "", site.reports[0].getString("auth"))
                waitFor("the not-linked notice with a code") { vm.reportUnlinkedNotice && rule.onAllNodesWithTag("report-unlinked").fetchSemanticsNodes().isNotEmpty() && rule.onAllNodesWithText("K7M2-QX9P").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(listOf("AVR-00000001"), vm.unclaimedReports())
                site.approved = true
                waitFor("linked, and the report handed to the account") { vm.account.linked && synchronized(site.claimed) { site.claimed.toList() } == listOf("AVR-00000001") }
                waitFor("notice closed") { !vm.reportUnlinkedNotice && vm.unclaimedReports().isEmpty() }
                // "It's still happening" on the website: the next check-in sends a new playback log, with the token.
                rule.runOnIdle { vm.playbackLog.add(com.avidviewer.tv.data.PlaybackLog.STALL, "Live channel 4F2A", "Froze 3 s waiting for data.") }
                site.sendLog = true
                rule.runOnIdle { vm.checkAccount() }
                waitFor("a playback log sent") { site.reports.size == 2 }
                assertEquals("playback", site.reports[1].getString("kind"))
                assertTrue(site.reports[1].getString("auth").startsWith("Bearer TVTOKEN_"))
                assertEquals(0, relay.posts.size)
            }
        }
    }

    @Test
    fun crashDialogReportIssueSendsAndClearsAndCancelKeeps() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val relay = FakeRelay()
        val posts = relay.posts
        try {
            val crashText = "Avid Viewer 1.2.1 · 2026-11-13 11:00:00\nDevice: Amazon AFTKRT · Android 11\nThread: main\n\n" +
                "java.io.FileNotFoundException: http://provider.example:8080/get.php?username=alice&password=s3cret\n" +
                "  at com.avidviewer.tv.data.Net.open(Sources.kt:60)"
            fun crashAgain() {
                java.io.File(app.filesDir, "last_crash.txt").writeText(crashText)
                rule.runOnIdle { vm.crashRelay = relay.url; vm.reloadCrash(); vm.crashNoticeSeen = false; vm.crashReportStatus = null }
                settle()
            }

            // Cancel: the dialog closes, nothing is sent, the report stays.
            crashAgain()
            assertTrue(rule.onAllNodesWithText("Avid Viewer closed unexpectedly").fetchSemanticsNodes().isNotEmpty())
            assertTrue(rule.onAllNodesWithText("Report issue").fetchSemanticsNodes().isNotEmpty())
            rule.onNodeWithTag("crash-cancel").performClick(); settle()
            assertTrue(vm.crashNoticeSeen)
            assertTrue(vm.lastCrash != null && java.io.File(app.filesDir, "last_crash.txt").exists())
            assertEquals(0, posts.size)

            // The relay refuses: the report is kept and the dialog says so.
            relay.ok = false
            crashAgain()
            rule.onNodeWithTag("crash-report").performClick()
            waitFor("the refused report") { !vm.crashReportBusy && posts.size == 1 }
            assertTrue(vm.lastCrash != null)
            assertTrue(vm.crashReportStatus.orEmpty(), vm.crashReportStatus.orEmpty().startsWith("Couldn't send"))

            // Report issue: sent (without the provider's address or login), then cleared.
            relay.ok = true
            rule.onNodeWithTag("crash-report").performClick()
            waitFor("the report to be sent") { vm.lastCrash == null && posts.size == 2 }
            val sent = org.json.JSONObject(posts[1])
            assertEquals("avid-viewer", sent.getString("app"))
            assertTrue(sent.getString("crash"), sent.getString("crash").contains("java.io.FileNotFoundException") && sent.getString("crash").contains("Sources.kt:60"))
            for (secret in listOf("provider.example", "alice", "s3cret", "http://")) assertTrue("sent $secret", !posts[1].contains(secret))
            assertTrue(!java.io.File(app.filesDir, "last_crash.txt").exists())
            assertTrue(rule.onAllNodesWithText("Thanks", substring = true).fetchSemanticsNodes().isNotEmpty())
            waitFor("the thank-you to close") { vm.crashNoticeSeen }
        } finally { relay.close() }
    }

    @Test
    fun aboutShowsQrCodesForTheUserGuideAndTheControl4Driver() {
        go(Screen.SETTINGS)
        rule.runOnIdle { vm.settingsSection = "about" }
        settle()
        rule.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction()).onFirst()
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("control4-qr"))
        settle()
        assertTrue(rule.onAllNodesWithText("User guide").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Control4 mini driver").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag("guide-qr").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag("control4-qr").fetchSemanticsNodes().isNotEmpty())
        assertEquals("https://avidviewer.app/guide/", com.avidviewer.tv.data.Links.USER_GUIDE)
    }

    @Test
    fun updatesAndAboutBlocksAreReachableAndShownWhole() {
        go(Screen.SETTINGS)
        var tries = 0
        while (!focused().contains("Updates & about") && tries < 15) { press(listOf(Key.DirectionDown)); tries++ }
        press(listOf(Key.DirectionCenter))
        fun focusedTag(): String = rule.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes()
            .joinToString { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) ?: focused() }
        waitFor("OK opens Updates & about on its first block (focus: ${focusedTag()})") { vm.settingsSection == "about" && (focusedTag() == "block-Report a bug") }
        fun shownWhole(text: String) {
            val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
            val b = rule.onAllNodesWithText(text).onFirst().fetchSemanticsNode().boundsInRoot
            assertTrue("\"$text\" is cut off: $b (screen $root)", b.top >= root.top && b.bottom <= root.bottom)
        }
        shownWhole("Report a bug")
        tries = 0
        while (focusedTag() != "block-User guide and Control4 driver" && tries < 4) { press(listOf(Key.DirectionDown)); tries++ }
        assertEquals("Down reaches the QR codes block", "block-User guide and Control4 driver", focusedTag())
        shownWhole("User guide and Control4 driver")
        shownWhole("Control4 mini driver")
        // And back up: the bug report block shows whole again.
        press(listOf(Key.DirectionUp))
        shownWhole("Report a bug")
    }

    // ---------------------------------------------------------------- Sports, ESPN first

    @Test
    fun sportsListsEspnGamesAndOkGoesToTheirChannels() {
        val now = System.currentTimeMillis()
        val wings = FakeEspn.Companion.T("DET", "Red Wings", "Detroit", "e30526", "7-8-3", "2")
        val blues = FakeEspn.Companion.T("STL", "Blues", "St. Louis", "0070b9", "7-8-2", "1")
        val espn = FakeEspn(mapOf("hockey/nhl" to FakeEspn.board(
            FakeEspn.event("401", now - 30 * 60_000L, "in", wings, blues, listOf(Triple("TNT", "national", "TV")), "3:44 - 2nd", 2, "3:44"),
        )))
        try {
            com.avidviewer.tv.data.EspnFeed.base = espn.base
            rule.runOnIdle {
                vm.live.replacingChannels(TestData.P2) { add ->
                    add(com.avidviewer.tv.data.Channel("${TestData.P2}:tnt", "US| TNT HD", "SPORTS", null, "http://127.0.0.1:9/tnt.ts", null))
                }
            }
            go(Screen.SPORTS)
            waitFor("the game from ESPN") { vm.sportGames.any { it.game.id == "NHL:401" && it.channels.isNotEmpty() } }
            settle()
            assertTrue(rule.onAllNodesWithText("Where to watch").fetchSemanticsNodes().isNotEmpty())
            assertTrue(rule.onAllNodesWithText("LIVE NOW · 1").fetchSemanticsNodes().isNotEmpty())
            assertTrue(rule.onAllNodesWithText("National TV · TNT").fetchSemanticsNodes().isNotEmpty())
            // From the league filters down to the game, then OK: the highlight goes to its first channel.
            var tries = 0
            while (!focused().contains("Red Wings") && tries < 6) { press(listOf(Key.DirectionDown)); tries++ }
            assertTrue("on the game card (focus: ${focused()})", focused().contains("Red Wings"))
            press(listOf(Key.DirectionCenter))
            waitFor("OK on a game moves to its channels (focus: ${focused()})") { focused().contains("US| TNT HD") }
            press(listOf(Key.DirectionCenter))
            waitFor("OK on a channel plays it in the preview") { vm.currentKey == "${TestData.P2}:tnt" }
        } finally { espn.close() }
    }

    /**
     * Issues #11 and #12: in full screen from Sports, Channel Up/Down stays on the channels found for
     * that game; Back returns to Sports with the playing channel highlighted, not the top-left button.
     */
    @Test
    fun sportsFullScreenStaysOnTheGameAndBackKeepsYourPlace() {
        val now = System.currentTimeMillis()
        val wings = FakeEspn.Companion.T("DET", "Red Wings", "Detroit", "e30526", "7-8-3", "2")
        val blues = FakeEspn.Companion.T("STL", "Blues", "St. Louis", "0070b9", "7-8-2", "1")
        val espn = FakeEspn(mapOf("hockey/nhl" to FakeEspn.board(
            FakeEspn.event("401", now - 30 * 60_000L, "in", wings, blues, listOf(Triple("TNT", "national", "TV")), "3:44 - 2nd", 2, "3:44"),
        )))
        val p2 = TestData.P2
        try {
            com.avidviewer.tv.data.EspnFeed.base = espn.base
            rule.runOnIdle {
                vm.live.replacingChannels(p2) { add ->
                    add(com.avidviewer.tv.data.Channel("$p2:tnt", "US| TNT HD", "SPORTS", null, "http://127.0.0.1:9/tnt.ts", null))
                    add(com.avidviewer.tv.data.Channel("$p2:ev", "NHL 03: Red Wings @ Blues", "SPORTS", null, "http://127.0.0.1:9/ev.ts", null))
                }
            }
            go(Screen.SPORTS)
            waitFor("the game with two channels") { vm.sportGames.any { it.game.id == "NHL:401" && it.channels.size >= 2 } }
            settle()
            val chans = vm.sportGames.first { it.game.id == "NHL:401" }.channels.map { it.key }
            var tries = 0
            while (!focused().contains("Red Wings") && tries < 6) { press(listOf(Key.DirectionDown)); tries++ }
            fun nameOf(k: String) = vm.channelName(vm.sportsChannel(k)!!)
            press(listOf(Key.DirectionCenter))
            waitFor("on the first channel (focus: ${focused()})") { focused().contains(nameOf(chans[0])) }
            press(listOf(Key.DirectionCenter))
            waitFor("previewing") { vm.currentKey == chans[0] }
            press(listOf(Key.DirectionCenter))
            waitFor("full screen") { vm.screen == Screen.PLAYER }

            // Channel Up/Down: through this game's channels only, wrapping around.
            rule.runOnIdle { vm.step(1) }
            waitFor("the game's next channel") { vm.currentKey == chans[1 % chans.size] }
            assertTrue("label names the game: ${vm.contextLabel()}", vm.contextLabel().startsWith("Red Wings at Blues · channel 2 of ${chans.size}"))
            repeat(chans.size - 1) { rule.runOnIdle { vm.step(1) } }
            waitFor("wrapped back to the first channel") { vm.currentKey == chans[0] }
            rule.runOnIdle { vm.step(1) }
            waitFor("on the second channel again") { vm.currentKey == chans[1] }

            // Back: Sports, with the channel that was playing highlighted.
            press(listOf(Key.Back))
            waitFor("back on Sports") { vm.screen == Screen.SPORTS }
            waitFor("the playing channel is highlighted, not the top-left button (focus: ${focused()})") { focused().contains(nameOf(chans[1])) }
        } finally { espn.close() }
    }

    @Test
    fun settingsSportsTabSwitchesLeaguesWithOk() {
        go(Screen.SETTINGS)
        rule.runOnIdle { vm.settingsSection = "sports" }
        settle()
        assertTrue(rule.onAllNodesWithText("Leagues").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("College basketball (men)").fetchSemanticsNodes().isNotEmpty())
        rule.onAllNodesWithText("NFL").onFirst().performClick()
        rule.waitForIdle()
        assertEquals(false, vm.sportLeagues().first { it.id == "NFL" }.on)
    }

    // ---------------------------------------------------------------- guide management and quick dialogs

    @Test
    fun searchSitsInTheCategoryListsAndTheHeaderHasNoInstructions() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }
        settle()
        showCategories()
        assertTrue("search in the guide's category list", rule.onAllNodesWithTag("nav-search").fetchSemanticsNodes().isNotEmpty())
        assertTrue("no key instructions in the header", rule.onAllNodesWithText("OK: preview", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue("no category label next to search", rule.onAllNodesWithText("KIDS  ·  12 channels").fetchSemanticsNodes().isEmpty())
        rule.runOnIdle { vm.guideQuery = "Free News" }
        settle()
        assertTrue(rule.onAllNodesWithText("Clear search", substring = true).fetchSemanticsNodes().isNotEmpty())
        go(Screen.MOVIES)
        waitFor("movies") { vm.vodOf("movie").isNotEmpty() }
        assertTrue("search in the movie category list", rule.onAllNodesWithTag("vod-search").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun everySettingsSectionIsReachableWithDownIncludingTheLast() {
        go(Screen.SETTINGS)
        waitFor("Settings opens on Playlists (focus: ${focused()})") { focused().contains("Playlists") && vm.settingsSection == "playlists" }
        // 1.12.0: Account is the first section, just above Playlists.
        press(listOf(Key.DirectionUp))
        assertTrue("Up reaches Account (focus: ${focused()})", focused().startsWith("Account"))
        press(listOf(Key.DirectionUp))
        assertTrue("no wrap at the top (focus: ${focused()})", focused().startsWith("Account"))
        var tries = 0
        while (!focused().startsWith("Updates & about") && tries < 16) { press(listOf(Key.DirectionDown)); tries++ }
        assertTrue("Down reaches the last section, Updates & about (focus: ${focused()})", focused().startsWith("Updates & about"))
        assertEquals("moving the highlight doesn't open sections", "playlists", vm.settingsSection)
        // The section list doesn't wrap: Down at the end stays on the last section.
        press(listOf(Key.DirectionDown))
        assertTrue("no wrap in the section list (focus: ${focused()})", focused().startsWith("Updates & about"))
        tries = 0
        while (!focused().startsWith("Account") && tries < 16) { press(listOf(Key.DirectionUp)); tries++ }
        press(listOf(Key.DirectionCenter))
        waitFor("OK opens it (section=${vm.settingsSection})") { vm.settingsSection == "account" && vm.settingsInContent }
    }

    @Test
    fun onlyCategoryAndChannelListsWrapAround() {
        // The main menu: Down on its last item stays there.
        press(listOf(Key.Back, Key.Back, Key.Back))
        waitFor("the main menu") { vm.navMode == NavMode.FULL }
        var tries = 0
        while (!focused().contains("Settings") && tries < 12) { press(listOf(Key.DirectionDown)); tries++ }
        press(listOf(Key.DirectionDown))
        assertTrue("no wrap in the main menu (focus: ${focused()})", focused().contains("Settings"))
        // The poster grid: Down on the last row stays on it.
        go(Screen.MOVIES)
        waitFor("movies") { vm.vodOf("movie").isNotEmpty() }
        rule.runOnIdle { vm.chooseVodCategory("movie", "Action") }
        settle()
        press(listOf(Key.DirectionRight))
        repeat(4) { press(listOf(Key.DirectionDown)) }
        val last = focused()
        press(listOf(Key.DirectionDown))
        assertEquals("no wrap in the poster grid", last, focused())
        // Nov 22, 2026: in the profile's sort (Date added by default), so the last row is whatever the list ends with.
        val lastRow = vm.vodItems("movie").let { l -> l.takeLast(if (l.size % 7 == 0) 7 else l.size % 7) }.map { it.name }
        assertTrue("on the grid's last row (focus: $last, last row: $lastRow)", lastRow.any { last.contains(it) })
    }

    @Test
    fun downAtTheEndOfAPlaylistListStaysInPlaylists() {
        go(Screen.SETTINGS)
        rule.runOnIdle { vm.settingsSection = "playlists" }
        settle()
        rule.onAllNodesWithText("Free channels").onFirst().requestFocus()
        settle()
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown))
        assertEquals("still in Playlists (focus: ${focused()})", "playlists", vm.settingsSection)
        assertTrue("still on the last playlist (focus: ${focused()})", focused().contains("Free channels"))
        // In the actions column too: Down past the last option goes nowhere.
        press(listOf(Key.DirectionRight))
        repeat(12) { press(listOf(Key.DirectionDown)) }
        assertEquals("still in Playlists after Down in the actions (focus: ${focused()})", "playlists", vm.settingsSection)
    }

    @Test
    fun tvGuideSettingsAndGuideSourcesOpen() {
        go(Screen.SETTINGS)
        rule.runOnIdle { vm.settingsSection = "guide" }
        settle()
        assertTrue(rule.onAllNodesWithText("Download the guide every").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("guide-every-12").performClick()
        settle()
        assertEquals(12, vm.prefs.guideRefreshHours)
        rule.runOnIdle { vm.settingsSection = "playlists" }
        settle()
        rule.onAllNodesWithText("My provider").onFirst().performClick()
        settle()
        rule.onNodeWithTag("pl-guide-sources").performClick()
        settle()
        assertTrue(rule.onAllNodesWithText("Guide sources · My provider").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Provider's guide").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("guide-src-add").performClick()
        settle()
        assertTrue(rule.onAllNodesWithText("New guide source").fetchSemanticsNodes().isNotEmpty())
        // Back with something typed asks before throwing it away (SET-02); Keep editing is highlighted.
        rule.onNodeWithTag("guide-src-url").performClick()
        settle()
        rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasTestTag("guide-src-url")).onFirst().performTextInput("http://example.invalid/guide.xml")
        settle()
        press(listOf(Key.Back)) // closes the keyboard
        if (rule.onAllNodesWithText("Discard changes?").fetchSemanticsNodes().isEmpty()) press(listOf(Key.Back))
        waitFor("Discard changes? (focus: ${focused()})") { rule.onAllNodesWithText("Discard changes?").fetchSemanticsNodes().isNotEmpty() }
        waitFor("Keep editing highlighted (focus: ${focused()})") { focused().contains("Keep editing") }
        press(listOf(Key.DirectionCenter))
        waitFor("still editing") { rule.onAllNodesWithText("Discard changes?").fetchSemanticsNodes().isEmpty() && rule.onAllNodesWithText("New guide source").fetchSemanticsNodes().isNotEmpty() }
        press(listOf(Key.Back))
        waitFor("asked again") { rule.onAllNodesWithText("Discard").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Discard").onFirst().performClick()
        waitFor("the form closed without saving") { rule.onAllNodesWithText("New guide source").fetchSemanticsNodes().isEmpty() }
        assertTrue(vm.playlists.first { it.id == TestData.P1 }.epgSources.none { it.url.contains("example.invalid") })
        // Deleting asks first.
        rule.onAllNodesWithTag("guide-src-delete").onFirst().performClick()
        settle()
        assertTrue(rule.onAllNodesWithText("can't be undone", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun matchGuideFromTheChannelMenu() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }
        settle()
        val key = vm.channelsIn("KIDS").first().key
        rule.runOnIdle { vm.channelMenu = key }
        settle()
        rule.onAllNodesWithText("Match guide", substring = true).onFirst().performClick()
        waitFor("the match dialog") { rule.onAllNodesWithText("Match guide · ", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("No guide").onFirst().performClick()
        settle()
        assertEquals("", vm.prefs.epgMap[key])
        assertEquals(null, vm.matchingChannel)
    }

    @Test
    fun choosingAListOrSavingARenameClosesTheDialog() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }
        settle()
        val key = vm.channelsIn("KIDS").first { !vm.listsFor(it.key).contains("Kids night") }.key
        rule.runOnIdle { vm.listPickerKey = key }
        settle()
        // The row in the picker ("＋  …Kids night"), not the category list behind it.
        val rows = rule.onAllNodesWithText("Kids night", substring = true)
        val idx = rows.fetchSemanticsNodes().indexOfFirst { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text.startsWith("＋") } == true }
        assertTrue("the list is in the picker", idx >= 0)
        rows[idx].performClick()
        settle()
        assertEquals("the picker closes once a list is chosen", null, vm.listPickerKey)
        assertTrue(vm.listsFor(key).contains("Kids night"))
        rule.runOnIdle { vm.channelMenu = key }
        settle()
        rule.onAllNodesWithText("Rename", substring = true).onFirst().performClick()
        settle()
        waitFor("the rename dialog") { rule.onAllNodesWithText("Rename channel").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Save").onFirst().performClick()
        settle()
        assertEquals("the channel menu closes after saving the new name", null, vm.channelMenu)
        assertTrue("no dialog left open", rule.onAllNodesWithText("Rename channel").fetchSemanticsNodes().isEmpty())
    }

    // ---------------------------------------------------------------- layers (Nov 10, evening)

    private fun goDeepInBig() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "BIG") }
        waitFor("the long category") { focused().contains("BIG Channel 1 with") }
        hold(Key.DirectionDown, 25)
        var tries = 0
        while (!focused().contains("BIG Channel 60 with") && tries < 70) {
            press(listOf(if (Regex("BIG Channel (\\d+) ").find(focused())?.groupValues?.get(1)?.toInt()?.let { it > 60 } == true) Key.DirectionUp else Key.DirectionDown)); tries++
        }
        assertTrue("couldn't get to channel 60 (focus: ${focused()})", focused().contains("BIG Channel 60 with"))
    }

    // ---------------------------------------------------------------- navigation store (audit work plan 2.1)

    @Test
    fun aCategorySeenBeforeComesBackOnTheSameChannelEvenAfterAChannelAboveIsHidden() {
        goDeepInBig()
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }
        waitFor("KIDS starts at its first channel (focus: ${focused()})") { focused().contains("KIDS Channel 1 with") }
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "BIG") }
        waitFor("BIG comes back on channel 60, not the top (focus: ${focused()})") { focused().contains("BIG Channel 60 with") }
        // Hide channels above it: the same channel keeps the highlight (positions are by key, not by index).
        rule.runOnIdle { vm.setChannelsHidden(listOf("${TestData.P1}:big3", "${TestData.P1}:big4"), true) }
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }
        waitFor("KIDS again (focus: ${focused()})") { focused().contains("KIDS Channel") }
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "BIG") }
        waitFor("still channel 60 after hiding two above it (focus: ${focused()})") { focused().contains("BIG Channel 60 with") }
    }

    @Test
    fun backFromFullScreenKeepsTheChosenTimeAndProgramme() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "NEWS") }
        waitFor("NEWS") { focused().contains("NEWS Channel 1 with") }
        press(listOf(Key.DirectionDown, Key.DirectionDown))
        // Into the programmes, then far enough right that the guide's window moves on.
        val seen = ArrayList<String>()
        repeat(9) { press(listOf(Key.DirectionRight)); seen.add(focused() + " @" + vm.nav.guideWindowStart) }
        val programme = focused()
        assertTrue("Right should move the window on to later programmes (focus: $programme; base ${(vm.now / (30 * 60_000L)) * (30 * 60_000L)}; steps $seen)", programme.contains("Program") && vm.nav.guideWindowStart > (vm.now / (30 * 60_000L)) * (30 * 60_000L))
        val window = vm.nav.guideWindowStart
        press(listOf(Key.DirectionCenter)) // preview
        press(listOf(Key.DirectionCenter)) // full screen
        waitFor("full screen") { vm.screen == Screen.PLAYER }
        press(listOf(Key.Back))
        waitFor("back on the same programme, same time window (focus: ${focused()}, wanted $programme)") {
            vm.screen == Screen.GUIDE && focused() == programme && vm.nav.guideWindowStart == window
        }
    }

    @Test
    fun backFromATitlePageReturnsToItsPoster() {
        go(Screen.MOVIES)
        waitFor("movies") { vm.vodOf("movie").isNotEmpty() }
        rule.runOnIdle { vm.chooseVodCategory("movie", "Action") }
        settle()
        press(listOf(Key.DirectionRight, Key.DirectionDown, Key.DirectionRight))
        val poster = focused()
        assertTrue("should be on a poster (focus: $poster)", poster.contains("Action Movie"))
        press(listOf(Key.DirectionCenter))
        waitFor("the title's page") { vm.vodDetail != null }
        press(listOf(Key.Back))
        waitFor("back on the same poster (focus: ${focused()}, wanted $poster)") { vm.vodDetail == null && focused() == poster }
    }

    // ---------------------------------------------------------------- focus and Back rules (audit work plan 2.2, 2.3)

    @Test
    fun settingsLeftAndBackReturnToTheSectionYouWereIn() {
        // First leave the guide from its playlist list (this used to leave a stale "in the playlist list" flag).
        press(listOf(Key.Back, Key.Back))
        waitFor("the playlist list (nav=${vm.navMode})") { vm.navMode == NavMode.COLLAPSED && vm.inPlaylistTree }
        go(Screen.SETTINGS)
        waitFor("Settings opens on its first section (focus: ${focused()})") { focused().contains("Playlists") }
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown)) // past Profiles, List Editor, Location, Display
        waitFor("on TV guide (focus: ${focused()})") { focused().contains("TV guide") }
        assertEquals("highlighting a section doesn't open it", "playlists", vm.settingsSection)
        press(listOf(Key.DirectionCenter))
        waitFor("OK opens it and lands on its first control (section=${vm.settingsSection}, focus: ${focused()})") {
            vm.settingsSection == "guide" && vm.settingsInContent && focused().contains("4 hours")
        }
        val steps = ArrayList<String>()
        repeat(6) { press(listOf(Key.DirectionDown)); steps.add(focused()) }
        press(listOf(Key.DirectionLeft)); steps.add("◀ " + focused())
        waitFor("Left goes back to TV guide in the list, not a neighbouring section (section=${vm.settingsSection}, focus: ${focused()}, steps $steps)") {
            vm.settingsSection == "guide" && focused().contains("TV guide") && !vm.settingsInContent
        }
        assertEquals("Left from Settings didn't open the main menu", NavMode.COLLAPSED, vm.navMode)
        press(listOf(Key.DirectionRight))
        waitFor("back inside") { vm.settingsInContent }
        press(listOf(Key.Back))
        waitFor("Back goes up one level, to TV guide in the list (focus: ${focused()}, nav=${vm.navMode})") {
            vm.navMode == NavMode.COLLAPSED && vm.settingsSection == "guide" && focused().contains("TV guide")
        }
        press(listOf(Key.Back))
        waitFor("Back again opens the main menu") { vm.navMode == NavMode.FULL }
    }

    @Test
    fun backAtTheTopAsksForASecondPressBeforeExiting() {
        press(listOf(Key.Back, Key.Back, Key.Back))
        waitFor("the main menu") { vm.navMode == NavMode.FULL }
        press(listOf(Key.Back))
        assertEquals("Press Back again to exit", vm.notice)
        assertTrue("one Back mustn't exit", !rule.activity.isFinishing)
        press(listOf(Key.Back))
        assertTrue("a second Back exits", rule.activity.isFinishing)
    }

    @Test
    fun backFromAChannelOpenedFromSearchReturnsToTheResult() {
        go(Screen.SEARCH)
        rule.runOnIdle { vm.search("KIDS Channel 3") }
        waitFor("results") { vm.searchResults?.any { it.stations.isNotEmpty() } == true }
        var tries = 0
        while (!focused().contains("KIDS Channel 3 with") && tries < 12) { press(listOf(Key.DirectionDown)); tries++ }
        assertTrue("couldn't reach the result (focus: ${focused()})", focused().contains("KIDS Channel 3 with"))
        val result = focused()
        press(listOf(Key.DirectionCenter))
        waitFor("full screen") { vm.screen == Screen.PLAYER }
        press(listOf(Key.Back))
        waitFor("Back returns to the search results, on the chosen result (screen=${vm.screen}, focus: ${focused()})") {
            vm.screen == Screen.SEARCH && focused() == result
        }
    }

    /** The guide fills the screen while it has the highlight (Nov 15): ◀'s way back to the category list. */
    private fun showCategories() {
        rule.runOnIdle { vm.navFocusKey = vm.selectedGroup }
        waitFor("the category list (focus: ${focused()})") { vm.inGroupNav }
    }

    @Test
    fun theMainMenuShowsAsIconsAtLaunchOnTheGuide() {
        settle()
        assertEquals(Screen.GUIDE, vm.screen)
        assertEquals(NavMode.GROUPS, vm.navMode)
        // Nov 15: on the guide, with a category chosen, the guide fills the screen; ◀ brings the category list and the menu icons.
        waitFor("the guide fills the screen") { vm.guideHasFocus && rule.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isEmpty() }
        press(listOf(Key.DirectionLeft))
        waitFor("main menu icons are on screen after ◀ (focus: ${focused()})") { rule.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty() && vm.inGroupNav }
    }

    @Test
    fun backFromDeepInACategoryStepsThroughTheLayers() {
        goDeepInBig()
        press(listOf(Key.Back))
        waitFor("Back highlights the current category in the category list (focus: ${focused()}, nav=${vm.navMode})") {
            vm.navMode == NavMode.GROUPS && vm.inGroupNav && (focused().trim().startsWith("BIG") && focusedSelected())
        }
        press(listOf(Key.Back))
        waitFor("Back again highlights the same category in the playlist list, not the top (focus: ${focused()}, nav=${vm.navMode})") {
            vm.navMode == NavMode.COLLAPSED && vm.inPlaylistTree && focused().trim().startsWith("BIG")
        }
        press(listOf(Key.Back))
        waitFor("Back again opens the main menu (nav=${vm.navMode})") { vm.navMode == NavMode.FULL }
    }

    @Test
    fun leftWorksLikeBackBetweenTheLayers() {
        goDeepInBig()
        press(listOf(Key.DirectionLeft))
        waitFor("Left from a channel name highlights the current category (focus: ${focused()})") { vm.inGroupNav && (focused().trim().startsWith("BIG") && focusedSelected()) }
        press(listOf(Key.DirectionLeft))
        waitFor("Left again goes to the playlist list on that category (focus: ${focused()}, nav=${vm.navMode})") {
            vm.navMode == NavMode.COLLAPSED && focused().trim().startsWith("BIG")
        }
        press(listOf(Key.DirectionLeft))
        waitFor("Left again opens the main menu (nav=${vm.navMode})") { vm.navMode == NavMode.FULL }
    }

    // ---------------------------------------------------------------- reported bugs (Nov 10)

    /** Holds a remote button: a key down, [repeats] auto-repeats (like holding it), then the key up. */
    private fun hold(k: Key, repeats: Int) {
        val code = k.nativeKeyCode
        rule.runOnUiThread {
            val target = topWindowView() ?: rule.activity.window.decorView
            val t = android.os.SystemClock.uptimeMillis()
            for (r in 0..repeats) target.dispatchKeyEvent(AndroidKeyEvent(t, t + r * 50L, AndroidKeyEvent.ACTION_DOWN, code, r))
            target.dispatchKeyEvent(AndroidKeyEvent(t, t + repeats * 50L + 40, AndroidKeyEvent.ACTION_UP, code, 0))
        }
        settle()
    }

    /** A tiny Xtream Codes provider on this machine: a login, and a series with two seasons of episodes. */
    private class FakeProvider(private val plot: String = "A show for testing.") : AutoCloseable {
        /** A third season appears (a new episode is released). */
        @Volatile var season3 = false
        /** Series 99 has one long season (30 episodes), so most of them are off screen. */
        private fun longSeason() = (1..30).joinToString(",") { n -> """{"id":"${8000 + n}","episode_num":$n,"title":"Long Ep $n","container_extension":"mp4","info":{}}""" }
        private val socket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        private val thread = Thread {
            while (!socket.isClosed) {
                val c = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    c.use { conn ->
                        val reader = conn.getInputStream().bufferedReader()
                        val requestLine = reader.readLine() ?: return@use
                        while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                        val target = requestLine.split(" ").getOrElse(1) { "/" }
                        val body = when {
                            target.contains("xmltv") -> "<tv></tv>"
                            target.contains("action=get_series_info") && target.contains("series_id=99") -> """{"info":{"name":"Long Show"},"episodes":{"1":[${longSeason()}]}}"""
                            target.contains("action=get_series_info") -> """{"info":{"name":"Test Show","plot":"$plot"},"episodes":{
                                "1":[{"id":"101","episode_num":1,"title":"Pilot","container_extension":"mp4","info":{"duration":"00:42:00"}},
                                     {"id":"102","episode_num":2,"title":"Second","container_extension":"mp4","info":{}}],
                                "2":[{"id":"201","episode_num":1,"title":"Season Two Opener","container_extension":"mkv","info":{"air_date":"${if (target.contains("series_id=79")) "2026-11-01" else "2025-03-10"}"}}]""" +
                                (if (season3) ""","3":[{"id":"301","episode_num":1,"title":"Season Three","container_extension":"mkv","info":{}}]""" else "") + "}}"
                            target.contains("action=") -> "[]"
                            else -> """{"user_info":{"auth":1,"status":"Active"},"server_info":{}}"""
                        }.toByteArray()
                        val out = conn.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(body); out.flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { runCatching { socket.close() } }
    }

    @Test
    fun aLongDescriptionStillLeavesRoomForSeasonsAndEpisodes() {
        val longPlot = List(40) { "A very long description of the show that goes on and on, sentence $it." }.joinToString(" ")
        FakeProvider(longPlot).use { server ->
            rule.runOnIdle { vm.addPlaylist("Fake provider", "xtream", "http://127.0.0.1:${server.port}", "u", "", "", "", liveOn = false, vodOn = true) }
            waitFor("the provider to be added (status=${vm.status})") { vm.playlists.any { it.name == "Fake provider" } }
            val pid = vm.playlists.first { it.name == "Fake provider" }.id
            go(Screen.SERIES)
            rule.runOnIdle { vm.openVodDetail(com.avidviewer.tv.data.VodItem("$pid:s78", pid, "series", "Long Show", null, "", seriesId = "78", category = "Shows")) }
            waitFor("episodes to load") { vm.detailEpisodes?.isNotEmpty() == true }
            // Nothing watched yet: the page opens on Season 1, Episode 1 (#8). Up goes back to the buttons.
            waitFor("the page opens on episode 1 (focus: ${focused()})") { focused().contains("Pilot") }
            var tries = 0
            while (!focused().contains("My List") && tries < 12) { press(listOf(Key.DirectionUp)); tries++ }
            assertTrue("couldn't go Up to the buttons (focus: ${focused()})", focused().contains("My List"))
            tries = 0
            while (!focused().contains("Season 1") && tries < 10) { press(listOf(Key.DirectionDown)); tries++ }
            assertTrue("couldn't reach the season buttons below a long description (focus: ${focused()})", focused().contains("Season 1"))
            tries = 0
            while (!focused().contains("Pilot") && tries < 6) { press(listOf(Key.DirectionDown)); tries++ }
            assertTrue("couldn't reach an episode below a long description (focus: ${focused()})", focused().contains("Pilot"))
        }
    }

    /** Issues #5 and #6: Back and Left from Series posters go to the current category, then the main menu. */
    @Test
    fun backAndLeftFromPostersGoToTheCurrentCategoryThenTheMainMenu() {
        rule.runOnIdle { vm.navSelect(Screen.SERIES) }
        waitFor("series") { vm.vodOf("series").isNotEmpty() }
        rule.runOnIdle { vm.chooseVodCategory("series", "Crime") }
        rule.runOnIdle { vm.vodCatFocus++ }
        waitFor("on the Crime category (focus: ${focused()})") { focused().contains("Crime") && !focused().contains("Show") }
        press(listOf(Key.DirectionRight))
        waitFor("on a Crime poster (focus: ${focused()})") { focused().contains("Crime Show") }
        press(listOf(Key.DirectionDown)); press(listOf(Key.DirectionRight)); press(listOf(Key.DirectionRight))
        // #6: Left back out of the posters lands on Crime, not on whichever category is level with them.
        var tries = 0
        while (focused().contains("Show") && tries < 8) { press(listOf(Key.DirectionLeft)); tries++ }
        assertTrue("Left from the posters didn't land on Crime (focus: ${focused()})", (focused().contains("Crime") && !focused().contains("Show") && focusedSelected()))
        // #5: title page → Back → its poster → Back → the category → Back → the main menu, on Series.
        press(listOf(Key.DirectionRight))
        waitFor("a poster again") { focused().contains("Crime Show") }
        press(listOf(Key.DirectionCenter))
        waitFor("the title page") { vm.vodDetail != null }
        press(listOf(Key.Back))
        waitFor("Back returns to the poster (focus: ${focused()})") { vm.vodDetail == null && focused().contains("Crime Show") }
        press(listOf(Key.Back))
        waitFor("Back goes to the Crime category (focus: ${focused()})") { (focused().contains("Crime") && !focused().contains("Show") && focusedSelected()) && vm.navMode != NavMode.FULL }
        press(listOf(Key.Back))
        waitFor("Back opens the main menu on Series (focus: ${focused()} mode=${vm.navMode})") { vm.navMode == NavMode.FULL && focused().contains("Series") }
        assertEquals(Screen.SERIES, vm.screen)
    }

    /**
     * 1.17.1 (owner report Nov 17): a series stays in Continue watching until its latest episode is watched, and comes
     * back when a new episode or season appears. Episodes watched from the series page before 1.17.1 (saved under the
     * provider's episode id) move to the series' own keys.
     */
    @Test
    fun aSeriesStaysInContinueWatchingUntilItsLatestEpisodeIsWatched() {
        FakeProvider().use { server ->
            rule.runOnIdle { vm.addPlaylist("Fake provider", "xtream", "http://127.0.0.1:${server.port}", "u", "", "", "", liveOn = false, vodOn = true) }
            waitFor("the provider to be added (status=${vm.status})") { vm.playlists.any { it.name == "Fake provider" } }
            val pid = vm.playlists.first { it.name == "Fake provider" }.id
            val show = com.avidviewer.tv.data.VodItem("$pid:s78", pid, "series", "Test Show", null, "", seriesId = "78", category = "Shows")
            rule.runOnIdle { vm.catalog.replace(pid, sequenceOf(show)) { false } }
            // Watched on 1.17.0 from the series page: saved under the provider's episode id.
            rule.runOnIdle { vm.markWatched("$pid:e101") }
            go(Screen.SERIES)
            rule.runOnIdle { vm.openVodDetail(show) }
            waitFor("episodes") { vm.detailEpisodes?.size == 3 }
            assertTrue("moved to the series' key", vm.isWatched("$pid:s78:1:1"))
            assertFalse(vm.isWatched("$pid:e101"))
            assertEquals(listOf(show.key), vm.continueKeysForTests())
            rule.runOnIdle { vm.closeVodDetail() }
            // Watched up to the latest episode (S2 E1): off the list.
            rule.runOnIdle { vm.markWatched("$pid:s78:1:2"); vm.markWatched("$pid:s78:2:1") }
            assertTrue(vm.continueKeysForTests().isEmpty())
            // A new season appears: back on the list.
            server.season3 = true
            rule.runOnIdle { vm.openVodDetail(show) }
            waitFor("the new season") { vm.detailEpisodes?.size == 4 }
            rule.runOnIdle { vm.closeVodDetail() }
            assertEquals(listOf(show.key), vm.continueKeysForTests())
            // Removed with Menu: off until something new happens; watching the new season's episode finishes it.
            rule.runOnIdle { vm.removeFromContinue(show) }
            assertTrue(vm.continueKeysForTests().isEmpty())
            Thread.sleep(5)
            rule.runOnIdle { vm.markWatched("$pid:s78:3:1") }
            assertTrue(vm.continueKeysForTests().isEmpty())
        }
    }

    /**
     * Nov 22, 2026 (owner): a series in My List with episodes left is in Continue watching; Menu on an episode marks
     * it, or its season, watched; once an episode counts as watched the player offers the next one.
     */
    @Test
    fun myListSeriesContinueWatchedMarksAndNextEpisode() {
        FakeProvider().use { server ->
            rule.runOnIdle { vm.addPlaylist("Fake provider", "xtream", "http://127.0.0.1:${server.port}", "u", "", "", "", liveOn = false, vodOn = true) }
            waitFor("the provider to be added (status=${vm.status})") { vm.playlists.any { it.name == "Fake provider" } }
            val pid = vm.playlists.first { it.name == "Fake provider" }.id
            val show = com.avidviewer.tv.data.VodItem("$pid:s78", pid, "series", "Test Show", null, "", seriesId = "78", category = "Shows")
            rule.runOnIdle { vm.catalog.replace(pid, sequenceOf(show)) { false } }
            // In My List, nothing watched yet: in Continue watching.
            assertTrue(vm.continueKeysForTests().isEmpty())
            rule.runOnIdle { vm.toggleMyList(show) }
            assertEquals(listOf(show.key), vm.continueKeysForTests())
            go(Screen.SERIES)
            rule.runOnIdle { vm.openVodDetail(show) }
            waitFor("episodes") { vm.detailEpisodes?.size == 3 }
            waitFor("the page opens on episode 1 (focus: ${focused()})") { focused().contains("Pilot") }
            // Menu on the episode: Mark as watched.
            press(listOf(Key.Menu))
            waitFor("the episode's options") { vm.episodeMenu != null }
            rule.onNodeWithText("Mark season 1 as watched", substring = true).assertExists()
            waitFor("the first option has the highlight (focus: ${focused()})") { focused().contains("Mark as watched") }
            press(listOf(Key.DirectionCenter))
            waitFor("S1 E1 watched") { vm.isWatched("$pid:s78:1:1") && vm.episodeMenu == null }
            // The whole of season 1, then not watched again.
            val s1 = vm.seasonEpisodes(vm.detailEpisodes!!.first().season)
            rule.runOnIdle { vm.setEpisodesWatched(show, s1, true) }
            assertTrue(vm.isWatched("$pid:s78:1:2"))
            rule.runOnIdle { vm.setEpisodesWatched(show, s1.take(1), false) }
            assertFalse(vm.isWatched("$pid:s78:1:1"))
            assertTrue(vm.isWatched("$pid:s78:1:2"))
            // Next episode: offered only once the episode counts as watched; S1 E2 leads to S2 E1.
            rule.runOnIdle { vm.playDetailEpisode(vm.detailEpisodes!!.first { it.number == 2 && it.season.trim() == "1" }) }
            waitFor("S1 E2 plays") { vm.vodPlaying?.name?.contains("S1 E2") == true }
            assertNull(vm.nextEpisodeDue(50_000, 100_000, false))
            assertEquals(2, vm.nextEpisodeDue(96_000, 100_000, false)?.season?.trim()?.toInt())
            assertEquals(1, vm.nextEpisodeDue(0, 0, true)?.number)
            rule.runOnIdle { vm.playNextEpisode() }
            waitFor("S2 E1 plays") { vm.vodPlaying?.name?.contains("S2 E1") == true }
            assertNull("no episode after the last", vm.nextEpisodeDue(0, 0, true))
            rule.runOnIdle { vm.closeFullScreen() }
            // Every episode watched: off Continue watching even though it's in My List.
            rule.runOnIdle { vm.setEpisodesWatched(show, vm.detailEpisodes!!, true) }
            assertTrue(vm.continueKeysForTests().isEmpty())
        }
    }

    /**
     * Nov 22, 2026 (owner): series in Continue watching sort by their newest episode's release date, and leave once
     * no episode is left to watch.
     */
    @Test
    fun continueWatchingSeriesSortByNewestEpisodeAndLeaveWhenAllWatched() {
        FakeProvider().use { server ->
            rule.runOnIdle { vm.addPlaylist("Fake provider", "xtream", "http://127.0.0.1:${server.port}", "u", "", "", "", liveOn = false, vodOn = true) }
            waitFor("the provider to be added (status=${vm.status})") { vm.playlists.any { it.name == "Fake provider" } }
            val pid = vm.playlists.first { it.name == "Fake provider" }.id
            val older = com.avidviewer.tv.data.VodItem("$pid:s78", pid, "series", "Older Show", null, "", seriesId = "78", category = "Shows")
            val newer = com.avidviewer.tv.data.VodItem("$pid:s79", pid, "series", "Newer Show", null, "", seriesId = "79", category = "Shows")
            rule.runOnIdle { vm.catalog.replace(pid, sequenceOf(older, newer)) { false } }
            rule.runOnIdle { vm.toggleMyList(older); vm.toggleMyList(newer) }
            go(Screen.SERIES)
            // Watching the older show makes it the most recent activity, but the newer show's newest episode came out later.
            rule.runOnIdle { vm.openVodDetail(older) }
            waitFor("episodes") { vm.detailEpisodes?.size == 3 }
            rule.runOnIdle { vm.setEpisodesWatched(older, vm.detailEpisodes!!.take(1), true) }
            rule.runOnIdle { vm.openVodDetail(newer) }
            waitFor("the newer show's episodes") { vm.vodDetail?.key == newer.key && vm.detailEpisodes?.size == 3 }
            assertEquals(listOf(newer.key, older.key), vm.continueKeysForTests())
            // Every episode of the older show watched: gone, even though it's in My List.
            rule.runOnIdle { vm.openVodDetail(older) }
            waitFor("the older show's episodes") { vm.vodDetail?.key == older.key && vm.detailEpisodes?.size == 3 }
            rule.runOnIdle { vm.setEpisodesWatched(older, vm.detailEpisodes!!, true) }
            assertEquals(listOf(newer.key), vm.continueKeysForTests())
        }
    }

    /**
     * Nov 22, 2026 (owner): Back from full screen highlights the episode that was playing, even far down its season
     * (it fell back to My List); a page opened again starts where the profile left off.
     */
    @Test
    fun backFromAnEpisodeFarDownHighlightsItAndReopeningStartsWhereLeftOff() {
        FakeProvider().use { server ->
            rule.runOnIdle { vm.addPlaylist("Fake provider", "xtream", "http://127.0.0.1:${server.port}", "u", "", "", "", liveOn = false, vodOn = true) }
            waitFor("the provider to be added (status=${vm.status})") { vm.playlists.any { it.name == "Fake provider" } }
            val pid = vm.playlists.first { it.name == "Fake provider" }.id
            val show = com.avidviewer.tv.data.VodItem("$pid:s99", pid, "series", "Long Show", null, "", seriesId = "99", category = "Shows")
            rule.runOnIdle { vm.catalog.replace(pid, sequenceOf(show)) { false } }
            go(Screen.SERIES)
            rule.runOnIdle { vm.openVodDetail(show) }
            waitFor("30 episodes") { vm.detailEpisodes?.size == 30 }
            rule.runOnIdle { vm.playDetailEpisode(vm.detailEpisodes!!.first { it.number == 25 }) }
            waitFor("episode 25 plays") { vm.vodPlaying?.name?.contains("E25") == true }
            rule.runOnIdle { vm.closeFullScreen() }
            waitFor("episode 25 has the highlight after Back (focus: ${focused()})") { focused().contains("Long Ep 25") }
            // Finished episode 25, then leave and open the page again: it starts on episode 26.
            rule.runOnIdle { vm.setEpisodesWatched(show, vm.detailEpisodes!!.filter { it.number == 25 }, true); vm.closeVodDetail() }
            rule.runOnIdle { vm.openVodDetail(show) }
            waitFor("the page opens on episode 26 (focus: ${focused()})") { focused().contains("Long Ep 26") }
            // Left at the page's left edge goes back to the posters, not to the main menu.
            press(listOf(Key.DirectionLeft))
            waitFor("Left closes the title page (focus: ${focused()})") { vm.vodDetail == null }
            assertEquals(Screen.SERIES, vm.screen)
            assertNotEquals(NavMode.FULL, vm.navMode)
        }
    }

    /**
     * Nov 24, 2026 (#42): with lists read in the background, opening Movies, switching categories and sorts, and
     * scrolling a long list never count or read titles on the screen's thread (a Fire TV Cube closed the app when the
     * count took too long there).
     */
    @Test
    fun movieListsNeverReadTheCatalogOnTheScreenThread() {
        val pid = TestData.P1
        go(Screen.MOVIES)
        waitFor("movies to load") { vm.vodOf("movie").isNotEmpty() }
        rule.runOnIdle {
            vm.catalog.replace(pid, (1..900).asSequence().map { n ->
                com.avidviewer.tv.data.VodItem("$pid:m$n", pid, "movie", "Movie %03d".format(n), null, "http://x/$n.mp4", category = if (n % 2 == 0) "EN | Action" else "EN | Drama", added = n.toLong())
            }) { false }
            vm.regroupLater(0)
        }
        val wasAsync = com.avidviewer.tv.data.PagedTitles.asyncPages
        try {
            rule.runOnIdle { vm.asyncLists = true; com.avidviewer.tv.data.PagedTitles.asyncPages = true; vm.catalog.mainThreadReads.set(0); vm.catalog.mainThreadReadsFrom.clear() }
            go(Screen.GUIDE)
            go(Screen.MOVIES)
            waitFor("the list to arrive") { vm.vodItems("movie").size >= 900 }
            rule.runOnIdle { vm.setVodSort("az") }
            waitFor("A–Z to arrive") { vm.vodItems("movie").let { it.size >= 900 && it[0].name == "Movie 001" } }
            // Scroll far down the grid with the remote: pages beyond the first are read in the background.
            press(listOf(Key.DirectionRight))
            waitFor("a poster has the highlight (focus: ${focused()})") { focused().contains("Movie") }
            repeat(4) { press(List(10) { Key.DirectionDown }) }
            waitFor("posters far down drawn (focus: ${focused()})") { Regex("Movie (\\d{3})").find(focused())?.groupValues?.get(1)?.toInt()?.let { it > 200 } == true }
            rule.runOnIdle { vm.chooseVodCategory("movie", "EN | Drama") }
            waitFor("the category to arrive") { vm.vodItems("movie").size == 450 }
            assertEquals("catalog reads on the screen's thread: ${vm.catalog.mainThreadReadsFrom.joinToString(" || ")}", 0, vm.catalog.mainThreadReads.get())
        } finally {
            rule.runOnIdle { vm.asyncLists = false; com.avidviewer.tv.data.PagedTitles.asyncPages = wasAsync }
        }
    }

    /** #39: without a Menu button (Google TV) a tip says to hold OK; a Menu press shows the remote has one, and it goes. */
    @Test
    fun holdOkTipShowsUntilTheRemoteShowsAMenuButton() {
        rule.runOnIdle { com.avidviewer.tv.ui.MenuKey.reset(hasMenu = false) }
        try {
            go(Screen.GUIDE)
            rule.runOnIdle { vm.chooseGroup(TestData.P1, "NEWS") }
            settle()
            waitFor("the tip") { rule.onAllNodesWithTag("hold-ok-tip").fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodes(androidx.compose.ui.test.hasText("NEWS Channel 1 with", substring = true) and androidx.compose.ui.test.hasClickAction()).onFirst().requestFocus()
            press(listOf(Key.Menu))
            waitFor("the tip to go") { rule.onAllNodesWithTag("hold-ok-tip").fetchSemanticsNodes().isEmpty() }
            assertTrue(com.avidviewer.tv.ui.MenuKey.hasMenu)
            press(listOf(Key.Back))
        } finally {
            rule.runOnIdle { com.avidviewer.tv.ui.MenuKey.reset(hasMenu = true) }
        }
    }

    /** #43: a title page says the title's language. */
    @Test
    fun titlePageShowsTheLanguage() {
        go(Screen.MOVIES)
        waitFor("movies to load") { vm.vodItems("movie").isNotEmpty() }
        rule.runOnIdle { vm.openVodDetail(vm.vodItems("movie")[0]) }
        waitFor("the Language fact") { rule.onAllNodesWithText("Language").fetchSemanticsNodes().isNotEmpty() }
    }

    /** Nov 25: Settings › Discover says how to get a TMDB key, with the answers for TMDB's form and a QR code. */
    @Test
    fun discoverSettingsSayHowToGetATmdbKey() {
        rule.runOnIdle { vm.settingsSection = "discover"; vm.navSelect(Screen.SETTINGS); vm.settingsSection = "discover" }
        settle()
        waitFor("the how-to") { rule.onAllNodesWithText("How to get a free key").fetchSemanticsNodes().isNotEmpty() }
        for (t in listOf("Application Name", "Avid Viewer", "https://avidviewer.app", "Application Summary"))
            assertTrue(t, rule.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty())
    }

    /** Nov 12: from My List, a series with several copies (versions line) left no room for its episodes. */
    @Test
    fun aSeriesFromMyListWithSeveralCopiesStillReachesItsEpisodes() {
        val longPlot = List(25) { "A long description of the show that goes on and on, sentence $it." }.joinToString(" ")
        FakeProvider(longPlot).use { server ->
            rule.runOnIdle { vm.addPlaylist("Fake provider", "xtream", "http://127.0.0.1:${server.port}", "u", "", "", "", liveOn = false, vodOn = true) }
            waitFor("the provider to be added (status=${vm.status})") { vm.playlists.any { it.name == "Fake provider" } }
            val pid = vm.playlists.first { it.name == "Fake provider" }.id
            fun s(k: String, n: String, cat: String) = com.avidviewer.tv.data.VodItem("$pid:$k", pid, "series", n, null, "", seriesId = "80", category = cat)
            val en = s("s80en", "EN | Copy Show", "EN | Drama")
            rule.runOnIdle {
                vm.catalog.replace(pid, sequenceOf(en, s("s80fr", "FR | Copy Show", "FR | Séries"), s("s80x", "Copy Show 4K", "Crime"))) { false }
                vm.toggleMyList(en)
            }
            go(Screen.SERIES)
            rule.runOnIdle { vm.openVodDetail(vm.prefs.myList.first { it.key == en.key }) }
            waitFor("episodes and versions") { vm.detailEpisodes?.isNotEmpty() == true && vm.versionsOfItem(en).size == 3 }
            rule.onNodeWithText("This version: English", substring = true).assertExists()
            waitFor("the page opens on episode 1 (focus: ${focused()})") { focused().contains("Pilot") }
            var tries = 0
            while (!focused().contains("My List") && tries < 12) { press(listOf(Key.DirectionUp)); tries++ }
            assertTrue("couldn't go Up to the buttons (focus: ${focused()})", focused().contains("My List"))
            tries = 0
            while (!focused().contains("Pilot") && tries < 12) { press(listOf(Key.DirectionDown)); tries++ }
            assertTrue("couldn't reach episode 1 from My List (focus: ${focused()})", focused().contains("Pilot"))
            press(listOf(Key.DirectionCenter))
            waitFor("episode 1 plays") { vm.screen == Screen.PLAYER && vm.vodPlaying?.name?.contains("S1 E1") == true }
        }
    }

    @Test
    fun seriesEpisodeCanBeReachedWithTheRemoteAndPlays() {
        FakeProvider().use { server ->
            rule.runOnIdle { vm.addPlaylist("Fake provider", "xtream", "http://127.0.0.1:${server.port}", "u", "", "", "", liveOn = false, vodOn = true) }
            waitFor("the provider to be added (status=${vm.status})") { vm.playlists.any { it.name == "Fake provider" } }
            val pid = vm.playlists.first { it.name == "Fake provider" }.id
            go(Screen.SERIES)
            rule.runOnIdle { vm.openVodDetail(com.avidviewer.tv.data.VodItem("$pid:s77", pid, "series", "Test Show", null, "", seriesId = "77", category = "Shows")) }
            waitFor("episodes to load") { vm.detailEpisodes?.isNotEmpty() == true }
            // As a viewer: from the page's first button, press Down until an episode is highlighted.
            var tries = 0
            while (!focused().contains("Pilot") && tries < 10) { press(listOf(Key.DirectionDown)); tries++ }
            assertTrue("couldn't reach an episode with Down (focus: ${focused()})", focused().contains("Pilot"))
            press(listOf(Key.DirectionCenter))
            waitFor("the episode to play (screen=${vm.screen}, focus=${focused()})") { vm.screen == Screen.PLAYER }
            assertTrue(vm.vodPlaying!!.name, vm.vodPlaying!!.name.contains("S1 E1"))
            // Back to the series page, then season 2.
            press(listOf(Key.Back))
            waitFor("back on the series page") { vm.screen == Screen.SERIES && vm.vodDetail != null }
            // The highlight comes back on the episode that was playing.
            waitFor("back on the episode that played (focus: ${focused()})") { focused().contains("Pilot") }
            // With the remote: Up to the season buttons, Right to Season 2, OK.
            tries = 0
            while (!focused().contains("Season 1") && tries < 6) { press(listOf(Key.DirectionUp)); tries++ }
            assertTrue("couldn't reach the season buttons (focus: ${focused()}, trail=${vm.focusTrail.toList().takeLast(12)})", focused().contains("Season 1"))
            press(listOf(Key.DirectionRight))
            assertTrue("Right didn't reach Season 2 (focus: ${focused()})", focused().contains("Season 2"))
            press(listOf(Key.DirectionCenter))
            tries = 0
            while (!focused().contains("Season Two Opener") && tries < 10) { press(listOf(Key.DirectionDown)); tries++ }
            assertTrue("couldn't reach a season 2 episode (focus: ${focused()})", focused().contains("Season Two Opener"))
        }
    }

    /** #8: the series page opens on the next episode to watch, in its season. */
    @Test
    fun aSeriesPageOpensOnTheNextUnwatchedEpisode() {
        FakeProvider().use { server ->
            rule.runOnIdle { vm.addPlaylist("Fake provider", "xtream", "http://127.0.0.1:${server.port}", "u", "", "", "", liveOn = false, vodOn = true) }
            waitFor("the provider to be added (status=${vm.status})") { vm.playlists.any { it.name == "Fake provider" } }
            val pid = vm.playlists.first { it.name == "Fake provider" }.id
            val show = com.avidviewer.tv.data.VodItem("$pid:s77", pid, "series", "Test Show", null, "", seriesId = "77", category = "Shows")
            go(Screen.SERIES)
            fun open(what: String, want: String) {
                rule.runOnIdle { vm.closeVodDetail(false); vm.openVodDetail(show) }
                waitFor("episodes ($what)") { vm.detailEpisodes?.isNotEmpty() == true }
                waitFor("$what: the page opens on “$want” (focus: ${focused()}, season ${vm.detailSeason})") { focused().contains(want) }
            }
            open("nothing watched", "Pilot")
            val eps = vm.detailEpisodes!!
            rule.runOnIdle { vm.markWatched(vm.episodeKey(show, eps[0])) }
            open("episode 1 watched", "Second")
            rule.runOnIdle { vm.markWatched(vm.episodeKey(show, eps[1])) }
            open("season 1 watched", "Season Two Opener")
            assertEquals("2", vm.detailSeason)
        }
    }

    @Test
    fun backFromFullScreenReturnsToTheChannelFarDownTheList() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "BIG") }
        waitFor("the long category") { focused().contains("BIG Channel 1 with") }
        hold(Key.DirectionDown, 25)
        var tries = 0
        while (!focused().contains("BIG Channel 60 with") && tries < 70) {
            press(listOf(if (Regex("BIG Channel (\\d+) ").find(focused())?.groupValues?.get(1)?.toInt()?.let { it > 60 } == true) Key.DirectionUp else Key.DirectionDown)); tries++
        }
        assertTrue("couldn't get to channel 60 (focus: ${focused()})", focused().contains("BIG Channel 60 with"))
        press(listOf(Key.DirectionCenter)) // preview
        press(listOf(Key.DirectionCenter)) // full screen
        waitFor("full screen") { vm.screen == Screen.PLAYER }
        press(listOf(Key.Back))
        waitFor("back in the guide on channel 60 (focus: ${focused()})") { vm.screen == Screen.GUIDE && focused().contains("BIG Channel 60 with") }
    }

    /**
     * Nov 12: searching "Farg" found nothing; part of a word, in any order, now finds it.
     * Nov 26: typing alone searches nothing; Search does.
     */
    @Test
    fun searchFindsPartOfAWordWhenSearchIsPressed() {
        go(Screen.SEARCH)
        rule.runOnIdle { vm.typeSearch("farg") } // typed, Search not pressed
        Thread.sleep(800); rule.waitForIdle()
        assertEquals("typing alone searches nothing", "", vm.searchedFor)
        assertEquals(null, vm.searchResults)
        rule.runOnIdle { vm.search(vm.searchQuery) }
        waitFor("results for farg (searched=${vm.searchedFor})") { vm.searchedFor == "farg" && vm.searchResults?.any { it.stations.isNotEmpty() } == true }
        assertTrue(vm.searchResults!!.flatMap { it.stations }.all { it.title.contains("Fargo") })
        rule.runOnIdle { vm.search("fargo wday 3") }
        waitFor("words in any order") { vm.searchedFor == "fargo wday 3" && vm.searchResults?.flatMap { it.stations }?.any { it.title.contains("WDAY ABC 3 Fargo") } == true }
        rule.runOnIdle { vm.search("movie drama 1") }
        waitFor("movies too") { vm.searchedFor == "movie drama 1" && vm.searchResults?.flatMap { it.movies }?.any { it.title == "Drama Movie 1" } == true }
        rule.runOnIdle { vm.search("zzzz") }
        waitFor("nothing found names what was searched") { vm.searchedFor == "zzzz" }
        rule.onNodeWithText("Nothing found for “zzzz”", substring = true).assertExists()
        // The TV Guide's own search, too.
        rule.runOnIdle { vm.runGuideSearch("abc farg") }
        waitFor("guide search finds the channels") { vm.guideSearchHits.channels.any { vm.channelName(it).contains("Fargo") } }
    }

    /**
     * #16 (Nov 14): a two-word search crashed a 1 GB Fire TV: the first word alone matched much of the
     * guide, every match was collected, and each pause in typing started another full search while the
     * last kept running. Now a newer search stops the older one and only the best matches are kept.
     * Same day: searches were too strict, so a typo or a word left out now finds near matches, ranked last.
     */
    @Test
    fun twoWordSearchStaysBoundedAndNearMatchesAreFound() {
        go(Screen.SEARCH)
        for (t in listOf("pr", "pro", "program", "program e", "program episode")) rule.runOnIdle { vm.search(t) }
        waitFor("results for the two words (searched=${vm.searchedFor})") { vm.searchedFor == "program episode" && !vm.searching && vm.searchResults != null }
        val res = vm.searchResults!!
        assertTrue("found programmes", res.sumOf { it.programs.size } > 0)
        assertTrue("at most 60 programmes a playlist", res.all { it.programs.size <= 60 })
        // A typo in a channel name, and in a movie title.
        rule.runOnIdle { vm.search("wdya fargo") }
        waitFor("near match for a channel") { vm.searchedFor == "wdya fargo" && !vm.searching && vm.searchResults?.flatMap { it.stations }?.any { it.title.contains("WDAY ABC") } == true }
        rule.runOnIdle { vm.search("drma movie 1") }
        waitFor("near match for a movie") { vm.searchedFor == "drma movie 1" && !vm.searching && vm.searchResults?.flatMap { it.movies }?.any { it.title == "Drama Movie 1" } == true }
        // Exact matches before near ones.
        rule.runOnIdle { vm.search("action movie 1") }
        waitFor("exact movie search") { vm.searchedFor == "action movie 1" && !vm.searching && vm.searchResults != null }
        val movies = vm.searchResults!!.flatMap { it.movies }.map { it.title }
        assertTrue(movies.toString(), movies.isNotEmpty() && movies.first().startsWith("Action Movie 1"))
        // The Movies search box finds near matches too.
        // (Titles are regrouped in the background after start-up, which renews the catalog version: ask again until settled.)
        waitFor("near match in the Movies search box") {
            kotlinx.coroutines.runBlocking { vm.loadVodNear("movie", "drma movie 1") }
            rule.runOnIdle { vm.vodSearch("movie", "drma movie 1").any { it.name == "Drama Movie 1" } }
        }
    }

    /** Nov 12: on a Discover title page, the remote couldn't reach the versions below the description. */
    @Test
    fun discoverTitlePageReachesEveryVersionWithTheRemote() {
        val p1 = TestData.P1
        go(Screen.MOVIES)
        waitFor("movies") { vm.vodOf("movie").isNotEmpty() }
        rule.runOnIdle {
            val c = vm.catalog
            val have = c.page(com.avidviewer.tv.data.Catalog.Query("movie", listOf(p1), includeHidden = true), 0, 100_000) +
                c.page(com.avidviewer.tv.data.Catalog.Query("series", listOf(p1), includeHidden = true), 0, 100_000)
            fun m(k: String, n: String, cat: String) = com.avidviewer.tv.data.VodItem("$p1:$k", p1, "movie", n, null, "http://127.0.0.1:9/$k.mp4", category = cat)
            c.replace(p1, (have + m("dfr", "FR | Mob Test", "FR | Films") + m("den", "EN | Mob Test", "EN | Action") + m("dx", "Mob Test 4K", "Crime")).asSequence()) { false }
            vm.setVodTitlesHidden(listOf("$p1:none"), true); vm.setVodTitlesHidden(listOf("$p1:none"), false)
        }
        go(Screen.DISCOVER)
        val long = List(30) { "A long description of the film that keeps going, sentence $it." }.joinToString(" ")
        rule.runOnIdle { vm.discoverDetail = com.avidviewer.tv.data.Title(1, "movie", "Mob Test", "2025", long, null, null, 7.5, emptyList(), "") }
        waitFor("the title page, on Play (focus: ${focused()})") { focused().contains("Play") && vm.versionsOf(vm.discoverDetail!!).size == 3 }
        val seen = ArrayList<String>()
        var tries = 0
        while (!focused().contains("Where to watch") && tries < 12) { press(listOf(Key.DirectionDown)); seen.add(focused().take(40)); tries++ }
        assertTrue("Down didn't reach every version and Where to watch: $seen", seen.any { it.contains("EN | Mob Test") } && seen.any { it.contains("FR | Mob Test") } && focused().contains("Where to watch"))
        tries = 0
        while (!focused().contains("FR | Mob Test") && tries < 6) { press(listOf(Key.DirectionUp)); tries++ }
        press(listOf(Key.DirectionCenter))
        waitFor("the French copy plays") { vm.screen == Screen.PLAYER && vm.vodPlaying?.key == "$p1:dfr" }
    }

    /** Issue #10: Menu on a Discover title adds it to My List (and takes it off), like Movies and Series. */
    @Test
    fun menuOnADiscoverTitleTogglesMyList() {
        val p1 = TestData.P1
        go(Screen.MOVIES)
        waitFor("movies") { vm.vodOf("movie").isNotEmpty() }
        rule.runOnIdle {
            val c = vm.catalog
            val have = c.page(com.avidviewer.tv.data.Catalog.Query("movie", listOf(p1), includeHidden = true), 0, 100_000) +
                c.page(com.avidviewer.tv.data.Catalog.Query("series", listOf(p1), includeHidden = true), 0, 100_000)
            c.replace(p1, (have + com.avidviewer.tv.data.VodItem("$p1:menu1", p1, "movie", "Menu Test Film", null, "http://127.0.0.1:9/m.mp4", category = "Action")).asSequence()) { false }
            vm.setVodTitlesHidden(listOf("$p1:none"), true); vm.setVodTitlesHidden(listOf("$p1:none"), false)
        }
        go(Screen.DISCOVER)
        val onProvider = com.avidviewer.tv.data.Title(11, "movie", "Menu Test Film", "2025", "", null, null, 7.0, emptyList(), "")
        val elsewhere = com.avidviewer.tv.data.Title(12, "movie", "Not In Any Playlist Film", "2025", "", null, null, 7.0, emptyList(), "")
        waitFor("the title is on the provider") { vm.matchVod(onProvider) != null }
        rule.runOnIdle { vm.discoverMenu(onProvider) }
        waitFor("added to My List") { vm.inMyList("$p1:menu1") }
        assertTrue(vm.discoverHint.orEmpty().startsWith("Added"))
        rule.runOnIdle { vm.discoverMenu(onProvider) }
        waitFor("taken off My List") { !vm.inMyList("$p1:menu1") }
        assertTrue(vm.discoverHint.orEmpty().startsWith("Removed"))
        val before = vm.prefs.myList.size
        rule.runOnIdle { vm.discoverMenu(elsewhere) }
        assertEquals(before, vm.prefs.myList.size)
        assertTrue(vm.discoverHint.orEmpty().contains("isn't on your provider"))
    }

    /** Nov 12: Series / Movies list a title once; its page offers the other copies (languages, playlists). */
    @Test
    fun aTitleIsListedOnceAndItsPageOffersItsVersions() {
        go(Screen.SERIES)
        waitFor("series") { vm.vodOf("series").isNotEmpty() }
        val p1 = TestData.P1
        rule.runOnIdle {
            val c = vm.catalog
            val have = c.page(com.avidviewer.tv.data.Catalog.Query("movie", listOf(p1), includeHidden = true), 0, 100_000) +
                c.page(com.avidviewer.tv.data.Catalog.Query("series", listOf(p1), includeHidden = true), 0, 100_000)
            fun s(k: String, n: String, cat: String) = com.avidviewer.tv.data.VodItem("$p1:$k", p1, "series", n, null, "", seriesId = k, category = cat)
            c.replace(p1, (have + s("dupfr", "FR | Mob Test", "FR | Séries") + s("dupen", "EN | Mob Test", "EN | Drama") + s("dupx", "Mob Test 4K", "Crime")).asSequence()) { false }
            vm.setVodTitlesHidden(listOf("$p1:none"), true); vm.setVodTitlesHidden(listOf("$p1:none"), false) // the lists read again
        }
        fun mob() = vm.vodOf("series").filter { com.avidviewer.tv.data.Tmdb.normalize(it.name) == "mobtest" }
        waitFor("one Mob Test in the list") { mob().size == 1 }
        assertEquals("the English copy stands for the title", "$p1:dupen", mob().single().key)
        rule.runOnIdle { vm.openVodDetail(mob().single()) }
        waitFor("the versions button") { rule.onAllNodesWithTag("versions-button").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("3 versions", substring = true).assertExists()
        rule.onNodeWithText("This version: English", substring = true).assertExists()
        rule.onNodeWithTag("versions-button").requestFocus(); settle()
        press(listOf(Key.DirectionCenter))
        waitFor("the versions list, on the copy shown (focus: ${focused()})") { vm.versionPicker != null && focused().contains("EN | Mob Test") }
        press(listOf(Key.DirectionDown))
        assertTrue("next copy (focus: ${focused()})", focused().contains("Mob Test 4K") || focused().contains("FR | Mob Test"))
        var tries = 0
        while (!focused().contains("FR | Mob Test") && tries < 4) { press(listOf(Key.DirectionDown)); tries++ }
        press(listOf(Key.DirectionCenter))
        waitFor("the page shows the French copy") { vm.vodDetail?.key == "$p1:dupfr" && vm.versionPicker == null }
    }

    /** Nov 12: Menu in full screen opens Picture & sound (screen format, audio, captions, Add to List). */
    @Test
    fun menuInFullScreenOpensPictureAndSound() {
        val key = "${TestData.P1}:c1-3"
        rule.runOnIdle { vm.openFullScreen(key, PlayContext.GROUP) }
        waitFor("full screen") { vm.screen == Screen.PLAYER && vm.currentKey == key }
        press(listOf(Key.Menu))
        waitFor("the panel opens on Screen format (focus: ${focused()})") { vm.avPanel && focused().contains("Screen format") }
        rule.onNodeWithText("Picture & sound").assertIsDisplayed()
        // Right changes the format, saved for this channel only.
        press(listOf(Key.DirectionRight))
        assertEquals("fill", vm.prefs.avChannels[key]?.format)
        assertEquals(null, vm.prefs.avDefault.format)
        assertEquals(com.avidviewer.tv.data.ScreenFormat.FILL, vm.screenFormat())
        press(listOf(Key.DirectionLeft)); press(listOf(Key.DirectionLeft))
        assertEquals("4:3", vm.prefs.avChannels[key]?.format)
        // Up/Down move through the rows and never change the channel underneath.
        press(listOf(Key.DirectionDown)); press(listOf(Key.DirectionDown))
        assertTrue("Down reaches Closed captions (focus: ${focused()})", focused().contains("Closed captions"))
        assertEquals(key, vm.currentKey)
        press(listOf(Key.DirectionRight)) // captions on (before any track is found)
        assertEquals(true, vm.prefs.avChannels[key]?.captions)
        press(listOf(Key.DirectionDown))
        assertTrue("caption size row (focus: ${focused()})", focused().contains("Text size"))
        press(listOf(Key.DirectionRight))
        assertEquals("large", vm.prefs.captionStyle.size)
        // "Save changes for" → all channels: the next change goes to the default.
        var tries = 0
        while (!focused().contains("Save changes for") && tries < 8) { press(listOf(Key.DirectionDown)); tries++ }
        press(listOf(Key.DirectionRight))
        assertTrue(vm.prefs.avForAll)
        // Back closes the panel, not full screen; the channel's own format stays.
        press(listOf(Key.Back))
        waitFor("panel closed, still full screen (panel=${vm.avPanel} screen=${vm.screen} focus=${focused()})") { !vm.avPanel && vm.screen == Screen.PLAYER }
        assertEquals(com.avidviewer.tv.data.ScreenFormat.STANDARD, vm.screenFormat())
        // Up/Down change channel again once it's closed.
        press(listOf(Key.DirectionDown))
        waitFor("Down changes channel after the panel closes") { vm.currentKey != key }
        // Another channel uses the default; Reset brings a channel back to it.
        assertEquals(com.avidviewer.tv.data.ScreenFormat.AUTO, vm.screenFormat())
        rule.runOnIdle { vm.setAvForAll(false); vm.openFullScreen(key, PlayContext.GROUP) }
        press(listOf(Key.Menu))
        waitFor("panel again") { vm.avPanel && focused().contains("Screen format") }
        tries = 0
        while (!focused().contains("Use the settings for all channels") && tries < 12) { press(listOf(Key.DirectionDown)); tries++ }
        press(listOf(Key.DirectionCenter))
        assertEquals(null, vm.prefs.avChannels[key])
        // Add to List is still in the panel.
        tries = 0
        while (!focused().contains("Add to List") && tries < 12) { press(listOf(Key.DirectionDown)); tries++ }
        press(listOf(Key.DirectionCenter))
        waitFor("the list picker opens") { vm.listPickerKey == key && !vm.avPanel }
    }

    /** Presses sent back to back, faster than the screen redraws (a quick thumb on the remote). */
    private fun burst(k: Key, n: Int) { repeat(n) { key(k) }; settle() }

    /** Nov 12: tapping Up/Down fast in the TV Guide froze and closed the app after a wrap-around. */
    @Test
    fun fastTapsInTheChannelListKeepUpAndNeverFreeze() {
        fun row(): Int = Regex("BIG Channel (\\d+) with").find(focused())?.groupValues?.get(1)?.toInt() ?: -1
        fun near(what: String, want: Int) = waitFor("$what: about channel $want (focus: ${focused()})") { row() in (want - 2)..want }
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "BIG") }
        waitFor("BIG channel 1 highlighted") { row() == 1 }
        // Fast presses count from where the highlight is heading (a press arriving mid-scroll may drop).
        burst(Key.DirectionDown, 10); near("10 fast presses down", 11)
        var at = row()
        burst(Key.DirectionDown, 25); near("25 more", at + 25)
        at = row()
        burst(Key.DirectionUp, 20); near("20 fast presses up", at - 20)
        // Up to the top, past the preview, wraps to the bottom; fast presses carry on from there.
        var tries = 0
        while (row() != 80 && tries < 60) { press(listOf(Key.DirectionUp)); tries++ }
        assertEquals("Up past the top didn't wrap to the last channel (focus: ${focused()})", 80, row())
        burst(Key.DirectionUp, 4); near("4 up from the bottom", 76)
        tries = 0
        while (row() != 80 && tries < 10) { press(listOf(Key.DirectionDown)); tries++ }
        press(listOf(Key.DirectionDown))
        waitFor("Down past the bottom wraps to channel 1 (focus: ${focused()})") { row() == 1 }
        // Mixed fast taps, across both wraps, never lose the highlight.
        val rnd = Random(1112)
        repeat(30) { burst(if (rnd.nextBoolean()) Key.DirectionDown else Key.DirectionUp, 1 + rnd.nextInt(8)) }
        settle()
        assertTrue("highlight lost (focus: ${focused()})", focused() != "(nothing focused)")
    }

    @Test
    fun channelAndCategoryListsWrapAround() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }
        waitFor("KIDS channel 1 highlighted") { focused().contains("KIDS Channel 1 with") }
        // Down on the last channel goes to the first; Up from the top (past the header) to the last.
        // (Up from the first channel goes to the day strip, then the preview, then wraps.)
        var ups = 0
        while (!focused().contains("KIDS Channel 12 ") && ups < 3) { press(listOf(Key.DirectionUp)); ups++ }
        waitFor("Up wraps to the last channel (focus: ${focused()})") { focused().contains("KIDS Channel 12 ") }
        press(listOf(Key.DirectionDown))
        waitFor("Down wraps to the first channel (focus: ${focused()})") { focused().contains("KIDS Channel 1 with") }
        // Holding doesn't wrap: it stops at the end.
        hold(Key.DirectionUp, 30)
        assertTrue("holding wrapped (focus: ${focused()})", !focused().contains("KIDS Channel 12 "))
        // Categories: Down on the last one goes to the top of the list.
        showCategories()
        rule.onNode(androidx.compose.ui.test.hasText("BIG") and androidx.compose.ui.test.hasClickAction()).requestFocus()
        settle()
        val last = vm.groups().last().name
        rule.onNode(androidx.compose.ui.test.hasText(last) and androidx.compose.ui.test.hasClickAction()).requestFocus()
        settle()
        press(listOf(Key.DirectionDown))
        waitFor("Down on the last category wraps to the top (focus: ${focused()})") { focused().contains("Menu") || focused().contains("Favorites") }
        // Movies categories
        go(Screen.MOVIES)
        waitFor("movies") { vm.vodOf("movie").isNotEmpty() }
        waitFor("All highlighted") { focused().contains("All") }
        // Nov 26, 2026 (owner): Search, My List, Recently added, All, then the categories.
        press(listOf(Key.DirectionUp)); waitFor("Recently added above All (focus: ${focused()})") { focused().contains("Recently added") }
        press(listOf(Key.DirectionUp)); waitFor("My List above it (focus: ${focused()})") { focused().contains("My List") }
        press(listOf(Key.DirectionUp)); waitFor("Search at the top (focus: ${focused()})") { focused().startsWith("Search") }
        press(listOf(Key.DirectionUp))
        val lastCat = vm.vodCatName("movie", vm.vodCategories("movie").last().first)
        waitFor("Up from Search wraps to the last movie category (focus: ${focused()})") { focused().contains(lastCat) }
        press(listOf(Key.DirectionDown))
        waitFor("Down from the last category wraps to Search (focus: ${focused()})") { focused().startsWith("Search") }
    }

    /** Press-and-hold through every screen (the reported lock-ups and crashes). */
    @Test
    fun holdingButtonsEverywhereNeverLocksOrCrashes() {
        val screens = listOf(Screen.GUIDE, Screen.MOVIES, Screen.SERIES, Screen.SETTINGS, Screen.SPORTS, Screen.SEARCH, Screen.DISCOVER)
        val rnd = Random(1110)
        val dirs = listOf(Key.DirectionDown, Key.DirectionUp, Key.DirectionRight, Key.DirectionLeft)
        for (round in 0 until 3) for (s in screens) try {
            go(s)
            if (s == Screen.GUIDE) rule.runOnIdle { vm.chooseGroup(TestData.P1, "BIG") }
            settle()
            repeat(12) {
                hold(dirs[rnd.nextInt(dirs.size)], 5 + rnd.nextInt(40))
                press(listOf(dirs[rnd.nextInt(dirs.size)]))
            }
            // After holding, the remote must still move the highlight.
            val before = focused()
            press(listOf(Key.DirectionDown)); press(listOf(Key.DirectionUp)); press(listOf(Key.DirectionRight)); press(listOf(Key.DirectionLeft))
            assertTrue("highlight lost after holding on $s", focused() != "(nothing focused)" || before == "(nothing focused)")
        } catch (e: Throwable) {
            throw AssertionError("Holding failed on $s round $round (focus: ${focused()})", e)
        }
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
        val keys = listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Back, Key.Menu)
        val weights = listOf(5, 5, 4, 4, 2, 1, 1)
        val screens = listOf(Screen.GUIDE, Screen.MOVIES, Screen.SETTINGS, Screen.SPORTS, Screen.SERIES, Screen.SEARCH, Screen.DISCOVER)
        val rnd = Random(20261109)
        val recent = ArrayDeque<String>()
        for (i in 0 until 600) try {
            if (i % 60 == 0) go(screens[(i / 60) % screens.size])
            var r = rnd.nextInt(weights.sum()); var k = 0
            while (r >= weights[k]) { r -= weights[k]; k++ }
            key(keys[k])
            if (i % 10 == 0) settle()
            if (vm.screen == Screen.PLAYER && rnd.nextInt(4) == 0) rule.runOnIdle { vm.closeFullScreen() }
            recent.addLast(keys[k].toString()); if (recent.size > 12) recent.removeFirst()
        } catch (e: Throwable) {
            throw AssertionError(
                "Random press #$i failed on screen=${vm.screen} section=${vm.settingsSection} nav=${vm.navMode} " +
                    "group=${vm.selectedGroup} focus=${focused()} last keys=$recent", e,
            )
        }
        settle()
    }

    /**
     * 1.16.1: devices are tied to an account. An unlinked device with playlists keeps working and is asked to link
     * (Not now waits 7 days); Settings › Playlists › Add playlist shows the link code instead of the form.
     */
    @Test
    fun anUnlinkedDeviceIsAskedToLinkAndCantAddPlaylistsUntilItIs() {
        FakeAvidSite().use { site ->
            rule.runOnIdle { vm.accountApi = site.base; vm.remindToLink = true; vm.maybeRemindToLink(0) }
            waitFor("reminder shown") { rule.onAllNodesWithTag("link-reminder").fetchSemanticsNodes().isNotEmpty() }
            waitFor("its code") { rule.onAllNodesWithText("K7M2-QX9P").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("link-reminder-later").performClick(); settle()
            assertFalse(vm.linkReminder)
            assertTrue(vm.account.linkReminderAt > 0)
            rule.runOnIdle { vm.maybeRemindToLink(0) }; settle(300)
            assertFalse("not again within 7 days", vm.linkReminder)
            go(Screen.SETTINGS)
            rule.runOnIdle { vm.settingsSection = "playlists" }; settle()
            rule.onAllNodesWithText("Add playlist").onFirst().performClick(); settle()
            waitFor("link pane instead of the form") { rule.onAllNodesWithTag("link-to-add").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(rule.onAllNodesWithText("Link this device to add playlists").fetchSemanticsNodes().isNotEmpty())
            // Linked: the form is back.
            linkForTest(site)
            waitFor("the form once linked") { rule.onAllNodesWithTag("link-to-add").fetchSemanticsNodes().isEmpty() }
        }
    }

    /** BUG-033 (#18): adding a suggested channel to a list, or ✕, moves the highlight to the next suggestion. */
    @Test
    fun addingOrDismissingASuggestionHighlightsTheNextOne() {
        val keys = vm.channels.filter { it.key.startsWith(TestData.P1 + ":") }.take(4).map { it.key }
        rule.runOnIdle {
            vm.updatePrefs { it.copy(showSuggestions = true, favorites = emptyList()) }
            vm.setSuggestionsForTests(keys.map { com.avidviewer.tv.data.Suggestion(it, TestData.P1, "near you", 1) })
            vm.chooseGroup(TestData.P1, com.avidviewer.tv.ui.FAV_GROUP)
        }
        waitFor("the suggestions row") { rule.onAllNodesWithTag("sugg-add-${keys[0]}").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("sugg-add-${keys[0]}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus); settle()
        // ＋ Add to List › the first list in the picker.
        press(listOf(Key.DirectionCenter))
        waitFor("the list picker") { vm.listPickerKey == keys[0] }
        press(listOf(Key.DirectionCenter))
        waitFor("added and closed") { vm.listPickerKey == null && vm.inAnyList(keys[0]) }
        waitFor("the next suggestion has the highlight (focus: ${focused()})") { isFocusedTag("sugg-add-${keys[1]}") }
        settle(600)
        assertTrue("…and keeps it (focus: ${focused()})", isFocusedTag("sugg-add-${keys[1]}"))
        // ✕ on that one: the one after it.
        press(listOf(Key.DirectionRight))
        waitFor("on ✕ (focus: ${focused()})") { isFocusedTag("sugg-x-${keys[1]}") }
        press(listOf(Key.DirectionCenter))
        waitFor("the next suggestion after ✕ (focus: ${focused()})") { isFocusedTag("sugg-add-${keys[2]}") }
        settle(600)
        assertTrue("…and keeps it (focus: ${focused()})", isFocusedTag("sugg-add-${keys[2]}"))
    }

    /** BUG-045: OK on a category always moves the highlight into its channels, even if the suggestions flag was left set. */
    @Test
    fun okOnACategoryAlwaysOpensIt() {
        rule.runOnIdle {
            vm.updatePrefs { it.copy(showSuggestions = true) }
            vm.setSuggestionsForTests(vm.channels.filter { it.key.startsWith(TestData.P1 + ":") }.take(2).map { com.avidviewer.tv.data.Suggestion(it.key, TestData.P1, "near you", 1) })
            vm.suggestionsFocused = true // left set by a suggestions row that went away
            vm.chooseGroup(TestData.P1, "NEWS")
        }
        waitFor("a NEWS channel has the highlight (focus: ${focused()})") { vm.guideHasFocus && vm.channels.filter { it.group == "NEWS" }.any { focused().contains(vm.channelName(it)) } }
    }

    private fun isFocusedTag(tag: String) = runCatching {
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag(tag) and androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    /** 1.6.0: a new install (no playlists) opens on the welcome screen; from 1.16.1 it only offers linking to an account. */
    @Test
    fun welcomeScreenOnANewInstall() {
        FakeAvidSite().use { site ->
            rule.runOnIdle { vm.accountApi = site.base; vm.showWelcome = true }
            waitFor("welcome shown") { rule.onAllNodesWithTag("welcome").fetchSemanticsNodes().isNotEmpty() }
            // 1.12.1: the device shows its own link code and a QR code for avidviewer.app/link with the code in it.
            waitFor("link code shown") { rule.onAllNodesWithTag("welcome-link-code").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(rule.onAllNodesWithText("K7M2-QX9P").fetchSemanticsNodes().isNotEmpty())
            assertTrue(vm.linkCode!!.qrUrl.endsWith("/link/?c=K7M2QX9P"))
            assertTrue(rule.onAllNodesWithTag("welcome-link-qr").fetchSemanticsNodes().isNotEmpty())
            // 1.16.1: a device must be linked to an account before it gets playlists: no Add a playlist here.
            assertTrue(rule.onAllNodesWithText("Add a playlist", substring = true).fetchSemanticsNodes().isEmpty())
            assertTrue(rule.onAllNodesWithText("Enter a setup code").fetchSemanticsNodes().isEmpty())
            assertTrue(rule.onAllNodesWithText("To get started, link this device to your account").fetchSemanticsNodes().isNotEmpty())
            rule.runOnIdle { vm.showWelcome = false }
            waitFor("linking stopped when the welcome screen closed") { vm.linkCode == null }
        }
        // A TV with no playlists at all starts on it; one with playlists doesn't.
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(AppViewModel(app).showWelcome)
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        assertTrue(AppViewModel(app).showWelcome)
    }

    /** 1.11.0: Settings › Account › Link this TV shows a code + QR code, links once approved, and signs out. */
    @Test
    fun linkThisTvToAnAccountAndSignOut() {
        FakeAvidSite().use { site ->
            rule.runOnIdle {
                vm.accountApi = site.base
                vm.screen = Screen.SETTINGS; vm.settingsSection = "account"; vm.navMode = NavMode.COLLAPSED
            }
            waitFor("Link this TV") { rule.onAllNodesWithTag("account-link").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("account-link").performClick()
            waitFor("code on screen") { rule.onAllNodesWithText("K7M2-QX9P").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(rule.onAllNodesWithTag("account-qr").fetchSemanticsNodes().isNotEmpty())
            val started = site.calls.first { it.startsWith("POST /api/tv/link/start") }
            assertTrue("sends only name, model and version: $started", started.contains("\"appVersion\"") && !started.contains("password"))
            // Still waiting until the person approves it on their phone.
            settle(1500); assertEquals(null, vm.accountEmail)
            site.approved = true
            waitFor("linked (status: ${vm.accountStatus})") { vm.accountEmail == "viewer@example.com" }
            waitFor("Sign out shown") { rule.onAllNodesWithTag("account-signout").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(vm.account.linked)
            // A TV removed on the account page unlinks itself the next time it checks in.
            site.removed = true
            rule.runOnIdle { vm.checkAccount() }
            waitFor("unlinked after removal") { vm.accountEmail == null && !vm.account.linked }
            // Link again, then Sign out of this TV.
            site.removed = false
            rule.onNodeWithTag("account-link").performClick()
            waitFor("linked again") { vm.accountEmail != null }
            rule.onNodeWithTag("account-signout").performClick()
            waitFor("confirm") { rule.onAllNodesWithText("Sign out").fetchSemanticsNodes().size >= 1 && rule.onAllNodesWithText("Sign out of this device?").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Sign out").performClick()
            waitFor("signed out") { vm.accountEmail == null && !vm.account.linked }
            waitFor("server told") { site.calls.any { it.startsWith("POST /api/tv/signout") } }
        }
    }


    @Test
    fun channelsSortByProviderAzOrManualAndListsMixPlaylists() {
        FakeAvidSite().use { site ->
            restartWith { st -> st.savePlaylists(st.loadPlaylists().map { if (it.id == TestData.P1) it.copy(remoteId = "acctP1") else it }) }
            rule.runOnIdle { vm.chooseGroup(TestData.P1, "NEWS") }
            waitFor("channels") { vm.channelsIn("NEWS").size == 12 }
            val provider = vm.channelsIn("NEWS").map { it.key }
            assertEquals("no setting, no order of its own: the provider's order", SORT_PROVIDER, vm.channelSortOf(TestData.P1))
            assertEquals("${TestData.P1}:c1-1", provider.first())

            // A–Z by the name as shown: a renamed channel sorts by its new name; case doesn't matter.
            rule.runOnIdle { vm.updatePrefs { it.copy(channelNames = it.channelNames + ("${TestData.P1}:c1-5" to "aardvark news")) }; vm.setChannelSort(TestData.P1, SORT_AZ) }
            waitFor("sorted") { vm.channelsIn("NEWS").first().key == "${TestData.P1}:c1-5" }
            val az = vm.channelsIn("NEWS").map { it.key }
            assertEquals(az.drop(1).map { k -> vm.channelName(vm.channel(k)!!) }, az.drop(1).map { k -> vm.channelName(vm.channel(k)!!) }.sortedWith(String.CASE_INSENSITIVE_ORDER))
            assertEquals("every category of the playlist", vm.channelsIn("KIDS").map { vm.channelName(it) }, vm.channelsIn("KIDS").map { vm.channelName(it) }.sortedWith(String.CASE_INSENSITIVE_ORDER))

            // Re-ordering switches the playlist to Manual, starting from the A–Z order shown.
            rule.runOnIdle { vm.moveChannel(az[2], -1) }
            waitFor("manual") { vm.channelSortOf(TestData.P1) == SORT_MANUAL }
            assertEquals(listOf(az[0], az[2], az[1]) + az.drop(3), vm.channelsIn("NEWS").map { it.key })
            assertEquals("other categories keep the A–Z order too", vm.channelsIn("KIDS").map { vm.channelName(it) }.sortedWith(String.CASE_INSENSITIVE_ORDER), vm.channelsIn("KIDS").map { vm.channelName(it) })
            // The provider's order again: the playlist as sent, the manual order kept for later.
            rule.runOnIdle { vm.setChannelSort(TestData.P1, SORT_PROVIDER) }
            waitFor("provider") { vm.channelsIn("NEWS").map { it.key } == provider }
            assertTrue(vm.prefs.channelOrder["NEWS"].orEmpty().isNotEmpty())

            // ★ Favorites A–Z mixes playlists by name; Manual starts from that order.
            rule.runOnIdle { vm.updatePrefs { it.copy(favorites = listOf("${TestData.P1}:c1-2", "${TestData.P2}:f3")) } }
            rule.runOnIdle { vm.ensureFavoriteData() }
            waitFor("the other playlist's favorite") { vm.channel("${TestData.P2}:f3") != null }
            assertEquals(listOf("${TestData.P1}:c1-2", "${TestData.P2}:f3"), vm.channelsIn(FAV_GROUP).map { it.key })
            rule.runOnIdle { vm.setListSort(FAV_GROUP, SORT_AZ) }
            waitFor("fav az") { vm.channelsIn(FAV_GROUP).first().key == "${TestData.P2}:f3" }
            rule.runOnIdle { vm.setListSort(FAV_GROUP, SORT_MANUAL) }
            assertEquals(listOf("${TestData.P2}:f3", "${TestData.P1}:c1-2"), vm.prefs.favorites)

            // Sent to the account: the playlist's sort with its order, Favorites' sort with the profile's lists.
            rule.runOnIdle { vm.setChannelSort(TestData.P1, SORT_AZ) }
            linkForTest(site)
            site.profileMap = mapOf("main" to "acctDad")
            site.profiles = org.json.JSONArray().put(org.json.JSONObject().put("id", "acctDad").put("name", "Dad").put("parent", true).put("color", 0).put("pin", "")
                .put("playlists", org.json.JSONArray().put("acctP1")).put("visibility", org.json.JSONObject()))
            rule.runOnIdle { vm.syncAccountPlaylists() }
            waitFor("order sent") { site.syncs.any { s -> s.getJSONArray("playlists").length() > 0 } }
            val order = site.syncs.last { it.getJSONArray("playlists").length() > 0 }.getJSONArray("playlists").getJSONObject(0).getJSONObject("data")
            assertEquals(SORT_AZ, order.getString("channelSort"))
            waitFor("lists sent") { site.syncs.any { s -> s.getJSONArray("profiles").length() > 0 } }
            val lists = site.syncs.last { it.getJSONArray("profiles").length() > 0 }.getJSONArray("profiles").getJSONObject(0).getJSONObject("data")
            assertEquals(SORT_MANUAL, lists.getString("favSort"))
        }
    }

    @Test
    fun theGuideKeepsOnlyChannelsSomeProfileCanSeeAndDownloadsAgainWhenOneIsShown() {
        // A guide address that answers (the test playlists' go nowhere), so downloads complete.
        val server = java.net.ServerSocket(0)
        kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                kotlin.concurrent.thread(isDaemon = true) {
                    c.use { conn ->
                        val r = conn.getInputStream().bufferedReader()
                        r.readLine() ?: return@use
                        while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                        val body = "<?xml version=\"1.0\"?><tv></tv>".toByteArray()
                        conn.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
                    }
                }
            }
        }
        val guideUrl = "http://127.0.0.1:${server.localPort}/g.xml"
        restartWith { st -> st.savePlaylists(st.loadPlaylists().map { if (it.id == TestData.P1) it.copy(epgUrl = guideUrl, epgSources = emptyList()) else it }) }
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "NEWS") }
        waitFor("channels") { vm.channelsIn("NEWS").isNotEmpty() }
        val sports = vm.channels.filter { it.group != "NEWS" && vm.epgIdOf(it) != null }.groupBy { it.group }.entries.first { it.value.size >= 2 }
        val (fav, other) = sports.value[0] to sports.value[1]
        val all = kotlinx.coroutines.runBlocking { vm.guideIdsForTests(TestData.P1) }
        assertTrue(vm.epgIdOf(other)!! in all)
        // Hidden on the only profile: its guide isn't kept, except a channel that's in Favorites.
        rule.runOnIdle { vm.updatePrefs { it.copy(favorites = listOf(fav.key)) }; vm.setGroupsHidden(TestData.P1, listOf(sports.key), true) }
        val kept = kotlinx.coroutines.runBlocking { vm.guideIdsForTests(TestData.P1) }
        assertFalse("a hidden category's channel isn't kept", vm.epgIdOf(other)!! in kept)
        assertTrue("a favorite in it still is", vm.epgIdOf(fav)!! in kept)
        assertTrue(vm.epgIdOf(vm.channelsIn("NEWS").first())!! in kept)
        // The change is noticed after a pause and the guide downloaded for the new set.
        waitFor("downloaded for the new set", ms = 60_000) { vm.guideKeptSigForTests(TestData.P1) == kept.sorted().hashCode().toString() }
        // Shown again: downloaded again, with it.
        rule.runOnIdle { vm.setGroupsHidden(TestData.P1, listOf(sports.key), false) }
        waitFor("downloaded again", ms = 60_000) { vm.guideKeptSigForTests(TestData.P1) == all.sorted().hashCode().toString() }
        server.close()
    }

    @Test
    fun aChannelTheProviderRefusesIsExplainedAndMarkedUntilItPlays() {
        val spec = androidx.media3.datasource.DataSpec(android.net.Uri.parse("http://127.0.0.1:9/s.ts"))
        val e405 = androidx.media3.common.PlaybackException("x", androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(405, "Not Allowed", null, emptyMap(), spec, ByteArray(0)),
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        val (text, refused) = vm.playErrorText(e405)
        assertTrue(text, text.startsWith("The provider refused this channel (HTTP 405)"))
        assertTrue(refused)
        val dns = androidx.media3.common.PlaybackException("x", java.io.IOException(java.net.UnknownHostException("h")), androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertTrue(vm.playErrorText(dns).first.startsWith("Can't find the provider's server"))
        val key = vm.channels.first().key
        rule.runOnIdle { vm.noteFailedChannel(key, true) }
        assertTrue(vm.failedRecently(key))
        waitFor("marked in the guide") { rule.onAllNodesWithText("didn't play", substring = true).fetchSemanticsNodes().isNotEmpty() || vm.selectedGroup != vm.channels.first().group }
        // It played: the mark goes.
        rule.runOnIdle { vm.noteFailedChannel(key, false) }
        assertFalse(vm.failedRecently(key))
    }
}
