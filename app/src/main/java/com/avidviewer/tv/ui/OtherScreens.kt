@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.avidviewer.tv.ui

import kotlinx.coroutines.launch

import androidx.compose.foundation.focusable

import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.lazy.itemsIndexed
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
            Label("Nothing here yet. Press Menu (or hold OK) on a channel to add it, or use ☆ Favorite and + My List in Search.", size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE)
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
        Label("Rename, reorder or hide groups and channels. Changes apply everywhere, including channel numbers and the TV Guide.", size = 12.sp, lines = Int.MAX_VALUE)
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
private const val ADD_PLAYLIST = "__add__"

/** An action that needs a yes before it runs. */
private class Confirm(val title: String, val message: String, val yes: String, val run: () -> Unit)

/** "This can't be undone" confirmation; Cancel has the highlight. */
@Composable
private fun ConfirmDialog(c: Confirm, close: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = close,
        containerColor = C.Surface,
        title = { Text(c.title, color = C.Text, fontWeight = FontWeight.Bold) },
        text = { Label(c.message, size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE) },
        confirmButton = { TvButton(c.yes, primary = true) { close(); c.run() } },
        dismissButton = { TvButton("Cancel", modifier = Modifier.focusRequester(cancelFocus)) { close() } },
    )
    LaunchedEffect(c) { focusWhenReady { runCatching { cancelFocus.requestFocus() }.isSuccess } }
}

/**
 * Settings › Playlists, laid out left to right for the remote:
 * the list (Up/Down picks a playlist or "Add playlist"), then that playlist's actions and switches,
 * then the add / edit form on the far right.
 */
@Composable
fun PlaylistsScreen(vm: AppViewModel) {
    var sel by remember { mutableStateOf(vm.activePlaylist()?.id ?: vm.playlists.firstOrNull()?.id ?: ADD_PLAYLIST) }
    var editing by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    LaunchedEffect(sel) { sources = false }
    val selP = vm.playlists.firstOrNull { it.id == sel }
    val adding = selP == null
    val active = vm.activePlaylist()?.id
    val seenSaves = remember { mutableStateOf(vm.playlistSaved) }
    LaunchedEffect(vm.playlistSaved) {
        if (vm.playlistSaved != seenSaves.value) {
            seenSaves.value = vm.playlistSaved
            editing = false
            if (sel == ADD_PLAYLIST) sel = vm.prefs.activePlaylist ?: vm.playlists.lastOrNull()?.id ?: ADD_PLAYLIST
        }
    }

    Row(Modifier.fillMaxSize().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. The list
        LazyColumn(Modifier.width(220.dp).fillMaxHeight().staysInSection(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text("Playlists", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            item { Label("Up/Down to choose · Right for options", size = 12.sp, modifier = Modifier.padding(bottom = 4.dp)) }
            item(key = "add") {
                Focusable(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    color = if (adding) C.Surface2 else C.Surface,
                    onFocus = { if (sel != ADD_PLAYLIST) { sel = ADD_PLAYLIST; editing = false } },
                    onClick = { sel = ADD_PLAYLIST; editing = false },
                ) {
                    Text("＋", color = C.Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, end = 10.dp))
                    Label("Add playlist", size = 15.sp, color = C.Text, weight = FontWeight.SemiBold)
                }
            }
            items(vm.playlists, key = { "pl-" + it.id }) { p ->
                val on = p.id == sel
                Focusable(
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    color = if (on) C.Surface2 else C.Surface,
                    onFocus = { if (sel != p.id) { sel = p.id; editing = false } },
                    onClick = { sel = p.id },
                ) {
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Label(p.name, size = 15.sp, color = if (p.enabled) C.Text else C.Muted, weight = FontWeight.SemiBold)
                        Label(playlistState(p, false), size = 12.sp, color = if (p.enabled) C.Accent else C.Muted)
                    }
                    if (on) Text("›", color = C.Accent, fontSize = 20.sp, modifier = Modifier.padding(end = 10.dp))
                }
            }
            if (vm.playlists.isEmpty()) item { Label("No playlists yet. Avid Viewer doesn't include any channels; add your provider.", size = 12.sp, lines = Int.MAX_VALUE) }
        }

        // 2. Actions and switches for the chosen playlist
        if (selP != null) {
            LazyColumn(Modifier.width(250.dp).fillMaxHeight().staysInSection(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Column(Modifier.padding(bottom = 4.dp)) {
                        Label(selP.name, size = 17.sp, color = C.Text, weight = FontWeight.Bold, lines = 2)
                        Label(if (selP.isXtream) "Xtream Codes" else "M3U link", size = 12.sp)
                    }
                }
                item { TvButton("✎  Edit", modifier = Modifier.fillMaxWidth(), selected = editing) { editing = true; sources = false } }
                item { TvButton("▤  Guide sources", modifier = Modifier.fillMaxWidth().testTag("pl-guide-sources"), selected = sources) { sources = true; editing = false } }
                item {
                    TvButton(if (vm.busy) "Working…" else "↻  Refresh", modifier = Modifier.fillMaxWidth()) {
                        if (!vm.busy) confirm = Confirm(
                            "Refresh ${selP.name}?",
                            "Your saved channels, guide, movies and series for this playlist are replaced with what your provider has now. This can't be undone.",
                            "Refresh",
                        ) { vm.refreshPlaylist(selP.id) }
                    }
                }
                item {
                    TvButton("✕  Remove", modifier = Modifier.fillMaxWidth()) {
                        confirm = Confirm(
                            "Remove ${selP.name}?",
                            "The playlist, its login and everything saved from it are deleted from this device. This can't be undone. To keep it for later, turn it off instead.",
                            "Remove",
                        ) {
                            vm.removePlaylist(selP.id)
                            sel = vm.playlists.firstOrNull()?.id ?: ADD_PLAYLIST
                            editing = false
                        }
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
                item { MiniToggle("Playlist on", if (selP.enabled) "Shown in the guide, search and Sports" else "Hidden everywhere; nothing is deleted", selP.enabled) { vm.setPlaylistEnabled(selP.id, !selP.enabled) } }
                item { MiniToggle("Live TV & guide", if (selP.liveOn) "Channels and TV guide load" else "Live channels aren't loaded", selP.liveOn, enabled = selP.enabled) { vm.setPlaylistContent(selP.id, !selP.liveOn, selP.vodOn) } }
                item { MiniToggle("Movies & series", if (selP.vodOn) "Movies and series load" else "Movies and series aren't loaded", selP.vodOn, enabled = selP.enabled) { vm.setPlaylistContent(selP.id, selP.liveOn, !selP.vodOn) } }
                vm.status?.let { st -> item { Label(st, size = 12.sp, color = C.Soft, lines = Int.MAX_VALUE, modifier = Modifier.padding(top = 4.dp)) } }
            }
        }

        // 3. Add or edit form (far right)
        Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(C.Surface)) {
            when {
                adding -> PlaylistForm(vm, null, onCancel = null) { f ->
                    vm.addPlaylist(f.name, f.type, f.server, f.user, f.pass, f.m3u, f.epg, f.liveOn, f.vodOn)
                }
                editing -> PlaylistForm(vm, selP, onCancel = { editing = false }) { f ->
                    confirm = Confirm(
                        "Save changes to ${selP!!.name}?",
                        "If you changed the server, login or links, the saved channels and guide are cleared and downloaded again. Favorites from the old details may no longer match. This can't be undone.",
                        "Save",
                    ) { vm.updatePlaylist(selP.id, f.name, f.type, f.server, f.user, f.pass, f.m3u, f.epg) }
                }
                sources && selP != null -> GuideSourcesPane(vm, selP) { t, m, y, r -> confirm = Confirm(t, m, y, r) }
                else -> PlaylistDetails(selP!!, selP.id == active)
            }
        }
    }

    confirm?.let { c ->
        val cancelFocus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = C.Surface,
            title = { Text(c.title, color = C.Text, fontWeight = FontWeight.Bold) },
            text = { Label(c.message, size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE) },
            confirmButton = { TvButton(c.yes, primary = true) { confirm = null; c.run() } },
            dismissButton = { TvButton("Cancel", modifier = Modifier.focusRequester(cancelFocus)) { confirm = null } },
        )
        LaunchedEffect(c) { focusWhenReady { runCatching { cancelFocus.requestFocus() }.isSuccess } }
    }
}

