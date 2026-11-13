package com.avidviewer.tv

import android.app.Application
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.ui.AppRoot
import com.avidviewer.tv.ui.AppViewModel
import com.avidviewer.tv.ui.Screen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.onNodeWithTag

/**
 * Pictures of the main screens on a TV-sized screen, saved with the test report (the screens folder), so a change to
 * how things look can be checked without a device. Nothing is asserted beyond the screens drawing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = Application::class)
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var vm: AppViewModel
    private val out = File("build/reports/tests/testDebugUnitTest/screens").apply { mkdirs() }

    private fun settle(ms: Long = 300) {
        repeat(3) {
            rule.waitForIdle()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
            Thread.sleep(60)
        }
        rule.waitForIdle()
    }

    private fun waitFor(ms: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { settle(100); if (cond()) return }
    }

    private fun shot(name: String) {
        settle()
        // Drawn straight from the window (captureToImage waits for a frame Robolectric's paused clock never gives).
        // A picture that can't be made is skipped: this never fails the build.
        runCatching {
            rule.runOnUiThread {
                val v = rule.activity.window.decorView
                val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
                v.draw(android.graphics.Canvas(bmp))
                File(out, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }.onFailure { println("screenshot $name skipped: $it") }
    }

    /** A remote button, delivered to the window the way UsabilityTest does it. */
    private fun key(k: androidx.compose.ui.input.key.Key) {
        val code = k.nativeKeyCode
        rule.runOnUiThread {
            val t = android.os.SystemClock.uptimeMillis()
            rule.activity.dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0))
            rule.activity.dispatchKeyEvent(android.view.KeyEvent(t, t + 40, android.view.KeyEvent.ACTION_UP, code, 0))
        }
        settle()
    }

    /** Opens Movies › Featured and highlights a poster with artwork (Right, Down, Right, as UsabilityTest does). */
    private fun highlightPoster() {
        rule.runOnIdle { vm.navSelect(Screen.SETTINGS) }
        settle()
        rule.runOnIdle { vm.navSelect(Screen.MOVIES) }
        settle(400)
        rule.runOnIdle { vm.chooseVodCategory("movie", "Featured") }
        settle(400)
        listOf(androidx.compose.ui.input.key.Key.DirectionRight, androidx.compose.ui.input.key.Key.DirectionDown, androidx.compose.ui.input.key.Key.DirectionRight).forEach { key(it) }
        settle(800)
    }

    @Test
    fun mainScreens() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        TestData.seed(app)
        vm = AppViewModel(app)
        rule.setContent {
            androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = com.avidviewer.tv.ui.C.Accent, background = com.avidviewer.tv.ui.C.Bg, surface = com.avidviewer.tv.ui.C.Surface)) { AppRoot(vm) }
        }
        waitFor { vm.channels.isNotEmpty() }
        rule.runOnIdle { vm.setTheme("roast") } // the test data is an "existing install", which keeps Midnight blue
        rule.runOnIdle { vm.chooseGroup(TestData.P1, "KIDS") }
        waitFor { vm.guideLastEnd > System.currentTimeMillis() + 24 * 3600_000L }
        shot("01-guide-day-strip")
        rule.runOnIdle { vm.navSelect(Screen.MOVIES) }
        waitFor { vm.vodOf("movie").isNotEmpty() }
        shot("02-movies-categories")
        rule.runOnIdle { vm.navSelect(Screen.SETTINGS); vm.settingsSection = "playlists" }
        shot("03-settings-playlists")
        rule.runOnIdle { vm.settingsSection = "privacy" }
        shot("04-settings-privacy-switches")
        rule.runOnIdle { vm.settingsSection = "playback" }
        shot("05-settings-current-value")
        rule.runOnIdle { vm.settingsSection = "about" }
        shot("06-settings-about")
        rule.runOnIdle { vm.navSelect(Screen.DISCOVER) }
        shot("07-discover-needs-key")
        // Profiles: a grown-up and a Kids profile (the owner sets a PIN first).
        rule.runOnIdle { vm.addProfile("Sam", kids = false, color = 1); vm.addKidsProfile("Kids", 2) }
        rule.runOnIdle { vm.answerPin("1234") }
        rule.runOnIdle { vm.answerPin("1234") }
        rule.runOnIdle { vm.navSelect(Screen.SETTINGS); vm.settingsSection = "profiles" }
        shot("08-settings-profiles")
        rule.runOnIdle { vm.editingProfile = vm.profiles.list.first { it.kids }.id }
        shot("09-kids-profile-categories")
        rule.runOnIdle { vm.editingProfile = null; vm.askWhoIsWatching() }
        shot("10-whos-watching")
        rule.runOnIdle { vm.chooseProfile(vm.profiles.owner.id) }
        shot("11-pin")
        rule.runOnIdle { vm.cancelPin(); vm.chooseProfile(vm.profiles.list.first { it.kids }.id) }
        shot("12-kids-guide")
    }

    /** The poster grid with artwork (1.4.0): art fills each tile; the highlighted poster shows its title on a fade. */
    @Test
    fun posterGrid() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        TestData.seed(app)
        // Stand-in artwork: plain painted pictures on disk (no network in tests). Every third has none.
        val dir = File(app.cacheDir, "art").apply { mkdirs() }
        val hues = listOf(0xFF8C5A3C, 0xFF3F5A6B, 0xFF6B3F4A, 0xFF7A6A3A, 0xFF3F6B55, 0xFF5A4A7A, 0xFF9A6A4A, 0xFF4A6A7A)
        val vod = (1..24).map { i ->
            val logo = if (i % 3 == 0) null else File(dir, "p$i.png").also { f ->
                val w = 300; val h = 450
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                val top = hues[i % hues.size].toInt(); val bottom = hues[(i * 5 + 3) % hues.size].toInt()
                c.drawPaint(android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, w * 0.4f, h.toFloat(), top, bottom, android.graphics.Shader.TileMode.CLAMP) })
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                p.color = 0x55FFFFFF; c.drawCircle(w * (0.25f + (i % 4) * 0.15f), h * 0.32f, w * 0.22f, p)
                p.color = 0x66000000; c.drawRect(0f, h * 0.62f, w.toFloat(), h.toFloat(), p)
                p.color = 0x33FFE7C4; c.drawCircle(w * 0.7f, h * 0.7f, w * 0.35f, p)
                f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }.toURI().toString()
            com.avidviewer.tv.data.VodItem("${TestData.P1}:art$i", TestData.P1, "movie", "Sample Feature $i", logo, "http://127.0.0.1:9/none/a$i.mp4",
                category = "Featured", added = i.toLong(), year = "${2000 + i}", rating = if (i % 2 == 0) "7.${i % 10}" else "")
        }
        com.avidviewer.tv.data.Store(app).saveVod(TestData.P1, vod)
        vm = AppViewModel(app)
        rule.setContent {
            androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = com.avidviewer.tv.ui.C.Accent, background = com.avidviewer.tv.ui.C.Bg, surface = com.avidviewer.tv.ui.C.Surface)) { AppRoot(vm) }
        }
        waitFor { vm.channels.isNotEmpty() }
        rule.runOnIdle { vm.setTheme("roast") }
        rule.runOnIdle { vm.navSelect(Screen.MOVIES) }
        waitFor { vm.vodOf("movie").isNotEmpty() }
        highlightPoster()
        shot("13-movies-poster-grid")
        rule.runOnIdle { vm.setVodLayout("featured") }
        highlightPoster()
        shot("15-movies-featured")
        rule.runOnIdle { vm.setVodLayout("showcase") }
        highlightPoster()
        shot("16-movies-showcase")
        rule.runOnIdle { vm.setVodLayout("grid") }
        // Every theme: Settings › Display (the theme picker, one highlighted) and the poster grid.
        com.avidviewer.tv.ui.Themes.all.forEachIndexed { k, t ->
            rule.runOnIdle { vm.setTheme(t.id); vm.navSelect(Screen.SETTINGS); vm.settingsSection = "display" }
            settle(500)
            key(androidx.compose.ui.input.key.Key.DirectionRight)
            settle(500)
            shot("${17 + 2 * k}-theme-${t.id}-settings")
            highlightPoster()
            shot("${18 + 2 * k}-theme-${t.id}-movies")
        }
    }
}
