package com.avidviewer.tv.ui

/**
 * Where the highlight and the scroll were, per place in the app ("guide/<playlist>/<category>",
 * "vod/<kind>/<category>|<search>"), so coming back lands on the same item even after the list
 * changed (menu audit work plan 2.1: NAV-01, NAV-04, NAV-05, GUIDE-02).
 *
 * Positions are kept by item key. When the key is gone (hidden, removed, refreshed), the nearest
 * item by the old position is used, never the top.
 */
class NavStore {
    /** The focused item (key + where it was) and the first visible item (key + where it was + pixel offset). */
    data class Spot(val key: String? = null, val index: Int = 0, val firstKey: String? = null, val firstIndex: Int = 0, val offset: Int = 0)

    private val spots = HashMap<String, Spot>()

    operator fun get(path: String): Spot? = spots[path]

    fun has(path: String) = path in spots

    fun focused(path: String, key: String, index: Int) {
        spots[path] = (spots[path] ?: Spot()).copy(key = key, index = index)
    }

    fun scrolled(path: String, firstKey: String?, firstIndex: Int, offset: Int) {
        spots[path] = (spots[path] ?: Spot()).copy(firstKey = firstKey, firstIndex = firstIndex, offset = offset)
    }

    fun forget(path: String) { spots.remove(path) }

    // ---- the TV Guide's time (one for the whole guide, like a cable box) ----

    /** Start of the guide's visible window (0 = now). */
    var guideWindowStart = 0L
    /** The time the highlight is on, for Up/Down between rows (0 = now). */
    var guideAnchor = 0L
    /** Whether the highlight was on a programme (true) or a channel name (false) when the guide was left. */
    var guideOnProgram = false

    companion object {
        /**
         * Where [key] is now in a list of [size] items read by [keyAt]: at [index] if still there,
         * else the nearest place within [radius] of it, else [index] clamped (the nearest neighbour).
         * Returns -1 for an empty list.
         */
        fun find(size: Int, keyAt: (Int) -> String?, key: String?, index: Int, radius: Int = size): Int {
            if (size <= 0) return -1
            val at = index.coerceIn(0, size - 1)
            if (key == null || keyAt(at) == key) return at
            for (d in 1..radius) {
                val below = at + d; val above = at - d
                if (below >= size && above < 0) break
                if (above >= 0 && keyAt(above) == key) return above
                if (below < size && keyAt(below) == key) return below
            }
            return at
        }
    }
}
