# Troubleshooting & FAQ

## Shizuku

**"Start Shizuku and allow TinyKit first."**
Shizuku isn't running, or TinyKit isn't authorised. Open the **Shizuku** tab: if it says
*Allow*, tap it. If Shizuku isn't running, start it (see
[Getting started](getting-started.md#2-start-shizuku)). Shizuku has to be started again after
every reboot.

**Start does nothing, or says *"Shizuku didn't start"*.**
- *With the auth token*: check that the token matches the one in Shizuku's settings, and that
  you're using a Shizuku build that supports start intents (thedjchi's fork).
- *With Termux*: open Termux once (Android may have stopped it), and make sure
  `shizuku-termux` is installed. Exit code 127 means the `shizuku` command wasn't found.
- *"Termux doesn't allow other apps to run commands"*: run the shizuku-termux install command
  in Termux. It enables `allow-external-apps`.
- *Start opens the Shizuku app*: that's expected with the standard Shizuku app and no Termux.
  Tap **Start** in Shizuku, then go back to TinyKit. See
  [Getting started](getting-started.md#with-the-standard-shizuku-app).
- *Termux asked for permission, then nothing happened*: fixed in 1.10.2. Update TinyKit and
  tap Start again.

**Stop works, then Shizuku comes straight back.**
That's the Watchdog in thedjchi's fork. Save the auth token in TinyKit, so Stop uses Shizuku's
own stop intent, which the Watchdog respects. The standard Shizuku app has no Watchdog, so Stop
ends it for good.

**A tile says *"Start Shizuku once so TinyKit can change this."***
TinyKit grants itself `WRITE_SECURE_SETTINGS` the first time Shizuku is ready. Start Shizuku
and open TinyKit once. After that, the settings tiles work without Shizuku.

## Fake permissions

**The app still gets my location or contacts.**
Force-stop the app and open it again, because some apps cache data from an earlier grant. Then
check the state with `appops get <pkg>`; it should show `ignore` for that op. See
[Verifying it yourself](fake-permissions.md#verifying-it-yourself).

**A Fake permission turned back into Real or Off.**
Changing that permission in Android's Settings app re-syncs the app-op. Set it to Fake in
TinyKit again.

**The control jumps back after I tap it.**
The change was refused. For example, an app built for an old Android version may not accept
runtime grants, or the permission doesn't exist on your phone. The toast shows Android's error
message.

## Features

**Screen record says there's no screenrecord command.**
Some Huawei builds leave out `screenrecord`. Use the phone's built-in recorder.

**Screenshot says the app blocks screenshots.**
The app sets `FLAG_SECURE` (banking, DRM video, some messengers). Android doesn't let anything
capture those screens without root.

**App log is empty.**
The app isn't running, or your phone hides app logs (most Huawei phones do). **Crash log** still
works.

**Recent access, Wake-ups or Vibration log is empty or partial.**
These come from `dumpsys` output, which manufacturers format differently. TinyKit shows what it
can parse.

**Ad-blocking DNS stops a Wi-Fi login page from loading.**
Captive portals at hotels and airports often need normal DNS. Turn Ad-blocking DNS off, sign
in, then turn it back on.

**Disable HW overlays turned itself off after a restart.**
Android forgets it, and TinyKit puts it back once Shizuku is running again, like Force dark.

**Force dark turned itself off after a restart.**
Android forgets it, and TinyKit puts it back once Shizuku is running again, without opening
TinyKit. If it doesn't come back, Shizuku isn't running or hasn't allowed TinyKit yet.

**Blocked apps are online again after a restart.**
Android clears the block at every restart. TinyKit re-applies it as soon as Shizuku is running,
so start Shizuku and give it a few seconds. Without Shizuku nothing can set the block.

**An app I installed later has internet in allow list mode.**
TinyKit blocks new apps the moment they're installed, but it needs Shizuku running to do it. If
Shizuku was off, the app is blocked as soon as Shizuku starts again. If it still isn't, open
TinyKit once, and check that Shizuku has allowed it.

**All sensors off turned itself off.**
It resets when the phone reboots. That's how Android implements it. Turn it on again.

**The Debloater won't let me select an app.**
Apps marked **Unsafe**, and the ones in use right now (home screen, keyboard, phone, messages,
web view, TinyKit, Shizuku), are locked so the phone can't be broken by accident.

**A removed app doesn't appear under Removed.**
Only apps that came with the phone can be restored. An app you installed yourself and removed
has no files left to bring back.

**The language I picked for an app has no effect.**
Android only applies it if the app has that language. Pick another, or choose *System default*.

**Unused apps or Storage by app is empty.**
TinyKit needs the usage access app-op, which it grants itself through Shizuku. Start Shizuku
and open the page again.

**Hide apps: where did the app go?**
It's disabled, not uninstalled. Uncheck it in **Apps → Hide apps** to bring it back.

## Huawei / EMUI notes

TinyKit is developed and tested on a Huawei phone, and handles EMUI's quirks:

- **Hide apps** uses `pm disable-user`, the only method that works on EMUI.
- **Default apps** falls back to `dumpsys role`, because EMUI lacks `cmd role get-role-holders`.
- **Settings toggles** fall back to the Shizuku shell for keys that EMUI locks against app
  writes.
- **The quick panel editor** draws EMUI's round tiles and four-row grid.
- **The Shizuku tile** is an *active* tile, because EMUI never binds passive tiles.
- **Recover deleted files** decodes Huawei Gallery's Base32-encoded file names.

## Still stuck?

[Open an issue](https://github.com/Order52/tinykit/issues) and include:

- Your phone model and Android or EMUI version
- Your Shizuku version, and how you start it
- What you tapped, and the exact message you saw
- If possible, the output of the related command from **System → Run a command**