private fun playlistState(p: com.avidviewer.tv.data.Playlist, active: Boolean): String = when {
    !p.enabled -> "Off"
    !p.liveOn && !p.vodOn -> "Nothing loaded"
    else -> listOfNotNull(if (p.liveOn) "Live TV" else null, if (p.vodOn) "Movies & series" else null).joinToString(" · ")
}

@Composable
private fun MiniToggle(title: String, desc: String, on: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    Focusable(Modifier.fillMaxWidth().heightIn(min = 58.dp), onClick = { if (enabled) onToggle() }) {
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Label(title, size = 14.sp, color = if (enabled) C.Text else C.Muted, weight = FontWeight.SemiBold, lines = 2)
            Label(if (enabled) desc else "Turn the playlist on first", size = 12.sp, lines = Int.MAX_VALUE)
        }
        Label(if (on) "On" else "Off", size = 12.sp, color = if (on && enabled) C.Accent else C.Muted, weight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
        Row(
            Modifier.padding(end = 12.dp).width(40.dp).height(22.dp).clip(RoundedCornerShape(11.dp)).background(if (on && enabled) C.Accent else C.Line).padding(3.dp),
            horizontalArrangement = if (on) Arrangement.End else Arrangement.Start,
        ) {
            Spacer(Modifier.width(16.dp).height(16.dp).clip(RoundedCornerShape(8.dp)).background(androidx.compose.ui.graphics.Color.White))
        }
    }
}

@Composable
private fun PlaylistDetails(p: com.avidviewer.tv.data.Playlist, active: Boolean) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Details", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        DetailLine("Name", p.name)
        DetailLine("Type", if (p.isXtream) "Xtream Codes" else "M3U link")
        if (p.isXtream) {
            DetailLine("Server", p.server)
            DetailLine("Username", p.username)
            DetailLine("Password", "•••••• (stored encrypted on this device)")
        } else {
            DetailLine("Playlist link", p.m3uUrl)
        }
        DetailLine("TV guide", p.epgUrl.ifBlank { if (p.isXtream) "From your login" else "From the playlist, if it lists one" })
        DetailLine("Status", playlistState(p, active).ifBlank { "On" })
        Label("Choose Edit to change these details.", size = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Label(label, size = 12.sp)
        Label(value, size = 14.sp, color = C.Text, lines = 2)
    }
}

private class PlaylistFields(
    val name: String, val type: String, val server: String, val user: String, val pass: String,
    val m3u: String, val epg: String, val liveOn: Boolean, val vodOn: Boolean,
)

