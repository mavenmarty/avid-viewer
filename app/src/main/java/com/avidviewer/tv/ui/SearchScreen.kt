package com.avidviewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SearchScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Search", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvField(
                "TV stations, shows, movies or series", vm.searchQuery, modifier = Modifier.width(420.dp),
                imeAction = ImeAction.Search, onSubmit = { vm.search(vm.searchQuery) },
            ) { vm.searchQuery = it }
            TvButton(if (vm.searching) "Searching…" else "Search", primary = true) { vm.search(vm.searchQuery) }
        }
        val results = vm.searchResults
        when {
            results == null -> Label("Searches every playlist: channel names, TV guide listings (now and upcoming), movies and series.", size = 13.sp, lines = 2)
            results.all { it.total == 0 } -> Label("Nothing found for “${vm.searchQuery.trim()}”.", size = 15.sp, color = C.Soft)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                results.forEach { pr ->
                    item(key = "pl-" + pr.playlist.id) {
                        Row(Modifier.padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(pr.playlist.name, color = C.Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Label("  ${pr.total} results" + if (pr.playlist.id == vm.activePlaylist()?.id) " · active playlist" else "", size = 12.sp)
                        }
                    }
                    if (pr.total == 0) item(key = "none-" + pr.playlist.id) { Label("No matches in this playlist.", size = 13.sp) }
                    section(vm, pr.playlist.id, "TV stations", pr.stations)
                    section(vm, pr.playlist.id, "Programs in the TV guide", pr.programs)
                    section(vm, pr.playlist.id, "Movies", pr.movies)
                    section(vm, pr.playlist.id, "Series", pr.series)
                }
            }
        }
    }

    SeriesDialog(vm)

}

private fun androidx.compose.foundation.lazy.LazyListScope.section(vm: AppViewModel, pid: String, title: String, hits: List<Hit>) {
    if (hits.isEmpty()) return
    item(key = "$pid-$title") { SectionTitle("$title (${hits.size})", Modifier.padding(start = 4.dp, top = 6.dp)) }
    itemsIndexed(hits, key = { i, _ -> "$pid-$title-$i" }) { _, h ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Focusable(Modifier.weight(1f).height(50.dp), onClick = { vm.openHit(h) }) {
                ChannelBadge(h.title, h.logo, 32.dp)
                Column(Modifier.padding(horizontal = 10.dp).weight(1f)) {
                    Label(h.title, size = 14.sp, color = C.Text, weight = FontWeight.SemiBold)
                    Label(h.detail, size = 12.sp)
                }
                Label(
                    "▶ " + when (h.kind) { "channel" -> "Watch"; "program" -> "Tune in"; "series" -> "Episodes"; else -> "Play" },
                    size = 13.sp, color = C.Accent, weight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp),
                )
            }
            val key = h.channelKey
            val item = h.vod
            if (key != null) {
                val fav = vm.isFavorite(key)
                TvButton(if (fav) "★ Favorite" else "☆ Favorite", selected = fav, modifier = Modifier.width(130.dp)) { vm.toggleFavorite(key) }
            } else if (item != null) {
                val inList = vm.inMyList(item.key)
                TvButton(if (inList) "✓ My List" else "+ My List", selected = inList, modifier = Modifier.width(130.dp)) { vm.toggleMyList(item) }
            }
        }
    }
}

@Composable
fun SeriesDialog(vm: AppViewModel) {
    vm.seriesOpen?.let { s ->
        AlertDialog(
            onDismissRequest = { vm.closeSeries() },
            containerColor = C.Surface,
            title = { Text(s.name, color = C.Text) },
            text = {
                val eps = vm.episodes
                when {
                    eps == null -> Label("Loading episodes…", size = 14.sp)
                    eps.isEmpty() -> Label("No episodes found for this series.", size = 14.sp)
                    else -> LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(eps) { e ->
                            Focusable(Modifier.fillMaxWidth().height(40.dp), onClick = { vm.playEpisode(e); vm.closeSeries() }) {
                                Label("S${e.season} E${e.number}", size = 12.sp, modifier = Modifier.width(70.dp).padding(start = 10.dp))
                                Label(e.title, size = 14.sp, color = C.Text)
                            }
                        }
                    }
                }
            },
            confirmButton = { TvButton("Close") { vm.closeSeries() } },
        )
    }}
