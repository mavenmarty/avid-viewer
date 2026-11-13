# Avid Viewer — menu, navigation and data-loading audit

Audited build: `next` @ `959d8a6` (= published **v1.0.97**), Nov 11, 2026.
Product code was not changed. Everything written for the audit is in `audit/`:

| Path | What it is |
|---|---|
| `audit/MENU_AUDIT.md` | This report |
| `audit/fixtures/generate.py` | Deterministic stress catalog: 10,000 live channels (one category of 8,000), 30,000 movies/series (one category of 15,500), 7-day XMLTV for 500 channels, messy titles. `--serve` serves it to a real TV |
| `audit/harness/AuditHarnessTest.kt` | Stress harness: drives the real app UI with simulated remote keys against the fixtures and prints `AUDIT|…` lines |
| `audit/results/harness-run-43019787212.txt` | Raw results of the final harness run (GitHub Actions run 43019787212) |

**How the numbers were measured, and their limits.** There is no Fire TV or Android emulator in this environment. The harness runs the app's real Compose UI under Robolectric on the CI machine's JVM, at the TV's 960×540 dp screen size, with key events sent the way the remote sends them. That makes these reliable: rendered node counts, request counts, focus/position restore (Y/N), and memory *trends*. Times and absolute memory are JVM numbers, not Fire TV numbers. As a rule of thumb, a Fire TV Stick (2nd gen/Lite) is 5–10× slower per call. Frame drops and input lag can't be measured here; they are marked "device" below. To measure on the lowest device, see "Running the stress fixtures on a TV" at the end.

### Project context (filled in from the code)

- **Platform and devices:** Android TV and Fire TV, minSdk 23, targetSdk 35. The lowest target is a Fire TV Stick (Lite / 2nd gen, 1 GB RAM). `android:largeHeap="true"` is set (`AndroidManifest.xml:39`).
- **Framework:** Kotlin, Jetpack Compose (material3, BOM 2024.12.01), Media3 ExoPlayer, OkHttp, Coil 2.
  - **State:** one `AppViewModel` holds Compose `mutableStateOf` fields (`ui/AppViewModel.kt`, 2,273 lines); there is no navigation library and no state-management library.
- **Input:** D-pad remote (arrows, OK, Back, Menu, CH±, Page±). Long-press OK equals Menu. Text entry is through the system keyboard. There is no voice, touch or mouse support.
- **Data source:** whatever the user's provider serves. Nothing is bundled and there is no backend of our own.
  - **M3U:** one text file, with no pagination possible.
  - **Xtream Codes JSON API:** whole-list endpoints `get_live_streams`, `get_vod_streams` and `get_series`. They can be filtered by `category_id`, but there is no cursor and no page size.
  - **XMLTV guides:** one `.xml` or `.xml.gz` file.
  - **TMDB** is used for Discover only.
  - Everything is saved to app-private JSON files (`data/Store.kt`).
- **Catalog size:** typical providers have 5–30k live channels and 10–100k titles. The worst case reported by the user is 1,105 titles in one category; the fixtures go to 15,500.
- **Known symptoms:**
  - Issue #1, a Discover crash after 10–15 s, is fixed in 1.0.82. It was caused by whole-file reads and a lookup built on the main thread.
  - Locked navigation while holding a key was fixed in 1.0.80.
  - Favorites one channel off after a restore was fixed in 1.0.94.
- **Run locally:** `gradle testDebugUnitTest` runs the UI tests, and CI builds the APK. For the stress harness, see the end of this report.

---

## A. Executive summary

1. **The whole catalog is downloaded, parsed and held in memory, for every playlist.** This is the single biggest crash risk.
   - **What's loaded:** the active playlist's live channels and guide at startup; every playlist's movies and series as soon as Movies, Series or Discover opens; and every playlist's channels, guides and titles the first time someone searches.
   - **Measured peaks:**
     - 70 MB to read a 40,000-entry M3U
     - 88 MB to save and load 30,000 titles
     - 137 MB of allocation while reading a 7-day, 500-channel guide
     - background sync repeats this work for each playlist while the user browses
   - **Why it matters on the Stick:** a Fire TV Stick's app heap is roughly 256–512 MB with `largeHeap`, shared with ExoPlayer's buffers and Coil's image cache. These peaks are the most likely way to run out of memory.
   - **Retained memory is modest:** about 28 MB for 10k channels plus a day of guide data, and it stays flat over time.
   - **Unbounded caches:** two caches of other playlists' data are never emptied.
2. **The screens themselves are already virtualized and don't grow with the catalog.**
   - **Measured:** a 15,500-title category renders 36 posters; an 8,000-channel guide renders 7 rows and 23 program cells.
   - **Memory stays flat:** over 50 deep-and-back round trips and 50 category switches.
   - **What's slow** is the work the screens redo on every highlight move: channel numbers for the whole playlist take 8.5 ms per call on the build machine (about 40–85 ms estimated on a Stick), and movie category filtering takes 10.5 ms per recomposition.
3. **Position memory is good one way and broken the other.**
   - **Works (50/50):** Back from full screen returns to channel 2,400 of 8,000, and each Back step lands on the current category.
   - **Broken (0/50):** opening the *same* category again lands on whichever row happens to be at the top of the screen, not the remembered channel.
   - **Index-based:** remembered scroll positions are stored by row number (index) and for only one category at a time.
   - **Not remembered at all:** the guide's chosen time.
4. **Settings breaks the app's own navigation rules.**
   - **Left switches sections:** in 6 of 11 sections, Left from inside the section moves you to a *different* section. Sections are selected as soon as they're highlighted, and Left picks whatever is spatially nearby.
   - **Back skips a level:** Back jumps straight to the main menu instead of going back to the section list.
   - **Right lands mid-page:** entering a section highlights the nearest control rather than the first one.
   - **Inconsistent controls:** three different toggle styles, "current value" that looks exactly like a primary button, and some destructive actions with no confirmation.
5. **The highlight can be taken away or misplaced without the user doing anything.**
   - **Update dialog:** the "Update available" dialog opens while the user is browsing and grabs the highlight. It interrupted the harness twice.
   - **Stale flags:** the flags that tell Left which list it's in can go stale, so Left on the Playlists page jumped to the main menu.
   - **One-shot requests:** several screens still place the highlight with a single delayed request, which can silently fail on a slow device.

**Biggest single cause of crashes:** loading whole catalogs and guides into memory, with large allocation spikes during parsing and background sync. The fix is to stream provider data into an on-device database once, then page every list from it (section E).

**Issue count:** 3 × P0, 10 × P1, 22 × P2, 6 × P3 (41 total).

---

## B. Navigation map and screen inventory

### Where navigation and focus state lives

| State | Where | Survives screen change? |
|---|---|---|
| Current screen (`screen`), menu level (`navMode` FULL / COLLAPSED / GROUPS), `lastScreen` | `AppViewModel` (`ui/AppViewModel.kt:101-105`) | Yes (process lifetime) |
| Selected TV category (`selectedGroup`), playing channel (`currentKey`) | `AppViewModel` :121-122 | Yes; also saved as `prefs.lastChannel` |
| Guide list scroll | `guideScroll` = one (category, index, offset) triple, :112 | One category only; index-based |
| Movies/Series grid scroll | `vodScroll[kind]` = (category, index, offset), :114 | One category per kind; index-based |
| Guide time window and time anchor (`slots`, `anchor`) | `remember` in `GuideScreen` (`ui/GuideScreen.kt:73, 86`) | **No**, lost when the guide leaves composition |
| Category-list scroll | `rememberLazyListState` in `GroupNav` (`ui/Nav.kt:249`) and `PlaylistTree` (:158) | **No**, rebuilt from `navFocusKey` on return |
| Settings section (`settingsSection`) | `AppViewModel` | Yes; reset to Playlists by `navSelect(SETTINGS)` |
| Settings nav collapsed (`compact`), Playlists column selection, List Editor tab/category | `remember` in `OtherScreens.kt` | **No** |
| "Which left list has focus" (`inGroupNav`, `inPlaylistTree`) | Plain fields set by `onFocusChanged` (`AppViewModel.kt:1982-1983`) | Not cleared when the list disappears (stale) |
| Pending highlight targets (`navFocusKey`, `guideFocusTick` + `guideFocusMode`, `entryFocusFor`) | `AppViewModel` | Yes |

