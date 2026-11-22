package com.avidviewer.tv

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.PlaybackLog
import com.avidviewer.tv.ui.AppViewModel
import com.avidviewer.tv.ui.quietStartInTests
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Nov 21, 2026: background work waits for a quiet start, and the playback log says how the start went. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QuietStartTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var vm: AppViewModel? = null

    @After
    fun stop() {
        quietStartInTests = false
        vm?.viewModelScope?.cancel()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun idle(ms: Long) = shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    @Test
    fun withNothingPlayingTheQuietStartEndsAfter30SecondsAndIsLogged() {
        TestData.seed(app)
        quietStartInTests = true
        val v = AppViewModel(app).also { vm = it }
        v.playbackLog.clear()
        idle(20_000)
        assertFalse("still quiet at 20 s", v.quietStartDone.isCompleted)
        idle(12_000)
        assertTrue("over once nothing has played for 30 s", v.quietStartDone.isCompleted)
        val start = v.playbackLog.entries().firstOrNull { it.kind == PlaybackLog.DEVICE && it.text.startsWith("Start-up:") }
        assertTrue("logged: ${v.playbackLog.entries().map { it.text }}", start != null && start.text.contains("nothing was playing"))
    }
}
