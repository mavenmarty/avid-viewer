package com.avidviewer.tv.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
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
import coil.compose.AsyncImage
import com.avidviewer.tv.data.VodItem
import kotlinx.coroutines.delay

/** With more than one playlist offering movies & series, choose which one these screens show. */
@Composable
private fun VodSourcePicker(vm: AppViewModel) {
    val pls = vm.vodPlaylists
    if (pls.size < 2) return
    val cur = vm.vodPlaylist()?.id
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        pls.forEach { p -> TvButton(p.name, selected = p.id == cur) { vm.chooseVodPlaylist(p.id) } }
    }
}

/** Movies or Series: categories on the left, posters on the right, details page on OK. */
@Composable
fun VodScreen(vm: AppViewModel, kind: String) {
    val detail = vm.vodDetail
    if (detail != null && (detail.kind == "series") == (kind == "series")) {
        VodDetail(vm, detail)
        return
    }
    val title = if (kind == "series") "Series" else "Movies"
    LaunchedEffect(Unit) { vm.ensureVod() }
    val src = vm.vodPlaylist()
    val all = vm.vodOf(kind)
    if (all.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            VodSourcePicker(vm)
            Label(
                when {
                    src == null -> "Movies & series are switched off for every playlist. Turn them on for a playlist in Settings › Playlists."
                    vm.vodLoading -> vm.status ?: "Loading ${title.lowercase()} from ${src.name}…"
                    vm.vodError != null -> vm.vodError!!
                    else -> "No ${title.lowercase()} from ${src.name} yet. If your provider offers them, choose Refresh in Settings › Playlists."
                },
                size = 15.sp, color = C.Soft, lines = 4,
            )
            TvButton("Playlists", primary = true) { vm.navSelect(Screen.PLAYLISTS) }
        }
        return
    }
    val current = vm.vodCategory[kind] ?: "All"
    val specials = listOf("All" to all.size, "Recently added" to -1, "Continue watching" to -1, "My List" to -1)
    val cats = vm.vodCategories(kind)
    val vq = (vm.vodQuery[kind] ?: "").trim()
    val items = if (vq.isNotBlank()) vm.vodOf(kind).filter { it.name.contains(vq, ignoreCase = true) } else vm.vodItems(kind)

    Row(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.width(220.dp).fillMaxHeight().background(C.Bg),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item { Text(title, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) }
            items(specials + cats, key = { "c-" + it.first }) { (name, count) ->
                val on = name == current
                Focusable(
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    color = if (on) C.Surface2 else Color.Transparent,
                    onClick = { vm.chooseVodCategory(kind, name) },
                ) {
                    Label((if (on) "▶ " else "") + name, size = 13.sp, color = if (on) C.Text else C.Soft, weight = if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(start = 10.dp).weight(1f))
                    if (count >= 0) Label("$count", size = 12.sp, modifier = Modifier.padding(end = 10.dp))
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp, end = 16.dp, top = 14.dp)) {
            if (vm.vodPlaylists.size > 1) Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("From", size = 12.sp)
                VodSourcePicker(vm)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                Label(if (vq.isNotBlank()) "Results for “$vq”  ·  ${items.size} titles" else "$current  ·  ${items.size} titles", size = 14.sp, color = C.Soft, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                var text by remember(kind) { mutableStateOf(vq) }
                TvField("Search ${title.lowercase()}", text, modifier = Modifier.width(280.dp), imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    onSubmit = { vm.vodQuery = vm.vodQuery + (kind to text.trim()) }) { text = it }
                if (vq.isNotBlank()) TvButton("✕ Clear") { text = ""; vm.vodQuery = vm.vodQuery + (kind to "") }
            }
            if (items.isEmpty()) {
                Label(
                    when (current) {
                        "My List" -> if (vq.isNotBlank()) "No titles match." else "Nothing in My List yet. Open a title and choose + My List."
                        "Continue watching" -> "Titles you stop part-way through appear here."
                        else -> "No titles in this category."
                    }, size = 14.sp, color = C.Muted, lines = 2,
                )
            } else {
                // One scroll state per category: a new category starts at the top, the same one (or coming
                // back from a title's page) keeps its place.
                val gridKey = "$current|$vq"
                val gridState = remember(kind, gridKey) {
                    val saved = vm.vodScroll[kind]
                    if (saved != null && saved.first == gridKey) androidx.compose.foundation.lazy.grid.LazyGridState(saved.second, saved.third)
                    else androidx.compose.foundation.lazy.grid.LazyGridState()
                }
                LaunchedEffect(gridState) {
                    androidx.compose.runtime.snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
                        .collect { (i, o) -> vm.vodScroll[kind] = Triple(gridKey, i, o) }
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    itemsIndexed(items, key = { i, v -> "$i-${v.key}" }) { _, v -> Poster(vm, v) }
                }
            }
        }
    }
}

