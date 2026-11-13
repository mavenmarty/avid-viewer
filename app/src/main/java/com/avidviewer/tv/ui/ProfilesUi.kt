package com.avidviewer.tv.ui

import androidx.compose.ui.semantics.selected

import androidx.compose.foundation.border

import androidx.compose.foundation.layout.fillMaxHeight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avidviewer.tv.data.PROFILE_COLORS
import com.avidviewer.tv.data.Profile

/** A profile's tile: its colour and first letter. */
@Composable
fun ProfileAvatar(p: Profile, size: Dp, modifier: Modifier = Modifier) {
    val (bg, fg) = PROFILE_COLORS[p.color.coerceIn(0, PROFILE_COLORS.lastIndex)]
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(Color(bg)),
        contentAlignment = Alignment.Center,
    ) {
        Text(p.initial, color = Color(fg), fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold)
    }
}

/** Who's watching? At start (with more than one profile): OK opens a profile; one that needs it asks for a PIN. */
@Composable
fun WhoIsWatching(vm: AppViewModel) {
    val first = remember { FocusRequester() }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    BackHandler { activity?.finish() }
    Column(
        Modifier.fillMaxSize().background(C.Bg).testTag("whos-watching"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Who's watching?", color = C.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            vm.profiles.list.forEachIndexed { i, p ->
                Focusable(
                    modifier = Modifier.width(150.dp).then(if (p.id == vm.profile.id || (i == 0 && vm.profiles.get(vm.profile.id) == null)) Modifier.focusRequester(first) else Modifier),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Transparent, focusedColor = C.FocusFill,
                    onClick = { vm.chooseProfile(p.id) },
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProfileAvatar(p, 110.dp)
                        Text(p.name, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Label(
                            when {
                                p.kids -> "Kids"
                                vm.profiles.needsPin(p) -> "PIN"
                                else -> ""
                            },
                            size = 13.sp, color = if (p.kids) C.Accent else C.Muted,
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focusWhenReady(vm) { runCatching { first.requestFocus() }.isSuccess } }
}

/** The switcher from the main menu: every profile, then Manage profiles (grown-up profiles only). */
@Composable
fun ProfileSwitcherDialog(vm: AppViewModel) {
    if (!vm.profileSwitcher) return
    val current = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = { vm.profileSwitcher = false },
        containerColor = C.Surface,
        title = { Text("Switch profile", color = C.Text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.profiles.list.forEach { p ->
                    val on = p.id == vm.profile.id
                    Focusable(
                        modifier = Modifier.fillMaxWidth().height(56.dp).activePath(on).then(if (on) Modifier.focusRequester(current) else Modifier),
                        color = if (on) C.Surface2 else Color.Transparent,
                        onClick = { vm.chooseProfile(p.id) },
                    ) {
                        ProfileAvatar(p, 40.dp, Modifier.padding(start = 8.dp))
                        Text(p.name, color = C.Text, fontSize = 16.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp).weight(1f), maxLines = 1)
                        Label(
                            when {
                                on -> "Watching now"
                                p.kids -> "Kids"
                                vm.profiles.needsPin(p) -> "PIN"
                                else -> ""
                            },
                            size = 12.sp, color = if (on) C.AccentFocus else C.Muted, modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                }
                if (!vm.kidsMode) {
                    Spacer(Modifier.height(4.dp))
                    TvButton("Manage profiles…") { vm.profileSwitcher = false; vm.navSelect(Screen.SETTINGS); vm.settingsSection = "profiles" }
                }
                Label(
                    if (vm.profiles.hasKids) "Kids opens straight away; every other profile asks for its PIN or a parent's." else "A profile with a PIN asks for it.",
                    size = 12.sp, lines = 3,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TvButton("Close") { vm.profileSwitcher = false } },
    )
    LaunchedEffect(Unit) { focusWhenReady(vm) { runCatching { current.requestFocus() }.isSuccess } }
}

/** Asks for a 4-digit PIN: the remote's number buttons, or the keypad on screen. */
@Composable
fun PinDialog(vm: AppViewModel) {
    val ask = vm.pinAsk ?: return
    var digits by remember(ask) { mutableStateOf("") }
    var error by remember(ask) { mutableStateOf<String?>(null) }
    val five = remember(ask) { FocusRequester() }
    fun press(d: String) {
        if (digits.length >= 4) return
        digits += d
        if (digits.length == 4) { val e = vm.answerPin(digits); if (vm.pinAsk === ask) { error = e; digits = "" } }
    }
    AlertDialog(
        onDismissRequest = { vm.cancelPin() },
        containerColor = C.Surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ask.avatar?.let { ProfileAvatar(it, 40.dp, Modifier.padding(end = 12.dp)) }
                Text(ask.title, color = C.Text, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                Modifier.testTag("pin-dialog").onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val d = when (e.key) {
                        Key.Zero, Key.NumPad0 -> "0"; Key.One, Key.NumPad1 -> "1"; Key.Two, Key.NumPad2 -> "2"
                        Key.Three, Key.NumPad3 -> "3"; Key.Four, Key.NumPad4 -> "4"; Key.Five, Key.NumPad5 -> "5"
                        Key.Six, Key.NumPad6 -> "6"; Key.Seven, Key.NumPad7 -> "7"; Key.Eight, Key.NumPad8 -> "8"
                        Key.Nine, Key.NumPad9 -> "9"; else -> null
                    }
                    if (d != null) { press(d); true } else false
                },
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (ask.note.isNotBlank()) Label(ask.note, size = 13.sp, color = C.Soft, lines = 3)
                Row(
                    Modifier.semantics { contentDescription = "${digits.length} of 4 digits entered" },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    repeat(4) { i ->
                        Box(Modifier.size(40.dp, 46.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface2), contentAlignment = Alignment.Center) {
                            if (i < digits.length) Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(C.Text))
                        }
                    }
                }
                error?.let { Label(it, size = 13.sp, color = C.Live, weight = FontWeight.SemiBold) }
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "")).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { k ->
                            when (k) {
                                "" -> Spacer(Modifier.width(64.dp))
                                "⌫" -> TvButton("Delete", modifier = Modifier.width(64.dp)) { digits = digits.dropLast(1) }
                                else -> TvButton(k, modifier = Modifier.width(64.dp).then(if (k == "5") Modifier.focusRequester(five) else Modifier)) { press(k) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TvButton("Cancel") { vm.cancelPin() } },
    )
    LaunchedEffect(ask) { focusWhenReady(vm) { runCatching { five.requestFocus() }.isSuccess } }
}

/** Settings › Profiles. */
internal fun LazyListScope.profilesSettings(vm: AppViewModel, ask: (Confirm) -> Unit) {
    val set = vm.profiles
    // Changing profiles needs a parent (with their PIN) unless the profile in use is one.
    fun asParent(why: String, then: () -> Unit) = if (vm.profile.isParent) then() else vm.withParentPin(why, then)
    item {
        Label(
            "Each profile has its own favorites, lists, hidden channels and titles, My List and watch history. " +
                "Playlists, the guide, renames and Picture & sound are shared. A Kids profile sees only the categories you choose, " +
                "opens without a PIN, and has no Settings; while one exists, every other profile needs a PIN.",
            size = 13.sp, color = C.Soft, lines = Int.MAX_VALUE,
        )
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton("＋ Add profile", primary = true, modifier = Modifier.testTag("profile-add")) {
                asParent("A parent's PIN is needed to add a profile") {
                    val id = vm.addProfile("Profile ${set.list.size + 1}", kids = false, color = set.list.size % PROFILE_COLORS.size)
                    vm.editingProfile = id
                }
            }
            TvButton("＋ Add Kids profile", modifier = Modifier.testTag("profile-add-kids")) {
                asParent("A parent's PIN is needed to add a profile") {
                    vm.addKidsProfile("Kids", 2) { id -> vm.editingProfile = id }
                }
            }
        }
    }
    vm.profiles.list.forEach { p ->
        item(key = "profile-${p.id}") {
            val editing = vm.editingProfile == p.id
            SettingBlock(
                p.name + if (p.id == vm.profile.id) " · watching now" else "",
                listOfNotNull(
                    if (p.isOwner) "Owner" else if (p.kids) "Kids" else if (p.parent) "Parent" else null,
                    when {
                        p.kids -> "no PIN · ${p.allowedGroups.size} live and ${p.allowedVodCats.size} movie & series categories"
                        p.hasPin -> "PIN set"
                        set.hasKids -> "no PIN yet: opens with a parent's PIN"
                        else -> "no PIN"
                    },
                ).joinToString(" · "),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(p, 36.dp)
                    TvButton(if (editing) "Done" else "Edit", selected = editing, modifier = Modifier.testTag("profile-edit-${p.id}")) {
                        if (editing) vm.editingProfile = null else asParent("A parent's PIN is needed to change a profile") { vm.editingProfile = p.id }
                    }
                    if (!p.kids) TvButton(if (p.hasPin) "Change PIN" else "Set PIN", modifier = Modifier.testTag("profile-pin-${p.id}")) {
                        val go = { vm.askNewPin(p) { pin -> vm.setProfilePin(p.id, pin) } }
                        if (p.id == vm.profile.id || vm.profile.isParent) go() else vm.withParentPin("A parent's PIN is needed to change ${p.name}'s PIN", go)
                    }
                    // A grown-up profile keeps its PIN while a Kids profile exists.
                    if (!p.kids && p.hasPin && !set.hasKids) TvButton("Remove PIN") {
                        asParent("A parent's PIN is needed") { vm.setProfilePin(p.id, null) }
                    }
                    if (!p.isOwner && p.id != vm.profile.id) TvButton("Delete…") {
                        asParent("A parent's PIN is needed to delete a profile") {
                            ask(Confirm("Delete ${p.name}?", "Its favorites, lists, hidden items and watch history are deleted. This can't be undone.", "Delete profile") {
                                if (vm.editingProfile == p.id) vm.editingProfile = null
                                vm.deleteProfile(p.id)
                            })
                        }
                    }
                }
            }
        }
        if (vm.editingProfile == p.id) profileEditor(vm, p)
    }
    item {
        SettingBlock("When the app starts", if (set.multiple) "" else "Who's watching? appears once there's more than one profile.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("Ask who's watching", selected = set.startMode == "ask") { vm.setStartMode("ask") }
                TvButton("Last profile used", selected = set.startMode == "last") { vm.setStartMode("last") }
            }
        }
    }
}

/** Name, colour, Kids or Parent, and a Kids profile's categories. */
private fun LazyListScope.profileEditor(vm: AppViewModel, p: Profile) {
    item(key = "profile-edit-${p.id}") {
        var name by remember(p.id) { mutableStateOf(p.name) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface2).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TvField("Name", name, modifier = Modifier.width(260.dp).testTag("profile-name"), onSubmit = { vm.updateProfile(p.copy(name = name.trim().ifEmpty { p.name })) }) { name = it.take(20) }
                TvButton("Save name") { vm.updateProfile(p.copy(name = name.trim().ifEmpty { p.name })) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("Colour", size = 13.sp, modifier = Modifier.padding(end = 6.dp))
                PROFILE_COLORS.indices.forEach { i ->
                    Focusable(
                        modifier = Modifier.size(34.dp).semantics { contentDescription = "Colour ${i + 1}" + if (i == p.color) ", chosen" else "" },
                        shape = RoundedCornerShape(8.dp),
                        color = Color(PROFILE_COLORS[i].first), focusedColor = Color(PROFILE_COLORS[i].first),
                        onClick = { vm.updateProfile(p.copy(color = i)) },
                    ) {
                        if (i == p.color) Glyph("✓", Color(PROFILE_COLORS[i].second), 16.sp, Modifier.padding(start = 9.dp))
                    }
                }
            }
            Label("Theme", size = 13.sp)
            ThemePicker(p.theme) { vm.setTheme(it, p) }
            if (!p.isOwner && !p.kids) SettingSwitch("Parent", "Can manage profiles and unlock any profile with this profile's PIN.", p.parent, compact = true) {
                vm.updateProfile(p.copy(parent = !p.parent))
            }
        }
    }
    if (!p.kids) return
    item(key = "kids-live-${p.id}") {
        Text("Live TV categories ${p.name} can watch", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Label("Adult categories (XXX, Adult, 18+) are never shown, even if chosen.", size = 12.sp, lines = 2)
    }
    val live = vm.allLiveCategories()
    if (live.isEmpty()) item { Label("No live TV categories yet. They appear once channels have downloaded.", size = 13.sp) }
    live.forEach { (pl, raw, n) ->
        val id = vm.liveCategoryId(pl.id, raw)
        val adult = vm.isAdultCategory(raw)
        item(key = "kl-${p.id}-$id") {
            SettingSwitch(
                vm.groupName(raw) + if (vm.livePlaylists.size > 1) " · ${pl.name}" else "",
                if (adult) "Adult category: never shown in a Kids profile" else "$n channels",
                id in p.allowedGroups && !adult, enabled = !adult, compact = true,
            ) {
                val cur = vm.profiles.get(p.id) ?: return@SettingSwitch
                vm.updateProfile(cur.copy(allowedGroups = if (id in cur.allowedGroups) cur.allowedGroups - id else cur.allowedGroups + id))
            }
        }
    }
    item(key = "kids-vod-${p.id}") {
        Text("Movie and series categories", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
    }
    val vod = vm.allVodCategories()
    if (vod.isEmpty()) item { Label("No movies or series yet.", size = 13.sp) }
    vod.forEach { (key, label, n) ->
        val adult = vm.isAdultCategory(key.substringAfterLast('|'))
        item(key = "kv-${p.id}-$key") {
            SettingSwitch(label, if (adult) "Adult category: never shown in a Kids profile" else "$n titles", key in p.allowedVodCats && !adult, enabled = !adult, compact = true) {
                val cur = vm.profiles.get(p.id) ?: return@SettingSwitch
                vm.updateProfile(cur.copy(allowedVodCats = if (key in cur.allowedVodCats) cur.allowedVodCats - key else cur.allowedVodCats + key))
            }
        }
    }
}

/** The five colour themes as small previews; OK picks one. Each profile has its own (1.4.0). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ThemePicker(current: String, onPick: (String) -> Unit) {
    val chosen = Themes.of(current).id
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Themes.all.forEach { t ->
            val on = t.id == chosen
            Focusable(
                modifier = Modifier.width(150.dp).testTag("theme-${t.id}")
                    .semantics { contentDescription = t.name + if (on) ", chosen" else ""; if (on) selected = true },
                shape = RoundedCornerShape(10.dp), color = C.Surface, focusedColor = C.Surface, ringWidth = 3.dp,
                onClick = { onPick(t.id) },
            ) {
                Column(Modifier.padding(6.dp)) {
                    // A tiny screen in the theme: menu strip, a highlighted row with its text, an accent bar.
                    Row(Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(6.dp)).background(t.bg)) {
                        Box(Modifier.width(16.dp).fillMaxHeight().background(t.rail))
                        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(3.dp)).background(t.focusFill)
                                .then(if (t.focusLight) Modifier else Modifier.border(1.5.dp, t.focusRing, RoundedCornerShape(3.dp)))) {
                                Box(Modifier.padding(start = 4.dp, top = 5.dp).width(40.dp).height(4.dp).background(if (t.focusLight) t.inkText else t.text))
                            }
                            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(3.dp)).background(t.surface)) {
                                Box(Modifier.padding(start = 4.dp, top = 5.dp).width(32.dp).height(4.dp).background(t.soft))
                            }
                            Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(t.accent))
                        }
                    }
                    Row(Modifier.padding(top = 5.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label((if (on) "✓ " else "") + t.name, size = 12.sp, color = if (on) C.Accent else C.Text, weight = if (on) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}
