package com.avidviewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- Favorites
@Composable
fun FavoritesScreen(vm: AppViewModel) {
    val favs = vm.favoriteChannels()
    val numbers = vm.numbers()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Favorites", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if (favs.isEmpty()) {
            Label("No favorites yet. On Live TV or the TV Guide, press Menu (or hold OK) on a channel to add it.", size = 14.sp, color = C.Soft, lines = 3)
            return@Column
        }
        Label("In full screen, Up/Down and CH+/CH− move through this list.", size = 12.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(favs, key = { it.key }) { c ->
                ChannelRow(vm, c, numbers[c.key], selected = false, onFocus = {}, onClick = { vm.openFullScreen(c.key, PlayContext.FAVORITES) })
            }
        }
    }
}

// ---------------------------------------------------------------- Manage
@Composable
fun ManageScreen(vm: AppViewModel) {
    var editGroup by remember { mutableStateOf<String?>(null) }
    var editChannel by remember { mutableStateOf<String?>(null) }
    var manageGroup by remember { mutableStateOf(vm.selectedGroup) }
    val groups = vm.groups(includeHidden = true)
    if (manageGroup == null || groups.none { it.raw == manageGroup }) manageGroup = groups.firstOrNull()?.raw

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Manage channels", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Label("Rename, reorder or hide groups and channels. Changes apply everywhere, including channel numbers and the TV Guide.", size = 12.sp, lines = 2)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                item { SectionTitle("Groups", Modifier.padding(4.dp)) }
                items(groups, key = { it.raw }) { g ->
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (g.raw == manageGroup) C.Surface2 else C.Surface).padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Focusable(Modifier.weight(1f).fillMaxHeight(), color = androidx.compose.ui.graphics.Color.Transparent, onClick = { manageGroup = g.raw }) {
                            Column(Modifier.padding(horizontal = 6.dp)) {
                                Label(g.name, size = 14.sp, color = if (g.hidden) C.Muted else C.Text, weight = FontWeight.SemiBold)
                                Label("${g.count} channels" + if (g.hidden) " · hidden" else "", size = 12.sp)
                            }
                        }
                        TvButton("▲") { vm.moveGroup(g.raw, -1) }
                        TvButton("▼") { vm.moveGroup(g.raw, 1) }
                        TvButton("Rename") { editGroup = g.raw }
                        TvButton(if (g.hidden) "Show" else "Hide") { vm.toggleHidden(g.raw) }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                item { SectionTitle("Channels in ${vm.groupName(manageGroup ?: "")}", Modifier.padding(4.dp)) }
                items(vm.channelsIn(manageGroup), key = { it.key }) { c ->
                    Row(
                        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ChannelBadge(vm.channelName(c), c.logo, 30.dp)
                        Label(vm.channelName(c), size = 14.sp, color = C.Text, modifier = Modifier.weight(1f))
                        TvButton("▲") { vm.moveChannel(c.key, -1) }
                        TvButton("▼") { vm.moveChannel(c.key, 1) }
                        TvButton("Rename") { editChannel = c.key }
                    }
                }
            }
        }
    }

    editGroup?.let { raw ->
        RenameDialog("Rename group", vm.groupName(raw), onDismiss = { editGroup = null }) { vm.renameGroup(raw, it); editGroup = null }
    }
    editChannel?.let { key ->
        val c = vm.channel(key)
        if (c != null) RenameDialog("Rename channel", vm.channelName(c), onDismiss = { editChannel = null }) { vm.renameChannel(key, it); editChannel = null }
    }
}

@Composable
private fun RenameDialog(title: String, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(title, color = C.Text) },
        text = { TvField("Name", text) { text = it } },
        confirmButton = { TvButton("Save", primary = true) { onSave(text) } },
        dismissButton = { TvButton("Cancel") { onDismiss() } },
    )
}

@Composable
fun TvField(label: String, value: String, modifier: Modifier = Modifier, password: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text, unfocusedTextColor = C.Text,
            focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line,
            focusedLabelColor = C.Accent, unfocusedLabelColor = C.Muted, cursorColor = C.Accent,
        ),
    )
}

