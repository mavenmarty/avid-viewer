package com.avidviewer.tv.ui

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
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
            Label(
                when {
                    src == null -> "Movies & series are switched off for every playlist. Turn them on for a playlist in Settings › Playlists."
                    vm.vodLoading -> vm.status ?: "Loading ${title.lowercase()}…"
                    vm.vodError != null -> vm.vodError!!
                    else -> "No ${title.lowercase()} in your playlists yet. If your provider offers them, choose Refresh in Settings › Playlists."
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

    val catState = androidx.compose.foundation.lazy.rememberLazyListState()
    val catWrap = rememberListWrap(catState)
    val allCats = specials + cats
    Row(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.width(220.dp).fillMaxHeight().background(C.Bg),
            state = catState,
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item { Text(title, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) }
            items(specials + cats, key = { "c-" + it.first }) { (name, count) ->
                val on = name == current
                val special = specials.any { it.first == name }
                val isMoving = vm.vodMovingCat == (kind to name)
                val fr = remember { androidx.compose.ui.focus.FocusRequester() }
                var hasFocus by remember { mutableStateOf(false) }
                LaunchedEffect(isMoving, cats.indexOfFirst { it.first == name }) {
                    if (isMoving) for (attempt in 0 until 15) { delay(30); if (hasFocus) break; runCatching { fr.requestFocus() } }
                }
                Focusable(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).focusRequester(fr).onFocusChanged { hasFocus = it.isFocused }
                        .then(if (!isMoving) Modifier.wrapEnds(catWrap, isFirst = name == allCats.first().first, isLast = name == allCats.last().first) else Modifier)
                        .then(if (name == "All") Modifier.entryFocus(vm, if (kind == "series") Screen.SERIES else Screen.MOVIES) else Modifier)
                        .then(if (isMoving) Modifier.border(2.dp, C.AccentFocus, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) else Modifier)
                        .onPreviewKeyEvent { e ->
                            if (!isMoving) false
                            else reorderKeys(e, onMove = { vm.moveVodCatBy(kind, name, it) }, onTop = { vm.moveVodCatTo(kind, name, 0) },
                                onBottom = { vm.moveVodCatTo(kind, name, Int.MAX_VALUE) }, onDone = { vm.vodMovingCat = null })
                        },
                    color = if (on || isMoving) C.Surface2 else Color.Transparent,
                    onMenu = if (special) null else ({ if (!isMoving) vm.vodCatMenu = kind to name }),
                    onClick = { if (isMoving) vm.vodMovingCat = null else vm.chooseVodCategory(kind, name) },
                ) {
                    val shown = if (special) name else vm.vodCatName(kind, name)
                    Label((if (isMoving) "⇅ " else if (on) "▶ " else "") + shown, size = 13.sp, color = if (on || isMoving) C.Text else C.Soft, weight = if (on || isMoving) FontWeight.Bold else FontWeight.Normal, lines = 2, modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp).weight(1f))
                    if (isMoving) Label("${cats.indexOfFirst { it.first == name } + 1}/${cats.size}", size = 12.sp, color = C.Accent, modifier = Modifier.padding(end = 10.dp))
                    else if (count >= 0) Label("$count", size = 12.sp, modifier = Modifier.padding(end = 10.dp))
                }
            }
            item { Label("Menu on a category: rename, re-order, hide", size = 11.sp, lines = Int.MAX_VALUE, modifier = Modifier.padding(start = 10.dp, top = 8.dp)) }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp, end = 16.dp, top = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                Label(if (vq.isNotBlank()) "Results for “$vq”  ·  ${items.size} titles" else "${vm.vodCatName(kind, current)}  ·  ${items.size} titles", size = 14.sp, color = C.Soft, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
    val episodeFocus = remember { FocusRequester() }
    var inside by remember { mutableStateOf(false) }
    var onEpisode by remember { mutableStateOf(false) }
    val isSeries = v.kind == "series"
    // Opening the page, coming back from the player, or episodes arriving: put the highlight
    // on the episode that was playing, else the main button. Retried per frame until it lands,
    // and dropped as soon as the user presses a key.
    LaunchedEffect(v.key, vm.screen, vm.detailEpisodes != null) {
        if (vm.screen == Screen.PLAYER) return@LaunchedEffect
        val started = System.currentTimeMillis()
        var held = 0
        for (frame in 0 until 120) {
            androidx.compose.runtime.withFrameNanos { }
            if (vm.lastKeyAt > started) break
            val ep = vm.lastEpisode
            val wantEpisode = isSeries && frame < 60 && ep != null && vm.detailEpisodes?.any { it.key == ep.key && it.season == ep.season } == true
            val ok = if (wantEpisode) onEpisode else inside
            // Done once the highlight has stayed for a few frames (the menu may grab it right after).
            if (ok) { if (++held >= 12) break; continue }
            held = 0
            if (wantEpisode) runCatching { episodeFocus.requestFocus() } else runCatching { playFocus.requestFocus() }
            if (frame % 20 == 0) vm.trail("vod f$frame ep=$wantEpisode in=$inside onEp=$onEpisode")
        }
    }
    Row(Modifier.fillMaxSize().padding(24.dp).onFocusChanged { inside = it.hasFocus }, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
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
            if (isSeries) SeasonsAndEpisodes(vm, episodeFocus) { onEpisode = it }
        }
    }
}

@Composable
private fun SeasonsAndEpisodes(vm: AppViewModel, episodeFocus: FocusRequester, onLastFocused: (Boolean) -> Unit) {
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
                    val last = vm.lastEpisode
                    val isLast = last != null && last.key == e.key && last.season == e.season && last.number == e.number
                    Focusable(Modifier.fillMaxWidth().height(52.dp).then(if (isLast) Modifier.focusRequester(episodeFocus).onFocusChanged { onLastFocused(it.isFocused) } else Modifier), onClick = { vm.playDetailEpisode(e) }) {
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
