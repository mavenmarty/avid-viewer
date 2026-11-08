# Avid Viewer

An IPTV player for Android TV and Fire TV. Bring your own provider (Xtream Codes login or M3U link). The app includes no channels.

## Version 1 features
- Playlists: Xtream Codes (server, username, password) or M3U link, with an optional XMLTV guide link. Passwords are encrypted with the Android Keystore.
- Live TV: channel groups, channel list with what's on now, and a live preview window.
- TV Guide: timeline grid with a preview window. Channel Up and Channel Down (or Page Up and Page Down) move one page at a time.
- Full screen: Up and Channel Up go to the previous channel, and Down and Channel Down go to the next one. Switching stays inside the current group or Favorites and wraps from the end of the list to the top. OK shows the info bar, Menu adds or removes a favorite, and Back returns to the previous screen.
- Favorites: press Menu (or hold OK) on a channel to add or remove it.
- Manage: rename, reorder or hide groups and channels.
- Settings: show or hide the clock, time zone, stream format (TS or HLS), clear cache on exit, and pause or clear watch history.
- All data stays on the device. Cloud backup and device-to-device transfer are turned off.

## Building
Every push to `main` builds a signed APK with GitHub Actions and publishes it under **Releases**.
Latest APK: `https://github.com/mavenmarty/avid-viewer/releases/latest/download/AvidViewer.apk`

Every version is signed with the same key, which comes from two repository secrets: `SIGNING_KEYSTORE_B64` (the key file, base64-encoded) and `SIGNING_PASSWORD`. The key is never stored in the repo. Keep a copy of the key file and password somewhere safe: Android only installs an update over the old version when both are signed with the same key.
