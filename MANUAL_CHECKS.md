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

**And one standing rule for anything scripted, because its absence shipped a bug
in the sibling port: a driver must not bypass the UI gating it is meant to be
checking.** z80cpmw 1.0.26-beta greyed out its Start menu item on every install
that had no ROM - a deadlock, since Start is the only thing that fetches a ROM -
and its whole driven run passed anyway, because the driver posted `WM_COMMAND`
straight to the window and never looked at the menu state. A person installing
the package found it. The Android shape of the same mistake is `performClick()`,
from an instrumentation thread or a debug hook: it calls the click listener
whether or not the view is enabled, exactly as a posted `WM_COMMAND` does. A
real touch does not - a disabled `View` drops it in `dispatchTouchEvent` - so
`adb shell input tap` is honest where `performClick()` is not. When a check says
the user CAN do something, read the state as well as sending the event:

    adb shell uiautomator dump /sdcard/window_dump.xml
    adb shell cat /sdcard/window_dump.xml   # node with content-desc="Start/Stop"

and look at that node's `enabled` attribute rather than at what the app did
afterwards.

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

**And know where the release will move by itself, because 3.5.1 is no longer
where a device settles.** The migration renames into the `V0_LEGACY_ROMWBW`
namespace, still `"3.5.1"`, while the index marks **3.6.0** `default: true` - and
an install that has never picked a release by hand follows that default rather
than a constant compiled into the app. `selectedRomwbwVersion()` answers 3.5.1
only until an index has been fetched, and nothing fetches one at launch: the move
happens the first time a catalog is read, which is browsing the disk catalog,
opening the ROM list, or a first launch's starter-disk step. So a migrated device
comes up on 3.5.1 with its migrated slots and then finds itself on 3.6.0 with
four empty slots the first time somebody opens the disk catalog. That is the
design working - each release keeps its own slots and nothing is deleted - but it
is not what the 1.27 wording of these checks expected, and a checker who does not
know it will report it as a loss.

1. **The first fetch, and that it is the v0 one.** Fresh install, then
   `adb logcat -d | grep -E 'MainActivity|CatalogLoader'`. Nothing logs a
   bundled ROM any more, because there is not one. Expect
   `Selected RomWBW 3.5.1 (following the catalog); core supports 3.5.1, 3.6.0`
   before anything is fetched - that 3.5.1 is `V0_LEGACY_ROMWBW`, the namespace
   an upgrading user's slots were written under, and not a release this build
   prefers - then `Following the catalog: RomWBW 3.6.0 (was 3.5.1)` once the
   index has been read, and a `catalog fetched` line naming a generation. Then
   confirm no ioscpm URL is requested at all - the point of the change is that
   nothing interpolates a tag any more:

       adb shell ping -c1 github.com >/dev/null; adb logcat -c
       # browse the catalog, then:
       adb logcat -d | grep -i ioscpm     # must find nothing from this app

2. **The natives are actually bound.** `JniNameParityTest` compares the two
   *source* name lists and would fail the build on a typo, but it cannot see
   what the NDK put in the built `.so`, and minification being off means R8 will
   not either - so a binding that is wrong for any other reason is still an
   `UnsatisfiedLinkError` at the first call and nowhere earlier.

   Where that first call happens has moved. `logRomwbwSelection()` runs in
   `onCreate` and asks for `romwbwSupportedList()`, so an unbound symbol now
   takes the app down at launch instead of waiting for somebody to open
   Settings, and `romwbwReleaseSupported()` is asked about every index entry on
   the first fetch. Launch, confirm the `Selected RomWBW ... core supports ...`
   line is in logcat with no `UnsatisfiedLinkError` beside it, then open the
   release picker, which is the cheapest thing that fetches an index.

   The third, `romwbwReleaseOfImage()`, has no Kotlin caller left - it read the
   release out of the bundled ROM - so nothing exercises it and this check can
   say nothing about it. The name parity test still compares it in both
   directions, and that is now all there is guarding it.

