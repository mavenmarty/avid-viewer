package com.avidviewer.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** "Add to List": Favorites first, then the user's lists (✓ = already in it), then "New list". */
@Composable
fun ListPickerDialog(vm: AppViewModel) {
    val key = vm.listPickerKey ?: return
    val c = vm.channel(key)
    var naming by remember(key) { mutableStateOf(false) }
    if (naming) {
        RenameDialog("New list", "", onDismiss = { naming = false }) { name ->
            vm.createList(name, key)
            naming = false
            vm.listPickerKey = null // the channel is in the new list: done
        }
        return
    }
    val first = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = { vm.listPickerKey = null },
        containerColor = C.Surface,
        title = { Text("Add to List", color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(c?.let { vm.channelName(it) + " · " + vm.playlistName(it) } ?: "", size = 13.sp, color = C.Soft, lines = 2)
                LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(vm.allLists(), key = { it.first }) { (group, name) ->
                        val on = vm.inList(group, key)
                        Focusable(
                            modifier = Modifier.fillMaxWidth().height(44.dp).then(if (group == FAV_GROUP) Modifier.focusRequester(first) else Modifier),
                            color = if (on) C.Surface2 else C.Bg,
                            // Choosing a list is the whole action: the picker closes (choosing a ✓ list takes the channel out).
                            onClick = { vm.toggleInList(group, key); vm.listPickerKey = null },
                        ) {
                            Label((if (on) "✓  " else "＋  ") + name, size = 15.sp, color = C.Text, weight = FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp).weight(1f))
                            Label(if (on) "In list" else "", size = 12.sp, color = C.Accent, modifier = Modifier.padding(end = 14.dp))
                        }
                    }
                    item(key = "new") {
                        Focusable(Modifier.fillMaxWidth().height(44.dp), color = C.Bg, onClick = { naming = true }) {
                            Label("＋  New list…", size = 15.sp, color = C.Accent, weight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TvButton("Done", primary = true) { vm.listPickerKey = null } },
    )
    LaunchedEffect(key) { focusWhenReady(vm) { runCatching { first.requestFocus() }.isSuccess } }
}

/** Menu key on one of the user's lists in the TV Guide: rename, move, delete (with a confirmation). */
@Composable
fun ListOptionsDialog(vm: AppViewModel) {
    val group = vm.listMenu ?: return
    val lst = vm.userList(group) ?: run { vm.listMenu = null; return }
    var renaming by remember(group) { mutableStateOf(false) }
    var deleting by remember(group) { mutableStateOf(false) }
    if (renaming) {
        RenameDialog("Rename list", lst.name, onDismiss = { renaming = false }) { vm.renameList(group, it); renaming = false; vm.listMenu = null }
        return
    }
    if (deleting) {
        val cancel = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = C.Surface,
            title = { Text("Delete “${lst.name}”?", color = C.Text, fontWeight = FontWeight.Bold) },
            text = { Label("The list is removed. Its ${lst.keys.size} channel(s) stay in your playlists. This can't be undone.", size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE) },
            confirmButton = { TvButton("Delete", primary = true) { vm.deleteList(group); deleting = false; vm.listMenu = null } },
            dismissButton = { TvButton("Cancel", modifier = Modifier.focusRequester(cancel)) { deleting = false } },
        )
        LaunchedEffect(Unit) { focusWhenReady(vm) { runCatching { cancel.requestFocus() }.isSuccess } }
        return
    }
    AlertDialog(
        onDismissRequest = { vm.listMenu = null },
        containerColor = C.Surface,
        title = { Text(lst.name, color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("${lst.keys.size} channels · from any playlist", size = 13.sp, color = C.Soft)
                OptionRow("✎  Rename") { renaming = true }
                OptionRow("▲  Move up") { vm.moveList(group, -1) }
                OptionRow("▼  Move down") { vm.moveList(group, 1) }
                OptionRow("✕  Delete list") { deleting = true }
            }
        },
        confirmButton = { TvButton("Done", primary = true) { vm.listMenu = null } },
    )
}

/** "New list" from the TV Guide's left navigation (no channel yet). */
@Composable
fun NewListDialog(vm: AppViewModel, onDone: () -> Unit) {
    RenameDialog("New list", "", onDismiss = onDone) { name ->
        val g = vm.createList(name)
        onDone()
        vm.chooseGroup(vm.activePlaylist()?.id ?: "", g)
    }
}

/** Menu key on a Movies or Series category: rename, re-order in place, hide. */
@Composable
fun VodCategoryOptionsDialog(vm: AppViewModel) {
    val (kind, cat) = vm.vodCatMenu ?: return
    var renaming by remember(kind, cat) { mutableStateOf(false) }
    if (renaming) {
        RenameDialog("Rename category", vm.vodCatName(kind, cat), onDismiss = { renaming = false }) { vm.renameVodCat(kind, cat, it); renaming = false; vm.vodCatMenu = null }
        return
    }
    val cats = vm.vodCategories(kind)
    val pos = cats.indexOfFirst { it.first == cat }
    AlertDialog(
        onDismissRequest = { vm.vodCatMenu = null },
        containerColor = C.Surface,
        title = { Text(vm.vodCatName(kind, cat), color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label((if (kind == "series") "Series" else "Movies") + " category ${pos + 1} of ${cats.size} · ${cats.getOrNull(pos)?.second ?: 0} titles", size = 13.sp, color = C.Soft, lines = Int.MAX_VALUE)
                OptionRow("✎  Rename") { renaming = true }
                OptionRow("⇅  Re-order") { vm.vodCatMenu = null; vm.vodMovingCat = kind to cat }
                OptionRow("⊘  Hide category") { vm.hideVodCatEverywhere(kind, cat); vm.vodCatMenu = null }
                Label("Hidden categories come back in Settings › List Editor.", size = 12.sp, lines = Int.MAX_VALUE)
            }
        },
        confirmButton = { TvButton("Done", primary = true) { vm.vodCatMenu = null } },
    )
}
