package com.avidviewer.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Full-screen playback. Remote:
 *  Up / Channel Up     -> previous channel in the current group (or Favorites), wrapping to the bottom
 *  Down / Channel Down -> next channel, wrapping to the top
 *  OK                  -> show the info bar
 *  Menu                -> add / remove favorite
 *  Back                -> return to the screen you came from
 */
@Composable
fun PlayerScreen(vm: AppViewModel) {
    val focus = remember { FocusRequester() }
    var showInfo by remember { mutableStateOf(true) }
    var infoTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(infoTick) {
        showInfo = true
        delay(5000)
        showInfo = false
    }
    BackHandler { vm.closeFullScreen() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (it.key) {
                    Key.DirectionUp, Key.ChannelUp -> { vm.step(-1); infoTick++; true }
                    Key.DirectionDown, Key.ChannelDown -> { vm.step(1); infoTick++; true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.DirectionLeft, Key.DirectionRight, Key.Info -> { infoTick++; true }
                    Key.Menu -> { vm.toggleFavorite(vm.currentKey); infoTick++; true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        VideoSurface(vm, Modifier.fillMaxSize())

        vm.playerError?.let {
            Text(it, color = C.Soft, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center).padding(40.dp))
        }

        vm.osd?.let {
            Text(
                it, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
                    .clip(RoundedCornerShape(20.dp)).background(Color(0xF21E2632)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (showInfo) InfoBar(vm, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun InfoBar(vm: AppViewModel, modifier: Modifier) {
    val c = vm.channel(vm.currentKey) ?: return
    val (now, next) = vm.nowNext(c)
    Row(
        modifier.fillMaxWidth().background(Color(0xF510151C)).padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ChannelBadge(vm.channelName(c), c.logo, 52.dp)
        Column(Modifier.weight(1f)) {
            Row {
                Label("${vm.numbers()[c.key] ?: ""} · ${vm.channelName(c)}" + if (vm.isFavorite(c.key)) "  ★" else "", size = 13.sp)
                Label("  ·  ${vm.contextLabel()}", size = 13.sp, color = C.Accent)
            }
            Label(now?.title ?: "No guide information", size = 20.sp, color = C.Text, weight = FontWeight.Bold)
            if (now != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("${vm.fmtTime(now.start)} – ${vm.fmtTime(now.end)}", size = 12.sp)
                    Spacer(Modifier.width(10.dp))
                    ProgressBar(now, vm.now, Modifier.weight(1f))
                }
            }
        }
        if (next != null) {
            Column(Modifier.width(200.dp)) {
                SectionTitle("Up next")
                Label(next.title, size = 14.sp, color = C.Text, weight = FontWeight.SemiBold)
                Label(vm.fmtTime(next.start), size = 12.sp)
            }
        }
        if (vm.prefs.showClock) {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(110.dp)) {
                Text(vm.fmtTime(vm.now), color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Label(vm.fmtDate(vm.now), size = 12.sp)
            }
        }
    }
}
