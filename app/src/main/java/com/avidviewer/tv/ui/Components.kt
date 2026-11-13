package com.avidviewer.tv.ui

import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.composed

/**
 * True inside a highlighted tile whose fill is the light warm-grey highlight ([C.FocusFill]): the text and accent
 * colours read from [C] turn dark there, so they stay readable on the light fill.
 */
val LocalOnLight = androidx.compose.runtime.compositionLocalOf { false }

/**
 * "Dark roast & warm grey" (1.4.0): a neutral dark-brown base, a soft warm-grey highlight with dark text, and a
 * muted gold accent. Text and accent colours are read in composition and follow [LocalOnLight].
 */
object C {
    val Bg = Color(0xFF1B1714)
    val Rail = Color(0xFF201B18)
    val Surface = Color(0xFF2B2521)
    val Surface2 = Color(0xFF37302B)
    val Line = Color(0xFF4B423B)
    /** The highlight: a filled warm-grey tile; what's inside it is drawn in dark colours. */
    val FocusFill = Color(0xFFD9D3CC)
    /** The ring round a highlighted tile that keeps its own fill (posters, coloured buttons). */
    val FocusRing = Color(0xFFD9D3CC)
    /** The accent as a plain value, for drawing code outside composition. */
    val Gold = Color(0xFFCDAE7E)
    val Live = Color(0xFFD46A5C)
    val Text: Color @Composable @ReadOnlyComposable get() = pick(0xFFEDE8E2, 0xFF221C18)
    val Soft: Color @Composable @ReadOnlyComposable get() = pick(0xFFC9C1B8, 0xFF3E352E)
    val Muted: Color @Composable @ReadOnlyComposable get() = pick(0xFFA79E94, 0xFF5C524A)
    val Accent: Color @Composable @ReadOnlyComposable get() = pick(0xFFCDAE7E, 0xFF7A5A2A)
    val AccentFocus: Color @Composable @ReadOnlyComposable get() = pick(0xFFE3CBA0, 0xFF6E4F22)
    val OnAccent: Color @Composable @ReadOnlyComposable get() = pick(0xFF1E1915, 0xFFF4EEE6)

    @Composable @ReadOnlyComposable
    private fun pick(dark: Long, light: Long): Color = Color(if (LocalOnLight.current) light else dark)
}

/**
 * A remote-friendly focusable box. Highlighted, it fills with [focusedColor]: the warm-grey [C.FocusFill] by
 * default, with dark text inside; a tile that keeps its own fill gets a [C.FocusRing] ring instead. OK = click,
 * long-press OK or the Menu key = [onMenu].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Focusable(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    color: Color = C.Surface,
    focusedColor: Color = C.FocusFill,
    ringWidth: Dp = 2.dp,
    onFocus: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val menuDown = remember { booleanArrayOf(false) }
    Row(
        modifier
            .onPreviewKeyEvent {
                // Menu acts on release (so the same press doesn't reach what it opens). Its press is taken here
                // too: left to the system it beeped as an unhandled key, and on 1.0.170 a tester saw only the beep
                // (BUG-015 retest). A release without a press seen here (e.g. after a dialog closed) is ignored.
                if (onMenu == null || it.key != Key.Menu) false
                else if (it.type == KeyEventType.KeyDown) { if (it.nativeKeyEvent.repeatCount == 0) menuDown[0] = true; true }
                else if (it.type == KeyEventType.KeyUp) { if (menuDown[0]) { menuDown[0] = false; onMenu() }; true }
                else false
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
            }
            .clip(shape)
            .background(if (focused) focusedColor else color)
            .then(if (focused && focusedColor != C.FocusFill) Modifier.border(ringWidth, C.FocusRing, shape) else Modifier)
            .combinedClickable(onLongClick = onMenu, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) { androidx.compose.runtime.CompositionLocalProvider(LocalOnLight provides (focused && focusedColor == C.FocusFill)) { content(focused) } }
}

@Composable
fun TvButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    // STATE-02: the main action is filled; the current value of a choice is outlined with a check mark, so the
    // two never look alike. STATE-04: a busy button shows a spinner, is dimmed and ignores OK; it keeps the
    // highlight (taking it away would leave the remote with nothing highlighted) and TalkBack says "Busy".
    val outline = selected && !primary
    Focusable(
        modifier = modifier
            .then(if (outline) Modifier.border(2.dp, C.Accent, RoundedCornerShape(20.dp)) else Modifier)
            .semantics { if (selected) this.selected = true; if (busy) stateDescription = "Busy" }
            .alpha(if (busy) 0.6f else 1f),
        shape = RoundedCornerShape(20.dp),
        color = if (primary) C.Accent else C.Surface2,
        focusedColor = if (primary) C.AccentFocus else C.FocusFill,
        onClick = { if (!busy) onClick() },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) androidx.compose.material3.CircularProgressIndicator(color = if (primary) C.OnAccent else C.Accent, strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp).size(13.dp))
            else if (outline && !text.startsWith("✓")) Glyph("✓", C.Accent, 13.sp, Modifier.padding(end = 6.dp))
            Text(text, color = if (primary) C.OnAccent else C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** A decorative symbol (✓ ★ ☰ ▸): drawn, but not read out by TalkBack (A11Y-01). */
