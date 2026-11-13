package com.avidviewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avidviewer.tv.data.Av
import com.avidviewer.tv.data.AvTrack
import com.avidviewer.tv.data.CaptionStyle
import com.avidviewer.tv.data.ScreenFormat

/**
 * Full screen › Menu: picture & sound for what's playing. Left/Right (or OK) change a row's value;
 * changes are saved for this channel (or title) or for all channels, as "Save changes for" says.
 * Caption style is the same everywhere.
 */
@Composable
fun BoxScope.AvPanel(vm: AppViewModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusWhenReady(vm, frames = 30) { runCatching { first.requestFocus() }.isSuccess } }
    val live = vm.vodPlaying == null
    val key = vm.avKey()
    val choice = vm.avFor(key)
    val style = vm.prefs.captionStyle
    val what = vm.vodPlaying?.let { if (it.kind == "episode") "this series" else "this movie" } ?: "this channel"
    Column(
        Modifier.align(Alignment.CenterEnd).width(430.dp).fillMaxHeight()
            .background(Color(0xF2201B18)).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Label("Picture & sound", size = 20.sp, color = C.Text, weight = FontWeight.Bold)
        val name = vm.vodPlaying?.name ?: vm.channel(vm.currentKey)?.let { vm.channelName(it) } ?: ""
        Label(name + "  ·  " + (if (vm.prefs.avForAll) "changes apply to all channels" else "changes are saved for $what"), size = 12.sp)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val format = ScreenFormat.of(choice.format) ?: ScreenFormat.AUTO
            ChoiceRow("format", "Screen format", ScreenFormat.entries, format, { it.label }, Modifier.focusRequester(first)) { vm.setScreenFormat(it) }

            val audio: List<AvTrack?> = listOf(null) + vm.audioTracks
            val audioNow = if (choice.audio == null) null else vm.currentAudio()
            ChoiceRow(
                "audio", "Audio track", audio, audioNow,
                { t -> t?.let { Av.describe(it, vm.audioTracks) } ?: ("Automatic" + (vm.audioTracks.firstOrNull { it.selected }?.let { " (" + Av.describe(it, vm.audioTracks) + ")" } ?: "")) },
                hint = when (vm.audioTracks.size) { 0 -> "Audio tracks show here once it's playing"; 1 -> "Only one audio track here"; else -> "${vm.audioTracks.size} audio tracks, incl. secondary audio (SAP) when the channel sends it" },
            ) { vm.setAudioTrack(it) }

            // With no caption tracks found yet, "On" still means: show them when they appear.
            val cc: List<AvTrack?> = listOf(null) + vm.captionTracks.ifEmpty { listOf(vm.anyCaptions) }
            val ccNow = if (choice.captions == true && vm.captionTracks.isEmpty()) vm.anyCaptions else vm.currentCaptions()
            ChoiceRow(
                "captions", "Closed captions", cc, ccNow,
                { t -> when { t == null -> "Off"; t === vm.anyCaptions -> "On (when available)"; else -> Av.describe(t, vm.captionTracks) } },
                hint = if (vm.captionTracks.isEmpty()) "No captions found yet on what's playing" else null,
            ) { vm.setCaptions(it) }

            SectionTitle("Caption style (everywhere)", Modifier.padding(top = 6.dp))
            ChoiceRow("cc-size", "Text size", CaptionStyle.SIZES, CaptionStyle.SIZES.first { it.first == style.size }, { it.second }) { vm.setCaptionStyle(style.copy(size = it.first)) }
            ChoiceRow("cc-background", "Background", CaptionStyle.BACKGROUNDS, CaptionStyle.BACKGROUNDS.first { it.first == style.background }, { it.second }) { vm.setCaptionStyle(style.copy(background = it.first)) }
            ChoiceRow("cc-position", "Position", CaptionStyle.POSITIONS, CaptionStyle.POSITIONS.first { it.first == style.position }, { it.second }) { vm.setCaptionStyle(style.copy(position = it.first)) }

            SectionTitle("Remember", Modifier.padding(top = 6.dp))
            ChoiceRow("scope", "Save changes for", listOf(false, true), vm.prefs.avForAll, { if (it) "All channels" else what.replaceFirstChar { c -> c.uppercase() } + " only" }) { vm.setAvForAll(it) }
            if (vm.hasOwnAv(key)) {
                PanelButton("reset", "Use the settings for all channels here") { runCatching { first.requestFocus() }; vm.resetAvForChannel() }
            }
            if (live) PanelButton("add-to-list", "＋  Add to List…") { vm.avPanel = false; vm.listPickerKey = vm.currentKey }
        }
        Label("◀ ▶ change  ·  Back close", size = 12.sp)
    }
}

/** A setting with a few values: Left/Right step through them, OK goes to the next one. */
@Composable
private fun <T> ChoiceRow(
    id: String,
    label: String,
    values: List<T>,
    current: T,
    text: (T) -> String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    onChange: (T) -> Unit,
) {
    val i = values.indexOf(current).coerceAtLeast(0)
    fun go(d: Int) { if (values.size > 1) onChange(values[(i + d + values.size) % values.size]) }
    Focusable(
        modifier = modifier.fillMaxWidth().testTag("av-$id").onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { go(-1); true }
                Key.DirectionRight -> { go(1); true }
                else -> false
            }
        },
        color = C.Surface, focusedColor = C.FocusFill,
        onClick = { go(1) },
    ) { focused ->
        Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(label, size = 13.sp, color = C.Soft, modifier = Modifier.weight(1f))
                if (values.size > 1) Label("${i + 1} of ${values.size}", size = 11.sp)
            }
            Label((if (focused && values.size > 1) "◀  " else "") + text(current) + (if (focused && values.size > 1) "  ▶" else ""), size = 15.sp, color = C.Text, weight = FontWeight.SemiBold)
            if (focused && hint != null) Label(hint, size = 11.sp)
        }
    }
}

@Composable
private fun PanelButton(id: String, text: String, onClick: () -> Unit) {
    Focusable(Modifier.fillMaxWidth().height(44.dp).testTag("av-$id"), color = C.Surface2, focusedColor = C.FocusFill, onClick = onClick) {
        Box(Modifier.padding(horizontal = 12.dp)) { Label(text, size = 14.sp, color = C.Text, weight = FontWeight.SemiBold) }
    }
}
