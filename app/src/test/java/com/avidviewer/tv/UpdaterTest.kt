package com.avidviewer.tv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.avidviewer.tv.data.Updater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Reported: in-app update hangs around 95% and never installs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdaterTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val apk = ByteArray(3_000_000) { (it * 31 + 7).toByte() }

    /** Serves [apk]. The first request stops sending at 95% and goes silent; later ones honour Range. */
    private fun server(stallFirst: Boolean, rangeSupported: Boolean = true): Pair<ServerSocket, AtomicInteger> {
        val socket = ServerSocket(0)
        val requests = AtomicInteger()
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val c = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    c.use { conn ->
                        val reader = conn.getInputStream().bufferedReader()
                        val lines = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                        val n = requests.incrementAndGet()
                        val from = lines.firstOrNull { it.startsWith("Range:", true) }?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull()
                        val os = conn.getOutputStream()
                        if (from != null && rangeSupported) {
                            val part = apk.copyOfRange(from, apk.size)
                            os.write(("HTTP/1.1 206 Partial Content\r\nContent-Length: ${part.size}\r\nContent-Range: bytes $from-${apk.size - 1}/${apk.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            os.write(part); os.flush()
                        } else {
                            os.write(("HTTP/1.1 200 OK\r\nContent-Length: ${apk.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            if (stallFirst && n == 1) {
                                os.write(apk, 0, apk.size * 95 / 100); os.flush()
                                Thread.sleep(60_000) // the connection stays open but nothing more arrives
                            } else { os.write(apk); os.flush() }
                        }
                    }
                }
            }
        }
        return socket to requests
    }

    @Test
    fun aStalledDownloadResumesAndFinishes() {
        val (socket, requests) = server(stallFirst = true)
        socket.use {
            val notes = mutableListOf<String>()
            var lastPct = 0
            val started = System.currentTimeMillis()
            val f = Updater.download(app, "http://127.0.0.1:${socket.localPort}/AvidViewer.apk", apk.size.toLong(), checkApk = false) { pct, note ->
                lastPct = pct; note?.let { notes.add(it) }
            }
            assertArrayEquals(apk, f.readBytes())
            assertTrue("resumed with a second request", requests.get() >= 2)
            assertTrue("told the viewer it was resuming: $notes", notes.any { it.contains("resuming") })
            assertTrue("reached 100% (was $lastPct)", lastPct == 100)
            assertTrue("recovered within a minute", System.currentTimeMillis() - started < 60_000)
        }
    }

    @Test
    fun aServerWithoutRangeSupportStartsOverAndStillFinishes() {
        val (socket, _) = server(stallFirst = true, rangeSupported = false)
        socket.use {
            val f = Updater.download(app, "http://127.0.0.1:${socket.localPort}/AvidViewer.apk", apk.size.toLong(), checkApk = false) { _, _ -> }
            assertArrayEquals(apk, f.readBytes())
        }
    }

    @Test
    fun aDamagedFileIsRefusedBeforeInstalling() {
        val (socket, _) = server(stallFirst = false)
        socket.use {
            val r = runCatching { Updater.download(app, "http://127.0.0.1:${socket.localPort}/AvidViewer.apk", apk.size.toLong()) { _, _ -> } }
            assertTrue("not an APK, so refused: ${r.exceptionOrNull()?.message}", r.exceptionOrNull()?.message?.contains("damaged") == true)
        }
    }
}