There is no back stack. Back is a function of (`navMode`, `screen`, focus flags) in `navBack()` (`AppViewModel.kt:1991-2008`), plus screen-local `BackHandler`s: the movie/series detail page (`VodScreen.kt:212`) and the player.

There is no focus manager. Each screen places the highlight itself using one of four patterns:
- `entryFocus` (per-frame retry, `ui/Components.kt`)
- the guide's `pendingFocus` loop (`GuideScreen.kt:123-142`)
- `navFocusTarget` (`Components.kt`)
- one-shot `delay(50–80) + requestFocus` (`Nav.kt:96-101`, `Nav.kt:173-174`, `Lists.kt:72, 96`, `OtherScreens.kt:287, 418`)

A root safety net (`focusRescue`, `Root.kt:53-61` with `MainActivity.dispatchKeyEvent`) puts the highlight back when a key reaches nothing.

**Lists and grids:** every scrolling list is `LazyColumn`, `LazyRow` or `LazyVerticalGrid`.
- **Guide:** `LazyColumn` of rows; each row is a plain `Row` of the programs inside a 2.5 h window.
- **Settings section list:** a non-lazy `Column` with `verticalScroll`.

**Data layer:**
- **Download:** `data/Sources.kt` (Xtream live, M3U, XMLTV), `data/Vod.kt` (Xtream movies/series), `data/Tmdb.kt`.
- **Save:** `data/Store.kt` streams JSON item by item since 1.0.82.
- **Cache:** in-memory lists in `AppViewModel` (`channels`, `guide`, `vod`, `otherLive`, `otherVod`, `searchPool`, `discoverLists`, `whereCache`).
- **Images:** Coil's default `ImageLoader`, with no custom size cap or concurrency limit, via `AsyncImage` in posters and channel badges.

### Navigation map

```
App launch ──► TV Guide (screen=GUIDE, navMode=GROUPS), last channel previewing
│
├─ [main menu: NavRail — icons; full width with labels in FULL]          ui/Nav.kt:91
│   Search · Discover · TV Guide · Sports · Movies · Series · Settings
│
├─ TV Guide  (GROUPS)                                                   ui/GuideScreen.kt
│   ├─ Category list (GroupNav)                                         ui/Nav.kt:247
│   │    ‹ Menu · ★ Favorites · your lists · ＋ New list (dialog) · 🔍 Search · categories…
│   │    Menu on a category ► Category options dialog ► Rename (dialog) / Re-order (mode) / Sports / Hide
│   ├─ Preview window ──OK──► Player (full screen)                      ui/PlayerScreen.kt
│   ├─ Details panel (not focusable)
│   └─ Grid: channel name column + program cells (2.5 h window, ≤12 h ahead)
│        OK on name/program ► preview; OK again ► Player
│        Menu ► Channel options ► Add to List (picker ► New list) / Rename / Re-order / Match guide (dialog) / Hide
│   Back/◀ ► current category in GroupNav ► Back/◀ ► Playlist list (COLLAPSED) ► Back/◀ ► main menu (FULL) ► Back ► exit app
│
├─ Playlist list (PlaylistTree, COLLAPSED)                              ui/Nav.kt:155
│    ★ Favorites · lists · playlists ▸ (expand) ► categories ──OK──► TV Guide (GROUPS)
│
├─ Search (global)                                                      ui/SearchScreen.kt
│    text field ► results (channels, programs, movies, series) ► channel: TV Guide on that playlist/category
│                                                                       ► title: Movies/Series detail
├─ Discover (TMDB)                                                      ui/DiscoverScreen.kt
│    tabs ► poster grid ► title detail (in-screen state)
├─ Sports                                                               ui/SportsScreen.kt
│    categories ► event list ► OK: preview/tune ► Player
├─ Movies / Series                                                      ui/VodScreen.kt
│    Category list (All, Recently added, Continue watching, My List, 🔍 Search, categories…)
│    Menu on a category ► options dialog ► Rename / Re-order (mode) / Hide
│    ► Poster grid ► Detail page (BackHandler)                          VodScreen.kt:212
│         Movie: Play/Resume/Start over/+My List ► Player
│         Series: seasons ► episodes ► Player (Back ► same episode)
└─ Settings                                                             ui/OtherScreens.kt:570
     Section list (collapses to icons when a section has focus)
     ├─ Playlists: [list] [actions + switches] [add/edit form | details | ▤ Guide sources]   (3 columns, none collapse)
     │     Refresh / Remove / Save edit ► confirmation dialogs
     ├─ List Editor: tabs Live TV · Movies · Series · My lists ► [categories] [items]   (2 columns)
     ├─ Location & weather · Display & time · TV guide · Sports tab · Discover · Playback ·
     │  Privacy & analytics · Storage · Updates & about (backup PIN dialogs, SAMBA, report a bug)
     Global modals: crash notice, update prompt (Root.kt:97-128), re-order help panel (non-modal)
```

### Screen inventory

| Screen / column | How you get there | How you leave | Lazy? | Remembers position? |
|---|---|---|---|---|
| Main menu (NavRail) | Launch (icons); Back/◀ from the playlist list | OK on an item; ▶ into content | No (7 items) | Highlights current screen |
| Playlist list (PlaylistTree) | Back/◀ from the category list; TV Guide from the menu | OK on a category; Back/◀ to the menu | Yes | Current category (via `navFocusKey`); scroll rebuilt |
| Category list (GroupNav) | OK on a category; launch | ‹ Menu, Back/◀ to the playlist list; ▶ into guide | Yes | Current category; scroll rebuilt |
| TV Guide grid | Launch; OK on a category | Back/◀ to the category list; OK×2 to Player | Rows yes; programs windowed | Channel after full screen ✓; same category again ✗; time ✗ |
| Player | OK on preview / channel / title | Back | — | — |
| Search | Menu | Back to menu; OK on result | Yes | Query and results kept |
| Discover | Menu | Back; OK on a title (detail state) | Grid yes | Tab kept |
| Sports | Menu | Back; OK on event | Yes | Category kept |
| Movies / Series categories | Menu | ▶ grid; Back to menu | Yes | Current category (no scroll memory) |
| Poster grid | ▶ from categories | ◀ categories; OK detail | Yes (grid) | Index-based, one category per kind |
| Movie/series detail | OK on poster or search result | Back (to grid) | Episodes yes | Last-played episode ✓ |
| Settings section list | Menu | ▶ into section; Back/◀ to menu | No (`Column`+scroll) | Section kept (reset by menu) |
| Settings section content | ▶ from section list | ◀ to section list; Back to menu | Mostly | No |
| Dialogs (options, list picker, rename, match guide, confirm, PIN, update, crash) | Menu / actions / automatic | OK on an action, Back, Done | — | Focus return relies on Compose default |

---

## C. Issue log

Severity: **P0** crash, data loss or unusable at the target catalog size; **P1** navigation broken or the user loses their place; **P2** inconsistency between screens; **P3** polish. "Measured" = reproduced by the harness (see the results file); "suspected" = from code reading, with what would confirm it. "Ref:" = how mature streaming/cable apps handle it, and whether to copy, adapt or differ.

