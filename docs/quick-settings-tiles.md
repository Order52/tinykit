# Quick Settings tiles

TinyKit adds 16 tiles to Android's Quick Settings panel. Like the rest of TinyKit, they cost
nothing when you aren't using them: Android only binds a tile while the panel is open, and none
of them keeps a service running.

## Adding tiles

- **Android 13 and newer**: open **System → TinyKit tiles** and tap **Add**. Android asks you to
  confirm, and the tile is added.
- **Older versions**: pull down the quick panel, tap the edit (pencil) button and drag the tile
  in. Or use TinyKit's own **System → Edit quick panel**, which can add any tile.

## The tiles

| | Tile | Tap does | Shows | Needs Shizuku |
| :---: | --- | --- | --- | :---: |
| <img src="images/tiles/shizuku.svg" width="40" alt=""> | <a id="shizuku"></a>**Shizuku** | Starts or stops Shizuku | On / Off / Starting… | No¹ |
| <img src="images/tiles/screenshot.svg" width="40" alt=""> | <a id="screenshot"></a>**Screenshot** | Closes the panel and saves a screenshot to `Pictures/Screenshots` | — | Yes |
| <img src="images/tiles/record.svg" width="40" alt=""> | **Screen record** | Starts recording to `Movies`; tap again to stop and save | Recording… | Yes |
| <img src="images/tiles/caffeine.svg" width="40" alt=""> | **Caffeine** | Keeps the screen on: 5 min → 15 min → 30 min → never → off | The current timeout | No |
| <img src="images/tiles/dev.svg" width="40" alt=""> | **Developer options** | Turns developer options on or off | On / off | No |
| <img src="images/tiles/usb.svg" width="40" alt=""> | **USB debugging** | Turns USB debugging on or off | On / off | No |
| <img src="images/tiles/hidedev.svg" width="40" alt=""> | **Hide dev options** | Makes apps see developer options as off | On / off | No |
| <img src="images/tiles/sensors.svg" width="40" alt=""> | **Sensors off** | Blocks every sensor (motion, mic, camera) | Blocked | Yes |
| <img src="images/tiles/grayscale.svg" width="40" alt=""> | **Grayscale** | Turns the whole screen grey | On / off | No |
| <img src="images/tiles/dim.svg" width="40" alt=""> | **Extra dim** | Dims below the normal minimum brightness | On / off | No |
| <img src="images/tiles/adblock.svg" width="40" alt=""> | **Ad-block DNS** | Turns AdGuard Private DNS on, or back to your previous DNS | On / off | No |
| <img src="images/tiles/miccam.svg" width="40" alt=""> | **Mic & camera off** | Blocks the microphone and camera for every app | On / off | Yes |
| <img src="images/tiles/timeout.svg" width="40" alt=""> | **Screen timeout** | Cycles 30 s → 1 min → 5 min → 30 min → never | The current timeout | No |
| <img src="images/tiles/cache.svg" width="40" alt=""> | **Clear caches** | Clears every app's cache | MB freed | Yes |
| <img src="images/tiles/ram.svg" width="40" alt=""> | **Free RAM** | Speeds up the phone, like **Speed up phone** | Free memory | Android 14+² |
| <img src="images/tiles/kill.svg" width="40" alt=""> | **Close app** | Force-stops the app on screen | — | Yes |

¹ The Shizuku tile needs either the auth token or Termux with shizuku-termux. See
[Getting started](getting-started.md#starting-shizuku-from-tinykit).

² Free RAM works without Shizuku on Android 13 and older, closing cached apps only.

Tiles marked **No** work with Shizuku stopped, because TinyKit granted itself
`WRITE_SECURE_SETTINGS` the first time Shizuku was available. If that hasn't happened yet, they
show *"Start Shizuku once so TinyKit can change this."*

## Details

**Shizuku.** With the auth token saved it sends Shizuku's own start or stop intent. Otherwise it
asks Termux to run `shizuku start` or `shizuku stop`. With neither, it can still stop a running
Shizuku by force-stopping the Shizuku app first (so the Watchdog doesn't restart it) and then
ending the server. Its state refreshes on its own when Shizuku starts or stops.

**Screenshot.** Closes the panel, waits a moment, then runs `screencap`. Apps that block
screenshots (`FLAG_SECURE`, used by banking and DRM apps) stay blocked, and the tile tells you
so. Android doesn't let anything get past that without root.

**Screen record.** Runs the system's `screenrecord` command in the background. Tapping again
sends it `SIGINT`, so it finishes writing a playable file. Some Huawei builds don't include
`screenrecord`; the tile tells you to use the phone's own recorder there.

**Caffeine.** Raises the normal screen-off timeout rather than holding a wake lock, so nothing
runs in the background and Android still turns the screen off at the end. Tapping past *never*
restores the timeout you had before.

**Grayscale** turns on the accessibility colour-correction mode with the monochrome setting
(`accessibility_display_daltonizer_enabled=1`, `accessibility_display_daltonizer=0`).

**Extra dim** uses Android's *Reduce bright colours* setting (`reduce_bright_colors_activated`).

**Sensors off** and **Mic & camera off** differ. *Mic & camera off* uses Android 12+'s
per-sensor privacy toggles (`cmd sensor_privacy`). *Sensors off* blocks **all** sensors,
including the accelerometer and gyroscope, and resets on reboot.

**Close app** finds the app in front (`dumpsys activity activities`) and force-stops it. It never
stops SystemUI, the launcher or TinyKit itself.

**Free RAM** runs the same routine as **System → Speed up phone**: it trims app memory, closes
cached apps only if memory is low, and shows how much is free. See
[Speed up phone](features.md#clean--speed).
