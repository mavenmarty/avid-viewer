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
import androidx.compose.ui.platform.testTag
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
    // Which screen was on, in case the app is closed (CrashLog); only the screen's name.
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    androidx.compose.runtime.LaunchedEffect(vm.screen) { com.avidviewer.tv.data.CrashLog.note(appCtx, "Screen: ${vm.screen.name.lowercase()}") }
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
            // The item nearest to the one that disappeared first; else the first item on screen.
            if (!vm.anyFocus && vm.screen != Screen.PLAYER && FocusSpots.lostScreen == vm.screen && FocusSpots.recover()) return@LaunchedEffect
            for (frame in 0 until 10) {
                androidx.compose.runtime.withFrameNanos { }
                if (vm.anyFocus) break
                if (!rescueFm.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)) rescueFm.moveFocus(androidx.compose.ui.focus.FocusDirection.Next)
            }
        }
        // The highlighted item disappeared (hidden, deleted, moved away) and nothing named a new place for it
        // within a moment: the item nearest to where it was takes it (BUG-036). Before, the highlight went to
        // the first item on screen (the main menu or ‹ Menu). Not while a dialog has the remote, after a screen
        // change, or once a button has been pressed since.
        FocusSpots.screenNow = vm.screen
        FocusSpots.log = vm::trail
        val rootView = androidx.compose.ui.platform.LocalView.current.rootView
        if (FocusSpots.mainRoot?.get() !== rootView) { FocusSpots.reset(); FocusSpots.mainRoot = java.lang.ref.WeakReference(rootView) }
        FocusSpots.onLost = onLost@{ lostAt ->
            val screen = vm.screen
            if (FocusSpots.lostAt != lostAt || vm.lastKeyAt > lostAt || screen == Screen.PLAYER || FocusSpots.lostScreen != screen) {
                vm.trail("skip key=${vm.lastKeyAt > lostAt} screen=$screen/${FocusSpots.lostScreen}"); return@onLost
            }
            // A text field being typed in keeps it.
            if (FocusSpots.typing) return@onLost
            val ok = FocusSpots.recover(somethingFocused = vm.anyFocus)
            vm.trail("recover $ok")
            if (ok) com.avidviewer.tv.data.CrashLog.note(appCtx, "Highlight lost on ${screen.name.lowercase()}; given to the nearest item")
        }
        if (vm.choosingProfile) {
            WhoIsWatching(vm)
        } else if (vm.splashProfile != null && !vm.showWelcome) {
            KidsSplash(vm)
        } else if (vm.showWelcome) {
            WelcomeScreen(vm)
        } else if (vm.screen == Screen.PLAYER) {
            PlayerScreen(vm)
        } else {
            Row(Modifier.fillMaxSize()) {
                val browsing = vm.screen == Screen.LIVE || vm.screen == Screen.GUIDE
                // Inside the TV Guide with a category chosen, the guide takes the whole screen: the main menu and
                // the category list step aside. ◀ from the channel names, or Back, brings back the category list
                // (on the current category) with the main menu as icons (both set navFocusKey).
                val guideFull = vm.guideFullLayout
                // The main menu is otherwise always there: icons only while a lower layer is in use.
                if (!guideFull) NavRail(vm)
                if (browsing && vm.navMode == NavMode.COLLAPSED) PlaylistTree(vm)
                if (browsing && vm.navMode == NavMode.GROUPS && !guideFull) GroupNav(vm)
                // Whether the guide area had the highlight since it appeared (its first "no highlight" report, before the
                // highlight arrives, isn't a loss).
                val guideHad = remember { booleanArrayOf(false) }
                Box(Modifier.fillMaxHeight().weight(1f).onFocusChanged {
                    // Into the guide from the category list (a category chosen, ▶): the full layout at once, even
                    // if a request to highlight a category was still pending. Losing the highlight (to full screen,
                    // or a channel's dialogs) changes nothing, so coming back doesn't redraw (R-1); anywhere else, the
                    // menus come back as before.
                    if (browsing && it.hasFocus) { if (!vm.guideHasFocus) vm.navFocusKey = null; vm.guideHasFocus = true }
                    else if (!it.hasFocus && guideHad[0] && !vm.guideAwayBriefly()) vm.guideHasFocus = false
                    guideHad[0] = it.hasFocus
                }) {
                    when (vm.screen) {
                        Screen.SEARCH -> SearchScreen(vm)
                        Screen.CONTINUE -> ContinueScreen(vm)
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
        ProfileSwitcherDialog(vm)
        PinDialog(vm)
        StreamStoppedDialog(vm)
        if (vm.linkReminder && !vm.showWelcome && !vm.choosingProfile && vm.screen != Screen.PLAYER) LinkReminderDialog(vm)
        ChannelOptionsDialog(vm)
        GroupOptionsDialog(vm)
        ListPickerDialog(vm)
        MatchGuideDialog(vm)
        ReorderHelp(vm)
        ListOptionsDialog(vm)
        VodCategoryOptionsDialog(vm)
        // The app closed unexpectedly last time: say so once, and offer to send the report to avidviewer.app
        // (Report issue sends it and clears it; Cancel keeps it in Settings › Updates & about).
        if ((vm.lastCrash != null || vm.crashReportSent) && !vm.crashNoticeSeen && vm.screen != Screen.PLAYER) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { if (!vm.crashReportBusy) vm.closeCrashNotice() },
                containerColor = C.Surface,
                modifier = Modifier.testTag("crash-dialog"),
                title = { Text("Avid Viewer closed unexpectedly", color = C.Text, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!vm.crashReportSent) Label(
                            "Sorry about that. The details were saved on this device. Report issue sends them to Avid Viewer so the problem can be found and fixed: the app version, the TV model and the error, with no playlists, addresses or logins.",
                            size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE,
                        )
                        vm.crashReportStatus?.let { Label(it, size = 13.sp, color = if (vm.crashReportSent) C.Text else C.Accent, lines = 3, modifier = Modifier.testTag("crash-report-status")) }
                    }
                },
                confirmButton = {
                    if (vm.crashReportSent) TvButton("OK", primary = true) { vm.closeCrashNotice() }
                    else TvButton(if (vm.crashReportBusy) "Sending…" else "Report issue", primary = true, busy = vm.crashReportBusy, modifier = Modifier.testTag("crash-report")) { vm.reportCrash() }
                },
                dismissButton = {
                    if (!vm.crashReportSent) TvButton("Cancel", modifier = Modifier.testTag("crash-cancel")) { if (!vm.crashReportBusy) vm.closeCrashNotice() }
                },
            )
        }
        // A report sent from a device that isn't linked can't be followed on avidviewer.app: say so, and offer to link it.
        if (vm.reportUnlinkedNotice && !((vm.lastCrash != null || vm.crashReportSent) && !vm.crashNoticeSeen) && vm.screen != Screen.PLAYER) ReportUnlinkedDialog(vm)
        // 1.22.0: a device that isn't linked can't be used until it is (covers everything, the player included).
        if (vm.linkRequired) LinkRequiredScreen(vm)
        // Nov 22, 2026: rating titles on TMDB (link the profile's TMDB account, then rate).
        if (vm.tmdbLinkUrl != null || vm.tmdbLinkError != null) TmdbLinkDialog(vm)
        vm.rateTarget?.let { RateDialog(vm, it) }
        // Nov 21, 2026: over everything for a moment at start, while this TV's channels and guide are read.
        if (vm.startSplash) StartSplash()
        // Nov 21, 2026: a playlist's subscription ends within 30, 14 or 2 days (each once), or has ended.
        if (!vm.linkRequired && !vm.showWelcome && !vm.choosingProfile) {
            vm.fixCheck?.let { FixCheckDialog(vm, it) }
            vm.expiredNotice?.let { pid -> ExpiryDialog(vm, pid, expired = true) }
            if (vm.expiredNotice == null && vm.screen != Screen.PLAYER) vm.expiryReminder?.let { (pid, _) -> ExpiryDialog(vm, pid, expired = false) }
        }
        // New version found: ask before updating (never interrupts full-screen viewing).
        val rel = vm.update
        // Opens by itself only once the latest release has been out 24 hours (see updatePromptAllowed).
        if (rel != null && vm.updatePromptAllowed && !vm.updatePromptDismissed && vm.screen != Screen.PLAYER) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { if (!vm.updateBusy) vm.remindAboutUpdateLater() },
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
                    TvButton("Later") { vm.remindAboutUpdateLater() }
                },
            )
        }
        // #39: no Menu button on this remote (Google TV): say how to open options, until it's been used a few times.
        if (MenuKey.showTip && vm.notice == null && !vm.showWelcome && !vm.choosingProfile &&
            vm.screen in setOf(Screen.CONTINUE, Screen.GUIDE, Screen.LIVE, Screen.MOVIES, Screen.SERIES, Screen.FAVORITES)) {
            Box(Modifier.fillMaxSize().padding(bottom = 18.dp, end = 24.dp), contentAlignment = Alignment.BottomEnd) {
                Label("Tip: hold OK on a channel or title for its options", size = 13.sp, color = C.Text,
                    modifier = Modifier.testTag("hold-ok-tip").clip(RoundedCornerShape(16.dp)).background(C.Surface2).padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        vm.notice?.let { text ->
            Box(Modifier.fillMaxSize().padding(bottom = 36.dp), contentAlignment = Alignment.BottomCenter) {
                Label(text, size = 15.sp, color = C.Text, weight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(C.Surface2).padding(horizontal = 22.dp, vertical = 10.dp))
            }
        }
        // Back walks up the menu levels; at the full menu on the TV Guide it asks for a second press, then exits.
        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
        BackHandler(enabled = vm.screen != Screen.PLAYER && !vm.choosingProfile) { if (!vm.navBack()) activity?.finish() }
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

/**
 * Nov 28, 2026: "Is it fixed?" for a problem this device's person reported, now fixed in the version it has. One press
 * answers; Back is Ask me later.
 */
@Composable
private fun FixCheckDialog(vm: AppViewModel, c: com.avidviewer.tv.data.AccountApi.FixCheck) {
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.answerFix("later") },
        containerColor = C.Surface,
        modifier = Modifier.testTag("fix-check-dialog"),
        title = { Text("Is it fixed?", color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("You reported: \u201c${c.text.trim().let { if (it.length > 160) it.take(157).trimEnd() + "\u2026" else it }}\u201d", size = 14.sp, color = C.Soft, lines = 5)
                Label("A fix is in Avid Viewer ${c.fixedIn}, which this TV has now. Is it working for you?", size = 14.sp, color = C.Text, lines = 3)
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Yes, it's fixed", primary = true, modifier = Modifier.focusRequester(first).testTag("fix-yes")) { vm.answerFix("fixed") }
                TvButton("It's still happening", modifier = Modifier.testTag("fix-still")) { vm.answerFix("still") }
                TvButton("Ask me later", modifier = Modifier.testTag("fix-later")) { vm.answerFix("later") }
            }
        },
    )
    LaunchedEffect(c.id) { runCatching { first.requestFocus() } }
}

/** "Service expires soon" / "Service has expired": contact the provider to renew. Dismissed with OK or Back. */
@Composable
private fun ExpiryDialog(vm: AppViewModel, pid: String, expired: Boolean) {
    val p = vm.playlists.firstOrNull { it.id == pid } ?: return
    val at = vm.expiries[pid] ?: return
    val day = remember(at) { java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(at)) }
    val days = Math.ceil((at - System.currentTimeMillis()) / 86_400_000.0).toInt().coerceAtLeast(1)
    val close = { if (expired) vm.expiredNotice = null else vm.dismissExpiryReminder() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = close,
        containerColor = C.Surface,
        modifier = Modifier.testTag(if (expired) "expired-dialog" else "expiry-dialog"),
        title = { Text(if (expired) "Service has expired" else "Service expires soon", color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(
                    if (expired) "Your subscription for \u201c${p.name}\u201d ended on $day, so its channels, movies and series can't play."
                    else "Your subscription for \u201c${p.name}\u201d ends on $day, in $days day${if (days == 1) "" else "s"}.",
                    size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE,
                )
                Label("Contact your service provider to renew it.", size = 14.sp, color = C.Text, lines = 2)
            }
        },
        confirmButton = { TvButton("OK", primary = true, modifier = Modifier.testTag("expiry-ok")) { close() } },
    )
}

