package com.avidviewer.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
 * The first screen of a new install (1.6.0): shown at start while there are no playlists. Avid Viewer comes with
 * no channels, so it says so. From 1.16.1 a device must be linked to an account before it gets playlists (licensing):
 * the welcome screen has no Add a playlist; it shows this device's link code, and once linked, where to add
 * playlists and Restore a backup.
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
                    "Add your playlists at avidviewer.app/playlists and give them to a profile on this device. They arrive here within a minute while Avid Viewer is open. Or restore another device's backup.",
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
 * replaces an expired one; once it's approved, the account's backups are offered.
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
                TvButton("Restore a backup", modifier = Modifier.testTag("welcome-signin")) { vm.welcomeSignIn() }
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


/** Welcome › Sign in to your account: the link code, then the account's backups to restore (1.12.0). */
@Composable
fun WelcomeAccountDialog(vm: AppViewModel) {
    val fmt = remember { java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.getDefault()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.closeWelcomeAccount() },
        containerColor = C.Surface,
        modifier = Modifier.testTag("welcome-account"),
        title = { androidx.compose.material3.Text(if (vm.accountEmail == null) "Sign in to your account" else "Linked to ${vm.accountEmail}", color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val code = vm.linkCode
                when {
                    vm.accountEmail == null && code != null -> LinkCodePanel(vm, code)
                    vm.accountEmail == null -> Label(vm.accountStatus ?: "Getting a code…", size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE)
                    else -> {
                        val list = vm.cloudBackups
                        if (!list.isNullOrEmpty()) {
                            Label("Choose a backup to restore on this device:", size = 14.sp, color = C.Soft)
                            list.take(8).forEach { b ->
                                val who = when { b.thisTv -> "This device"; b.removed -> "${b.name} (removed)"; else -> b.name }
                                val pls = b.playlists?.let { n -> "  ·  $n playlist" + if (n == 1) "" else "s" } ?: ""
                                TvButton("$who  ·  ${fmt.format(b.createdAt)}$pls", modifier = Modifier.testTag("cloud-backup")) { vm.chooseCloudBackup(b) }
                            }
                        }
                        vm.backupStatus?.let { Label(it, size = 13.sp, color = C.Soft, lines = Int.MAX_VALUE) }
                        if (list != null && list.isEmpty()) Label("No backups yet. Add playlists at avidviewer.app/playlists to get started; this device will back up to your account once you choose a backup PIN.", size = 13.sp, color = C.Muted, lines = Int.MAX_VALUE)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TvButton(if (vm.accountEmail != null) "Close" else "Cancel", modifier = Modifier.testTag("welcome-account-close")) { vm.closeWelcomeAccount() } },
    )
    if (vm.pendingRestore != null) RestorePinDialog(vm)
}