| ID | Area | Severity | Title | Where (screen + file:line) | Steps to reproduce | Expected vs. actual | Evidence | Recommended fix | Effort |
|---|---|---|---|---|---|---|---|---|---|
| DATA-01 | Data | P0 | Whole catalogs and guides are parsed and held in memory, for every playlist | All screens. `ui/AppViewModel.kt:769-808` (live at launch), `:555-610` (all VOD when Movies/Series/Discover opens), `:1091-1135` (background sync of every playlist), `data/Sources.kt:103-150` (M3U), `:188-240` (XMLTV) | Add the fixture playlist; open the guide, then Movies; wait for background sync | Expected: launch loads category structure + first page; lists page from local storage. Actual: full download + full parse + full in-memory list for each playlist; sync repeats it per playlist while browsing | Measured peak allocation: M3U 40k entries **70 MB** (778 ms JVM), Store save+load 30k titles **88 MB**, XMLTV 7 d × 500 ch **137 MB** (1.0 s JVM; keeps 22,193 of 93,482 programmes). Retained after launch: 28 MB. Device peak suspected to be the main crash cause; confirm with `adb shell dumpsys meminfo` + `am monitor` during first sync on a Stick | Stream provider data into an on-device database (Room/SQLite) in the background, one chunk at a time; screens query pages by category. Ref: Netflix/Prime "shell first, pages on approach" → **adapt** (our "API" is a whole file, so the device database plays the server) | L |
| DATA-02 | Data | P0 | Caches of other playlists' data never shrink | Search, Favorites, List Editor. `AppViewModel.kt:190` `otherLive`, `:192` `otherVod`, `:1140-1145` `searchPool`, `:1832-1849` `liveFor`/`vodFor`, `:1851-1893` `search()` | Have 3 big playlists; run one global search; open Favorites holding channels from each | Expected: bounded cache (LRU, size cap). Actual: every playlist's channels + guide + titles are loaded and kept for the life of the process | Code. Suspected P0 with 2+ large playlists; confirm with heap dump after one search with three 30k playlists | Search and lookups go to the database (indexed `LIKE`/FTS); remove the in-memory copies or cap them (LRU by playlist). Ref: streaming apps search server-side → **adapt** (device DB) | M |
| DATA-03 | Data | P0 | Xtream live list is read as one string and one JSON tree | `data/Sources.kt:68-75` (`JSONArray(Net.text(...get_live_streams))`) | Xtream playlist with 30k+ live channels; Refresh | Expected: streamed like Xtream movies (`data/Vod.kt:80-114`, `JsonReader`). Actual: whole response string + DOM | Measured: 10k channels (1.9 MB JSON) **22 MB** peak; scales linearly (~70 MB at 30k, more for providers with long names) | Use `JsonReader` as `XtreamVod.movies` does; write to the DB as it reads. Ref: n/a (implementation) → **copy** our own VOD path | S |
| NAV-01 | Navigation | P1 | Opening the same category again forgets the channel you were on | TV Guide. `ui/GuideScreen.kt:114` (`GuideFocus.KEEP -> pendingFocus = listState.firstVisibleItemIndex`) | Guide › ALL CHANNELS › go to channel 2,400 › Back › Back › OK on ALL CHANNELS | Expected: highlight on channel 2,400. Actual: highlight on the top visible row (2,398) | Measured **0/50** restored (`S4…restored_channel_after_reentering`) | Remember the focused channel key per category (nav store) and focus it; fall back to nearest neighbour by position if gone. Ref: every TV guide returns to the channel → **copy** | S |
| NAV-02 | Navigation / Settings | P1 | Left inside Settings switches you to a different section | Settings. `ui/OtherScreens.kt:598` (`onFocus = { vm.settingsSection = sec.id }`) + spatial Left | Settings › List Editor › ▶ › ▼×30 › ◀ | Expected: back on "List Editor" in the section list. Actual: lands on "Updates & about" and shows it | Measured: wrong section after ◀ in 6/11 sections (List Editor→About, TV guide→Playback, Discover→List Editor, Playback→List Editor, Privacy→Discover, Storage→Display) | ◀ from section content always targets the active section (focus restorer / explicit `left` requester); select sections on OK or keep focus-select but never on a Left arrival. Ref: Apple TV/Netflix settings return to the opened item → **copy** | S |
| NAV-03 | Navigation / Focus | P1 | Stale "which list has focus" flags make Left act as Back from unrelated places | `AppViewModel.kt:1982-1983`, set in `Nav.kt:177` and `:277` via `onFocusChanged`; used by `Components.kt:228-233` (`leftGoesBack`) | Guide › Back to playlist list › Menu › Settings › Playlists › ▶ › ◀ | Expected: ◀ moves within Playlists / to the section list. Actual: jumps to the main menu (`nav=FULL`) | Measured (`S7… playlists via Left … nav=FULL`). Flag stays true when the list leaves composition while focused | Derive the layer from the focused node (central focus manager) or clear flags in a `DisposableEffect`; Left-as-Back only when the focused item is in that list. Ref: n/a → fix | S |
| NAV-04 | Navigation | P1 | Remembered scroll positions are index-based and for one category only | `AppViewModel.kt:112` (`guideScroll`), `:114` (`vodScroll`), `VodScreen.kt:172` (grid keys include the index) | Scroll a movie category far down › switch category › come back; or hide/refresh items while deep | Expected: same item focused, same offset. Actual: first category's position is gone after visiting another; a refresh/hide/rename shifts the index to a different item | Code + `S4` (single-slot). Suspected for the refresh case; confirm by hiding 10 titles above the focused one and returning | Nav store keyed by path (screen/playlist/category): anchor **item key** + pixel offset; restore by key, nearest neighbour fallback. Ref: Netflix rows keep per-row cursor → **copy** | M |
| NAV-05 | Guide | P1 | The guide forgets the chosen time when you leave it | `GuideScreen.kt:73` (`slots`), `:86` (`anchor`) in `remember` | Guide › ▶ through programs to 9 PM › OK OK (full screen) › Back | Expected: same time window and cell. Actual: window back at "now", highlight on the channel name | Code; `closeFullScreen` → `GuideFocus.CURRENT` focuses the name cell (`GuideScreen.kt:116-120`, `AppViewModel.kt:1537-1553`) | Keep `slots`/`anchor`/focused program in the nav store; return to the same cell. Ref: cable guides return to the cell → **copy** | S |
| NAV-06 | Focus | P1 | "Update available" dialog opens mid-browse and takes the highlight | `ui/Root.kt:107-128` | Leave auto-check on; browse the guide when a release appears | Expected: never steal focus; offer at an idle moment. Actual: modal appears and captures key input | Measured twice (harness runs 1–2: focus moved to "Update now"/"Later" during guide and settings scenarios) | Non-modal badge in the menu (already exists, `Nav.kt:144-147`) + prompt only at launch before the first key, or in Settings. Ref: streaming apps update silently or at launch → **copy** | S |
| PERF-01 | Performance | P1 | Every highlight move in the guide recomputes channel numbers for the whole playlist | `GuideScreen.kt:68` → `AppViewModel.kt:1287-1292` (`numbers()`), also `LiveScreen.kt:48`, `OtherScreens.kt:59`; `channelsIn` `:1252`, linear `channel()` `:1262`, `groups()` `:1192` | Guide with 10k channels; move the highlight | Expected: <100 ms input response on the Stick. Actual: `numbers()` alone **8.5 ms/call** on the CI JVM (≈40–85 ms on a Stick, device to confirm), plus `groups()` 1.3 ms, `channel()` 0.19 ms × many calls | Measured (`recompose_costs`). `focusProg` state is read at the top of `GuideScreen`, so each focus change recomposes it (`GuideScreen.kt:77, 241, 292`) | Derive numbers/groups/lists once per data change (`derivedStateOf`/cached in VM, keyed by a data version) and index channels by key (`HashMap`). Ref: n/a → fix | M |
| PERF-02 | Performance | P1 | Movies/Series recompute the full catalog filters on each recomposition | `VodScreen.kt:63-85` → `AppViewModel.kt:2066-2140` (`vodOf`, `vodCategories`, `vodItems`) | 30k titles; open Movies, move around, change category | Expected: filters computed once per data/category change. Actual: **10.5 ms** per recomposition on the CI JVM for 30k titles (device ≈5–10×) | Measured (`recompose_costs`) | Cache in the VM keyed by (catalog version, category, query) or query the DB page | S |
| PERF-03 | Performance | P1 | The guide allocates two focus requesters per channel of the category | `GuideScreen.kt:87`, `:98` (`List(list.size) { FocusRequester() }`); `LiveScreen.kt:50` | Open an 8,000-channel category | Expected: per visible row. Actual: 16,000 objects created, re-created whenever the size changes | Code (cheap per object, but on the main thread at every category change) | Create the requester inside the row (`remember` in the item), find rows by key | S |
| FOCUS-01 | Focus | P1 | Some screens place the highlight once after a fixed delay | `Nav.kt:96-101` (main menu, 50 ms), `Nav.kt:173-174` (playlist list, 80 ms), `Lists.kt:72, 96`, `OtherScreens.kt:287, 418` | Slow device: Back to the main menu or open the playlist list while the UI is busy | Expected: highlight always lands (retry until it holds, cancel on key). Actual: one attempt; on a busy frame it lands nowhere (the rescue fixes it only after the next key) | Code; this class of bug was the cause of 1.0.80's series and guide bugs. Confirm on a Stick during first sync | Single focus manager API (`focusWhenReady(target)` with per-frame retry + key cancel) used everywhere. Ref: n/a | S |
| NAV-07 | Navigation / Settings | P2 | Back and Left do different things in Settings | `AppViewModel.kt:1991-2008` (`navBack`), `OtherScreens.kt` (no BackHandler in sections) | Settings › any section › ▶ › Back | Expected (contract): Back = up one level = section list. Actual: Back goes to the main menu (`nav=FULL`); Left goes to the section list | Measured (`S7 … via Back … nav=FULL`) | Back from section content → section list (then menu) | S |
| NAV-08 | Navigation | P2 | Some columns select on highlight, others on OK | `OtherScreens.kt:598` (Settings sections on focus) vs `Nav.kt:123` (menu on OK), `Nav.kt:348` (categories on OK), `VodScreen` categories (OK) | Compare moving through Settings sections with moving through categories | One rule everywhere. Actual: mixed | Code | Contract: left columns *preview* on highlight only if cheap; commit on OK/▶. For Settings: highlight shows the section's summary, ▶/OK opens it. Ref: Apple TV settings commit on select → **copy** | S |
| NAV-09 | Navigation | P2 | Wrap-around exists in some lists only | Wraps: `GuideScreen.kt:178-181, 287-289`, `Nav.kt:282, 338`, `VodScreen` categories. No wrap: main menu, playlist list, settings sections/content, poster grid, search, List Editor | Press ▼ at the end of each list | One rule. Actual: mixed | Code (`Components.kt:297-324` `ListWrap`, `wrapEnds`) | Decide (see G); proposal: wrap in vertical *menus* (category lists, guide rows), never in grids or settings | S |
| NAV-10 | Settings | P2 | ▶ into a settings section lands on the nearest control, not the first | Settings content (`OtherScreens.kt:609-…`) | Settings › Updates & about › ▶ | Expected: first control (or last used). Actual: "User name (or DOMAIN\user)" in the SAMBA block | Measured (`S7 about … right=…`) | Entry focus = remembered or first control of the section | S |
| NAV-11 | Navigation | P2 | Back at the top: exits from the Guide without asking; elsewhere goes to the Guide first | `Root.kt:130`, `navBack` FULL branch | Back repeatedly from the main menu on the Guide vs on Movies | Defined, consistent exit. Actual: immediate exit from Guide; other screens step to Guide first | Code | Keep "Back → Guide → exit", add "Press Back again to exit" toast or confirm. Ref: Netflix/Prime ask before exit → **copy** | S |
| NAV-12 | Navigation | P2 | Jumping from Search never returns to the results | `AppViewModel.kt` `tuneAnywhere` (:1148-1159); no history | Search › choose a channel › Back×n | Expected (mature apps): Back returns to the results. Actual: Back walks the guide layers to the menu | Code | Push a "return to Search" entry when a jump starts from Search/Discover/suggestions; Back pops it. Ref: Netflix search → **copy** | M |
| NAV-13 | Navigation | P2 | Category-list positions are found by counting header rows | `Nav.kt:257-258`, `:267-271`, `:163-169` | Add a header row (as 1.0.97 did with Search) | Expected: positions by key. Actual: three hand-counted offsets that must be updated together | Code (1.0.97 had to change all three) | Scroll by item key (`layoutInfo` + key index map) | S |
| STATE-01 | States | P2 | "Active path" looks different in every column | Menu `Nav.kt:121` (solid accent fill), categories `Nav.kt:291, 346, 350` (Surface2 + "▶" + bold), Movies cats (same as categories), Settings sections `OtherScreens.kt:596-603` (Surface2 + accent icon, no ▶), playlist list `Nav.kt:217-223` ("▾" + "Active" label) | Compare the selected parent in each column | One token set. Actual: four styles | Code | Contract tokens (section D) | S |
| STATE-02 | States | P2 | "Current value" looks exactly like a primary button, by colour alone | `Components.kt:95-120` (`TvButton`: `selected` and `primary` share `C.Accent`) | Settings › Playback: "MPEG-TS (.ts)" vs any primary button | Distinct, not colour-only. Actual: identical | Code | Current value = check mark + outline (not fill); primary = fill | S |
| STATE-03 | States / Settings | P2 | Three different toggle controls | `OtherScreens.kt:837` (`ToggleRow`), `:429` (`MiniToggle`), `:681` (pill "Auto-check: on/off") | Compare Storage, Playlists, Updates & about | One switch control | Code | One `SettingSwitch` component | S |
| STATE-04 | States | P2 | Disabled state exists in one place; busy buttons stay focusable and do nothing | `OtherScreens.kt:429` (only `MiniToggle` has `enabled`); "Downloading…", "Working…" buttons in `GuideTools.kt`, `OtherScreens.kt:680` | Press "Downloading…" | One rule (see G). Actual: silent no-op | Code | Busy = spinner + label, not focusable, or focusable with "Busy" announcement | S |
| SET-01 | Settings | P2 | Destructive actions are confirmed inconsistently | Confirmed: remove/refresh playlist, delete guide source, clear matches, restore. Not: Clear watch history `OtherScreens.kt:779`, Clear cache `:655`, Clear crash report `:669`, remove Sports category (`sportsSettings`), remove TMDB key | Press each | All destructive actions confirm with Cancel focused | Code | Use the existing `ConfirmDialog` everywhere | S |
| SET-02 | Settings | P2 | Fields that need Save are discarded silently by Back | Postal code, TMDB key, Sports "Add", playlist add/edit form, guide source form (`GuideTools.kt` `SourceForm`) | Type a postal code › Back | Expected: keep draft or ask. Actual: lost (suspected; confirm on device) | Code (local `remember` state) | Auto-save on field submit, or "Discard changes?" on Back | S |
| SET-03 | Settings | P2 | Column collapse has no single rule | Section list collapses to icons (`OtherScreens.kt:578-581`); Playlists' 3 columns and List Editor's 2 columns never collapse; main menu collapses; playlist list is replaced by the category list | Walk every settings column | One rule | Measured (`S7 navCollapsed=true` for all sections; inner columns unchanged) + code | Contract: the column that loses focus to a deeper one compresses to icons/title; at most 3 full columns | M |
| GUIDE-01 | Guide | P2 | Only 12 hours ahead; no jump to now / next day / date | `GuideScreen.kt:57` (`AHEAD_MIN = 12*60`), `AppViewModel.kt:961` (keeps −3 h…+36 h) | ▶ through the guide | Expected: days ahead with "Now", "Next day", date picker. Actual: stops at 12 h; 7-day data unused | Measured (`xmltv_programmes_kept 22,193 of 93,482`) | Day strip + "Now" key (e.g. Menu › Jump to…); fetch/keep a day window around the view | M |
| GUIDE-02 | Guide | P2 | Back from full screen lands on the channel name, not the chosen program | `GuideScreen.kt:116-120` | Choose a later program › OK OK › Back | Same cell. Actual: name cell, window at now | Code | See NAV-05 | S |
| DATA-04 | Data / Images | P2 | Holding a key or switching categories fires hundreds of image requests | Poster grid (`VodScreen.kt:196`), Coil default loader | Hold ▼ 10 s in a 15,500-title category; switch categories 50× | Expected: requests only where focus rests; cap concurrency. Actual: **230** requests in 10 s (217 cancelled), **501** for 50 switches (471 cancelled) | Measured (`S3`, `S5`). Cancellation works; issuance isn't throttled | Start image loads after ~150 ms of rest or when scroll velocity is low; Coil `ImageLoader` with capped memory cache and dispatcher limit. Ref: Netflix/Prime throttle row image loads during fast scroll → **copy** | S |
| DATA-05 | Data | P2 | Guide sources are downloaded whole every refresh, without conditional requests or de-duplication | `AppViewModel.kt` `downloadGuide` / `refreshGuideNow` | Change two guide sources quickly | One download; `If-Modified-Since`/ETag. Actual: full download per call; concurrent calls not merged (suspected; confirm by counting requests) | Code | Conditional GET + single-flight per playlist | S |
| DATA-06 | Data | P2 | No prefetch of the next column | Category → items | Move through categories | After pagination exists, prefetch the highlighted category's first page; cancel when focus moves | Code | Part of section E | S |
| DATA-07 | States | P2 | Loading, empty and error states differ per screen; not all errors offer Retry | Guide `EmptyState`, `VodScreen.kt:64-79` (only a "Playlists" button), Discover/Search status text | Turn off Wi-Fi and open each screen | Same skeleton/empty/error components; Retry from the remote everywhere | Code | Shared `LoadingState`/`EmptyState`/`ErrorState(retry)` | S |
| FOCUS-02 | Focus | P2 | Focus return after dialogs relies on Compose's default | All dialogs; explicit restore only in `PlayerScreen` (list picker) | Menu › Rename › Save (or Cancel) on each screen | Back on the item that opened it (suspected OK on most; the player needed an explicit fix). Confirm with a harness check per dialog | Code | Focus manager records the opener and restores it on dismiss | S |
| NAV-14 | Navigation | P2 | Breadcrumb for the category is now only the highlight in the left list | Guide header (`GuideScreen.kt:241-245`, label removed in 1.0.97) | Move into the grid; the category list stays visible, but in search results or full screen there's no category name | Where-am-I always visible | Code | Keep the category in the details panel's first line or the time row ("KIDS · Today") | S |
| STATE-05 | States | P3 | Inactive columns aren't dimmed | All multi-column screens | Move between columns | Columns without focus slightly dimmed | Code | Token `inactiveColumnAlpha` | S |
| GUIDE-03 | Guide | P3 | Half-hour columns are aligned to UTC, not local time | `GuideScreen.kt:71` | Set time zone to Asia/Kathmandu (+5:45) | Columns on local :00/:30. Actual: :15/:45. DST is handled (epoch times, zone-aware formatting); not run across a DST boundary | Code | Compute slot starts in the selected zone | S |
| GUIDE-04 | Guide | P3 | Past programmes can't be viewed | Window starts at the current half hour | ◀ from the first column | Product decision (catch-up) | Code | See G | — |
| A11Y-01 | Accessibility | P3 | Glyph prefixes are read aloud; selected state isn't exposed to TalkBack | "▶ ", "★", "☰", "⇅" in labels (`Nav.kt:294, 307, 350`); no `selected` semantics | TalkBack on | Announce "KIDS, selected"; no glyph noise. Suspected; confirm with TalkBack | Code | Move glyphs to decorative icons with `contentDescription = null`; set `Modifier.semantics { selected = … }` | S |
| DATA-08 | Data | P3 | Stalled downloads wait 2 minutes | `data/Sources.kt:23` (`readTimeout(120s)`) | Throttle the network during a refresh | Stall detection + resume (as the updater does since 1.0.86) | Code | Shorter read timeout + retry with backoff | S |
| NAV-15 | Navigation | P3 | Holding ◀ inside the guide's programs does nothing special at the edge | `GuideScreen.kt:340-343` | Hold ◀ on a later window | Accelerated back to now | Code | Hold ◀ jumps to now | S |

