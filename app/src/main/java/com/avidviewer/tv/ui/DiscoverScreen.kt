package com.avidviewer.tv.ui

import androidx.compose.foundation.layout.height

import androidx.compose.ui.platform.testTag

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avidviewer.tv.data.Title
import kotlinx.coroutines.delay

private val TABS = listOf("new" to "New", "trending" to "Trending", "foryou" to "For you", "theaters" to "In theaters")

/** Discover: new releases, trending and personal picks, with where to watch. Data: TMDB and JustWatch. */
@Composable
fun DiscoverScreen(vm: AppViewModel) {
    val detail = vm.discoverDetail
    if (detail != null) { TitleDetail(vm, detail); return }

    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Discover", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
            TABS.forEachIndexed { i, (id, label) -> TvButton(label, selected = vm.discoverTab == id, modifier = if (i == 0) Modifier.entryFocus(vm, Screen.DISCOVER) else Modifier) { vm.chooseDiscoverTab(id) } }
            Spacer(Modifier.weight(1f))
            TvButton(if (vm.onlyOnProvider) "✓ On your provider" else "On your provider", selected = vm.onlyOnProvider, modifier = Modifier.testTag("only-on-provider")) {
                vm.onlyOnProvider = !vm.onlyOnProvider
            }
            if (vm.prefs.myServices.isNotEmpty()) {
                TvButton(if (vm.onlyMyServices) "✓ My services" else "My services", selected = vm.onlyMyServices) { vm.onlyMyServices = !vm.onlyMyServices }
            }
        }
        Spacer(Modifier.padding(top = 10.dp))
        if (vm.tmdbKey().isBlank()) {
            StateMessage(StateKind.EMPTY, "Discover needs your free TMDB API key.", "Enter it in Settings › Discover. It's stored encrypted on this device only.",
                action = "Open Settings" to { vm.settingsSection = "discover"; vm.navSelect(Screen.SETTINGS); vm.settingsSection = "discover" })
            return@Column
        }
        val all = vm.discoverLists[vm.discoverTab]
        val mine = vm.prefs.myServices.map { it.id }.toSet()
        val list = all?.let { l ->
            val onProvider = if (vm.onlyOnProvider) l.filter { t -> vm.matchVod(t) != null } else l
            if (!vm.onlyMyServices || mine.isEmpty()) onProvider
            else onProvider.filter { t -> vm.whereCache[t.key]?.let { w -> (w.stream + w.free).any { it.id in mine } } ?: false || vm.matchVod(t) != null }
        }
        vm.discoverNote?.takeIf { vm.discoverTab == "foryou" }?.let { Label(it, size = 13.sp, color = C.Soft, lines = 2, modifier = Modifier.padding(bottom = 8.dp)) }
        when {
            vm.discoverError != null && all == null -> StateMessage(StateKind.ERROR, "Couldn't load Discover", vm.discoverError, retry = { vm.loadDiscover(vm.discoverTab, force = true) })
            list == null -> StateMessage(StateKind.LOADING, "Loading…")
            list.isEmpty() -> StateMessage(StateKind.EMPTY, "Nothing to show", when {
                vm.onlyOnProvider -> "None of these titles are on your provider. Turn off “On your provider” to see everything."
                vm.onlyMyServices -> "Nothing here on your services yet. Turn off “My services” to see everything."
                else -> "Nothing to show right now."
            })
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(list.distinctBy { it.key }, key = { it.key }) { t -> TitleCard(vm, t) }
            }
        }
        val hint = vm.discoverHint
        LaunchedEffect(hint) { if (hint != null) { delay(4000); if (vm.discoverHint == hint) vm.discoverHint = null } }
        if (hint != null) Label(hint, size = 13.sp, color = C.Text, lines = 2, modifier = Modifier.padding(top = 6.dp).testTag("discover-hint"))
        Label("Data from TMDB · Streaming availability by JustWatch · Menu: add to My List", size = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
    }
}

