package com.avidviewer.tv

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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.printToString
import androidx.compose.ui.semantics.getOrNull
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
        rule.onAllNodesWithText("NEWS Channel 1 with", substring = true).onFirst().requestFocus()
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionRight, Key.DirectionRight, Key.DirectionLeft, Key.DirectionUp))
        assertEquals(NavMode.GROUPS, vm.navMode)
        press(listOf(Key.Back))
        assertNotEquals("Back should leave the category list", NavMode.GROUPS, vm.navMode)
    }

    @Test
    fun menuReorderMovesTheChannelAndBackOnlyEndsReordering() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }
        settle()
        val first = vm.channelsIn("KIDS").first().key
        // As a viewer would: highlight the channel name, press Menu, choose Re-order.
        rule.onAllNodesWithText("KIDS Channel 1 with", substring = true).onFirst().requestFocus()
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
    private class FakeProvider : AutoCloseable {
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
                            target.contains("action=get_series_info") -> """{"info":{"name":"Test Show","plot":"A show for testing."},"episodes":{
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
            // With the remote: Down to the season buttons, Right to Season 2, OK.
            tries = 0
            while (!focused().contains("Season 1") && tries < 6) { press(listOf(Key.DirectionDown)); tries++ }
            assertTrue("couldn't reach the season buttons (focus: ${focused()})", focused().contains("Season 1"))
            press(listOf(Key.DirectionRight))
            assertTrue("Right didn't reach Season 2 (focus: ${focused()})", focused().contains("Season 2"))
            press(listOf(Key.DirectionCenter))
            tries = 0
            while (!focused().contains("Season Two Opener") && tries < 10) { press(listOf(Key.DirectionDown)); tries++ }
            assertTrue("couldn't reach a season 2 episode (focus: ${focused()})", focused().contains("Season Two Opener"))
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

    @Test
    fun channelAndCategoryListsWrapAround() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }
        waitFor("KIDS channel 1 highlighted") { focused().contains("KIDS Channel 1 with") }
        // Down on the last channel goes to the first; Up from the top (past the header) to the last.
        press(listOf(Key.DirectionUp))
        if (!focused().contains("KIDS Channel 12 ")) press(listOf(Key.DirectionUp))
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
