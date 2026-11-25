package com.avidviewer.tv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * #39 (Nov 24, 2026): Google TV remotes, and the Google TV phone app, have no Menu button, so the options on a channel
 * or title (favorites, lists, My List, mark watched…) seemed impossible to reach. Holding OK opens the same options.
 * On a device that isn't a Fire TV and hasn't shown a Menu press yet, the hints say "Hold OK", and a tip shows on the
 * TV Guide, Movies and Series until options have been opened with a held OK a few times.
 */
object MenuKey {
    private const val TIPS = 3
    private var store: android.content.SharedPreferences? = null

    /** The remote has a Menu button: a Fire TV, or a Menu press was seen here. */
    var hasMenu by mutableStateOf(true); private set
    private var held by mutableIntStateOf(0)

    fun init(context: android.content.Context) {
        val p = context.getSharedPreferences("menu-key", android.content.Context.MODE_PRIVATE).also { store = it }
        val fireTv = context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
        hasMenu = fireTv || p.getBoolean("saw-menu", false)
        held = p.getInt("held-ok", 0)
    }

    /** A Menu press arrived: this remote has the button, so the tip isn't needed. */
    fun sawMenu() { if (hasMenu) return; hasMenu = true; store?.edit()?.putBoolean("saw-menu", true)?.apply() }

    /** Options were opened by holding OK. */
    fun heldOk() { if (held >= TIPS) return; held++; store?.edit()?.putInt("held-ok", held)?.apply() }

    /** Whether to show the "Hold OK for options" tip. */
    val showTip: Boolean get() = !hasMenu && held < TIPS

    /** How the hints say to open options: "Press Menu (or hold OK)" or, without a Menu button, "Hold OK". */
    val press: String get() = if (hasMenu) "Press Menu (or hold OK)" else "Hold OK"
    /** The same, mid-sentence. */
    val pressLower: String get() = if (hasMenu) "press Menu (or hold OK)" else "hold OK"
    /** Short form for key lists: "Menu or hold OK" / "Hold OK". */
    val short: String get() = if (hasMenu) "Menu or hold OK" else "Hold OK"

    /** For tests. */
    internal fun reset(hasMenu: Boolean, held: Int = 0) { this.hasMenu = hasMenu; this.held = held }
}
