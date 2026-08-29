# Manual checks

Checks that need a person: a device or an emulator, keys pressed, a screen
watched. Nothing here can be settled by reading the source, which is why none of
it lives in `todo.txt` - that file keeps a one-line pointer at each of these.

**Delete a check once someone has run it.** The result belongs in `CHANGELOG.md`
under **Verified**, not here. A check that has been run and left in place turns
this file into the same accumulating record `todo.txt` was.

Three checks left this file on 2026-08-29 under that rule - the zero-byte
export, the ANSI colour parser, and most of the keyboard and scrollback work.
They were run on an API 36 emulator against a build made the same day, and what
they found is in **Verified**. What is below is what an emulator could not
answer.

One note for whoever runs these, because it cost this round twice: `adb shell
input text` fires each character as a separate command and the guest drops some
of them at that rate. It is the harness, not the app - the native queue behind
`emu_console_queue_char` is unbounded and mutex-guarded. Put a delay between
characters, and read back what actually landed on screen before believing a
command ran.

---

## 1. The tablet, and the third-party IME

The emulator answered the drawing question with its own stock keyboard. It
cannot answer it for the tablet: **Samsung SM-X200, Galaxy Tab A8, Android 14**,
whose IME is **Smart Keyboard Pro**. Insets were never the problem there -
`ime.bottom=636` was measured in July - so this is about what gets drawn, and
the drawing changed again on 2026-08-29 when `bgBuffer` and a per-cell
background rect landed under it.

**Install a fresh build first.** There is a release-keystore APK on that tablet
from 2026-07-25 labelled versionCode 19 / 1.18. It is not the published 1.18, it
was built from the fix, and it now predates seven commits that touched
`TerminalView.kt` or `MainActivity.kt`. It is the wrong build to check against.

1. **Raise the keyboard at the CP/M prompt.** The prompt must stay visible. When
   the viewport is shorter than 24 rows, `onDraw` scrolls within the live screen
   to the cursor rather than shrinking the font - on the emulator that showed as
   `rows=24` with the font unchanged at 29.5 and `fullHeight` kept at its
   keyboard-hidden value. Watch for the same in `logcat`:

       adb logcat -d | grep -E 'TerminalView|MainActivity: (Insets|Keyboard)'

2. **Rotate with the keyboard up, then down.** Rotation resets `fullHeight`, so
   the font is re-sized from the full keyboard-hidden height. A third-party IME
   that reports its insets late is the case a stock keyboard does not produce.

3. **Colour with the keyboard up.** Anything that sets a background now paints a
   rect per cell. Confirm a coloured screen still scrolls to the cursor rather
   than leaving a band, and that nothing tears on a slower GPU than an emulator's.

---

## 2. The four questions only a hand can answer

Unchanged by the 2026-08-29 round, and deliberately still open. None of them is
a bug; each is a judgement about feel that a screenshot cannot make.

1. Which way should a drag scroll? Today: drag DOWN goes back into history.
2. How many lines should one drag move?
3. How much blank space should sit above the prompt on a fresh boot with empty
   history? On an AVD a fresh boot shows a large black band above the first
   line, because the live screen is anchored at the bottom and history is empty.
   It is by design and it does look odd.
4. Should a drag scroll at all while the keyboard is up?

---

## 3. Sharing to a real app

The share sheet was opened on an emulator and offered Quick Share, Chrome,
Drive, Messages and CPMDroid itself, with a valid
`content://com.awohl.cpmdroid.fileprovider/...` Uri and no `SecurityException`.
**No share was completed to a real recipient**, because an emulator has no
account signed in to any of them.

1. `W8` a file, open **File transfer** from the toolbar, tap the row, **Share**.
2. Send it to something that will actually receive: Gmail to yourself, Drive,
   or a messaging app.
3. Confirm the file arrives with the right name and the right bytes. `r8.com`
   is a good subject - it is 1792 bytes and any truncation is obvious.
4. Repeat from the **Imports** section, which shares through the same provider
   entry.

---

## 4. Receiving from a real app

`ImportReceiverActivity` was exercised on an emulator only by an explicit
`am start`, which is the attacker's shape, not the user's. The user's shape is
a share sheet in somebody else's app.

1. In Gmail, Drive or a file manager, pick a small `.txt` and share it to
   **CPMDroid**.
2. It must land in `Imports/` under a CP/M-legal 8.3 name, and a toast must say
   which name it got. `My Long Archive.tar.gz` becomes `my-long-.gz`.
3. Boot CP/M and `R8` it under that name.
4. Share the same file twice and confirm the "replaced" wording appears the
   second time - 8.3 makes collisions ordinary, and silence would be the bug.
