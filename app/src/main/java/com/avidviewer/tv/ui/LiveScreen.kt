package com.avidviewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avidviewer.tv.data.Channel
import com.avidviewer.tv.data.Program
import kotlinx.coroutines.delay

@Composable
fun LiveScreen(vm: AppViewModel) {
    if (vm.channels.isEmpty()) {
        EmptyState(vm, "No channels yet", "Add your provider on the Playlists screen.")
        return
    }
    val numbers = vm.numbers()
    val list0 = vm.channelsIn(vm.selectedGroup)
    val rowFocus = remember(list0.size, vm.selectedGroup) { List(list0.size) { FocusRequester() } }
    val listState = rememberLazyListState()
    // Land on the playing channel (or the first one) after choosing a category.
    LaunchedEffect(vm.guideFocusTick, vm.selectedGroup) {
        if (vm.navMode != NavMode.GROUPS || list0.isEmpty()) return@LaunchedEffect
        val idx = list0.indexOfFirst { it.key == vm.currentKey }.coerceAtLeast(0)
        listState.scrollToItem(idx)
        focusWhenReady(vm) { runCatching { rowFocus[idx].requestFocus() }.isSuccess }
    }

    Row(Modifier.fillMaxSize()) {
        val list = vm.channelsIn(vm.selectedGroup)
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            state = listState,
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            itemsIndexed(list, key = { _, c -> c.key }) { i, c ->
                ChannelRow(vm, c, numbers[c.key], selected = c.key == vm.currentKey, modifier = Modifier.focusRequester(rowFocus.getOrElse(i) { FocusRequester() }),
                    onFocus = {},
                    onClick = { vm.selectChannel(c.key, PlayContext.GROUP) })
            }
        }
        PreviewPane(vm, Modifier.width(270.dp).fillMaxHeight().padding(12.dp))
    }
}

@Composable
fun ChannelRow(vm: AppViewModel, c: Channel, num: Int?, selected: Boolean, modifier: Modifier = Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val (now, _) = vm.nowNext(c)
    Focusable(
        modifier = modifier.fillMaxWidth().height(56.dp),
        color = if (selected) C.Surface2 else C.Surface,
        onFocus = onFocus,
        onMenu = { vm.listPickerKey = c.key },
        onClick = onClick,
    ) {
        Label("${num ?: ""}", size = 12.sp, modifier = Modifier.width(34.dp).padding(start = 8.dp))
        ChannelBadge(vm.channelName(c), c.logo, 34.dp)
        Column(Modifier.padding(horizontal = 10.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(vm.channelName(c), size = 14.sp, color = C.Text, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                if (vm.isFavorite(c.key)) Text("  ★", color = C.Accent, fontSize = 12.sp)
                if (selected) Text("  ▶ Playing", color = C.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Label(now?.let { "${it.title} · ${vm.fmtTime(it.start)}–${vm.fmtTime(it.end)}" } ?: "No guide information", size = 12.sp)
            ProgressBar(now, vm.now, Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
fun ProgressBar(p: Program?, now: Long, modifier: Modifier = Modifier) {
    val frac = if (p == null) 0f else ((now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { frac },
        modifier = modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
        color = C.Accent,
        trackColor = C.Line,
        drawStopIndicator = {},
    )
}

@Composable
private fun PreviewPane(vm: AppViewModel, modifier: Modifier) {
    val c = vm.channel(vm.currentKey)
    val (now, next) = vm.nowNext(c)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
            VideoSurface(vm, Modifier.fillMaxSize())
            if (vm.player.mediaItemCount == 0) Label("Press OK on a channel to preview it", size = 12.sp, color = C.Muted, lines = 2, modifier = Modifier.align(Alignment.Center).padding(10.dp))
            vm.playerError?.let {
                Label(it, size = 12.sp, color = C.Soft, lines = 4, modifier = Modifier.align(Alignment.Center).padding(10.dp))
            }
        }
        if (c != null) {
            Label("${vm.numbers()[c.key] ?: ""} · ${vm.channelName(c)}", size = 12.sp)
            Label(now?.title ?: "No guide information", size = 18.sp, color = C.Text, weight = FontWeight.Bold, lines = 2)
            now?.let {
                Label("${vm.fmtTime(it.start)} – ${vm.fmtTime(it.end)}" + (it.subtitle?.let { s -> " · $s" } ?: ""), size = 12.sp)
                ProgressBar(it, vm.now)
            }
            next?.let { Label("Next: ${it.title} · ${vm.fmtTime(it.start)}", size = 12.sp, color = C.Soft, lines = 2) }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("Watch", primary = true) { vm.openFullScreen(c.key, PlayContext.GROUP) }
                TvButton(if (vm.isFavorite(c.key)) "★ Saved" else "☆ Favorite") { vm.toggleFavorite(c.key) }
            }
            Label("OK on a channel: preview · OK again: full screen · Menu or hold OK: favorite", size = 12.sp, lines = Int.MAX_VALUE)
        }
    }
}

@Composable
fun EmptyState(vm: AppViewModel, title: String, body: String) {
    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Label(body, size = 15.sp, color = C.Soft, lines = 3)
        if (vm.busy) Label(vm.status ?: "Loading…", size = 14.sp, lines = Int.MAX_VALUE)
        TvButton("Add a playlist", primary = true) { vm.navSelect(Screen.PLAYLISTS) }
    }
}
