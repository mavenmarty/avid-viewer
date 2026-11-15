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
import androidx.compose.runtime.remember
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
 * no channels, so it says so, offers Add a playlist (highlighted) and Restore a backup, and shows the user guide
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
                "It doesn't include any channels. To start, add your provider's playlist: an Xtream Codes login (server, username, password) or an M3U link.",
                size = 16.sp, color = C.Soft, lines = Int.MAX_VALUE,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("＋  Add a playlist", primary = true, modifier = Modifier.focusRequester(add).testTag("welcome-add")) { vm.welcomeAddPlaylist() }
                TvButton("Restore a backup", modifier = Modifier.testTag("welcome-restore")) { vm.welcomeRestore() }
            }
            Label("Moving from another TV? Restore a backup made in Settings › Updates & about.", size = 13.sp, color = C.Muted, lines = 2)
        }
        Column(
            Modifier.width(300.dp).clip(RoundedCornerShape(16.dp)).background(C.Surface).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QrCode(com.avidviewer.tv.data.Links.USER_GUIDE, Modifier.size(200.dp).testTag("welcome-guide-qr"))
            Label("User guide", size = 18.sp, color = C.Text, weight = FontWeight.Bold)
            Label(
                "Scan with your phone's camera for step-by-step setup, every screen explained, and optional extras such as curated playlists and Discover.",
                size = 13.sp, color = C.Soft, lines = Int.MAX_VALUE,
            )
        }
    }
}