@Composable
fun Glyph(g: String, color: Color, size: TextUnit = 14.sp, modifier: Modifier = Modifier) =
    Text(g, color = color, fontSize = size, fontWeight = FontWeight.Bold, maxLines = 1, modifier = modifier.clearAndSetSemantics { })

/** How much a list without the highlight is dimmed, so the column you're in stands out (STATE-05). */
const val INACTIVE_ALPHA = 0.72f

/** Dims a side list (categories, settings sections) while the highlight is elsewhere on the screen (STATE-05). */
fun Modifier.dimWhenInactive(): Modifier = composed {
    var has by remember { mutableStateOf(true) }
    onFocusChanged { has = it.hasFocus }.alpha(if (has) 1f else INACTIVE_ALPHA)
}

/**
 * STATE-01: the row you are in (the screen in the menu, the category, the settings section) looks the same in
 * every list: a raised row with an accent bar down its left edge and a bold label. TalkBack hears "selected".
 */
fun Modifier.activePath(on: Boolean): Modifier = semantics { selected = on }.then(
    if (on) Modifier.drawWithContent { drawContent(); drawRect(C.Gold, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) } else Modifier,
)

@Composable
fun Label(text: String, size: TextUnit = 13.sp, color: Color = C.Muted, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier, lines: Int = 1) =
    Text(text, color = color, fontSize = size, fontWeight = weight, maxLines = lines, overflow = TextOverflow.Ellipsis, modifier = modifier)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Label(text.uppercase(), size = 12.sp, weight = FontWeight.Bold, modifier = modifier)

/** Channel logo, or a coloured badge with initials when there's no logo. */
@Composable
fun ChannelBadge(name: String, logo: String?, size: Dp = 32.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(badgeColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(name), color = Color.White, fontSize = (size.value * 0.36f).coerceAtLeast(11f).sp, fontWeight = FontWeight.Bold)
        if (logo != null) {
            TvImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier
                    .size(size)
                    .background(C.Surface2)
                    .padding(3.dp),
            )
        }
    }
}

fun initials(n: String): String =
    n.replace(Regex("^[A-Z]{2,3}[:|] *"), "").split(' ', '-').filter { it.isNotBlank() && it[0].isLetterOrDigit() }
        .take(2).joinToString("") { it.take(1) }.uppercase().ifBlank { "TV" }

// Muted earth tones that sit with the dark-roast palette (tiles with no artwork, channel badges).
private val palette = listOf(0xFF6B4A3A, 0xFF4E5A44, 0xFF6A3F44, 0xFF7A5A32, 0xFF3F5157, 0xFF5A4A5E, 0xFF4A5560, 0xFF6E5A3E, 0xFF5B4636, 0xFF455046)
fun badgeColor(name: String) = Color(palette[(name.hashCode() and 0x7fffffff) % palette.size])

/**
 * Put on a screen's first item: when the screen is picked in the left menu, the highlight goes here
 * (the top of the screen) instead of wherever the remote happened to land.
 */