Positive findings, not logged as issues:
- **Virtualization:** a 15,500-title category renders 36 posters, and an 8,000-row guide renders 7 rows and 21–23 program cells. That's 122–160 semantics nodes on any screen.
- **Memory over time:** flat across 50 deep round trips (113–119 MB) and 50 category switches (116–118 MB).
- **Position after full screen:** Back from full screen returns to channel 2,400 of 8,000 (50/50), and each Back step lands on the current category (50/50).
- **Sticky time:** ▲▼ through 40 rows kept 12:00 AM.
- **Images:** off-screen image requests are cancelled.
- **Streaming reads:** saved files, the M3U, Xtream movies and the XMLTV guide are all read as streams.
- **Search:** runs off the main thread when you submit; 74 ms for 40,000 items on the JVM.

---

## D. Navigation and interaction contract

Every screen, dialog and new feature must follow these rules. Review against them before merging.

### 1. Keys

| Key | Always means |
|---|---|
| ▲ ▼ | Move within the current column / list / grid column. Never leave the column vertically (Settings already enforces this; apply to all multi-column screens) |
| ▶ | Go one level deeper (into the next column, the grid, a section). In a grid or guide row: next item; at the row's end: scroll the row (guide: time) |
| ◀ | Inside a row/grid: previous item. At the left edge of a column: **up one level, exactly like Back** |
| OK | Activate (open, play, toggle, choose). Never only "select on highlight" for things that load data |
| Back | Up one level, landing on the item that led here. Closes a dialog first, then a mode (re-order), then a level |
| Menu / hold OK | Options for the focused item. Never navigation |
| CH± / Page± | Page in long lists (one screen) |
| Hold any arrow | Accelerate: 1 → 3 → 6 → 12 items per repeat (existing `holdSteps`), throttled to what the device can lay out; never crosses a level |

