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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.ui.AppRoot
import com.avidviewer.tv.ui.AppViewModel
import com.avidviewer.tv.ui.FAV_GROUP
import com.avidviewer.tv.ui.PlayContext
import com.avidviewer.tv.ui.Screen
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Navigation audit fixes (Nov 15): each test does one customization with the remote's keys only, after putting
 * the highlight where a viewer would start, and checks where the highlight lands and how many presses it took.
 * Before these fixes, hiding a category, deleting a list, taking a poster off My List and moving a guide source
 * sent the highlight to the main menu or ‹ Menu (BUG-036–041); Rename took 9 presses (BUG-043).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = Application::class)
class NavigationFixesTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var vm: AppViewModel
    private var presses = 0

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

    /** Types into the open keyboard (letters not counted) and presses its Done / Next (1 press). */
    private fun typeAndSubmit(text: String?) {
        waitFor { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        // The field with the keyboard open (it has the highlight), else the dialog's field (listed last).
        val n = if (rule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty())
            rule.onAllNodes(hasSetTextAction() and isFocused()).onFirst() else rule.onAllNodes(hasSetTextAction()).onLast()
        if (text != null) {
            n.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString(text)) }
            settle()
        }
        presses++
        n.performImeAction()
        settle()
    }

    private fun expectFocus(what: String, cond: (String) -> Boolean) {
        val ok = waitFor(4_000) { cond(focused()) }
        assertTrue("$what (highlight: ${where()}; trail: ${vm.focusTrail.toList().takeLast(14)})", ok)
    }

    @Test fun hidingATvCategoryHighlightsTheNextOne() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }; settle()
        val names = vm.groups().map { it.name }
        val next = names[names.indexOf("NEWS") + 1]
        startOn("NEWS", substring = false)
        key(Key.Menu); assertTrue(moveTo("Hide category")); key(Key.DirectionCenter)
        expectFocus("the category after NEWS ($next)") { it.startsWith(next) }
    }

    @Test fun deletingAListHighlightsItsNeighbour() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }; settle()
        startOn("Kids night")
        key(Key.Menu); assertTrue(moveTo("Delete list")); key(Key.DirectionCenter)
        moveTo("Delete", Key.DirectionLeft, 2) || moveTo("Delete", Key.DirectionRight, 2)
        key(Key.DirectionCenter)
        expectFocus("the item that took the list's place") { it.contains("New list") || it.contains("Favorites") }
    }

    @Test fun takingAPosterOffMyListHighlightsTheNextPoster() {
        rule.runOnIdle { vm.navSelect(Screen.MOVIES) }
        waitFor { vm.vodOf("movie").isNotEmpty() && vm.entryFocusFor == null }
        val items = vm.vodOf("movie").take(3)
        rule.runOnIdle { items.forEach { vm.toggleMyList(it) } }; settle()
        startOn("My List", substring = false)
        key(Key.DirectionCenter); key(Key.DirectionRight)
        val first = focused()
        key(Key.Menu)
        expectFocus("another poster in My List (was on $first)") { it.contains("Movie") && it != first && vm.screen == Screen.MOVIES }
    }

    @Test fun hidingAMoviesCategoryHighlightsTheNextOne() {
        rule.runOnIdle { vm.navSelect(Screen.MOVIES) }
        waitFor { vm.vodOf("movie").isNotEmpty() && vm.entryFocusFor == null }
        val cats = vm.vodCategories("movie").map { vm.vodCatName("movie", it.first) }
        val i = cats.indexOf("Action").also { assertTrue("Action in $cats", it >= 0) }
        val next = cats.getOrNull(i + 1) ?: cats[i - 1]
        startOn("Action", substring = false)
        key(Key.Menu); assertTrue(moveTo("Hide category")); key(Key.DirectionCenter)
        expectFocus("the category after Action ($next)") { it.startsWith(next) }
    }

    @Test fun movingAGuideSourceKeepsTheHighlightOnIt() {
        rule.runOnIdle {
            vm.addEpgSource(TestData.P1, "One", "http://127.0.0.1:9/1.xml", 0)
            vm.addEpgSource(TestData.P1, "Two", "http://127.0.0.1:9/2.xml", 0)
        }
        openGuideSources()
        waitFor { rule.onAllNodesWithText("▲").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("▲") and hasClickAction()).onFirst().requestFocus(); settle()
        key(Key.DirectionCenter)
        expectFocus("▼ on the source moved to the top") { it == "▼" }
    }

    @Test fun newListTakesTheHighlightAndDoneCreatesIt() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }; settle()
        startOn("New list")
        key(Key.DirectionCenter) // the keyboard opens with the dialog
        typeAndSubmit("Late")
        assertEquals("OK, Done", 2, presses)
        expectFocus("the new list's row") { it.contains("Late") }
        assertTrue(vm.allLists().any { it.second == "Late" })
    }

    @Test fun renameIsMenuRenameTypeDone() {
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }; settle()
        startOn("KIDS Channel 3 with")
        key(Key.Menu); assertTrue(moveTo("Rename")); key(Key.DirectionCenter)
        typeAndSubmit("Late show")
        assertTrue("at most 5 presses (was 9): $presses", presses <= 5)
        expectFocus("the renamed channel") { it.contains("Late show") }
    }

    @Test fun addingAGuideSourceFlowsFromFieldToField() {
        openGuideSources()
        rule.onAllNodesWithTag("guide-src-add").onFirst().requestFocus(); settle(); presses = 0
        key(Key.DirectionCenter) // the keyboard opens on Name
        typeAndSubmit("Mine") // Next: on to the address
        typeAndSubmit("http://127.0.0.1:9/g.xml") // Done: saves
        assertEquals("OK, Next, Done (was 11)", 3, presses)
        waitFor { vm.playlists.first { it.id == TestData.P1 }.epgSources.any { it.name == "Mine" } }
        assertTrue(vm.playlists.first { it.id == TestData.P1 }.epgSources.any { it.name == "Mine" && it.url.endsWith("g.xml") })
        expectFocus("Add guide source again") { it.contains("Add guide source") }
    }

    private fun openGuideSources() {
        rule.runOnIdle { vm.navSelect(Screen.SETTINGS) }; settle()
        rule.runOnIdle { vm.settingsSection = "playlists" }; settle()
        rule.onAllNodesWithText("My provider").onFirst().performClick(); settle()
        rule.onAllNodesWithTag("pl-guide-sources").onFirst().performClick(); settle()
    }
}
