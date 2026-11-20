package com.avidviewer.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avidviewer.tv.R

/**
 * The first screen of a new install: shown at start while there are no playlists. Avid Viewer comes with no channels,
 * and a device must be linked to an account before it gets playlists (licensing), so it shows this device's link code
 * and, once linked, where to add playlists. Restoring a backup is in Settings › Updates & about.
 */
@Composable
fun WelcomeScreen(vm: AppViewModel) {
    Row(
        Modifier.fillMaxSize().background(C.Bg).padding(horizontal = 56.dp, vertical = 40.dp).testTag("welcome"),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
                Label("Welcome to Avid Viewer", size = 30.sp, color = C.Text, weight = FontWeight.Bold)
            }
            Label(
                "Avid Viewer plays live TV, movies and series from your own TV provider, with a TV guide, sports, and search across everything.",
                size = 16.sp, color = C.Soft, lines = Int.MAX_VALUE,
            )
            Label(
                "It doesn't include any channels: you add your provider's playlist (an Xtream Codes login or an M3U link) in your Avid Viewer account.",
                size = 16.sp, color = C.Soft, lines = Int.MAX_VALUE,
            )
            if (vm.accountEmail == null) {
                Label("To get started, link this device to your account", size = 20.sp, color = C.Text, weight = FontWeight.Bold, lines = 2, modifier = Modifier.testTag("welcome-steps"))
                Label(
                    "1. Scan the code on the right with your phone, or go to avidviewer.app/link and enter it.\n2. Sign in or make an account, and choose Add this device.\n3. Add your playlists at avidviewer.app/playlists. They arrive here within a minute, with no logins typed on the remote.",
                    size = 16.sp, color = C.Soft, lines = Int.MAX_VALUE,
                )
            } else {
                Label("This device is linked", size = 20.sp, color = C.Text, weight = FontWeight.Bold, lines = 2, modifier = Modifier.testTag("welcome-steps"))
                Label(
                    "Add your playlists at avidviewer.app/playlists and give them to a profile on this device. They arrive here within a minute while Avid Viewer is open.",
                    size = 16.sp, color = C.Soft, lines = Int.MAX_VALUE,
                )
            }
        }
        WelcomeLinkPanel(vm)
    }
}

/**
 * The welcome screen's right side (1.12.1): this device's own link code, made by avidviewer.app for this device,
 * with a QR code that opens avidviewer.app/link with the code filled in (like signing in to a TV app). A new code
 * replaces an expired one.
 */
@Composable
private fun WelcomeLinkPanel(vm: AppViewModel) {
    LaunchedEffect(vm.accountEmail) { if (vm.accountEmail == null && vm.linkCode == null) vm.startLinking() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (vm.accountEmail == null) vm.cancelLinking() } }
    Column(
        Modifier.width(340.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface).padding(22.dp).testTag("welcome-link"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val code = vm.linkCode
        when {
            vm.accountEmail != null -> {
                Label("Linked to", size = 14.sp, color = C.Soft)
                Label(vm.accountEmail.orEmpty(), size = 18.sp, color = C.Text, weight = FontWeight.Bold, lines = 2)
                Label("Next: add your playlists at avidviewer.app/playlists.", size = 13.sp, color = C.Soft, lines = 3, modifier = Modifier.testTag("welcome-next"))
            }
            code != null -> {
                Label("Link to your account", size = 18.sp, color = C.Text, weight = FontWeight.Bold)
                QrCode(code.qrUrl, Modifier.size(190.dp).testTag("welcome-link-qr"))
                Label("Scan with your phone, or go to", size = 13.sp, color = C.Soft)
                Label("avidviewer.app/link", size = 18.sp, color = C.Text, weight = FontWeight.Bold)
                Label("and enter", size = 13.sp, color = C.Soft)
                Label(com.avidviewer.tv.data.AccountApi.pretty(code.code), size = 34.sp, color = C.Accent, weight = FontWeight.Bold, modifier = Modifier.testTag("welcome-link-code"))
                Label("No account yet? You can make one there.", size = 12.sp, color = C.Muted, lines = 2)
            }
            else -> Label(vm.accountStatus ?: "Getting a code…", size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE)
        }
    }
}


/**
 * 1.16.1: a device with playlists that isn't linked to an account. Its playlists keep working; this asks to link it
 * (licensing), with its code and QR code. Not now puts it off for 7 days.
 */
