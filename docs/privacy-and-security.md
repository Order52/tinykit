# Privacy & security

TinyKit asks for powerful access. This page explains exactly what it can do with that access,
what it actually does, and what it never does.

## In short

- **No analytics, no ads, no automatic crash reporting, no accounts.** If TinyKit crashes it
  *offers* to open a pre-filled GitHub issue, and only sends anything if you post it
  (see [Crash reports](#crash-reports)).
- **Nothing leaves your phone** except two requests you trigger yourself (see
  [Network](#network)).
- **Nothing runs in the background by default.** The optional switch *Block new apps at once*
  keeps a quiet notification and a small service so a new app is blocked the moment it is
  installed; it is off until you turn it on and shows in the settings whether it is running. Besides
  that, TinyKit only runs a short check when Shizuku starts.
- **Everything it changes is a standard Android setting**, which you can inspect and undo.
- **Open source** under the MIT licence: every command is in the code, and this documentation
  lists them.

## Permissions TinyKit declares

| Permission | Why |
| --- | --- |
| `QUERY_ALL_PACKAGES` | App pickers list every installed app |
| `INTERNET` | Check for updates, and the public IP on Network info, only when you use them |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Network info reads the active connection's details |
| `com.termux.permission.RUN_COMMAND` | Start Shizuku through Termux. Android asks you first. |
| `KILL_BACKGROUND_PROCESSES` | Speed up phone without Shizuku on Android 13 and older. Closes only cached apps. |
| `PACKAGE_USAGE_STATS` | Unused apps and Storage by app. Granted through Shizuku. |
| `POST_NOTIFICATIONS` | A "TinyKit stopped, send the details?" notification when it crashes. Android 13+ asks you first, and only if you tap the option in the crash popup. |
| `WRITE_SECURE_SETTINGS` | Lets toggles and tiles work with Shizuku stopped. Granted once through Shizuku. |

There are **no** location, contacts, camera, microphone, storage or phone permissions. When
TinyKit reads files (for recovery and APK backup) or system data, it does so through Shizuku as
the `shell` user, and only when you open that tool.

## What Shizuku access means

While Shizuku is running and TinyKit is allowed to use it, TinyKit can run commands with the
same rights as `adb shell`. That's a lot of power: it can change settings, permissions and
app-ops for any app, read shared storage, read logs, and stop apps. It can't touch other apps'
private data, system partitions, or anything that needs root.

TinyKit uses that access only for the tool you're using at that moment. To take it away:

- Revoke TinyKit in **Shizuku → Authorized applications**, or
- Stop Shizuku. It also stops by itself when the phone reboots.

## Crash reports

TinyKit never sends a crash report by itself. When it crashes, it keeps the error (the stack
trace, TinyKit's version, your phone model, your Android version and whether Shizuku was running)
in a private file on the phone and, where notifications are allowed, shows a notification. The
next time you open TinyKit it asks whether to send it:

- **Send** opens a new issue on GitHub in your browser with those details filled in. You can
  read and edit everything, and nothing is sent until you press *Submit* on GitHub (which needs a
  GitHub account).
- **Not now** deletes the saved crash.

**Info → Report a problem** does the same for a problem that isn't a crash, and adds TinyKit's
own recent log lines (Android only lets an app read its own). Turn **Offer to report crashes**
off to stop TinyKit from keeping crashes at all.

## Sensitive data TinyKit can show you

Some tools display data that other apps can't see. It stays on your device:

| Tool | Data | Stored? |
| --- | --- | --- |
| Saved Wi-Fi networks | Wi-Fi passwords | No. Read each time the page opens. |
| Device IDs | Android ID, serial, MAC | No |
| App log and crash log | Other apps' log output | No |
| Recover deleted files | Files in recycle bins | Only the files you choose to restore, copied to `Download/TinyKit/Recovered` |

TinyKit's manifest sets `allowBackup="false"`, so Android's cloud and device-to-device backups
never copy its private files, including the auth token.

## Network

| Request | Sent to | Contains | When |
| --- | --- | --- | --- |
| Update check | `api.github.com` | A standard GET; no identifiers | When you tap **Check for updates**, or when TinyKit opens (at most hourly) if **Check for updates on start** is on |
| Public IP | `api.ipify.org` (fallback `ifconfig.me`) | A standard GET with `User-Agent: TinyKit` | When **Network info** opens with a connection |

**Ad-blocking DNS** sends your DNS queries to AdGuard (`dns.adguard-dns.com`) while it's on.
That's what it does. Turning it off restores your previous Private DNS setting.

## Things TinyKit will never do

- Run in the background or start at boot, other than the optional *Block new apps at once* service and the Shizuku-start check above.
- Send any data anywhere other than the two requests above.
- Touch your Huawei ID or Google account. The telemetry tools explicitly skip `hwid` packages.
- Change anything you didn't tap. Opening a page only reads.

## Reporting a security issue

If you find a vulnerability, please **don't** open a public issue. Contact the maintainer
privately through the email on their [GitHub profile](https://github.com/Order52), with steps to
reproduce. You'll get a reply before any fix is published.
