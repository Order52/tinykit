# Privacy & security

TinyKit asks for powerful access. This page explains exactly what it can do with that access,
what it actually does, and what it never does.

## In short

- **No analytics, no ads, no crash reporting, no accounts.**
- **Nothing leaves your phone** except two requests you trigger yourself (see
  [Network](#network)).
- **Nothing runs in the background.** The one exception is when an app is installed while the
  internet block is in use: TinyKit wakes for that install, blocks the app and ends.
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

- Run in the background, or start at boot (other than the one-off install and Shizuku-start wake-ups above).
- Send any data anywhere other than the two requests above.
- Touch your Huawei ID or Google account. The telemetry tools explicitly skip `hwid` packages.
- Change anything you didn't tap. Opening a page only reads.

## Reporting a security issue

If you find a vulnerability, please **don't** open a public issue. Contact the maintainer
privately through the email on their [GitHub profile](https://github.com/Order52), with steps to
reproduce. You'll get a reply before any fix is published.
