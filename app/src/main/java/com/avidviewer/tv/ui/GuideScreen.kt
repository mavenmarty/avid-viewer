package com.avidviewer.tv.ui

import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avidviewer.tv.data.Program
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DP_PER_MIN = 3.3f
private const val CH_COL = 200 // channel column width (dp)
private const val WINDOW_MIN = 150
/** How far ahead the guide can scroll. */
private const val AHEAD_MIN = 12 * 60
private const val HALF_MS = 30 * 60 * 1000L

@Composable
fun GuideScreen(vm: AppViewModel) {
    if (vm.channels.isEmpty()) {
        EmptyState(vm, "No channels yet", "Add your provider on the Playlists screen.")
        return
    }
    @Suppress("UNUSED_VARIABLE") val favTick = vm.favDataTick // redraw when other playlists' favorites load
    val list = vm.channelsIn(vm.selectedGroup).distinctBy { it.key }
    val numbers = vm.numbers()
    val half = HALF_MS
    // The visible 2.5-hour window starts at the current half hour plus [slots] × 30 minutes (up to 12 hours ahead).
    val base = (vm.now / half) * half
    val maxSlots = (AHEAD_MIN - WINDOW_MIN) / 30 + 1
    var slots by remember { mutableStateOf(0) }
    slots = slots.coerceIn(0, maxSlots)
    val winStart = base + slots * half
    val winEnd = winStart + WINDOW_MIN * 60 * 1000L
    var focusProg by remember { mutableStateOf<Pair<String, Program?>?>(null) }
    /** Which channel name cell has the highlight (-1 = none). */
    var focusedRow by remember { mutableStateOf(-1) }
    /** The channel (by key) whose name cell has the highlight; survives rows being replaced. */
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val wrapFocus = androidx.compose.ui.platform.LocalFocusManager.current

    // The time the highlight is on. Up/Down lands on the program airing at this time in the next row,
    // whatever the run times are; it only changes when you move left or right.
    var anchor by remember { mutableStateOf(vm.now) }
    val progFocus = remember(list.size, vm.selectedGroup) { List(list.size) { FocusRequester() } }
    var pendingProg by remember { mutableStateOf<Int?>(null) }
    val listState = remember(vm.selectedGroup) {
        val (g, i, o) = vm.guideScroll
        if (g == vm.selectedGroup) androidx.compose.foundation.lazy.LazyListState(i, o) else androidx.compose.foundation.lazy.LazyListState()
    }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (i, o) -> vm.guideScroll = Triple(vm.selectedGroup, i, o) }
    }
    val scope = rememberCoroutineScope()
    val rowFocus = remember(list.size, vm.selectedGroup) { List(list.size) { FocusRequester() } }
    var pendingFocus by remember { mutableStateOf<Int?>(null) }

    // Scroll to and focus the current channel (at startup and when returning from full screen).
    LaunchedEffect(vm.guideFocusTick, list.isNotEmpty()) {
        val mode = vm.guideFocusMode
        vm.trail("tick${vm.guideFocusTick} $mode n=${list.size} nav=${vm.navMode}")
        if (list.isEmpty() || vm.navMode != NavMode.GROUPS) return@LaunchedEffect
        repeat(9) { androidx.compose.runtime.withFrameNanos { } }
        vm.trail("go $mode")
        // Only now is the request used up (if the screen was rebuilt during the pause, the new one uses it).
        vm.guideFocusMode = GuideFocus.CURRENT
        when (mode) {
            // Newly chosen category: start at the top.
            GuideFocus.TOP -> { scope.launch { listState.scrollToItem(0) }; pendingFocus = 0 }
            // Same category again: stay where the list was.
            GuideFocus.KEEP -> pendingFocus = listState.firstVisibleItemIndex.coerceIn(0, list.size - 1)
            // Startup or back from full screen: the channel that was playing.
            GuideFocus.CURRENT -> {
                val idx = list.indexOfFirst { it.key == vm.currentKey }.let { if (it < 0) 0 else it }
                scope.launch { listState.scrollToItem((idx - 2).coerceAtLeast(0)) }
                pendingFocus = idx
            }
        }
    }
    LaunchedEffect(pendingFocus) {
        val i = pendingFocus ?: return@LaunchedEffect
        // Keep asking each frame (up to ~2 s) until that channel really has the highlight: on a slow
        // device the row may not be ready at once. A button press in the meantime wins.
        val started = android.os.SystemClock.uptimeMillis()
        val want = list.getOrNull(i)?.key
        vm.trail("want $i")
        var held = 0
        for (frame in 0 until 120) {
            androidx.compose.runtime.withFrameNanos { }
            if (vm.lastKeyAt > started) break
            // Done once the highlight has stayed there for a few frames (something else may grab it right after).
            if (focusedKey == want) { if (++held >= 12) break; continue }
            held = 0
            val r = runCatching { rowFocus.getOrNull(i)?.requestFocus() }
            if (frame % 20 == 0) vm.trail("f$frame fk=$focusedKey ${r.exceptionOrNull()?.javaClass?.simpleName ?: "ok"}")
        }
        vm.trail("end fk=$focusedKey")
        pendingFocus = null
    }
    // Re-ordering a channel: keep it on screen and highlighted as it moves.
    val movingIdx = list.indexOfFirst { it.key == vm.movingChannel }
    var movingFocused by remember { mutableStateOf(false) }
    LaunchedEffect(vm.movingChannel, movingIdx) {
        if (movingIdx < 0) return@LaunchedEffect
        val vis = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (vis.isEmpty() || movingIdx <= vis.first() || movingIdx >= vis.last()) listState.scrollToItem((movingIdx - 2).coerceAtLeast(0))
        // A big jump (to the top or bottom) can drop the highlight; keep asking until the channel has it.
        for (attempt in 0 until 15) {
            delay(30)
            if (movingFocused) break
            runCatching { rowFocus[movingIdx].requestFocus() }
        }
    }
    LaunchedEffect(pendingProg, slots) {
        val i = pendingProg ?: return@LaunchedEffect
        // Retry for a few frames: the row may still be coming on screen after a scroll.
        for (frame in 0 until 30) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { progFocus[i].requestFocus() }.isSuccess && frame > 1) break
        }
        pendingProg = null
    }

    /** Puts the highlight on a channel name row, scrolling it into view (used for wrap-around). */
    fun wrapTo(i: Int) {
        scope.launch {
            listState.scrollToItem(if (i == 0) 0 else (i - (listState.layoutInfo.visibleItemsInfo.size - 2)).coerceAtLeast(0))
            pendingFocus = i
        }
    }

    /** Up/Down from a program: go to the program airing at [anchor] in the row above or below. */
    fun moveRow(from: Int, dir: Int, steps: Int = 1, fresh: Boolean = false): Boolean {
        // Down on the last row (a fresh press) wraps to the first row, keeping the time; Up on the first row
        // goes to the header as before (Up again from the top of the screen wraps to the last row).
        if (dir > 0 && from == list.lastIndex && fresh && list.size > 1) {
            scope.launch { listState.scrollToItem(0); pendingProg = 0 }
            return true
        }
        if (from + dir !in list.indices) return false
        val target = (from + dir * steps).coerceIn(0, list.size - 1)
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
        scope.launch {
            if (target !in visible || target == visible.lastOrNull() || target == visible.firstOrNull()) {
                listState.scrollToItem((if (dir > 0) target - (visible.size - 2).coerceAtLeast(0) else target).coerceAtLeast(0))
            }
            pendingProg = target
        }
        return true
    }

    /** Right on the last program in view (or Left on the first) scrolls the window by whole half hours. */
    fun shiftTo(time: Long, row: Int, forward: Boolean) {
        val need = if (forward) ((time + half - (base + WINDOW_MIN * 60 * 1000L)) + half - 1) / half else (time - base) / half
        val n = need.toInt().coerceIn(0, maxSlots)
        if (n == slots) return
        slots = n
        anchor = time
        pendingProg = row
    }

    // Channel Up / Channel Down (and Page Up / Page Down) move a full page; Up/Down still move one row.
    fun page(dir: Int) {
        if (list.isEmpty()) return
        val visible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
        val first = listState.firstVisibleItemIndex
        var target = first + dir * visible
        target = when {
            target >= list.size -> 0
            target < 0 -> ((list.size - 1) / visible) * visible
            else -> target
        }
        scope.launch {
            listState.scrollToItem(target)
            pendingFocus = target
        }
    }

    Column(
        Modifier.fillMaxSize().padding(14.dp).onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (it.key) {
                Key.ChannelUp, Key.PageUp -> { page(-1); true }
                Key.ChannelDown, Key.PageDown -> { page(1); true }
                else -> false
            }
        },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Preview window + details of the focused show. Up from the top of the screen wraps to the last channel.
        Row(
            Modifier.fillMaxWidth().height(130.dp).onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && e.nativeKeyEvent.repeatCount == 0 && list.isNotEmpty()) {
                    if (!wrapFocus.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)) wrapTo(list.lastIndex)
                    true
                } else false
            },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PreviewWindow(vm, Modifier.width(231.dp).fillMaxHeight(), onOpen = { vm.openFullScreen(vm.currentKey, PlayContext.GROUP) })
            val fc = vm.channel(focusProg?.first ?: vm.currentKey)
            val fp = focusProg?.second ?: vm.nowNext(fc).first
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label(listOfNotNull(fc?.let { vm.channelName(it) }, fc?.let { vm.playlistName(it) }?.ifBlank { null }, fp?.let { "${vm.fmtTime(it.start)} – ${vm.fmtTime(it.end)}" }).joinToString(" · "), size = 12.sp)
                Label(fp?.title ?: "No guide information", size = 18.sp, color = C.Text, weight = FontWeight.Bold)
                Label(listOfNotNull(fp?.subtitle?.let { "“$it”" }, if (fp?.isNew == true) "New" else null).joinToString(" · "), size = 13.sp, color = C.Soft)
                Label("OK: preview · OK again: full screen · CH+/CH−: page · Menu: options", size = 12.sp, lines = Int.MAX_VALUE)
            }
        }
        // Groups
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(vm.groupName(vm.selectedGroup ?: "") + "  ·  ${list.size} channels", size = 13.sp, color = C.Soft, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            var text by remember { mutableStateOf(vm.guideQuery) }
            TvField("Search channels and programs", text, modifier = Modifier.width(300.dp), imeAction = androidx.compose.ui.text.input.ImeAction.Search, onSubmit = { vm.guideQuery = text.trim() }) { text = it }
            if (vm.guideQuery.isNotBlank()) TvButton("✕ Clear") { text = ""; vm.guideQuery = "" }
        }
        if (vm.selectedGroup == FAV_GROUP && vm.guideQuery.isBlank()) LocalSuggestions(vm)
        if (list.isEmpty() && vm.selectedGroup == FAV_GROUP) {
            Label("No favorites yet. Press Menu (or hold OK) on any channel and choose Add to List — favorites from every playlist appear here.", size = 13.sp, color = C.Soft, lines = Int.MAX_VALUE)
        } else if (list.isEmpty() && isListGroup(vm.selectedGroup)) {
            Label("This list is empty. Press Menu (or hold OK) on any channel and choose Add to List. Channels from every playlist can go in one list.", size = 13.sp, color = C.Soft, lines = Int.MAX_VALUE)
        }
        if (vm.guideQuery.isNotBlank()) {
            GuideSearchResults(vm, Modifier.weight(1f))
        } else {
        // Timeline header
        Row(Modifier.fillMaxWidth().height(18.dp)) {
            Label(vm.dayLabel(winStart) + " · ${vm.zoneLabel()}" + if (slots > 0) "  ◀" else "", size = 12.sp, modifier = Modifier.width((CH_COL + 3).dp))
            Box(Modifier.weight(1f)) {
                for (i in 0 until WINDOW_MIN / 30) {
                    Label(vm.fmtTime(winStart + i * half), size = 12.sp, modifier = Modifier.offset(x = (i * 30 * DP_PER_MIN).dp))
                }
            }
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Keyed by channel so a row keeps its highlight while it moves (re-order) or the list changes.
                itemsIndexed(list, key = { _, c -> c.key }) { idx, c ->
                    Row(Modifier.fillMaxWidth().height(50.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        // Channel name: a fixed-width cell with up to two lines (no widening while highlighted).
                        val isMoving = c.key == vm.movingChannel
                        Focusable(
                            modifier = Modifier.width(CH_COL.dp).fillMaxHeight().focusRequester(rowFocus[idx])
                                .then(if (isMoving) Modifier.border(2.dp, C.AccentFocus, RoundedCornerShape(8.dp)) else Modifier)
                                .onFocusChanged {
                                    if (isMoving) movingFocused = it.isFocused
                                    if (it.isFocused) { focusedRow = idx; focusedKey = c.key } else if (focusedKey == c.key) { focusedRow = -1; focusedKey = null }
                                }
                                .onPreviewKeyEvent { e ->
                                    if (isMoving) reorderKeys(e, onMove = { vm.moveChannelBy(c.key, it) }, onTop = { vm.moveChannelTo(c.key, 0) },
                                        onBottom = { vm.moveChannelTo(c.key, Int.MAX_VALUE) }, onDone = { vm.movingChannel = null })
                                    // Down on the last channel (a fresh press, not a hold) goes back to the first.
                                    else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && idx == list.lastIndex && e.nativeKeyEvent.repeatCount == 0 && list.size > 1) {
                                        wrapTo(0); true
                                    } else false
                                },
                            color = if (isMoving) C.Surface2 else C.Surface,
                            onFocus = { focusProg = c.key to vm.nowNext(c).first },
                            onMenu = { if (!isMoving) vm.channelMenu = c.key },
                            onClick = { if (isMoving) vm.movingChannel = null else vm.selectChannel(c.key, PlayContext.GROUP) },
                        ) {
                            Label(if (isMoving) "⇅" else "${numbers[c.key] ?: ""}", size = 12.sp, color = if (isMoving) C.Accent else C.Muted, modifier = Modifier.width(30.dp).padding(start = 6.dp))
                            ChannelBadge(vm.channelName(c), c.logo, 28.dp)
                            Label(
                                vm.channelName(c) + if (vm.isFavorite(c.key)) " ★" else "",
                                size = 13.sp, color = C.Text, weight = FontWeight.SemiBold,
                                lines = 2,
                                modifier = Modifier.padding(start = 7.dp, end = 6.dp),
                            )
                        }
                        val progs = vm.programs(c).filter { it.end > winStart && it.start < winEnd }
                        if (progs.isEmpty()) {
                            Focusable(
                                modifier = Modifier.width((WINDOW_MIN * DP_PER_MIN).dp).fillMaxHeight().focusRequester(progFocus[idx])
                                    .onPreviewKeyEvent { e ->
                                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (e.key) {
                                            Key.DirectionUp -> moveRow(idx, -1, holdSteps(e), e.nativeKeyEvent.repeatCount == 0)
                                            Key.DirectionDown -> moveRow(idx, 1, holdSteps(e), e.nativeKeyEvent.repeatCount == 0)
                                            else -> false
                                        }
                                    },
                                color = C.Surface.copy(alpha = 0.6f),
                                onFocus = { focusProg = c.key to null },
                                onClick = { vm.selectChannel(c.key, PlayContext.GROUP) },
                            ) { Label("No guide information", size = 12.sp, modifier = Modifier.padding(horizontal = 10.dp)) }
                        } else {
                            var cursor = winStart
                            // The program at the remembered time (or the nearest one) takes Up/Down focus for this row.
                            val at = anchor.coerceIn(winStart, winEnd - 1)
                            val target = progs.firstOrNull { at >= it.start && at < it.end }
                                ?: progs.firstOrNull { it.start >= at } ?: progs.last()
                            progs.forEachIndexed { pi, p ->
                                val a = maxOf(p.start, winStart); val b = minOf(p.end, winEnd)
                                if (a > cursor) Box(Modifier.width((((a - cursor) / 60000f * DP_PER_MIN).dp - 3.dp).coerceAtLeast(0.dp)))
                                val isNow = vm.now >= p.start && vm.now < p.end
                                Focusable(
                                    modifier = Modifier.width(((((b - a) / 60000f) * DP_PER_MIN).dp - 3.dp).coerceAtLeast(4.dp)).fillMaxHeight()
                                        .then(if (p === target) Modifier.focusRequester(progFocus[idx]) else Modifier)
                                        .onPreviewKeyEvent { e ->
                                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                            when (e.key) {
                                                Key.DirectionUp -> moveRow(idx, -1, holdSteps(e), e.nativeKeyEvent.repeatCount == 0)
                                                Key.DirectionDown -> moveRow(idx, 1, holdSteps(e), e.nativeKeyEvent.repeatCount == 0)
                                                // Last program in view: scroll the guide forward to the next program.
                                                Key.DirectionRight -> if (pi == progs.lastIndex && slots < maxSlots) { shiftTo(maxOf(p.end, winEnd - 60_000L), idx, true); true } else false
                                                // First program in view: scroll back (at the start, Left goes to the channel name).
                                                Key.DirectionLeft -> if (pi == 0 && slots > 0) { shiftTo(minOf(p.start, winStart) - 60_000L, idx, false); true } else false
                                                else -> false
                                            }
                                        },
                                    color = if (isNow) C.Surface2 else C.Surface,
                                    focusedColor = C.Line,
                                    onFocus = {
                                        focusProg = c.key to p
                                        // Keep the remembered time if it falls in this program; otherwise it moves here.
                                        if (anchor < maxOf(p.start, winStart) || anchor >= minOf(p.end, winEnd)) anchor = maxOf(p.start, winStart)
                                    },
                                    onMenu = { vm.channelMenu = c.key },
                                    onClick = { vm.selectChannel(c.key, PlayContext.GROUP) },
                                ) {
                                    Column(Modifier.padding(horizontal = 8.dp)) {
                                        Label((if (p.isNew) "NEW · " else "") + p.title, size = 12.sp, color = C.Text, weight = FontWeight.SemiBold)
                                        Label("${vm.fmtTime(p.start)} – ${vm.fmtTime(p.end)}", size = 12.sp)
                                    }
                                }
                                cursor = b
                            }
                        }
                    }
                }
            }
            // "Now" line
            if (vm.now in winStart until winEnd) {
                val nowX = (CH_COL + 3) + ((vm.now - winStart) / 60000f) * DP_PER_MIN
                Box(Modifier.offset(x = nowX.dp).width(2.dp).fillMaxHeight().background(C.Accent))
            }
        }
        }
    }
}