/** The add form, or the edit form when [existing] is given. */
@Composable
private fun PlaylistForm(vm: AppViewModel, existing: com.avidviewer.tv.data.Playlist?, onCancel: (() -> Unit)?, onSubmit: (PlaylistFields) -> Unit) {
    val k = existing?.id ?: ADD_PLAYLIST
    var type by remember(k) { mutableStateOf(existing?.type ?: "xtream") }
    var name by remember(k) { mutableStateOf(existing?.name ?: "") }
    var server by remember(k) { mutableStateOf(existing?.server ?: "") }
    var user by remember(k) { mutableStateOf(existing?.username ?: "") }
    var pass by remember(k) { mutableStateOf("") }
    var m3u by remember(k) { mutableStateOf(existing?.m3uUrl ?: "") }
    var epg by remember(k) { mutableStateOf(existing?.epgUrl ?: "") }
    var liveOn by remember(k) { mutableStateOf(true) }
    var vodOn by remember(k) { mutableStateOf(true) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text(if (existing == null) "Add playlist" else "Edit ${existing.name}", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
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
            item { TvField(if (existing != null) "Password (leave empty to keep the current one)" else "Password", pass, password = true) { pass = it } }
            item { Label("Live TV and the TV guide load from your login. Your password is stored encrypted on this device only.", size = 12.sp, lines = Int.MAX_VALUE) }
        } else {
            item { TvField("M3U playlist link", m3u) { m3u = it } }
            if (existing == null) item { TvField("TV guide (XMLTV) link, optional", epg) { epg = it } }
            else item { Label("Guide sources for this playlist (add, edit, remove, time offset) are under ▤ Guide sources.", size = 12.sp, color = C.Soft, lines = Int.MAX_VALUE) }
        }
        if (existing == null) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("Load from this playlist", size = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton((if (liveOn) "✓ " else "") + "Live TV & guide", selected = liveOn) { liveOn = !liveOn }
                    TvButton((if (vodOn) "✓ " else "") + "Movies & series", selected = vodOn) { vodOn = !vodOn }
                }
            }
        }
        item {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(if (vm.busy) "Working…" else if (existing == null) "Add playlist" else "Save changes", primary = true) {
                    if (!vm.busy) {
                        onSubmit(PlaylistFields(name, type, server, user, pass, m3u, epg, liveOn, vodOn))
                        if (existing == null) pass = ""
                    }
                }
                if (onCancel != null) TvButton("Cancel") { onCancel() }
            }
        }
        if (existing == null) vm.status?.let { st -> item { Label(st, size = 12.sp, color = C.Soft, lines = Int.MAX_VALUE) } }
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

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
/**
 * A settings section's content: Up/Down never leave it; Left at its left edge goes to the section
 * it belongs to in the list ([sectionFr], not whichever row happens to be nearest, NAV-02); Right
 * into it lands on its first control ([firstFr], NAV-10).
 */
private fun Modifier.staysInSection(sectionFr: FocusRequester? = null, firstFr: FocusRequester? = null, firstOn: () -> Boolean = { false }): Modifier = this
    .focusProperties {
        exit = { dir ->
            when (dir) {
                androidx.compose.ui.focus.FocusDirection.Up, androidx.compose.ui.focus.FocusDirection.Down -> FocusRequester.Cancel
                androidx.compose.ui.focus.FocusDirection.Left -> sectionFr ?: FocusRequester.Default
                else -> FocusRequester.Default
            }
        }
        // Only while the stop is on screen (scrolled away, a lazy list has dropped it and asking it would crash).
        if (firstFr != null) enter = { dir -> if (dir == androidx.compose.ui.focus.FocusDirection.Right && firstOn()) firstFr else FocusRequester.Default }
    }
    .focusGroup()

private val SECTIONS = listOf(
    SettingsSection("playlists", "Playlists", NavIcons.Playlists),
    SettingsSection("channels", "List Editor", NavIcons.Manage),
    SettingsSection("location", "Location & weather", NavIcons.Location),
    SettingsSection("display", "Display & time", NavIcons.Guide),
    SettingsSection("guide", "TV guide", NavIcons.Guide),
    SettingsSection("sports", "Sports tab", NavIcons.Sports),
    SettingsSection("discover", "Discover", NavIcons.Discover),
    SettingsSection("playback", "Playback", NavIcons.Live),
    SettingsSection("privacy", "Privacy & analytics", NavIcons.Privacy),
    SettingsSection("storage", "Storage", NavIcons.Storage),
    SettingsSection("about", "Updates & about", NavIcons.Settings),
)

/**
 * An invisible stop at the top of a settings page: entering the page lands here, and it hands the
 * highlight straight down to the page's first control (whatever kind of control that is).
 */
@Composable
private fun FirstControlStop(fr: FocusRequester, on: androidx.compose.runtime.MutableState<Boolean>) {
    androidx.compose.runtime.DisposableEffect(fr) { on.value = true; onDispose { on.value = false } }
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.foundation.layout.Box(
        // Full width and 1 dp high: never "to the left" of a control, so Left from the page can't land here.
        Modifier.fillMaxWidth().height(1.dp).focusRequester(fr)
            // "Next" follows the page's order, so this is its first control (the leftmost of a row of buttons),
            // where "Down" picked whichever was nearest the middle.
            .onFocusChanged { if (it.isFocused) scope.launch { fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Next) } }
            .focusable(),
    )
}

