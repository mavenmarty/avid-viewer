@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.avidviewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avidviewer.tv.data.EpgSource
import com.avidviewer.tv.data.GuideChannel
import com.avidviewer.tv.data.Playlist
import com.avidviewer.tv.data.Tmdb

// ---------------------------------------------------------------- Settings › TV guide

fun LazyListScope.guideSettings(vm: AppViewModel, confirm: (title: String, message: String, yes: String, run: () -> Unit) -> Unit) {
    val p = vm.prefs
    item {
        SettingBlock("Download the guide every", "How often the TV guide downloads again. Shorter keeps late schedule changes; longer uses less data and is easier on a Fire TV Stick. It also downloads on this schedule while the app stays open.") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(4, 8, 12, 24).forEach { h ->
                    TvButton("$h hours", selected = p.guideRefreshHours == h, modifier = Modifier.testTag("guide-every-$h")) { vm.setGuideRefreshHours(h) }
                }
            }
        }
    }
    item {
        SettingBlock("Last downloaded", "Each playlist with live TV keeps its own guide.") {
            if (vm.livePlaylists.isEmpty()) Label("No playlist has live TV switched on.", size = 12.sp, color = C.Soft)
            vm.livePlaylists.forEach { pl ->
                val at = vm.guideSavedAt(pl.id)
                val sources = (if (pl.providerGuide) 1 else 0) + pl.epgSources.count { it.enabled }
                Label("${pl.name}: " + (if (at > 0) "${vm.dayLabel(at)} ${vm.fmtTime(at)}" else "not yet") +
                    " · $sources source" + (if (sources == 1) "" else "s") + (if (vm.guideBusy == pl.id) " · downloading…" else ""),
                    size = 12.sp, color = C.Text, lines = Int.MAX_VALUE)
            }
            TvButton(if (vm.guideBusy != null) "Downloading…" else "↻  Download the guide now", modifier = Modifier.testTag("guide-refresh-now")) { if (vm.guideBusy == null) vm.refreshGuideNow() }
        }
    }
    item {
        SettingBlock("Guide sources", "Add, edit or remove guide (XMLTV) addresses for each playlist, set a time offset, or turn off the provider's guide: Settings › Playlists › choose a playlist › ▤ Guide sources.") {
            TvButton("Open Playlists") { vm.settingsSection = "playlists" }
        }
    }
    item {
        val n = vm.epgMatchCount()
        SettingBlock("Channels matched by hand", "When a channel shows the wrong listings or none, highlight it in the TV Guide, press Menu (or hold OK) and choose Match guide….") {
            Label(if (n == 0) "None yet." else "$n channel" + (if (n == 1) "" else "s") + " matched by hand.", size = 12.sp, color = C.Text)
            if (n > 0) TvButton("Clear all matches") {
                confirm("Clear all $n matches?", "Every channel goes back to the guide match your provider gave it. This can't be undone.", "Clear") { vm.clearEpgMatches() }
            }
        }
    }
}

// ---------------------------------------------------------------- Settings › Playlists › Guide sources

