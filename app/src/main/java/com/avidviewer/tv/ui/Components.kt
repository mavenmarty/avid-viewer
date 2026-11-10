package com.avidviewer.tv.ui

import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import coil.compose.AsyncImage

/** Palette taken from the Avid Viewer icon: deep navy, electric blue and silver. */
object C {
    val Bg = Color(0xFF040915)
    val Rail = Color(0xFF071022)
    val Surface = Color(0xFF0C1A33)
    val Surface2 = Color(0xFF132A52)
    val Line = Color(0xFF1E3B6B)
    val Text = Color(0xFFEAF2FF)
    val Soft = Color(0xFFBCCBE4)
    val Muted = Color(0xFF98AFD2)
    val Accent = Color(0xFF1E9BFF)
    val AccentFocus = Color(0xFF6CC2FF)
    val OnAccent = Color(0xFF02132E)
    val Live = Color(0xFFD13438)
}

/**
 * A remote-friendly focusable box: shows a white ring when focused, OK = click,
 * long-press OK or the Menu key = [onMenu].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Focusable(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    color: Color = C.Surface,
    focusedColor: Color = C.Surface2,
    onFocus: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .onPreviewKeyEvent {
                if (onMenu != null && it.key == Key.Menu && it.type == KeyEventType.KeyUp) {
                    onMenu(); true
                } else false
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
            }
            .clip(shape)
            .background(if (focused) focusedColor else color)
            .then(if (focused) Modifier.border(2.dp, C.AccentFocus, shape) else Modifier)
            .combinedClickable(onLongClick = onMenu, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(focused) }
}

@Composable
fun TvButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val on = primary || selected
    Focusable(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = if (on) C.Accent else C.Surface2,
        focusedColor = if (on) C.AccentFocus else C.Line,
        onClick = onClick,
    ) {
        Text(
            text,
            color = if (on) C.OnAccent else C.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            maxLines = 1,
        )
    }
}

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
            AsyncImage(
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

private val palette = listOf(0xFF1D4ED8, 0xFF7C3AED, 0xFF0F766E, 0xFFB91C1C, 0xFFB45309, 0xFF0E7490, 0xFF9D174D, 0xFF4D7C0F, 0xFF6D28D9, 0xFF334155)
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
        val n = holdSteps(e)
        if (n <= 1) return@onKeyEvent false // normal one-step move
        var moved = false
        repeat(n) { if (fm.moveFocus(dir)) moved = true else return@repeat }
        moved
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
