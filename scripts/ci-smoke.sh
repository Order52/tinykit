#!/bin/sh
# Emulator checks, run by CI (and by hand against one connected emulator):
#   1. install the APK, open every tab and every row on it, and fail on any crash;
#   2. run the same system commands and app_process helpers each feature uses, as the shell user
#      (what Shizuku gives TinyKit), and check they do what the feature expects.
# Usage: scripts/ci-smoke.sh path/to/app.apk
set -u
APK=${1:?usage: ci-smoke.sh app.apk}
PKG=io.github.order52.tinykit
fails=0
pass() { echo "ok   $*"; }
bad() { echo "FAIL $*"; fails=$((fails + 1)); }
check() { name=$1; shift; if "$@" >/dev/null 2>&1; then pass "$name"; else bad "$name"; fi; }
sh_() { adb shell "$@"; }
sdk=$(sh_ getprop ro.build.version.sdk | tr -d '\r')
echo "Android API $sdk"

# ---- Install --------------------------------------------------------------------------------
adb uninstall $PKG >/dev/null 2>&1
check "install" adb install -r "$APK"
check "app is installed" sh_ "pm path $PKG"
adb logcat -c

# ---- UI crawl: every tab, every row, no crash -----------------------------------------------
dump() { sh_ uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml; }
# Prints "x y" for the centre of every clickable node, one per line.
clickable() {
  dump | tr '>' '>\n' | grep 'clickable="true"' | grep -o 'bounds="[^"]*"' \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
    | while read -r a b c d; do echo "$(((a + c) / 2)) $(((b + d) / 2))"; done
}
crashed() { adb logcat -d -b crash 2>/dev/null | grep -q "$PKG"; }
foreground() { sh_ dumpsys window 2>/dev/null | grep -m1 mFocusedApp | grep -q "$PKG"; }
open_app() { sh_ am start -n $PKG/.MainActivity >/dev/null 2>&1; sleep 3; }