@Composable
fun Modifier.entryFocus(vm: AppViewModel, screen: Screen): Modifier {
    val fr = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(vm.entryFocusFor) {
        if (vm.entryFocusFor != screen) return@LaunchedEffect
        // Try on each screen refresh for up to ~2 s (on a slow device the item may not be ready at once),
        // and stop as soon as the viewer presses a button: the highlight is theirs from then on.
        val started = android.os.SystemClock.uptimeMillis()
        for (frame in 0 until 120) {
            androidx.compose.runtime.withFrameNanos { }
            if (vm.lastKeyAt > started) break
            if (focused) break
            runCatching { fr.requestFocus() }
        }
        if (vm.entryFocusFor == screen) vm.entryFocusFor = null
    }
    return this.then(androidx.compose.ui.Modifier.focusRequester(fr)).then(androidx.compose.ui.Modifier.onFocusChanged { focused = it.isFocused })
}

/**
 * Press-and-hold acceleration for the remote's arrow keys. A tap (and the first moments of a hold)
 * moves one item; holding longer moves 3, then 6, then 12 items per key repeat. Stops the moment
 * the button is released, because nothing moves without a key event.
 */
fun holdSteps(e: androidx.compose.ui.input.key.KeyEvent): Int {
    val r = e.nativeKeyEvent.repeatCount
    return when {
        r < 6 -> 1
        r < 18 -> 3
        r < 36 -> 6
        else -> 12
    }
}

/** Root-level handler: when an arrow key is held, move focus several items per repeat. */
private var lastHeldMove = 0L

/**
 * Takes the highlight when the view model asks for this item ([key] == vm.navFocusKey), retrying
 * each frame until it holds; a button press in the meantime wins.
 */
@Composable
fun Modifier.navFocusTarget(vm: AppViewModel, key: String?): Modifier {
    if (key == null) return this
    val fr = androidx.compose.runtime.remember { FocusRequester() }
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(vm.navFocusKey) {
        if (vm.navFocusKey != key) return@LaunchedEffect
        val started = android.os.SystemClock.uptimeMillis()
        var held = 0
        for (frame in 0 until 120) {
            androidx.compose.runtime.withFrameNanos { }
            if (vm.lastKeyAt > started) break
            if (focused) { if (++held >= 8) break; continue }
            held = 0
            runCatching { fr.requestFocus() }
        }
        if (vm.navFocusKey == key) vm.navFocusKey = null
    }
    return this.then(Modifier.focusRequester(fr)).then(Modifier.onFocusChanged { focused = it.isFocused })
}

/** Left at the edge of a left-hand list goes back a layer, like Back. */
fun Modifier.leftGoesBack(vm: AppViewModel): Modifier = this.then(Modifier.onKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown || e.key != Key.DirectionLeft || e.nativeKeyEvent.repeatCount != 0) return@onKeyEvent false
    // Only the guide's own left-hand lists are layers (Settings has its own rules).
    val onGuide = vm.screen == Screen.GUIDE || vm.screen == Screen.LIVE
    val onLayer = onGuide && ((vm.navMode == NavMode.GROUPS && vm.inGroupNav) || (vm.navMode == NavMode.COLLAPSED && vm.inPlaylistTree))
    if (onLayer) { vm.navBack(); true } else false
})

@Composable
fun Modifier.holdToAccelerate(): Modifier {
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    return this.then(androidx.compose.ui.Modifier.onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
        val dir = when (e.key) {
            Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
            Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
            Key.DirectionLeft -> androidx.compose.ui.focus.FocusDirection.Left
            Key.DirectionRight -> androidx.compose.ui.focus.FocusDirection.Right
            else -> return@onKeyEvent false
        }
        val repeatCount = e.nativeKeyEvent.repeatCount
        if (repeatCount > 0) {
            // Remote repeats can arrive faster than a slow device lays out new rows. Drop
            // repeats that are already stale or that come too close together, so a held button
            // never builds a backlog that freezes the screen.
            val now = android.os.SystemClock.uptimeMillis()
            val at = e.nativeKeyEvent.eventTime
            if (now - at > 120 || (at - lastHeldMove in 0 until 45)) return@onKeyEvent true
            lastHeldMove = at
        }
        val n = holdSteps(e)
        if (n <= 1) return@onKeyEvent false // normal one-step move
        var moved = 0
        while (moved < n && fm.moveFocus(dir)) moved++
        moved > 0
    })
}

