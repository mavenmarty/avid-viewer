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
                NavRail(vm)
                Box(Modifier.fillMaxHeight().weight(1f)) {
                    when (vm.screen) {
                        Screen.SEARCH -> SearchScreen(vm)
                        Screen.FAVORITES -> FavoritesScreen(vm)
                        Screen.LIVE -> LiveScreen(vm)
                        Screen.GUIDE -> GuideScreen(vm)
                        Screen.SPORTS -> SportsScreen(vm)
                        Screen.MANAGE -> ManageScreen(vm)
                        Screen.PLAYLISTS -> PlaylistsScreen(vm)
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
        // Back from any other screen returns to the TV Guide; Back on the guide exits.
        BackHandler(enabled = vm.screen != Screen.GUIDE && vm.screen != Screen.PLAYER) { vm.screen = Screen.GUIDE }
    }
}

@Composable
private fun NavRail(vm: AppViewModel) {
    val first = remember { FocusRequester() }
    Column(
        Modifier.width(168.dp).fillMaxHeight().background(C.Rail).padding(horizontal = 10.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 14.dp)) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.avidviewer.tv.R.drawable.logo),
                contentDescription = null,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Text("Avid Viewer", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        val items = listOf(
            Screen.SEARCH to "Search", Screen.FAVORITES to "Favorites", Screen.LIVE to "Live TV", Screen.GUIDE to "TV Guide", Screen.SPORTS to "Sports",
            Screen.MANAGE to "Manage", Screen.PLAYLISTS to "Playlists", Screen.SETTINGS to "Settings",
        )
        items.forEachIndexed { i, (s, label) ->
            val active = vm.screen == s
            Focusable(
                modifier = Modifier.fillMaxWidth().height(38.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                color = if (active) C.Accent else Color.Transparent,
                focusedColor = if (active) C.AccentFocus else C.Surface2,
                onClick = { if (s == Screen.SPORTS) vm.openSportsScreen() else { if (s == Screen.SETTINGS) vm.settingsSection = "general"; vm.screen = s } },
            ) {
                Text(
                    label, color = if (active) C.OnAccent else C.Soft, fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            if (s == Screen.FAVORITES || s == Screen.SPORTS) Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.weight(1f))
        if (vm.prefs.showClock) {
            Column(Modifier.padding(start = 8.dp)) {
                Text(vm.fmtTime(vm.now), color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Label(vm.fmtDate(vm.now))
            }
        }
        vm.weatherLine()?.let {
            Label(it, size = 13.sp, color = C.Soft, lines = 2, modifier = Modifier.padding(start = 8.dp, top = 6.dp))
        }
        vm.update?.let { rel ->
            TvButton("Update ${rel.versionName}", primary = true, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
                vm.settingsSection = "general"; vm.screen = Screen.SETTINGS
            }
        }
        vm.status?.let {
            Label(it, size = 11.sp, lines = 3, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
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
