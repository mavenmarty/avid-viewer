package com.avidviewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avidviewer.tv.data.VodItem

/**
 * Nov 26, 2026 (owner, design board 51): Continue watching on the main menu. A row each for Live TV (the last 10
 * channels this profile watched, on any of its TVs), Series and Movies, in that order. Each row scrolls sideways
 * (Left/Right); Up/Down changes row. OK on a channel tunes it live; on a series or movie it opens its page where the
 * profile left off. Menu: Remove (on every TV); on a channel also Clear Live TV history.
 */
@Composable
fun ContinueScreen(vm: AppViewModel) {
    val live = if (vm.kidsMode) emptyList() else vm.recent
    val series = vm.continueTitles("series")
    val movies = vm.continueTitles("movie")
    LaunchedEffect(Unit) { vm.refreshRecent() }
    // The tile to highlight: the one came back from, else the first one.
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun fr(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val firstKey = live.firstOrNull()?.let { "live:" + it.key } ?: series.firstOrNull()?.key ?: movies.firstOrNull()?.key
    LaunchedEffect(vm.cwFocusTick, firstKey) {
        if (vm.cwFocusTick == 0 && vm.entryFocusFor != Screen.CONTINUE) return@LaunchedEffect
        val keys = live.map { "live:" + it.key } + series.map { it.key } + movies.map { it.key }
        val want = vm.cwFocusKey?.takeIf { it in keys } ?: firstKey ?: return@LaunchedEffect
        focusWhenReady(vm) { runCatching { fr(want).requestFocus() }.isSuccess }
        if (vm.entryFocusFor == Screen.CONTINUE) vm.entryFocusFor = null
    }
    var menuFor by remember { mutableStateOf<Pair<String, Any>?>(null) } // "live" to key, or "title" to VodItem

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Label("Continue watching", size = 26.sp, color = C.Text, weight = FontWeight.Bold)
            Label("from every TV this profile uses" + if (live.isNotEmpty() || series.isNotEmpty() || movies.isNotEmpty()) " · ${MenuKey.short} for options" else "", size = 13.sp, color = C.Soft, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (live.isEmpty() && series.isEmpty() && movies.isEmpty()) {
            Label(
                "Nothing to continue yet. Live channels you watch for 2 minutes or more, and movies and episodes you stop part-way, appear here" +
                    (if (vm.kidsMode) "." else ", from every TV this profile uses."),
                size = 15.sp, color = C.Soft, lines = Int.MAX_VALUE, modifier = Modifier.padding(end = 28.dp).width(720.dp),
            )
            TvButton("Open Live TV", primary = true, modifier = Modifier.entryFocus(vm, Screen.CONTINUE).testTag("cw-empty-live")) { vm.navSelect(Screen.GUIDE) }
            return@Column
        }
        if (live.isNotEmpty()) CwRow("Live TV", "last ${live.size} channel" + (if (live.size == 1) "" else "s") + ", newest first", "cw-live") {
            items(live, key = { "live:" + it.key }) { r ->
                LiveTile(vm, r, Modifier.focusRequester(fr("live:" + r.key)), onMenu = { menuFor = "live" to r.key }) { vm.watchRecent(r.key) }
            }
        }
        if (series.isNotEmpty()) CwRow("Series", "new episodes first, then the last watched", "cw-series") {
            items(series, key = { it.key }) { v -> TitleTile(vm, v, Modifier.focusRequester(fr(v.key)), onMenu = { menuFor = "title" to v }) { vm.openFromContinue(v) } }
        }
        if (movies.isNotEmpty()) CwRow("Movies", "part-way, the last watched first", "cw-movies") {
            items(movies, key = { it.key }) { v -> TitleTile(vm, v, Modifier.focusRequester(fr(v.key)), onMenu = { menuFor = "title" to v }) { vm.openFromContinue(v) } }
        }
        Spacer(Modifier.height(24.dp))
    }

    menuFor?.let { (kind, what) ->
        val close = { menuFor = null }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = close,
            containerColor = C.Surface,
            title = {
                Label(if (kind == "live") vm.channel(what as String)?.let { vm.channelName(it) } ?: "Channel" else (what as VodItem).name, size = 18.sp, color = C.Text, weight = FontWeight.Bold, lines = 2)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton("Remove from Continue watching", primary = true, modifier = Modifier.fillMaxWidth().testTag("cw-remove")) {
                        if (kind == "live") vm.removeRecent(what as String) else vm.removeFromContinue(what as VodItem)
                        close()
                    }
                    if (kind == "live") TvButton("Clear Live TV history", modifier = Modifier.fillMaxWidth().testTag("cw-clear-live")) { vm.clearRecent(); close() }
                    if (kind == "live") Label("Clearing takes every channel off Continue watching here and on this profile's other TVs.", size = 12.sp, color = C.Soft, lines = 3)
                }
            },
            confirmButton = { TvButton("Cancel") { close() } },
        )
    }
}

