#!/bin/sh
# Repo consistency checks: versions agree everywhere, a changelog exists, docs links resolve,
# every manifest component has a class. Run from anywhere; exits 1 on the first group of failures.
cd "$(dirname "$0")/.." || exit 1
fail=0
bad() { echo "FAIL: $*"; fail=1; }

code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
name=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
[ -n "$code" ] && [ -n "$name" ] || bad "could not read version from app/build.gradle.kts"
grep -q "TinyKit $name " docs/README.md || bad "docs/README.md footer is not $name"
grep -q "^CurrentVersion: $name\$" fdroid/io.github.order52.tinykit.yml || bad "fdroid CurrentVersion is not $name"
grep -q "^CurrentVersionCode: $code\$" fdroid/io.github.order52.tinykit.yml || bad "fdroid CurrentVersionCode is not $code"
grep -q "commit: v$name\$" fdroid/io.github.order52.tinykit.yml || bad "fdroid has no build entry for v$name"
[ -s "fastlane/metadata/android/en-US/changelogs/$code.txt" ] || bad "missing changelogs/$code.txt"

# Relative markdown links and images must point at files that exist.
for f in README.md docs/*.md fdroid/*.md; do
  [ -f "$f" ] || continue
  dir=$(dirname "$f")
  grep -o '](\([^)#h][^)#]*\)' "$f" | sed 's/^](//' | while read -r link; do
    [ -e "$dir/$link" ] || echo "FAIL: $f links to missing $link"
  done
done | tee /tmp/tinykit-links.$$ ; grep -q FAIL /tmp/tinykit-links.$$ && fail=1; rm -f /tmp/tinykit-links.$$

# Every <activity>/<service>/<receiver> in the manifest must have a source class.
grep -o 'android:name="\.[A-Za-z$.]*"' app/src/main/AndroidManifest.xml | sed 's/android:name="\.//; s/"//' | while read -r c; do
  base=${c%%\$*}
  [ -f "app/src/main/java/io/github/order52/tinykit/$base.java" ] || echo "FAIL: manifest class .$c has no source file"
done | tee /tmp/tinykit-cls.$$ ; grep -q FAIL /tmp/tinykit-cls.$$ && fail=1; rm -f /tmp/tinykit-cls.$$

# Nothing runs in the background by default: the only services are Quick Settings tiles and the
# optional install watcher (NewAppService, off until the user turns it on); the only receivers are
# the widget, the tile's reply and the one that restarts the watcher after a restart or an update.
grep -A1 '<service\|<receiver' app/src/main/AndroidManifest.xml | grep -o 'android:name="[^"]*"' \
  | grep -v 'Tiles\$\|ShizukuTile\|ScreenshotTile\|BoostWidget\|NewAppService\|StartReceiver' | grep -v 'action' | while read -r n; do
  echo "FAIL: unexpected service or receiver $n"
done | tee /tmp/tinykit-bg.$$ ; grep -q FAIL /tmp/tinykit-bg.$$ && fail=1; rm -f /tmp/tinykit-bg.$$
[ $fail = 0 ] && echo "repo checks passed"
exit $fail
