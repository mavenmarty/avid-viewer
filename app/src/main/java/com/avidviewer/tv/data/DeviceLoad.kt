package com.avidviewer.tv.data

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * How busy the TV is, for the playback log (Nov 21, 2026): its free memory, the app's memory, and how much of the
 * processor the app used since the last reading. Nothing about what's playing.
 */
class DeviceLoad(context: Context) {
    private val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private var lastCpuTicks = -1L
    private var lastAt = 0L

    /** "Memory 410 MB free of 1.9 GB (low) · app 180 of 256 MB · app 74% of one core (4 cores)". */
    fun text(): String {
        val parts = ArrayList<String>()
        am?.let { m ->
            val mi = ActivityManager.MemoryInfo(); m.getMemoryInfo(mi)
            parts += "Memory ${mb(mi.availMem)} free of ${mb(mi.totalMem)}" + if (mi.lowMemory) " (low)" else ""
        }
        val rt = Runtime.getRuntime()
        parts += "app ${(rt.totalMemory() - rt.freeMemory()) / MB} of ${rt.maxMemory() / MB} MB"
        cpuPercent()?.let { parts += "app $it% of one core (${rt.availableProcessors()} cores)" }
        return parts.joinToString(" · ")
    }

    private fun cpuPercent(): Int? {
        val ticks = runCatching {
            val f = File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')
            // After the name: state is field 3, so utime (14) and stime (15) are at 11 and 12.
            f[11].toLong() + f[12].toLong()
        }.getOrNull() ?: return null
        val now = android.os.SystemClock.elapsedRealtime()
        val before = lastCpuTicks; val at = lastAt
        lastCpuTicks = ticks; lastAt = now
        if (before < 0 || now <= at) return null
        return ((ticks - before) * 1000.0 / TICKS_PER_SECOND / (now - at) * 100).toInt().coerceAtLeast(0)
    }

    companion object {
        private const val MB = 1024L * 1024
        private const val TICKS_PER_SECOND = 100.0
        fun mb(bytes: Long): String = if (bytes >= 1024 * MB) "%.1f GB".format(java.util.Locale.US, bytes / (1024.0 * MB)) else "${bytes / MB} MB"
    }
}
