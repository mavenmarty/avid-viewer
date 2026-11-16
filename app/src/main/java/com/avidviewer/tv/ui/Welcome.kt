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
 * no channels, so it says so, offers a setup code (highlighted), Add a playlist and Sign in to your account (link the
 * TV and restore a backup from the account, 1.12.0), and shows the user guide
 * as a QR code for a phone.
 */
@Composable
fun WelcomeScreen(vm: AppViewModel) {
    val add = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusWhenReady(vm) { runCatching { add.requestFocus() }.isSuccess } }
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
                "It doesn't include any channels: add your provider's playlist, an Xtream Codes login (server, username, password) or an M3U link.",
                size = 16.sp, color = C.Soft, lines = Int.MAX_VALUE,
            )
            Label(
                "Already use Avid Viewer on another device? Link this one to your account with the code on the right, then restore that device's backup. No typing logins with the remote.",
                size = 16.sp, color = C.Soft, lines = Int.MAX_VALUE,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("＋  Add a playlist", primary = true, modifier = Modifier.focusRequester(add).testTag("welcome-add")) { vm.welcomeAddPlaylist() }
                TvButton("Enter a setup code", modifier = Modifier.testTag("welcome-code")) { vm.openSetupCode() }
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
                Label(com.avidviewer.tv.data.SetupCode.pretty(code.code), size = 34.sp, color = C.Accent, weight = FontWeight.Bold, modifier = Modifier.testTag("welcome-link-code"))
                Label("No account yet? You can make one there.", size = 12.sp, color = C.Muted, lines = 2)
            }
            else -> Label(vm.accountStatus ?: "Getting a code…", size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE)
        }
    }
}

/**
 * Enter a setup code from avidviewer.app/setup (1.11.0). The keyboard opens at once; Done fetches the code's
 * playlists (or backup). Shown over the welcome screen, or from Settings › Playlists.
 */
@Composable
fun SetupCodeDialog(vm: AppViewModel) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.closeSetupCode() },
        containerColor = C.Surface,
        title = { androidx.compose.material3.Text("Enter your setup code", color = C.Text) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                QrCode(com.avidviewer.tv.data.SetupCode.PAGE, Modifier.size(130.dp))
                Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label("On your phone or computer, open avidviewer.app/setup (or scan the code), enter your playlist or upload a backup, and type the code it shows here.",
                        size = 14.sp, color = C.Soft, lines = Int.MAX_VALUE)
                    TvField("Setup code (8 letters and numbers)", text, modifier = Modifier.testTag("setup-code-field"), autoOpen = true,
                        next = { vm.redeemSetupCode(text) }) { text = it.uppercase().take(12) }
                    vm.setupStatus?.let { Label(it, size = 13.sp, color = C.Text, lines = Int.MAX_VALUE, modifier = Modifier.testTag("setup-status")) }
                }
            }
        },
        confirmButton = { TvButton(if (vm.setupBusy) "Setting up…" else "Set up", primary = true, busy = vm.setupBusy, modifier = Modifier.testTag("setup-go")) { vm.redeemSetupCode(text) } },
        dismissButton = { TvButton("Cancel") { vm.closeSetupCode() } },
    )
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
                        if (list != null && list.isEmpty()) Label("Add a playlist or enter a setup code to get started; this device will back up to your account once you choose a backup PIN.", size = 13.sp, color = C.Muted, lines = Int.MAX_VALUE)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TvButton(if (vm.accountEmail != null) "Close" else "Cancel", modifier = Modifier.testTag("welcome-account-close")) { vm.closeWelcomeAccount() } },
    )
    if (vm.pendingRestore != null) RestorePinDialog(vm)
}
