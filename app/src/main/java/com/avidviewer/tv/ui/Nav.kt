package com.avidviewer.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avidviewer.tv.R
import kotlinx.coroutines.delay

/** Simple outline icons (24×24) drawn from path data, so no icon library is needed. */
object NavIcons {
    private fun icon(name: String, d: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            stroke = SolidColor(Color.White), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ).build()

    val Search = icon("search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z M20 20l-4.2-4.2")
    val Favorites = icon("favorites", "M12 3l2.8 5.7 6.2.9-4.5 4.4 1 6.2L12 17.3 6.5 20.2l1-6.2L3 9.6l6.2-.9z")
    val Live = icon("live", "M3 7h18v12H3z M8 3l4 4 4-4")
    val Guide = icon("guide", "M3 4h18v16H3z M3 10h18 M9 10v10")
    val Sports = icon("sports", "M8 21h8 M12 17v4 M7 4h10v5a5 5 0 0 1-10 0z M17 5h3v2a3 3 0 0 1-3 3 M7 5H4v2a3 3 0 0 0 3 3")
    val Discover = icon("discover", "M12 3l1.8 4.7 4.7 1.8-4.7 1.8L12 16l-1.8-4.7L5.5 9.5l4.7-1.8z M19 15l.8 2.2 2.2.8-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z")
    val Movies = icon("movies", "M3 4h18v16H3z M7 4v16 M17 4v16 M3 9h4 M3 15h4 M17 9h4 M17 15h4")
    val Series = icon("series", "M4 8h16v12H4z M7 4h10 M10 12l5 2-5 2z")
    val Manage = icon("manage", "M4 7h10 M18 7h2 M4 17h4 M12 17h8 M16 5v4 M10 15v4")
    val Playlists = icon("playlists", "M4 6h12 M4 12h12 M4 18h8 M18 15v6 M15 18h6")
    val Location = icon("location", "M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21z M12 7a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z")
    val Privacy = icon("privacy", "M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6z")
    val Storage = icon("storage", "M4 5h16v5H4z M4 14h16v5H4z M8 7.5h.01 M8 16.5h.01")
    val Settings = icon("settings", "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z M12 2v3 M12 19v3 M4.2 4.2l2.1 2.1 M17.7 17.7l2.1 2.1 M2 12h3 M19 12h3 M4.2 19.8l2.1-2.1 M17.7 6.3l2.1-2.1")
}

private val MENU = listOf(
    Triple(Screen.SEARCH, "Search", NavIcons.Search),
    Triple(Screen.DISCOVER, "Discover", NavIcons.Discover),
    Triple(Screen.GUIDE, "TV Guide", NavIcons.Guide),
    Triple(Screen.SPORTS, "Sports", NavIcons.Sports),
    Triple(Screen.MOVIES, "Movies", NavIcons.Movies),
    Triple(Screen.SERIES, "Series", NavIcons.Series),
    Triple(Screen.SETTINGS, "Settings", NavIcons.Settings),
)

