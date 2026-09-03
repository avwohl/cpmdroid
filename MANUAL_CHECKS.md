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
they found is in **Verified**.

**Section 5 arrived the same day, from the other direction.** A second round
added the rest of the terminal parser and the per-cell attributes on a machine
with no Android SDK at all. That code is compiled - by host `clang++` and by
`kotlinc` against real Android framework classes, which `CHANGELOG.md`'s
1.22 preamble describes exactly - and it is unrun. Everything in section 5
is a first sighting, not a regression check.

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

---

## 5. The terminal parser and the attributes, first sighting

Nothing in this section has ever been drawn. An emulator answers all of it -
none of it needs the tablet - so this is the cheapest section in the file to
clear and the one most worth clearing first.

`R8`/`W8` are not involved; a stock boot plus `echo`-style output is enough.
The quickest way to feed the parser arbitrary bytes is a small `.COM` or a
`SUBMIT` file staged through `Imports/`, because `adb shell input text` drops
characters at the guest's rate - see the note at the top of this file.

1. **The four faces.** `ESC[1m` bold, `ESC[4m` underline, `ESC[1;4m` both,
   `ESC[0m` back. Check the grid does not shift under the bold text: the column
   positions come from the plain face alone, and a bold face with a wider
   advance should overhang its cell rather than move its neighbours. On a real
   GPU, not an argument.

2. **Reverse video on an untouched screen.** `ESC[7m` then some text, on a
   screen that has seen no other SGR. It must invert - black glyphs on green -
   rather than going invisible. This is the one place this port needed code the
   siblings did not: its default background is a sentinel meaning "paint no
   rectangle", and reversing it naively produces a foreground of "nothing".
   Then `ESC[27m` and confirm the text goes back to exactly what it was.

3. **Blink, and the cost of not blinking.** `ESC[5m` text, and watch it strobe
   at about two hertz. Then erase it - `ESC[2J` - and confirm it stops: the
   500 ms tick is supposed to end when the last blinking cell leaves the live
   screen. Watch the app's CPU or frame rate on a normal screen too and confirm
   nothing is repainting when nothing blinks.

4. **The scrolling region.** `ESC[1;20r` then fill past line 20 and confirm
   lines 21-24 hold still while the top scrolls. Then park the cursor below the
   region and confirm output there does not scroll anything. `ESC[r` puts it
   back.

5. **VT52.** `ESC[?2l` to enter, then `ESC Y` with two coordinate bytes to
   address the cursor, `ESC J` and `ESC K` to erase, `ESC <` to leave. Also
   confirm the auto-detection: from a cold ANSI screen, a bare `ESC A` should
   move the cursor up AND put the terminal into VT52, because receiving a
   VT52-exclusive escape is the signal.

6. **The editing commands**, which are the ones a real program will use:
   `ESC[3@` (insert 3 blanks), `ESC[3P` (delete 3), `ESC[3X` (erase 3),
   `ESC[2L` / `ESC[2M` (insert/delete lines). Check the vacated cells take the
   *current* background, not black - set one with `ESC[44m` first.

7. **The query replies go back to the guest.** `ESC[6n` must send the cursor
   position and `ESC[c` the device attributes. A CP/M program that reads them is
   the honest test; failing that, the answer arriving as typed input at the
   prompt is visible proof the path works. Confirm the reported column is never
   larger than the screen width - fill a line to the right margin first, which
   is the case that was wrong in the first draft.

8. **LF without CR.** Stage a text file with bare LF line endings into
   `Imports/`, `R8` it and `TYPE` it. It must NOT stair-step down and to the
   right. This is a behaviour change: before this round the column stayed put.

9. **`ESC c` (RIS).** Send it mid-session with a scrolling region set, VT52 on
   and a colour selected. Everything must go back to power-on - and the
   scrollback must SURVIVE, deliberately, because the user's history is not the
   guest's to discard.
