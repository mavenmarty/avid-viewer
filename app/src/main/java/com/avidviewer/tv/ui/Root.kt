package com.avidviewer.tv.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import androidx.compose.foundation.layout.aspectRatio
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
    Box(
        Modifier.fillMaxSize().background(C.Bg)
            .onFocusChanged { vm.anyFocus = it.hasFocus }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown) { vm.lastKeyAt = android.os.SystemClock.uptimeMillis(); Images.holding = it.nativeKeyEvent.repeatCount > 0 }
                else if (it.type == KeyEventType.KeyUp) Images.holding = false
                false
            }
            .leftGoesBack(vm)
            .holdToAccelerate(),
    ) {
        val rescueFm = androidx.compose.ui.platform.LocalFocusManager.current
        LaunchedEffect(vm.focusRescue) {
            if (vm.focusRescue == 0) return@LaunchedEffect
            for (frame in 0 until 10) {
                androidx.compose.runtime.withFrameNanos { }
                if (vm.anyFocus) break
                if (!rescueFm.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)) rescueFm.moveFocus(androidx.compose.ui.focus.FocusDirection.Next)
            }
        }
        if (vm.screen == Screen.PLAYER) {
            PlayerScreen(vm)
        } else {
            Row(Modifier.fillMaxSize()) {
                val browsing = vm.screen == Screen.LIVE || vm.screen == Screen.GUIDE
                // The main menu is always there: icons only while a lower layer is in use.
                NavRail(vm)
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
        MatchGuideDialog(vm)
        ReorderHelp(vm)
        ListOptionsDialog(vm)
        VodCategoryOptionsDialog(vm)
        // The app closed unexpectedly last time: say so once (details stay in Settings › About).
        if (vm.lastCrash != null && !vm.crashNoticeSeen && vm.screen != Screen.PLAYER) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { vm.crashNoticeSeen = true },
                containerColor = C.Surface,
                title = { Text("Avid Viewer closed unexpectedly", color = C.Text, fontWeight = FontWeight.Bold) },
                text = { Label("Sorry about that. The details were saved on this device in Settings › Updates & about, so the problem can be found and fixed. Nothing was sent anywhere.", size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE) },
                confirmButton = { TvButton("OK", primary = true) { vm.crashNoticeSeen = true } },
                dismissButton = { TvButton("See details") { vm.crashNoticeSeen = true; vm.navSelect(Screen.SETTINGS); vm.settingsSection = "about" } },
            )
        }
        // New version found: ask before updating (never interrupts full-screen viewing).
        val rel = vm.update
        // Opens by itself only when found before the first button press (see updatePromptAllowed).
        if (rel != null && vm.updatePromptAllowed && !vm.updatePromptDismissed && vm.screen != Screen.PLAYER) {
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
                    TvButton(if (vm.updateBusy) "Updating…" else "Update now", primary = true, busy = vm.updateBusy) { if (!vm.updateBusy) vm.installUpdate() }
                },
                dismissButton = {
                    TvButton("Later") { vm.updatePromptDismissed = true }
                },
            )
        }
        vm.notice?.let { text ->
            Box(Modifier.fillMaxSize().padding(bottom = 36.dp), contentAlignment = Alignment.BottomCenter) {
                Label(text, size = 15.sp, color = C.Text, weight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(C.Surface2).padding(horizontal = 22.dp, vertical = 10.dp))
            }
        }
        // Back walks up the menu levels; at the full menu on the TV Guide it asks for a second press, then exits.
        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
        BackHandler(enabled = vm.screen != Screen.PLAYER) { if (!vm.navBack()) activity?.finish() }
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
fun VideoSurface(vm: AppViewModel, modifier: Modifier = Modifier, fit: Boolean = true, fullScreen: Boolean = false) {
    // Full screen: the viewer's screen format and captions. The preview window just fits (or fills).
    val format = if (fullScreen) vm.screenFormat() else null
    val style = vm.prefs.captionStyle
    val ratio = format?.ratio
    androidx.compose.foundation.layout.Box(modifier.background(Color.Black), contentAlignment = androidx.compose.ui.Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    player = vm.player
                }
            },
            update = { v ->
                if (v.player !== vm.player) v.player = vm.player
                v.resizeMode = when (format) {
                    null -> if (fit) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    com.avidviewer.tv.data.ScreenFormat.AUTO -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    com.avidviewer.tv.data.ScreenFormat.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FILL // stretch, or fill a forced 16:9 / 4:3 frame
                }
                v.subtitleView?.apply {
                    visibility = if (fullScreen) android.view.View.VISIBLE else android.view.View.GONE
                    setApplyEmbeddedStyles(false)
                    setApplyEmbeddedFontSizes(false)
                    setFractionalTextSize(style.fraction)
                    setBottomPaddingFraction(style.bottomPadding)
                    setStyle(captionStyleOf(style))
                }
            },
            onRelease = { it.player = null },
            modifier = if (ratio != null) Modifier.fillMaxHeight().aspectRatio(ratio, matchHeightConstraintsFirst = true) else Modifier.fillMaxSize(),
        )
    }
}

/** The caption look for Media3's subtitle view. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun captionStyleOf(s: com.avidviewer.tv.data.CaptionStyle): androidx.media3.ui.CaptionStyleCompat {
    val white = android.graphics.Color.WHITE
    val clear = android.graphics.Color.TRANSPARENT
    return when (s.background) {
        "none" -> androidx.media3.ui.CaptionStyleCompat(white, clear, clear, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null)
        "solid" -> androidx.media3.ui.CaptionStyleCompat(white, android.graphics.Color.BLACK, clear, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE, clear, null)
        else -> androidx.media3.ui.CaptionStyleCompat(white, 0xBF000000.toInt(), clear, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE, clear, null)
    }
}

/** On-screen instructions while a category or channel is being re-ordered. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ReorderHelp(vm: AppViewModel) {
    val what = vm.movingGroup?.let { "category “${vm.groupName(it)}”" }
        ?: vm.movingChannel?.let { k -> vm.channel(k)?.let { "channel “${vm.channelName(it)}”" } }
        ?: vm.vodMovingCat?.let { (k, c) -> "category “${vm.vodCatName(k, c)}”" }
        ?: return
    // Sits over the preview window and title area at the top, so the list being re-ordered stays in view.
    androidx.compose.foundation.layout.Column(
        Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(top = 14.dp, end = 16.dp)
            .widthIn(max = 560.dp).heightIn(max = 132.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(C.Surface2)
            .border(1.dp, C.AccentFocus, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
    ) {
        Text("Re-ordering the $what", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text("▲ ▼  Move (hold = faster)   ◀  To the top   ▶  To the bottom", color = C.Soft, fontSize = 12.sp)
        Text("OK or Back  Done — the new order is saved", color = C.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