@Composable
fun GuideSourcesPane(vm: AppViewModel, pl: Playlist, confirm: (title: String, message: String, yes: String, run: () -> Unit) -> Unit) {
    var editing by remember(pl.id) { mutableStateOf<String?>(null) } // source id, or "new"
    val st = vm.guideSourceStatus
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column {
                Label("Guide sources · ${pl.name}", size = 17.sp, color = C.Text, weight = FontWeight.Bold, lines = 2)
                Label("Listings are read from these sources in order. For each channel, the first source that has listings for it is used.", size = 12.sp, lines = Int.MAX_VALUE)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("Download from these sources every", size = 13.sp, color = C.Text, weight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(4, 8, 12, 24).forEach { h ->
                        TvButton("$h h", selected = vm.prefs.guideRefreshHours == h, modifier = Modifier.testTag("src-every-$h")) { vm.setGuideRefreshHours(h) }
                    }
                }
                Label("Applies to every playlist's guide (also in Settings › TV guide).", size = 12.sp, color = C.Muted, lines = Int.MAX_VALUE)
            }
        }
        item {
            Focusable(Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag("guide-provider"), onClick = { vm.setProviderGuide(pl.id, !pl.providerGuide) }) {
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                    Label("Provider's guide", size = 14.sp, color = C.Text, weight = FontWeight.SemiBold)
                    Label(if (pl.isXtream) "Comes with your provider login." else "From the playlist file's own guide link, if it has one.", size = 12.sp, lines = Int.MAX_VALUE)
                    st[pl.id + "|provider"]?.takeIf { pl.providerGuide }?.let { Label(it, size = 12.sp, color = C.Soft, lines = Int.MAX_VALUE) }
                }
                Label(if (pl.providerGuide) "On" else "Off", size = 12.sp, color = if (pl.providerGuide) C.Accent else C.Muted, weight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
            }
        }
        items(pl.epgSources, key = { it.id }) { s ->
            if (editing == s.id) SourceForm(s, onCancel = { editing = null }) { name, url, off -> vm.updateEpgSource(pl.id, s.id, name, url, off); editing = null }
            else Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Bg).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Label(s.name + if (!s.enabled) " · off" else "", size = 14.sp, color = if (s.enabled) C.Text else C.Muted, weight = FontWeight.SemiBold, lines = 2)
                Label(s.url, size = 12.sp, color = C.Soft, lines = 2)
                Label(listOfNotNull(if (s.offsetHours != 0) "Time offset ${offsetLabel(s.offsetHours)}" else null, st[pl.id + "|" + s.id]?.takeIf { s.enabled }).joinToString(" · ").ifBlank { "Not downloaded yet." }, size = 12.sp, lines = Int.MAX_VALUE)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TvButton(if (s.enabled) "Turn off" else "Turn on") { vm.setEpgSourceEnabled(pl.id, s.id, !s.enabled) }
                    TvButton("✎  Edit", modifier = Modifier.testTag("guide-src-edit")) { editing = s.id }
                    if (pl.epgSources.first() != s) TvButton("▲") { vm.moveEpgSource(pl.id, s.id, -1) }
                    if (pl.epgSources.last() != s) TvButton("▼") { vm.moveEpgSource(pl.id, s.id, 1) }
                    TvButton("✕  Delete", modifier = Modifier.testTag("guide-src-delete")) {
                        confirm("Delete ${s.name}?", "This guide source is removed from ${pl.name} and its listings disappear after the next download. This can't be undone.", "Delete") { vm.removeEpgSource(pl.id, s.id) }
                    }
                }
            }
        }
        item {
            if (editing == "new") SourceForm(null, onCancel = { editing = null }) { name, url, off -> vm.addEpgSource(pl.id, name, url, off); editing = null }
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("＋  Add guide source", primary = true, modifier = Modifier.testTag("guide-src-add")) { editing = "new" }
                TvButton(if (vm.guideBusy == pl.id) "Downloading…" else "↻  Download now") { if (vm.guideBusy == null) vm.refreshGuideNow(pl.id) }
            }
        }
        item {
            Label("Tip: if a channel shows the wrong listings, press Menu on it in the TV Guide and choose Match guide….", size = 12.sp, color = C.Muted, lines = Int.MAX_VALUE)
        }
    }
}

private fun offsetLabel(h: Int) = if (h > 0) "+$h h" else "$h h"

@Composable
private fun SourceForm(src: EpgSource?, onCancel: () -> Unit, onSave: (name: String, url: String, offset: Int) -> Unit) {
    var name by remember(src?.id) { mutableStateOf(src?.name ?: "") }
    var url by remember(src?.id) { mutableStateOf(src?.url ?: "") }
    var offset by remember(src?.id) { mutableStateOf(src?.offsetHours ?: 0) }
    var problem by remember(src?.id) { mutableStateOf<String?>(null) }
    DiscardGuard(name != (src?.name ?: "") || url != (src?.url ?: "") || offset != (src?.offsetHours ?: 0)) { onCancel() }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Bg).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(if (src == null) "New guide source" else "Edit guide source", size = 14.sp, color = C.Text, weight = FontWeight.Bold)
        TvField("Name (e.g. EPG Share US)", name, modifier = Modifier.testTag("guide-src-name")) { name = it }
        TvField("XMLTV address (http… .xml or .xml.gz)", url, modifier = Modifier.testTag("guide-src-url")) { url = it; problem = null }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Label("Time offset", size = 13.sp, color = C.Text, modifier = Modifier.width(90.dp))
            TvButton("−") { offset = (offset - 1).coerceAtLeast(-12) }
            Label(if (offset == 0) "None" else offsetLabel(offset), size = 13.sp, color = C.Accent, weight = FontWeight.Bold, modifier = Modifier.width(60.dp))
            TvButton("+") { offset = (offset + 1).coerceAtMost(12) }
        }
        Label("Use an offset only if this guide's times are consistently early or late.", size = 12.sp, color = C.Muted, lines = Int.MAX_VALUE)
        problem?.let { Label(it, size = 12.sp, color = C.Accent, lines = 2) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton("Save", primary = true, modifier = Modifier.testTag("guide-src-save")) {
                val u = url.trim()
                if (!(u.startsWith("http://", true) || u.startsWith("https://", true))) problem = "Enter an address that starts with http:// or https://."
                else onSave(name, u, offset)
            }
            TvButton("Cancel") { onCancel() }
        }
    }
}

