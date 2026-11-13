package com.avidviewer.tv.ui

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            val playlists = "Playlists" to { vm.navSelect(Screen.PLAYLISTS) }
            when {
                src == null -> StateMessage(StateKind.EMPTY, "Movies & series are off", "Turn them on for a playlist in Settings › Playlists.", action = playlists)
                vm.vodLoading -> StateMessage(StateKind.LOADING, "Loading ${title.lowercase()}…", vm.status)
                vm.vodError != null -> StateMessage(StateKind.ERROR, "Couldn't load ${title.lowercase()}", vm.vodError, retry = { vm.ensureVod(refresh = true) }, action = playlists)
                else -> StateMessage(StateKind.EMPTY, "No ${title.lowercase()} yet", "If your provider offers them, choose Refresh in Settings › Playlists.", action = playlists)
            }
        }
        return
    }
    val current = vm.vodCategory[kind] ?: "All"
    val specials = listOf("All" to all.size, "Recently added" to -1, "Continue watching" to -1, "My List" to -1)
    val cats = vm.vodCategories(kind)
    val vq = (vm.vodQuery[kind] ?: "").trim()
    val items = if (vq.isNotBlank()) vm.vodSearch(kind, vq) else vm.vodItems(kind)

    val catState = androidx.compose.foundation.lazy.rememberLazyListState()
    // The category rows' highlight handles, by name: Back or Left from the posters goes to the current one.
    val catFrs = remember(kind) { HashMap<String, FocusRequester>() }
    val catWrap = rememberListWrap(catState)
    val allCats = specials + cats
    val catRow: @Composable (String, Int) -> Unit = { name, count ->
                val on = name == current
                val special = specials.any { it.first == name }
                val isMoving = vm.vodMovingCat == (kind to name)
                val fr = remember { androidx.compose.ui.focus.FocusRequester() }
                catFrs[name] = fr
                var hasFocus by remember { mutableStateOf(false) }
                LaunchedEffect(isMoving, cats.indexOfFirst { it.first == name }) {
                    if (isMoving) focusWhenReady(vm, frames = 30, isFocused = { hasFocus }) { runCatching { fr.requestFocus() }.isSuccess }
                }
                Focusable(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).focusRequester(fr).activePath(on && !isMoving).onFocusChanged { hasFocus = it.isFocused; if (it.isFocused && !special && !on) vm.prefetchVodCategory(kind, name) }
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
                    if (isMoving) Label("⇅", size = 13.sp, color = C.Accent, weight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                    Label(shown, size = 13.sp, color = if (on || isMoving) C.Text else C.Soft, weight = if (on || isMoving) FontWeight.Bold else FontWeight.Normal, lines = 2, modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp).weight(1f))
                    if (isMoving) Label("${cats.indexOfFirst { it.first == name } + 1}/${cats.size}", size = 12.sp, color = C.Accent, modifier = Modifier.padding(end = 10.dp))
                    else if (count >= 0) Label("$count", size = 12.sp, modifier = Modifier.padding(end = 10.dp))
                }
            }
    // Back or Left from the posters: the highlight goes to the category being shown, scrolled into view
    // (Left used to land on whichever category was level with the poster).
    LaunchedEffect(vm.vodCatFocus) {
        if (vm.vodCatFocus == 0) return@LaunchedEffect
        val si = specials.indexOfFirst { it.first == current }
        val row = if (si >= 0) 1 + si else cats.indexOfFirst { it.first == current }.let { if (it >= 0) 2 + specials.size + it else 1 }
        val vis = catState.layoutInfo.visibleItemsInfo.map { it.index }
        if (vis.isEmpty() || row <= vis.first() || row >= vis.last()) catState.scrollToItem((row - 2).coerceAtLeast(0))
        focusWhenReady(vm) { runCatching { (catFrs[current] ?: catFrs["All"])!!.requestFocus() }.isSuccess }
    }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.width(220.dp).fillMaxHeight().background(C.Bg).onFocusChanged { if (it.hasFocus) vm.vodOnPoster = false }.dimWhenInactive(),
            state = catState,
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item { Text(title, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) }
            items(specials, key = { "c-" + it.first }) { (name, count) -> catRow(name, count) }
            item(key = "vod-search") {
                // Search sits with the categories, below My List; results replace the grid.
                var text by remember(kind, vq.isBlank()) { mutableStateOf(vq) }
                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TvField("🔍  Search ${title.lowercase()}", text, modifier = Modifier.fillMaxWidth().testTag("vod-search"), imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                        onSubmit = { vm.vodQuery = vm.vodQuery + (kind to text.trim()) }) { text = it }
                    if (vq.isNotBlank()) TvButton("✕  Clear search", modifier = Modifier.fillMaxWidth()) { text = ""; vm.vodQuery = vm.vodQuery + (kind to "") }
                }
            }
            items(cats, key = { "c-" + it.first }) { (name, count) -> catRow(name, count) }
            item { Label("Menu on a category: rename, re-order, hide", size = 11.sp, lines = Int.MAX_VALUE, modifier = Modifier.padding(start = 10.dp, top = 8.dp)) }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp, end = 16.dp, top = 14.dp).onFocusChanged { if (it.hasFocus) vm.vodOnPoster = true }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                Label(if (vq.isNotBlank()) "Results for “$vq”  ·  ${items.size} titles" else "${vm.vodCatName(kind, current)}  ·  ${items.size} titles", size = 14.sp, color = C.Soft, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
                // Each category (and search) keeps its own place, by title key: coming back, or a list that
                // changed meanwhile (hidden titles, a refresh), lands on the same title (NAV-04).
                val path = vm.vodPath(kind, current, vq)
                val itemsNow by androidx.compose.runtime.rememberUpdatedState(items)
                // Only nearby titles are checked (a long list reads its pages from the database).
                fun findTitle(key: String?, index: Int) = NavStore.find(itemsNow.size, { itemsNow[it].key }, key, index, radius = 300)
                val gridState = remember(kind, path) {
                    val spot = vm.nav[path]
                    val i = spot?.let { findTitle(it.firstKey, it.firstIndex) } ?: -1
                    if (i >= 0) androidx.compose.foundation.lazy.grid.LazyGridState(i, spot!!.offset) else androidx.compose.foundation.lazy.grid.LazyGridState()
                }
                LaunchedEffect(gridState) {
                    androidx.compose.runtime.snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
                        .collect { (i, o) -> vm.nav.scrolled(path, itemsNow.getOrNull(i)?.key, i, o) }
                }
                // Back from a title's page: the highlight goes back to that title's poster.
                val backFr = remember(path) { FocusRequester() }
                val backKey = remember(path, vm.vodFocusBack) { vm.nav[path]?.key }
                LaunchedEffect(vm.vodFocusBack) {
                    if (vm.vodFocusBack == 0 || backKey == null) return@LaunchedEffect
                    val idx = vm.nav[path]?.let { findTitle(it.key, it.index) } ?: return@LaunchedEffect
                    val vis = gridState.layoutInfo.visibleItemsInfo.map { it.index }
                    if (vis.isEmpty() || idx < vis.first() || idx > vis.last()) gridState.scrollToItem(idx)
                    focusWhenReady(vm) { runCatching { backFr.requestFocus() }.isSuccess }
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(6),
                    // Room round the edges for the highlighted poster, which grows a little.
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 24.dp),
                ) {
                    itemsIndexed(items, key = { i, v -> "$i-${v.key}" }) { i, v ->
                        Poster(vm, v, Modifier.then(if (v.key == backKey) Modifier.focusRequester(backFr) else Modifier)
                            // Left from the first column: the current category, not the one level with this poster.
                            .then(if (i % 6 == 0) Modifier.onPreviewKeyEvent { e ->
                                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.DirectionLeft) { vm.vodCatFocus++; true } else false
                            } else Modifier)) { vm.nav.focused(path, v.key, i) }
                    }
                }
            }
        }
    }
}