// ---------------------------------------------------------------- Playlists
@Composable
fun PlaylistsScreen(vm: AppViewModel) {
    var type by remember { mutableStateOf("xtream") }
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var m3u by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    val active = vm.activePlaylist()

    Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.width(330.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Playlists", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Label("Bring your own provider. Avid Viewer doesn't include any channels.", size = 12.sp, lines = 2)
            if (vm.playlists.isEmpty()) Label("No playlists yet.", size = 14.sp, color = C.Soft)
            vm.playlists.forEach { p ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label(p.name + if (p.id == active?.id) "  · active" else "", size = 15.sp, color = C.Text, weight = FontWeight.SemiBold)
                    Label(if (p.isXtream) "Xtream Codes · ${p.server}" else "M3U · ${p.m3uUrl}", size = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (p.id != active?.id) TvButton("Use") { vm.setActive(p.id) }
                        TvButton("Refresh") { vm.refreshPlaylist(p.id) }
                        TvButton("Remove") { vm.removePlaylist(p.id) }
                    }
                }
            }
            vm.status?.let { Label(it, size = 12.sp, color = C.Soft, lines = 3) }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(C.Surface),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("Add playlist", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton("Xtream Codes", selected = type == "xtream") { type = "xtream" }
                    TvButton("M3U link", selected = type == "m3u") { type = "m3u" }
                }
            }
            item { TvField("Playlist name", name) { name = it } }
            if (type == "xtream") {
                item { TvField("Server (DNS), e.g. http://example.com:8080", server) { server = it } }
                item { TvField("Username", user) { user = it } }
                item { TvField("Password", pass, password = true) { pass = it } }
                item { Label("Live TV and the TV guide load from your login. Your password is stored encrypted on this device only.", size = 12.sp, lines = 2) }
            } else {
                item { TvField("M3U playlist link", m3u) { m3u = it } }
                item { TvField("TV guide (XMLTV) link, optional", epg) { epg = it } }
            }
            item {
                Spacer(Modifier.height(4.dp))
                TvButton(if (vm.busy) "Working…" else "Add playlist", primary = true) {
                    if (!vm.busy) {
                        vm.addPlaylist(name, type, server, user, pass, m3u, epg)
                        pass = ""
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Settings
private val ZONES = listOf(
    "auto" to "Auto", "device" to "Device",
    "America/New_York" to "Eastern", "America/Chicago" to "Central", "America/Denver" to "Mountain",
    "America/Phoenix" to "Arizona", "America/Los_Angeles" to "Pacific", "America/Anchorage" to "Alaska",
    "Pacific/Honolulu" to "Hawaii", "America/Halifax" to "Atlantic",
)

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val p = vm.prefs
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Settings", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("General", selected = vm.settingsSection == "general") { vm.settingsSection = "general" }
                TvButton("Sports tab", selected = vm.settingsSection == "sports") { vm.settingsSection = "sports" }
            }
        }
        if (vm.settingsSection == "sports") {
            sportsSettings(vm)
            return@LazyColumn
        }
        item {
            SettingBlock("App updates", "Installed: version ${vm.installed.second}" + (vm.update?.let { " · version ${it.versionName} available" } ?: "")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vm.update != null) TvButton(if (vm.updateBusy) "Working…" else "Download and install", primary = true) { if (!vm.updateBusy) vm.installUpdate() }
                    TvButton("Check now") { vm.checkForUpdate() }
                    TvButton(if (p.autoUpdateCheck) "Auto-check: on" else "Auto-check: off", selected = p.autoUpdateCheck) { vm.updatePrefs { it.copy(autoUpdateCheck = !it.autoUpdateCheck) } }
                }
                vm.updateStatus?.let { Label(it, size = 12.sp, color = C.Soft, lines = 3) }
            }
        }
        item { SectionTitle("On-screen display", Modifier.padding(top = 6.dp)) }
        item { ToggleRow("Show clock", "Time and date in the menu and the full-screen info bar", p.showClock) { vm.updatePrefs { it.copy(showClock = !it.showClock) } } }
        item { ToggleRow("Show weather", "Current conditions for your postal code, refreshed every 30 minutes", p.showWeather) { vm.toggleWeather() } }
        item {
            var code by remember(p.postalCode) { mutableStateOf(p.postalCode) }
            SettingBlock(
                "Postal code",
                if (p.postalCode.isBlank()) "US ZIP or Canadian postal code. Used for weather and the Auto time zone. Only the code is sent, to Open-Meteo."
                else "${p.postalCode} · ${p.placeName}. Used for weather and the Auto time zone.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvField("Postal code", code, modifier = Modifier.width(220.dp)) { code = it.take(8) }
                    TvButton("Save", primary = true) { vm.setPostalCode(code) }
                    TvButton("°F", selected = p.units == "F") { vm.setUnits("F") }
                    TvButton("°C", selected = p.units == "C") { vm.setUnits("C") }
                }
                vm.postalStatus?.let { Label(it, size = 12.sp, color = C.Soft) }
                vm.weatherLine()?.let { Label("Now: $it", size = 12.sp, color = C.Soft) }
            }
        }
        item {
            SettingBlock("Time zone", "Guide times, program times and the clock use this zone. Now: ${vm.fmtTime(vm.now)} ${vm.zoneLabel()}") {
                Label(vm.timeZoneSource(), size = 12.sp, color = C.Soft)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ZONES.take(6).forEach { (id, label) -> TvButton(label, selected = p.timeZone == id) { vm.updatePrefs { it.copy(timeZone = id) } } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ZONES.drop(6).forEach { (id, label) -> TvButton(label, selected = p.timeZone == id) { vm.updatePrefs { it.copy(timeZone = id) } } }
                }
            }
        }
        item { SectionTitle("Playback", Modifier.padding(top = 6.dp)) }
        item {
            SettingBlock("Stream format (Xtream)", "If channels won't play, try the other format. Applies after Refresh on the Playlists screen.") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TvButton("MPEG-TS (.ts)", selected = p.streamFormat == "ts") { vm.updatePrefs { it.copy(streamFormat = "ts") } }
                    TvButton("HLS (.m3u8)", selected = p.streamFormat == "m3u8") { vm.updatePrefs { it.copy(streamFormat = "m3u8") } }
                }
            }
        }
        item { SectionTitle("Storage", Modifier.padding(top = 6.dp)) }
        item { ToggleRow("Clear cache on exit", "Removes stored logos and temporary video data when you leave the app. Playlists, favorites, guide and settings are kept.", p.clearCacheOnExit) { vm.updatePrefs { it.copy(clearCacheOnExit = !it.clearCacheOnExit) } } }
        item {
            var size by remember { mutableStateOf(vm.store.cacheSizeBytes()) }
            SettingBlock("Cache size", "${size / (1024 * 1024)} MB") {
                TvButton("Clear cache now") { vm.store.clearCache(); size = vm.store.cacheSizeBytes() }
            }
        }
        item { SectionTitle("Privacy & data", Modifier.padding(top = 6.dp)) }
        item { Label("Everything stays on this device in the app's private storage. Nothing is uploaded or backed up to the cloud.", size = 12.sp, lines = 2) }
        item { ToggleRow("Pause watch history", "Stop recording what you watch. Existing history is kept until you clear it.", p.historyPaused) { vm.updatePrefs { it.copy(historyPaused = !it.historyPaused) } } }
        item {
            SettingBlock("Watch history", if (vm.history.isEmpty()) "No watch history" else "${vm.history.size} items from the last 90 days") {
                TvButton("Clear watch history") { vm.clearHistory() }
            }
        }
        item { Label("Avid Viewer ${appVersion(vm)}", size = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
    }
}