3. **The version picker, and what each row says.** Settings -> RomWBW Release
   -> Change. Both releases must be listed. Each row reads the release's label,
   then its published `status` - or `PREVIEW - not yet recommended` where the
   index says so - then `ROM will be downloaded` before that release's ROM has
   been fetched and `ROM downloaded` after. **No row may say anything about a
   ROM in the app.** The `ROM bundled in the app` marking went with the file it
   described, and a row claiming it back is the regression this section watches
   for. The currently selected release must be pre-checked, and on a machine
   that has never picked one by hand that is the index's `default: true` entry -
   3.6.0 today, not 3.5.1. (3.6.0 was published `preview` when this check was
   written and is `stable` now, so the `PREVIEW` marking may legitimately be
   absent - what must not happen is a row claiming a status the index does not
   publish.)

   Read the note under the row before opening the dialog, too. It says whether
   this release's ROM is on the device and which file it boots.

4. **A release switch loses nothing.** With all four slots assigned under the
   release the device is on, switch to the other one - 3.6.0 to 3.5.1, on a
   machine following the catalog; section 8 check 5 is the ROM download that
   switch now asks for - and confirm the four slots read `(empty)` and the toast
   says no disks are assigned yet. Leave Settings, come back, switch back, and
   confirm **all four slots are exactly what they were**, with the same
   filenames. Then check that nothing was deleted:

       adb shell ls /sdcard/Android/data/com.awohl.cpmdroid/files/Disks
       adb shell ls /sdcard/Android/data/com.awohl.cpmdroid/files/ModifiedDisks

   Every file that was there before the switch must still be there - including
   both releases' `.rom` files, which live in `Disks/` beside the images. This
   is the check that would have caught the iOS behaviour this app is
   deliberately not copying, where a switch deleted the library.

   One thing this costs that it did not in 1.27, and it is deliberate: the switch
   fetches the new release's ROM before it happens, so the first switch in each
   direction needs the network.

   What it does NOT cost, and used to: there is no pin. `selected_romwbw.v0` is
   one key and it is the whole preference, so a device left on 3.5.1 by this
   check is simply on 3.5.1, and this same row puts it back. An earlier draft of
   1.29 had a separate `romwbw_pinned_by_user.v0` flag that only this dialog
   could set and that nothing could clear - a device used for this check would
   have been stuck off the catalog until its data was wiped. It is gone.

5. **Downloading under 3.6.0.** Still on 3.6.0, download one small disk (not the
   51 MB combo). It must land beside the 3.5.1 files as `-v0-3.6.0.img`, with
   neither shadowing the other, and assigning it must fill a 3.6.0 slot only.
   Boot it and confirm CP/M prints **no** `HBIOS/CBIOS Version Mismatch`
   warning: the 3.6.0 disks are now running under the 3.6.0 ROM, and that
   warning appearing is the single clearest sign the ROM download did not
   happen. (This inverts what this check asked for in 1.27, when the app had no
   way to get any ROM but the bundled one.)

6. **Three failures, three messages.** The one string this replaces said "check
   your internet connection" for all of them.
   - Aeroplane mode, then browse the catalog: the message must name the index.
   - With the network up, that is as far as a device can go without a proxy. If
     you have one, serve a 404 for `catalog-v0-3.5.1.json` while leaving the
     index reachable, and confirm the message names the *release's catalog*, not
     the index; and serve a truncated catalog and confirm the message says it
     did not verify and that nothing already downloaded was touched.

---

## 8. The ROM, which now comes only from the catalog

There is no ROM in this package. `app/src/main/assets/` holds the help topics and
nothing else; `emu_avw.rom` was deleted, and `EmulatorSettings.romName`,
`RomRequirement` and `RomwbwSupport.bundledRomRelease()` went with it. Every ROM
is downloaded from the selected release's catalog `roms[]` and checked against
the `size` and `sha256` that catalog publishes - on the way in, and again from
the bytes handed to the emulator on every load afterwards. `RomSelectionTest`
(12 tests) and `CatalogParsingTest` (24) cover which entry is chosen and what the
published documents say, on a host JVM. None of what follows can be settled that
way: it needs the network, the device's storage, and a guest that boots.

The rule all of this is checking is one sentence: **starting a machine on RomWBW
X requires X's ROM, fetched from X's catalog and verified against what that
catalog publishes.** There is nothing left to fall back to, and nothing must be
added back: pairing one release's disks with another release's ROM is what makes
CP/M print `*** WARNING: HBIOS/CBIOS Version Mismatch ***` and then misbehave,
and removing that pairing is the whole reason the ROM is fetched at all. So a
check below that ends in a boot the app cannot account for is a failure even when
the guest looks perfectly happy.