### 2. Depth and columns

- Levels: **menu → (playlist) → category → items → detail → player**. Settings: **section list → section → value/detail**.
- **Column collapse rule:** when focus moves one level deeper, the column that lost focus compresses to icons (or a narrow title strip). At most **three** full-width columns are visible; older ones compress. Moving back expands it again. This applies to the main menu, the Settings section list and the Playlists columns alike.
- The active path is always visible: each compressed or inactive column shows its selected item in the **active-path** style.

### 3. Position memory

- One **navigation store** (in the view model or its own class, surviving screen changes and process death via saved state) holds, per path (`screen/playlist/category`, `settings/section`): the **focused item key**, the **scroll anchor** (first visible item key + offset), and screen-specific state (guide `slots`, `anchor`, focused program).
- Restore by key. If the key is gone (hidden, removed, refreshed), use the **nearest neighbour by previous position**, never the top.
- Data refreshes never move focus or reset paths. They update in place.
- Jumps (search, suggestions, Discover, notifications) push a return entry so Back returns to where the jump started.

### 4. Focus

- **Exactly one focused element.** The focus manager places it with retry-until-it-holds, cancels on any key press, and records the opener of every dialog/mode to restore on close.
- Async content **never moves focus**; prompts never open over active browsing (show at launch before the first key, or as a non-modal badge).
- Focus may not land on skeletons. Disabled items: see open question G-1.
- Scrolling: **edge scrolling** (current behaviour) everywhere; the guide keeps one row of look-ahead.

### 5. Visual states (tokens from `C` in `ui/Components.kt`)

| State | Token |
|---|---|
| Focused | `background = C.Surface2` (buttons `C.Line`), `border 2.dp C.AccentFocus`, no scale. One ring style everywhere |
| Active path (selected parent) | `background = C.Surface2`, text `C.Text` bold, leading accent bar or ▶ icon (decorative, not read aloud). Main menu uses the same, not a solid fill |
| Inactive column | content alpha 0.75 |
| Disabled | text `C.Muted` at 0.5 alpha, no ring when focused (if focusable) and announced "unavailable" |
| Current value (settings, pickers) | check icon + `C.Accent` outline, **not** a filled accent button |
| Primary action | filled `C.Accent`, text `C.OnAccent` — at most one per view |

### 6. Loading, empty and error

- Lists show **skeleton tiles** of the final size (no layout shift) until the first page arrives; focus can't land on them.
- Empty: a sentence that says what to do next plus one button. Error: what happened, plus **Retry** reachable by the remote.

### 7. Settings patterns

- **Control types:** one `SettingSwitch` (row with title, description, On/Off state); one `SettingChoice` (single choice: current value shown on the row; ▶/OK opens the choices in the third column); text fields save on submit (no separate Save) or ask before discarding on Back.
- The current value is visible on the row without opening it.
- Every destructive action: `ConfirmDialog`, Cancel focused, "can't be undone".
- Each section has **Restore defaults** where it has defaults.

### 8. Wrap-around

Proposal (pending G-2): wrap in vertical menus (category lists, guide rows) on a fresh press only; never in grids, settings or dialogs. Holding a key always stops at the end.

---

## E. Data-loading architecture proposal

### Current

| Concern | Today |
|---|---|
| Fetch | Whole M3U / Xtream list / XMLTV file per playlist; Xtream live as one string + JSON tree; VOD and M3U streamed |
| Store | JSON files per playlist (channels, guide, VOD), streamed read/write; staleness by file time (channels 1 day, guide 4–24 h setting, VOD 1 day) |
| Memory | Active playlist channels + guide (−3 h…+36 h); all VOD of all VOD playlists after first VOD screen; `otherLive`/`otherVod`/`searchPool` unbounded |
| Lists | Lazy lists/grids (virtualized); derived lists recomputed per recomposition (PERF-01/02) |
| Images | Coil defaults; cancellation on dispose; no throttle; no size cap set |
| Prefetch | None (everything is in memory) |
| Requests | Background sync sequential per playlist; no dedupe; 120 s read timeout; no retry |

### Proposed

1. **On-device catalog database (Room/SQLite).** Tables:
   - `channel(key PK, playlist, category, sort, name, logo, epgId)`
   - `title(key PK, playlist, kind, category, sort, name, poster, year, rating, added)`
   - `programme(epgId, start, end, title, sub, isNew)`, with an index on (epgId, start)
   - `category(playlist, kind, raw, name, sort, count, hidden)`

   User choices (favorites, lists, hidden items, renames, order) stay in prefs, keyed by the stable keys introduced in 1.0.94.
