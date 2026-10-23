# TinyKit

[![Release](https://img.shields.io/github/v/release/Order52/tinykit?label=release)](https://github.com/Order52/tinykit/releases/latest)
[![Android 7+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](#requirements)
[![APK size](https://img.shields.io/badge/APK-100%20KB-blue)](https://github.com/Order52/tinykit/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)

**Small but useful Android tweaks in one clean menu, powered by [Shizuku](https://shizuku.rikka.app/).**
No root, no ads or tracking, nothing running in the background. 100 KB.
Pure black theme, with everything one tap away in the bottom bar: Shizuku, Privacy, Apps, System, Info.
A search bar on the home screen jumps straight to any tool.

## Features

**Shizuku**
- **Start** and **Stop** Shizuku from TinyKit. With Shizuku's auth token set (from Shizuku's
  settings), both go straight to Shizuku, and Stop works with its Watchdog. Without it, Start uses
  Termux with [shizuku-termux](https://github.com/Order52/shizuku-termux).
- **Search** across every TinyKit setting and tool from the top of the home screen.

**Quick Settings tiles** — add any from *System → TinyKit tiles* (one tap on Android 13+):
- Shizuku on/off · Screenshot · Screen record · Caffeine (keep the screen on)
- Developer options · USB debugging · Hide developer options · Sensors off
- Grayscale · Extra dim · Ad-block DNS · Mic & camera off
- Screen timeout cycle · Clear caches · Free RAM · Close the app on screen

**Privacy**
- **App permissions** per app, each set to **Real**, **Fake** or **Off**. *Fake* shows the app
  the permission as granted while it actually gets nothing (the app-op is set to ignore) — plus the
  usually-hidden app-ops like clipboard and background access.
- **Analytics & telemetry**: switches to turn off Huawei's User Experience Programme, its
  background diagnostics apps and HiCloud, and to limit Google ad personalisation. Your Huawei ID
  sign-in is never touched.
- **All sensors off**: block every sensor for all apps — motion, microphone and camera — like the
  hidden developer "Sensors off" tile. One toggle, nothing running in the background.
- **Saved Wi-Fi networks**: see saved passwords and share any network with a QR code. Searchable.
- **Hide developer options**, **Ad-blocking DNS**, **Stop background scanning**,
  **Block camera & mic** per app.

**Apps**
- **Default apps**: set the launcher, browser, phone, SMS and assistant.
- **Hide apps** from the launcher · **Unused apps** (longest-unused first) · **Storage by app**.
- **Recent access**: who used the camera, mic, location, contacts or clipboard, and when.
- **Mixed audio**: let apps play audio at the same time, or mute an app — app-op changes the
  audio system enforces, so nothing runs in the background.
- **Running apps** (stop any) · **Stop background activity** · **Keep apps alive** ·
  **Back up app as APK**.

**System**
- **Edit quick panel**: reorder, add and remove tiles, laid out like the real panel.
- **Run a command** as the shell user through Shizuku.
- **Recover deleted files**: scans every recycle bin and trash location (Gallery recycle,
  `.File_Recycle`, `.fvTrash`, Android `.trashed-*`, thumbnail caches), shows thumbnails and
  restores to `Download/TinyKit/Recovered`.
- **Settings backup & restore**: save all system settings to a file and put them back.
- **Force dark on all apps**: dark-themes apps that have no dark mode (Android's force-dark),
  with nothing running in the background.
- **Clean & speed**: Clear all app caches, Optimize apps, Trim storage.
- **Network & battery**: Free hotspot, Mobile data off on Wi-Fi.

**Info & logs**
- **Phone info**, **Network info**, **Charging info**, **Temperatures** (every thermal sensor),
  **Device IDs**.
- **Battery usage**, **Wake-ups**, **Vibration log** — all shown as plain, readable lists.
- **App log**, **Crash log**, **Check for updates**, **About**.

Everything is a normal system setting or app-ops change, so it can be undone from the same screen.

## Requirements

- Android 7.0 or newer.
- Shizuku: [thedjchi's fork](https://github.com/thedjchi/Shizuku/releases/latest) (recommended)
  or the [original](https://shizuku.rikka.app/download/).
- To start Shizuku from the phone with the **Start Shizuku** button: Termux with
  [shizuku-termux](https://github.com/Order52/shizuku-termux) installed. The first time, allow
  *Run commands in Termux environment*.

## Install

Download `TinyKit.apk` from [Releases](https://github.com/Order52/tinykit/releases), open it,
then tap **Allow** when TinyKit asks to use Shizuku.

## Notes

- **Fake permissions** work by granting the permission but setting its app-op to *ignore*, so the
  app believes it is granted while getting empty results. Camera, microphone and location are
  controlled at the UID level on Android 12+; *Off* there revokes the permission outright.
- **Recover deleted files** copies out of the recycle bin; the original stays in place. On this
  Huawei, Gallery's recycle bin names files as Base32 of their metadata — TinyKit decodes the real
  file name for you.
- **Hide apps** uses `pm disable-user`: the app leaves the launcher and stops running until you
  bring it back here. (On EMUI this is the only method that works.)
- **Screen record** needs the system `screenrecord` command, which some Huawei builds don't ship;
  use the phone's built-in recorder there.
- Ad-blocking DNS uses `dns.adguard-dns.com`. Some captive Wi-Fi portals need it turned off to log in.
- Huawei hides most apps' own log output, so **App log** may be empty there.

## Building

```sh
export JAVA_HOME=/path/to/jdk21
./gradlew assembleRelease
```

Without a `keystore.properties` file the release APK is signed with your debug key.

## Credits

Made by Ali Alakbar.

## License

MIT
