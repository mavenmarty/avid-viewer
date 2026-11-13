package com.avidviewer.tv

import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
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
        // Left from the first programme goes back up to 2 hours, no further; aired programmes say so.
        focusKidsProgramme()
        var lefts = 0
        while (vm.nav.guideWindowStart != base - 2 * 3600_000L && lefts < 10) { press(listOf(Key.DirectionLeft)); lefts++ }
        assertEquals("window 2 h back (focus: ${focused()})", base - 2 * 3600_000L, vm.nav.guideWindowStart)
        waitFor("an aired programme highlighted and marked (focus: ${focused()})") {
            rule.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().any { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription) == "Already aired" }
        }
        press(listOf(Key.DirectionLeft)) // to the channel name: the window stays
        assertEquals("never more than 2 h back", base - 2 * 3600_000L, vm.nav.guideWindowStart)
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
        val news = vm.channelsIn("NEWS").first().key
        val kidsCh = vm.channelsIn("KIDS").first().key
        rule.runOnIdle { vm.toggleFavorite(news) }
        waitFor("owner's favorite") { news in vm.prefs.favorites }
        var sam = ""
        var kids = ""
        rule.runOnIdle { sam = vm.addProfile("Sam", kids = false, color = 1) }
        // Adding a Kids profile when no parent has a PIN: the owner sets one first (twice).
        rule.runOnIdle { vm.addKidsProfile("Kids", 2) { kids = it } }
        waitFor("asked for a new PIN") { vm.pinAsk != null }
        rule.runOnIdle { assertEquals(null, vm.answerPin("1234")) }
        rule.runOnIdle { assertEquals(null, vm.answerPin("1234")) }
        waitFor("Kids profile added") { kids.isNotEmpty() && vm.profiles.get(kids)?.kids == true }
        assertTrue("the owner has a PIN now", vm.profiles.owner.hasPin)
        rule.runOnIdle {
            val k = vm.profiles.get(kids)!!
            vm.updateProfile(k.copy(allowedGroups = setOf(vm.liveCategoryId(TestData.P1, "KIDS"))))
        }
        // Sam starts with a copy of the owner's favorites.
        rule.runOnIdle { vm.chooseProfile(sam) }
        waitFor("Sam asks for a PIN (a Kids profile exists)") { vm.pinAsk != null }
        rule.runOnIdle { assertEquals("That PIN isn't right.", vm.answerPin("0000")) }
        rule.runOnIdle { assertEquals(null, vm.answerPin("1234")) } // a parent's PIN opens it
        waitFor("watching as Sam") { vm.profile.id == sam }
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
        assertEquals(listOf("US| LOCALS", "NEWS", "SPORTS | NFL", "VOD | EN - Movies 4K", "KIDS", "FLO Sports").toSet(), vm.groups().map { it.raw }.toSet())
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

    @Test
    fun backupSectionOpensWithoutCrashing() {
        go(Screen.SETTINGS)
        rule.runOnIdle { vm.settingsSection = "about" }
        settle()
        assertTrue(rule.onAllNodesWithText("Backup & restore").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("backup-now").performClick()
        settle()
        assertTrue(rule.onAllNodesWithText("Choose a PIN for this backup").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun reportABugShowsTheAddressDetailsAndQrCode() {
        go(Screen.SETTINGS)
        rule.runOnIdle { vm.settingsSection = "about" }
        settle()
        assertTrue(rule.onAllNodesWithText("Report a bug").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("bugs@avidviewer.app").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("App version:", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Device:", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag("bug-qr").fetchSemanticsNodes().isNotEmpty())
        // The email link carries the details but nothing about the user's playlists.
        val link = android.net.Uri.decode(com.avidviewer.tv.data.BugReport.mailto("1.0.test", "java.lang.IllegalStateException: boom\n at x.y(Z.kt:1)"))
        assertTrue(link, link.startsWith("mailto:bugs@avidviewer.app?subject="))
        assertTrue(link, link.contains("App version: 1.0.test") && link.contains("Last crash: java.lang.IllegalStateException: boom"))
        vm.playlists.forEach { p -> assertTrue("leaks ${p.name}", !link.contains(p.name) && (p.server.isBlank() || !link.contains(p.server))) }
    }

    @Test
    fun aboutShowsQrCodesForTheUserGuideAndTheControl4Driver() {
        go(Screen.SETTINGS)
        rule.runOnIdle { vm.settingsSection = "about" }
        settle()
        rule.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction()).onFirst()
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("control4-qr"))
        settle()
        assertTrue(rule.onAllNodesWithText("User guide (PDF)").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Control4 mini driver").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag("guide-qr").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag("control4-qr").fetchSemanticsNodes().isNotEmpty())
        assertTrue(com.avidviewer.tv.data.Links.USER_GUIDE.endsWith("/releases/download/user-guide/AvidViewer-User-Guide.pdf"))
    }

    @Test
    fun updatesAndAboutBlocksAreReachableAndShownWhole() {
        go(Screen.SETTINGS)
        var tries = 0
        while (!focused().contains("Updates & about") && tries < 15) { press(listOf(Key.DirectionDown)); tries++ }
        press(listOf(Key.DirectionCenter))
        fun focusedTag(): String = rule.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes()
            .joinToString { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) ?: focused() }
        waitFor("OK opens Updates & about on its first block (focus: ${focusedTag()})") { vm.settingsSection == "about" && (focusedTag() == "block-Report a bug" || focused().contains("Open email app")) }
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
        waitFor("Settings' first section highlighted (focus: ${focused()})") { focused().contains("Playlists") && vm.settingsSection == "playlists" }
        var tries = 0
        while (!focused().contains("Updates & about") && tries < 15) { press(listOf(Key.DirectionDown)); tries++ }
        assertTrue("Down reaches Updates & about, on screen and highlighted (focus: ${focused()})", focused().contains("Updates & about"))
        assertEquals("moving the highlight doesn't open sections", "playlists", vm.settingsSection)
        // The section list doesn't wrap: Down at the end stays on the last section.
        press(listOf(Key.DirectionDown))
        assertTrue("no wrap in the section list (focus: ${focused()})", focused().contains("Updates & about"))
        press(listOf(Key.DirectionCenter))
        waitFor("OK opens it (section=${vm.settingsSection})") { vm.settingsSection == "about" && vm.settingsInContent }
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
        assertTrue("on the grid's last row (focus: $last)", Regex("Action Movie (\\d+)").find(last)?.groupValues?.get(1)?.toInt()?.let { it > 12 } == true)
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
        rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).onFirst().performTextInput("http://example.invalid/guide.xml")
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

    @Test
    fun theMainMenuShowsAsIconsAtLaunchOnTheGuide() {
        settle()
        assertEquals(Screen.GUIDE, vm.screen)
        assertEquals(NavMode.GROUPS, vm.navMode)
        assertTrue("main menu icons are on screen", rule.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty())
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
                            target.contains("action=get_series_info") -> """{"info":{"name":"Test Show","plot":"$plot"},"episodes":{
                                "1":[{"id":"101","episode_num":1,"title":"Pilot","container_extension":"mp4","info":{"duration":"00:42:00"}},
                                     {"id":"102","episode_num":2,"title":"Second","container_extension":"mp4","info":{}}],
                                "2":[{"id":"201","episode_num":1,"title":"Season Two Opener","container_extension":"mkv","info":{}}]}}"""
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

    /** Nov 12: searching "Farg" found nothing; part of a word, in any order, now finds it — as you type. */
    @Test
    fun searchFindsPartOfAWordAsYouType() {
        go(Screen.SEARCH)
        rule.runOnIdle { vm.typeSearch("farg") } // typed, Search not pressed
        waitFor("results for farg (searched=${vm.searchedFor})") { vm.searchedFor == "farg" && vm.searchResults?.any { it.stations.isNotEmpty() } == true }
        assertTrue(vm.searchResults!!.flatMap { it.stations }.all { it.title.contains("Fargo") })
        rule.runOnIdle { vm.typeSearch("fargo wday 3") }
        waitFor("words in any order") { vm.searchedFor == "fargo wday 3" && vm.searchResults?.flatMap { it.stations }?.any { it.title.contains("WDAY ABC 3 Fargo") } == true }
        rule.runOnIdle { vm.typeSearch("movie drama 1") }
        waitFor("movies too") { vm.searchedFor == "movie drama 1" && vm.searchResults?.flatMap { it.movies }?.any { it.title == "Drama Movie 1" } == true }
        rule.runOnIdle { vm.typeSearch("zzzz") }
        waitFor("nothing found names what was searched") { vm.searchedFor == "zzzz" }
        rule.onNodeWithText("Nothing found for “zzzz”", substring = true).assertExists()
        // The TV Guide's own search, too.
        rule.runOnIdle { vm.runGuideSearch("abc farg") }
        waitFor("guide search finds the channels") { vm.guideSearchHits.channels.any { vm.channelName(it).contains("Fargo") } }
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
        press(listOf(Key.DirectionUp))
        val lastCat = vm.vodCatName("movie", vm.vodCategories("movie").last().first)
        waitFor("Up from All wraps to the last movie category (focus: ${focused()})") { focused().contains(lastCat) }
        press(listOf(Key.DirectionDown))
        waitFor("Down from the last category wraps to All (focus: ${focused()})") { focused().contains("All") }
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
}
