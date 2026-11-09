package com.avidviewer.tv.ui

import androidx.compose.foundation.border
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@Composable
fun AppRoot(vm: AppViewModel) {
    Box(Modifier.fillMaxSize().background(C.Bg).holdToAccelerate()) {
        if (vm.screen == Screen.PLAYER) {
            PlayerScreen(vm)
        } else {
            Row(Modifier.fillMaxSize()) {
                val browsing = vm.screen == Screen.LIVE || vm.screen == Screen.GUIDE
                if (vm.navMode != NavMode.GROUPS || !browsing) NavRail(vm)
                if (browsing && vm.navMode == NavMode.COLLAPSED) PlaylistTree(vm)
                if (browsing && vm.navMode == NavMode.GROUPS) GroupNav(vm)
                Box(Modifier.fillMaxHeight().weight(1f)) {
                    when (vm.screen) {
                        Screen.SEARCH -> SearchScreen(vm)
                        Screen.DISCOVER -> DiscoverScreen(vm)
                        Screen.FAVORITES -> FavoritesScreen(vm)
                        Screen.LIVE -> GuideScreen(vm) // Live TV was folded into the TV Guide
                        Screen.GUIDE -> GuideScreen(vm)
                        Screen.SPORTS -> SportsScreen(vm)
                        Screen.MOVIES -> VodScreen(vm, "movie")
                        Screen.SERIES -> VodScreen(vm, "series")
                        Screen.MANAGE -> ManageScreen(vm)
                        Screen.PLAYLISTS -> SettingsScreen(vm) // Playlists now live in Settings
                        Screen.SETTINGS -> SettingsScreen(vm)
                        Screen.PLAYER -> Unit
                    }
                }
            }
        }
        ChannelOptionsDialog(vm)
        GroupOptionsDialog(vm)
        ListPickerDialog(vm)
        ReorderHelp(vm)
        ListOptionsDialog(vm)
        // New version found: ask before updating (never interrupts full-screen viewing).
        val rel = vm.update
        if (rel != null && !vm.updatePromptDismissed && vm.screen != Screen.PLAYER) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { if (!vm.updateBusy) vm.updatePromptDismissed = true },
                containerColor = C.Surface,
                title = { Text("Update available", color = C.Text, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Label("Avid Viewer ${rel.versionName} is ready to install. You have ${vm.installed.second}.", size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE)
                        Label("Your playlists, favorites and settings are kept.", size = 13.sp, lines = 2)
                        vm.updateStatus?.let { Label(it, size = 13.sp, color = C.Accent, lines = 3) }
                    }
                },
                confirmButton = {
                    TvButton(if (vm.updateBusy) "Updating…" else "Update now", primary = true) { if (!vm.updateBusy) vm.installUpdate() }
                },
                dismissButton = {
                    TvButton("Later") { vm.updatePromptDismissed = true }
                },
            )
        }
        // Back walks up the menu levels; Back at the full menu on the TV Guide exits.
        BackHandler(enabled = vm.screen != Screen.PLAYER && !(vm.navMode == NavMode.FULL && vm.screen == Screen.GUIDE)) { vm.navBack() }
    }
}

/** Video surface for the shared player. Several screens can show it; only one at a time is on screen. */
/**
 * A preview window the remote can move onto: it shows a focus ring and an "OK · Full screen" hint,
 * and OK opens what's playing full screen. [overlay] draws on top (e.g. a LIVE tag).
 */
@Composable
fun PreviewWindow(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    Focusable(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        color = Color.Black,
        focusedColor = Color.Black,
        onClick = onOpen,
    ) { focused ->
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().padding(if (focused) 3.dp else 0.dp)) {
            VideoSurface(vm, Modifier.fillMaxSize())
            overlay()
            if (focused) Text(
                "OK · Full screen",
                color = C.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(6.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(C.AccentFocus).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(vm: AppViewModel, modifier: Modifier = Modifier, fit: Boolean = true) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                resizeMode = if (fit) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                isFocusable = false
                isFocusableInTouchMode = false
                player = vm.player
            }
        },
        update = { if (it.player !== vm.player) it.player = vm.player },
        onRelease = { it.player = null },
        modifier = modifier.background(Color.Black),
    )
}

/** On-screen instructions while a category or channel is being re-ordered. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ReorderHelp(vm: AppViewModel) {
    val what = vm.movingGroup?.let { "category “${vm.groupName(it)}”" }
        ?: vm.movingChannel?.let { k -> vm.channel(k)?.let { "channel “${vm.channelName(it)}”" } }
        ?: return
    androidx.compose.foundation.layout.Column(
        Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 18.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(C.Surface2)
            .border(1.dp, C.AccentFocus, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        Text("Re-ordering the $what", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("▲ ▼  Move up or down (hold to move faster)     ◀  Move to the top     ▶  Move to the bottom", color = C.Soft, fontSize = 13.sp)
        Text("OK or Back  Done — the new order is saved", color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