/** Settings: sections on the left (OK opens one), options on the right. */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val p = vm.prefs
    val section = SECTIONS.firstOrNull { it.id == vm.settingsSection }?.id ?: "playlists"
    // The section list shows names while it has focus; once you move into a section it shrinks to icons,
    // giving the page the width. Moving back left (or Back) brings the names back.
    var compact by remember { mutableStateOf(false) }
    var settingsConfirm by remember { mutableStateOf<Confirm?>(null) }
    settingsConfirm?.let { c -> ConfirmDialog(c) { settingsConfirm = null } }
    val listWidth by androidx.compose.animation.core.animateDpAsState(if (compact) 64.dp else 230.dp, label = "settingsNav")
    // One handle per section row, so Left and Back from a section come back to its own row.
    val sectionFrs = remember { SECTIONS.associate { it.id to FocusRequester() } }
    // The first control of a section (a hidden stop at the top of the page that hands the highlight down).
    val firstFr = remember(section) { FocusRequester() }
    val firstOn = remember(section) { mutableStateOf(false) }
    // OK on a section: once its page is on screen, step into it (as Right does: its first control).
    var openTick by remember { mutableStateOf(0) }
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(openTick) {
        if (openTick > 0) focusWhenReady(vm, frames = 60, isFocused = { vm.settingsInContent }) { fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Right) }
    }
    // Back inside a section goes up one level, to its row in the section list (NAV-07); Back there opens the menu.
    LaunchedEffect(vm.settingsBackTick) {
        if (vm.settingsBackTick > 0) focusWhenReady(vm) { runCatching { sectionFrs[section]?.requestFocus() }.isSuccess }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.settingsInContent = false } }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(listWidth).fillMaxHeight().background(C.Bg)
                // More sections than fit on a TV screen: the list scrolls to keep the highlighted one in view.
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(if (compact) 6.dp else 10.dp)
                .onFocusChanged { if (it.hasFocus) compact = false },
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (compact) Spacer(Modifier.height(44.dp))
            else Text("Settings", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 10.dp))
            SECTIONS.forEach { sec ->
                val on = sec.id == section
                Focusable(
                    modifier = Modifier.fillMaxWidth().height(42.dp).focusRequester(sectionFrs.getValue(sec.id))
                        .then(if (sec == SECTIONS.first()) Modifier.entryFocus(vm, Screen.SETTINGS) else Modifier),
                    color = if (on) C.Surface2 else androidx.compose.ui.graphics.Color.Transparent,
                    // Highlighting a section only shows its summary; OK opens it and moves into it (NAV-08).
                    onClick = { vm.settingsSection = sec.id; openTick++ },
                ) {
                    androidx.compose.material3.Icon(sec.icon, contentDescription = sec.title, tint = if (on) C.Accent else C.Muted, modifier = Modifier.padding(start = if (compact) 12.dp else 10.dp).width(20.dp).height(20.dp))
                    if (!compact) Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Label(sec.title, size = 14.sp, color = if (on) C.Text else C.Soft, weight = if (on) FontWeight.Bold else FontWeight.Normal)
                        sectionSummary(vm, sec.id)?.let { Label(it, size = 12.sp) }
                    }
                }
            }
        }
        // Up/Down never leave the section you're in: at the top or bottom of its options the highlight stays put
        // (only Left goes back to the list of sections).
        val generic = section != "playlists" && section != "channels"
        Column(Modifier.weight(1f).fillMaxHeight().onFocusChanged { vm.settingsInContent = it.hasFocus; if (it.hasFocus) compact = true }
            .staysInSection(sectionFrs[section], if (generic) firstFr else null) { firstOn.value }) {
            when (section) {
                "playlists" -> PlaylistsScreen(vm)
                "channels" -> ListEditor(vm)
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FirstControlStop(firstFr, firstOn)
                        Text(SECTIONS.first { it.id == section }.title, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    when (section) {
                        "location" -> locationSettings(vm)
                        "display" -> displaySettings(vm)
                        "guide" -> guideSettings(vm) { t, m, y, r -> settingsConfirm = Confirm(t, m, y, r) }
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
                            item {
                                Label("The cache holds copies of channel logos, posters and recently streamed video, so screens load faster. Clearing it never removes your playlists, favorites, lists, TV guide, watch history or settings.", size = 13.sp, color = C.Soft, lines = Int.MAX_VALUE)
                            }
                            item {
                                ToggleRow(
                                    "Clear cache on exit",
                                    if (p.clearCacheOnExit) "On: every time you leave Avid Viewer, the cache is emptied. Logos and posters download again the next time you open the app, so the first screens may load more slowly."
                                    else "Off: the cache is kept between visits, so logos and posters appear right away. Turn this on to free up storage each time you leave the app.",
                                    p.clearCacheOnExit,
                                ) {
                                    vm.updatePrefs { it.copy(clearCacheOnExit = !it.clearCacheOnExit) }
                                }
                            }
                            item {
                                var size by remember { mutableStateOf(vm.store.cacheSizeBytes()) }
                                var note by remember { mutableStateOf<String?>(null) }
                                fun mb(b: Long) = if (b < 1024 * 1024) "${b / 1024} KB" else "${b / (1024 * 1024)} MB"
                                SettingBlock("Cache size: ${mb(size)}", "Clear it now to free up that space. Your playlists and settings stay as they are.") {
                                    TvButton("Clear cache now") {
                                        val before = size
                                        vm.store.clearCache(); size = vm.store.cacheSizeBytes()
                                        note = "Cleared ${mb((before - size).coerceAtLeast(0))}. Logos and posters will download again as you browse."
                                    }
                                    note?.let { Label(it, size = 12.sp, color = C.Accent, lines = Int.MAX_VALUE) }
                                }
                            }
                        }
                        "about" -> {
                            vm.lastCrash?.let { crash ->
                                item {
                                    SettingBlock("Last crash", "The app closed unexpectedly. These details stay on this device; share a photo of them to help fix the problem.") {
                                        Label(crash.lines().take(14).joinToString("\n"), size = 11.sp, color = C.Soft, lines = Int.MAX_VALUE)
                                        TvButton("Clear crash report") { vm.clearCrash() }
                                    }
                                }
                            }
                            item { BugReportBlock(vm) }
                            item { GuideAndDriverBlock() }
                            item { BackupBlock(vm) }
                            item {
                                SettingBlock("App updates", "Installed: version ${vm.installed.second}" + (vm.update?.let { " · version ${it.versionName} available" } ?: "")) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (vm.update != null) TvButton(if (vm.updateBusy) "Working…" else "Download and install", primary = true) { if (!vm.updateBusy) vm.installUpdate() }
                                        if (vm.updateFile != null && !vm.updateBusy) TvButton("Open installer", modifier = Modifier.testTag("update-open-installer")) { vm.openInstaller() }
                                        TvButton("Check now") { vm.checkForUpdate() }
                                        TvButton(if (p.autoUpdateCheck) "Auto-check: on" else "Auto-check: off", selected = p.autoUpdateCheck) { vm.updatePrefs { it.copy(autoUpdateCheck = !it.autoUpdateCheck) } }
                                    }
                                    vm.updateStatus?.let { Label(it, size = 12.sp, color = C.Soft, lines = 3) }
                                }
                            }
                            item { Label("Avid Viewer ${appVersion(vm)} · Bring your own provider; no channels are included.", size = 12.sp, lines = Int.MAX_VALUE) }
                            item {
                                SettingBlock("Credits", "This product uses the TMDB API but is not endorsed or certified by TMDB.") {
                                    Label("Streaming availability data provided by JustWatch.", size = 12.sp, color = C.Soft)
                                    Label("Weather by Open-Meteo. Sports scores from ESPN's public scoreboard.", size = 12.sp, color = C.Soft, lines = Int.MAX_VALUE)
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
    "playlists" -> "${vm.playlists.size} added" + (vm.playlists.count { !it.enabled }.takeIf { it > 0 }?.let { " · $it off" } ?: "")
    "channels" -> (vm.prefs.hiddenGroups.size + vm.prefs.hiddenChannels.size).let { if (it == 0) "Nothing hidden" else "$it hidden" }
    "guide" -> "Every ${vm.prefs.guideRefreshHours} h" + (vm.epgMatchCount().takeIf { it > 0 }?.let { " · $it matched" } ?: "")
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
    item { ToggleRow("Suggest local channels", "At the top of ★ Favorites: channels from your playlists that look local to your postal code. Favorites aren't suggested.", p.showSuggestions) { vm.setShowSuggestions(!p.showSuggestions) } }
    if (p.dismissedSuggestions.isNotEmpty()) item {
        SettingBlock("Dismissed suggestions", "${p.dismissedSuggestions.size} channel(s) you dismissed won't be suggested again.") {
            TvButton("Show them again") { vm.restoreSuggestions() }
        }
    }
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
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ZONES.forEach { (id, label) -> TvButton(label, selected = p.timeZone == id) { vm.updatePrefs { it.copy(timeZone = id) } } }
            }
        }
    }
}

