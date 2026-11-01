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

TinyKit's text lives in the code in English, and a translation is **one plain text file**:
`app/src/main/assets/i18n/<language>.tsv`, for example `es.tsv`. Each line is
`English text`, a tab, then `your translation`. The phone's language picks the file, and anything
not in the file stays in English, so a partial translation is fine.

- Edit the right-hand side only. The left-hand side must stay exactly as it is, including spaces
  at the start or end: some lines are the fixed part of a longer sentence (for example
  `Also ` before a list of app names).
- `\n` is a new line. Lines starting with `#` are comments.
- To add a language, copy `es.tsv` to `<code>.tsv` (`pt.tsv`, `fr.tsv`, `de.tsv`) and translate
  the right-hand side. Use the two-letter language code: one file covers every region of it.
- To test, build the app, set your phone's (or the app's) language, and open each page. A line
  that stays English is either not in the file or is built from several pieces.

Spanish (`es.tsv`) is written for Latin America and is waiting for a native speaker to check it.

## How to send it

1. Fork the repository and make a branch.
2. Add your files.
3. Open a pull request, or attach the files to an issue if pull requests are new to you.

By contributing, you agree that your translation is licensed under the [MIT licence](../LICENSE).
