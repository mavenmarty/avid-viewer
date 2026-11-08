package com.avidviewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avidviewer.tv.data.SportEvent
import kotlinx.coroutines.delay

private val LEAGUE_COLORS = mapOf(
    "NFL" to Color(0xFF013369), "NHL" to Color(0xFF3A3A3A), "NBA" to Color(0xFFC9082A), "MLB" to Color(0xFF041E42),
    "NCAA" to Color(0xFF3A4A5C), "ESPN+" to Color(0xFFB5121B), "PPV" to Color(0xFF7A1E1E), "SOCCER" to Color(0xFF1F6F43),
)

private enum class EvState { LIVE, UP, FINAL }

@Composable
fun SportsScreen(vm: AppViewModel) {
    if (vm.channels.isEmpty()) {
        EmptyState(vm, "No channels yet", "Add your provider on the Playlists screen.")
        return
    }
    val now = vm.now
    val events = vm.sportsVisibleEvents()
    fun state(e: SportEvent) = when { now >= e.end -> EvState.FINAL; now >= e.start -> EvState.LIVE; else -> EvState.UP }
    val live = events.filter { state(it) == EvState.LIVE }
    val upcoming = events.filter { state(it) == EvState.UP }.sortedBy { it.start }
    val final = events.filter { state(it) == EvState.FINAL }.sortedByDescending { it.end }
    val selected = events.firstOrNull { it.key == vm.selectedEvent } ?: live.firstOrNull() ?: upcoming.firstOrNull() ?: final.firstOrNull()


    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Preview window + selected event
        Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(231.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp))) {
                VideoSurface(vm, Modifier.fillMaxSize())
                if (selected != null) {
                    val st = state(selected)
                    Text(
                        if (st == EvState.LIVE) "LIVE" else if (st == EvState.FINAL) "FINAL" else "STARTS ${vm.fmtTime(selected.start)}",
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(4.dp)).background(if (st == EvState.LIVE) C.Live else C.Line).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (selected == null) {
                    Label("No events found", size = 17.sp, color = C.Text, weight = FontWeight.Bold)
                    Label("Events come from your TV guide on channels and groups that match your Sports categories. Change them in Settings › Sports tab.", size = 12.sp, lines = 3)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LeagueTag(selected.catId, vm.catName(selected.catId))
                        Label("  " + whenText(vm, selected), size = 12.sp)
                    }
                    Label(selected.title, size = 18.sp, color = C.Text, weight = FontWeight.Bold)
                    selected.score?.let { Label(scoreLine(it) + "  ·  " + it.detail, size = 14.sp, color = C.Soft) }
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TvButton("Watch full screen", primary = true) { vm.openFullScreen(selected.channelKeys.firstOrNull(), PlayContext.SPORTS) }
                        if (selected.channelKeys.size > 1) Label("Also on ${selected.channelKeys.size - 1} more channel" + if (selected.channelKeys.size > 2) "s" else "", size = 12.sp)
                    }
                }
            }
        }
        // Categories
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { TvButton("All", selected = vm.sportCat == null) { vm.sportCat = null; vm.selectedEvent = null } }
                items(vm.enabledCats(), key = { it.id }) { c ->
                    TvButton(c.name, selected = vm.sportCat == c.id) { vm.sportCat = c.id; vm.selectedEvent = null }
                }
            }
            TvButton("Categories") { vm.settingsSection = "sports"; vm.screen = Screen.SETTINGS }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (events.isEmpty()) item {
                Label(
                    if (vm.guide.isEmpty()) "Your TV guide hasn't loaded yet. Events appear once it does."
                    else "No events in the guide for ${vm.catName(vm.sportCat)} right now.",
                    size = 14.sp, color = C.Soft, lines = 2,
                )
            }
            listOf(Triple("Live now", C.Live, live), Triple("Upcoming", C.Accent, upcoming), Triple("Final", C.Muted, final)).forEach { (title, color, list) ->
                if (list.isNotEmpty()) {
                    item(key = "h-$title") {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(color))
                            Label("  ${title.uppercase()}  ·  ${list.size}", size = 12.sp, color = C.Soft, weight = FontWeight.Bold)
                        }
                    }
                    items(list.chunked(3), key = { row -> "r-$title-" + row.first().key }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { e -> EventCard(vm, e, state(e), selected?.key == e.key, Modifier.weight(1f)) }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventCard(vm: AppViewModel, e: SportEvent, st: EvState, isSelected: Boolean, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (st == EvState.LIVE) Color(0xFF2A1426) else C.Surface)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Focusable(
            Modifier.fillMaxWidth(),
            color = if (isSelected) C.Surface2 else Color.Transparent,
            onFocus = { vm.selectedEvent = e.key },
            onClick = { vm.selectedEvent = e.key; vm.selectChannel(e.channelKeys.firstOrNull(), PlayContext.SPORTS) },
        ) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LeagueTag(e.catId, vm.catName(e.catId))
                    Spacer(Modifier.weight(1f))
                    val status = when (st) {
                        EvState.LIVE -> e.score?.detail?.ifBlank { null } ?: "LIVE"
                        EvState.FINAL -> e.score?.detail?.ifBlank { null } ?: "ENDED"
                        EvState.UP -> "${vm.dayLabel(e.start)} ${vm.fmtTime(e.start)}"
                    }
                    Text(
                        status, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = if (st == EvState.LIVE) Color.White else if (st == EvState.UP) C.Accent else C.Soft,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (st == EvState.LIVE) C.Live else C.Surface2).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Label(e.title, size = 14.sp, color = C.Text, weight = FontWeight.Bold, lines = 2)
                e.score?.takeIf { st != EvState.UP }?.let { Label(scoreLine(it), size = 16.sp, color = C.Text, weight = FontWeight.Bold) }
                Label("${vm.fmtTime(e.start)} – ${vm.fmtTime(e.end)} ${vm.zoneLabel()}", size = 11.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            e.channelKeys.take(2).forEach { k ->
                val c = vm.channel(k)
                if (c != null) TvButton("▶ " + vm.channelName(c).take(22)) { vm.openFullScreen(k, PlayContext.SPORTS) }
            }
            if (e.channelKeys.size > 2) Label("+${e.channelKeys.size - 2}", size = 11.sp)
        }
    }
}

@Composable
private fun LeagueTag(id: String, name: String) {
    Text(
        name.uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(LEAGUE_COLORS[id] ?: C.Line).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun scoreLine(s: com.avidviewer.tv.data.Score) = "${s.awayAbbr} ${s.away} – ${s.home} ${s.homeAbbr}"

private fun whenText(vm: AppViewModel, e: SportEvent) = "${vm.dayLabel(e.start)} · ${vm.fmtTime(e.start)} – ${vm.fmtTime(e.end)} ${vm.zoneLabel()}"