@Composable
private fun TitleCard(vm: AppViewModel, t: Title) {
    val match = vm.matchVod(t)
    val where = vm.whereCache[t.key]
    Focusable(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        focusedColor = C.Surface2,
        onMenu = { vm.discoverMenu(t) },
        onClick = { vm.openTitle(t) },
    ) { focused ->
        Column(Modifier.fillMaxWidth().padding(3.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(badgeColor(t.name))) {
                Text(t.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 4, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
                if (t.poster != null) TvImage(model = t.poster, contentDescription = t.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (match != null) {
                    Text(
                        "▶ ON YOUR PROVIDER", color = C.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(C.Accent).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                    if (vm.inMyList(match.key)) {
                        Text("✓", color = C.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(C.Accent).padding(horizontal = 5.dp).testTag("in-my-list"))
                    }
                }
            }
            Label(t.name, size = 12.sp, color = if (focused) C.Text else C.Soft, weight = if (focused) FontWeight.Bold else FontWeight.Normal, lines = 2, modifier = Modifier.padding(top = 4.dp))
            val meta = listOf(if (t.type == "tv") "Series" else "Movie", t.year).filter { it.isNotBlank() }.joinToString(" · ")
            Label(meta, size = 12.sp)
            where?.let { w -> (w.stream + w.free).take(2).joinToString(", ") { it.name }.ifBlank { null } }?.let { Label(it, size = 12.sp, color = C.Accent) }
        }
    }
}

@Composable
private fun TitleDetail(vm: AppViewModel, t: Title) {
    BackHandler { vm.discoverDetail = null }
    val versions = vm.versionsOf(t)
    val match = versions.firstOrNull()
    val where = vm.whereCache[t.key]
    val first = remember { FocusRequester() }
    LaunchedEffect(t.key) { focusWhenReady(vm) { runCatching { first.requestFocus() }.isSuccess } }
    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.width(210.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(badgeColor(t.name))) {
            Text(t.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
            if (t.poster != null) TvImage(model = t.poster, contentDescription = t.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // One scrolling column: the buttons, the description, every version and Where to watch can all be
        // reached with Down. (The versions used to get only the height left over below a fixed layout;
        // on a 960×540 TV screen that was nothing, so no version could be highlighted.)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag("discover-detail"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t.name, color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    Label(
                        listOf(if (t.type == "tv") "Series" else "Movie", t.year, if (t.rating > 0) "★ %.1f".format(t.rating) else "").filter { it.isNotBlank() }.joinToString("  ·  "),
                        size = 14.sp, color = C.Soft,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (match != null) {
                            TvButton(if (match.kind == "series") "▶ Episodes" else "▶ Play", primary = true, modifier = Modifier.focusRequester(first)) { vm.playMatched(match) }
                            val inList = vm.inMyList(match.key)
                            TvButton(if (inList) "✓ In My List" else "+ My List", selected = inList) { vm.toggleMyList(match) }
                        } else {
                            TvButton("Search my playlists", primary = true, modifier = Modifier.focusRequester(first)) {
                                vm.discoverDetail = null; vm.searchQuery = Title_clean(t.name); vm.navSelect(Screen.SEARCH); vm.search(vm.searchQuery)
                            }
                        }
                        TvButton("Back") { vm.discoverDetail = null }
                    }
                    if (match != null) Label("Plays: " + versionLabel(vm, match), size = 12.sp, color = C.Soft)
                    if (t.overview.isNotBlank()) Label(t.overview, size = 14.sp, color = C.Text, lines = 4)
                    // Every version in your playlists (languages, qualities, playlists), English first: OK picks one.
                    if (versions.size > 1) Label("${versions.size} versions on your provider", size = 14.sp, color = C.Text, weight = FontWeight.Bold)
                }
            }
            if (versions.size > 1) items(versions.size, key = { "v:" + versions[it].key }) { i ->
                val v = versions[i]
                Focusable(Modifier.fillMaxWidth().height(48.dp).testTag("version-" + v.key), color = C.Surface2, onClick = { vm.playMatched(v) }) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val lang = com.avidviewer.tv.data.Language.of(v.name, v.category)
                        Label(lang ?: "Language not marked", size = 13.sp, weight = FontWeight.Bold, color = if (lang == "English") C.AccentFocus else C.Text, modifier = Modifier.width(150.dp))
                        Column(Modifier.weight(1f)) {
                            Label(v.name, size = 13.sp, color = C.Text)
                            Label(listOf(v.category, vm.playlists.firstOrNull { it.id == v.playlistId }?.name.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), size = 11.sp, color = C.Muted)
                        }
                        Label(if (v.kind == "series") "Episodes ›" else "▶ Play", size = 12.sp, color = C.Accent, weight = FontWeight.Bold)
                    }
                }
            }
            item(key = "where") {
                // Highlightable, so Down scrolls it into view after the versions.
                Focusable(Modifier.fillMaxWidth().testTag("where-to-watch"), color = C.Surface, focusedColor = C.Surface2, onClick = {}) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Label("Where to watch (${vm.region()})", size = 14.sp, color = C.Text, weight = FontWeight.Bold)
                        when {
                            where == null -> Label("Loading…", size = 13.sp)
                            else -> {
                                val mine = vm.prefs.myServices.map { it.id }.toSet()
                                listOf("Stream" to where.stream, "Free" to where.free, "Rent" to where.rent, "Buy" to where.buy).forEach { (label, l) ->
                                    if (l.isNotEmpty()) Label("$label: " + l.joinToString(", ") { if (it.id in mine) "${it.name} ✓" else it.name }, size = 13.sp, color = C.Soft, lines = 2)
                                }
                                if (where.stream.isEmpty() && where.free.isEmpty() && where.rent.isEmpty() && where.buy.isEmpty()) Label("No streaming options listed yet.", size = 13.sp)
                            }
                        }
                        Label("Streaming availability by JustWatch", size = 12.sp)
                    }
                }
            }
        }
    }
}

/** "English · EN | Netflix Series · Main": the language, category and playlist of a version. */
internal fun versionLabel(vm: AppViewModel, v: com.avidviewer.tv.data.VodItem): String =
    listOfNotNull(com.avidviewer.tv.data.Language.of(v.name, v.category) ?: "Language not marked", v.category.ifBlank { null }, vm.playlists.firstOrNull { it.id == v.playlistId }?.name)
        .joinToString(" · ")

@Suppress("FunctionName")
private fun Title_clean(s: String) = s.replace(Regex("[:\\-–].*$"), "").trim().ifBlank { s }