@Composable
private fun Poster(vm: AppViewModel, v: VodItem) {
    Focusable(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        focusedColor = C.Surface2,
        onMenu = { vm.toggleMyList(v) },
        onClick = { vm.openVodDetail(v) },
    ) { focused ->
        Column(Modifier.fillMaxWidth().padding(3.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(badgeColor(v.name))) {
                Text(
                    v.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 4,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
                if (v.logo != null) {
                    AsyncImage(model = v.logo, contentDescription = v.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (vm.inMyList(v.key)) {
                    Text("✓", color = C.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(C.Accent).padding(horizontal = 5.dp))
                }
            }
            Label(v.name, size = 12.sp, color = if (focused) C.Text else C.Soft, weight = if (focused) FontWeight.Bold else FontWeight.Normal, lines = 2, modifier = Modifier.padding(top = 4.dp))
            val meta = listOf(v.year, v.rating.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" } ?: "").filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) Label(meta, size = 12.sp)
        }
    }
}

@Composable
private fun VodDetail(vm: AppViewModel, v: VodItem) {
    BackHandler { vm.closeVodDetail() }
    val info = vm.vodInfo
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(v.key) {
        delay(120)
        runCatching { playFocus.requestFocus() }
    }
    val isSeries = v.kind == "series"
    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.width(200.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(badgeColor(v.name))) {
            Text(v.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
            val img = v.logo ?: info?.image
            if (img != null) AsyncImage(model = img, contentDescription = v.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(v.name, color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            val meta = listOf(
                (info?.year?.ifBlank { null } ?: v.year),
                info?.duration ?: "",
                (info?.rating?.ifBlank { null } ?: v.rating).takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" } ?: "",
                info?.genre ?: "",
                v.category,
            ).filter { it.isNotBlank() }.joinToString("  ·  ")
            if (meta.isNotBlank()) Label(meta, size = 14.sp, color = C.Soft)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isSeries) {
                    val r = vm.resumeOf(v.key)
                    if (r > 0) {
                        TvButton("▶ Resume from ${fmtPos(r)}", primary = true, modifier = Modifier.focusRequester(playFocus)) { vm.playVod(v) }
                        TvButton("Start over") { vm.playVod(v, fromStart = true) }
                    } else {
                        TvButton("▶ Play", primary = true, modifier = Modifier.focusRequester(playFocus)) { vm.playVod(v) }
                    }
                }
                val inList = vm.inMyList(v.key)
                TvButton(if (inList) "✓ In My List" else "+ My List", selected = inList, modifier = if (isSeries) Modifier.focusRequester(playFocus) else Modifier) { vm.toggleMyList(v) }
                TvButton("Back") { vm.closeVodDetail() }
            }
            if (info == null) Label("Loading details…", size = 13.sp)
            info?.plot?.takeIf { it.isNotBlank() }?.let { Label(it, size = 14.sp, color = C.Text, lines = if (isSeries) 3 else 7) }
            info?.cast?.takeIf { it.isNotBlank() }?.let { Label("Cast: $it", size = 13.sp, lines = 2) }
            info?.director?.takeIf { it.isNotBlank() }?.let { Label("Director: $it", size = 13.sp) }
            if (isSeries) SeasonsAndEpisodes(vm)
        }
    }
}

@Composable
private fun SeasonsAndEpisodes(vm: AppViewModel) {
    val eps = vm.detailEpisodes
    when {
        eps == null -> Label("Loading episodes…", size = 13.sp)
        eps.isEmpty() -> Label("No episodes found for this series.", size = 13.sp)
        else -> {
            val seasons = eps.map { it.season }.distinct()
            val season = vm.detailSeason ?: seasons.first()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(seasons) { s -> TvButton("Season $s", selected = s == season) { vm.detailSeason = s } }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(eps.filter { it.season == season }) { e ->
                    val r = vm.resumeOf(e.key)
                    Focusable(Modifier.fillMaxWidth().height(52.dp), onClick = { vm.playDetailEpisode(e) }) {
                        Label("E${e.number}", size = 13.sp, color = C.Accent, weight = FontWeight.Bold, modifier = Modifier.width(50.dp).padding(start = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Label(e.title, size = 14.sp, color = C.Text, weight = FontWeight.SemiBold)
                            Label(listOf(e.duration, if (r > 0) "Resume from ${fmtPos(r)}" else "", e.plot).filter { it.isNotBlank() }.joinToString(" · "), size = 12.sp)
                        }
                        Label("▶ Play", size = 13.sp, color = C.Accent, weight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                    }
                }
            }
        }
    }
}

private fun fmtPos(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
