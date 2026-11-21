package com.avidviewer.tv.data

import android.app.ActivityManager
import android.content.Context

/**
 * Nov 20, 2026 (owner): a lighter start-up on small devices. Fire TV sticks with about 2 GB of memory (AFTGAZL and
 * others on Fire OS 7) were closed by the system with no error, at or just after start-up on the TV Guide, while the
 * guide, channel logos and the preview video all start at once. On these devices the preview starts a few seconds
 * after the guide, the player's buffer is capped and the image memory cache is smaller.
 */
object DeviceClass {
    /** Total memory at or under this counts as small (2 GB devices report about 1.9–2.0 GB). */
    const val SMALL_BYTES = 2_200L * 1024 * 1024

    /** Preview delay at start-up on small devices. */
    const val PREVIEW_DELAY_MS = 3_000L

    /** The player's buffer cap on small devices (the default lets video reach about 130 MB). */
    const val BUFFER_BYTES = 48 * 1024 * 1024

    /** Share of the app's memory for decoded logos and posters (15% elsewhere). */
    const val IMAGE_CACHE_SMALL = 0.08
    const val IMAGE_CACHE = 0.15

    @Volatile private var cached: Boolean? = null

    /** Android says it's a low-RAM device, or it has about 2 GB or less. Unknown memory (tests) counts as not small. */
    fun isSmall(context: Context): Boolean = cached ?: if (android.os.Build.FINGERPRINT == "robolectric") false else runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        am.isLowRamDevice || (mi.totalMem in 1..SMALL_BYTES)
    }.getOrDefault(false).also { cached = it }
}