private fun appVersion(vm: AppViewModel): String = try {
    val ctx = vm.getApplication<android.app.Application>()
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
} catch (e: Exception) {
    ""
}

@Composable
private fun ToggleRow(title: String, desc: String, on: Boolean, onToggle: () -> Unit) {
    Focusable(Modifier.fillMaxWidth().height(60.dp), onClick = onToggle) {
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Label(title, size = 15.sp, color = C.Text, weight = FontWeight.SemiBold)
            Label(desc, size = 12.sp, lines = 2)
        }
        Label(if (on) "On" else "Off", size = 13.sp, color = if (on) C.Accent else C.Muted, weight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
        Row(
            Modifier.padding(end = 14.dp).width(46.dp).height(26.dp).clip(RoundedCornerShape(13.dp)).background(if (on) C.Accent else C.Line).padding(3.dp),
            horizontalArrangement = if (on) Arrangement.End else Arrangement.Start,
        ) {
            Spacer(Modifier.width(20.dp).height(20.dp).clip(RoundedCornerShape(10.dp)).background(androidx.compose.ui.graphics.Color.White))
        }
    }
}

@Composable
private fun SettingBlock(title: String, desc: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(title, size = 15.sp, color = C.Text, weight = FontWeight.SemiBold)
        Label(desc, size = 12.sp, lines = 2)
        content()
    }
}

// ---------------------------------------------------------------- Sports tab settings
private fun androidx.compose.foundation.lazy.LazyListScope.sportsSettings(vm: AppViewModel) {
    item {
        Label("Choose which categories appear on the Sports screen. Each one matches your provider's channel groups and channel names, plus guide listings that mention it. The order here is the order of the filter buttons.", size = 12.sp, lines = 3)
    }
    val cats = vm.prefs.sportCats
    cats.forEachIndexed { i, c ->
        item(key = "cat-" + c.id) {
            Row(
                Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TvButton(if (c.on) "On" else "Off", selected = c.on) {
                    vm.updateSportCats(cats.mapIndexed { j, x -> if (j == i) x.copy(on = !x.on) else x })
                }
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Label(c.name, size = 15.sp, color = C.Text, weight = FontWeight.SemiBold)
                    Label("Matches: " + c.keywords.joinToString(", "), size = 12.sp)
                }
                TvButton("▲") { if (i > 0) vm.updateSportCats(cats.toMutableList().apply { add(i - 1, removeAt(i)) }) }
                TvButton("▼") { if (i < cats.size - 1) vm.updateSportCats(cats.toMutableList().apply { add(i + 1, removeAt(i)) }) }
                if (!c.builtin) TvButton("Remove") { vm.updateSportCats(cats.filterIndexed { j, _ -> j != i }) }
            }
        }
    }
    item(key = "cat-add") {
        var name by remember { mutableStateOf("") }
        var kw by remember { mutableStateOf("") }
        SettingBlock("Add your own category", "For example, name \"Vikings\" and match \"vikings, minnesota\". Separate words with commas.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TvField("Name", name, modifier = Modifier.width(180.dp)) { name = it }
                TvField("Match words", kw, modifier = Modifier.width(280.dp)) { kw = it }
                TvButton("Add", primary = true) {
                    val n = name.trim()
                    if (n.isNotEmpty()) {
                        val words = (kw.ifBlank { n }).split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                        vm.updateSportCats(cats + com.avidviewer.tv.data.SportCat("c" + System.currentTimeMillis(), n, words, true, false))
                        name = ""; kw = ""
                    }
                }
            }
        }
    }
}
