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

    @Test
    fun mainScreens() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        TestData.seed(app)
        vm = AppViewModel(app)
        rule.setContent {
            androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = com.avidviewer.tv.ui.C.Accent, background = com.avidviewer.tv.ui.C.Bg, surface = com.avidviewer.tv.ui.C.Surface)) { AppRoot(vm) }
        }
        waitFor { vm.channels.isNotEmpty() }
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
    }
}
