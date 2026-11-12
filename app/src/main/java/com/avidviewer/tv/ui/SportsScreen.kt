package com.avidviewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.avidviewer.tv.data.Game
import com.avidviewer.tv.data.League
import com.avidviewer.tv.data.Team

/**
 * Sports, ESPN first (see the "Sports redesign" artboards): games from ESPN's schedules in league
 * filters and day groups on the left, the highlighted game at the top, and the channels found for it
 * on the right, best first, each with why it matched.
 */
@Composable
fun SportsScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { if (vm.sportGames.isEmpty()) vm.refreshSports() }
    val games = vm.sportsVisibleGames()
    val selected = games.firstOrNull { it.game.id == vm.selectedGame } ?: games.firstOrNull()
    val channelsFr = remember { FocusRequester() }
    val goToChannels = remember { mutableStateOf(0) }
    LaunchedEffect(goToChannels.value) {
        if (goToChannels.value > 0) focusWhenReady(vm) { runCatching { channelsFr.requestFocus() }.isSuccess }
    }

    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PreviewWindow(vm, Modifier.width(266.dp).fillMaxHeight(), onOpen = {
                val key = vm.currentKey?.takeIf { vm.isPreviewing(it) } ?: selected?.channels?.firstOrNull()?.key
                vm.openFullScreen(key, PlayContext.SPORTS)
            }) {
                if (selected != null) StatusBadge(vm, selected.game, Modifier.padding(8.dp))
            }
            GameHeader(vm, selected?.game, Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(470.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LeagueFilters(vm)
                GameList(vm, games, selected?.game?.id) { goToChannels.value++ }
            }
            WhereToWatch(vm, selected, channelsFr, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun LeagueFilters(vm: AppViewModel) {
    val leagues = vm.leaguesOn()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "all") {
            TvButton("All", selected = vm.sportLeague == null, modifier = Modifier.entryFocus(vm, Screen.SPORTS)) { vm.sportLeague = null; vm.selectedGame = null }
        }
        items(leagues, key = { it.id }) { l ->
            TvButton(if (l.id.startsWith("NCAA")) l.tag else l.name, selected = vm.sportLeague == l.id) { vm.sportLeague = l.id; vm.selectedGame = null }
        }
    }
}

/** Group headers and game cards, as one list. */
private sealed class Row0 { data class Header(val text: String) : Row0(); data class Card(val g: AppViewModel.SportGame) : Row0() }

@Composable
private fun GameList(vm: AppViewModel, games: List<AppViewModel.SportGame>, selectedId: String?, onOpen: () -> Unit) {
    if (games.isEmpty()) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label(if (vm.sportsLoading) "Getting today's games…" else "No games", size = 15.sp, color = C.Text, weight = FontWeight.Bold)
            Label(
                vm.sportsNote ?: if (vm.sportsLoading) "From ESPN's schedules." else
                    "No ${League.of(vm.sportLeague)?.name ?: "games"} today or in the next 2 days. Choose leagues in Settings › Sports tab.",
                size = 12.sp, lines = Int.MAX_VALUE,
            )
        }
        return
    }
    val rows = remember(games, vm.now / 60_000) {
        val out = ArrayList<Row0>()
        val live = games.filter { it.game.live }
        val upcoming = games.filter { it.game.state == "pre" }
        val final = games.filter { it.game.final }.sortedByDescending { it.game.start }
        if (live.isNotEmpty()) { out.add(Row0.Header("LIVE NOW · ${live.size}")); live.forEach { out.add(Row0.Card(it)) } }
        upcoming.groupBy { vm.dayLabel(it.game.start) }.forEach { (day, list) ->
            out.add(Row0.Header(if (day == "Today") "LATER TODAY" else day.uppercase()))
            list.forEach { out.add(Row0.Card(it)) }
        }
        if (final.isNotEmpty()) { out.add(Row0.Header("FINAL")); final.forEach { out.add(Row0.Card(it)) } }
        out
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        vm.sportsNote?.let { note -> item(key = "note") { Label(note, size = 11.sp, color = C.Soft, lines = 2) } }
        items(rows, key = { r -> when (r) { is Row0.Header -> "h:" + r.text; is Row0.Card -> r.g.game.id } }) { r ->
            when (r) {
                is Row0.Header -> Label(r.text, size = 11.sp, color = C.Muted, weight = FontWeight.Bold, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
                is Row0.Card -> GameCard(vm, r.g, r.g.game.id == selectedId, onOpen)
            }
        }
    }
}