// ---------------------------------------------------------------- Menu › Match guide…

/** Picks the guide channel a channel's listings come from. Closest names first; search covers every source. */
@Composable
fun MatchGuideDialog(vm: AppViewModel) {
    val key = vm.matchingChannel ?: return
    val c = vm.channel(key) ?: run { vm.matchingChannel = null; return }
    val pid = key.substringBefore(':')
    var all by remember(key) { mutableStateOf<List<GuideChannel>?>(null) }
    LaunchedEffect(key) { all = vm.guideChannels(pid) }
    var query by remember(key) { mutableStateOf("") }
    val current = vm.epgIdOf(c)
    val manual = vm.prefs.epgMap.containsKey(key)
    val name = vm.channelName(c)
    val shown = remember(all, query) {
        val list = all ?: emptyList()
        val q = Tmdb.normalize(query)
        if (q.isNotBlank()) list.filter { Tmdb.normalize(it.name).contains(q) || it.id.lowercase().contains(query.trim().lowercase()) }.take(60)
        else suggestMatches(name, list).take(12)
    }
    val first = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = { vm.matchingChannel = null },
        containerColor = C.Surface,
        title = { Text("Match guide · $name", color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Now: " + when {
                    current == null -> "no guide"
                    else -> (all?.firstOrNull { it.id == current }?.let { "${it.name} (${it.id})" } ?: current)
                } + if (manual) " · matched by hand" else " · from your provider", size = 13.sp, color = C.Soft, lines = 2)
                TvField("Search guide channels", query, modifier = Modifier.testTag("match-search")) { query = it }
                when {
                    all == null -> Label("Loading guide channels…", size = 12.sp)
                    all!!.isEmpty() -> Label("No guide channels yet. Download the guide (Settings › TV guide) or add a guide source to this playlist first.", size = 12.sp, lines = Int.MAX_VALUE)
                    shown.isEmpty() -> Label("Nothing matches “$query”.", size = 12.sp)
                    else -> Label(if (query.isBlank()) "Closest names" else "${shown.size} found", size = 12.sp, color = C.Muted)
                }
                LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(shown, key = { it.id + "|" + it.source }) { g ->
                        val on = g.id == current
                        Focusable(
                            modifier = Modifier.fillMaxWidth().height(48.dp).then(if (g == shown.first()) Modifier.focusRequester(first) else Modifier),
                            color = if (on) C.Surface2 else C.Bg,
                            onClick = { vm.setEpgMatch(key, g.id); vm.matchingChannel = null },
                        ) {
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Label((if (on) "✓  " else "") + g.name, size = 14.sp, color = C.Text, weight = FontWeight.SemiBold)
                                Label("${g.id} · ${g.source}", size = 11.sp)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (manual) TvButton("Use provider's match") { vm.setEpgMatch(key, null); vm.matchingChannel = null }
                    TvButton("No guide") { vm.setEpgMatch(key, ""); vm.matchingChannel = null }
                }
            }
        },
        confirmButton = { TvButton("Cancel", primary = true) { vm.matchingChannel = null } },
    )
}

/** Guide channels whose names look most like [name]: same words first, then shared words. */
fun suggestMatches(name: String, list: List<GuideChannel>): List<GuideChannel> {
    val target = Tmdb.normalize(name)
    if (target.isBlank()) return emptyList()
    val words = Tmdb.cleanName(name).lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()
    return list.asSequence().mapNotNull { g ->
        val n = Tmdb.normalize(g.name)
        val score = when {
            n == target -> 100
            n.startsWith(target) || target.startsWith(n) -> 80
            n.contains(target) || target.contains(n) && n.length > 2 -> 60
            else -> {
                val gw = Tmdb.cleanName(g.name).lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()
                val shared = (words intersect gw).size
                if (shared == 0) 0 else 20 + 30 * shared / maxOf(words.size, gw.size)
            }
        }
        if (score > 0) g to score else null
    }.sortedByDescending { it.second }.map { it.first }.toList()
}