2. **Ingest by streaming.** Read the provider file with `JsonReader`, the M3U line reader or `XmlPullParser`, and insert in batches of about 1,000 rows inside one transaction per batch.
   - **Memory:** stays flat at one batch.
   - **Atomic swap:** write to a new generation and switch over at the end, so a refresh never shows a half-written catalog.
   - **Conditional requests:** use `If-Modified-Since`/ETag where the provider sends them.
   - **Xtream:** optionally use `category_id` to fetch large categories separately.
   - **Guide:** keep 7 days of programmes in the database (disk, not heap).
3. **Paged queries everywhere.**
   - Lists use Paging 3 (`PagingSource` per category/kind/query, page size 50, prefetch distance about 2 screens) feeding the existing lazy lists and grids.
   - Item keys are the stable keys.
   - Search uses FTS4/5 tables (`name`, `title`) with debounced queries.
   - Channel numbers come from a view, not a recomputation.
4. **Small in-memory layer.**
   - An LRU of pages (about 5 MB) and of channel lookups by key.
   - The guide keeps only the visible day window plus one window either side, extended as the view moves (time-windowed queries).
5. **Normalized shape.**
   - **Lists are IDs:** the guide/category/list screens hold keys and resolve items from the cache or database.
   - **Remove the duplicate lists:** `otherLive`, `otherVod` and `searchPool` go away.
6. **Images.**
   - **One `ImageLoader`:** set in `Application`, with a memory cache capped at about 15% of the heap, a disk cache of about 100 MB, a dispatcher capped at 4–6 concurrent requests, and size-to-view (already the default).
   - **Throttle during holds:** delay starting image loads while the remote is auto-repeating, then load the rested area.
   - **Placeholders:** keep the existing fixed-aspect placeholders.
7. **Request discipline.**
   - **Single-flight:** one download per (playlist, kind), with jobs cancelled when a playlist is turned off or removed.
   - **Timeouts:** a 20 s stall timeout with resume and retry (exponential backoff, 3 tries).
   - **Offline:** show saved data with an "Offline · last updated …" note.
8. **Prefetch.** When the highlight rests on a category for about 300 ms, query its first page. Cancel the query if the highlight moves on.

**Backend/API dependencies.** None that we control. Providers' Xtream APIs offer per-category requests (`&category_id=`) but no cursor; M3U and XMLTV are single files. The device database is how this design gets pagination. If a future first-party backend appears, it should expose cursor-based list endpoints that return tile summaries (key, name, image, one or two fields), with full metadata on a separate request when an item is opened.

---

## F. Work plan

Effort: S ≤ 2 days, M ≤ 1 week, L ≤ 3 weeks (one developer). Every phase ends with the Phase 6 harness passing its budgets in CI (see "Harden").

### 1. Stabilize

| Task | Depends on | Acceptance criteria | Effort |
|---|---|---|---|
| 1.1 Stream the Xtream live list (DATA-03) | — | Peak allocation for 30k channels < 15 MB in the harness | S |
| 1.2 Bound or remove `otherLive`/`otherVod`/`searchPool`; search reads saved files a playlist at a time without keeping them (interim for DATA-02) | — | Heap after search across 3 × 30k playlists returns to baseline ±10 MB | S |
| 1.3 Cache derived lists in the VM: numbers, groups, channelsIn, VOD filters; `HashMap` by key for `channel()` (PERF-01/02/03) | — | `numbers()` called once per data change; highlight move in a 10k guide < 2 ms of app work on the JVM; no per-channel requesters | M |
| 1.4 Update prompt never opens over browsing (NAV-06) | — | Harness run with auto-check on shows no focus change mid-scenario | S |
| 1.5 Catalog database + streaming ingest + paged queries for Movies/Series and the guide (DATA-01, E1–E3) | 1.3 | Cold start to first interactive menu does no full parse on the main path; peak allocation during a 40k-entry refresh < 30 MB; Movies opens a 15,500 category in < 300 ms (JVM) with ≤ 2 pages in memory | L |

### 2. Navigation core

| Task | Depends on | Acceptance criteria | Effort |
|---|---|---|---|
| 2.1 Navigation store: per-path focused key + scroll anchor + guide time; restore by key with nearest-neighbour fallback (NAV-01, -04, -05, GUIDE-02) | — | Harness S4 "restored after re-entering" 50/50; guide time restored after full screen; hiding items above the focused one keeps the same item | M |
| 2.2 Focus manager: `focusWhenReady`, opener restore for dialogs, replace one-shot delays, derive "which list" from the focused node (NAV-03, FOCUS-01/02) | — | No `delay(..) + requestFocus` left; harness checks every dialog returns focus to its opener; S7 ◀ never jumps to the menu | M |
| 2.3 Back = ◀-at-edge everywhere including Settings; exit confirmation; return-to-search entries (NAV-07, -11, -12) | 2.1 | Contract key table passes a scripted round trip on every screen | S |
| 2.4 Scroll by key instead of counted header rows (NAV-13) | 2.1 | No hard-coded header offsets | S |

### 3. Consistency

| Task | Depends on | Acceptance criteria | Effort |
|---|---|---|---|
| 3.1 State tokens: focused, active path, inactive, disabled, current value, primary (STATE-01…05, A11Y-01) | — | One component per state; screenshot review of every screen; TalkBack announces "selected" | M |
| 3.2 Settings drill-down: sections → settings → value column; select on OK; ◀ returns to the opened section; collapse rule; `SettingSwitch`/`SettingChoice`; current values on rows; confirm all destructive actions; Restore defaults (NAV-02, -08, -10, SET-01…03, STATE-03) | 2.2, 3.1 | Harness S7: ◀ returns to the same section 11/11; Back = ◀; every destructive action confirms | M |
| 3.3 Wrap-around per the decided policy (NAV-09) | G-2 | Same behaviour in every list of a kind | S |
| 3.4 Shared loading/empty/error components with Retry (DATA-07) | — | Every screen offline shows Retry reachable by the remote | S |

### 4. Guide grid

| Task | Depends on | Acceptance criteria | Effort |
|---|---|---|---|
| 4.1 Programmes from the database by time window (visible ±1 window), extended as the view moves; keep 7 days on disk (GUIDE-01, DATA-05) | 1.5 | Heap for 500 ch × 7 d < 10 MB; scroll to day 6 works | M |
| 4.2 Day strip, "Now", next/previous day, date picker; hold ◀ = now (GUIDE-01, NAV-15) | 4.1 | Reach any day in ≤ 3 key presses | M |
| 4.3 Local-time slots; DST test (GUIDE-03) | — | Unit tests for a DST boundary and +5:45 | S |
| 4.4 Horizontal virtualization if the window grows beyond 2.5 h | 4.1 | Cells rendered ≤ visible + 1 window | S |

### 5. Harden

| Task | Depends on | Acceptance criteria | Effort |
|---|---|---|---|
| 5.1 `ImageLoader` with caps; image start throttled during auto-repeat (DATA-04) | — | Harness S3: image requests in a 10 s hold ≤ 60 | S |
| 5.2 Prefetch first page of the highlighted category; cancel on move (DATA-06) | 1.5 | Moving ▶ into a category shows items with no loading state in the harness | S |
| 5.3 Single-flight downloads, conditional GET, stall timeout + retry (DATA-05, DATA-08) | 1.5 | Two quick refreshes = one download; stalled download resumes | S |
| 5.4 Move the stress harness into CI as a nightly job with budgets: nodes ≤ 200 per screen; S4 restored 50/50 at every level; S3 ≤ 60 image requests; heap trend slope ≈ 0; peak allocation per refresh ≤ 30 MB; per-highlight work ≤ 2 ms (JVM) | all | Nightly job fails when a budget is exceeded | S |
| 5.5 Device pass on a Fire TV Stick with `generate.py --serve`: cold start, hold, depth ×50, memory via `dumpsys meminfo` | 1–4 | Targets: focus responds < 100 ms, no dropped frames while scrolling (GPU profile), memory flat | S |

---

## G. Open questions for product and design

