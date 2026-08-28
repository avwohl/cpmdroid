# Manual checks

Checks that need a person: a device or an emulator, keys pressed, a screen
watched. Nothing here can be settled by reading the source, which is why none of
it lives in `todo.txt` - that file keeps a one-line pointer at each of these.

**Delete a check once someone has run it.** The result belongs in `CHANGELOG.md`
under **Verified**, not here. A check that has been run and left in place turns
this file into the same accumulating record `todo.txt` was.

---

## 1. The zero-byte export (`c06fa58`)

Do this immediately after the first successful `assembleDebug` - `c06fa58` is
the newest source commit on `master` and has never been compiled by the NDK, so
the build is half of this check.

1. Build and install a fresh debug build. `./gradlew assembleDebug` (or
   `gradlew.bat assembleDebug`) needs no keystore, but `romwbw_emu` and
   `cpmemu` must be checked out beside this repository.
2. Boot to CP/M and make an empty file - `SAVE 0 EMPTY.TXT` at the prompt.
3. `W8 EMPTY.TXT`.
   - Right: a zero-byte `empty.txt` exists in the app's `Exports` folder, and
     `W8` prints the full `Exports` path it wrote to.
   - Wrong, and the bug this is checking for: `W8` reports success and nothing
     appears. The old code only reached `HOST_FILE_WRITE_READY` when the buffer
     had bytes in it, so the guest was told about a file that was never created.
4. `W8` a file with contents in it - `R8.COM` will do - and confirm the exported
   copy is byte-for-byte the size CP/M reports. This is the regression half: the
   state test replaced a byte-count test, and a wrong fix breaks normal exports
   rather than empty ones.

Until steps 1 to 4 have all happened, `CHANGELOG.md`'s **Verified** section must
keep saying this fix was not built. Do not quietly promote it.

---

## 2. Keyboard-aware scrolling and scrollback (`690da30`)

The oldest open item in the repository. The work is committed and on
`origin/master`, and it has never gone into a published build or been watched by
a person, so a clean install from the store still shows the black band above the
keyboard and still drops lines.

**Install a fresh build first.** There is a release-keystore APK on the test
tablet from 2026-07-25 labelled versionCode 19 / 1.18 but built from the fix -
it is not the published 1.18, and it predates `a523d40`, `9b68ab1` and
`c06fa58`, all three of which touched `TerminalView.kt` or `MainActivity.kt`. It
is the wrong build to check against.

An API 36 AVD boots the app and CP/M in about forty seconds and reaches all six
steps. Step 4 is worth repeating on the tablet - Samsung SM-X200, Galaxy Tab A8,
Android 14 - because that is where the third-party IME (Smart Keyboard Pro) is,
and a stock AVD keyboard is not evidence about it. Insets were never the
problem there (`ime.bottom=636` was measured in July); the drawing is.

1. **Fresh boot, portrait.** The live screen is fixed at 24 rows (`MIN_ROWS` in
   `TerminalView.kt`), prompt at the bottom, history above.
   - Right: content fills the view.
   - Wrong: a giant black void below the content. That is the bug this design
     replaced and it was worst in portrait.
   Then rotate to landscape and look again - rotation resets `fullHeight`, so
   the font is re-sized from the full keyboard-hidden height.
2. **Make output that scrolls off.** Boot to CP/M and run `DIR`, or press D or L
   at the boot menu.
3. **Drag down** in the terminal to page back into history. Drag up, or let new
   output arrive, and it must snap back to the live prompt. A tap with no drag
   must still raise the keyboard. (`TerminalView.onTouchEvent` is the gesture;
   `processOutput` resetting `userScrollUp` is the snap-back.)
4. **Tablet: raise the keyboard** and confirm the prompt stays visible. When the
   viewport is shorter than 24 rows, `onDraw` scrolls within the live screen to
   the cursor rather than shrinking the font. The user confirmed this half in
   July, before scrollback existed; scrollback changed the drawing path
   underneath it.
5. **Settings, scrollback slider.** `SCROLLBACK_CHOICES` is 0 (Off), 100, 250,
   500, 1000, 2000, 5000, 10000, and the value reaches `TerminalView` live. With
   history on screen, lower it: the view must not be left scrolled past the end,
   and choosing Off must clear the history rather than leave a screen the user
   can still drag back through. The setter clamps against `historyChars.size`,
   so this is about how it feels, not whether it crashes.
6. **Copy with scrollback non-empty.** `copyScreenToClipboard` prepends the
   history, so the clipboard must include lines that have already left the
   screen.

Logs while doing it:

    adb logcat -d | grep -E 'TerminalView|MainActivity: (Insets|Keyboard)'

`TerminalView` logs `onSizeChanged` and `calculateFontSize` - watch for `rows=24`
and a sane font size. `MainActivity` logs the inset and keyboard transitions.

Left open deliberately, and only a device can settle them: scroll direction feel,
how many lines a drag should move, how much blank space sits above the prompt on
a fresh boot with empty history, and whether a drag should scroll at all while
the keyboard is up.
