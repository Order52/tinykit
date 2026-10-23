# Submitting TinyKit to F-Droid

TinyKit is F-Droid-ready: MIT-licensed, no proprietary dependencies, no analytics, and the
app descriptions live in `fastlane/metadata/android/en-US/`.

F-Droid cannot be "pushed to" directly — the main repo builds apps from a recipe you submit
to the fdroiddata project and that their team reviews and merges. Steps:

1. Make sure a git tag `v1.6.0` exists on this repo (the release does this).
2. Fork https://gitlab.com/fdroid/fdroiddata
3. Add `metadata/io.github.order52.tinykit.yml` using the recipe in this folder.
4. Run `fdroid lint io.github.order52.tinykit` and `fdroid build io.github.order52.tinykit`.
5. Open a merge request. The F-Droid team reviews, builds and signs it with their own key.

Alternatively, host your own F-Droid repository (fdroid/fdroidserver) and add the signed APK
from GitHub Releases to it — that one you fully control.