1. **Disabled items:** may the highlight rest on them (announced as unavailable), or are they skipped? Apple TV skips; Android TV leanback usually allows focus with an explanation. Recommendation: allow focus on items that explain why they're unavailable (e.g. "Movies are off for this playlist"), skip purely decorative ones.
2. **Wrap-around policy:** keep it in category lists and guide rows only (as now), extend it to every vertical list, or remove it? Cable guides wrap channels; streaming apps don't wrap rows or grids.
3. **Past guide slots:** hidden (current), dimmed and non-selectable, or selectable for providers with catch-up/archive?
4. **Back at the top level:** exit immediately, "press Back again to exit", or a confirmation dialog?
5. **Jumps from Search/Discover:** should Back return to the results (proposed) or walk up the destination's levels (current)?
6. **Settings drill-down:** is a third "value" column wanted for single-choice settings (time zone, units, stream format), or should choices stay inline as buttons?
7. **Guide length:** how many days ahead should the guide offer (data allows 7)? This sets how much programme data is kept on disk.
8. **Category highlight vs. commit:** should moving the highlight through categories preview their contents (Netflix-style) or only commit on OK (current)? Preview needs the paging and prefetch work first.
9. **Update prompts:** only at launch, only in Settings, or a non-modal badge?

---

## Phase 6 measurements (raw results: `audit/results/harness-run-43019787212.txt`)

CI JVM under Robolectric (not a Fire TV). Fixtures: 10,000 live channels (one category of 8,000), 30,000 movies/series (one category of 15,500), 7-day XMLTV for 500 channels (93,482 programmes), messy titles.

| Scenario | Result |
|---|---|
| Cold start to first interactive menu | Channels ready 3.37 s, highlight placed 3.47 s, guide 4.18 s (includes downloading and parsing the 5.9 MB M3U and 1.1 MB gz guide). Heap +28 MB. Requests: M3U 1, guide 1, images 6, stream 2 |
| Open largest category (15,500 titles) | All 30,000 titles loaded in 278 ms (from saved file); category shown in 87 ms; **36 posters / 122 nodes rendered**; 11 image requests |
| Hold ▼ 10 s in largest list | Slowest key handling 20 ms (JVM); focus moved far down the grid; **230 image requests, 217 cancelled**; still 36 posters rendered. Frame drops/input lag: device only |
| 3 levels deep and back ×50 (channel 2,400 of 8,000) | Restored after full screen **50/50**; category after Back **50/50**; playlist list after Back **50/50**; channel after re-entering **0/50** (NAV-01). Heap every 10: 118, 117, 119, 113, 114 MB (flat) |
| Rapid category switching ×50 | **501 image requests, 471 cancelled, 25 completed**; heap 116, 116, 118, 116, 116 MB (flat) |
| Guide grid, 7 days × 500 rows, 8,000-row category | **7 rows, 21–23 program cells, 160 nodes**; time kept across 40 rows (12:00 AM); 0 extra downloads while scrolling; only 22,193 programmes kept (−3 h…+36 h); grid stops at 12 h ahead |
| Settings: every section in and back | Section list collapses to icons in 11/11; ◀ returns to a different section in 6/11 (NAV-02); Back goes to the main menu in 11/11 (NAV-07); stale flag sent ◀ to the menu from Playlists (NAV-03); ▶ into Updates & about lands mid-page (NAV-10); ▼×30 stays inside the section 11/11 |
| Parsers alone (peak allocation) | M3U 40k entries 70 MB / 778 ms; Xtream live 10k as JSONArray 22 MB; save+load 30k titles 88 MB; XMLTV 7 d × 500 ch 137 MB / 1.0 s |
| Work per highlight move | `numbers()` 8.50 ms, `groups()` 1.31 ms, `channelsIn(8000)` 0.38 ms, `channel(key)` 0.185 ms; Movies filters 10.53 ms per recomposition |
| Search across 40,000 items | 74 ms, 210 hits (runs off the main thread) |

### Running the stress fixtures on a TV

1. On a computer on the same network: `python3 audit/fixtures/generate.py /tmp/fx --host <computer-ip>:8099 --serve`.
2. On the TV: Settings › Playlists › ＋ Add playlist › M3U, link `http://<computer-ip>:8099/catalog.m3u`, TV guide link `http://<computer-ip>:8099/guide.xml.gz`.
3. Measure with `adb shell dumpsys meminfo com.avidviewer.tv` (before, during first sync, after 50 round trips) and the GPU rendering profile (Developer options › Profile HWUI rendering) while holding ▼ in "VOD | EN - Movies 4K".
4. Harness in CI: copy `audit/harness/AuditHarnessTest.kt` to `app/src/test/java/com/avidviewer/tv/audit/`, set `showStandardStreams = true` in `testLogging`, run `gradle testDebugUnitTest --tests "*AuditHarnessTest*"` (needs `python3`).

---

## Progress log

### Nov 11 — Stabilize 1.1–1.4 (on `next`, not yet published)

| Task | Change | Acceptance | Result (harness run 43026494292, `audit/results/harness-run-43026494292-after-stabilize.txt`) |
|---|---|---|---|
| 1.1 (DATA-03) | Xtream live list read with `JsonReader` (`data/Sources.kt` `liveChannels`) | Peak < 15 MB for 30k | 10k channels: **9 MB** peak (was 22 MB as a JSON tree) → ~27 MB at 30k scaled; met for the 10k fixture, re-check at 30k |
| 1.2 (DATA-02, interim) | Other playlists keep only favorite/list channels + their guide in memory; search, suggestions, category counts read each playlist from disk one at a time; guide-search pool released when search ends | Heap back to baseline after search | Unit test `StabilizeTest.otherPlaylistsKeepOnlyTheirFavoriteChannelsInMemory` (1 of 8 channels held); multi-playlist heap check still to do on a device |
| 1.3 (PERF-01/02/03) | Numbers, categories, category lists, key lookup and movie filters computed once per data change; guide focus handles only for shown rows | < 2 ms app work per highlight move | `numbers()` **0.01 ms** (was 8.50), `groups()` 0.01 (1.31), `channelsIn(8000)` 0.00 (0.38), `channel(key)` 0.002 (0.185), movie filters 0.01 (10.53) |
| 1.4 (NAV-06) | Update dialog opens by itself only when found before the first key press | No focus change mid-scenario | Unit test `StabilizeTest.updateDialogOnlyOpensBeforeTheFirstButtonPress` |

Found while doing this: the new caches were first declared after the view model's `init`, so a fast startup could hit them before they existed. The UI tests caught it as a NullPointerException, and it's fixed by declaring them first. Everything else measured the same as before (S1–S6), with memory still flat. Still open from Stabilize: **1.5, the on-device catalog database (DATA-01)**.

### Nov 11 — 1.5 step 1: movies and series in an on-device database (on `next`, not yet published)

- **New:** `data/Catalog.kt` adds the SQLite titles table and `PagedTitles`, a list that reads 100-title pages as the grid shows them and keeps at most 12 pages.
- **Now database queries:** Movies/Series lists and categories, Recently added, Continue watching, the List Editor, global search, "hide everywhere" and Discover's "On your provider".
- **Hidden flags:** updated as soon as you press the button.
- **Upgrading:** titles saved by older versions move into the database on first use.
- **Downloads:** replace a playlist's titles in one transaction, and choices that point at titles still follow them to new IDs.
- **Measured (harness run 43033050306):**
  - Memory with 30,000 titles is about 8 MB lower and stays flat: end of run 112 MB (was 120), category switching 109 MB (was 117).
  - The largest category still draws 36 posters, and opens in 80 ms.
  - Search returns the same 210 hits, in 186 ms (was 80 ms). It now queries the database instead of memory; it runs off the main thread.
- **Still to do for 1.5:**
  - **Step 2:** stream the provider lists straight into the database, so downloading no longer builds the whole list in memory first. The 70 MB M3U peak is still there.
  - **Step 3:** live channels and the guide in the database (programmes by time window).

### Nov 11 — 1.5 step 2: downloads stream straight into the database (on `next`, not yet published)