The cost is named rather than discovered, and check 2 is where somebody finally
looks at it: a first launch with no network cannot start.

**Do sections 6 and 7 first if you are doing all three.**

1. **Start must be pressable on an install that has no ROM.** First in this
   section, because it is the check that was missing and the sibling port
   shipped the bug it would have caught: z80cpmw 1.0.26-beta greyed Start out
   whenever it had no ROM, which is a deadlock rather than a disabled control,
   since pressing Start is the only thing that fetches one. Fresh install,
   launch, dismiss the ROM dialog, and look at the play button. It must be
   **enabled**, and pressing it must bring the dialog back rather than doing
   nothing.

   What makes that work today is the `!romLoaded -> startMachine()` arm of the
   click listener in `setupToolbar()`. Nothing in `MainActivity` assigns
   `playPauseButton.isEnabled` at all, and `updateStatus()` changes only the
   icon - so the regression to watch for is any new code that ties the button's
   enabled state, its visibility or its listener to `romLoaded`, to a stored ROM
   claim, or to a ROM file being on disk.

   **Do not settle for the scripted answer**; see the driver rule at the top of
   this file. Firing the listener directly is exactly how the sibling's driven
   run passed on the broken build.

2. **A first launch with no network says why, and does not hang.** This is the
   cost this change deliberately accepts, so it gets checked rather than
   discovered by somebody on a plane. Fresh install, aeroplane mode on before
   the first launch, then launch.

   Expect the status strip to read `ROM needed` in orange and a dialog titled
   **RomWBW 3.5.1 needs its ROM**, whose message opens "RomWBW 3.5.1 has no ROM
   on this device yet" and then says that CPMDroid downloads the ROM for the
   release it is set to and checks it against the catalog's hash before booting
   it. One button, **Download ROM**, and the dialog can be dismissed. What must
   not happen is a hang, a blank terminal with nothing said, or a boot.

   The release named there is 3.5.1 and that is not a fault: `V0_LEGACY_ROMWBW`
   is what `selectedRomwbwVersion()` answers until an index has been fetched, and
   offline no index arrives. It is the namespace an upgrading user's disk slots
   live in, not a claim about which release this build prefers.

   Then press **Download ROM** while still offline. Expect the download overlay
   to appear reading "Downloading the RomWBW 3.5.1 ROM" over "Reading the
   catalog...", the overlay to go away, and the same dialog to come back with the
   index failure in it - "Could not read the catalog index: ..." - with the
   status strip still on `ROM needed`. Do it twice more and confirm it is still
   that, and not a crash, an overlay left on screen, or silence.

