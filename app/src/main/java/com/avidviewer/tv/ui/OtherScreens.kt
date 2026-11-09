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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
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
    LaunchedEffect(vm.prefs.favorites) { vm.loadOtherFavorites() }
    val other = vm.otherFavorites
    val myList = vm.prefs.myList
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Favorites", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if (favs.isEmpty() && other.isEmpty() && myList.isEmpty()) {
            Label("Nothing here yet. Press Menu (or hold OK) on a channel to add it, or use ☆ Favorite and + My List in Search.", size = 14.sp, color = C.Soft, lines = 3)
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (favs.isNotEmpty()) {
                item { SectionTitle("Channels · ${vm.activePlaylist()?.name ?: ""}", Modifier.padding(start = 4.dp, top = 4.dp)) }
                item { Label("In full screen, Up/Down and CH+/CH− move through this list.", size = 12.sp, modifier = Modifier.padding(start = 4.dp)) }
                items(favs, key = { it.key }) { c ->
                    ChannelRow(vm, c, numbers[c.key], selected = false, onFocus = {}, onClick = { vm.openFullScreen(c.key, PlayContext.FAVORITES) })
                }
            }
            other.groupBy { it.first }.forEach { (pl, list) ->
                item(key = "of-" + pl.id) { SectionTitle("Channels · ${pl.name}", Modifier.padding(start = 4.dp, top = 10.dp)) }
                items(list.map { it.second }, key = { "o-" + it.key }) { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Focusable(Modifier.weight(1f).height(50.dp), onClick = { vm.playFavoriteFrom(pl.id, c.key) }) {
                            ChannelBadge(c.name, c.logo, 32.dp)
                            Label(c.name, size = 14.sp, color = C.Text, weight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp).weight(1f))
                            Label("▶ Watch", size = 13.sp, color = C.Accent, weight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                        }
                        TvButton("Remove") { vm.toggleFavorite(c.key) }
                    }
                }
            }
            if (myList.isNotEmpty()) {
                item(key = "ml") { SectionTitle("My List · movies & series", Modifier.padding(start = 4.dp, top = 10.dp)) }
                items(myList, key = { "ml-" + it.key }) { v ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Focusable(Modifier.weight(1f).height(50.dp), onClick = { if (v.kind == "series") vm.openVodDetail(v) else vm.playVod(v) }) {
                            ChannelBadge(v.name, v.logo, 32.dp)
                            Column(Modifier.padding(horizontal = 10.dp).weight(1f)) {
                                Label(v.name, size = 14.sp, color = C.Text, weight = FontWeight.SemiBold)
                                Label((if (v.kind == "series") "Series" else if (v.kind == "episode") "Episode" else "Movie") + " · " + (vm.playlists.firstOrNull { it.id == v.playlistId }?.name ?: ""), size = 12.sp)
                            }
                            Label(if (v.kind == "series") "▶ Episodes" else "▶ Play", size = 13.sp, color = C.Accent, weight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                        }
                        TvButton("Remove") { vm.toggleMyList(v) }
                    }
                }
            }
        }
    }
    SeriesDialog(vm)
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
        Text("Manage channels", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Label("Rename, reorder or hide groups and channels. Changes apply everywhere, including channel numbers and the TV Guide.", size = 12.sp, lines = 2)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.width(330.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
                items(vm.channelsIn(manageGroup).distinctBy { it.key }, key = { it.key }) { c ->
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
fun RenameDialog(title: String, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
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

/**
 * Remote-friendly text field. Moving onto it only highlights it; pressing OK opens the
 * on-screen keyboard. Done (or Back) closes the keyboard and leaves focus on the field.
 */
@Composable
fun TvField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: (() -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var wasEditing by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val boxFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun finish() {
        keyboard?.hide()
        editing = false
    }

    if (!editing) {
        Focusable(
            modifier = modifier.fillMaxWidth().height(56.dp).focusRequester(boxFocus),
            color = C.Surface2,
            focusedColor = C.Line,
            onClick = { editing = true },
        ) { focused ->
            Column(Modifier.padding(horizontal = 14.dp)) {
                Label(label, size = 12.sp, color = if (focused) C.AccentFocus else C.Muted)
                val shown = when {
                    value.isEmpty() -> if (focused) "Press OK to type" else ""
                    password -> "•".repeat(value.length.coerceAtMost(24))
                    else -> value
                }
                Label(shown, size = 15.sp, color = if (value.isEmpty()) C.Muted else C.Text)
            }
        }
        LaunchedEffect(Unit) {
            if (wasEditing) {
                wasEditing = false
                runCatching { boxFocus.requestFocus() }
            }
        }
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            modifier = modifier.fillMaxWidth().focusRequester(fieldFocus).onFocusChanged {
                if (it.isFocused) hadFocus = true
                else if (hadFocus) { hadFocus = false; editing = false }
            },
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onDone = { finish(); onSubmit?.invoke() },
                onSearch = { finish(); onSubmit?.invoke() },
                onGo = { finish(); onSubmit?.invoke() },
                onNext = { finish(); onSubmit?.invoke() },
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = C.Text, unfocusedTextColor = C.Text,
                focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line,
                focusedLabelColor = C.Accent, unfocusedLabelColor = C.Muted, cursorColor = C.Accent,
            ),
        )
        BackHandler { finish() }
        LaunchedEffect(Unit) {
            wasEditing = true
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
        }
    }
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
        Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

private data class SettingsSection(val id: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val SECTIONS = listOf(
    SettingsSection("playlists", "Playlists", NavIcons.Playlists),
    SettingsSection("channels", "Channels", NavIcons.Manage),
    SettingsSection("location", "Location & weather", NavIcons.Location),
    SettingsSection("display", "Display & time", NavIcons.Guide),
    SettingsSection("sports", "Sports tab", NavIcons.Sports),
    SettingsSection("discover", "Discover", NavIcons.Discover),
    SettingsSection("playback", "Playback", NavIcons.Live),
    SettingsSection("privacy", "Privacy & analytics", NavIcons.Privacy),
    SettingsSection("storage", "Storage", NavIcons.Storage),
    SettingsSection("about", "Updates & about", NavIcons.Settings),
)

/** Settings: sections on the left (highlighting one shows it), options on the right. */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val p = vm.prefs
    val section = SECTIONS.firstOrNull { it.id == vm.settingsSection }?.id ?: "playlists"
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(230.dp).fillMaxHeight().background(C.Bg).padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Settings", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 10.dp))
            SECTIONS.forEach { sec ->
                val on = sec.id == section
                Focusable(
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    color = if (on) C.Surface2 else androidx.compose.ui.graphics.Color.Transparent,
                    onFocus = { vm.settingsSection = sec.id },
                    onClick = { vm.settingsSection = sec.id },
                ) {
                    androidx.compose.material3.Icon(sec.icon, contentDescription = null, tint = if (on) C.Accent else C.Muted, modifier = Modifier.padding(start = 10.dp).width(20.dp).height(20.dp))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Label(sec.title, size = 14.sp, color = if (on) C.Text else C.Soft, weight = if (on) FontWeight.Bold else FontWeight.Normal)
                        sectionSummary(vm, sec.id)?.let { Label(it, size = 12.sp) }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            when (section) {
                "playlists" -> PlaylistsScreen(vm)
                "channels" -> HiddenItems(vm)
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { Text(SECTIONS.first { it.id == section }.title, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    when (section) {
                        "location" -> locationSettings(vm)
                        "display" -> displaySettings(vm)
                        "sports" -> sportsSettings(vm)
                        "discover" -> discoverSettings(vm)
                        "playback" -> item {
                            SettingBlock("Stream format (Xtream)", "If channels won't play, try the other format. Applies after Refresh in Playlists.") {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TvButton("MPEG-TS (.ts)", selected = p.streamFormat == "ts") { vm.updatePrefs { it.copy(streamFormat = "ts") } }
                                    TvButton("HLS (.m3u8)", selected = p.streamFormat == "m3u8") { vm.updatePrefs { it.copy(streamFormat = "m3u8") } }
                                }
                            }
                        }
                        "privacy" -> privacySettings(vm)
                        "storage" -> {
                            item { ToggleRow("Clear cache on exit", "Removes stored logos and temporary video data when you leave the app. Playlists, favorites, guide and settings are kept.", p.clearCacheOnExit) { vm.updatePrefs { it.copy(clearCacheOnExit = !it.clearCacheOnExit) } } }
                            item {
                                var size by remember { mutableStateOf(vm.store.cacheSizeBytes()) }
                                SettingBlock("Cache size", "${size / (1024 * 1024)} MB") {
                                    TvButton("Clear cache now") { vm.store.clearCache(); size = vm.store.cacheSizeBytes() }
                                }
                            }
                        }
                        "about" -> {
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
                            item { Label("Avid Viewer ${appVersion(vm)} · Bring your own provider; no channels are included.", size = 12.sp, lines = 2) }
                            item {
                                SettingBlock("Credits", "This product uses the TMDB API but is not endorsed or certified by TMDB.") {
                                    Label("Streaming availability data provided by JustWatch.", size = 12.sp, color = C.Soft)
                                    Label("Weather by Open-Meteo. Sports scores from ESPN's public scoreboard.", size = 12.sp, color = C.Soft, lines = 2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sectionSummary(vm: AppViewModel, id: String): String? = when (id) {
    "playlists" -> "${vm.playlists.size} added"
    "location" -> vm.prefs.postalCode.ifBlank { null }?.let { "$it · ${vm.prefs.placeName.substringBefore(',')}" } ?: "Not set"
    "display" -> vm.zoneLabel()
    "sports" -> "${vm.prefs.sportCats.count { it.on }} categories on"
    "discover" -> if (vm.prefs.tmdbKeyEnc.isBlank()) "Needs TMDB key" else "${vm.prefs.myServices.size} services"
    "privacy" -> if (vm.prefs.historyPaused) "History paused" else "History on"
    "about" -> vm.update?.let { "Update ${it.versionName} available" } ?: "Version ${vm.installed.second}"
    else -> null
}

private fun androidx.compose.foundation.lazy.LazyListScope.locationSettings(vm: AppViewModel) {
    val p = vm.prefs
    item {
        var code by remember(p.postalCode) { mutableStateOf(p.postalCode) }
        SettingBlock(
            "Postal code",
            if (p.postalCode.isBlank()) "US ZIP or Canadian postal code. Used for weather and the Auto time zone. Only the code is sent, to Open-Meteo."
            else "${p.postalCode} · ${p.placeName}",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TvField("Postal code", code, modifier = Modifier.width(220.dp)) { code = it.take(8) }
                TvButton("Save", primary = true) { vm.setPostalCode(code) }
            }
            vm.postalStatus?.let { Label(it, size = 12.sp, color = C.Soft) }
        }
    }
    item { ToggleRow("Show weather", "Current conditions under the clock, refreshed every 30 minutes", p.showWeather) { vm.toggleWeather() } }
    item {
        SettingBlock("Temperature units", vm.weatherLine()?.let { "Now: $it" } ?: "Set a postal code to see the weather.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("°F", selected = p.units == "F") { vm.setUnits("F") }
                TvButton("°C", selected = p.units == "C") { vm.setUnits("C") }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.displaySettings(vm: AppViewModel) {
    val p = vm.prefs
    item { ToggleRow("Show clock", "Time and date in the menu and the full-screen info bar", p.showClock) { vm.updatePrefs { it.copy(showClock = !it.showClock) } } }
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
}

/** Watch history and a viewing summary, all computed and kept on this device. */
private fun androidx.compose.foundation.lazy.LazyListScope.privacySettings(vm: AppViewModel) {
    val p = vm.prefs
    item { Label("Everything here stays on this device in the app's private storage. Nothing is uploaded or backed up to the cloud.", size = 13.sp, color = C.Soft, lines = 2) }
    item { ToggleRow("Pause watch history", "Stop recording what you watch. Existing history is kept until you clear it.", p.historyPaused) { vm.updatePrefs { it.copy(historyPaused = !it.historyPaused) } } }
    item {
        val week = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        val recent = vm.history.filter { it.time >= week }
        val hours = recent.sumOf { it.seconds } / 3600.0
        val top = recent.groupBy { it.key }.mapValues { (_, l) -> l.sumOf { it.seconds } }.toList().sortedByDescending { it.second }.take(5)
        SettingBlock("Your viewing, last 7 days", if (recent.isEmpty()) "No viewing recorded this week." else "%.1f hours across %d channels".format(hours, recent.map { it.key }.distinct().size)) {
            top.forEachIndexed { i, (key, secs) ->
                val name = vm.channel(key)?.let { vm.channelName(it) } ?: "Channel from another playlist"
                Label("${i + 1}.  $name  ·  ${secs / 3600}h ${(secs / 60) % 60}m", size = 13.sp, color = C.Text)
            }
        }
    }
    item {
        SettingBlock("Watch history", if (vm.history.isEmpty()) "No watch history" else "${vm.history.size} viewing sessions from the last 90 days") {
            TvButton("Clear watch history") { vm.clearHistory() }
        }
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
                    Label(if (c.groups.isNotEmpty()) "Playlist category: " + c.groups.joinToString(", ") { vm.groupName(it) } else "Matches: " + c.keywords.joinToString(", "), size = 12.sp, lines = 2)
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
        var msg by remember { mutableStateOf<String?>(null) }
        SettingBlock("Add your own category by words", "For example, name \"Vikings\" and match \"vikings, minnesota\". Separate words with commas. Either field is enough.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TvField("Name", name, modifier = Modifier.width(180.dp)) { name = it }
                TvField("Match words", kw, modifier = Modifier.width(280.dp)) { kw = it }
                TvButton("Add", primary = true) {
                    val words = kw.ifBlank { name }.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    val n = name.trim().ifBlank { kw.split(',').first().trim().replaceFirstChar { it.uppercase() } }
                    if (words.isEmpty() || n.isEmpty()) { msg = "Enter a name or some words to match." }
                    else {
                        vm.updateSportCats(cats + com.avidviewer.tv.data.SportCat("c" + System.currentTimeMillis(), n, words, true, false))
                        msg = "Added “$n”. It now appears on the Sports screen."
                        name = ""; kw = ""
                    }
                }
            }
            msg?.let { Label(it, size = 12.sp, color = C.Soft) }
        }
    }
    item(key = "from-playlist") {
        var filter by remember { mutableStateOf("") }
        val groups = vm.groups().filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
        SettingBlock("Add a category from your playlist", "Everything on that category's channels shows on the Sports screen. Tip: you can also press Menu on a category in the TV Guide.") {
            TvField("Filter categories", filter, modifier = Modifier.width(320.dp)) { filter = it }
            groups.take(40).forEach { g ->
                val added = vm.isSportsGroup(g.raw)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.Surface2).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label(g.name, size = 14.sp, color = C.Text, weight = FontWeight.SemiBold, lines = 2)
                        Label("${g.count} channels", size = 12.sp)
                    }
                    TvButton(if (added) "✓ In Sports" else "+ Add", selected = added) { if (added) vm.removeGroupFromSports(g.raw) else vm.addGroupToSports(g.raw) }
                }
            }
            if (groups.size > 40) Label("Showing 40 of ${groups.size}. Type in the filter to narrow the list.", size = 12.sp)
        }
    }
}


private fun androidx.compose.foundation.lazy.LazyListScope.discoverSettings(vm: AppViewModel) {
    val p = vm.prefs
    item {
        var key by remember { mutableStateOf("") }
        SettingBlock(
            "TMDB API key",
            if (p.tmdbKeyEnc.isBlank()) "Free from themoviedb.org (Settings › API). Stored encrypted on this device only." else "Saved (encrypted on this device). Enter a new key to replace it.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TvField("API key", key, modifier = Modifier.width(360.dp), password = p.tmdbKeyEnc.isNotBlank() && key.isEmpty()) { key = it.trim() }
                TvButton("Save", primary = true) { vm.setTmdbKey(key); key = "" }
                if (p.tmdbKeyEnc.isNotBlank()) TvButton("Remove") { vm.setTmdbKey("") }
            }
            vm.tmdbStatus?.let { Label(it, size = 12.sp, color = C.Soft, lines = 2) }
        }
    }
    if (p.tmdbKeyEnc.isNotBlank()) {
        item {
            LaunchedEffect(Unit) { if (vm.availableServices.isEmpty()) vm.loadServices() }
            SettingBlock("My streaming services", "Pick the services you subscribe to. Discover can then show only titles you can watch now. Region: ${vm.region()}") {
                if (vm.availableServices.isEmpty()) Label("Loading services…", size = 12.sp)
                vm.availableServices.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { s ->
                            val on = p.myServices.any { it.id == s.id }
                            TvButton((if (on) "✓ " else "") + s.name, selected = on) { vm.toggleService(s) }
                        }
                    }
                }
            }
        }
    }
}

/** Settings › Channels: editing now happens in the TV Guide (Menu key); this lists what's hidden. */
@Composable
private fun HiddenItems(vm: AppViewModel) {
    val hiddenGroups = vm.groups(includeHidden = true).filter { it.hidden }
    val hiddenChannels = vm.prefs.hiddenChannels.mapNotNull { vm.channel(it) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Channels", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        item {
            SettingBlock("Edit channels and categories in the TV Guide", "Highlight a channel name or a category and press Menu (or hold OK) to rename it, move it up or down, add it to Favorites or hide it.") {}
        }
        item { SectionTitle("Hidden categories (${hiddenGroups.size})", Modifier.padding(top = 6.dp)) }
        if (hiddenGroups.isEmpty()) item { Label("None", size = 13.sp) }
        items(hiddenGroups, key = { "hg-" + it.raw }) { g ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Label(g.name, size = 14.sp, color = C.Text, weight = FontWeight.SemiBold, lines = 2); Label("${g.count} channels", size = 12.sp) }
                TvButton("Show") { vm.toggleHidden(g.raw) }
            }
        }
        item { SectionTitle("Hidden channels (${hiddenChannels.size})", Modifier.padding(top = 6.dp)) }
        if (hiddenChannels.isEmpty()) item { Label("None", size = 13.sp) }
        items(hiddenChannels, key = { "hc-" + it.key }) { c ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ChannelBadge(vm.channelName(c), c.logo, 30.dp)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Label(vm.channelName(c), size = 14.sp, color = C.Text, weight = FontWeight.SemiBold, lines = 2); Label(vm.groupName(c.group), size = 12.sp) }
                TvButton("Show") { vm.showChannel(c.key) }
            }
        }
    }
}
