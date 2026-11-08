package com.avidviewer.tv.ui

import androidx.compose.foundation.background
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

private const val DP_PER_MIN = 4.0f
private const val WINDOW_MIN = 150

@Composable
fun GuideScreen(vm: AppViewModel) {
    if (vm.channels.isEmpty()) {
        EmptyState(vm, "No channels yet", "Add your provider on the Playlists screen.")
        return
    }
    val list = vm.channelsIn(vm.selectedGroup)
    val numbers = vm.numbers()
    val half = 30 * 60 * 1000L
    val winStart = (vm.now / half) * half
    val winEnd = winStart + WINDOW_MIN * 60 * 1000L
    var focusProg by remember { mutableStateOf<Pair<String, Program?>?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rowFocus = remember(list.size, vm.selectedGroup) { List(list.size) { FocusRequester() } }
    var pendingFocus by remember { mutableStateOf<Int?>(null) }

    // Scroll to and focus the current channel (at startup and when returning from full screen).
    LaunchedEffect(vm.guideFocusTick) {
        val idx = list.indexOfFirst { it.key == vm.currentKey }
        if (idx >= 0) {
            delay(150)
            listState.scrollToItem((idx - 2).coerceAtLeast(0))
            pendingFocus = idx
        }
    }
    LaunchedEffect(pendingFocus) {
        val i = pendingFocus ?: return@LaunchedEffect
        delay(60)
        runCatching { rowFocus[i].requestFocus() }
        pendingFocus = null
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
        // Preview window + details of the focused show
        Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(231.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp))) { VideoSurface(vm, Modifier.fillMaxSize()) }
            val fc = vm.channel(focusProg?.first ?: vm.currentKey)
            val fp = focusProg?.second ?: vm.nowNext(fc).first
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label((fc?.let { vm.channelName(it) } ?: "") + (fp?.let { " · ${vm.fmtTime(it.start)} – ${vm.fmtTime(it.end)}" } ?: ""), size = 12.sp)
                Label(fp?.title ?: "No guide information", size = 18.sp, color = C.Text, weight = FontWeight.Bold)
                Label(listOfNotNull(fp?.subtitle?.let { "“$it”" }, if (fp?.isNew == true) "New" else null).joinToString(" · "), size = 13.sp, color = C.Soft)
                Label("OK: preview · OK again: full screen · CH+/CH−: page · Menu: favorite", size = 11.sp)
            }
        }
        // Groups
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(vm.groups(), key = { it.raw }) { g ->
                TvButton(g.name, selected = g.raw == vm.selectedGroup) { vm.selectedGroup = g.raw }
            }
        }
        // Timeline header
        Row(Modifier.fillMaxWidth().height(18.dp)) {
            Label("Today · ${vm.zoneLabel()}", size = 11.sp, modifier = Modifier.width(150.dp))
            Box(Modifier.weight(1f)) {
                for (i in 0 until WINDOW_MIN / 30) {
                    Label(vm.fmtTime(winStart + i * half), size = 11.sp, modifier = Modifier.offset(x = (i * 30 * DP_PER_MIN).dp))
                }
            }
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(list, key = { _, c -> c.key }) { idx, c ->
                    Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Focusable(
                            modifier = Modifier.width(146.dp).fillMaxHeight().focusRequester(rowFocus[idx]),
                            onFocus = { focusProg = c.key to vm.nowNext(c).first },
                            onMenu = { vm.toggleFavorite(c.key) },
                            onClick = { vm.selectChannel(c.key, PlayContext.GROUP) },
                        ) {
                            Label("${numbers[c.key] ?: ""}", size = 11.sp, modifier = Modifier.width(26.dp).padding(start = 6.dp))
                            ChannelBadge(vm.channelName(c), c.logo, 26.dp)
                            Label(vm.channelName(c), size = 12.sp, color = C.Text, weight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, end = 4.dp))
                        }
                        val progs = vm.programs(c).filter { it.end > winStart && it.start < winEnd }
                        if (progs.isEmpty()) {
                            Focusable(
                                modifier = Modifier.width((WINDOW_MIN * DP_PER_MIN).dp).fillMaxHeight(),
                                color = C.Surface.copy(alpha = 0.6f),
                                onFocus = { focusProg = c.key to null },
                                onClick = { vm.selectChannel(c.key, PlayContext.GROUP) },
                            ) { Label("No guide information", size = 12.sp, modifier = Modifier.padding(horizontal = 10.dp)) }
                        } else {
                            var cursor = winStart
                            progs.forEach { p ->
                                val a = maxOf(p.start, winStart); val b = minOf(p.end, winEnd)
                                if (a > cursor) Box(Modifier.width((((a - cursor) / 60000f * DP_PER_MIN).dp - 3.dp).coerceAtLeast(0.dp)))
                                val isNow = vm.now >= p.start && vm.now < p.end
                                Focusable(
                                    modifier = Modifier.width(((((b - a) / 60000f) * DP_PER_MIN).dp - 3.dp).coerceAtLeast(4.dp)).fillMaxHeight(),
                                    color = if (isNow) C.Surface2 else C.Surface,
                                    focusedColor = C.Line,
                                    onFocus = { focusProg = c.key to p },
                                    onMenu = { vm.toggleFavorite(c.key) },
                                    onClick = { vm.selectChannel(c.key, PlayContext.GROUP) },
                                ) {
                                    Column(Modifier.padding(horizontal = 8.dp)) {
                                        Label((if (p.isNew) "NEW · " else "") + p.title, size = 12.sp, color = C.Text, weight = FontWeight.SemiBold)
                                        Label("${vm.fmtTime(p.start)} – ${vm.fmtTime(p.end)}", size = 10.sp)
                                    }
                                }
                                cursor = b
                            }
                        }
                    }
                }
            }
            // "Now" line
            val nowX = 150f + ((vm.now - winStart) / 60000f) * DP_PER_MIN
            Box(Modifier.offset(x = nowX.dp).width(2.dp).fillMaxHeight().background(C.Accent))
        }
    }
}