@Composable
fun LinkReminderDialog(vm: AppViewModel) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.remindLinkLater() },
        containerColor = C.Surface,
        modifier = Modifier.testTag("link-reminder"),
        title = { androidx.compose.material3.Text("Link this device to your account", color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("Avid Viewer devices are tied to an account. Your playlists keep working; once linked, you manage them, profiles and backups at avidviewer.app.", size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE)
                val code = vm.linkCode
                if (code != null) LinkCodePanel(vm, code)
                else Label(vm.accountStatus ?: "Getting a code…", size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE)
            }
        },
        confirmButton = {},
        dismissButton = { TvButton("Not now", modifier = Modifier.testTag("link-reminder-later")) { vm.remindLinkLater() } },
    )
}

/**
 * After a crash report or playback log is sent from a device that isn't linked (owner, Nov 20): it can't be followed on
 * avidviewer.app › Reports until the device is linked. Shows the link code and QR code; linking claims the report.
 */
@Composable
fun ReportUnlinkedDialog(vm: AppViewModel) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.closeReportUnlinkedNotice() },
        containerColor = C.Surface,
        modifier = Modifier.testTag("report-unlinked"),
        title = { androidx.compose.material3.Text("Report sent", color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("Thanks. This device isn't linked to an Avid Viewer account, so you can't follow what happens to this report.", size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE)
                Label("Link it, and this report and any you send later show at avidviewer.app › Reports.", size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE)
                val code = vm.linkCode
                if (code != null) LinkCodePanel(vm, code)
                else Label(vm.accountStatus ?: "Getting a code…", size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE)
            }
        },
        confirmButton = {},
        dismissButton = { TvButton("Not now", modifier = Modifier.testTag("report-unlinked-later")) { vm.closeReportUnlinkedNotice() } },
    )
}

/**
 * 1.22.0 (owner, Nov 20): a device that isn't linked can't be used until it is. Covers the whole app; no Not now.
 * Back (or Exit) leaves the app. Disappears by itself once the code is approved on avidviewer.app/link.
 */
@Composable
fun LinkRequiredScreen(vm: AppViewModel) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    LaunchedEffect(Unit) { vm.linkRequiredShown() }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { activity?.finish() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        val exit = remember { FocusRequester() }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(C.Bg).testTag("link-required"), contentAlignment = Alignment.Center) {
            Row(Modifier.padding(horizontal = 72.dp), horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Label("Link this device to keep watching", size = 40.sp, color = C.Text, weight = FontWeight.Bold, lines = 3)
                    Label("Avid Viewer now works with an avidviewer.app account. Link this device once: your playlists, favorites and settings on it stay as they are, and you manage them, profiles and backups at avidviewer.app.", size = 18.sp, color = C.Soft, lines = Int.MAX_VALUE)
                    Label("1. On your phone or computer, scan the QR code, or go to avidviewer.app/link.", size = 18.sp, color = C.Text, lines = 3)
                    Label("2. Sign in (or make a free account) and approve this device.", size = 18.sp, color = C.Text, lines = 3)
                    Label("This screen closes by itself once it's linked.", size = 15.sp, color = C.Muted, lines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (vm.linkCode == null && vm.accountStatus != null && vm.accountStatus != "Getting a code…")
                            TvButton("Try again", primary = true, modifier = Modifier.testTag("link-required-retry")) { vm.startLinking() }
                        TvButton("Exit Avid Viewer", modifier = Modifier.focusRequester(exit).testTag("link-required-exit")) { activity?.finish() }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val code = vm.linkCode
                    if (code != null) {
                        QrCode(code.qrUrl, Modifier.size(300.dp).testTag("link-required-qr"))
                        Label(com.avidviewer.tv.data.AccountApi.pretty(code.code), size = 56.sp, color = C.Accent, weight = FontWeight.Bold, modifier = Modifier.testTag("link-required-code"))
                        val mins = ((code.expiresAt - vm.now) / 60_000L + 1).coerceAtLeast(1)
                        Label("New code in $mins min", size = 14.sp, color = C.Muted)
                    } else {
                        Box(Modifier.size(300.dp).clip(RoundedCornerShape(12.dp)).background(C.Surface), contentAlignment = Alignment.Center) {
                            Label(vm.accountStatus ?: "Getting a code…", size = 16.sp, color = C.Soft, lines = 6, modifier = Modifier.padding(24.dp))
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { exit.requestFocus() } }
    }
}
