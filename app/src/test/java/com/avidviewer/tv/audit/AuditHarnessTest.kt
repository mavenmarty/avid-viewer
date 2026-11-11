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
class AuditHarnessTest {
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

    // ------------------------------------------------------------------ the run

    @Test
    fun stress() {
        File("build/audit-results.txt").delete()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fx = File(app.cacheDir, "fx").apply { mkdirs() }
        val server = FixtureServer(fx)
        val host = "127.0.0.1:${server.socket.localPort}"
        val gen = ProcessBuilder("python3", File("../audit/fixtures/generate.py").absolutePath, fx.absolutePath, "--host", host).redirectErrorStream(true).start()
        report("fixtures", "generator", gen.inputStream.bufferedReader().readText().trim()); gen.waitFor()

        // ---- isolated parser costs (no UI) ----
        val pl = Playlist(id = "fx", name = "Stress provider", type = "m3u", m3uUrl = "http://$host/catalog.m3u", providerGuide = false,
            epgSources = listOf(EpgSource("src-fx", "Stress guide", "http://$host/guide.xml.gz")))
        runCatching {
            val vodOut = ArrayList<com.avidviewer.tv.data.VodItem>()
            val t0 = System.nanoTime()
            val (chs, peak) = peakMb { com.avidviewer.tv.data.M3u.channels(pl, vodOut) }
            report("parse", "m3u_40k_entries_ms", (System.nanoTime() - t0) / 1_000_000)
            report("parse", "m3u_peak_heap_mb", peak)
            report("parse", "m3u_live_vod", "${chs.size} live, ${vodOut.size} vod")
            // Catalog step 2: the same file streamed straight into the database (what the app now does).
            val cat = com.avidviewer.tv.data.Catalog(app)
            val t2 = System.nanoTime()
            val (ing, speak) = peakMb { cat.replacing("fx-stream", { false }) { add -> com.avidviewer.tv.data.M3u.read(pl, add) } }
            report("parse", "m3u_stream_to_catalog_ms", (System.nanoTime() - t2) / 1_000_000)
            report("parse", "m3u_stream_to_catalog_peak_heap_mb", speak)
            report("parse", "m3u_stream_to_catalog_titles", ing.added)
            cat.delete("fx-stream")
            val (_, jpeak) = peakMb { org.json.JSONArray(File(fx, "live_streams.json").readText()).length() }
            report("parse", "xtream_live_10k_JSONArray_peak_heap_mb", jpeak)
            report("parse", "xtream_live_10k_json_mb", File(fx, "live_streams.json").length() / (1024 * 1024.0))
            val xp = Playlist(id = "xt", name = "Xtream stress", type = "xtream", server = "http://$host", username = "u", password = "p")
            val (xchs, xpeak) = peakMb { com.avidviewer.tv.data.Xtream.liveChannels(xp, "ts") }
            report("parse", "xtream_live_10k_app_peak_heap_mb", xpeak)
            report("parse", "xtream_live_10k_app_channels", xchs.size)
            val store = Store(app)
            val (_, spk) = peakMb { store.saveVod("fx-probe", vodOut); store.loadVod("fx-probe").size }
            report("parse", "store_vod_30k_save_load_peak_heap_mb", spk)
            val wanted = chs.mapNotNull { it.epgId }.toSet()
            val t1 = System.nanoTime()
            val (g, gpk) = peakMb { com.avidviewer.tv.data.Xmltv.read("http://$host/guide.xml.gz", wanted, System.currentTimeMillis() - 3 * 3600_000L, System.currentTimeMillis() + 36 * 3600_000L) }
            report("parse", "xmltv_7d_500ch_ms", (System.nanoTime() - t1) / 1_000_000)
            report("parse", "xmltv_peak_heap_mb", gpk)
            report("parse", "xmltv_programmes_kept", g.values.sumOf { it.size })
        }.onFailure { report("parse", "error", it.toString()) }

        // ---- S1 cold start ----
        app.filesDir.deleteRecursively(); app.filesDir.mkdirs()
        Store(app).savePlaylists(listOf(pl))
        Store(app).savePrefs(Prefs(activePlaylist = "fx", autoUpdateCheck = false)) // the update prompt would take the highlight mid-run (logged as an issue)
        server.reset()
        val heap0 = usedMb()
        val tStart = System.currentTimeMillis()
        vm = AppViewModel(app)
        rule.setContent { AppRoot(vm) }
        rule.runOnUiThread { rule.activity.window.decorView.requestFocus() }
        waitFor("channels") { vm.channels.isNotEmpty() }
        report("S1_cold_start", "first_channels_ms", System.currentTimeMillis() - tStart)
        waitFor("guide focus") { focused() != "(nothing focused)" }
        report("S1_cold_start", "first_interactive_ms", System.currentTimeMillis() - tStart)
        waitFor("guide") { vm.guide.isNotEmpty() }
        report("S1_cold_start", "guide_ready_ms", System.currentTimeMillis() - tStart)
        report("S1_cold_start", "channels_in_memory", vm.channels.size)
        report("S1_cold_start", "programmes_in_memory", vm.guide.values.sumOf { it.size })
        report("S1_cold_start", "heap_delta_mb", usedMb() - heap0)
        report("S1_cold_start", "requests", server.counts.mapValues { it.value.get() })
        report("S1_cold_start", "focused", focused())
        report("S1_cold_start", "semantics_nodes", allNodes())

        // ---- per-call costs the guide pays on every recomposition (each highlight move) ----
        runCatching {
            fun ms(n: Int, f: () -> Unit): Double { val t = System.nanoTime(); repeat(n) { f() }; return (System.nanoTime() - t) / 1e6 / n }
            report("recompose_costs", "numbers()_ms", "%.2f".format(ms(20) { vm.numbers() }))
            report("recompose_costs", "channelsIn(8000)_ms", "%.2f".format(ms(20) { vm.channelsIn("ALL CHANNELS") }))
            val k = vm.channels.last().key
            report("recompose_costs", "channel(key)_linear_ms", "%.3f".format(ms(200) { vm.channel(k) }))
            report("recompose_costs", "groups()_ms", "%.2f".format(ms(20) { vm.groups() }))
        }.onFailure { report("recompose_costs", "error", it.toString()) }

        // ---- S6 guide grid (ALL CHANNELS: 8,000 rows; first 500 have 7-day listings) ----
        runCatching {
            server.reset()
            rule.runOnIdle { vm.chooseGroup("fx", "ALL CHANNELS") }
            waitFor("all channels") { focused().contains("CH 0001") }
            report("S6_guide", "rows_in_category", vm.channelsIn("ALL CHANNELS").size)
            report("S6_guide", "program_cells_rendered", nodes("Prog "))
            report("S6_guide", "channel_rows_rendered", nodes("CH "))
            report("S6_guide", "semantics_nodes", allNodes())
            press(Key.DirectionRight) // onto programs
            val beforeH = vm.guide.values.sumOf { it.size }
            repeat(24) { key(Key.DirectionRight); settle(60) } // forward through the 12 h window
            report("S6_guide", "after_24_right_focus", focused())
            report("S6_guide", "furthest_ahead_reached", runCatching { rule.onAllNodesWithText("◀", substring = true).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }.getOrNull())
            report("S6_guide", "program_cells_rendered_after_right", nodes("Prog "))
            repeat(40) { key(Key.DirectionDown); settle(60) }
            report("S6_guide", "after_40_down_focus", focused())
            report("S6_guide", "program_cells_rendered_after_down", nodes("Prog "))
            report("S6_guide", "guide_downloads_during_scroll", server.count("/guide.xml.gz"))
            report("S6_guide", "programmes_held_before_after", "$beforeH / ${vm.guide.values.sumOf { it.size }}")
        }.onFailure { report("S6_guide", "error", it.toString()) }

        // ---- S4 three levels deep and back x50, from channel 2,400 of 8,000 ----
        runCatching {
            val list = vm.channelsIn("ALL CHANNELS")
            val target = list[2399]
            val name = target.name.take(12)
            var ok = IntArray(4); val heaps = ArrayList<Long>()
            for (i in 1..50) {
                // level 3: the channel full screen and back
                rule.runOnIdle { vm.selectChannel(target.key, PlayContext.GROUP); vm.openFullScreen(target.key, PlayContext.GROUP) }
                settle()
                rule.runOnIdle { vm.closeFullScreen() }
                if (waitFor("back from full screen", 8_000) { focused().contains(name) }) ok[0]++
                // level 2: Back to the category list
                press(Key.Back)
                if (vm.inGroupNav && focused().contains("ALL CHANNELS")) ok[1]++
                // level 1: Back to the playlist list
                press(Key.Back)
                if (vm.navMode == NavMode.COLLAPSED && focused().contains("ALL CHANNELS")) ok[2]++
                // and in again (OK on the same category): are we back on channel 2,400?
                press(Key.DirectionCenter)
                settle(400)
                if (focused().contains(name)) ok[3]++
                if (i % 10 == 0) heaps.add(usedMb())
            }
            report("S4_depth_x50", "restored_after_fullscreen", "${ok[0]}/50")
            report("S4_depth_x50", "restored_category_after_back", "${ok[1]}/50")
            report("S4_depth_x50", "restored_in_playlist_list_after_back", "${ok[2]}/50")
            report("S4_depth_x50", "restored_channel_after_reentering", "${ok[3]}/50")
            report("S4_depth_x50", "last_focus", focused())
            report("S4_depth_x50", "heap_mb_every_10", heaps)
        }.onFailure { report("S4_depth_x50", "error", it.toString()) }

        // ---- S2 open the largest movie category (15,500) ----
        runCatching {
            server.reset(); server.imgDelayMs = 0
            val h0 = usedMb(); val t0 = System.currentTimeMillis()
            rule.runOnIdle { vm.navSelect(Screen.MOVIES) }
            waitFor("movies loaded") { vm.vod.size >= 30000 }
            report("S2_largest", "vod_ready_ms", System.currentTimeMillis() - t0)
            report("S2_largest", "vod_in_memory", vm.vod.size)
            val t1 = System.currentTimeMillis()
            rule.runOnIdle { vm.chooseVodCategory("movie", "VOD | EN - Movies 4K") }
            waitFor("posters") { nodes("4K · ") > 0 }
            report("S2_largest", "first_items_ms", System.currentTimeMillis() - t1)
            report("S2_largest", "items_in_category", vm.vodItems("movie").size)
            report("S2_largest", "poster_nodes_rendered", nodes("4K · "))
            report("S2_largest", "semantics_nodes", allNodes())
            report("S2_largest", "heap_delta_mb", usedMb() - h0)
            settle(800)
            report("S2_largest", "image_requests_on_open", server.count("/img"))
            fun ms(n: Int, f: () -> Unit): Double { val t = System.nanoTime(); repeat(n) { f() }; return (System.nanoTime() - t) / 1e6 / n }
            report("recompose_costs", "vodOf+vodCategories+vodItems_ms", "%.2f".format(ms(10) { vm.vodOf("movie"); vm.vodCategories("movie"); vm.vodItems("movie") }))
        }.onFailure { report("S2_largest", "error", it.toString()) }

        // ---- S3 hold Down for 10 s in the largest list ----
        runCatching {
            server.reset(); server.imgDelayMs = 150
            rule.onAllNodesWithText("4K · ", substring = true).onFirst().requestFocus()
            settle()
            val code = Key.DirectionDown.nativeKeyCode
            val t = android.os.SystemClock.uptimeMillis()
            val t0 = System.currentTimeMillis()
            var maxStep = 0L
            for (r in 0..200) { // 10 s of remote auto-repeat at 50 ms
                val s = System.nanoTime()
                rule.runOnUiThread { (topWindowView() ?: rule.activity.window.decorView).dispatchKeyEvent(AndroidKeyEvent(t, t + r * 50L, AndroidKeyEvent.ACTION_DOWN, code, r)) }
                shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
                rule.waitForIdle()
                maxStep = maxOf(maxStep, (System.nanoTime() - s) / 1_000_000)
                Thread.sleep(10)
            }
            rule.runOnUiThread { (topWindowView() ?: rule.activity.window.decorView).dispatchKeyEvent(AndroidKeyEvent(t, t + 10_100, AndroidKeyEvent.ACTION_UP, code, 0)) }
            settle(600)
            report("S3_hold_down_10s", "wall_ms", System.currentTimeMillis() - t0)
            report("S3_hold_down_10s", "slowest_key_handling_ms", maxStep)
            report("S3_hold_down_10s", "focus_after", focused().take(80))
            report("S3_hold_down_10s", "image_requests", server.count("/img"))
            report("S3_hold_down_10s", "image_completed_aborted", "${server.imgCompleted.get()} / ${server.imgAborted.get()}")
            report("S3_hold_down_10s", "poster_nodes_rendered", nodes("4K · "))
        }.onFailure { report("S3_hold_down_10s", "error", it.toString()) }

        // ---- S5 rapid category switching x50 ----
        runCatching {
            server.reset(); server.imgDelayMs = 300
            val cats = vm.vodCategories("movie").map { it.first }.take(10)
            val heaps = ArrayList<Long>()
            for (i in 1..50) {
                rule.runOnIdle { vm.chooseVodCategory("movie", cats[i % cats.size]) }
                settle(60)
                if (i % 10 == 0) heaps.add(usedMb())
            }
            settle(1200)
            report("S5_switch_x50", "image_requests", server.count("/img"))
            report("S5_switch_x50", "image_completed_aborted", "${server.imgCompleted.get()} / ${server.imgAborted.get()}")
            report("S5_switch_x50", "heap_mb_every_10", heaps)
        }.onFailure { report("S5_switch_x50", "error", it.toString()) }

        // ---- S2b global search across 40,000 items ----
        runCatching {
            rule.runOnIdle { vm.navSelect(Screen.SEARCH) }; settle()
            val t0 = System.currentTimeMillis()
            rule.runOnIdle { vm.search("ocean") }
            waitFor("search") { vm.searchResults != null && !vm.searching }
            report("S2b_search", "search_ms", System.currentTimeMillis() - t0)
            report("S2b_search", "hits", vm.searchResults?.sumOf { it.stations.size + it.programs.size + it.movies.size + it.series.size })
        }.onFailure { report("S2b_search", "error", it.toString()) }

        // ---- S7 settings: every section, in and back out (Right/Left and Right/Back) ----
        runCatching {
            rule.runOnIdle { vm.navSelect(Screen.SETTINGS) }
            waitFor("settings") { focused().contains("Playlists") }
            val titles = listOf("playlists" to "Playlists", "channels" to "List Editor", "location" to "Location & weather", "display" to "Display & time",
                "guide" to "TV guide", "sports" to "Sports tab", "discover" to "Discover", "playback" to "Playback", "privacy" to "Privacy & analytics",
                "storage" to "Storage", "about" to "Updates & about")
            for ((id, title) in titles) {
                for (exitKey in listOf(Key.DirectionLeft, Key.Back)) {
                    rule.runOnIdle { vm.navSelect(Screen.SETTINGS); vm.settingsSection = id }
                    settle()
                    runCatching { rule.onAllNodesWithText(title, substring = true).onFirst().requestFocus() }
                    settle()
                    val start = focused().take(30)
                    press(Key.DirectionRight)
                    val inside = focused().take(40)
                    val collapsed = rule.onAllNodesWithText("Settings", substring = false).fetchSemanticsNodes().size <= 1 // only the main-menu label left
                    // Down x30 inside: does the highlight stay in the section?
                    repeat(30) { key(Key.DirectionDown); settle(40) }
                    val afterDown = "${vm.settingsSection}|${focused().take(30)}"
                    press(exitKey)
                    val after = "${vm.screen}/${vm.settingsSection}|${focused().take(30)}|nav=${vm.navMode}"
                    report("S7_settings", "$id via ${if (exitKey == Key.Back) "Back" else "Left"}",
                        "start='$start' right='$inside' navCollapsed=$collapsed down30='$afterDown' exit='$after'")
                }
            }
        }.onFailure { report("S7_settings", "error", it.toString()) }

        report("done", "heap_end_mb", usedMb())
        server.close()
    }
}