@Composable
private fun GameCard(vm: AppViewModel, sg: AppViewModel.SportGame, isSelected: Boolean, onOpen: () -> Unit) {
    val g = sg.game
    val none = sg.channels.isEmpty()
    Focusable(
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("game-" + g.id)
            .then(if (isSelected) Modifier.border(1.dp, C.Line, RoundedCornerShape(8.dp)) else Modifier),
        shape = RoundedCornerShape(8.dp),
        onFocus = { vm.selectedGame = g.id },
        onClick = { vm.selectedGame = g.id; if (!none) onOpen() },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LeagueTag(g.league, Modifier.width(44.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TeamDot(g.away); Label(g.away.name, size = 14.sp, color = if (none) C.Soft else C.Text, weight = FontWeight.Bold)
                    Label(if (g.neutral) "vs" else "@", size = 13.sp, color = C.Muted)
                    TeamDot(g.home); Label(g.home.name, size = 14.sp, color = if (none) C.Soft else C.Text, weight = FontWeight.Bold)
                }
                Label(networks(g).ifBlank { "No TV listed" }, size = 11.sp, color = C.Muted)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label(statusText(vm, g), size = 13.sp, weight = FontWeight.Bold, color = if (g.live) Color(0xFFFF8A8D) else C.Text)
                Label(when (val n = sg.channels.size) { 0 -> "No channel yet"; 1 -> "1 channel"; else -> "$n channels" }, size = 11.sp, color = if (none) C.Muted else C.Soft)
            }
        }
    }
}

@Composable
private fun GameHeader(vm: AppViewModel, g: Game?, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (g == null) {
            Label("Sports", size = 18.sp, color = C.Text, weight = FontWeight.Bold)
            Label("Games from ESPN's schedules for the leagues you follow, with the channels in your playlists that carry them.", size = 12.sp, lines = 3)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LeagueTag(g.league)
            StatusBadge(vm, g)
            Label(
                when { g.live -> "Started ${vm.fmtTime(g.start)}"; g.final -> vm.dayLabel(g.start) + " · " + vm.fmtTime(g.start); else -> startsIn(vm, g.start) },
                size = 12.sp, color = C.Muted,
            )
        }
        val scores = vm.prefs.sportsShowScores && (g.live || g.final)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TeamBadge(g.away)
                Column {
                    Label(g.away.name, size = 20.sp, color = C.Text, weight = FontWeight.Bold)
                    Label(listOf(g.away.location, g.away.record).filter { it.isNotBlank() }.joinToString(" · "), size = 12.sp, color = C.Muted)
                }
            }
            Label(if (scores) "${g.away.score} – ${g.home.score}" else if (g.neutral) "vs" else "at", size = if (scores) 30.sp else 18.sp, color = if (scores) C.Text else C.Muted, weight = FontWeight.Bold)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                Column(horizontalAlignment = Alignment.End) {
                    Label(g.home.name, size = 20.sp, color = C.Text, weight = FontWeight.Bold)
                    Label(listOf(g.home.location, g.home.record).filter { it.isNotBlank() }.joinToString(" · "), size = 12.sp, color = C.Muted)
                }
                TeamBadge(g.home)
            }
        }
        val nat = g.national
        val tv = nat.filter { !it.kind.equals("Streaming", true) }.map { it.name }
        val stream = nat.filter { it.kind.equals("Streaming", true) }.map { it.name }
        Label(
            listOfNotNull(
                listOf(g.venue, g.city).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null },
                tv.takeIf { it.isNotEmpty() }?.let { "National TV: " + it.joinToString(", ") },
                stream.takeIf { it.isNotEmpty() }?.let { "Streaming: " + it.joinToString(", ") },
            ).joinToString(" · "),
            size = 12.sp, color = C.Soft, lines = 1,
        )
    }
}

