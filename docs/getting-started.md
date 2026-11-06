# Getting started

This guide takes you from nothing installed to TinyKit running in about five minutes.

## Contents

- [What you need](#what-you-need)
- [1. Install Shizuku](#1-install-shizuku)
- [2. Start Shizuku](#2-start-shizuku)
- [3. Install TinyKit](#3-install-tinykit)
- [4. First run](#4-first-run)
- [Starting Shizuku from TinyKit](#starting-shizuku-from-tinykit)
- [What works without Shizuku](#what-works-without-shizuku)
- [Finding your way around](#finding-your-way-around)

## What you need

| | |
| --- | --- |
| **Phone** | Android 7.0 (API 24) or newer. No root. |
| **Shizuku** | [thedjchi's fork](https://github.com/thedjchi/Shizuku/releases/latest) (recommended) or the [original](https://shizuku.rikka.app/download/) |
| **A way to start Shizuku** | TinyKit's **Start** button on Android 11+ (it pairs with Wireless debugging itself), or a computer with `adb` |

## 1. Install Shizuku

[Shizuku](https://shizuku.rikka.app/) runs a small background service with the same privileges
as `adb shell`. TinyKit sends its commands through that service, which is why it needs no root.

We recommend **[thedjchi's fork](https://github.com/thedjchi/Shizuku/releases/latest)**. Its
*Watchdog* restarts Shizuku if Android kills it, and it supports start and stop *intents* with an
auth token, which TinyKit can use (see [below](#with-the-auth-token)). The standard Shizuku from
[shizuku.rikka.app](https://shizuku.rikka.app/) works too, including TinyKit's **Start** and
**Stop** buttons (see [below](#with-the-standard-shizuku-app)).

## 2. Start Shizuku

Shizuku has to be started again after every reboot. Choose whichever method suits you:

### Option A: TinyKit's Start button (Android 11+, nothing else to install)

Tap **Start** on the Shizuku tab (or use the Shizuku tile). TinyKit starts Shizuku itself through
Wireless debugging, with its own adb client. The first time it needs one thing Android won't let
an app do alone: a **pairing code**. TinyKit opens Developer options for you (turn on *Developer
options* first if they're off: *Settings → About phone*, tap *Build number* seven times), you
switch on **Wireless debugging**, tap **Pair device with pairing code**, and type the six digits
into TinyKit's notification. That's all. After that, **Start** needs nobody: TinyKit turns
Wireless debugging on by itself (once Shizuku has let it change that setting), connects and starts
Shizuku, also after a reboot. Wireless debugging needs Wi-Fi.

### Option B: A computer with adb

```sh
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

Shizuku shows the exact command for your version under *Start by connecting to a computer*.

## Starting Shizuku from TinyKit

The **Shizuku** tab has **Start** and **Stop** buttons, and there is a one-tap
[Shizuku tile](quick-settings-tiles.md#shizuku). The buttons work with the standard Shizuku app
and with forks. **Start** tries these in order:

1. The [auth token](#with-the-auth-token), if one is saved and your Shizuku has start intents.
2. TinyKit's [own adb start](#with-tinykits-own-adb-client) over Wireless debugging (Android 11+).
3. Otherwise (Android 10 and older) it [opens Shizuku](#with-the-standard-shizuku-app) so you tap its own Start button.

### With the auth token

thedjchi's fork can be started and stopped with an intent carrying a secret token.

1. In Shizuku, open *Settings* and find the start/stop intent parameters. Copy the token.
2. In TinyKit, tap **Auth token** on the Shizuku tab and paste it.

**Start** and **Stop** then go straight to Shizuku. **Stop** also works properly with the
Watchdog on, because Shizuku knows the stop was intentional.

### With TinyKit's own adb client

With no token saved, **Start** runs TinyKit's own adb client (`Adb.java`, no library, no other app):

1. It looks for your phone's Wireless debugging on the network and, if it is off and TinyKit may
   change that setting, switches it on.
2. The first time, it asks for the pairing code in a notification (see Option A above) and pairs.
3. It connects, and runs Shizuku's own starter as the adb user.

The notification says what it is waiting for and ends by itself. Android shows *"Allow wireless
debugging on this network?"* once for each Wi-Fi network.

### With the standard Shizuku app

The standard Shizuku app has no start intents, and without root or a computer it can only be
started from its own screen. So on Android 10 and older with no token, **Start** opens Shizuku on its
**Start** button. Tap it (the first time, Shizuku walks you through pairing with Wireless
debugging), then go back: TinyKit notices Shizuku is running and connects by itself.

### Stop

- **Forks with the auth token saved:** **Stop** uses Shizuku's own stop intent.
- **Otherwise:** **Stop** closes the Shizuku app first, so a Watchdog can't start Shizuku again,
  then asks Shizuku's server to end, and makes it end if it hasn't within 3 seconds.

The Shizuku tab, the Shizuku tile and Shut Up all stop Shizuku this same way.

## What works without Shizuku

These keep working when Shizuku is stopped (the tiles thanks to the one-time self-grant):

- These Quick Settings tiles: Developer options, USB debugging, Hide dev options, Ad-block DNS,
  Grayscale, Extra dim, Screen timeout and Caffeine (the matching switches inside the app still
  need Shizuku)
- **Phone info**, **Network info**, **Charging info**, **Temperatures** and **Device IDs**
  (some show fewer details)
- **Speed up phone** and the **Free RAM** tile on Android 13 and older (closing cached apps only)

Everything else needs Shizuku running: per-app tools, logs, file recovery, saved Wi-Fi networks,
and more. While Shizuku is off, those rows are dimmed and marked **Requires Shizuku**, and rows
that work without it look normal.

## Finding your way around

<p align="center">
  <img src="images/screenshots/home.png" width="200" alt="Shizuku tab">
  <img src="images/screenshots/privacy.png" width="200" alt="Privacy tab">
  <img src="images/screenshots/apps.png" width="200" alt="Apps tab">
</p>
<p align="center">
  <img src="images/screenshots/system.png" width="200" alt="System tab">
  <img src="images/screenshots/info.png" width="200" alt="Info tab">
  <img src="images/screenshots/search.png" width="200" alt="Search results">
</p>

| Tab | What's there |
| --- | --- |
| **Shizuku** | Shizuku status, Start and Stop, the auth token, the Shizuku tile, and the **search bar** |
| **Privacy** | Privacy toggles, all sensors off, analytics and telemetry, saved Wi-Fi networks |
| **Apps** | Fake permissions, default apps, hide, unused, storage, recent access, mixed audio, running and background apps, APK backup |
| **System** | Force dark, disable HW overlays, animation speed, quick panel editor, TinyKit tiles, run a command, file recovery, settings backup, advanced reboot, cleanup, network tweaks |
| **Info** | Phone, network, charging, temperatures, device IDs, battery usage, wake-ups, vibration, app and crash logs, updates |

The **search bar** at the top of the Shizuku tab searches every tool by name and description.
Every search word has to match, so `wifi qr` finds *Saved Wi-Fi networks*. Tapping a result
opens it.

Next: the full [feature reference](features.md), or the
[Fake permissions guide](fake-permissions.md).
