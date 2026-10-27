# Fake permissions

> Give an app the permission it insists on, without giving it your data.

Many apps refuse to work, nag on every launch, or hide features until you grant a permission
they don't really need, such as a flashlight asking for your contacts or a game asking for your
location. Android only offers two answers: **Allow** (the app gets your data) or **Deny** (the app
knows, and complains).

TinyKit adds a third answer. With **Fake**, the app is told the permission is granted, but Android
returns nothing behind it.

<p align="center">
  <img src="images/screenshots/fake-permissions.png" width="300" alt="TinyKit's per-app permission page: each permission has a Real / Fake / Off control">
  &nbsp;&nbsp;
  <img src="images/screenshots/hidden-app-ops.png" width="300" alt="The Hidden app-ops section, with Read clipboard set to Fake">
</p>

---

## Contents

- [The three states](#the-three-states)
- [Using it](#using-it)
- [How it works](#how-it-works)
- [What an app sees with Fake](#what-an-app-sees-with-fake)
- [Supported permissions](#supported-permissions)
- [Hidden app-ops](#hidden-app-ops)
- [Verifying it yourself](#verifying-it-yourself)
- [Undoing changes](#undoing-changes)
- [Caveats](#caveats)
- [FAQ](#faq)

## The three states

| State | Colour | Permission | App-op | What the app sees | What the app gets |
| --- | --- | --- | --- | --- | --- |
| **Real** | 🟩 Green | Granted | `allow` | Granted | Real data |
| **Fake** | 🟦 Blue | Granted | `ignore` | **Granted** | **Nothing**: empty or blank results |
| **Off** | 🟥 Red | Revoked | `ignore` | Denied | Nothing, and it knows |

**Fake** is the key one. The permission check the app runs (`checkSelfPermission`, or
`ContextCompat.checkSelfPermission` in AndroidX) returns `PERMISSION_GRANTED`, so the app has no
reason to ask again or refuse to start. But every data access goes through Android's app-ops
layer, which is set to `ignore`, so the system hands back empty results instead of throwing an
error.

## Using it

1. Make sure Shizuku is running and TinyKit is allowed to use it. See
   [Getting started](getting-started.md).
2. Open **Apps → App permissions**. You can also type `fake` or `permission` in the home
   search bar.
3. Pick an app. Use the search box, the **Show system apps** checkbox, or the **Sort** chip
   (by name or package) to find it.
4. Tap **Real**, **Fake** or **Off** for each permission. The change applies at once.

The control updates immediately, then re-reads the real state from the system. If the change
didn't stick, for example because the app isn't allowed that permission on this Android
version, the control jumps back to the actual state and TinyKit shows the error.

> [!TIP]
> Force-stop the app (**Apps → Running apps**, or the **Close app** tile) after a change.
> Some apps only check permissions at startup.

## How it works

Android protects sensitive data in **two layers**:

1. **Runtime permissions**, which decide whether the app *may ask* for the data. This is what
   apps check, and what the Settings app shows.
2. **App-ops** (`AppOpsManager`), which decide what happens when the app *actually does* the
   thing. Their modes are `allow`, `ignore`, `deny`, `foreground` and `default`.

Normally the two layers agree. TinyKit sets them separately. It runs these commands through
Shizuku as the `shell` user (`<pkg>` is the app's package name, and `<PERM>` and `<OP>` are the
matching permission and app-op):

| Tap | Commands |
| --- | --- |
| **Real** | `pm grant <pkg> <PERM>` then `appops set --uid <pkg> <OP> allow` |
| **Fake** | `pm grant <pkg> <PERM>` then `appops set --uid <pkg> <OP> ignore` |
| **Off** | `pm revoke <pkg> <PERM>` then `appops set --uid <pkg> <OP> ignore` |

Permission ops are set **per UID** (`--uid`), because Android 12 and newer enforce camera,
microphone and location at the UID level. A per-package mode would be ignored for those.

To show the current state, TinyKit reads both layers in a single shell round-trip:

```text
not granted                 → Off
granted + app-op "ignore"   → Fake
granted + anything else     → Real
```

The implementation is in `openAppPage`, `setOp`, `stateOf` and `opModes` in
[`MainActivity.java`](../app/src/main/java/io/github/order52/tinykit/MainActivity.java).

## What an app sees with Fake

With the app-op at `ignore`, each Android service fails *quietly* rather than throwing a
`SecurityException`. What that looks like depends on the service, and can vary a little between
Android versions and manufacturers:

| Permission | Typical result with **Fake** |
| --- | --- |
| Location | No location fixes; the last known location is empty |
| Camera | The camera can't be opened, as if another app were using it |
| Microphone | Recording "works" but the audio is silent |
| Contacts, call log, calendar, SMS | Queries return an empty list |
| Phone | Phone-state queries return empty or default values |
| Storage | Media and file queries come back empty |
| Body sensors, physical activity | No sensor events are delivered |
| Notifications | The app can post, but nothing is shown |
| Clipboard (hidden op) | The app reads an empty clipboard |

Most apps handle "granted, but no results" as an ordinary empty state, such as "no contacts
yet" or "location unavailable", and carry on.

## Supported permissions

The **Permissions** section lists only the permissions the app actually requests or holds, out
of this set:

| Shown as | Runtime permission | App-op |
| --- | --- | --- |
| Approximate location | `ACCESS_COARSE_LOCATION` | `COARSE_LOCATION` |
| Precise location | `ACCESS_FINE_LOCATION` | `FINE_LOCATION` |
| Camera | `CAMERA` | `CAMERA` |
| Microphone | `RECORD_AUDIO` | `RECORD_AUDIO` |
| Read contacts | `READ_CONTACTS` | `READ_CONTACTS` |
| Modify contacts | `WRITE_CONTACTS` | `WRITE_CONTACTS` |
| Calendar | `READ_CALENDAR` | `READ_CALENDAR` |
| Call log | `READ_CALL_LOG` | `READ_CALL_LOG` |
| Phone | `READ_PHONE_STATE` | `READ_PHONE_STATE` |
| SMS | `READ_SMS` | `READ_SMS` |
| Read storage | `READ_EXTERNAL_STORAGE` | `READ_EXTERNAL_STORAGE` |
| Write storage | `WRITE_EXTERNAL_STORAGE` | `WRITE_EXTERNAL_STORAGE` |
| Body sensors | `BODY_SENSORS` | `BODY_SENSORS` |
| Physical activity | `ACTIVITY_RECOGNITION` | `ACTIVITY_RECOGNITION` |
| Notifications | `POST_NOTIFICATIONS` | `POST_NOTIFICATION` |

If an app requests none of them, the page says so.

## Hidden app-ops

Below the permissions, every app also gets a **Hidden app-ops** section. These are app-ops that
have no runtime permission, so the Settings app usually doesn't show them at all:

| Shown as | App-op | Why you might change it |
| --- | --- | --- |
| Read clipboard | `READ_CLIPBOARD` | Stop an app from reading what you copy |
| Write clipboard | `WRITE_CLIPBOARD` | Stop an app from replacing your clipboard |
| Run in background | `RUN_ANY_IN_BACKGROUND` | Stop background work and battery drain |
| Keep device awake | `WAKE_LOCK` | Stop an app from keeping the phone awake |
| Draw over other apps | `SYSTEM_ALERT_WINDOW` | Block overlays and pop-ups |
| Read app usage | `GET_USAGE_STATS` | Hide which apps you use |
| All files access | `MANAGE_EXTERNAL_STORAGE` | Remove full storage access |
| Foreground service | `START_FOREGROUND` | Stop persistent "running" services |
| Vibrate | `VIBRATE` | Silence an app's vibrations |

Hidden ops are set **per package** (`appops set <pkg> <OP> <mode>`), and the three states map
directly to app-op modes:

| Tap | Mode |
| --- | --- |
| **Real** | `allow` |
| **Fake** | `ignore`: the call quietly does nothing |
| **Off** | `deny`: the call fails with an error the app can see |

> [!NOTE]
> **Recent access** (also in **Apps**) shows which apps recently used the camera, microphone,
> location, contacts or clipboard. It's a good way to find apps worth setting to Fake.

## Verifying it yourself

You can check any change from a computer with `adb`, or in TinyKit's own
**System → Run a command**. Here is an app with precise location and contacts set to Fake:

```console
$ adb shell dumpsys package com.android.chrome | grep -E "FINE_LOCATION|READ_CONTACTS"
    android.permission.ACCESS_FINE_LOCATION: granted=true, flags=[ REVOKED_COMPAT|… ]
    android.permission.READ_CONTACTS: granted=true, flags=[ REVOKED_COMPAT|… ]

$ adb shell appops get com.android.chrome
Uid mode: FINE_LOCATION: ignore
…
READ_CONTACTS: ignore
```

Both permissions are `granted=true`, so the app sees them as granted, while the app-op is
`ignore`. Android also marks them `REVOKED_COMPAT`, its own name for "granted, but blocked by
app-ops".

## Undoing changes

Every state is reversible from the same screen: tap **Real** to restore normal access. Nothing
is patched or replaced, and TinyKit keeps no state of its own for this. Everything lives in
Android's own permission and app-op databases, which means:

- Changes **survive reboots** and keep working with Shizuku stopped and TinyKit closed.
- Changes are **lost if the app is uninstalled** (its permissions go with it) and on a factory
  reset.
- To reset everything for an app by hand, run `appops reset <pkg>`, then grant or revoke
  permissions in Android's Settings app.

## Caveats

- **Real means "always allow".** Real sets the app-op to `allow`. For location, camera and
  microphone, Android's own default is often `foreground`, which means *only while the app is in
  use*. If you want while-in-use behaviour back, set it in Android's Settings app instead of
  tapping Real.
- **Android Settings can overwrite Fake.** Changing a permission in the system Settings app
  re-syncs the app-op, so a Fake permission can turn back into Real or Off. Re-apply it in
  TinyKit afterwards.
- **One app-op per UID.** Apps that share a user ID (rare, mostly system apps) share their
  permission app-ops, so changing one changes them all.
- **Some permissions can't be granted.** If an app targets an old Android version, or the
  permission doesn't exist on your phone, `pm grant` can fail. The control then shows the real
  state.
- **Detection is possible.** A determined app can call `AppOpsManager.noteOp` itself and see
  `MODE_IGNORED`. Almost none do, but Fake is a privacy tool, not an anti-detection tool.

## FAQ

**Is this the same as XPrivacyLua or App Ops (Rikka)?**
It's closest to *App Ops*: both use Android's built-in app-op modes, so neither needs root or
Xposed. TinyKit pairs the app-op with a permission grant, so the app also believes it has the
permission. XPrivacyLua-style tools hook into the app itself to return fake data such as a
made-up location. TinyKit doesn't invent data; it returns none.

**Does Fake use battery or run in the background?**
No. Android enforces app-ops itself. TinyKit isn't involved after you tap.

**Why does the camera show as unavailable instead of a black image?**
This is how Android's camera service handles an ignored camera op. TinyKit can't change it
without root.

**Can I Fake a permission for many apps at once?**
Not yet. The page works one app at a time. For background activity, **Apps → Stop background
activity** has a multi-select list.
