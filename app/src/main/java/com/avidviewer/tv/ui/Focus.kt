package com.avidviewer.tv.ui

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.boundsInWindow

/**
 * Puts the highlight somewhere as soon as it can hold (menu audit work plan 2.2, FOCUS-01): asks
 * once per frame, for up to [frames] frames (~2 s), because on a slow device the target may not be
 * laid out yet. A button press in the meantime wins: the user has moved on, so this stops.
 *
 * [request] asks for focus and says whether the request went through. With [isFocused], it
 * keeps going until the target has actually kept the highlight for a few frames (something else
 * may grab it right after); without it, the first request that goes through after the first frame ends it.
 * Returns whether the highlight landed.
 */
suspend fun focusWhenReady(
    vm: AppViewModel? = null,
    frames: Int = 120,
    isFocused: (() -> Boolean)? = null,
    request: () -> Boolean,
): Boolean {
    val started = android.os.SystemClock.uptimeMillis()
    var held = 0
    for (frame in 0 until frames) {
        withFrameNanos { }
        if (vm != null && vm.lastKeyAt > started) return false
        if (isFocused != null) {
            if (isFocused()) { if (++held >= 12) return true; continue }
            held = 0
            request()
        } else if (request() && frame > 0) return true
    }
    return isFocused?.invoke() ?: false
}

/**
 * Where the highlight goes when the item that had it disappears (navigation audit, Nov 15; BUG-036).
 *
 * Every [Focusable] registers here with its place on screen. When the highlighted one is removed (a hidden
 * category, a deleted list, a poster taken off My List, a button that no longer applies), nothing on screen
 * has the highlight. Before, the next button press put it on the first item on screen (the main menu or
 * ‹ Menu). Now [recover] gives it at once to the item nearest to where it was, preferring the same column:
 * usually the item that took its place.
 */
object FocusSpots {
    class Spot {
        val requester = androidx.compose.ui.focus.FocusRequester()
        var coords: androidx.compose.ui.layout.LayoutCoordinates? = null
        /** The window this item is drawn in (dialogs have their own). */
        var root: android.view.View? = null
        var focused = false
    }