/**
 * A title in the poster grid (1.4.0): the artwork fills the tile, with no card or text block underneath. The title
 * and year show on a black fade over the bottom of the art only on the highlighted poster, and on a poster with no
 * artwork (most posters already carry their title). The highlighted poster grows a little and gets a warm-grey ring.
 */
@Composable
private fun Poster(vm: AppViewModel, v: VodItem, modifier: Modifier = Modifier, onFocus: () -> Unit = {}) {
    var focused by remember { mutableStateOf(false) }
    val meta = listOf(v.year, v.rating.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" } ?: "").filter { it.isNotBlank() }.joinToString(" · ")
    Focusable(
        modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f)
            .onFocusChanged { focused = it.hasFocus }
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { val k = if (focused) 1.07f else 1f; scaleX = k; scaleY = k }
            // TalkBack: the title is read from the art when it isn't written on it.
            .semantics { if (v.logo != null && !focused) contentDescription = listOf(v.name, meta).filter { it.isNotBlank() }.joinToString(", ") },
        onFocus = onFocus,
        shape = RoundedCornerShape(8.dp),
        color = badgeColor(v.name),
        focusedColor = badgeColor(v.name),
        ringWidth = 3.dp,
        onMenu = { vm.toggleMyList(v) },
        onClick = { vm.openVodDetail(v) },
    ) {
        Box(Modifier.fillMaxSize()) {
            if (v.logo != null) TvImage(model = v.logo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (focused || v.logo == null) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Transparent, 0.38f to Color.Transparent, 0.66f to Color.Black.copy(alpha = 0.62f), 1f to Color.Black.copy(alpha = 0.92f))))
                Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 9.dp)) {
                    Text(v.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, lineHeight = 16.sp)
                    if (meta.isNotBlank()) Text(meta, color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (vm.inMyList(v.key)) {
                Text("✓", color = C.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(C.Accent).padding(horizontal = 5.dp))
            }
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
    val versions = vm.versionsOfItem(v)
    vm.versionPicker?.let { VersionPickerDialog(vm, it, versions) }
    // Opening the page, coming back from the player, or episodes arriving: put the highlight
    // on the episode that was playing, else the main button. Retried per frame until it lands,
    // and dropped as soon as the user presses a key.
    LaunchedEffect(v.key, vm.screen, vm.detailEpisodes != null) {
        if (vm.screen == Screen.PLAYER) return@LaunchedEffect
        // Same clock as the key-press time (it used to compare wall time with uptime, so a press never stopped it).
        val started = android.os.SystemClock.uptimeMillis()
        var held = 0
        for (frame in 0 until 120) {
            androidx.compose.runtime.withFrameNanos { }
            if (vm.lastKeyAt > started) break
            // The episode that was playing, else (BUG-020) the next one to watch.
            val eps = vm.detailEpisodes
            val ep = vm.lastEpisode?.takeIf { l -> eps?.any { it.key == l.key && it.season == l.season && it.number == l.number } == true }
                ?: eps?.let { vm.nextUpEpisode(v, it) }
            val wantEpisode = isSeries && frame < 60 && ep != null && (vm.detailSeason ?: ep.season) == ep.season
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
            if (img != null) TvImage(model = img, contentDescription = v.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // One scrolling list: the details, then the seasons and every episode, so Down always reaches
        // them. (Given only the height left under the text, the episodes could get none: a long
        // description, or the versions line of a title with several copies, e.g. from My List.)
        val seasonFr = remember { FocusRequester() }
        LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag("vod-detail"), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
          item(key = "head") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(v.name, color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            val meta = listOf(
                (info?.year?.ifBlank { null } ?: v.year),
                info?.duration ?: "",
                (info?.rating?.ifBlank { null } ?: v.rating).takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" } ?: "",
                info?.genre ?: "",
                v.category,
                vm.playlists.firstOrNull { it.id == v.playlistId }?.name.orEmpty(),
            ).filter { it.isNotBlank() }.joinToString("  ·  ")
            if (meta.isNotBlank()) Label(meta, size = 14.sp, color = C.Soft, lines = 1)
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
                if (versions.size > 1) TvButton("⇄ ${versions.size} versions", modifier = Modifier.testTag("versions-button")) { vm.versionPicker = v }
                TvButton("Back") { vm.closeVodDetail() }
            }
            // Movies and Series list each title once; this says which copy the page is on.
            if (versions.size > 1) Label("This version: " + versionLabel(vm, v), size = 13.sp, color = C.Accent, lines = 1)
            if (info == null) Label("Loading details…", size = 13.sp)
            info?.plot?.takeIf { it.isNotBlank() }?.let { Label(it, size = 14.sp, color = C.Text, lines = if (isSeries) 3 else 7) }
            info?.cast?.takeIf { it.isNotBlank() }?.let { Label("Cast: $it", size = 13.sp, lines = if (isSeries) 1 else 2) }
            info?.director?.takeIf { it.isNotBlank() }?.let { Label("Director: $it", size = 13.sp, lines = 1) }
          } }
          if (isSeries) seasonsAndEpisodes(vm, v, seasonFr, episodeFocus) { onEpisode = it }
        }
    }
}

