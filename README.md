# TinyKit

[![Release](https://img.shields.io/github/v/release/Order52/tinykit?label=release)](https://github.com/Order52/tinykit/releases/latest)
[![Android 7+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](#requirements)
[![APK size](https://img.shields.io/badge/APK-36%20KB-blue)](https://github.com/Order52/tinykit/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)

**Small but useful Android tweaks in one clean menu, powered by [Shizuku](https://shizuku.rikka.app/).**
No root, no internet permission, nothing running in the background. 36 KB.

## Features

**Start Shizuku**
- The Shizuku card at the top has a **Start Shizuku** button that starts it without a
  computer, through Termux and [shizuku-termux](https://github.com/Order52/shizuku-termux).

**Privacy**
- **Hide developer options**: apps see Developer options as off; adb and Shizuku keep working.
- **Ad-blocking DNS**: AdGuard Private DNS blocks ads and trackers in every app. Turning it off
  restores your previous Private DNS setting.
- **Stop background scanning**: Wi-Fi and Bluetooth stop scanning while they're off.
- **Block camera & mic** per app, even if the app has the permission.

**Apps**
- **Running apps**: memory use of everything running; stop any app.
- **Stop background activity** per app: it can't run or keep the phone awake in the background.
- **Keep apps alive** per app: exempt from battery optimization, so Android doesn't kill it.
- **Back up app as APK** to `Download/TinyKit` (split APKs are saved as a folder).

**Clean & speed**
- **Clear all app caches**, **Optimize apps** (background compilation), **Trim storage**.

**Network & battery**
- **Free hotspot**: hotspot uses your normal data connection (works on many carriers).
- **Mobile data off on Wi-Fi**: saves battery.

**Info & logs**
- **Phone info**: battery health, temperature, voltage, capacity and cycles (when the phone
  reports them), memory, storage and system details.
- **Crash log**: why an app crashed or closed by itself.

Everything is a normal system setting or app-ops change, so it can be undone from the same screen.

## Requirements

- Android 7.0 or newer.
- [Shizuku](https://shizuku.rikka.app/download/).
- To start Shizuku from the phone with the **Start Shizuku** button: Termux with
  [shizuku-termux](https://github.com/Order52/shizuku-termux) installed. The first time, allow
  *Run commands in Termux environment*.

## Install

Download `TinyKit.apk` from [Releases](https://github.com/Order52/tinykit/releases), open it,
then tap **Allow** when TinyKit asks to use Shizuku.

## Notes

- Hide developer options only changes the flag apps read. Apps that check whether USB debugging
  itself is on will still notice.
- Ad-blocking DNS uses `dns.adguard-dns.com`. Some captive Wi-Fi portals (hotels, airports) need
  it turned off to log in.
- Stop background activity can delay notifications from the apps you pick, so leave messengers
  unchecked.

## Building

```sh
./gradlew assembleRelease
```

Without a `keystore.properties` file the release APK is signed with your debug key.

## Credits

Made with the assistance of AI.

## License

MIT
