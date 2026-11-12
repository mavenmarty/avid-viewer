package com.avidviewer.tv.audit

// Menu audit stress harness (Phase 6). Not product code and not part of the normal build:
// to run it, copy this file to app/src/test/java/com/avidviewer/tv/audit/ and run
//   gradle testDebugUnitTest --tests "*AuditHarnessTest*"
// It needs python3 (fixtures come from audit/fixtures/generate.py, the same files a real TV can be pointed at).
// Results are printed as lines starting with "AUDIT|" and written to app/build/audit-results.txt.
//
// What this can and can't measure: it runs the real app UI (Compose, Robolectric, 960×540 dp TV screen) on the
// build machine's JVM, so times and memory are relative, not Fire TV numbers. Frame drops and input lag are not
// measurable here; node counts, request counts, focus/position restore and memory *trends* are.

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.Playlist
import com.avidviewer.tv.data.Prefs
import com.avidviewer.tv.data.EpgSource
import com.avidviewer.tv.data.Store
import com.avidviewer.tv.ui.AppRoot
import com.avidviewer.tv.ui.AppViewModel
import com.avidviewer.tv.ui.NavMode
import com.avidviewer.tv.ui.PlayContext
import com.avidviewer.tv.ui.Screen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = Application::class)
class ChannelNavProbeTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = StringBuilder()

    private fun report(scenario: String, key: String, value: Any?) {
        val line = "AUDIT|$scenario|$key|$value"
        println(line); out.appendLine(line)
        File("build/audit-results.txt").appendText(line + "\n")
    }

    // ------------------------------------------------------------------ fixture server

    /** Serves the fixture folder; /img/ answers after [imgDelayMs] (so cancellation can be counted). */
    class FixtureServer(private val dir: File, var imgDelayMs: Long = 0) : AutoCloseable {
        val socket = ServerSocket(0)
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val imgCompleted = AtomicInteger(); val imgAborted = AtomicInteger()
        private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 31, 21, -60, -119, 0, 0, 0, 13, 73, 68, 65, 84, 120, -100, 99, 96, 0, 0, 0, 2, 0, 1, 84, -94, 79, 93, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)
        fun count(prefix: String) = counts[prefix]?.get() ?: 0
        fun reset() { counts.clear(); imgCompleted.set(0); imgAborted.set(0) }
        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val c = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        runCatching {
                            c.use { conn ->
                                val r = conn.getInputStream().bufferedReader()
                                val line = r.readLine() ?: return@use
                                while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                                val path = line.split(' ').getOrNull(1) ?: "/"
                                val prefix = "/" + path.trimStart('/').substringBefore('/').substringBefore('?')
                                counts.getOrPut(prefix) { AtomicInteger() }.incrementAndGet()
                                val os = conn.getOutputStream()
                                when {
                                    prefix == "/img" -> {
                                        if (imgDelayMs > 0) Thread.sleep(imgDelayMs)
                                        try {
                                            os.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\nConnection: close\r\n\r\n".toByteArray()); os.write(png); os.flush()
                                            imgCompleted.incrementAndGet()
                                        } catch (e: Exception) { imgAborted.incrementAndGet() }
                                    }
                                    prefix == "/player_api.php" -> {
                                        val action = Regex("action=([a-z_]+)").find(path)?.groupValues?.get(1)
                                        val f = File(dir, "live_streams.json")
                                        if (action == "get_live_streams" && f.isFile) {
                                            os.write("HTTP/1.1 200 OK\r\nContent-Length: ${f.length()}\r\nConnection: close\r\n\r\n".toByteArray())
                                            f.inputStream().use { it.copyTo(os) }; os.flush()
                                        } else { val b = "[]".toByteArray(); os.write("HTTP/1.1 200 OK\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); os.write(b); os.flush() }
                                    }
                                    File(dir, path.trimStart('/')).isFile -> {
                                        val f = File(dir, path.trimStart('/'))
                                        os.write("HTTP/1.1 200 OK\r\nContent-Length: ${f.length()}\r\nConnection: close\r\n\r\n".toByteArray())
                                        f.inputStream().use { it.copyTo(os) }; os.flush()
                                    }
                                    else -> { os.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); os.flush() }
                                }
                            }
                        }
                    }
                }
            }
        }
        override fun close() = socket.close()
    }

    private fun usedMb(): Long {
        repeat(3) { System.gc(); Thread.sleep(40) }
        val r = Runtime.getRuntime()
        return (r.totalMemory() - r.freeMemory()) / (1024 * 1024)
    }

    /** Approximate peak heap growth (MB) while [block] runs: sampled every 2 ms on another thread. */
    private fun <T> peakMb(block: () -> T): Pair<T, Long> {
        repeat(3) { System.gc(); Thread.sleep(40) }
        val r = Runtime.getRuntime()
        val base = r.totalMemory() - r.freeMemory()
        val peak = java.util.concurrent.atomic.AtomicLong(base)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = thread(isDaemon = true) { while (running.get()) { peak.accumulateAndGet(r.totalMemory() - r.freeMemory()) { a, b -> maxOf(a, b) }; Thread.sleep(2) } }
        val res = try { block() } finally { running.set(false); sampler.join() }
        return res to ((peak.get() - base).coerceAtLeast(0L) / (1024 * 1024))
    }

    /** Largest amount of memory still in use right after a collection, while [block] runs (what it holds, not its garbage). */
    private fun <T> peakLiveMb(block: () -> T): Pair<T, Long> {
        repeat(3) { System.gc(); Thread.sleep(40) }
        val r = Runtime.getRuntime()
        val base = r.totalMemory() - r.freeMemory()
        val peak = java.util.concurrent.atomic.AtomicLong(base)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = thread(isDaemon = true) { while (running.get()) { System.gc(); peak.accumulateAndGet(r.totalMemory() - r.freeMemory()) { a, b -> maxOf(a, b) }; Thread.sleep(30) } }
        val res = try { block() } finally { running.set(false); sampler.join() }
        System.gc(); val after = r.totalMemory() - r.freeMemory()
        return res to ((peak.get() - base).coerceAtLeast(0L) / (1024 * 1024))
    }

    // ------------------------------------------------------------------ remote helpers (as in UsabilityTest)

    private lateinit var vm: AppViewModel

    private fun settle(ms: Long = 250) {
        rule.waitForIdle()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
        Thread.sleep(60)
        rule.waitForIdle()
    }

    private fun focused(): String = runCatching {
        rule.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().joinToString(" / ") { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } ?: "(no text)"
        }.ifBlank { "(nothing focused)" }
    }.getOrDefault("(unknown)")

    private fun topWindowView(): android.view.View? {
        val views: List<android.view.View> = runCatching {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            val wmg = cls.getMethod("getInstance").invoke(null)
            (cls.getDeclaredField("mViews").apply { isAccessible = true }.get(wmg) as List<*>).filterIsInstance<android.view.View>()
        }.getOrDefault(emptyList())
        return views.lastOrNull()?.takeIf { it !== rule.activity.window.decorView }
    }

    private fun key(k: Key) {
        val code = k.nativeKeyCode
        rule.runOnUiThread {
            val t = android.os.SystemClock.uptimeMillis()
            val target = topWindowView() ?: rule.activity.window.decorView
            target.dispatchKeyEvent(AndroidKeyEvent(t, t, AndroidKeyEvent.ACTION_DOWN, code, 0))
            target.dispatchKeyEvent(AndroidKeyEvent(t, t + 40, AndroidKeyEvent.ACTION_UP, code, 0))
        }
    }

    private fun press(k: Key) { key(k); settle() }

    private fun waitFor(what: String, ms: Long = 60_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { settle(100); if (cond()) return true; Thread.sleep(50) }
        report("error", "timeout", "$what (focus=${focused()} screen=${vm.screen} nav=${vm.navMode} status=${vm.status})")
        return false
    }

    /** Text nodes on screen (composed) whose text starts with [prefix]. */
    private fun nodes(prefix: String): Int = rule.onAllNodesWithText(prefix, substring = true, useUnmergedTree = true)
        .fetchSemanticsNodes().count { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(prefix) } == true }

    /** Every semantics node currently composed (a proxy for view nodes). */
    private fun allNodes(): Int = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().size


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

    /** Fast presses with little settling, like a person tapping quickly. */
    private fun tap(k: Key, n: Int) { repeat(n) { key(k); rule.waitForIdle(); shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60)) }; settle() }


    /** [n] presses sent back to back with no frame in between (a fast thumb on a real remote). */
    private fun burst(k: Key, n: Int, gapMs: Long = 0) {
        val code = k.nativeKeyCode
        repeat(n) {
            rule.runOnUiThread {
                val target = topWindowView() ?: rule.activity.window.decorView
                val t = android.os.SystemClock.uptimeMillis()
                target.dispatchKeyEvent(AndroidKeyEvent(t, t, AndroidKeyEvent.ACTION_DOWN, code, 0))
                target.dispatchKeyEvent(AndroidKeyEvent(t, t + 5, AndroidKeyEvent.ACTION_UP, code, 0))
            }
            if (gapMs > 0) shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(gapMs))
        }
        settle()
    }

    private fun burstsMixed(seed: Int) {
        val rnd = java.util.Random(seed.toLong())
        repeat(60) {
            val k = if (rnd.nextBoolean()) Key.DirectionDown else Key.DirectionUp
            burst(k, 2 + rnd.nextInt(12), listOf(0L, 0L, 8L, 16L, 33L)[rnd.nextInt(5)])
        }
    }

    private val bg = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun step(name: String, block: () -> Unit) {
        val t0 = System.currentTimeMillis()
        val main = android.os.Looper.getMainLooper().thread
        val testThread = Thread.currentThread()
        val dog = thread(isDaemon = true) {
            try {
                var n = 0
                while (true) {
                    Thread.sleep(if (n == 0) 45_000 else 5_000)
                    n++
                    for (th in listOf(main, testThread).distinct()) report("probe", "$name-stuck-$n-${th.name}", th.stackTrace.take(45).joinToString(" || "))
                    report("probe", "$name-stuck-$n-trail", vm.focusTrail.toList().takeLast(25).joinToString(" "))
                    if (n >= 4) { File("build/audit-results.txt").appendText("giving up\n"); Runtime.getRuntime().halt(3) }
                }
            } catch (_: InterruptedException) {}
        }
        try { block(); report("probe", name, "ok ${System.currentTimeMillis() - t0} ms focus=${focused().take(80)}") }
        catch (e: Throwable) {
            val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
            report("probe", name, "CRASH " + sw.toString().lines().take(40).joinToString(" || "))
        } finally { dog.interrupt() }
        if (bg.isNotEmpty()) { report("probe", "$name-background", bg.joinToString(" ## ")); bg.clear() }
    }

    @Test
    fun channelListUpDown() {
        File("build/audit-results.txt").delete()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
            bg.add("[${t.name}] " + sw.toString().lines().take(30).joinToString(" || "))
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fx = File(app.cacheDir, "fx").apply { mkdirs() }
        val server = FixtureServer(fx)
        val host = "127.0.0.1:${server.socket.localPort}"
        val gen = ProcessBuilder("python3", File("../audit/fixtures/generate.py").absolutePath, fx.absolutePath, "--host", host).redirectErrorStream(true).start()
        gen.inputStream.bufferedReader().readText(); gen.waitFor()
        val pl = Playlist(id = "fx", name = "Stress provider", type = "m3u", m3uUrl = "http://$host/catalog.m3u", providerGuide = false,
            epgSources = listOf(EpgSource("src-fx", "Stress guide", "http://$host/guide.xml.gz")))
        app.filesDir.deleteRecursively(); app.filesDir.mkdirs()
        app.deleteDatabase("catalog.db")
        com.avidviewer.tv.data.EspnFeed.base = "http://127.0.0.1:9/espn/"
        Store(app).savePlaylists(listOf(pl))
        Store(app).savePrefs(Prefs(activePlaylist = "fx", autoUpdateCheck = false))
        vm = AppViewModel(app)
        rule.setContent { AppRoot(vm) }
        rule.runOnUiThread { rule.activity.window.decorView.requestFocus() }
        waitFor("channels") { vm.channels.isNotEmpty() }
        waitFor("guide focus") { focused() != "(nothing focused)" }
        waitFor("guide") { vm.guide.isNotEmpty() }
        report("probe", "loaded", "${vm.channels.size} channels, ${vm.guide.size} guide ids, groups=${vm.groups().take(5).map { it.raw }}")

        val g = "ALL CHANNELS"
        step("open") { rule.runOnIdle { vm.chooseGroup("fx", g) }; waitFor("group", 15_000) { focused() != "(nothing focused)" && vm.selectedGroup == g } }
        step("up-to-preview") { press(Key.DirectionUp) }
        step("up-wraps-to-bottom") { press(Key.DirectionUp) }
        step("after-wrap-idle") { settle(1000) }
        step("up-press-at-bottom") { repeat(5) { press(Key.DirectionUp) } }
        step("down-press-at-bottom") { repeat(8) { press(Key.DirectionDown) } }
        step("reopen") { rule.runOnIdle { vm.chooseGroup("fx", g) }; settle(800) }
        step("burst-up-3") { burst(Key.DirectionUp, 3) }
        step("burst-up-10") { burst(Key.DirectionUp, 10) }
        step("burst-down-10") { burst(Key.DirectionDown, 10) }
        step("burst-mixed") { burstsMixed(1) }
        step("burst-mixed-2") { burstsMixed(7) }
        step("to-programs") { press(Key.DirectionRight) }
        step("programs-burst-mixed") { burstsMixed(2) }
        step("programs-wrap-bursts") { press(Key.DirectionLeft); burst(Key.DirectionUp, 30); burst(Key.DirectionDown, 30); press(Key.DirectionRight); burst(Key.DirectionUp, 30); burst(Key.DirectionDown, 30) }
        step("small-group-burst") {
            val small = vm.groups().minByOrNull { it.count }?.raw ?: "ALL CHANNELS"
            rule.runOnIdle { vm.chooseGroup("fx", small) }; settle(500); burstsMixed(5); press(Key.DirectionRight); burstsMixed(6)
        }
        Thread.setDefaultUncaughtExceptionHandler(prev)
        server.close()
    }
}