/** The start-up screen (Nov 21, 2026): the logo while the channels and guide saved on this TV are read. */
@Composable
fun StartSplash() {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(C.Bg).testTag("start-splash"), contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.avidviewer.tv.R.drawable.logo), contentDescription = null,
                modifier = Modifier.size(84.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)))
            Text("Avid Viewer", color = C.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Getting your channels ready…", color = C.Soft, fontSize = 15.sp)
        }
    }
}

/** Linking the profile's TMDB account (Nov 22, 2026): approve this TV on themoviedb.org by scanning the code. */
@Composable
private fun TmdbLinkDialog(vm: AppViewModel) {
    val url = vm.tmdbLinkUrl
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.cancelTmdbLink() },
        containerColor = C.Surface,
        modifier = Modifier.testTag("tmdb-link-dialog"),
        title = { Text("Link your TMDB account", color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                vm.tmdbLinkError?.let { Label(it, size = 14.sp, color = C.Text, lines = 3) }
                if (url != null) {
                    Label("Scan with your phone and choose Approve on themoviedb.org (sign in to TMDB first). Ratings from ${vm.profile.name} then go to that TMDB account.", size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE)
                    QrCode(url, Modifier.size(180.dp))
                    Label(url, size = 12.sp, color = C.Soft, lines = 2)
                    Label("Waiting for you to approve…", size = 13.sp, color = C.Text)
                } else if (vm.tmdbLinkError == null) Label("Asking TMDB…", size = 13.sp)
            }
        },
        confirmButton = { TvButton(if (url != null) "Cancel" else "Close", primary = true) { vm.cancelTmdbLink() } },
    )
}