- **M3U:** `M3u.read(p, onVod)` hands each movie/series entry over as the file is read; nothing collects them. Live channels are still a list (step 3).
- **Xtream:** movies and series stream into one save (`XtreamVod.movies/series(p, emit)`).
- **One transaction, kept on failure:** `Catalog.replacing` writes inside one transaction (WAL, so screens keep reading the old titles until it commits). A dropped download rolls back. For Xtream, if movies or series fail and titles were already saved, the old ones stay and the message says so (before, the failed part was emptied).
- **Following choices to new IDs without the list in memory:** a stream-address token column (database v2; existing databases upgrade in place) and `Catalog.remap`. It uses the same rules as before (same address, then name + category, then a unique name) via indexed lookups and one light pass over names.
- **Measured (harness run 43037727854, `audit/results/harness-run-43037727854-catalog-step2.txt`), same 6 MB file with 10k live + 30k VOD:**
  - Memory actually held while reading (measured after collections): **7 MB** streamed vs **17 MB** collected as a list. The 7 MB left is the 10k live channels.
  - The sampled peak without collections (93 vs 102 MB) is mostly short-lived garbage, so it isn't a fair measure of either. The earlier "70 MB M3U peak" was this kind of number.
  - The streamed save takes 2.1–2.8 s for 30k titles (parse alone: 0.6–0.8 s), off the main thread. The old way also wrote the titles afterwards.
  - End-of-run heap 111 MB (112 at step 1); search, posters and memory over 50 switches unchanged.
- **Tests:** `CatalogStreamTest` covers rollback, keep-old on a partial failure, first-duplicate-wins, remap by address/name+category/unique name (and refusing an ambiguous name), the v1→v2 upgrade, and streaming an M3U file. All CI tests pass on `next`.
- **Still to do for 1.5:** step 3, live channels and the guide in the database.

### Nov 11 — 1.5 step 3: live channels and the guide in the database (on `next`, not yet published)

- **New:** `data/LiveDb.kt` adds channel, programme and saved-time tables in the same database (version 3; existing databases upgrade in place).
  - Old `channels_<id>.json` / `guide_<id>.json` files are moved in on first use, then deleted.
- **Downloads stream straight in:**
  - Live lists: M3U, and Xtream `get_live_streams`. An M3U file fills channels and titles in one read.
  - Guide: each XMLTV source, in source order; the first source with a channel's programmes still wins.
  - **Failures keep the saved data:** a failed download, or an empty live list (expired account, error page), keeps what was saved. The guide also stays if every source fails.
- **Only the playlist being watched is read back whole** (the guide grid needs it).
  - Other playlists are looked up instead of loaded: favorites and lists (only those channels), category counts, global search, and the TV Guide's search box.
  - The search box now holds only the matches and their guide (it used to hold every playlist's channels and guides while open). This closes the rest of DATA-02.
- **Saved choices follow changed channel keys** via indexed lookups (address, guide id + name, name + category, unique name), for referenced channels only.
- **Found and fixed:** adding two guide sources quickly started two downloads, and the older one (with fewer sources) could finish last and overwrite the newer. Guide downloads for a playlist now run one at a time, each with the sources current when it starts. Part of 5.3 (DATA-05); conditional GET is still to do.
- **Measured (harness runs 43044191314 and 43045873316, `audit/results/harness-run-43045873316-catalog-step3.txt`):**
  - **Live list, 10k channels:** memory held while saving **0 MB** streamed, vs 5 MB as a list.
  - **Guide, 500 ch × 7 days (22,209 programmes kept):** writing to the database costs **0 MB**. Reading the window back takes **1 MB** for 500 channels, in 0.6 s.
  - **The XML read itself measures 1–33 MB held between runs.** It's the same parser either way, so this is noise in the measurement (collections triggered from the test aren't guaranteed to finish); it isn't something the database adds. The old JSON guide file (whole-file string + JSON tree on save and load) is gone.
  - **First start on a fresh install:** channels showed at 3.0 s and 5.0 s in the two runs (2.5–3.6 s at steps 1–2). CI timing varies a lot. When VOD is on, an M3U playlist's channels appear only after the same file's titles are written (1.8 s for 30k), and that was already so in step 2.
  - Unchanged: memory over 50 deep returns and 50 category switches (110–112 MB), title search 182 ms, posters.
- **Tests:** `LiveDbTest` covers failed or empty downloads keeping saved data, first duplicate wins, category counts, search with literal wildcards, remap rules, guide windows and programme search, deletion, and moving old files in. `StabilizeTest` checks that the search box holds only the matches. All 61 CI tests pass.
- **1.5 acceptance:**
  - No full parse on the main path at start: met; saved data is read from the database.
  - Peak during a 40k refresh < 30 MB: met for what the app holds (6 MB with titles, 0 MB channels). The raw sampled peak, which includes garbage, is still higher.
  - Movies opens a 15,500 category with ≤ 2 pages: met at step 1.
- **Next in the work plan:**
  - Phase 2, navigation core.
  - 4.1, the guide reading programmes by time window as it scrolls, with 7 days kept on disk. The database makes this a small change now.
  - Possible follow-up: show an M3U's channels before its titles finish saving on a first download.

### Nov 11 — Phase 2: navigation core (on `next`, not yet published)

| Task | Change | Result |
|---|---|---|
| 2.1 Navigation store (NAV-01, -04, -05, GUIDE-02) | `ui/NavStore.kt`. Per place (`guide/<playlist>/<category>`, `vod/<kind>/<category>\|<search>`) it keeps the focused item **by key** and the first visible item by key + offset. When a key is gone, the nearest item by old position is used. The guide's time window, highlighted time and "on a programme or a name" are kept for the whole guide. | Harness S4 "restored channel after re-entering": **50/50** (was 0/50). Tests: a category seen before comes back on the same channel, also after hiding two channels above it; Back from full screen returns to the same programme and time window; Back from a title's page lands on its poster. |
| 2.2 Focus manager (NAV-02, NAV-03, FOCUS-01) | `focusWhenReady` (`ui/Focus.kt`): retries each frame, stops on a key press, and replaces every fixed `delay(..) + requestFocus` (menu, playlist list, dialogs, Discover, Live, re-order). The playlist/category-list flags clear when those lists leave the screen, and Left-as-Back only applies on the guide. In Settings, Left at a section's left edge goes to that section's own row. | S7: ◀ never opens the menu from Settings any more (was 2/22 runs). ◀ from a section's left edge lands on its own row in every section; the 4 where ◀ moves along a row of buttons first (°C→°F, time zones, stream format) behave as expected. FOCUS-02 (dialogs returning focus) wasn't changed; the dialog tests that exist (rename, add to list) still pass. |
| 2.3 Back rules (NAV-07, -10, -11, -12) | Back inside a Settings section goes to its row, then the menu. Right into a section lands on its first control (a hidden stop hands the highlight to the first control in reading order). Back at the top shows "Press Back again to exit" and exits on a second press within 2.5 s. Back from a channel or title opened from Search returns to that result. | S7 Back: right row in **11/11** sections (was 0/11: it opened the menu). Tests cover each rule. |
| 2.4 Scroll by key (NAV-13) | Both left-hand lists find a category by the item keys the list itself uses; the three counted header offsets are gone. | Existing Back/Left layer tests pass. |

**Found and fixed along the way:**
- Programmes that overlap in a guide were laid out one after another, pushing the row past the screen edge, where Right couldn't reach them. Each now starts where the one before ends.
- The title page's "put the highlight back" loop compared wall-clock time with uptime, so a key press never stopped it.
- An early version of the Settings entry stop could be asked for focus after the list had dropped it, which crashed the hold-buttons test; it's now only used while on screen.

**Decisions taken without asking (open questions G-4 and G-5):**
- Back at the top asks for a second press; it doesn't exit at once, and there's no confirmation dialog.
- Back after a jump from Search returns to the results.

Both are easy to change.

**Still open from phase 2:**
- FOCUS-02, checking that every dialog returns focus to its opener (needs a harness check per dialog).
- Restoring position after the app is killed (the store is in memory only).
- NAV-08 (Settings sections still preview on highlight) and NAV-09 (wrap-around policy) are waiting on G-2 and G-8.

**Measured:** harness run 43062052674 (`audit/results/harness-run-43062052674-navigation.txt`). Memory over 50 deep returns is flat (110–112 MB) and the end-of-run heap is 111 MB. All 67 CI tests pass.

### Nov 11 — decisions G-2 and G-8 (on `next`, not yet published)

- **Settings sections open on OK** (NAV-08). Highlighting a section only shows its summary; OK opens it and moves into its first control. Left and Back still return to the open section's row.
- **Wrap-around** (NAV-09, G-2): only the guide's category list, the Movies/Series category lists and the guide's channel list wrap, and only on a fresh press, never while holding. That was already the case; `onlyCategoryAndChannelListsWrapAround` now guards it. The main menu, the Settings section list and the poster grid stop at the end.