/** The season buttons, then the chosen season's episodes, as items of the title page's list. */
private fun androidx.compose.foundation.lazy.LazyListScope.seasonsAndEpisodes(vm: AppViewModel, series: VodItem, seasonFr: FocusRequester, episodeFocus: FocusRequester, onLastFocused: (Boolean) -> Unit) {
    val eps = vm.detailEpisodes
    when {
        eps == null -> item(key = "eps-loading") { Label("Loading episodes…", size = 13.sp) }
        eps.isEmpty() -> item(key = "eps-none") { Label("No episodes found for this series.", size = 13.sp) }
        else -> {
            val seasons = eps.map { it.season }.distinct()
            val season = vm.detailSeason ?: seasons.first()
            item(key = "seasons") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(seasons) { s -> TvButton("Season $s", selected = s == season, modifier = if (s == season) Modifier.focusRequester(seasonFr) else Modifier) { vm.detailSeason = s } }
                }
            }
            val shown = eps.filter { it.season == season }
            // The episode the page opens on: the one that was playing, else the next one to watch.
            val target = vm.lastEpisode?.takeIf { l -> eps.any { it.key == l.key && it.season == l.season && it.number == l.number } } ?: vm.nextUpEpisode(series, eps)
            items(shown, key = { "e:" + it.season + ":" + it.number + ":" + it.key }) { e ->
                val ek = vm.episodeKey(series, e)
                val r = vm.resumeOf(ek)
                val isLast = target != null && target.key == e.key && target.season == e.season && target.number == e.number
                Focusable(Modifier.fillMaxWidth().height(52.dp).then(if (e == shown.first()) Modifier.focusProperties { up = seasonFr } else Modifier).then(if (isLast) Modifier.focusRequester(episodeFocus).onFocusChanged { onLastFocused(it.isFocused) } else Modifier), onClick = { vm.playDetailEpisode(e) }) {
                    Label("E${e.number}", size = 13.sp, color = C.Accent, weight = FontWeight.Bold, modifier = Modifier.width(50.dp).padding(start = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Label(e.title, size = 14.sp, color = C.Text, weight = FontWeight.SemiBold)
                        Label(listOf(e.duration, if (r > 0) "Resume from ${fmtPos(r)}" else if (vm.isWatched(ek)) "✓ Watched" else "", e.plot).filter { it.isNotBlank() }.joinToString(" · "), size = 12.sp)
                    }
                    Label("▶ Play", size = 13.sp, color = C.Accent, weight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                }
            }
        }
    }
}