/** Watch history and a viewing summary, all computed and kept on this device. */
private fun androidx.compose.foundation.lazy.LazyListScope.privacySettings(vm: AppViewModel) {
    val p = vm.prefs
    item { Label("Everything here stays on this device in the app's private storage. Nothing is uploaded or backed up to the cloud.", size = 13.sp, color = C.Soft, lines = Int.MAX_VALUE) }
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

@Composable
private fun BugReportBlock(vm: AppViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val version = appVersion(vm)
    val crash = vm.lastCrash
    val details = remember(version, crash) { com.avidviewer.tv.data.BugReport.details(version, crash) }
    val link = remember(version, crash) { com.avidviewer.tv.data.BugReport.mailto(version, crash) }
    val canEmail = remember { com.avidviewer.tv.data.BugReport.canEmail(ctx) }
    SettingBlock(
        "Report a bug",
        "Something not working? Email ${com.avidviewer.tv.data.BugReport.ADDRESS}. Say what happened and the steps that lead to it, and include the details below." +
            if (crash != null) " If the app closed unexpectedly, a photo of the crash report above helps too." else "",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            QrCode(link, Modifier.size(132.dp).testTag("bug-qr"))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label(com.avidviewer.tv.data.BugReport.ADDRESS, size = 16.sp, color = C.Accent, weight = FontWeight.Bold)
                Label("Scan with your phone's camera to start the email with these details filled in.", size = 12.sp, color = C.Soft, lines = Int.MAX_VALUE)
                details.forEach { (k, v) -> Label("$k: $v", size = 12.sp, color = C.Text, lines = Int.MAX_VALUE) }
                Label("No playlists, logins or provider addresses are included.", size = 11.sp, color = C.Muted, lines = Int.MAX_VALUE)
                if (canEmail) TvButton("Open email app", modifier = Modifier.testTag("bug-email")) {
                    runCatching { ctx.startActivity(com.avidviewer.tv.data.BugReport.emailIntent(version, crash)) }
                }
            }
        }
    }
}

/** QR codes for the user guide and the Control4 driver: a TV can't open them, a phone can. */
@Composable
private fun GuideAndDriverBlock() {
    SettingBlock("User guide and Control4 driver", "Scan with your phone's camera to open them.") {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            LinkQr("User guide (PDF)", "The latest version, with setup, every screen and release notes.", com.avidviewer.tv.data.Links.USER_GUIDE, "guide-qr")
            LinkQr("Control4 mini driver", "Puts Avid Viewer in the Control4 Watch menu (um_avid_viewer.c4z). Setup is in the user guide, section 12.", com.avidviewer.tv.data.Links.CONTROL4_DRIVER, "control4-qr")
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.LinkQr(title: String, note: String, url: String, tag: String) {
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        QrCode(url, Modifier.size(120.dp).testTag(tag))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label(title, size = 14.sp, color = C.Accent, weight = FontWeight.Bold)
            Label(note, size = 12.sp, color = C.Soft, lines = Int.MAX_VALUE)
        }
    }
}

/** A QR code drawn from its modules (white background so phones can read it on a dark screen). */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        runCatching {
            com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 2, com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"))
        }.getOrNull()
    } ?: return
    androidx.compose.foundation.Canvas(modifier.clip(RoundedCornerShape(6.dp)).background(androidx.compose.ui.graphics.Color.White)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) drawRect(androidx.compose.ui.graphics.Color.Black, topLeft = androidx.compose.ui.geometry.Offset(x * cell, y * cell), size = androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f))
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
    Focusable(Modifier.fillMaxWidth().heightIn(min = 60.dp), onClick = onToggle) {
        Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 9.dp)) {
            Label(title, size = 15.sp, color = C.Text, weight = FontWeight.SemiBold, lines = 2)
            Label(desc, size = 12.sp, lines = Int.MAX_VALUE)
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
internal fun SettingBlock(title: String, desc: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(title, size = 15.sp, color = C.Text, weight = FontWeight.SemiBold, lines = 2)
        if (desc.isNotBlank()) Label(desc, size = 12.sp, lines = Int.MAX_VALUE)
        content()
    }
}

