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

---

## 6. The v0 disk-name migration, on a device that has state to lose

The pass in `V0Migration.kt` renames the user's downloaded images, the copies
they have written to from inside CP/M, and the four `disk_slot_N` preferences
that name them. `app/src/test/.../V0MigrationTest.kt` covers the name mapping
and the two directories against temporary directories on a JVM. What it cannot
reach is any of what follows: an upgrade in place over real pre-v0 state,
external storage that is not there, and a restore from backup.

**Build an old state first, and do not skip this.** These checks are worthless
against a fresh install, because a fresh install has nothing to migrate.
Install a build from before this change (`versionCode 27` or earlier), let it
download, assign all four slots to four *different* disks in Settings, boot, and
then **write something from inside CP/M** - `W8` a file, or copy one with `PIP` -
so a `ModifiedDisks/` copy exists. Confirm it does:

    adb shell ls -l /sdcard/Android/data/com.awohl.cpmdroid/files/ModifiedDisks

1. **Upgrade in place.** Install the new build over it - `adb install -r`, not
   an uninstall, which would take the state with it. Then:
   - `adb logcat -d | grep 'v0 disk-name migration'` must report
     `complete=true` and a `renamed=` count matching the files that were there.
   - All four slots in Settings must still name a disk, now reading
     `hd1k_*-v0-3.5.1.img`. A slot that has gone empty, or a slot 0 that has
     become the Combo when the user had put something else there, is the
     failure this whole change exists to prevent.
   - Boot. The disk that comes up must be the one with the user's file on it,
     **not** a pristine copy of the same image. `DIR` for the file written
     above; that is the only way to tell the two apart from the screen.
   - The catalog dialog must still show the downloaded ticks, and no download
     must start by itself. A tick that has gone means the file rename did not
     land while the preference did.
   - Both directories must hold `-v0-3.5.1` names and nothing else, and the
     byte counts must be unchanged - a rename keeps the size and the
     modification time; a copy would not.

2. **Storage that is not there.** Repeat the upgrade with external storage
   unavailable (an emulator with the SD card ejected is the easiest way).
   `getExternalFilesDir(null)` returns null and the pass must report
   `complete=false` and rename nothing. Then make storage available again,
   relaunch, and confirm the pass runs and completes *then*: the flag is only
   written on a completed pass, and a device that stamped it while it could not
   see the files would be left permanently half-migrated.

3. **Restore from backup.** `android:allowBackup="true"` and there are no
   extraction rules, so the 1 KB preferences file and a 51 MB image do not
   travel together. Force a backup and restore of pre-v0 state
   (`adb shell bmgr backupnow com.awohl.cpmdroid`, then wipe and restore) and
   launch. Whatever arrives, nothing must be deleted and the app must boot: a
   slot naming a file that did not come back is expected, and re-downloading it
   is the user's decision to make, not the migration's.

## 7. The two-level catalog and the RomWBW release picker

`DiskCatalogRepository` no longer holds a release tag. It fetches
`index-v0.json`, filters it by asking the emulator core, fetches the selected
release's catalog, and takes every download URL from that catalog's `base_url`.
`app/src/test/.../CatalogParsingTest.kt` covers the parsers against byte-for-byte
copies of the published documents and has been run - 20 tests, all passing, on a
host JVM. None of what follows can be settled that way: it needs the network, the
device's storage and the three new JNI calls, which nothing but a running device
links to their C++ side.

**Do section 6 first if you are doing both.** These checks assume the disks on
the device are already on `-v0-3.5.1` names.

1. **The first fetch, and that it is the v0 one.** Fresh install, then
   `adb logcat -d | grep -E 'RomwbwSupport|CatalogLoader|catalog fetched'`.
   Expect the bundled ROM to read `3.5.1`, the core to report `3.5.1, 3.6.0`,
   and the catalog line to name a generation. Then confirm no ioscpm URL is
   requested at all - the point of the change is that nothing interpolates a
   tag any more:

       adb shell ping -c1 github.com >/dev/null; adb logcat -c
       # browse the catalog, then:
       adb logcat -d | grep -i ioscpm     # must find nothing from this app

2. **The three natives are actually bound.** `JniNameParityTest` compares the
   two *source* name lists and would fail the build on a typo, but it cannot see
   what the NDK put in the built `.so`, and minification being off means R8 will
   not either - so a binding that is wrong for any other reason is still an
   `UnsatisfiedLinkError` at the first call and nowhere earlier. Open Settings
   once (the RomWBW row calls all three) and confirm no `UnsatisfiedLinkError`
   in logcat and that the row reads a version rather than being blank.

3. **The version picker, and the preview marking.** Settings -> RomWBW Release
   -> Change. Both releases must be listed, 3.6.0 must be marked `PREVIEW` and
   `no ROM in this build`, 3.5.1 must be marked as matching the bundled ROM, and
   the currently selected one must be pre-checked.

4. **A release switch loses nothing.** With all four slots assigned under 3.5.1,
   switch to 3.6.0, accept the mismatch warning, and confirm the four slots read
   `(empty)` and the toast says no disks are assigned yet. Leave Settings, come
   back, switch back to 3.5.1, and confirm **all four slots are exactly what
   they were**, with the same filenames. Then check that nothing was deleted:

       adb shell ls /sdcard/Android/data/com.awohl.cpmdroid/files/Disks
       adb shell ls /sdcard/Android/data/com.awohl.cpmdroid/files/ModifiedDisks

   Every file that was there before the switch must still be there. This is the
   check that would have caught the iOS behaviour this app is deliberately not
   copying, where a switch deleted the library.

5. **Downloading under 3.6.0.** Still on 3.6.0, download one small disk (not the
   51 MB combo). It must land beside the 3.5.1 files as `-v0-3.6.0.img`, with
   neither shadowing the other, and assigning it must fill a 3.6.0 slot only.
   Booting it against the bundled 3.5.1 ROM is *expected* to print
   `*** WARNING: HBIOS/CBIOS Version Mismatch ***`; confirm that it does, since
   that warning is what the picker's confirmation promises.

6. **Three failures, three messages.** The one string this replaces said "check
   your internet connection" for all of them.
   - Aeroplane mode, then browse the catalog: the message must name the index.
   - With the network up, that is as far as a device can go without a proxy. If
     you have one, serve a 404 for `catalog-v0-3.5.1.json` while leaving the
     index reachable, and confirm the message names the *release's catalog*, not
     the index; and serve a truncated catalog and confirm the message says it
     did not verify and that nothing already downloaded was touched.