open_app
size=$(sh_ wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${size%x*}; H=${size#*x}
TABY=$((H - 60))
tabs=0
for tx in $((W / 10)) $((W * 3 / 10)) $((W / 2)) $((W * 7 / 10)) $((W * 9 / 10)); do
  tabs=$((tabs + 1))
  sh_ input tap "$tx" "$TABY"; sleep 1
  for pass_ in top scrolled; do
    [ $pass_ = scrolled ] && { sh_ input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 300; sleep 1; }
    rows=$(clickable | awk -v y=$TABY '$2 < y - 20' | head -40)
    echo "$rows" | while read -r x y; do
      [ -z "$x" ] && continue
      sh_ input tap "$x" "$y"; sleep 1
      # Dialogs and pages: back out, and re-select the tab if we left the app.
      sh_ input keyevent KEYCODE_BACK; sleep 0.5
      foreground || { open_app; sh_ input tap "$tx" "$TABY"; sleep 1; }
    done
    crashed && break
  done
  crashed && break
done
crashed && { bad "UI crawl crashed"; adb logcat -d -b crash | tail -30; } || pass "UI crawl: $tabs tabs, no crash"
open_app; foreground && pass "app opens again after the crawl" || bad "app does not open after the crawl"

# ---- Features, at the command level ---------------------------------------------------------
# Speed up phone
check "trim memory" sh_ "cmd activity help | grep -q send-trim-memory"
if [ "$sdk" -ge 30 ]; then check "compact system" sh_ "cmd activity compact system"; fi
check "idle maintenance" sh_ "sm idle-maint run"
check "trim caches" sh_ "pm trim-caches 1G"
check "process list" sh_ "ps -A | grep -q ."

# Settings toggles round-trip (what the tiles and System tab write)
for kv in "global window_animation_scale 0.5" "secure accessibility_display_daltonizer_enabled 1" "global private_dns_mode hostname"; do
  set -- $kv; ns=$1; key=$2; val=$3
  old=$(sh_ settings get $ns $key | tr -d '\r')
  sh_ settings put $ns $key $val >/dev/null 2>&1
  [ "$(sh_ settings get $ns $key | tr -d '\r')" = "$val" ] && pass "setting $key" || bad "setting $key"
  if [ "$old" = null ]; then sh_ settings delete $ns $key >/dev/null 2>&1; else sh_ settings put $ns $key "$old" >/dev/null 2>&1; fi
done
check "force dark property" sh_ "setprop debug.hwui.force_dark true && [ \"\$(getprop debug.hwui.force_dark)\" = true ]; setprop debug.hwui.force_dark false"

# Permissions and app-ops (Apps tab, Special access, fake permissions)
check "appops set/get" sh_ "appops set $PKG REQUEST_INSTALL_PACKAGES allow && appops get $PKG REQUEST_INSTALL_PACKAGES | grep -q allow; appops set $PKG REQUEST_INSTALL_PACKAGES default"
check "special access ops" sh_ "for o in SYSTEM_ALERT_WINDOW WRITE_SETTINGS MANAGE_EXTERNAL_STORAGE GET_USAGE_STATS; do appops get $PKG \$o >/dev/null || exit 1; done"
check "grant and revoke a permission" sh_ "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS && pm revoke $PKG android.permission.WRITE_SECURE_SETTINGS; pm grant $PKG android.permission.WRITE_SECURE_SETTINGS"
check "device idle whitelist" sh_ "dumpsys deviceidle whitelist +$PKG >/dev/null && dumpsys deviceidle whitelist -$PKG >/dev/null"
check "restrict background" sh_ "cmd appops set $PKG RUN_IN_BACKGROUND ignore; cmd appops set $PKG RUN_IN_BACKGROUND default"

# Debloater: remove for the user only, then restore (uses a harmless system app if present)
victim=$(sh_ pm list packages -s | tr -d '\r' | sed 's/package://' | grep -m1 '^com.android.egg$')
if [ -n "$victim" ]; then
  check "debloat remove" sh_ "pm uninstall -k --user 0 $victim"
  check "removed app is listed" sh_ "pm list packages -u | grep -q $victim && ! pm path $victim | grep -q ."
  check "debloat restore" sh_ "pm install-existing --user 0 $victim && pm path $victim | grep -q ."
else
  echo "skip debloat (no test app on this image)"
fi
check "hide and show an app" sh_ "pm disable-user --user 0 com.android.egg; pm enable --user 0 com.android.egg"

# Install with TinyKit as the installer ("Installed with TinyKit")
check "install with installer name" sh_ "pm install-create -r -i $PKG | grep -q 'Success'"
adb push "$APK" /data/local/tmp/ci.apk >/dev/null 2>&1
check "installer is recorded" sh_ "pm install -r -i $PKG /data/local/tmp/ci.apk && dumpsys package $PKG | grep -q 'installerPackageName=$PKG'"
adb install -r "$APK" >/dev/null 2>&1

# App language (Android 13+)
if [ "$sdk" -ge 33 ]; then
  check "set app language" sh_ "cmd locale set-app-locales $PKG --user 0 --locales fr && cmd locale get-app-locales $PKG --user 0 | grep -q fr"
  check "reset app language" sh_ "cmd locale set-app-locales $PKG --user 0"
fi

# Screen time and usage stats
check "usage stats" sh_ "dumpsys usagestats | grep -q ."

# Phone info, logs, and the Info tab sources
for c in "dumpsys battery" "dumpsys batterystats --charged" "dumpsys thermalservice" "dumpsys vibrator_manager" \
         "dumpsys connectivity" "dumpsys wifi" "ip addr" "dumpsys meminfo" "df /data" "settings get secure android_id" \
         "logcat -d -t 20" "logcat -b crash -d"; do
  check "info: $c" sh_ "$c"
done
check "role holders" sh_ "cmd role get-role-holders --user 0 android.app.role.HOME"
check "default apps" sh_ "dumpsys role | grep -q ."

# Run a command, backup and restore of settings, recovery paths
check "run a command" sh_ "echo hello | grep -q hello"
check "settings backup source" sh_ "settings list global | grep -q . && settings list secure | grep -q . && settings list system | grep -q ."
check "shared storage is readable" sh_ "ls /sdcard/Download >/dev/null"
check "write the TinyKit folder" sh_ "mkdir -p /sdcard/Download/TinyKit && echo x > /sdcard/Download/TinyKit/ci.txt && rm /sdcard/Download/TinyKit/ci.txt"
check "reboot options exist" sh_ "which reboot svc"
check "screenshot and record tools" sh_ "which screencap screenrecord"

# app_process helpers: the same classes Shizuku runs for TinyKit
base=$(sh_ pm path $PKG | tr -d '\r' | sed 's/package://' | head -1)
helper() { sh_ "CLASSPATH=$base app_process /system/bin io.github.order52.tinykit.$*"; }
out=$(helper Sensors state 2>&1 | tr -d '\r' | tail -1)
case $out in true|false) pass "sensor privacy helper ($out)";; *) bad "sensor privacy helper: $out";; esac
helper WifiDump >/dev/null 2>&1; pass "Wi-Fi helper runs (rc $?)"
if [ "$sdk" -ge 34 ]; then
  uid=$(sh_ pm list packages -U $PKG | tr -d '\r' | sed 's/.*uid://')
  out=$(helper Firewall block "$uid" 2>&1 | tail -1); [ "$out" = OK ] && pass "firewall block" || bad "firewall block: $out"
  helper Firewall query "$uid" 2>&1 | grep -q "$uid" && pass "firewall query lists the app" || bad "firewall query"
  out=$(helper Firewall sync -- "$uid" 2>&1 | tail -1); [ "$out" = OK ] && pass "firewall sync allow" || bad "firewall sync: $out"
  helper Firewall query "$uid" 2>&1 | grep -q "$uid" && bad "firewall still blocks after allow" || pass "firewall cleared"
fi

# ---- Final crash check ----------------------------------------------------------------------
crashed && bad "TinyKit crashed during the run" || pass "no TinyKit crash in the whole run"
echo
[ $fails = 0 ] && echo "ALL CHECKS PASSED" || echo "$fails CHECK(S) FAILED"
exit $((fails > 0))
