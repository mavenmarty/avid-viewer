package com.avidviewer.tv

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.ui.AppRoot
import com.avidviewer.tv.ui.AppViewModel
import com.avidviewer.tv.ui.FAV_GROUP
import com.avidviewer.tv.ui.PlayContext
import com.avidviewer.tv.ui.Screen
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Navigation audit probe (Nov 14): each scenario does one customization with the remote's keys only (after
 * putting the highlight where a viewer would start) and records how many presses it took and where the
 * highlight ended up. Not part of the regular build: run on the ci-check branch; the last test prints the table.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = Application::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NavAuditTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var vm: AppViewModel
    private val out = File(System.getProperty("java.io.tmpdir"), "nav-audit.txt")
    private var presses = 0
    private val log = StringBuilder()

    @Before
    fun start() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        TestData.seed(app)
        vm = AppViewModel(app)
        rule.setContent { AppRoot(vm) }
        rule.runOnUiThread { rule.activity.window.decorView.requestFocus() }
        waitFor { vm.channels.isNotEmpty() }
    }

    private fun settle(ms: Long = 250) {
        rule.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
        Thread.sleep(90)
        rule.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
        rule.waitForIdle()
    }

    private fun waitFor(ms: Long = 8_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { settle(100); if (cond()) return true; Thread.sleep(40) }
        return false
    }

    private fun focused(): String = runCatching {
        rule.onAllNodes(isFocused(), useUnmergedTree = false).fetchSemanticsNodes().joinToString(" / ") { n ->
            (n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
                ?: n.config.getOrNull(SemanticsProperties.EditableText)?.text?.let { "[field] $it" }
                ?: "(no text)").take(70)
        }.ifBlank { "(nothing)" }
    }.getOrDefault("(unknown)")

    private fun where() = "${focused()}  ‹screen=${vm.screen.name.lowercase()} nav=${vm.navMode.name.lowercase()} group=${vm.selectedGroup}›"

    private fun topWindowView(): android.view.View? {
        val views: List<android.view.View> = runCatching {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            val wmg = cls.getMethod("getInstance").invoke(null)
            (cls.getDeclaredField("mViews").apply { isAccessible = true }.get(wmg) as List<*>).filterIsInstance<android.view.View>()
        }.getOrDefault(emptyList())
        return views.lastOrNull()?.takeIf { it !== rule.activity.window.decorView }
    }

    private fun key(k: Key, count: Boolean = true) {
        if (count) presses++
        val code = k.nativeKeyCode
        rule.runOnUiThread {
            val t = android.os.SystemClock.uptimeMillis()
            val down = AndroidKeyEvent(t, t, AndroidKeyEvent.ACTION_DOWN, code, 0)
            val up = AndroidKeyEvent(t, t + 40, AndroidKeyEvent.ACTION_UP, code, 0)
            val target = topWindowView()
            if (target != null) { target.dispatchKeyEvent(down); target.dispatchKeyEvent(up) } else { rule.activity.dispatchKeyEvent(down); rule.activity.dispatchKeyEvent(up) }
        }
        settle()
    }

    private fun step(what: String) { log.append("    · ").append(what).append(" → ").append(where()).append('\n') }

    /** Presses Down (or [dir]) until the highlight is on [label]; false if it never gets there. */
    private fun moveTo(label: String, dir: Key = Key.DirectionDown, max: Int = 10): Boolean {
        var n = 0
        while (!focused().contains(label) && n < max) { key(dir); n++ }
        return focused().contains(label)
    }

    private fun startOn(text: String, substring: Boolean = true) {
        waitFor { rule.onAllNodes(hasText(text, substring = substring) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText(text, substring = substring) and hasClickAction()).onFirst().requestFocus()
        settle()
        presses = 0
    }

    /** Types into the open text field, as with the on-screen keyboard (letters not counted), then presses its Done/Next. */
    private fun typeAndDone(text: String) {
        waitFor { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        val n = rule.onAllNodes(hasSetTextAction()).onFirst()
        n.performTextInput(text)
        settle()
        presses++ // the keyboard's Done / Next key
        n.performImeAction()
        settle()
    }

    private fun record(name: String, expect: String, ok: Boolean = focused().contains(expect)) {
        val line = StringBuilder()
        line.append(if (ok) "OK   " else "BAD  ").append(name).append(" · presses=").append(presses).append('\n')
        line.append("    expected: ").append(expect).append('\n')
        line.append("    landed:   ").append(where()).append('\n')
        if (!ok && focused() == "(nothing)") {
            // What the viewer sees next: any arrow puts the highlight somewhere.
            key(Key.DirectionDown, count = false)
            line.append("    next ▼ :  ").append(where()).append('\n')
        }
        line.append(log)
        mine.append(line.toString() + "\n")
        log.clear()
    }

    private val mine = StringBuilder()

    /** Runs a scenario; its results are reported as this test's failure message, so they show in the build log. */
    private fun guardRun(name: String, body: () -> Unit) {
        try { body() } catch (t: Throwable) {
            mine.append("ERR  $name: ${t.javaClass.simpleName}: ${t.message?.take(400)}\n$log\n")
            log.clear()
        }
        val rt = Runtime.getRuntime()
        mine.append("    (heap ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB)\n")
        throw AssertionError("NAV-AUDIT\n$mine")
    }

    // ------------------------------------------------------------------ TV Guide: channel menu

    @Test fun a01_renameChannel() = guardRun("Guide › Menu on a channel › Rename › Save") {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }; settle()
        startOn("KIDS Channel 3 with")
        key(Key.Menu); step("Menu")
        moveTo("Rename"); step("to Rename")
        key(Key.DirectionCenter); step("OK on Rename (dialog opens)")
        val fieldFirst = focused().contains("Name") || focused().contains("[field]")
        if (!fieldFirst) { moveTo("Name"); step("to the Name field") }
        key(Key.DirectionCenter); step("OK on the field (keyboard)")
        typeAndDone(" X"); step("typed, keyboard Done/Next")
        moveTo("Save", Key.DirectionDown, 4) || moveTo("Save", Key.DirectionRight, 3); step("to Save")
        key(Key.DirectionCenter)
        record("Guide › Rename channel", "KIDS Channel 3 with")
    }

    @Test fun a02_addToListFromChannelMenu() = guardRun("Guide › Menu › Add to List › a list") {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }; settle()
        startOn("KIDS Channel 3 with")
        key(Key.Menu); moveTo("Add to List"); step("Menu, to Add to List")
        key(Key.DirectionCenter); step("OK (picker opens)")
        moveTo("Kids night"); step("to Kids night")
        key(Key.DirectionCenter)
        record("Guide › Add to List (from a category)", "KIDS Channel 3 with")
    }

    @Test fun a03_removeFromFavoritesViaPicker() = guardRun("Favorites › Menu › Add to List › ✓ Favorites") {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, FAV_GROUP) }; settle()
        val names = vm.channelsIn(FAV_GROUP).map { vm.channelName(it) }
        log.append("    favorites: $names\n")
        startOn(names.first().take(18))
        key(Key.Menu); moveTo("Add to List"); key(Key.DirectionCenter); step("Menu › Add to List")
        moveTo("Favorites", Key.DirectionUp, 3); step("on ✓ Favorites")
        key(Key.DirectionCenter)
        record("Favorites › take a channel out with the Add to List picker", names.getOrElse(1) { "Favorites" }.take(18))
    }

    @Test fun a04_removeFromListViaMenu() = guardRun("A list › Menu › Remove from list") {
        val list = vm.allLists().first { it.second.contains("Kids night") }.first
        rule.runOnIdle { vm.chooseGroup(TestData.P1, list) }; settle()
        val names = vm.channelsIn(list).map { vm.channelName(it) }
        startOn(names.first().take(18))
        key(Key.Menu); moveTo("Remove from"); step("Menu, to Remove from")
        key(Key.DirectionCenter)
        record("Kids night › Menu › Remove from Kids night", names.getOrElse(1) { "Kids night" }.take(18))
    }

    @Test fun a05_addToListThenTheListItself() = guardRun("Add to List into the list on screen") {
        val list = vm.allLists().first { it.second.contains("Kids night") }.first
        rule.runOnIdle { vm.chooseGroup(TestData.P1, list) }; settle()
        val names = vm.channelsIn(list).map { vm.channelName(it) }
        startOn(names.first().take(18))
        key(Key.Menu); moveTo("Add to List"); key(Key.DirectionCenter); step("Menu › Add to List")
        moveTo("Kids night"); step("on ✓ Kids night"); key(Key.DirectionCenter)
        record("Kids night › take a channel out with the picker", names.getOrElse(1) { "Kids night" }.take(18))
    }

    @Test fun a06_hideChannel() = guardRun("Guide › Menu › Hide channel") {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }; settle()
        startOn("KIDS Channel 3 with")
        key(Key.Menu); moveTo("Hide channel"); key(Key.DirectionCenter)
        record("Guide › Menu › Hide channel", "KIDS Channel 4 with")
    }

    @Test fun a07_matchGuide() = guardRun("Guide › Menu › Match guide › No guide") {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }; settle()
        startOn("KIDS Channel 3 with")
        key(Key.Menu); moveTo("Match guide"); key(Key.DirectionCenter); step("Menu › Match guide…")
        moveTo("No guide", max = 14); step("to No guide"); key(Key.DirectionCenter)
        record("Guide › Match guide › choose", "KIDS Channel 3 with")
    }

    @Test fun a08_menuBackAndDone() = guardRun("Guide › Menu › Back / Done") {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }; settle()
        startOn("KIDS Channel 3 with")
        key(Key.Menu); step("Menu"); key(Key.Back)
        record("Guide › Menu › Back", "KIDS Channel 3 with")
        startOn("KIDS Channel 3 with")
        key(Key.Menu); moveTo("Done", max = 10); key(Key.DirectionCenter)
        record("Guide › Menu › Done", "KIDS Channel 3 with")
    }

    @Test fun a09_reorderEnds() = guardRun("Guide › Menu › Re-order › OK") {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }; settle()
        startOn("KIDS Channel 3 with")
        key(Key.Menu); moveTo("Re-order"); key(Key.DirectionCenter); key(Key.DirectionDown); key(Key.DirectionCenter)
        record("Guide › Re-order a channel, OK", "KIDS Channel 3 with")
    }

    // ------------------------------------------------------------------ TV Guide: categories and lists

    @Test fun b01_chooseCategory() = guardRun("Category list › choose another") {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "NEWS") }; settle()
        startOn("KIDS", substring = false)
        key(Key.DirectionCenter); step("OK on KIDS")
        record("Category list › OK on a category", "KIDS Channel 1 with")
        key(Key.DirectionLeft, count = false); step("◀ back to the categories")
        presses = 0
        moveTo("SPORTS | NFL", Key.DirectionUp, 4); key(Key.DirectionCenter)
        record("Category list › ◀ then another category, OK", "SPORTS | NFL Channel 1")
    }

    @Test fun b02_chooseCategoryAfterFullScreen() = guardRun("Full screen › Back › another category") {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }; settle()
        startOn("KIDS Channel 3 with")
        key(Key.DirectionCenter); key(Key.DirectionCenter); step("OK, OK (full screen)")
        waitFor { vm.screen == Screen.PLAYER }
        key(Key.Back); step("Back")
        key(Key.DirectionLeft); step("◀ to the categories")
        key(Key.DirectionUp); step("▲"); key(Key.DirectionCenter)
        record("After full screen › ◀, ▲, OK on a category", "Channel 1 with")
    }

    @Test fun b03_renameCategory() = guardRun("Category › Menu › Rename") {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }; settle()
        startOn("NEWS", substring = false)
        key(Key.Menu); moveTo("Rename"); key(Key.DirectionCenter); step("Menu › Rename")
        if (!focused().contains("[field]") && !focused().contains("Name")) moveTo("Name")
        key(Key.DirectionCenter); typeAndDone(" X"); step("typed")
        moveTo("Save", Key.DirectionDown, 4) || moveTo("Save", Key.DirectionRight, 3); key(Key.DirectionCenter)
        record("Category › Rename", "NEWS X")
    }

    @Test fun b04_hideCategory() = guardRun("Category › Menu › Hide") {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }; settle()
        startOn("NEWS", substring = false)
        key(Key.Menu); moveTo("Hide category"); key(Key.DirectionCenter)
        record("Category › Hide category", "SPORTS | NFL")
    }

    @Test fun b05_listMenuMoveAndDelete() = guardRun("List › Menu › Move down / Delete") {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }; settle()
        startOn("Kids night")
        key(Key.Menu); moveTo("Move down"); key(Key.DirectionCenter); step("Move down")
        moveTo("Done", max = 6); key(Key.DirectionCenter)
        record("List › Menu › Move down, Done", "Kids night")
        startOn("Kids night")
        key(Key.Menu); moveTo("Delete list"); key(Key.DirectionCenter); step("Delete list?")
        moveTo("Delete", Key.DirectionLeft, 2) || moveTo("Delete", Key.DirectionRight, 2); key(Key.DirectionCenter)
        record("List › Delete list", "Favorites")
    }

    @Test fun b06_newList() = guardRun("＋ New list") {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }; settle()
        startOn("New list")
        key(Key.DirectionCenter); step("OK (dialog)")
        if (!focused().contains("[field]") && !focused().contains("Name")) moveTo("Name")
        key(Key.DirectionCenter); typeAndDone("Late"); step("typed")
        moveTo("Save", Key.DirectionDown, 4) || moveTo("Save", Key.DirectionRight, 3); key(Key.DirectionCenter)
        record("＋ New list › Save", "Late")
    }

    @Test fun b07_renameList() = guardRun("List › Menu › Rename") {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }; settle()
        startOn("Kids night")
        key(Key.Menu); moveTo("Rename"); key(Key.DirectionCenter)
        if (!focused().contains("[field]") && !focused().contains("Name")) moveTo("Name")
        key(Key.DirectionCenter); typeAndDone(" 2")
        moveTo("Save", Key.DirectionDown, 4) || moveTo("Save", Key.DirectionRight, 3); key(Key.DirectionCenter)
        record("List › Rename", "Kids night 2")
    }

    // ------------------------------------------------------------------ Settings › Playlists › Guide sources

    private fun openGuideSources() {
        rule.runOnIdle { vm.navSelect(Screen.SETTINGS) }; settle()
        rule.runOnIdle { vm.settingsSection = "playlists" }; settle()
        rule.onAllNodesWithText("My provider").onFirst().performClick(); settle()
        rule.onAllNodesWithTag("pl-guide-sources").onFirst().performClick(); settle()
    }

    @Test fun c01_addGuideSource() = guardRun("Guide sources › Add › Save") {
        openGuideSources()
        rule.onAllNodesWithTag("guide-src-add").onFirst().requestFocus(); settle(); presses = 0
        key(Key.DirectionCenter); step("OK on Add guide source")
        key(Key.DirectionCenter); typeAndDone("Mine"); step("name typed")
        moveTo("XMLTV"); key(Key.DirectionCenter); typeAndDone("http://127.0.0.1:9/g.xml"); step("address typed")
        moveTo("Save", Key.DirectionDown, 6); step("to Save"); key(Key.DirectionCenter)
        record("Guide sources › Add › Save", "Add guide source")
    }

    @Test fun c02_moveAndDeleteGuideSource() = guardRun("Guide sources › ▲ / Delete") {
        rule.runOnIdle {
            vm.addEpgSource(TestData.P1, "One", "http://127.0.0.1:9/1.xml", 0)
            vm.addEpgSource(TestData.P1, "Two", "http://127.0.0.1:9/2.xml", 0)
        }
        openGuideSources()
        waitFor { rule.onAllNodesWithText("▲").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("▲") and hasClickAction()).onFirst().requestFocus(); settle(); presses = 0
        key(Key.DirectionCenter)
        record("Guide sources › ▲ on a source (moves it to the top)", "▼")
        rule.onAllNodesWithTag("guide-src-delete").onFirst().requestFocus(); settle(); presses = 0
        key(Key.DirectionCenter); step("Delete?")
        moveTo("Delete", Key.DirectionLeft, 2) || moveTo("Delete", Key.DirectionRight, 2); key(Key.DirectionCenter)
        record("Guide sources › Delete a source", "Delete")
        rule.onAllNodes(hasText("Turn off") and hasClickAction()).onFirst().requestFocus(); settle(); presses = 0
        key(Key.DirectionCenter)
        record("Guide sources › Turn off", "Turn on")
    }

    // ------------------------------------------------------------------ Movies and Series

    @Test fun d01_myListFromPoster() = guardRun("Movies › Menu on a poster (My List)") {
        rule.runOnIdle { vm.navSelect(Screen.MOVIES) }
        waitFor { vm.vodOf("movie").isNotEmpty() && vm.entryFocusFor == null }
        key(Key.DirectionRight, count = false); settle(); step("to the posters")
        val title = focused()
        presses = 0
        key(Key.Menu)
        record("Movies › Menu on a poster (+ My List)", title.take(12))
    }

    @Test fun d02_vodCategoryRenameAndHide() = guardRun("Movies › category Menu") {
        rule.runOnIdle { vm.navSelect(Screen.MOVIES) }
        waitFor { vm.vodOf("movie").isNotEmpty() && vm.entryFocusFor == null }
        startOn("Action", substring = false)
        key(Key.Menu); moveTo("Rename"); key(Key.DirectionCenter)
        if (!focused().contains("[field]") && !focused().contains("Name")) moveTo("Name")
        key(Key.DirectionCenter); typeAndDone(" X")
        moveTo("Save", Key.DirectionDown, 4) || moveTo("Save", Key.DirectionRight, 3); key(Key.DirectionCenter)
        record("Movies › category › Rename", "Action X")
        startOn("Action X", substring = false)
        key(Key.Menu); moveTo("Hide category"); key(Key.DirectionCenter)
        record("Movies › category › Hide", "(a neighbouring category)", ok = focused() != "(nothing)" && vm.screen == Screen.MOVIES)
    }

    // ------------------------------------------------------------------ Search and full screen

    @Test fun e01_addToListFromSearch() = guardRun("Search › + Add to List") {
        rule.runOnIdle { vm.navSelect(Screen.SEARCH) }; settle()
        rule.runOnIdle { vm.search("kids channel 3") }
        waitFor { vm.searchedFor == "kids channel 3" && !vm.searching }
        startOn("Add to List")
        key(Key.DirectionCenter); step("picker"); moveTo("Kids night"); key(Key.DirectionCenter)
        record("Search › ＋ Add to List › a list", "In a list")
    }

    @Test fun e02_addToListFromFullScreen() = guardRun("Full screen › Menu › Add to List") {
        val k = "${TestData.P1}:c4-3"
        rule.runOnIdle { vm.openFullScreen(k, PlayContext.GROUP) }
        waitFor { vm.screen == Screen.PLAYER }
        presses = 0
        key(Key.Menu); moveTo("Add to List", max = 14); key(Key.DirectionCenter); step("picker")
        moveTo("Kids night"); key(Key.DirectionCenter)
        record("Full screen › Picture & sound › Add to List › a list", "(still full screen, panel or player)", ok = vm.screen == Screen.PLAYER)
    }

}
