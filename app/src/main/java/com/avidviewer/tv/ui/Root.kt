package com.avidviewer.tv.ui

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
    Box(Modifier.fillMaxSize().background(C.Bg)) {
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
        // New version found: ask before updating (never interrupts full-screen viewing).
        val rel = vm.update
        if (rel != null && !vm.updatePromptDismissed && vm.screen != Screen.PLAYER) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { if (!vm.updateBusy) vm.updatePromptDismissed = true },
                containerColor = C.Surface,
                title = { Text("Update available", color = C.Text, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Label("Avid Viewer ${rel.versionName} is ready to install. You have ${vm.installed.second}.", size = 14.sp, color = C.Soft, lines = 2)
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