// ---------------------------------------------------------------- Sports tab settings
private fun androidx.compose.foundation.lazy.LazyListScope.sportsSettings(vm: AppViewModel) {
    item {
        Label("Choose which categories appear on the Sports screen. Each one matches your provider's channel groups and channel names, plus guide listings that mention it. The order here is the order of the filter buttons.", size = 12.sp, lines = Int.MAX_VALUE)
    }
    val cats = vm.prefs.sportCats
    cats.forEachIndexed { i, c ->
        item(key = "cat-" + c.id) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TvButton(if (c.on) "On" else "Off", selected = c.on) {
                    vm.updateSportCats(cats.mapIndexed { j, x -> if (j == i) x.copy(on = !x.on) else x })
                }
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Label(c.name + if (c.builtin) "  · built-in" else "", size = 15.sp, color = C.Text, weight = FontWeight.SemiBold, lines = 2)
                    Label(if (c.groups.isNotEmpty()) "Playlist category: " + c.groups.joinToString(", ") { vm.groupName(it) } else "Matches: " + c.keywords.joinToString(", "), size = 12.sp, lines = Int.MAX_VALUE)
                }
                TvButton("▲") { if (i > 0) vm.updateSportCats(cats.toMutableList().apply { add(i - 1, removeAt(i)) }) }
                TvButton("▼") { if (i < cats.size - 1) vm.updateSportCats(cats.toMutableList().apply { add(i + 1, removeAt(i)) }) }
                TvButton("Remove") { vm.updateSportCats(cats.filterIndexed { j, _ -> j != i }) }
            }
        }
    }
    // Built-in categories can be removed; bring any of them back here.
    val missing = com.avidviewer.tv.data.SportCat.DEFAULTS.filter { d -> cats.none { it.id == d.id } }
    if (missing.isNotEmpty()) item(key = "cat-restore") {
        SettingBlock("Removed built-in categories", missing.joinToString(", ") { it.name }) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                missing.forEach { d -> TvButton("+ " + d.name) { vm.updateSportCats(vm.prefs.sportCats + d) } }
                if (missing.size > 1) TvButton("Restore all") { vm.updateSportCats(vm.prefs.sportCats + missing) }
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
            if (groups.size > 40) Label("Showing 40 of ${groups.size}. Type in the filter to narrow the list.", size = 12.sp, lines = Int.MAX_VALUE)
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
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.availableServices.let { row ->
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
/** Which part of Settings › List Editor is showing. */
private enum class LeTab(val title: String) { LIVE("Live TV"), MOVIES("Movies"), SERIES("Series"), LISTS("☰ My lists") }

/** One category in the List Editor: a live category of a playlist, or a movie/series category of a playlist. */
private data class LeCat(val key: String, val pid: String, val raw: String, val name: String, val count: Int)

/**
 * Settings › List Editor (layout "A"): only showing and hiding, one at a time or in bulk.
 * Tabs: Live TV · Movies · Series · My lists. Categories from every playlist are grouped under the
 * playlist's name; highlighting one lists its channels or titles on the right. Rename, re-order and
 * Add to List live on the Menu key where the item is (TV Guide, Movies, Series).
 */
@Composable
private fun ListEditor(vm: AppViewModel) {
    var tab by remember { mutableStateOf(LeTab.LIVE) }
    Column(Modifier.fillMaxSize().padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("List Editor", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Label(
            "Show or hide categories, channels, movies and series — one at a time with OK, or many at once with the filter and Show all / Hide all. " +
                "To rename, re-order or add to a list, press Menu on the item itself in the TV Guide, Movies or Series.",
            size = 12.sp, lines = Int.MAX_VALUE,
        )
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LeTab.values().forEach { t ->
                val label = if (t == LeTab.LISTS) "${t.title} (${vm.prefs.lists.size})" else t.title
                TvButton(label, selected = tab == t, modifier = Modifier.testTag("le-tab-" + t.name)) { tab = t }
            }
        }
        when (tab) {
            LeTab.LISTS -> MyListsPane(vm, Modifier.weight(1f).fillMaxWidth())
            else -> EditorColumns(vm, tab, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun EditorColumns(vm: AppViewModel, tab: LeTab, modifier: Modifier) {
    val live = tab == LeTab.LIVE
    val kind = if (tab == LeTab.SERIES) "series" else "movie"
    // Live channels of every playlist (hidden ones included), loaded once per visit.
    var liveData by remember { mutableStateOf<Map<String, List<com.avidviewer.tv.data.Channel>>>(emptyMap()) }
    LaunchedEffect(live, vm.playlists, vm.channels) {
        if (live) liveData = vm.playlists.filter { it.liveOn }.associate { it.id to vm.editorChannels(it.id) }
        else vm.ensureVod()
    }
    // Sections: one per playlist, with its categories.
    val sections: List<Pair<com.avidviewer.tv.data.Playlist, List<LeCat>>> = vm.playlists.map { p ->
        p to if (live) {
            liveData[p.id]?.let { chs -> vm.editorGroups(chs).map { (raw, n) -> LeCat("L|${p.id}|$raw", p.id, raw, vm.groupName(raw), n) } } ?: emptyList()
        } else {
            vm.vodEditorCategories(kind, p.id).map { (c, n) -> LeCat(vm.vodCatKey(kind, p.id, c), p.id, c, vm.vodCatName(kind, c), n) }
        }
    }
    fun hidden(c: LeCat) = if (live) vm.isGroupHidden(c.pid, c.raw) else c.key in vm.prefs.vodHiddenCats
    fun setHidden(cs: List<LeCat>, h: Boolean) {
        if (live) cs.groupBy { it.pid }.forEach { (pid, l) -> vm.setGroupsHidden(pid, l.map { it.raw }, h) }
        else vm.setVodCatsHidden(cs.map { it.key }, h)
    }
    var catQ by remember(tab) { mutableStateOf("") }
    var itemQ by remember(tab) { mutableStateOf("") }
    var selKey by remember(tab) { mutableStateOf<String?>(null) }
    val filtered = sections.map { (p, l) -> p to l.filter { catQ.isBlank() || it.name.contains(catQ, ignoreCase = true) } }
    val allShown = filtered.flatMap { it.second }
    val sel = allShown.firstOrNull { it.key == selKey } ?: allShown.firstOrNull()

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Categories, grouped by playlist
        Column(Modifier.width(320.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val hidCount = allShown.count { hidden(it) }
            Label("CATEGORIES · ${allShown.size}" + if (hidCount > 0) " · $hidCount hidden" else "", size = 12.sp, weight = FontWeight.Bold)
            TvField("Filter categories", catQ, modifier = Modifier.fillMaxWidth()) { catQ = it }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val n = if (catQ.isBlank()) "all" else "${allShown.size}"
                TvButton("Show $n", modifier = Modifier.testTag("le-cats-show")) { setHidden(allShown, false) }
                TvButton("Hide $n", modifier = Modifier.testTag("le-cats-hide")) { setHidden(allShown, true) }
            }
            if (!live && vm.vodLoading && vm.vod.isEmpty()) Label("Loading movies and series…", size = 13.sp)
            LazyColumn(Modifier.weight(1f).testTag("le-cats"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                filtered.forEach { (p, cats) ->
                    item(key = "h-" + p.id) {
                        val off = when {
                            !p.enabled -> " · playlist is off"
                            live && !p.liveOn -> " · live TV is off"
                            !live && !p.vodOn -> " · movies & series are off"
                            cats.isEmpty() -> if (catQ.isBlank()) " · nothing saved yet" else " · no matches"
                            else -> ""
                        }
                        Label(p.name.uppercase() + off, size = 11.sp, weight = FontWeight.Bold, lines = 2, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp))
                    }
                    items(cats, key = { "c-" + it.key }) { c ->
                        val h = hidden(c)
                        EditorRow(
                            title = c.name, sub = "${c.count} " + if (live) "channels" else "titles", hidden = h, selected = c.key == sel?.key,
                            onFocus = { selKey = c.key; itemQ = "" }, onToggle = { setHidden(listOf(c), !h) },
                        )
                    }
                }
            }
        }
        // What's in the highlighted category
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sel == null) {
                Label("Choose a category on the left.", size = 13.sp)
                return@Column
            }
            val chs = if (live) vm.editorChannelsIn(liveData[sel.pid] ?: emptyList(), sel.raw) else emptyList()
            val titles = if (live) emptyList() else remember(sel.key, vm.vodVersion) { vm.vodEditorTitles(kind, sel.pid, sel.raw) }
            val total = if (live) chs.size else titles.size
            val hiddenN = if (live) chs.count { it.key in vm.prefs.hiddenChannels } else titles.count { it.key in vm.prefs.vodHiddenTitles }
            Label(sel.name.uppercase() + " · $total" + (if (hiddenN > 0) " · $hiddenN hidden" else ""), size = 12.sp, weight = FontWeight.Bold, lines = 2)
            TvField(if (live) "Filter channels" else "Filter titles", itemQ, modifier = Modifier.fillMaxWidth()) { itemQ = it }
            val shownChs = chs.filter { itemQ.isBlank() || vm.channelName(it).contains(itemQ, ignoreCase = true) }
            val shownTitles = if (live || itemQ.isBlank()) titles else remember(sel.key, itemQ, vm.vodVersion) { vm.vodEditorTitles(kind, sel.pid, sel.raw, itemQ) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val n = if (itemQ.isBlank()) "all" else "${if (live) shownChs.size else shownTitles.size}"
                TvButton("Show $n", modifier = Modifier.testTag("le-items-show")) {
                    if (live) vm.setChannelsHidden(shownChs.map { it.key }, false) else vm.setVodTitlesHidden(shownTitles.map { it.key }, false)
                }
                TvButton("Hide $n", modifier = Modifier.testTag("le-items-hide")) {
                    if (live) vm.setChannelsHidden(shownChs.map { it.key }, true) else vm.setVodTitlesHidden(shownTitles.map { it.key }, true)
                }
                if (hidden(sel)) Label("This category is hidden, so none of these show.", size = 12.sp, color = C.Soft, lines = 2, modifier = Modifier.weight(1f))
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (live) itemsIndexed(shownChs, key = { i, c -> "lc-$i-${c.key}" }) { _, c ->
                    val h = c.key in vm.prefs.hiddenChannels
                    EditorRow(title = vm.channelName(c), sub = null, hidden = h, selected = false, logo = c.logo, onFocus = {}, onToggle = { vm.setChannelsHidden(listOf(c.key), !h) })
                } else itemsIndexed(shownTitles, key = { i, v -> "lv-$i-${v.key}" }) { _, v ->
                    val h = v.key in vm.prefs.vodHiddenTitles
                    EditorRow(title = v.name, sub = null, hidden = h, selected = false, logo = v.logo, onFocus = {}, onToggle = { vm.setVodTitlesHidden(listOf(v.key), !h) })
                }
            }
        }
    }
}

/** List Editor › My lists: show or hide the lists you made. Rename, re-order and delete are on Menu in the TV Guide. */
@Composable
private fun MyListsPane(vm: AppViewModel, modifier: Modifier) {
    val lists = vm.prefs.lists
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Label("Hidden lists keep their channels but don't appear in the TV Guide. To rename, re-order or delete a list, press Menu on it in the TV Guide's category list. ★ Favorites is always shown.", size = 12.sp, lines = Int.MAX_VALUE)
        }
        if (lists.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TvButton("Show all") { lists.forEach { vm.setListHidden(LIST_PREFIX + it.id, false) } }
                TvButton("Hide all") { lists.forEach { vm.setListHidden(LIST_PREFIX + it.id, true) } }
            }
        }
        item { EditorRow(title = "★ Favorites", sub = "${vm.prefs.favorites.size} channels · always shown", hidden = false, selected = false, onFocus = {}, onToggle = {}) }
        if (lists.isEmpty()) item { Label("You haven't made any lists yet. In the TV Guide, press Menu on a channel and choose Add to List.", size = 13.sp, lines = Int.MAX_VALUE) }
        items(lists, key = { "ml-" + it.id }) { l ->
            EditorRow(
                title = l.name, sub = "${l.keys.size} channels" + if (l.hidden) " · not in the TV Guide" else "", hidden = l.hidden, selected = false,
                onFocus = {}, onToggle = { vm.setListHidden(LIST_PREFIX + l.id, !l.hidden) },
            )
        }
    }
}

