# Translating TinyKit

Translations are welcome, and you can start today. There are three parts, from easiest to biggest.
Each can be a separate pull request, so you can do one and stop.

## 1. Store listing (no code)

The text that F-Droid and IzzyOnDroid show lives in `fastlane/metadata/android/<locale>/`.
Copy the `en-US` folder to your locale and translate the files:

| File | Limit |
| --- | --- |
| `title.txt` | the app name, normally left as TinyKit |
| `short_description.txt` | 80 characters |
| `full_description.txt` | 4000 characters |

Locale folder names: `es-419` for Latin American Spanish, `es-ES` for Spain, `pt-BR`, `de-DE`,
`fr-FR` and so on. Leave `changelogs/` and `images/` alone; the English ones are used.

## 2. README and docs (no code)

Translate `README.md` to `README.<code>.md` (for example `README.es.md`) and link it at the top
of the English README. Docs in `docs/` can follow later, one page at a time.

## 3. The app itself

TinyKit's text is currently written in the Java code and not in Android string files, so the app
can't be translated by only adding a file. Moving it into `res/values/strings.xml` is a large
change that has to be done carefully, so it is planned and not started yet. If you want to help
with it, say so in an issue first so we don't work on it twice. Once it is done, a translation
will be one `res/values-<locale>/strings.xml` file.

## How to send it

1. Fork the repository and make a branch.
2. Add your files.
3. Open a pull request, or attach the files to an issue if pull requests are new to you.

By contributing, you agree that your translation is licensed under the [MIT licence](../LICENSE).