/** One row: its name, a note, and its tiles in a list that scrolls sideways. */
@Composable
private fun CwRow(title: String, note: String, tag: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(title, size = 18.sp, color = C.Text, weight = FontWeight.SemiBold)
            Label(note, size = 12.sp, color = C.Soft, modifier = Modifier.padding(bottom = 2.dp))
        }
        LazyRow(
            Modifier.fillMaxWidth().testTag(tag),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = 4.dp, end = 40.dp, top = 6.dp, bottom = 6.dp),
            content = content,
        )
    }
}

/** A live channel: its logo or name, what's on now (from this TV's guide), and where and when it was watched. */
@Composable
private fun LiveTile(vm: AppViewModel, r: AppViewModel.RecentChannel, modifier: Modifier, onMenu: () -> Unit, onClick: () -> Unit) {
    val c = vm.channel(r.key)
    val name = c?.let { vm.channelName(it) } ?: r.key.substringAfter(':')
    Column(Modifier.width(230.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Focusable(modifier.fillMaxWidth().height(120.dp).testTag("cw-tile"), color = badgeColor(name), focusedColor = badgeColor(name), ringWidth = 3.dp, onMenu = onMenu, onClick = onClick) {
            Box(Modifier.fillMaxSize()) {
                if (c?.logo != null) TvImage(model = c.logo, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.align(Alignment.Center).padding(18.dp).fillMaxSize())
                else Label(name, size = 17.sp, color = Color.White, weight = FontWeight.Bold, lines = 2, modifier = Modifier.align(Alignment.Center).padding(12.dp))
                Label("LIVE", size = 11.sp, color = Color.White, weight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFC8402F)).padding(horizontal = 6.dp, vertical = 1.dp))
            }
        }
        Label(name, size = 14.sp, color = C.Text, weight = FontWeight.SemiBold)
        Label(r.on?.let { "Now: $it" } ?: "Live", size = 12.sp, color = C.Soft)
        Label(vm.recentNote(r.key), size = 12.sp, color = C.Accent)
    }
}

/** A series or a movie: its poster, name and where it was left. */
@Composable
private fun TitleTile(vm: AppViewModel, v: VodItem, modifier: Modifier, onMenu: () -> Unit, onClick: () -> Unit) {
    Column(Modifier.width(140.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Focusable(modifier.fillMaxWidth().height(210.dp).testTag("cw-tile"), shape = RoundedCornerShape(8.dp), color = badgeColor(v.name), focusedColor = badgeColor(v.name), ringWidth = 3.dp, onMenu = onMenu, onClick = onClick) {
            Box(Modifier.fillMaxSize()) {
                if (v.logo != null) TvImage(model = v.logo, contentDescription = v.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Label(v.name, size = 15.sp, color = Color.White, weight = FontWeight.Bold, lines = 3, modifier = Modifier.align(Alignment.Center).padding(10.dp))
            }
        }
        Label(v.name, size = 13.sp, color = C.Text, weight = FontWeight.SemiBold, lines = 2)
        vm.continueFrom(v)?.let { Label(it, size = 12.sp, color = C.Accent) }
    }
}