/** Rating a title on TMDB: 1–10 (Left/Right along the row), or removing a rating. */
@Composable
private fun RateDialog(vm: AppViewModel, t: AppViewModel.RateTarget) {
    val mine = vm.myRatingOf(t)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.closeRate() },
        containerColor = C.Surface,
        modifier = Modifier.testTag("rate-dialog"),
        title = { Text("Rate ${t.name}", color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                Label("On TMDB, as ${vm.prefs.tmdbUser.ifBlank { "your account" }}." + (mine?.let { " Your rating now: $it." } ?: ""), size = 13.sp, color = C.Soft, lines = 2)
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    val first = remember { androidx.compose.ui.focus.FocusRequester() }
                    (1..10).forEach { n ->
                        TvButton("$n", selected = mine?.let { Math.round(it).toInt() == n } == true,
                            modifier = (if (n == (mine?.let { Math.round(it).toInt() } ?: 7)) Modifier.focusRequester(first) else Modifier).testTag("rate-$n")) { vm.submitRating(n.toDouble()) }
                    }
                    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                }
                vm.rateStatus?.let { Label(it, size = 13.sp, color = C.Text, lines = 2) }
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                if (mine != null) TvButton("Remove my rating") { vm.submitRating(null) }
                TvButton("Cancel") { vm.closeRate() }
            }
        },
    )
}
