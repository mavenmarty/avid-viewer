package com.avidviewer.tv.ui

import androidx.compose.runtime.withFrameNanos

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