@Composable
private fun EditorRow(title: String, sub: String?, hidden: Boolean, selected: Boolean, logo: String? = null, logoName: String = title, onFocus: () -> Unit, onToggle: () -> Unit) {
    Focusable(
        modifier = Modifier.fillMaxWidth().heightIn(min = if (sub != null) 50.dp else 46.dp),
        color = if (selected) C.Surface2 else C.Surface,
        onFocus = onFocus,
        onClick = onToggle,
    ) {
        if (sub == null) Row(Modifier.padding(start = 8.dp)) { ChannelBadge(logoName, logo, 26.dp) }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 5.dp)) {
            Label(title, size = 13.sp, color = if (hidden) C.Muted else C.Text, weight = FontWeight.SemiBold, lines = 3)
            if (sub != null) Label(sub, size = 12.sp)
        }
        Label(if (hidden) "Hidden" else "Shown", size = 12.sp, color = if (hidden) C.Muted else C.Accent, weight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
    }
}

/** Settings › Updates & about › Backup & restore. */
@Composable
private fun BackupBlock(vm: AppViewModel) {
    var making by remember { mutableStateOf(false) }
    SettingBlock(
        "Backup & restore",
        "Save your playlists (with their logins), favorites, lists, names, order, hidden items, Sports categories, location and settings to a file in this device's Download folder. " +
            "It stays there if Avid Viewer is deleted, so after reinstalling you can restore everything here. Channels, guides and movies download again on their own.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(if (vm.backupBusy) "Working…" else "Back up now", primary = true, modifier = Modifier.testTag("backup-now")) { if (!vm.backupBusy) making = true }
            TvButton("Restore from a backup", modifier = Modifier.testTag("backup-restore")) { if (!vm.backupBusy) vm.startRestore() }
        }
        vm.backupStatus?.let { Label(it, size = 12.sp, color = C.Soft, lines = Int.MAX_VALUE) }
        if (vm.foundBackups.isNotEmpty()) {
            Label("Backups found in the Download folder — choose one:", size = 12.sp, lines = Int.MAX_VALUE)
            vm.foundBackups.take(6).forEach { f ->
                val date = java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.getDefault()).format(f.modified)
                TvButton("${f.name}  ·  $date") { vm.chooseFoundBackup(f) }
            }
        }
    }
    ShareBlock(vm)
    if (making) BackupPinDialog(vm) { making = false }
    if (vm.pendingRestore != null) RestorePinDialog(vm)
}

