package com.avidviewer.tv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * A colour theme (1.4.0). Each profile picks one in Settings › Profiles or Settings › Display. "Midnight blue" is
 * the look up to 1.3.0 and stays the theme of profiles that existed before 1.4.0; new installs and new profiles
 * start on "Dark roast".
 *
 * [focusLight]: the highlight is a light filled tile with dark text inside (the warm themes). Otherwise the
 * highlight is a raised dark tile with a [focusRing] ring, as before 1.4.0.
 */
class Theme(
    val id: String,
    val name: String,
    val note: String,
    bg: Long, rail: Long, surface: Long, surface2: Long, line: Long,
    text: Long, soft: Long, muted: Long, accent: Long, accentFocus: Long, onAccent: Long, live: Long,
    focusFill: Long, focusRing: Long, focusText: Long, val focusLight: Boolean,
) {
    val bg = Color(bg); val rail = Color(rail); val surface = Color(surface); val surface2 = Color(surface2); val line = Color(line)
    val text = Color(text); val soft = Color(soft); val muted = Color(muted)
    val accent = Color(accent); val accentFocus = Color(accentFocus); val onAccent = Color(onAccent); val live = Color(live)
    val focusFill = Color(focusFill); val focusRing = Color(focusRing)

    // The same roles inside a light highlighted tile: dark text, a deeper accent.
    val inkText = Color(focusText)
    val inkSoft = lerp(inkText, this.focusFill, 0.22f)
    val inkMuted = lerp(inkText, this.focusFill, 0.40f)
    val inkAccent = lerp(this.accent, Color.Black, 0.45f)
    val inkAccentFocus = lerp(this.accent, Color.Black, 0.52f)
    val inkOnAccent = lerp(this.focusFill, Color.White, 0.5f)
}

object Themes {
    const val MIDNIGHT = "midnight"
    const val ROAST = "roast"
    /** New installs and new profiles. */
    const val DEFAULT = ROAST
    /** Profiles from before 1.4.0 keep the look they had. */
    const val LEGACY = MIDNIGHT

    val all = listOf(
        Theme(ROAST, "Dark roast", "Neutral dark brown, a soft warm-grey highlight, muted gold.",
            0xFF1B1714, 0xFF201B18, 0xFF2B2521, 0xFF37302B, 0xFF4B423B,
            0xFFEDE8E2, 0xFFC9C1B8, 0xFFA79E94, 0xFFCDAE7E, 0xFFE3CBA0, 0xFF1E1915, 0xFFD46A5C,
            0xFFD9D3CC, 0xFFD9D3CC, 0xFF221C18, true),
        Theme(MIDNIGHT, "Midnight blue", "The original look: navy panels, a bright blue ring.",
            0xFF040915, 0xFF071022, 0xFF0C1A33, 0xFF132A52, 0xFF1E3B6B,
            0xFFEAF2FF, 0xFFBCCBE4, 0xFF98AFD2, 0xFF1E9BFF, 0xFF6CC2FF, 0xFF02132E, 0xFFD13438,
            0xFF132A52, 0xFF6CC2FF, 0xFFEAF2FF, false),
        Theme("cream", "Espresso & cream", "Deep espresso, a cream highlight, caramel accents.",
            0xFF1A1310, 0xFF1F1713, 0xFF2A201A, 0xFF362922, 0xFF4A3A30,
            0xFFF2E9DA, 0xFFCDBDA8, 0xFFAE9C86, 0xFFC8965F, 0xFFDDB585, 0xFF1F150F, 0xFFD46A5C,
            0xFFF1E6D2, 0xFFF1E6D2, 0xFF2A1E17, true),
        Theme("ivory", "Espresso & ivory", "A darker roast, a bright ivory highlight, copper accents.",
            0xFF17110E, 0xFF1C1511, 0xFF261D18, 0xFF33271F, 0xFF463629,
            0xFFF7F1E6, 0xFFD3C6B4, 0xFFB09F8A, 0xFFC47F4B, 0xFFDBA27A, 0xFF1A110C, 0xFFD46A5C,
            0xFFFBF7EE, 0xFFFBF7EE, 0xFF241A14, true),
        Theme("latte", "Mocha & latte", "A lighter mocha base, a latte highlight, warm amber.",
            0xFF2B211C, 0xFF31261F, 0xFF3B2E26, 0xFF4A3A30, 0xFF5E4A3D,
            0xFFF4ECE0, 0xFFDCCDBA, 0xFFBEAC97, 0xFFE0A56A, 0xFFEDC193, 0xFF2A1C12, 0xFFE07A6B,
            0xFFEADBC4, 0xFFEADBC4, 0xFF2B1F18, true),
    )

    fun of(id: String?): Theme = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT }
}