private fun fmtPos(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** The title page's "⇄ N versions": every copy of the title (language, category, playlist), English first. */
@Composable
private fun VersionPickerDialog(vm: AppViewModel, current: VodItem, versions: List<VodItem>) {
    val first = remember { FocusRequester() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.versionPicker = null },
        containerColor = C.Surface,
        title = { Text("Versions of “${com.avidviewer.tv.data.Tmdb.cleanName(current.name)}”", color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(versions.size, key = { versions[it].key }) { i ->
                    val o = versions[i]
                    val here = o.key == current.key
                    Focusable(
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("version-" + o.key).then(if (here || (i == 0 && versions.none { it.key == current.key })) Modifier.focusRequester(first) else Modifier),
                        color = if (here) C.Surface2 else C.Bg,
                        onClick = { vm.chooseVersion(o) },
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val lang = com.avidviewer.tv.data.Language.of(o.name, o.category)
                            Label(lang ?: "Language not marked", size = 13.sp, weight = FontWeight.Bold, color = if (lang == "English") C.AccentFocus else C.Text, modifier = Modifier.width(150.dp))
                            Column(Modifier.weight(1f)) {
                                Label(o.name, size = 13.sp, color = C.Text)
                                Label(listOf(o.category, vm.playlists.firstOrNull { it.id == o.playlistId }?.name.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), size = 11.sp, color = C.Muted)
                            }
                            Label(if (here) "✓ Showing" else "Choose ›", size = 12.sp, color = C.Accent, weight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = { TvButton("Close") { vm.versionPicker = null } },
    )
    LaunchedEffect(current.key) { focusWhenReady(vm) { runCatching { first.requestFocus() }.isSuccess } }
}