/** Menu on a channel: favorite, rename, move, hide. Stays open so Move can be pressed repeatedly. */
@Composable
fun ChannelOptionsDialog(vm: AppViewModel) {
    val key = vm.channelMenu ?: return
    val c = vm.channel(key) ?: run { vm.channelMenu = null; return }
    var renaming by remember(key) { mutableStateOf(false) }
    if (renaming) {
        RenameDialog("Rename channel", vm.channelName(c), onDismiss = { renaming = false }) { vm.renameChannel(key, it); renaming = false }
        return
    }
    val list = vm.channelsIn(vm.selectedGroup)
    val pos = list.indexOfFirst { it.key == key }
    val inLists = vm.listsFor(key)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.channelMenu = null },
        containerColor = C.Surface,
        title = { Text(vm.channelName(c), color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(listOf(vm.groupName(vm.selectedGroup ?: c.group), if (pos >= 0) "${pos + 1} of ${list.size}" else "", vm.playlistName(c)).filter { it.isNotBlank() }.joinToString(" · "), size = 13.sp, color = C.Soft, lines = 2)
                OptionRow("＋  Add to List…" + if (inLists.isNotBlank()) "   (in $inLists)" else "") { vm.channelMenu = null; vm.listPickerKey = key }
                if (isListGroup(vm.selectedGroup)) OptionRow("−  Remove from ${vm.groupName(vm.selectedGroup!!).removePrefix("★ ").removePrefix("☰ ")}") { vm.toggleInList(vm.selectedGroup!!, key); vm.channelMenu = null }
                OptionRow("✎  Rename") { renaming = true }
                OptionRow("⇅  Re-order") { vm.channelMenu = null; vm.movingChannel = key }
                if (!isListGroup(vm.selectedGroup)) OptionRow("⊘  Hide channel") { vm.hideChannel(key); vm.channelMenu = null }
            }
        },
        confirmButton = { TvButton("Done", primary = true) { vm.channelMenu = null } },
    )
}

