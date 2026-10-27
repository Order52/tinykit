# Contributing

Thanks for helping. TinyKit is small on purpose, so the bar for new features is "does it fit
the [design principles](architecture.md#design-principles)?"

## Reporting bugs

[Open an issue](https://github.com/Order52/tinykit/issues) with your phone model, Android or
EMUI version, Shizuku version, what you tapped, and the exact message you saw. The output of the
related command from **System → Run a command** helps a lot.

Security issues: see [Privacy & security](privacy-and-security.md#reporting-a-security-issue).

## Suggesting features

Good fits:

- Something `adb shell` can already do, that's useful, but buried or hidden by Android or a
  manufacturer.
- Something that can be turned off again from the same screen.

Poor fits:

- Anything that needs to run in the background, root, or a new dependency.
- Things Android's Settings app already does well.

## Pull requests

1. Fork, then branch from `main`.
2. Keep changes focused: one feature or fix per pull request.
3. Follow the code's existing style:
   - Plain Java, no new libraries, no XML layouts. Build UI with the helpers in
     `MainActivity`'s *Rows and widgets* section.
   - Shell commands run on the `worker` thread. Update views with `main.post`.
   - Quote any value that comes from outside the app with `Shell.quote()`.
   - Short, plain-English UI text that says what will happen. Error messages should say what
     to do next.
   - Comments explain *why*, especially manufacturer quirks.
4. Add the feature to the home search (`features()` in `MainActivity`) if it opens a page.
5. Build the **release** variant and test on a real device. Mention which devices and Android
   versions you tested on.
6. Update the docs in `docs/` and the README if behaviour changes.

## Documentation

Docs live in `docs/` as Markdown, written to render on GitHub. Screenshots are in
`docs/images/screenshots/` (540 px wide, compressed PNG). Tile icons in `docs/images/tiles/`
are SVG versions of the app's vector drawables, so update them if you change a tile icon.

## Licence

By contributing, you agree that your contributions are licensed under the
[MIT licence](../LICENSE).
