package com.avidviewer.tv.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Saves the details of a crash on the device so the next launch can say what happened.
 * It stays in the app's private storage until it's cleared, or sent with "Report issue" ([CrashReport]),
 * which also clears it.
 *
 * Hard closes (1.6.1, #16): when the TV closes the app to free memory, or it stops in native code or
 * stops responding, nothing in the app runs, so nothing was saved and the viewer was just sent to the
 * Fire TV home screen. While the app is on screen a small marker file is kept ([inUse]) with the last
 * few steps (screen names, "Search: 2 words", never what was searched or watched) and the memory in use;
 * it's removed when the viewer leaves the app ([leftNormally]). If the next launch still finds it, the app
 * closed while in use: [adoptUncleanExit] turns it into a crash report, with the reason Android gives
 * (Fire OS 8 and later), so the usual "closed unexpectedly" dialog and Report issue cover it too.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")
    private fun session(context: Context) = File(context.filesDir, "session.txt")

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "crash-log").apply { isDaemon = true } }
    /** Memory set aside so that, after an out-of-memory error, there's room to write the report. */
    @Volatile private var reserve: ByteArray? = null
    @Volatile private var version = ""
    /** For tests: the version the marker is written with and compared against. */
    internal var runningVersion: String
        get() = version
        set(v) { version = v }
    @Volatile private var active = false
    private val steps = ArrayDeque<String>()

    private fun stamp(ms: Long = System.currentTimeMillis()) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
    private fun clock(ms: Long = System.currentTimeMillis()) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
    private fun deviceLine() = "Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"

    fun install(context: Context, version: String) {
        val app = context.applicationContext
        this.version = version
        reserve = ByteArray(256 * 1024)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            reserve = null // room to work after an OutOfMemoryError
            runCatching {
                // The first line of the error is written first: if the rest fails, that much is kept.
                val head = "Avid Viewer $version · ${stamp()}\n${deviceLine()}\nThread: ${thread.name}\n\n"
                file(app).writeText(head + e.javaClass.name + ": " + (e.message ?: "") + "\n")
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file(app).writeText(head + sw.toString().take(12_000) + "\n" + trail(app))
            }
            runCatching { session(app).delete() }
            previous?.uncaughtException(thread, e)
        }
    }

    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) { file(context).delete() }

    // ---------------------------------------------------------------- hard closes

    /** The app is on screen (onStart): keep the marker until [leftNormally]. */
    fun inUse(context: Context) {
        active = true
        note(context, "App on screen")
    }

    /** The viewer left the app (onStop): an end the system makes later is not a crash. */
    fun leftNormally(context: Context) {
        active = false
        val app = context.applicationContext
        writer.execute { runCatching { session(app).delete() } }
    }

    /** One step worth knowing if the app is closed (a screen, "Search: 2 words"). Never names or searches. */
    fun note(context: Context, step: String) {
        synchronized(steps) {
            steps.addLast("${clock()} $step")
            while (steps.size > 10) steps.removeFirst()
        }
        save(context)
    }

    // ---------------------------------------------------------------- #20: main-thread watchdog
    private var watch: Thread? = null
    @Volatile private var beatAt = 0L

    /**
     * While the app is on screen: every second a beat is posted to the main thread. If it hasn't run for 4 s, "Main
     * thread blocked N s on <screen>" goes into the marker (at 4, 10 and 30 s), so a close while the app had stopped
     * responding says so. [screen] is read on the watchdog's thread.
     */
    fun watchMainThread(context: Context, screen: () -> String) {
        if (watch?.isAlive == true) return
        val app = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        beatAt = android.os.SystemClock.uptimeMillis()
        watch = Thread({
            var noted = 0
            var blockedOn = ""
            while (active && !Thread.currentThread().isInterrupted) {
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
                main.post { beatAt = android.os.SystemClock.uptimeMillis() }
                val late = android.os.SystemClock.uptimeMillis() - beatAt
                if (late >= 4_000) {
                    if (noted == 0) blockedOn = runCatching(screen).getOrDefault("?")
                    val mark = listOf(4_000L, 10_000L, 30_000L).count { late >= it }
                    if (mark > noted) {
                        noted = mark
                        note(app, "Main thread blocked ${late / 1000} s on $blockedOn")
                        // #20: where it's stuck (release builds are obfuscated: read with the build's R8 mapping).
                        if (mark == 1) runCatching { note(app, "  at " + stackLine(android.os.Looper.getMainLooper().thread.stackTrace)) }
                    }
                } else if (noted > 0) noted = 0
            }
        }, "avid-watchdog").apply { isDaemon = true; start() }
    }
    fun stopWatching() { watch?.interrupt(); watch = null }

    /**
     * The top of a stack in one line: the first 4 frames, then up to 6 of the app's own (com.avidviewer, or the
     * obfuscated short names R8 gives them), so the step list stays short.
     */
    fun stackLine(stack: Array<StackTraceElement>): String {
        fun f(e: StackTraceElement) = e.className.substringAfterLast('.') + "." + e.methodName + ":" + e.lineNumber
        val top = stack.take(4)
        val own = stack.drop(4).filter { it.className.startsWith("com.avidviewer") || !it.className.contains('.') }.take(6)
        return (top.map(::f) + (if (own.isEmpty()) emptyList() else listOf("…") + own.map(::f))).joinToString(" < ").take(600)
    }

    /** Refreshes the memory figures in the marker (every 30 s while on screen). */
    fun tick(context: Context) = save(context)

    private fun save(context: Context) {
        if (!active) return
        val app = context.applicationContext
        val text = "Avid Viewer $version · last seen ${stamp()}\n" + trail(app)
        writer.execute { runCatching { if (active) session(app).writeText(text) } }
    }

    /** The last steps and the memory now, as report lines. */
    private fun trail(app: Context): String {
        val lines = synchronized(steps) { steps.toList() }
        return "Last steps:\n" + lines.joinToString("\n") { "  $it" } + "\n" + memoryLine(app) + "\n"
    }

    fun memoryLine(app: Context): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
        val maxMb = rt.maxMemory() / 1_048_576
        val tv = runCatching {
            val mi = ActivityManager.MemoryInfo()
            (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            "; TV free ${mi.availMem / 1_048_576} of ${mi.totalMem / 1_048_576} MB" + if (mi.lowMemory) " (low)" else ""
        }.getOrDefault("")
        return "Memory: app ${usedMb} of $maxMb MB$tv"
    }

    /**
     * At launch: the marker is still there, so the app closed while on screen. Unless a crash was saved
     * (which says more), it becomes the saved crash report. Returns true when one was made.
     */
    fun adoptUncleanExit(context: Context): Boolean {
        val s = session(context)
        if (!s.exists()) return false
        val marker = runCatching { s.readText() }.getOrDefault("")
        val markerAt = s.lastModified()
        s.delete()
        if (file(context).exists()) return false
        // Nov 17 (AVR-b5a685ae, 1.15.2 → 1.16.0): an update installed while the app was on screen ends the old version
        // without onStop. A marker left by another version means the app was updated, not that it crashed.
        // Nov 17 (AVR-9276c15f, 1.17.0 → 1.17.1): this runs from the Application, before MainActivity sets [version],
        // so the version is read here; and an install after the marker's last tick is an update too.
        val now = version.ifEmpty { installedVersion(context) }
        val was = Regex("^Avid Viewer (\\S+) ·").find(marker.lineSequence().firstOrNull().orEmpty())?.groupValues?.get(1)
        if (was != null && now.isNotEmpty() && was != now) return false
        if (updatedAfter(context, markerAt)) return false
        val reason = exitReason(context, markerAt)
        if (reason?.userStopped == true) return false // force-stopped in Settings: not a crash
        val text = marker.lineSequence().firstOrNull().orEmpty().ifBlank { "Avid Viewer" } + "\n" +
            deviceLine() + "\n\n" +
            "Error: Avid Viewer closed while in use; no error reached the app.\n" +
            "Likely: " + (reason?.text ?: "the TV closed it to free memory (this Fire OS doesn't say why)") + "\n" +
            (reason?.detail?.let { "$it\n" } ?: "") + "\n" +
            marker.lines().drop(1).joinToString("\n")
        runCatching { file(context).writeText(text.take(12_000)) }
        return true
    }

    private fun installedVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    /** The app was installed (updated) after [markerAt], the marker's last write. */
    private fun updatedAfter(context: Context, markerAt: Long): Boolean = markerAt > 0 && runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime > markerAt
    }.getOrDefault(false)

    private class Exit(val text: String, val detail: String?, val userStopped: Boolean)

    /** Why Android ended the app last time (Android 11 / Fire OS 8 and later). */
    private fun exitReason(context: Context, markerAt: Long = 0L): Exit? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val e = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
            // #20 (AVR-4b1a6270): only the exit of the run that left the marker, not an earlier process's.
            if (markerAt > 0 && e.timestamp < markerAt - 5_000) return null
            val text = when (e.reason) {
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "the TV closed it to free memory"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "the TV closed it for using too much memory or power"
                android.app.ApplicationExitInfo.REASON_ANR -> "it stopped responding, and the TV closed it"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "a crash in the video or graphics code"
                android.app.ApplicationExitInfo.REASON_CRASH -> "a crash"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "the system stopped it"
                android.app.ApplicationExitInfo.REASON_OTHER -> "the system closed it without saying why (Fire OS often does this to free memory for the app in front)"
                else -> "closed by the system (reason ${e.reason})"
            }
            val user = e.reason == 15 /* REASON_PACKAGE_STATE_CHANGE */ ||
                e.reason == 16 /* REASON_PACKAGE_UPDATED (Android 14) */ ||
                e.reason == android.app.ApplicationExitInfo.REASON_USER_REQUESTED ||
                e.reason == android.app.ApplicationExitInfo.REASON_USER_STOPPED
            // #33: whether the app was on screen when it was closed, and (when it had stopped responding) where the
            // screen's thread was stuck, from Android's own record.
            val where = when {
                e.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "on screen"
                e.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible behind another app"
                e.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "behind another app"
                else -> "in the background (importance ${e.importance})"
            }
            val trace = if (e.reason == android.app.ApplicationExitInfo.REASON_ANR) runCatching {
                e.traceInputStream?.bufferedReader()?.use { anrMainThread(it.readText()) }
            }.getOrNull() else null
            val detail = "Exit: ${stamp(e.timestamp)}, $where, memory at exit ${e.pss / 1024} MB, reason ${e.reason}, status ${e.status}" +
                (e.description?.let { " · " + it.take(200) } ?: "") +
                (trace?.let { "\nScreen thread when it stopped responding:\n$it" } ?: "")
            Exit(text, detail, user)
        }.getOrNull()
    }

    /** From an ANR trace file: the "main" thread's first 25 lines (the stack where the screen's thread was stuck). */
    fun anrMainThread(trace: String): String? {
        val lines = trace.lines()
        val start = lines.indexOfFirst { it.startsWith("\"main\"") }.takeIf { it >= 0 } ?: return null
        val out = ArrayList<String>()
        for (l in lines.drop(start)) { if (out.isNotEmpty() && l.isBlank()) break; out.add(l.trimEnd()) ; if (out.size >= 25) break }
        return out.joinToString("\n") { "  " + it.trim() }.take(3_000)
    }

}