@Composable
private fun WhereToWatch(vm: AppViewModel, sg: AppViewModel.SportGame?, firstFr: FocusRequester, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val list = sg?.channels.orEmpty()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("Where to watch", size = 14.sp, color = C.Text, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (sg != null) Label(
                when { list.isEmpty() -> "No channel found yet"; list.size == 1 -> if (sg.game.state == "pre") "1 channel so far" else "1 channel"; else -> "${list.size} channels · best first" },
                size = 11.sp, color = C.Muted,
            )
        }
        if (sg == null) { Label("Highlight a game to see where it's on.", size = 12.sp, lines = 2); return@Column }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.key }) { m ->
                val c = vm.sportsChannel(m.key)
                Focusable(
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("watch-" + m.key)
                        .then(if (m == list.first()) Modifier.focusRequester(firstFr) else Modifier),
                    color = C.Surface2,
                    shape = RoundedCornerShape(8.dp),
                    onClick = { vm.selectChannel(m.key, PlayContext.SPORTS) },
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).background(C.Rail), contentAlignment = Alignment.Center) {
                            if (c?.logo != null) AsyncImage(model = c.logo, contentDescription = null, modifier = Modifier.size(30.dp))
                            else Label(m.name.take(3).uppercase(), size = 10.sp, color = C.Soft, weight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f)) {
                            Label(c?.let { vm.channelName(it) } ?: m.name, size = 13.sp, color = C.Text, weight = FontWeight.Bold)
                            Label(m.reasons.joinToString(" · "), size = 11.sp, color = if (com.avidviewer.tv.data.MatchKind.NATIONAL in m.kinds) C.AccentFocus else C.Soft)
                        }
                        Label(vm.playlistName(c), size = 10.sp, color = C.Muted)
                    }
                }
            }
            if (sg.game.state == "pre" && list.size < 3) item(key = "later") {
                Column(Modifier.fillMaxWidth().border(1.dp, C.Line, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Label(if (list.isEmpty()) "No channel found yet" else "More channels can appear closer to game time", size = 12.sp, color = C.Soft, weight = FontWeight.Bold, lines = 2)
                    Label("Your TV guide covers about the next 36 hours, so guide listings and event channels for this game show up nearer the start. The list updates on its own.", size = 11.sp, lines = 4)
                }
            }
            if (list.isEmpty() && sg.game.state != "pre") item(key = "none") {
                Label("No channel in your playlists matched this game. ESPN lists: ${networks(sg.game).ifBlank { "no TV" }}.", size = 12.sp, lines = 3)
            }
            item(key = "search") {
                TvButton("Search the guide for “${sg.game.home.name}”") { vm.searchGuideFor(sg.game.home.name) }
            }
        }
    }
}

@Composable
private fun StatusBadge(vm: AppViewModel, g: Game, modifier: Modifier = Modifier) {
    val text = when {
        g.live -> "LIVE · " + periodText(g)
        g.final -> "FINAL"
        else -> vm.dayLabel(g.start).let { if (it == "Today") "TODAY " else it.uppercase() + " " } + vm.fmtTime(g.start)
    }
    Label(text, size = 11.sp, color = Color.White, weight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(if (g.live) C.Live else C.Line).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun LeagueTag(id: String, modifier: Modifier = Modifier) {
    val l = League.of(id)
    Label(l?.tag ?: id, size = 10.sp, color = Color.White, weight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(Color(l?.color ?: 0xFF3A4A5C)).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun TeamDot(t: Team) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(t.color?.let { Color(it) } ?: C.Line))
}

/** The team's logo from ESPN, on a circle of its colour (the abbreviation until the logo loads). */
@Composable
private fun TeamBadge(t: Team) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(t.color?.let { Color(it) } ?: C.Line), contentAlignment = Alignment.Center) {
        Label(t.abbr, size = 11.sp, color = Color.White, weight = FontWeight.Bold)
        if (t.logo != null) AsyncImage(model = t.logo, contentDescription = t.fullName, modifier = Modifier.size(32.dp))
    }
}

private fun networks(g: Game): String =
    (g.national + g.broadcasts.filter { it.market != "national" }).map { it.name }.distinct().take(3).joinToString(" · ")

private fun periodText(g: Game): String {
    val p = g.period
    val name = when (g.league) {
        "NHL" -> when (p) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "OT" }
        "MLB" -> g.detail.ifBlank { "Inning $p" }
        else -> when (p) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; 4 -> "4th"; 0 -> ""; else -> "OT" }
    }
    return if (g.league == "MLB") name else listOf(name, g.clock.takeIf { it.isNotBlank() && it != "0:00" && it != "0.0" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
}

private fun statusText(vm: AppViewModel, g: Game): String {
    val scores = vm.prefs.sportsShowScores
    return when {
        g.live -> if (scores) "${g.away.score}–${g.home.score} · ${periodText(g).substringBefore(' ')}" else periodText(g).substringBefore(' ')
        g.final -> if (scores) "Final · ${g.away.score}–${g.home.score}" else "Final"
        else -> vm.fmtTime(g.start)
    }
}

private fun startsIn(vm: AppViewModel, start: Long): String {
    val mins = ((start - vm.now) / 60_000).toInt()
    return when {
        mins < 1 -> "starting now"
        mins < 60 -> "in $mins min"
        mins < 24 * 60 -> "in ${mins / 60} h ${mins % 60} min"
        else -> "in ${mins / (24 * 60)} days"
    }
}