/** "Suggested near Fargo, ND": local channels from the postal code that aren't favorites yet. */
@Composable
private fun LocalSuggestions(vm: AppViewModel) {
    val list = vm.suggestions()
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("Suggested near ${vm.suggestionPlace()}", size = 13.sp, color = C.Text, weight = FontWeight.Bold)
            Label("from your postal code · ${list.size}", size = 12.sp, modifier = Modifier.weight(1f))
            TvButton("Hide suggestions") { vm.setShowSuggestions(false) }
        }
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.key }) { s ->
                val c = vm.channel(s.key) ?: return@items
                Row(
                    Modifier.width(300.dp).height(60.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ChannelBadge(vm.channelName(c), c.logo, 28.dp)
                    Column(Modifier.weight(1f)) {
                        Label(vm.channelName(c), size = 13.sp, color = C.Text, weight = FontWeight.SemiBold)
                        Label(listOf(s.reason, vm.playlistName(c)).filter { it.isNotBlank() }.joinToString(" · "), size = 12.sp)
                    }
                    TvButton("＋ Add to List") { vm.listPickerKey = s.key }
                    TvButton("✕") { vm.dismissSuggestion(s.key) }
                }
            }
        }
    }
}

/** Menu on a category in the left navigation: rename, move, hide. */
@Composable
fun GroupOptionsDialog(vm: AppViewModel) {
    val raw = vm.groupMenu ?: return
    var renaming by remember(raw) { mutableStateOf(false) }
    if (renaming) {
        RenameDialog("Rename category", vm.groupName(raw), onDismiss = { renaming = false }) { vm.renameGroup(raw, it); renaming = false }
        return
    }
    val all = vm.groups(includeHidden = true)
    val pos = all.indexOfFirst { it.raw == raw }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.groupMenu = null },
        containerColor = C.Surface,
        title = { Text(vm.groupName(raw), color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Category ${pos + 1} of ${all.size} · ${all.getOrNull(pos)?.count ?: 0} channels", size = 13.sp, lines = Int.MAX_VALUE, color = C.Soft)
                OptionRow("✎  Rename") { renaming = true }
                OptionRow("⇅  Re-order") { vm.groupMenu = null; vm.movingGroup = raw }
                if (vm.isSportsGroup(raw)) OptionRow("🏆  Remove from Sports tab") { vm.removeGroupFromSports(raw) }
                else OptionRow("🏆  Add to Sports tab") { vm.addGroupToSports(raw) }
                OptionRow("⊘  Hide category") { vm.toggleHidden(raw); vm.groupMenu = null }
            }
        },
        confirmButton = { TvButton("Done", primary = true) { vm.groupMenu = null } },
    )
}