/** Backups on a SAMBA / Windows network share (a folder on a PC or NAS). */
@Composable
private fun ShareBlock(vm: AppViewModel) {
    var address by remember { mutableStateOf(vm.prefs.smbAddress) }
    var user by remember { mutableStateOf(vm.prefs.smbUser) }
    var password by remember { mutableStateOf("") }
    var making by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    SettingBlock(
        "Network share (SAMBA)",
        "Keep backups in a shared folder on your PC or NAS, so a reinstalled app or a new TV can restore without a file manager or USB drive. " +
            "Enter the share like \\\\192.168.1.20\\Backups\\AvidViewer (the folder is made if it's missing). Leave the user name empty for a guest share.",
    ) {
        TvField("Share address, e.g. \\\\NAS\\Backups\\AvidViewer", address, modifier = Modifier.fillMaxWidth()) { address = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvField("User name (or DOMAIN\\user)", user, modifier = Modifier.weight(1f)) { user = it }
            TvField(if (vm.prefs.smbPassEnc.isNotBlank()) "Password (leave empty to keep)" else "Password", password, password = true, modifier = Modifier.weight(1f)) { password = it }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton("Save and test", modifier = Modifier.testTag("smb-save")) { vm.saveShare(address, user, password.ifEmpty { null }); password = "" }
            if (vm.smbReady) {
                TvButton("Back up to the share", primary = true, modifier = Modifier.testTag("smb-backup")) { if (!vm.backupBusy) making = true }
                TvButton("Restore from the share", modifier = Modifier.testTag("smb-restore")) { vm.listShareBackups() }
            }
        }
        vm.smbStatus?.let { Label(it, size = 12.sp, color = C.Soft, lines = Int.MAX_VALUE) }
        if (vm.shareBackups.isNotEmpty()) {
            Label("Backups on the share — choose one:", size = 12.sp, lines = Int.MAX_VALUE)
            vm.shareBackups.take(8).forEach { (name, modified) ->
                val date = java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.getDefault()).format(modified)
                TvButton("$name  ·  $date") { vm.chooseShareBackup(name) }
            }
        }
    }
    if (making) BackupPinDialog(vm, toShare = true) { making = false }
}

/** Choose a PIN for a new backup (entered twice). */
@Composable
private fun BackupPinDialog(vm: AppViewModel, toShare: Boolean = false, close: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = close,
        containerColor = C.Surface,
        title = { Text("Choose a PIN for this backup", color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("The backup holds your provider logins, so it's locked with a PIN. You'll need the same PIN to restore it, and it can't be recovered if forgotten.", size = 13.sp, color = C.Soft, lines = Int.MAX_VALUE)
                TvField("PIN (4 or more digits)", pin, password = true, modifier = Modifier.fillMaxWidth()) { pin = it.filter { c -> c.isDigit() }.take(12) }
                TvField("Enter the PIN again", again, password = true, modifier = Modifier.fillMaxWidth()) { again = it.filter { c -> c.isDigit() }.take(12) }
                problem?.let { Label(it, size = 12.sp, color = C.AccentFocus, lines = Int.MAX_VALUE) }
            }
        },
        confirmButton = {
            TvButton("Save backup", primary = true) {
                problem = when {
                    pin.length < 4 -> "Use at least 4 digits."
                    pin != again -> "The two PINs don't match."
                    else -> null
                }
                if (problem == null) { vm.backUp(pin, toShare); close() }
            }
        },
        dismissButton = { TvButton("Cancel") { close() } },
    )
}

/** Enter the backup's PIN and confirm replacing what's on this device. */
@Composable
private fun RestorePinDialog(vm: AppViewModel) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf<String?>(null) }
    val cancel = remember { FocusRequester() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.cancelRestore() },
        containerColor = C.Surface,
        title = { Text("Restore this backup?", color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(vm.pendingRestoreInfo, size = 13.sp, color = C.Text, lines = Int.MAX_VALUE)
                Label("Restoring replaces the playlists and settings on this device with the ones in the backup. This can't be undone.", size = 13.sp, color = C.Soft, lines = Int.MAX_VALUE)
                TvField("Backup PIN", pin, password = true, modifier = Modifier.fillMaxWidth()) { pin = it.filter { c -> c.isDigit() }.take(12) }
                wrong?.let { Label(it, size = 12.sp, color = C.AccentFocus, lines = Int.MAX_VALUE) }
            }
        },
        confirmButton = {
            TvButton(if (vm.backupBusy) "Restoring…" else "Restore", primary = true) {
                if (!vm.backupBusy) vm.restore(pin) { ok -> if (!ok) wrong = vm.backupStatus ?: "That PIN doesn't open this backup." }
            }
        },
        dismissButton = { TvButton("Cancel", modifier = Modifier.focusRequester(cancel)) { vm.cancelRestore() } },
    )
}