/** Main menu. Full width with labels at the top level; icons only once a choice is made (labels return while it has focus). */
@Composable
fun NavRail(vm: AppViewModel) {
    var railFocused by remember { mutableStateOf(false) }
    val expanded = vm.navMode == NavMode.FULL || railFocused
    val requesters = remember { MENU.map { FocusRequester() } }

    LaunchedEffect(vm.navMode) {
        if (vm.navMode == NavMode.FULL) {
            delay(50)
            val i = MENU.indexOfFirst { it.first == vm.screen }.coerceAtLeast(0)
            runCatching { requesters[i].requestFocus() }
        }
    }

    Column(
        Modifier.width(if (expanded) 176.dp else 60.dp).fillMaxHeight().background(C.Rail)
            .onFocusChanged { railFocused = it.hasFocus }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
            Image(painterResource(R.drawable.logo), contentDescription = "Avid Viewer", modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)))
            if (expanded) {
                Spacer(Modifier.width(8.dp))
                Text("Avid Viewer", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        MENU.forEachIndexed { i, (s, label, icon) ->
            val active = vm.screen == s
            Focusable(
                modifier = Modifier.fillMaxWidth().height(36.dp).focusRequester(requesters[i]),
                color = if (active) C.Accent else Color.Transparent,
                focusedColor = if (active) C.AccentFocus else C.Surface2,
                onClick = { vm.navSelect(s) },
            ) {
                Icon(icon, contentDescription = label, tint = if (active) C.OnAccent else C.Soft, modifier = Modifier.padding(start = 10.dp).size(20.dp))
                if (expanded) {
                    Text(
                        label, color = if (active) C.OnAccent else C.Soft, fontSize = 14.sp, maxLines = 1,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            if (s == Screen.DISCOVER || s == Screen.SERIES) Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.weight(1f))
        if (vm.prefs.showClock) {
            Column(Modifier.padding(start = 4.dp)) {
                Text(vm.fmtTime(vm.now), color = C.Text, fontSize = if (expanded) 22.sp else 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (expanded) Label(vm.fmtDate(vm.now))
            }
        }
        if (expanded) {
            vm.weatherLine()?.let { Label(it, size = 13.sp, color = C.Soft, lines = 2, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }
            vm.update?.let { rel ->
                TvButton("Update ${rel.versionName}", primary = true, modifier = Modifier.padding(top = 8.dp)) {
                    vm.settingsSection = "about"; vm.screen = Screen.SETTINGS; vm.navMode = NavMode.COLLAPSED
                }
            }
        }
    }
}

/** Second level on Live TV / TV Guide: every playlist, each expandable to its categories. */
@Composable
fun PlaylistTree(vm: AppViewModel) {
    val active = vm.activePlaylist()?.id
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    LazyColumn(
        Modifier.width(250.dp).fillMaxHeight().background(C.Bg),
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Focusable(
                modifier = Modifier.fillMaxWidth().height(44.dp),
                color = C.Surface,
                onClick = { vm.ensureFavoriteData(); vm.chooseGroup(active ?: "", FAV_GROUP) },
            ) {
                Text("★", color = C.Accent, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp, end = 8.dp))
                Column(Modifier.weight(1f)) {
                    Label("Favorites", size = 14.sp, color = C.Text, weight = FontWeight.SemiBold)
                    Label("${vm.prefs.favorites.size} channels · all playlists", size = 12.sp)
                }
            }
        }
        item { SectionTitle("Playlists", Modifier.padding(start = 6.dp, top = 10.dp, bottom = 6.dp)) }
        if (vm.shownPlaylists.isEmpty()) item { Label(if (vm.playlists.isEmpty()) "No playlists yet. Add one in Settings › Playlists." else "All playlists are turned off. Turn one on in Settings › Playlists.", size = 13.sp, lines = 3) }
        vm.shownPlaylists.forEach { p ->
            val open = vm.expandedPlaylist == p.id
            item(key = "pl-" + p.id) {
                Focusable(
                    modifier = Modifier.fillMaxWidth().height(44.dp).then(if (p.id == active) Modifier.focusRequester(first) else Modifier),
                    color = if (open) C.Surface2 else C.Surface,
                    onClick = { vm.togglePlaylist(p.id) },
                ) {
                    Text(if (open) "▾" else "▸", color = C.Accent, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp, end = 8.dp))
                    Column(Modifier.weight(1f)) {
                        Label(p.name, size = 14.sp, color = C.Text, weight = FontWeight.SemiBold)
                        if (p.id == active) Label("Active", size = 12.sp, color = C.Accent)
                    }
                }
            }
            if (open) {
                val groups = if (p.id == active) vm.groups() else vm.playlistGroups[p.id]
                if (groups == null) item(key = "ld-" + p.id) { Label("Loading…", size = 12.sp, modifier = Modifier.padding(start = 30.dp)) }
                else items(groups, key = { "g-" + p.id + "-" + it.raw }) { g ->
                    Focusable(
                        modifier = Modifier.fillMaxWidth().padding(start = 18.dp).height(36.dp),
                        color = C.Bg,
                        onClick = { vm.chooseGroup(p.id, g.raw) },
                    ) {
                        Label(g.name, size = 13.sp, color = C.Soft, modifier = Modifier.padding(start = 10.dp).weight(1f))
                        Label("${g.count}", size = 12.sp, modifier = Modifier.padding(end = 10.dp))
                    }
                }
            }
        }
    }
}

/** Third level: the menu is hidden and the active playlist's categories are the left navigation. */
@Composable
fun GroupNav(vm: AppViewModel) {
    val groups = vm.groups()
    LazyColumn(
        Modifier.width(210.dp).fillMaxHeight().background(C.Rail),
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Focusable(Modifier.fillMaxWidth().height(36.dp), color = Color.Transparent, onClick = { vm.navBack() }) {
                Text("‹  Menu", color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
            }
        }
        item { SectionTitle(vm.activePlaylist()?.name ?: "Categories", Modifier.padding(start = 6.dp, top = 8.dp, bottom = 6.dp)) }
        item(key = "fav") {
            val on = vm.selectedGroup == FAV_GROUP
            Focusable(
                modifier = Modifier.fillMaxWidth().height(38.dp),
                color = if (on) C.Surface2 else Color.Transparent,
                onClick = { vm.ensureFavoriteData(); vm.chooseGroup(vm.activePlaylist()?.id ?: "", FAV_GROUP) },
            ) {
                Label((if (on) "▶ " else "") + "★ Favorites", size = 14.sp, color = if (on) C.Text else C.Accent, weight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp).weight(1f))
                Label("${vm.prefs.favorites.size}", size = 12.sp, modifier = Modifier.padding(end = 10.dp))
            }
        }
        items(groups, key = { it.raw }) { g ->
            val on = g.raw == vm.selectedGroup
            Focusable(
                modifier = Modifier.fillMaxWidth().height(38.dp),
                color = if (on) C.Surface2 else Color.Transparent,
                onMenu = { vm.groupMenu = g.raw },
                onClick = { vm.chooseGroup(vm.activePlaylist()?.id ?: "", g.raw) },
            ) {
                Label((if (on) "▶ " else "") + g.name, size = 14.sp, color = if (on) C.Text else C.Soft, weight = if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(start = 10.dp).weight(1f))
                Label("${g.count}", size = 12.sp, modifier = Modifier.padding(end = 10.dp))
            }
        }
    }
}
