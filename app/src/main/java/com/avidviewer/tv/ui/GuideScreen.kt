package com.avidviewer.tv.ui

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
private const val CH_COL_FOCUSED = 330 // widened while the channel name is highlighted
private const val WINDOW_MIN = 150

@Composable
fun GuideScreen(vm: AppViewModel) {
    if (vm.channels.isEmpty()) {
        EmptyState(vm, "No channels yet", "Add your provider on the Playlists screen.")
        return
    }
    @Suppress("UNUSED_VARIABLE") val favTick = vm.favDataTick // redraw when other playlists' favorites load
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
        val idx = list.indexOfFirst { it.key == vm.currentKey }.let { if (it < 0 && list.isNotEmpty()) 0 else it }
        if (idx >= 0 && vm.navMode == NavMode.GROUPS) {
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
            PreviewWindow(vm, Modifier.width(231.dp).fillMaxHeight(), onOpen = { vm.openFullScreen(vm.currentKey, PlayContext.GROUP) })
            val fc = vm.channel(focusProg?.first ?: vm.currentKey)
            val fp = focusProg?.second ?: vm.nowNext(fc).first
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label(listOfNotNull(fc?.let { vm.channelName(it) }, fc?.let { vm.playlistName(it) }?.ifBlank { null }, fp?.let { "${vm.fmtTime(it.start)} – ${vm.fmtTime(it.end)}" }).joinToString(" · "), size = 12.sp)
                Label(fp?.title ?: "No guide information", size = 18.sp, color = C.Text, weight = FontWeight.Bold)
                Label(listOfNotNull(fp?.subtitle?.let { "“$it”" }, if (fp?.isNew == true) "New" else null).joinToString(" · "), size = 13.sp, color = C.Soft)
                Label("OK: preview · OK again: full screen · CH+/CH−: page · Menu: options", size = 12.sp)
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
            Label("No favorites yet. Press Menu (or hold OK) on any channel and choose Add to List — favorites from every playlist appear here.", size = 13.sp, color = C.Soft, lines = 2)
        } else if (list.isEmpty() && isListGroup(vm.selectedGroup)) {
            Label("This list is empty. Press Menu (or hold OK) on any channel and choose Add to List. Channels from every playlist can go in one list.", size = 13.sp, color = C.Soft, lines = 2)
        }
        if (vm.guideQuery.isNotBlank()) {
            GuideSearchResults(vm, Modifier.weight(1f))
        } else {
        // Timeline header
        Row(Modifier.fillMaxWidth().height(18.dp)) {
            Label("Today · ${vm.zoneLabel()}", size = 12.sp, modifier = Modifier.width((CH_COL + 3).dp))
            Box(Modifier.weight(1f)) {
                for (i in 0 until WINDOW_MIN / 30) {
                    Label(vm.fmtTime(winStart + i * half), size = 12.sp, modifier = Modifier.offset(x = (i * 30 * DP_PER_MIN).dp))
                }
            }
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(list, key = { i, c -> "$i-${c.key}" }) { idx, c ->
                    Row(Modifier.fillMaxWidth().height(50.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        // Channel name: two lines normally; the cell widens to show the full name while highlighted.
                        var nameFocused by remember { mutableStateOf(false) }
                        val colWidth by animateDpAsState(if (nameFocused) CH_COL_FOCUSED.dp else CH_COL.dp, label = "chcol")
                        Focusable(
                            modifier = Modifier.width(colWidth).fillMaxHeight().focusRequester(rowFocus[idx])
                                .onFocusChanged { nameFocused = it.hasFocus },
                            onFocus = { focusProg = c.key to vm.nowNext(c).first },
                            onMenu = { vm.channelMenu = c.key },
                            onClick = { vm.selectChannel(c.key, PlayContext.GROUP) },
                        ) {
                            Label("${numbers[c.key] ?: ""}", size = 12.sp, modifier = Modifier.width(30.dp).padding(start = 6.dp))
                            ChannelBadge(vm.channelName(c), c.logo, 28.dp)
                            Label(
                                vm.channelName(c) + if (vm.isFavorite(c.key)) " ★" else "",
                                size = 13.sp, color = C.Text, weight = FontWeight.SemiBold,
                                lines = if (nameFocused) 3 else 2,
                                modifier = Modifier.padding(start = 7.dp, end = 6.dp),
                            )
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
            val nowX = (CH_COL + 3) + ((vm.now - winStart) / 60000f) * DP_PER_MIN
            Box(Modifier.offset(x = nowX.dp).width(2.dp).fillMaxHeight().background(C.Accent))
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
                OptionRow("▲  Move up") { vm.moveInGuide(key, -1) }
                OptionRow("▼  Move down") { vm.moveInGuide(key, 1) }
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
                Label("Category ${pos + 1} of ${all.size} · ${all.getOrNull(pos)?.count ?: 0} channels", size = 13.sp, color = C.Soft)
                OptionRow("✎  Rename") { renaming = true }
                OptionRow("▲  Move up") { vm.moveGroup(raw, -1) }
                OptionRow("▼  Move down") { vm.moveGroup(raw, 1) }
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
    val pool = remember(vm.channels, vm.prefs.favorites, vm.favDataTick) { (vm.channels + vm.allLists().flatMap { vm.channelsIn(it.first) }).distinctBy { it.key } }
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
            Focusable(Modifier.fillMaxWidth().height(50.dp), onMenu = { vm.channelMenu = c.key }, onClick = { vm.selectChannel(c.key, PlayContext.GROUP) }) {
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
            Focusable(Modifier.fillMaxWidth().height(50.dp), onClick = { vm.selectChannel(c.key, PlayContext.GROUP) }) {
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
