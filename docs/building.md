# Building

## Requirements

| | Version |
| --- | --- |
| JDK | 21 (the Gradle 9 wrapper needs it to run; the code itself targets Java 11) |
| Android SDK | Platform 34 and Build-Tools. Put the path in `local.properties` as `sdk.dir=…`, or set `ANDROID_HOME`. |
| Gradle | Not needed; use the wrapper (`./gradlew`) |

Android Studio works too: open the folder and run the `app` configuration.

## Build

```sh
git clone https://github.com/Order52/tinykit.git
cd tinykit
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk` (about 110 KB).

On macOS with Homebrew's JDK:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleRelease
```

For a debug build, use `./gradlew assembleDebug`. To build and install on a connected device in
one step, use `./gradlew installRelease`.

## Signing

Release builds are signed with the key in `keystore.properties` at the project root. The file
and any `*.jks` are git-ignored:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without that file, release builds are signed with the debug key, which is fine for testing but
can't update a release installed from GitHub or F-Droid.

## R8 and ProGuard

Release builds are minified and resource-shrunk. `app/proguard-rules.pro` keeps the things that
are only reached by reflection or from outside the app:

- Shizuku's private `newProcess` method, called by `Shell` through reflection
- The `main` methods of `WifiDump` and `Sensors`, started with `app_process`

If you add another `app_process` helper or reflective call, add a keep rule for it, and test the
**release** build: a debug build won't show R8 problems.

## Testing on a device

There's no automated test suite. Each change is tested by hand on a real phone, and on an
emulator for stock Android behaviour. Useful commands while testing:

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell appops get <pkg>                 # check app-op modes
adb shell dumpsys package <pkg> | grep granted=   # check runtime permissions
adb shell settings get global <key>       # check a setting
adb logcat -b crash                       # see crashes
```

On an emulator you can start Shizuku over adb (see [Getting started](getting-started.md#option-b-a-computer-with-adb)).

## Releasing

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` with the user-facing
   changes.
3. Commit, then tag the commit `v<versionName>` (for example `v1.8.0`) and push the tag.
4. Build the signed release APK, rename it `TinyKit.apk`, and attach it to a GitHub release for
   the tag. TinyKit's **Check for updates** reads the latest GitHub release.
5. F-Droid picks up new tags by itself (`UpdateCheckMode: Tags`,
   `AutoUpdateMode: Version v%v`). The recipe is in `fdroid/io.github.order52.tinykit.yml`, and
   the submission notes are in `fdroid/README.md`.