@Composable
fun OptionRow(label: String, onClick: () -> Unit) {
    Focusable(Modifier.fillMaxWidth().height(44.dp), color = C.Surface2, focusedColor = C.Line, onClick = onClick) {
        Label(label, size = 15.sp, color = C.Text, weight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp))
    }
}

/** TV Guide search: matching channels, then matching programs (on now or later), in this playlist and Favorites. */
@Composable
private fun GuideSearchResults(vm: AppViewModel, modifier: Modifier) {
    val q = vm.guideQuery.lowercase()
    LaunchedEffect(Unit) { vm.loadSearchPool() }
    val pool = remember(vm.searchPool, vm.channels, vm.favDataTick) { (vm.searchPool + vm.channels).distinctBy { it.key }.filter { it.key !in vm.prefs.hiddenChannels } }
    val chHits = remember(q, pool) { pool.filter { vm.channelName(it).lowercase().contains(q) }.take(60) }
    val progHits = remember(q, pool, vm.guide) {
        val now = System.currentTimeMillis()
        pool.flatMap { c -> vm.programs(c).filter { it.end > now && (it.title.lowercase().contains(q) || it.subtitle?.lowercase()?.contains(q) == true) }.map { c to it } }
            .sortedBy { it.second.start }.take(80)
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        item { SectionTitle("Channels (${chHits.size})", Modifier.padding(start = 4.dp, top = 4.dp)) }
        if (chHits.isEmpty()) item { Label("No channels match.", size = 13.sp, modifier = Modifier.padding(start = 4.dp)) }
        itemsIndexed(chHits, key = { i, c -> "c$i-${c.key}" }) { _, c ->
            val now = vm.nowNext(c).first
            Focusable(Modifier.fillMaxWidth().height(50.dp), onMenu = { vm.channelMenu = c.key }, onClick = { vm.tuneAnywhere(c.key) }) {
                Spacer(Modifier.width(8.dp))
                ChannelBadge(vm.channelName(c), c.logo, 32.dp)
                Column(Modifier.padding(horizontal = 10.dp).weight(1f)) {
                    Label(vm.channelName(c) + if (vm.isFavorite(c.key)) "  ★" else "", size = 14.sp, color = C.Text, weight = FontWeight.SemiBold)
                    Label(listOf(vm.groupName(c.group), vm.playlistName(c), now?.let { "Now: ${it.title}" } ?: "").filter { it.isNotBlank() }.joinToString(" · "), size = 12.sp)
                }
                Label(if (vm.isPreviewing(c.key)) "▶ Full screen" else "▶ Preview", size = 13.sp, color = C.Accent, weight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
            }
        }
        item { SectionTitle("Programs (${progHits.size})", Modifier.padding(start = 4.dp, top = 12.dp)) }
        if (progHits.isEmpty()) item { Label("No programs in the guide match.", size = 13.sp, modifier = Modifier.padding(start = 4.dp)) }
        itemsIndexed(progHits, key = { i, _ -> "p$i" }) { _, (c, p) ->
            val onNow = vm.now >= p.start
            Focusable(Modifier.fillMaxWidth().height(50.dp), onClick = { vm.tuneAnywhere(c.key) }) {
                Label(if (onNow) "On now" else "${vm.dayLabel(p.start)} ${vm.fmtTime(p.start)}", size = 12.sp, color = if (onNow) C.Accent else C.Soft, weight = FontWeight.Bold, modifier = Modifier.width(120.dp).padding(start = 12.dp))
                Column(Modifier.weight(1f)) {
                    Label((if (p.isNew) "NEW · " else "") + p.title + (p.subtitle?.let { " · $it" } ?: ""), size = 14.sp, color = C.Text, weight = FontWeight.SemiBold)
                    Label(vm.channelName(c) + " · " + vm.fmtTime(p.start) + " – " + vm.fmtTime(p.end), size = 12.sp)
                }
                Label(if (onNow) "▶ Tune in" else "▶ Channel", size = 13.sp, color = C.Accent, weight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
            }
        }
    }
}
