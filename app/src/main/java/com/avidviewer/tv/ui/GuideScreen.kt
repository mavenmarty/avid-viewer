package com.avidviewer.tv.ui

import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
/** How far ahead the guide can scroll at least; it goes further when the guide data does (up to [GUIDE_DAYS] days). */
private const val AHEAD_MIN = 12 * 60
private const val HALF_MS = 30 * 60 * 1000L
/** How far back the guide can scroll, in half hours: 2 hours of programmes that have aired, dimmed (GUIDE-04). */
private const val BACK_SLOTS = -4
/** Key repeats of a held Left before the guide jumps back to now. */
private const val HOLD_TO_NOW = 4

/**
 * The start of the half hour [ms] falls in, on the clock of [zone]: the guide's columns sit on local :00 and :30
 * even in zones that are 15 or 45 minutes off the hour (GUIDE-03).
 */
fun localHalfHour(ms: Long, zone: java.util.TimeZone): Long {
    val off = zone.getOffset(ms).toLong()
    return Math.floorDiv(ms + off, HALF_MS) * HALF_MS - off
}

/** The start of the local day [ms] falls in, on the clock of [zone]. */
fun localDayStart(ms: Long, zone: java.util.TimeZone): Long =
    java.util.Calendar.getInstance(zone).apply {
        timeInMillis = ms; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

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
    // The visible 2.5-hour window starts at the current (local) half hour plus [slots] × 30 minutes: back 2 hours,
    // ahead as far as the guide data goes (at least 12 hours, at most GUIDE_DAYS days).
    val zone = vm.timeZone
    val base = localHalfHour(vm.now, zone)
    val aheadMs = maxOf(AHEAD_MIN * 60_000L, vm.guideLastEnd - base)
    val maxSlots = ((aheadMs - WINDOW_MIN * 60_000L) / half).toInt().coerceAtLeast(0) + 1
    // The window and the highlighted time are kept in the navigation store, so leaving the guide
    // (full screen, another screen) comes back to the same time (NAV-05).
    // A remembered window from long ago (or none yet) opens at now.
    var slots by remember { mutableStateOf(Math.floorDiv(vm.nav.guideWindowStart - base, half).let { if (it < BACK_SLOTS || it > Int.MAX_VALUE) 0 else it.toInt() }) }
    slots = slots.coerceIn(BACK_SLOTS, maxSlots)
    val winStart = base + slots * half
    val winEnd = winStart + WINDOW_MIN * 60 * 1000L
    var focusProg by remember { mutableStateOf<Pair<String, Program?>?>(null) }
    /** Which channel name cell has the highlight (-1 = none). */
    var focusedRow by remember { mutableStateOf(-1) }
    /** The channel (by key) whose name cell has the highlight; survives rows being replaced. */
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val previewFr = remember { FocusRequester() }

    // The time the highlight is on. Up/Down lands on the program airing at this time in the next row,
    // whatever the run times are; it only changes when you move left or right.
    var anchor by remember { mutableStateOf(vm.nav.guideAnchor.takeIf { it >= base + BACK_SLOTS * half } ?: vm.now) }
    LaunchedEffect(slots, anchor) { vm.nav.guideWindowStart = winStart; vm.nav.guideAnchor = anchor }
    val path = vm.guidePath(vm.selectedGroup)
    // Days ahead: the programmes for the times on screen are read for this category when they aren't in memory.
    LaunchedEffect(winStart, path, list.size) { vm.guideViewing(list, winStart, winEnd) }
    // Focus handles per channel, made only for rows that are actually shown (an 8,000-channel category
    // used to allocate 16,000 of them up front).
    val progFocusMap = remember(vm.selectedGroup) { HashMap<String, FocusRequester>() }
    fun progFr(i: Int): FocusRequester? = list.getOrNull(i)?.let { progFocusMap.getOrPut(it.key) { FocusRequester() } }
    var pendingProg by remember { mutableStateOf<Int?>(null) }
    // Each category keeps its scroll by the first visible channel's key (a changed list doesn't shift it).
    val listState = remember(path) {
        val spot = vm.nav[path]
        val i = spot?.let { sp -> NavStore.find(list.size, { list[it].key }, sp.firstKey, sp.firstIndex) } ?: -1
        if (i >= 0) androidx.compose.foundation.lazy.LazyListState(i, spot!!.offset) else androidx.compose.foundation.lazy.LazyListState()
    }
    val listNow by androidx.compose.runtime.rememberUpdatedState(list)
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (i, o) -> vm.nav.scrolled(path, listNow.getOrNull(i)?.key, i, o) }
    }
    /** Where the remembered channel of this category is now (-1 = nothing remembered). */
    fun rememberedRow(): Int = vm.nav[path]?.let { sp -> NavStore.find(list.size, { list[it].key }, sp.key, sp.index) } ?: -1
    val scope = rememberCoroutineScope()
    val rowFocusMap = remember(vm.selectedGroup) { HashMap<String, FocusRequester>() }
    fun rowFr(i: Int): FocusRequester? = list.getOrNull(i)?.let { rowFocusMap.getOrPut(it.key) { FocusRequester() } }
    var pendingFocus by remember { mutableStateOf<Int?>(null) }

    /** Scrolls only if row [i] isn't already comfortably on screen (a restored scroll stays as it was). */
    fun bringIntoView(i: Int) {
        val vis = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (vis.isEmpty() || i <= vis.first() || i >= vis.last()) scope.launch { listState.scrollToItem((i - 2).coerceAtLeast(0)) }
    }

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
            // A category seen before: the channel it was on (or its nearest neighbour if that one is gone).
            GuideFocus.KEEP -> {
                val idx = rememberedRow().let { if (it < 0) listState.firstVisibleItemIndex.coerceIn(0, list.size - 1) else it }
                bringIntoView(idx)
                pendingFocus = idx
            }
            // Startup or back from full screen: the channel that was playing, on the programme that was highlighted.
            GuideFocus.CURRENT -> {
                val idx = list.indexOfFirst { it.key == vm.currentKey }.let { if (it < 0) 0 else it }
                bringIntoView(idx)
                if (vm.nav.guideOnProgram && vm.nav[path]?.key == vm.currentKey) pendingProg = idx else pendingFocus = idx
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
            val r = runCatching { rowFr(i)?.requestFocus() }
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
        focusWhenReady(vm, frames = 30, isFocused = { movingFocused }) { runCatching { rowFr(movingIdx)?.requestFocus() }.isSuccess }
    }
    LaunchedEffect(pendingProg, slots) {
        val i = pendingProg ?: return@LaunchedEffect
        // Retry for a few frames: the row may still be coming on screen after a scroll.
        for (frame in 0 until 30) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { progFr(i)!!.requestFocus() }.isSuccess && frame > 1) break
        }
        pendingProg = null
    }

    /** Puts the highlight on a channel name row, scrolling it into view (used for wrap-around). */
    fun wrapTo(i: Int) {
        val target = if (i == 0) 0 else (i - (listState.layoutInfo.visibleItemsInfo.size - 2)).coerceAtLeast(0)
        scope.launch { listState.scrollToItem(target) }
        pendingFocus = i
    }

    // The row a quick press is heading to before the highlight gets there (-1 = none): fast presses
    // count from it, not from the row that still has the highlight.
    val aim = remember(path) { intArrayOf(-1) }

    /**
     * Up/Down on a channel name, done here rather than by Compose's focus search. After a jump (a wrap
     * from the top to the bottom of 8,000 channels) the highlight can still sit on a row far off screen;
     * Compose then laid out row after row across the gap looking for the next one, and the app froze.
     */
    fun moveName(idx: Int, dir: Int, steps: Int, fresh: Boolean): Boolean {
        val from = aim[0].takeIf { it in list.indices } ?: idx
        if (dir > 0 && from == list.lastIndex) { if (fresh && list.size > 1) { aim[0] = 0; wrapTo(0) }; return true }
        if (dir < 0 && from == 0) {
            if (idx == 0) { aim[0] = -1; return false } // Up from the first channel goes to the preview, as before
            aim[0] = 0; wrapTo(0); return true
        }
        val target = (from + dir * steps).coerceIn(0, list.size - 1)
        aim[0] = target
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (visible.size > 2 && target > visible.first() && target < visible.last() && runCatching { rowFr(target)!!.requestFocus() }.isSuccess) return true
        scope.launch {
            listState.scrollToItem((if (dir > 0) target - (visible.size - 3).coerceAtLeast(0) else target - 1).coerceAtLeast(0))
            pendingFocus = target
        }
        return true
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
        val need = if (forward) Math.floorDiv((time + half - (base + WINDOW_MIN * 60 * 1000L)) + half - 1, half) else Math.floorDiv(time - base, half)
        val n = need.toInt().coerceIn(BACK_SLOTS, maxSlots)
        if (n == slots) return
        slots = n
        anchor = time
        pendingProg = row
    }

    /** Puts the window on [time] (Now, a day in the day strip, or holding Left); [row] takes the highlight if given. */
    fun jumpTo(time: Long, row: Int?) {
        val n = Math.floorDiv(localHalfHour(time, zone) - base, half).toInt().coerceIn(BACK_SLOTS, maxSlots)
        slots = n
        anchor = time.coerceAtLeast(base + n * half)
        if (row != null) pendingProg = row
    }
    // The day strip (Now, Tomorrow, Sat…): shown when the guide reaches past today.
    val today = localDayStart(vm.now, zone)
    val days = remember(today, vm.guideLastEnd) {
        val out = ArrayList<Long>()
        var d = localDayStart(today + 26 * 3600 * 1000L, zone)
        while (d < vm.guideLastEnd && out.size < GUIDE_DAYS) { out.add(d); d = localDayStart(d + 26 * 3600 * 1000L, zone) }
        out
    }
    val dayFr = remember { FocusRequester() }

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
            Modifier.fillMaxWidth().height(130.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PreviewWindow(vm, Modifier.width(231.dp).fillMaxHeight().focusRequester(previewFr).onPreviewKeyEvent { e ->
                // Up from the preview (the top of the screen) wraps to the last channel; a fresh press only.
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && e.nativeKeyEvent.repeatCount == 0 && list.size > 1) { aim[0] = list.lastIndex; wrapTo(list.lastIndex); true } else false
            }, onOpen = { vm.openFullScreen(vm.currentKey, PlayContext.GROUP) })
            val fc = vm.channel(focusProg?.first ?: vm.currentKey)
            DetailsPanel(vm, fc, focusProg?.second, numbers[fc?.key], Modifier.weight(1f).fillMaxHeight())
        }
        // Search lives in the category list now (🔍 Search, under ＋ New list); results replace the guide.
        if (vm.guideQuery.isNotBlank()) Label("Results for “${vm.guideQuery}”  ·  clear it with ✕ in the category list", size = 13.sp, color = C.Soft, weight = FontWeight.Bold)
        if (vm.selectedGroup == FAV_GROUP && vm.guideQuery.isBlank()) LocalSuggestions(vm)
        if (list.isEmpty() && vm.selectedGroup == FAV_GROUP) {
            Label("No favorites yet. Press Menu (or hold OK) on any channel and choose Add to List — favorites from every playlist appear here.", size = 13.sp, color = C.Soft, lines = Int.MAX_VALUE)
        } else if (list.isEmpty() && isListGroup(vm.selectedGroup)) {
            Label("This list is empty. Press Menu (or hold OK) on any channel and choose Add to List. Channels from every playlist can go in one list.", size = 13.sp, color = C.Soft, lines = Int.MAX_VALUE)
        }
        if (vm.guideQuery.isNotBlank()) {
            GuideSearchResults(vm, Modifier.weight(1f))
        } else {
        if (days.isNotEmpty()) DayStrip(vm, days, winStart, dayFr, previewFr,
            onNow = { jumpTo(vm.now, null) },
            onDay = { d -> jumpTo(d + (vm.now - today).coerceIn(0, 24 * 3600 * 1000L - 1), null) })
        // Timeline header: where you are (category · day · zone), then the half-hour marks.
        Row(Modifier.fillMaxWidth().height(18.dp)) {
            Label(listOfNotNull(vm.selectedGroup?.let { vm.groupName(it) }, vm.dayLabel(winStart)).joinToString(" · ") + " · ${vm.zoneLabel()}" + if (slots != 0) "  ◀" else "",
                size = 12.sp, modifier = Modifier.width((CH_COL + 3).dp))
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
                            modifier = Modifier.width(CH_COL.dp).fillMaxHeight().focusRequester(rowFr(idx)!!)
                                .then(if (idx == 0) Modifier.focusProperties { up = if (days.isNotEmpty()) dayFr else previewFr } else Modifier)
                                .then(if (isMoving) Modifier.border(2.dp, C.AccentFocus, RoundedCornerShape(8.dp)) else Modifier)
                                .onFocusChanged {
                                    if (isMoving) movingFocused = it.isFocused
                                    if (it.isFocused) { focusedRow = idx; focusedKey = c.key; vm.nav.focused(path, c.key, idx); vm.nav.guideOnProgram = false; if (aim[0] == idx) aim[0] = -1 }
                                    else if (focusedKey == c.key) { focusedRow = -1; focusedKey = null }
                                }
                                .onPreviewKeyEvent { e ->
                                    if (isMoving) reorderKeys(e, onMove = { vm.moveChannelBy(c.key, it) }, onTop = { vm.moveChannelTo(c.key, 0) },
                                        onBottom = { vm.moveChannelTo(c.key, Int.MAX_VALUE) }, onDone = { vm.movingChannel = null })
                                    // Left from a channel name goes back to the category list, on the current category (like Back).
                                    else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && e.nativeKeyEvent.repeatCount == 0 && vm.navMode == NavMode.GROUPS && vm.selectedGroup != null) {
                                        vm.navFocusKey = vm.selectedGroup; true
                                    }
                                    // Up/Down move by channel (holding speeds up); Down on the last channel (a fresh press) goes back to the first.
                                    else if (e.type == KeyEventType.KeyDown && (e.key == Key.DirectionDown || e.key == Key.DirectionUp)) {
                                        moveName(idx, if (e.key == Key.DirectionDown) 1 else -1, holdSteps(e), e.nativeKeyEvent.repeatCount == 0)
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
                        val progs = vm.programsAt(c, winStart, winEnd).filter { it.end > winStart && it.start < winEnd }
                        if (progs.isEmpty()) {
                            Focusable(
                                modifier = Modifier.width((WINDOW_MIN * DP_PER_MIN).dp).fillMaxHeight().focusRequester(progFr(idx)!!)
                                    .onPreviewKeyEvent { e ->
                                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (e.key) {
                                            Key.DirectionUp -> moveRow(idx, -1, holdSteps(e), e.nativeKeyEvent.repeatCount == 0)
                                            Key.DirectionDown -> moveRow(idx, 1, holdSteps(e), e.nativeKeyEvent.repeatCount == 0)
                                            else -> false
                                        }
                                    },
                                color = C.Surface.copy(alpha = 0.6f),
                                onFocus = { focusProg = c.key to null; vm.nav.focused(path, c.key, idx); vm.nav.guideOnProgram = true },
                                onClick = { vm.selectChannel(c.key, PlayContext.GROUP) },
                            ) { Label("No guide information", size = 12.sp, modifier = Modifier.padding(horizontal = 10.dp)) }
                        } else {
                            var cursor = winStart
                            // Programmes that overlap (some guides list them) each start where the one before ends;
                            // laid out as listed, they pushed the row past the screen edge, where Right couldn't reach them.
                            val laid = ArrayList<Triple<Program, Long, Long>>()
                            run { var end = winStart; for (p in progs) { val a = maxOf(p.start, end); val b = minOf(p.end, winEnd); if (b > a) { laid.add(Triple(p, a, b)); end = b } } }
                            // The program at the remembered time (or the nearest one) takes Up/Down focus for this row.
                            val at = anchor.coerceIn(winStart, winEnd - 1)
                            val target = (laid.firstOrNull { at >= it.second && at < it.third }
                                ?: laid.firstOrNull { it.second >= at } ?: laid.last()).first
                            laid.forEachIndexed { pi, (p, a, b) ->
                                if (a > cursor) Box(Modifier.width((((a - cursor) / 60000f * DP_PER_MIN).dp - 3.dp).coerceAtLeast(0.dp)))
                                val isNow = vm.now >= p.start && vm.now < p.end
                                // Already aired: dimmed; OK still tunes the channel live (there's no catch-up).
                                val aired = p.end <= vm.now
                                Focusable(
                                    modifier = Modifier.width(((((b - a) / 60000f) * DP_PER_MIN).dp - 3.dp).coerceAtLeast(4.dp)).fillMaxHeight()
                                        .then(if (p === target) Modifier.focusRequester(progFr(idx)!!) else Modifier)
                                        .then(if (aired) Modifier.semantics { stateDescription = "Already aired" } else Modifier)
                                        .onPreviewKeyEvent { e ->
                                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                            when (e.key) {
                                                Key.DirectionUp -> moveRow(idx, -1, holdSteps(e), e.nativeKeyEvent.repeatCount == 0)
                                                Key.DirectionDown -> moveRow(idx, 1, holdSteps(e), e.nativeKeyEvent.repeatCount == 0)
                                                // Last program in view: scroll the guide forward to the next program.
                                                Key.DirectionRight -> if (pi == laid.lastIndex && slots < maxSlots) { shiftTo(maxOf(p.end, winEnd - 60_000L), idx, true); true } else false
                                                // Holding Left on a later window jumps straight back to now (NAV-15).
                                                // First program in view: scroll back, up to 2 hours (at the end, Left goes to the channel name).
                                                Key.DirectionLeft -> when {
                                                    slots > 0 && e.nativeKeyEvent.repeatCount >= HOLD_TO_NOW -> { jumpTo(vm.now, idx); true }
                                                    pi == 0 && slots > BACK_SLOTS -> { shiftTo(minOf(p.start, winStart) - 60_000L, idx, false); true }
                                                    else -> false
                                                }
                                                else -> false
                                            }
                                        },
                                    color = if (isNow) C.Surface2 else if (aired) C.Surface.copy(alpha = 0.45f) else C.Surface,
                                    focusedColor = C.FocusFill,
                                    onFocus = {
                                        focusProg = c.key to p
                                        vm.nav.focused(path, c.key, idx); vm.nav.guideOnProgram = true
                                        // Keep the remembered time if it falls in this program; otherwise it moves here.
                                        if (anchor < a || anchor >= b) anchor = a
                                    },
                                    onMenu = { vm.channelMenu = c.key },
                                    onClick = { vm.selectChannel(c.key, PlayContext.GROUP) },
                                ) {
                                    Column(Modifier.padding(horizontal = 8.dp)) {
                                        Label((if (p.isNew && !aired) "NEW · " else "") + p.title, size = 12.sp, color = if (aired) C.Muted else C.Text, weight = FontWeight.SemiBold)
                                        Label("${vm.fmtTime(p.start)} – ${vm.fmtTime(p.end)}", size = 12.sp, color = if (aired) C.Muted.copy(alpha = 0.7f) else C.Muted)
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

/**
 * Now, then one chip per day the guide reaches (Tomorrow, Sat, Sun…). The chip of the day on screen is marked;
 * OK moves the guide there, at the same time of day as now (GUIDE-01).
 */
@Composable
private fun DayStrip(vm: AppViewModel, days: List<Long>, winStart: Long, first: FocusRequester, previewFr: FocusRequester, onNow: () -> Unit, onDay: (Long) -> Unit) {
    val onDayShown = days.lastOrNull { winStart >= it }
    Row(Modifier.fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        DayChip("Now", onDayShown == null && winStart <= vm.now, Modifier.focusRequester(first).focusProperties { up = previewFr }, onNow)
        days.forEach { d -> DayChip(vm.dayLabel(d + 12 * 3600 * 1000L), d == onDayShown, Modifier.focusProperties { up = previewFr }) { onDay(d) } }
    }
}

@Composable
private fun DayChip(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Focusable(modifier = modifier.height(26.dp), shape = RoundedCornerShape(13.dp), color = if (on) C.Accent else C.Surface2, focusedColor = if (on) C.AccentFocus else C.FocusFill, onClick = onClick) {
        Text(text, color = if (on) C.OnAccent else C.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

/** Menu on a channel: favorite, rename, move, hide. Stays open so Move can be pressed repeatedly. */
@Composable
fun ChannelOptionsDialog(vm: AppViewModel) {
    val key = vm.channelMenu ?: return
    val c = vm.channel(key) ?: run { vm.channelMenu = null; return }
    var renaming by remember(key) { mutableStateOf(false) }
    // Cancel closes the menu too, like Save: the highlight goes back to the channel (FOCUS-02).
    if (renaming) {
        RenameDialog("Rename channel", vm.channelName(c), onDismiss = { renaming = false; vm.channelMenu = null }) { vm.renameChannel(key, it); renaming = false; vm.channelMenu = null }
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
                OptionRow("▤  Match guide…" + if (vm.prefs.epgMap.containsKey(key)) "   (matched by hand)" else "") { vm.channelMenu = null; vm.matchingChannel = key }
                if (!isListGroup(vm.selectedGroup)) OptionRow("⊘  Hide channel") { vm.hideChannel(key); vm.channelMenu = null }
            }
        },
        confirmButton = { TvButton("Done", primary = true) { vm.channelMenu = null } },
    )
}

/**
 * What's on the highlighted channel: number and name, the programme (with a New tag), its times,
 * time left and progress, up to two lines about it, and what's next. No key instructions.
 */
@Composable
private fun DetailsPanel(vm: AppViewModel, c: com.avidviewer.tv.data.Channel?, chosen: com.avidviewer.tv.data.Program?, number: Int?, modifier: Modifier) {
    val (nowP, nextP) = vm.nowNext(c)
    val p = chosen ?: nowP
    val following = if (chosen == null || chosen == nowP) nextP else vm.programs(c).firstOrNull { it.start >= chosen.end }
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(listOfNotNull(number?.toString(), c?.let { vm.channelName(it) }).joinToString(" · ").ifBlank { "No channel" }, size = 13.sp, color = C.Text, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
            if (vm.livePlaylists.size > 1) c?.let { Label(vm.playlistName(it), size = 11.sp, color = C.Muted) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(p?.title ?: "No guide information", size = 18.sp, color = C.Text, weight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
            if (p?.isNew == true) Label("New", size = 11.sp, color = C.OnAccent, weight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(C.Accent).padding(horizontal = 6.dp, vertical = 1.dp))
        }
        if (p != null) {
            val now = vm.now
            val airing = now >= p.start && now < p.end
            val left = ((p.end - now) / 60000L).coerceAtLeast(0)
            val untilStart = ((p.start - now) / 60000L).coerceAtLeast(0)
            Label("${vm.fmtTime(p.start)} – ${vm.fmtTime(p.end)}" + when {
                airing -> " · $left min left"
                p.start > now -> " · " + (if (vm.dayLabel(p.start) != "Today") vm.dayLabel(p.start) else "starts in " + (if (untilStart >= 60) "${untilStart / 60} h ${untilStart % 60} min" else "$untilStart min"))
                else -> " · already aired · OK plays the channel live"
            }, size = 12.sp, color = if (airing) C.Accent else C.Soft)
            if (airing) {
                val frac = ((now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Line)) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(C.Accent))
                }
            }
            p.subtitle?.let { Label(it, size = 12.sp, color = C.Soft, lines = 2) }
        }
        following?.let { Label("Next ${vm.fmtTime(it.start)} · ${it.title}", size = 12.sp, color = C.Muted) }
    }
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
        RenameDialog("Rename category", vm.groupName(raw), onDismiss = { renaming = false }) { vm.renameGroup(raw, it); renaming = false; vm.groupMenu = null }
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
                OptionRow("⊘  Hide category") { vm.toggleHidden(raw); vm.groupMenu = null }
            }
        },
        confirmButton = { TvButton("Done", primary = true) { vm.groupMenu = null } },
    )
}

@Composable
fun OptionRow(label: String, onClick: () -> Unit) {
    Focusable(Modifier.fillMaxWidth().height(44.dp), color = C.Surface2, focusedColor = C.FocusFill, onClick = onClick) {
        Label(label, size = 15.sp, color = C.Text, weight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp))
    }
}

/** TV Guide search: matching channels, then matching programs (on now or later), in this playlist and Favorites. */
@Composable
private fun GuideSearchResults(vm: AppViewModel, modifier: Modifier) {
    // Searched in the view model: this playlist in memory, the others in the database (only matches kept).
    LaunchedEffect(vm.guideQuery, vm.channels, vm.guide) { vm.runGuideSearch(vm.guideQuery) }
    // Search over: let go of the other playlists' matches.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.endGuideSearch() } }
    val chHits = vm.guideSearchHits.channels
    val progHits = vm.guideSearchHits.programs
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