/**
 * Keys while re-ordering an item in place: Up/Down move it (faster while held), Left sends it to the top,
 * Right to the bottom, OK / Back / Menu finish. Finishing happens on key release so the same press
 * doesn't also act on the screen behind. Consumes every key so the highlight stays on the item.
 */
fun reorderKeys(
    e: androidx.compose.ui.input.key.KeyEvent,
    onMove: (Int) -> Unit, onTop: () -> Unit, onBottom: () -> Unit, onDone: () -> Unit,
): Boolean {
    val done = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter ||
        e.key == Key.Back || e.key == Key.Escape || e.key == Key.Menu
    if (e.type == KeyEventType.KeyDown) when (e.key) {
        Key.DirectionUp -> onMove(-holdSteps(e))
        Key.DirectionDown -> onMove(holdSteps(e))
        Key.DirectionLeft -> onTop()
        Key.DirectionRight -> onBottom()
        else -> {}
    } else if (e.type == KeyEventType.KeyUp && done) onDone()
    return true
}

/** Asks for the highlight for a few frames (a row may still be coming on screen after a scroll). */
suspend fun FocusRequester.requestSoon(frames: Int = 30) {
    for (frame in 0 until frames) {
        androidx.compose.runtime.withFrameNanos { }
        if (runCatching { requestFocus() }.isSuccess && frame > 1) break
    }
}

/**
 * Wrap-around for a list: Down on its last item goes to its first, Up on its first goes to its last.
 * Only on a fresh press, so holding a button still stops at the ends.
 */
class ListWrap(private val state: androidx.compose.foundation.lazy.LazyListState, private val scope: kotlinx.coroutines.CoroutineScope) {
    val first = FocusRequester()
    val last = FocusRequester()
    fun toFirst() { scope.launch { state.scrollToItem(0); first.requestSoon() } }
    fun toLast() { scope.launch { state.scrollToItem((state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)); last.requestSoon() } }
}

@Composable
fun rememberListWrap(state: androidx.compose.foundation.lazy.LazyListState): ListWrap {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return androidx.compose.runtime.remember(state) { ListWrap(state, scope) }
}

/** Put on a list's first and/or last item (before the item's own focus handling). */
fun Modifier.wrapEnds(wrap: ListWrap, isFirst: Boolean, isLast: Boolean): Modifier {
    if (!isFirst && !isLast) return this
    return this
        .then(if (isFirst) Modifier.focusRequester(wrap.first) else Modifier)
        .then(if (isLast) Modifier.focusRequester(wrap.last) else Modifier)
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || e.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent false
            when {
                e.key == Key.DirectionUp && isFirst && !isLast -> { wrap.toLast(); true }
                e.key == Key.DirectionDown && isLast && !isFirst -> { wrap.toFirst(); true }
                else -> false
            }
        }
}


/** What a screen is waiting for or missing (DATA-07): the same look everywhere. */
enum class StateKind { LOADING, EMPTY, ERROR }

/**
 * Loading, empty and error messages, one style on every screen: a title, a line or two, then buttons the
 * remote can reach. An error always offers Try again ([retry]); [action] is the screen's own way out
 * (Add a playlist, Open Settings…). The first button takes the highlight.
 */
@Composable
fun StateMessage(
    kind: StateKind,
    title: String,
    body: String? = null,
    retry: (() -> Unit)? = null,
    action: Pair<String, () -> Unit>? = null,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    Column(modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (kind == StateKind.LOADING) androidx.compose.material3.CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            if (kind == StateKind.ERROR) Text("!", color = C.OnAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Accent).padding(horizontal = 8.dp, vertical = 1.dp))
            Text(title, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        body?.takeIf { it.isNotBlank() }?.let { Label(it, size = 14.sp, color = C.Soft, lines = 4) }
        val buttons = listOfNotNull(retry?.takeIf { kind == StateKind.ERROR }?.let { "Try again" to it }, action)
        if (buttons.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            buttons.forEachIndexed { i, (label, go) -> TvButton(label, primary = i == 0, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { go() } }
        }
    }
}
