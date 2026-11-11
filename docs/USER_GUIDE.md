# Avid Viewer — User Guide & Release Notes

<!-- The version below and the Release notes section are updated automatically after each published build
     (.github/workflows/user-guide.yml). Feature descriptions are written by hand. -->
This guide covers **Avid Viewer 1.0.94**, the current public release. Avid Viewer is an IPTV player for Android TV and Fire TV. You bring your own provider (an Xtream Codes login or an M3U link); the app doesn't include any channels.

**Contents:** [Getting started](#1-getting-started) · [Main menu](#2-main-menu) · [TV Guide](#3-tv-guide) · [Full-screen playback](#4-full-screen-playback) · [Search](#5-search) · [Discover](#6-discover) · [Sports](#7-sports) · [Movies and Series](#8-movies-and-series) · [Settings](#9-settings) · [Privacy](#10-privacy) · [Troubleshooting](#11-troubleshooting) · [Release notes](#release-notes)

## 1. Getting started

### Install

1. On your TV box, download **AvidViewer.apk** from the project's GitHub Releases page (the latest is always at `github.com/mavenmarty/avid-viewer/releases/latest`).
2. Open the file and install it. You may need to allow installs from unknown sources for your browser or file manager.
3. Every version is signed with the same key, so a new version installs over the old one and keeps your playlists, favorites and settings.

### Add your first playlist

1. Open **Settings › Playlists** and choose **Add playlist**.
2. Pick the type:
   - **Xtream Codes**: enter a playlist name, the **Server (DNS)** (for example `http://example.com:8080`), your **Username** and **Password**. Live TV and the TV guide load from your login.
   - **M3U link**: enter a playlist name, the **M3U playlist link** and, optionally, a **TV guide (XMLTV) link**.
3. Choose which content to load (Live TV, Movies & series), then press **Add playlist**.

Your password is stored encrypted on the device (Android Keystore) and is never shown again.

### Using the remote

- **Arrows** move the highlight. **Hold an arrow** to speed up (3, then 6, then 12 items per repeat).
- **OK** selects. Text boxes open the keyboard only when you press OK on them.
- **Menu** (or **hold OK**) opens options for the highlighted item.
- **Back** goes up one level. Pressing Back on the full menu while on the TV Guide exits the app.
- **Channel Up / Channel Down** (or Page Up / Page Down) move a page at a time in the TV Guide and change channels in full screen.
- **Lists wrap around.** In channel and category lists, a single press past the last item goes to the first, and the reverse. Holding a button stops at the end, so a fast scroll doesn't fly past.
- If a press ever finds nothing highlighted, the app puts the highlight back.

## 2. Main menu

The menu on the left lists **Search, Discover, TV Guide, Sports, Movies, Series** and **Settings**. Picking a screen highlights its first item. Once you've chosen a screen, the menu shrinks to icons, and the labels come back when you move onto it.

At the bottom of the menu you'll see:

- The **clock and date** (if Show clock is on).
- **Current weather** (if a postal code is set and Show weather is on).
- An **Update** button when a new version is available.

The app opens on the TV Guide at the last channel you watched.

## 3. TV Guide

The TV Guide is the home screen for live TV. It has three levels, and Back walks up through them:

1. **Main menu.**
2. **Playlist tree**: ★ Favorites, your own lists, then every playlist with live TV switched on. Press OK on a playlist to expand it into its categories.
3. **Category view**: the menu is hidden and the playlist's categories become the left navigation, with **‹ Menu**, ★ Favorites, your lists and **＋ New list** at the top.

### The guide grid

- The top shows a **preview window** and details of the highlighted show: time, title, "New" tag and description.
- Below it is a **timeline grid**: two-line channel names on the left, programs across a 2.5-hour window, with a line marking "now".
- **Left/Right** move between programs. Going past the last program in view scrolls the guide forward by half hours, up to **12 hours ahead**. Left on the first program scrolls back, and at the start it moves to the channel name.
- **Up/Down** move one channel and land on the program airing at the same time, so the highlighted time stays put as you move between rows.
- **Up on the first channel** moves to the preview window; **Up again** jumps to the last channel. **Down on the last channel** goes back to the first.
- **CH+/CH−** (Page Up/Down) move a full page and wrap around at the ends.
- **OK** on a channel or program starts it in the preview. **OK again**, or OK on the preview window, opens it full screen.
- Choosing a new category starts at the top, with its first channel highlighted. Coming back to the same category keeps your place.
- **Back from full screen** returns to the channel you were watching, even far down a long category.
- In the category list, **Up on ‹ Menu** jumps to the last category, and **Down on the last category** returns to ‹ Menu.

### Search in the guide

Use the **Search channels and programs** box above the grid. Results show **Channels** (with what's on now, and **▶ Preview** / **▶ Full screen**) and **Programs** on now or later (**▶ Tune in** / **▶ Channel**). Search covers every playlist. **✕ Clear** empties the box.

### Channel options (Menu or hold OK on a channel)

- **＋ Add to List…**: add it to ★ Favorites or one of your lists, or make a **New list**. The menu shows which lists it's already in.
- **− Remove from [list]**: when you're viewing Favorites or a list.
- **✎ Rename**: give the channel your own name.
- **⇅ Re-order**: move the channel within its category (see *Re-ordering* below).
- **⊘ Hide channel**: hide it everywhere. You can bring it back in Settings › List Editor.

### Category options (Menu on a category in the left navigation)

- **✎ Rename**, **⇅ Re-order**, **⊘ Hide category**.
- **🏆 Add to Sports tab / Remove from Sports tab**: show this category's channels on the Sports screen.

### Re-ordering

When you choose Re-order, a help panel appears at the bottom of the screen:

- **▲ ▼** move the item up or down (hold to move faster).
- **◀** moves it to the top; **▶** moves it to the bottom.
- **OK or Back** finishes, and the new order is saved.

While you're moving it, the item has a blue outline. A category shows its position (for example "4/27") and a channel shows ⇅ in place of its number. Channels move within whatever the guide is showing (a category, ★ Favorites or one of your lists), and channel numbers follow the new order.

### Favorites and your lists

- **★ Favorites** gathers channels from every playlist in one place and is always shown.
- **Your lists** (for example "Kids" or "News") can also hold channels from any playlist. Make one with **＋ New list** or from Add to List.
- Press **Menu on a list** to **Rename**, **Move up / Move down** or **Delete** it. Deleting asks you to confirm; the channels stay in your playlists.
- **Suggested near [your town]**: at the top of Favorites, the app suggests channels from your playlists that look local to your postal code. Press **＋ Add to List** to keep one, or **Hide suggestions**. Dismissed channels aren't suggested again unless you restore them in Settings.

## 4. Full-screen playback

### Live TV

- **Up / Channel Up**: previous channel. **Down / Channel Down**: next channel. Switching stays inside the current category, list or Favorites and wraps around at the ends.
- **OK, Left, Right or Info**: show the info bar (channel number and name, ★ if it's a favorite, playlist, what's on now with times, and **Up next**). It hides after 5 seconds.
- **Menu**: open **Add to List** for the channel you're watching.
- **Back**: return to the screen you came from.

### Movies and episodes

- **Left / Rewind**: back 10 seconds. **Right / Fast-forward**: ahead 30 seconds.
- **OK / Play-Pause**: pause or resume.
- **Up, Down or Info**: show the progress bar.
- The progress bar appears when playback starts or you press a button, and hides 5 seconds after the last press. It shows "Loading…" while the video starts, and stays up while paused ("Paused · …").
- Stopping part-way saves your place for **Resume**.

## 5. Search

The **Search** screen searches **every playlist** at once: TV stations, programs in the TV guide (now and upcoming), movies and series. Results are grouped by playlist and by kind, each with an action:

- **Tune in** for a program, **Play** for a movie, **Episodes** for a series (choose a season and episode).
- **+ Add to List** for a channel (shows **✓ In a list** once added).
- **+ My List** for a movie or series (shows **✓ My List**).

## 6. Discover

Discover shows what's new and popular, and where to watch it, using TMDB and JustWatch data.

- **Tabs**: New, Trending, For you, In theaters.
- **My services**: only show titles on the streaming services you've picked.
- Titles your provider carries are tagged **▶ ON YOUR PROVIDER**. Open one to **Play on your provider** (or see **Episodes**), add it to **My List**, or **Search my playlists**.
- **Where to watch** lists Stream, Free, Rent and Buy options for your region.

Discover needs a free **TMDB API key**. Enter it in **Settings › Discover**; it's stored encrypted on the device.

## 7. Sports

The Sports screen finds games and events in your TV guide.

- **Categories** across the top: All, plus your Sports categories. The built-ins are NFL, NHL, NBA, MLB, NCAA, ESPN+, PPV and Soccer.
- Events are grouped into **Live now**, **Upcoming** and **Final**, with times in your time zone and scores where available (from ESPN's public scoreboard).
- The **preview window** shows the selected event, tagged LIVE, FINAL or STARTS [time]. Choose **Watch full screen**. If the event is on more than one channel, the screen tells you how many.
- To change what appears here, use **Settings › Sports tab** or press Menu on a category in the TV Guide.

## 8. Movies and Series

- **Left side**: Playlists, then categories: **All**, **Recently added**, **Continue watching**, **My List**, and your provider's categories.
- **Right side**: posters for the chosen category, with a search box (**Search movies** / **Search series**).
- **OK on a poster** opens its page: plot, cast, director, **▶ Play**, or **▶ Resume from [time]** and **Start over**, plus **+ My List**. Series pages list episodes by **Season**, with resume points. From the page's buttons, **Down** goes to the season buttons, then to the episodes; **Up** from the first episode goes back to the seasons. After you watch an episode and press **Back**, that episode is highlighted.
- **Category list wraps around**: Up on **All** jumps to the last category, and Down on the last returns to All.
- **Menu on a category**: **Rename**, **Re-order** or **Hide** it. Hidden categories come back in Settings › List Editor.
- If you have more than one playlist, movies and series from all of them are merged.

## 9. Settings

Settings has sections on the left; highlighting one shows its options on the right.

### Playlists

- The list of your playlists (Up/Down to choose, Right for options) and **Add playlist**.
- For each playlist:
  - **✎ Edit**: change its details. If you change the server, login or links, its channels and guide are downloaded again, and you're asked to confirm first.
  - **↻ Refresh**: replace the saved channels, guide, movies and series with what your provider has now.
  - **✕ Remove**: delete the playlist and its login from the device. To keep it for later, turn it off instead.
  - Switches: **Playlist on** (off hides it everywhere without deleting anything), **Live TV & guide**, and **Movies & series**.
- **Details** shows the type, server, username, playlist link, TV guide source and status.

**Background downloads.** About 10 seconds after the app opens, it updates each switched-on playlist in turn, fetching only what's out of date: channels older than a day, the guide older than 8 hours, and movies & series older than a day. Content that's switched off is never loaded. The first download of a large movie catalog can make a Fire TV Stick feel slower for a minute or two.

### List Editor

Show or hide items one at a time with OK, or many at once with the filter and **Show all / Hide all**. It has four tabs:

- **Live TV**: categories by playlist, then the channels in each category.
- **Movies** and **Series**: categories and titles.
- **☰ My lists**: show or hide your lists in the TV Guide. ★ Favorites is always shown.

Rename, re-order and Add to List are done with Menu on the item itself in the TV Guide, Movies or Series.

- Headers show counts, for example "EN - MOVIES 4K · 1105 · 222 hidden". With a filter typed, Show all / Hide all act only on the matches (for example "Hide 222").
- Hidden movies, series and categories also disappear from search.
- A hidden list keeps its channels. Add to List marks it "(hidden)".

### Location & weather

- **Postal code**: a US ZIP or Canadian postal code. It's used for weather, local channel suggestions and the Auto time zone. Only the code is sent, to Open-Meteo.
- **Show weather**: current conditions under the clock, refreshed every 30 minutes.
- **Suggest local channels**: on or off. **Show them again** restores suggestions you dismissed.
- **Temperature units**.

### Display & time

- **Show clock**: time and date in the menu and in the full-screen info bar.
- **Time zone**: Auto (from your postal code), Device, Eastern, Central, Mountain, Arizona, Pacific, Alaska, Hawaii or Atlantic. Guide and program times use this zone.

### Sports tab

- Turn categories on or off and set their order (the order of the filters on the Sports screen).
- **Remove** a built-in category, or bring it back with **Restore all**.
- **Add your own category by words**: give it a name and match words, separated by commas.
- **Add a category from your playlist**: everything on that category's channels shows on the Sports screen.

### Discover

- **TMDB API key**: Save or Remove it.
- **My streaming services**: pick the services you subscribe to.

### Playback

- **Stream format (Xtream)**: **MPEG-TS (.ts)** or **HLS (.m3u8)**. If channels won't play, try the other format. The change applies after a Refresh in Playlists.

### Privacy & analytics

Everything here stays on the device; nothing is uploaded or backed up to the cloud.

- **Pause watch history**: stop recording what you watch.
- **Your viewing, last 7 days**: hours watched and your top channels.
- **Clear watch history** (the app keeps up to 90 days).

### Storage

- **Clear cache on exit**: empty the cache (logos, posters, recently streamed video) every time you leave the app.
- **Cache size** and **Clear cache now**. Clearing never removes your playlists, favorites, lists, guide, history or settings.

### Updates & about

- **App updates**: the installed version, **Check now**, **Download and install**, and **Auto-check** on/off. When an update is found, the app asks before installing (**Update now** / **Later**) and never interrupts full-screen viewing.
- **Last crash**: if the app closed unexpectedly, the details are kept here on the device (nothing is sent anywhere), with **Clear crash report**.
- **Credits**: TMDB, JustWatch, Open-Meteo and ESPN.

#### Backup & restore

A backup saves your playlists (with their logins), ★ Favorites and your lists, renamed and re-ordered channels and categories, hidden items, Sports categories, location, the TMDB key, network-share settings, all other settings, watch history and resume points. Channels, guides and movie catalogs aren't included; they download again after a restore.

Because it holds your provider logins, every backup is locked with a **PIN** you choose (4 or more digits). The PIN can't be recovered if you forget it.

**Back up to this device**

1. Choose **Back up now**, enter a PIN twice, then **Save backup**.
2. The file is saved to **Download › AvidViewer**, with a name like `AvidViewer-backup-2026-11-09_2015.avbak`. It stays there even if you delete the app.
3. On Android 9 and older, the app first asks for permission to save to the Download folder.

**Restore**

1. Choose **Restore from a backup**. This opens the device's file picker, or a file manager app if one is installed. If neither exists, the app lists the backups it finds in the Download folder.
2. Check the confirmation, which shows when the backup was made and which playlists it holds. Restoring replaces the playlists and settings on this device and can't be undone.
3. Enter the backup's PIN. A wrong PIN is refused and nothing changes. After a restore, channels and guides start downloading again.

**Network share (SAMBA)**

Keep backups in a shared folder on a PC or NAS, so a reinstalled app or a new TV can restore without a file manager or USB drive.

1. Enter the **share address**, for example `\\192.168.1.20\Backups\AvidViewer` (`\\NAS\Backups` and `smb://server/share/folder` also work). The folder is created if it's missing.
2. Enter a **user name** (`DOMAIN\user` works) and **password**. Leave both empty for a guest share.
3. Choose **Save and test**. The app connects and tells you how many backups are already there.
4. **Back up to the share** asks for a PIN, then saves the file there.
5. **Restore from the share** lists the backups, newest first. Pick one, enter its PIN and confirm.

The share password is stored encrypted on the TV and is included in your PIN-locked backups.

**Moving to another TV:** copy the `.avbak` file across (network share, USB drive or file manager), then restore it on the new TV.

## 10. Privacy

- All your data stays on the device. Android cloud backup and device-to-device transfer are turned off.
- Provider passwords and the TMDB key are encrypted with the Android Keystore.
- Backup files are encrypted and locked with your PIN, because they contain provider logins.
- Outside services get only what they need: your postal code (Open-Meteo, for weather), title lookups (TMDB/JustWatch, if you add a key), scoreboards (ESPN) and update checks (GitHub).

## 11. Troubleshooting

- **Channels won't play**: switch the Stream format in Settings › Playback, then Refresh the playlist.
- **No channels or guide**: check that the playlist, and its Live TV switch, are on in Settings › Playlists, then choose Refresh.
- **No movies or series**: turn on Movies & series for the playlist and Refresh.
- **Discover is empty**: add a TMDB API key in Settings › Discover, or turn off "My services".
- **Sports is empty**: wait for the TV guide to load, or adjust your categories in Settings › Sports tab.
- **A channel or category disappeared**: it may be hidden. Check Settings › List Editor.

### Backup questions

**After reinstalling on a newer Fire TV, the app can't see my backup.** On Fire OS 8 (Android 11), a reinstalled app may not be allowed to see a backup the old install saved, and some Fire TVs have no file picker. The app suggests installing a free file manager such as **X-plore**, which lets you pick the file. Backing up to a network share avoids the problem altogether.

**The network share won't connect.**

- Use the PC's or NAS's **IP address** if the TV can't find it by name. The app says "try the IP address" when this is the problem.
- Make sure the user has permission to **change** files in the shared folder, not just read them.
- Avid Viewer supports SMB 2 and 3 (Windows 10/11 and most NAS boxes). Devices that only offer SMB 1 won't connect.
- Other errors are explained in plain words: wrong password, no share by that name, no permission to write, or server not reachable.

**I forgot my backup PIN.** It can't be recovered. Make a new backup with a new PIN.

### Reliability

Every build runs automated usability tests before it's released: the real app screens on a TV-sized screen, driven by simulated remote presses. They cover every screen and Settings section, re-ordering, List Editor bulk actions, search, hold-to-scroll, backups (including a real network share) and a 600-press random-key crash hunt. A failing test stops the release.

---

## Release notes

Every public release since 1.0.35, newest first. Releases are published on GitHub (`mavenmarty/avid-viewer`) as `AvidViewer.apk`. The app offers each new version itself, or you can check in Settings › Updates & about › **Check now**.

<!-- new-releases: entries for new builds are added automatically below this line -->

### 1.0.94 — November 10, 2026, 7:27 PM CT (current)

**Change log**

- Layers: main menu always visible (icons), Back and Left step back one layer, landing on the current category
- TV guide management: refresh every 4/8/12/24 h, guide sources per playlist, match channels by hand; Add to List and Rename close on the action
- Re-order help sits over the preview and title area; test fixes
- Test: pick the list row inside the picker
- Test: use a channel not yet in the list
- Fix favorites one channel off after restoring a backup: stable M3U channel keys
- Tests: stop background work after tests that create the app outside the UI harness

### 1.0.86 — November 10, 2026, 5:42 PM CT

**Change log**

- Settings › Updates & about: Report a bug (bugs@avidviewer.app, device details, QR code to start the email on a phone)
- Fix build
- In-app update: resume stalled downloads, verify the file, installer fallback

### 1.0.82 — November 10, 2026, 3:45 PM CT

**Change log**

- Fix #1: Discover crash on Fire TV — stream catalog files, build provider lookup off the main thread

### 1.0.80 — November 10, 2026, 9:39 AM CT

**Release notes**

- **Lists wrap around:** Down on the last channel or category jumps to the top; Up on the first jumps to the bottom. Holding a button stops at the end. In the TV Guide, Up on the first channel still goes to the preview window first.
- **Series episodes fixed:** you can move from the episodes back up to the season buttons, and after watching, the highlight returns to the episode you watched.
- **TV Guide keeps your place:** Back from full screen returns to the channel you were watching, even far down a long category. Choosing a category reliably highlights its first channel.
- **Navigation lock-ups fixed:** holding an arrow on a slow device (such as a Fire TV Stick) no longer piles up moves and freezes the screen. If a press ever finds nothing highlighted, the app puts the highlight back.
- **Full-screen bar for movies and episodes** hides again a few seconds after you press a button; it stays up only while paused.

**Change log**

- Wrap-around in channel and category lists (a fresh press only; holding stops at the ends)
- Series: Up from the first episode reaches the seasons; Back from the player highlights the episode that played
- TV Guide: Up from the first channel reaches the preview, Up again wraps
- TV Guide and series page: keep placing the highlight until it lands (fixes Back from full screen and newly chosen categories)
- Held-key throttle (late or bunched repeats are dropped) and focus rescue when a key reaches nothing
- Full-screen bar for movies and episodes hides once playing
- New usability tests for each reported bug (series episodes by remote, Back from full screen, wrap-around, holding buttons on every screen), with a fake provider server

### 1.0.67 — November 9, 2026, 8:35 PM CT

**Release notes**

- **New minimalist icon:** a white eye whose pupil is a play button, on blue, with a matching banner on the Fire TV / Android TV home row.
- **Backup & restore** in Settings › Updates & about: a PIN-locked file of your playlists (with logins), lists and settings, saved to the Download folder, where it stays even if the app is deleted.
- **Network share (SAMBA) backups:** save to and restore from a shared folder on a PC or NAS.

**Change log**

- New minimalist app icon (eye with play-button pupil) and matching TV banners
- Backup & restore in Settings › Updates & about (PIN-locked file in Download; restore via file picker or the Download folder); tests
- Backups on a SAMBA network share (SMB 2/3): save, list, restore; tested against a real Samba server in the build

### 1.0.61 — November 9, 2026, 7:10 PM CT

**Release notes**

- **Hold to scroll faster:** holding an arrow speeds up scrolling in every list and grid (3, then 6, then 12 items per repeat). Full screen still changes one channel per press.
- **Re-order in place:** re-order categories and channels from the Menu key, with on-screen instructions.
- **List Editor redesign:** tabs for Live TV · Movies · Series · My lists. It's now only for showing and hiding, and you can hide individual movies and series too. Rename and Re-order moved to the Menu key on the item itself.
- **Movies and Series categories:** Menu offers Rename, Re-order and Hide, the same as TV categories.
- **Crash reports:** after an unexpected close, the app tells you on the next launch and keeps the details on the device.
- **Reliability fixes** found by the new automated usability tests: the highlight could drop off a moving item after a big jump, the first-item highlight could fail on slow devices, and TV Guide rows lost their highlight when moved.

**Change log**

- Hold an arrow key to scroll faster (3, 6, then 12 items per repeat); the guide keeps the highlighted time
- Re-order categories and channels in place from the Menu key (Up/Down, hold to speed up; Left = top; Right = bottom) with on-screen help
- Usability tests (Robolectric + Compose UI) and on-device crash reports; List Editor layout A; Movies/Series category Menu
- TV Guide rows keyed by channel so a moved row keeps its highlight
- Keep the moving item highlighted after jumps; a key press cancels a late automatic highlight
- First-item highlight retries each frame and confirms focus

### 1.0.45 — November 9, 2026, 5:35 PM CT

**Release notes**

- **Faster on Fire TV Stick:** content that's switched off is never loaded, and movies load only when you open Movies, Series or Discover.
- **No more "active playlist":** search covers every playlist, Movies and Series combine titles from all of them, and every switched-on playlist stays downloaded in the background.
- **TV Guide:** scrolls up to 12 hours ahead, Up/Down keeps the time you're on, and channel names no longer widen when highlighted.
- **Readable text everywhere:** long text wraps instead of being cut off.
- **Built-in Sports categories** can be removed and restored.
- **Settings menu** shrinks to icons when you move into a section.
- **Picking a screen from the left menu** highlights that screen's first item.
- **My lists in the List Editor.** *(From 1.0.61 this page only shows and hides lists; rename, re-order and delete are on the Menu key in the TV Guide.)*

**Change log**

- Performance: skip live/VOD of playlists where they're off; load VOD only when a VOD screen needs it
- Search every playlist: keep all playlists downloaded in the background; guide search across playlists; merged Movies/Series; no "active" playlist in the UI; no channel-name flyout
- Readability: wrap descriptions, flow button rows, roomier List Editor, clearer Storage; removable built-in Sports categories; Settings menu shrinks to icons
- Time zone buttons wrap instead of overflowing
- TV Guide: scroll up to 12 hours ahead; Up/Down keeps the highlighted time across rows
- List Editor › My lists: show/hide, rename, re-order and remove lists
- Picking a screen in the left menu highlights that screen's first item

### 1.0.37 — November 9, 2026, 2:42 PM CT

**Release notes**

- **Settings › Channels becomes the List Editor:** show or hide categories (by playlist) and channels (by category), one at a time or in bulk with a filter. *(Redesigned in 1.0.61.)*
- **Hiding a category now applies to its own playlist only.** Before, it hid a same-named category in every playlist. Categories hidden before this release stay hidden everywhere until you show them once in the List Editor.

**Change log**

- List Editor: bulk show/hide categories by playlist and channels by category

### 1.0.35 — November 9, 2026, 2:08 PM CT

**Release notes**

- **Settings › Playlists rework:** a three-column layout with Edit, Refresh and Remove, plus on/off switches for each playlist.
- **Per-playlist content switches:** turn a playlist off entirely, or load only Live TV or only Movies & series.
- **Confirmations:** Refresh, Remove and saving an edit each ask first. Cancel is highlighted by default.
- **Local channel suggestions** near your postal code at the top of ★ Favorites.
- **Channel lists:** "Add to Favorites" becomes **Add to List**, so you can make your own lists alongside Favorites.
- **Preview windows:** move onto the preview and press OK to watch full screen.
- **Category scrolling:** a newly chosen category starts at the top; the same category keeps your place.

**Change log**

- Playlists settings: list, actions and edit form; on/off, Live TV and VOD switches; confirmations
- Local channel suggestions from the postal code in Favorites (skips favorites, dismissible)
- Channel lists (Add to List, new lists, list options); OK on preview windows opens full screen
- Close channel options when the list picker opens
- A new category starts at the top; the same category keeps its scroll (guide, movies, series)