3. **A first launch with the network up asks twice, and that is the design
   working.** Same fresh install, aeroplane mode off. Expect, in order: the
   `RomWBW 3.5.1 needs its ROM` dialog; **Download ROM** fetching
   `emu_avw-v0-3.5.1.rom`; the starter-disk step, which is where an index is read
   for the first time and which logs `Following the catalog: RomWBW 3.6.0 (was
   3.5.1)`; the 3.6.0 combo image downloading; and then a **second** dialog,
   `RomWBW 3.6.0 needs its ROM`, whose Download fetches `emu_avw-v0-3.6.0.rom`
   and boots.

   Two ROM downloads is 1 MB, and it is what the code does rather than a fault:
   the release is only resolved against the index once a catalog has been
   fetched, and the starter-disk step is the first thing that fetches one. What
   matters is the guard at the end of it, which logcat must show:

       The catalog fetch moved the selection 3.5.1 -> 3.6.0;
       resolving its ROM rather than starting on the old one

   Without that, the machine would boot 3.6.0's disks on 3.5.1's ROM and the only
   symptom would be the mismatch banner inside CP/M. Afterwards:

       adb shell ls -l /sdcard/Android/data/com.awohl.cpmdroid/files/Disks/*.rom
       # emu_avw-v0-3.5.1.rom and emu_avw-v0-3.6.0.rom, 524288 bytes each

   Report rather than tick if the first dialog does not appear, or if only one
   ROM is fetched: either would mean the release is being resolved somewhere this
   reading of the code does not have it.

4. **Every later launch is offline.** With the machine booting, turn aeroplane
   mode on, kill the app and relaunch. It must boot with no dialog and no
   network: the catalog's claims about the ROM are stored beside the file when it
   is fetched, and `readVerifiedRom` re-checks the file against them from the
   bytes it is about to hand the emulator. logcat must read `ROM loaded from the
   catalog download for RomWBW 3.6.0 (524288 bytes)` with no fetch before it.
   This is the other half of check 2, and it is the half that makes the cost
   there a one-off rather than a standing requirement.

5. **Switching release fetches the ROM first.** Settings -> RomWBW Release ->
   Change -> the release you are not on (3.5.1, on a machine following the
   catalog) -> Select. Expect a dialog headed "Switch to RomWBW 3.5.1?" saying
   that release has its own ROM which has not been downloaded, that it is about
   half a megabyte and is checked against the catalog's hash before it is ever
   used, and that nothing is deleted; then **Download and switch**; then a
   "Preparing RomWBW 3.5.1" progress dialog that reaches 100%; then a toast
   reading `Downloaded the RomWBW 3.5.1 ROM`, and the switch. Confirm:

       adb shell ls -l /sdcard/Android/data/com.awohl.cpmdroid/files/Disks/*.rom
       # emu_avw-v0-3.5.1.rom, 524288 bytes

   Then leave Settings and confirm the machine reboots onto it: logcat must say
   `RomWBW release changed 3.6.0 -> 3.5.1; reloading the ROM as well as the
   disks`, then `ROM loaded from the catalog download for RomWBW 3.5.1` and
   `Rebooting onto the newly loaded ROM`, and the guest must print a 3.5.1
   banner. The three ways this can be wrong all look like success from the
   Settings screen alone: the ROM downloaded but not loaded, loaded but not
   rebooted onto, or the switch applied with the download having failed.

   Switch back afterwards and watch the cheap case once: the ROM is already
   there, so the toast says `Verified the RomWBW 3.6.0 ROM` rather than
   `Downloaded`, and nothing crosses the network.

6. **A failed fetch does not switch.** Open Settings -> RomWBW Release -> Change
   **with the network up** - the picker fetches the index, so it cannot be opened
   in aeroplane mode at all - then turn aeroplane mode on and only then choose
   the other release and Select. The switch must NOT happen: the dialog must name
   the index as unreachable, and the RomWBW row must still read the release you
   started on. The app pointed at a release it cannot start on is the state this
   ordering exists to avoid.

   Do it again with the connection dropped mid-transfer (aeroplane mode on while
   the progress dialog is moving). The message must say the ROM **could not be
   downloaded** and name what went wrong; it must not say it did not verify,
   which is a different fault with a different fix.

7. **The ROM picker, and that the pick is obeyed rather than merely stored.**
   Settings has a **ROM** row at the top with its own Change button. It replaces
   a read-only "Bundled ROM: emu_avw.rom" label, and it is the only thing that
   has ever made `emu_rcz80` reachable: both releases have published it since the
   migration and no build could select it.

   - Change. If no catalog has been read on this screen yet, a "Reading the
     RomWBW 3.6.0 catalog..." dialog comes first. The list must be that release's
     published `roms[]` - two entries today, **EMU AVW** marked `default` and
     **EMU RCZ80** - with `downloaded` beside whichever one is on the device. Two
     hardcoded rows, or a row naming a file this release does not publish, is the
     regression.
   - Pick **EMU RCZ80** and Select. The toast names it, the row changes to
     `EMU RCZ80`, and the note under it reads `emu_rcz80-v0-3.6.0.rom. Not on
     this device yet; CPMDroid fetches it before it starts.`
   - Leave Settings. The running machine keeps the ROM it started on, because
     only a RELEASE change re-resolves the ROM in `onResume`. That is correct
     rather than the pick being lost, and it is worth knowing before reporting
     the next step as a delay.
   - Kill the app and relaunch. Expect `RomWBW 3.6.0 needs its ROM` and, on
     Download, `emu_rcz80-v0-3.6.0.rom` fetched and booted. The message reads
     "has no ROM on this device yet" even though `emu_avw-v0-3.6.0.rom` is
     sitting right there, because the claim on file is for the other ROM;
     that wording is expected here. logcat must carry `RomWBW 3.6.0 ROM on disk
     is emu_avw, but emu_rcz80 is selected; fetching it` and then `RomWBW 3.6.0
     ROM is EMU RCZ80 (emu_rcz80, emu_rcz80-v0-3.6.0.rom, 524288 bytes), chosen
     from 2 published`.
   - Relaunch once more and confirm it comes up on emu_rcz80 with no dialog and
     no download.

   **The trap this guards, and what losing it looks like.** A verified ROM
   already on disk used to answer the "which ROM" question before the catalog was
   ever opened - that fast path is what makes an offline launch possible - so a
   pick could be stored in preferences and then silently ignored on every launch.
   The guard is `RomClaim.romId` and the two `it.romId == wantedRomId` tests that
   read it, in `startMachine()` and in `fetchRomForRelease()`. Losing it does not
   produce an error: the picker goes on saying EMU RCZ80, no dialog appears,
   `Disks/` never gains `emu_rcz80-v0-3.6.0.rom`, and the machine boots emu_avw
   as though nothing had been chosen. So check the file list and the log line,
   not the setting.

   One more thing while the dialog is open: what gets stored is the catalog
   **id**, never the filename shown beside it. The two are the same type and look
   alike, and seeding a filename into the field that holds an id is the bug that
   shipped in the sibling port and corrupted the preference on OK. Round-trip it
   - pick EMU RCZ80, leave Settings, come back, Change - and the list must reopen
   with the EMU RCZ80 row checked, not with a row reading
   `emu_rcz80-v0-3.6.0.rom`.

8. **A deleted ROM stops the machine and names what is missing.** With 3.6.0
   selected and working, kill the app and delete its ROM:

       adb shell rm /sdcard/Android/data/com.awohl.cpmdroid/files/Disks/emu_avw-v0-3.6.0.rom

   Relaunch. Expect a dialog titled **RomWBW 3.6.0 needs its ROM** whose message
   names the file - "RomWBW 3.6.0 needs its ROM, emu_avw-v0-3.6.0.rom, which is
   not on the device" - the status strip reading `ROM needed` in orange, and the
   machine NOT running behind the dialog.

   **There is one button, Download ROM, and there must not be a second.** The
   1.28 version of this check expected a `Use RomWBW 3.5.1` button beside it and
   a boot on the ROM inside the app. Neither exists now and neither is to be
   re-added under any name: there is no ROM in the package, and a fallback would
   put 3.6.0's disks under 3.5.1's ROM with a warning line inside CP/M as its
   only symptom. Booting anything at all here is the failure.

   Dismiss the dialog rather than answering it, and confirm the status strip
   stays on `ROM needed` and nothing starts. Then press the play button and
   confirm the same dialog comes back - that is the recovery, and it is the same
   path check 1 is about. Finally take **Download ROM** and confirm the file is
   fetched again and the machine boots.

   Switching release is the other way out of this state and is worth confirming
   once from here: Settings -> RomWBW Release -> Change, pick 3.5.1, and the
   machine must come up on it without the play button being touched.

9. **A corrupt ROM is refused rather than run.** With 3.6.0 selected and its ROM
   present, corrupt it and relaunch:

       adb shell "dd if=/dev/zero \
         of=/sdcard/Android/data/com.awohl.cpmdroid/files/Disks/emu_avw-v0-3.6.0.rom \
         bs=1 seek=1000 count=16 conv=notrunc"

   Expect the same dialog, this time saying the ROM did not verify and naming
   both hashes - "sha256 <what was read>, the catalog says <what was published>".
   Take **Download ROM**: the fetch must replace the file and the machine must
   boot. Nothing may start on any other ROM in the meantime; if something does,
   the whole point of this release is gone and the only visible symptom would
   have been a warning line inside CP/M.

10. **Truncation is caught too, and by size before hash.** Same again with
    `dd ... bs=1024 count=8 > file` to leave a short file. The message must say
    the byte count - "8192 bytes, the catalog says 524288" - and not a hash.

11. **The ROM does not pollute the disk list.** With both releases' ROMs
    downloaded, open the catalog dialog under each release. No `.rom` file may
    appear as a row, and the downloaded ticks on the images must be unchanged -
    `getDownloadedDisks()` filters on `.img`, and this is the check that it still
    does.

12. **Storage unavailable.** With the device's external storage unmounted or
    otherwise unavailable, launch on 3.6.0. It must report a ROM it cannot find
    rather than crash, and - since there is nowhere else a ROM can come from - it
    must not start at all.