    private val spots = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Spot, Boolean>())
    /** The highlighted item in the app's window, and where it was (window coordinates). */
    private var current: java.lang.ref.WeakReference<Spot>? = null
    private var currentBounds: androidx.compose.ui.geometry.Rect? = null
    private var currentScreen: Screen? = null
    /** Where the highlighted item was when it disappeared, on which screen, and when. */
    var lostBounds: androidx.compose.ui.geometry.Rect? = null; private set
    var lostScreen: Screen? = null; private set
    var lostAt = 0L; private set

    /** The screen on show (set by AppRoot): a highlight lost on one screen isn't given back on another. */
    @Volatile var screenNow: Screen? = null
    /** The app's own window (set by AppRoot); highlights in dialogs, which have their own windows, aren't tracked. */
    var mainRoot: java.lang.ref.WeakReference<android.view.View>? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /** What AppRoot does once a highlighted item has gone (given the time it went). */
    var onLost: (Long) -> Unit = {}

    /** Diagnostics (AppViewModel.trail). */
    var log: (String) -> Unit = {}

    fun add(s: Spot) = synchronized(spots) { spots.add(s) }

    /** The item that had the highlight before [current], and when [current] took it. */
    private var previous: java.lang.ref.WeakReference<Spot>? = null
    private var previousBounds: androidx.compose.ui.geometry.Rect? = null
    private var previousScreen: Screen? = null
    private var currentSince = 0L

    /** A new app window (the app restarted in the same process, as in tests): forget everything. */
    fun reset() {
        current = null; currentBounds = null; currentScreen = null
        previous = null; previousBounds = null; previousScreen = null
        lostBounds = null; lostScreen = null; lostAt = 0L
        typing = false
    }

    /**
     * [s] left the screen; if it had the highlight, remember where, so its neighbour can take it. Also when it
     * had the highlight a moment ago: Android may hand the highlight to the first item on screen as a dialog
     * closes, just before the item the dialog removed goes.
     */
    fun remove(s: Spot) {
        synchronized(spots) { spots.remove(s) }
        val justBefore = previous?.get() === s && android.os.SystemClock.uptimeMillis() - currentSince < 600
        if (current?.get() === s || justBefore) {
            lostBounds = if (justBefore) previousBounds else currentBounds
            lostScreen = if (justBefore) previousScreen else currentScreen
            lostAt = android.os.SystemClock.uptimeMillis()
            current = null
            log("lost ${lostBounds?.let { "${it.left.toInt()},${it.top.toInt()}" }} on $lostScreen")
            // Give whatever else is about to place the highlight a moment, then check (AppRoot's onLost).
            val at = lostAt
            handler.postDelayed({ onLost(at) }, 260)
        }
    }

    /** [s] has the highlight (or moved while it has it): remember where it is. */
    fun focused(s: Spot) {
        val c = s.coords ?: return
        if (!c.isAttached) return
        val main = mainRoot?.get()
        if (main != null && s.root !== main) return
        val bounds = runCatching { c.boundsInWindow() }.getOrNull() ?: return
        if (current?.get() !== s) {
            previous = current; previousBounds = currentBounds; previousScreen = currentScreen
            currentSince = android.os.SystemClock.uptimeMillis()
        }
        currentBounds = bounds
        current = java.lang.ref.WeakReference(s)
        currentScreen = screenNow
    }

    /** A text field is being typed in (its keyboard open): never taken away from it. */
    @Volatile var typing = false

    /** Whether one of the app window's items has the highlight now. */
    fun hasCurrent() = current?.get()?.focused == true

    private fun boundsOf(s: Spot) = runCatching { s.coords!!.boundsInWindow() }.getOrNull()
    private fun sameColumn(a: androidx.compose.ui.geometry.Rect, b: androidx.compose.ui.geometry.Rect) = a.left < b.right && a.right > b.left

    /**
     * Gives the highlight to the item nearest to where the lost one was, in the app's window: same column first
     * (overlapping left and right edges), by vertical distance; then anything, by distance. Returns whether one
     * took it.
     *
     * When something already has the highlight (Android puts it on the first item on screen when a dialog
     * closes onto nothing), it's moved only if that item is in another column and an item in the lost one's
     * column is there to take it: a deliberate landing nearby (the next channel) stays.
     */
    fun recover(somethingFocused: Boolean = false): Boolean {
        val b = lostBounds ?: return false
        val root = mainRoot?.get() ?: return false
        val candidates = synchronized(spots) { spots.filter { it.root === root && it.coords?.isAttached == true } }
            .mapNotNull { s -> boundsOf(s)?.takeIf { it.width > 1f && it.height > 1f }?.let { s to it } }
        if (candidates.isEmpty()) return false
        val now = current?.get()?.takeIf { it.focused }?.let { boundsOf(it) }
        log("recover from ${b.left.toInt()},${b.top.toInt()} now=${now?.let { "${it.left.toInt()},${it.top.toInt()}" }} other=$somethingFocused")
        if (now != null || somethingFocused) {
            // Something already has the highlight. Android's own choice when a dialog closes onto nothing is the
            // first item in the window, at the top (the main menu, ‹ Menu): that is replaced. Anything else was
            // put there on purpose (the next channel, the button that opened a form) and stays.
            // Something that isn't one of these items has it (a guide row, a text field): it was put there on purpose.
            if (now == null) return false
            else {
                val windowHeight = root.height.toFloat().takeIf { it > 0f } ?: return false
                val atTop = now.top < windowHeight * 0.25f
                if (!atTop) return false
                if (candidates.none { sameColumn(it.second, b) && it.second != now }) return false
            }
        }
        fun score(r: androidx.compose.ui.geometry.Rect): Float {
            val dy = kotlin.math.abs(r.center.y - b.center.y)
            val dx = kotlin.math.abs(r.center.x - b.center.x)
            // Below the old place wins a tie: the item after the removed one usually moves up into it.
            val below = if (r.center.y >= b.center.y - 1f) 0f else 0.5f
            return if (sameColumn(r, b)) dy + below else 100_000f + dx + dy
        }
        for ((s, r) in candidates.sortedBy { score(it.second) }.take(4)) {
            if (runCatching { s.requester.requestFocus() }.isSuccess) { log("to ${r.left.toInt()},${r.top.toInt()} of ${candidates.size}"); return true }
        }
        log("none of ${candidates.size}")
        return false
    }
}
