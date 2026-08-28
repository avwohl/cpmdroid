# Changelog

## Unreleased

Synced to emulator core **v1.36**, and took the four keyboard and terminal
gaps the cross-port sweep found here. Built and run this time - on a Windows
machine with the SDK, the NDK and an API 36 emulator - which is what 1.19
below could not be. One bullet is outside that: the zero-byte export fix, last
under **Fixed**, was written afterwards on a machine with no SDK, and has never
been through the NDK or onto a device. It says so where it is, and so does
**Verified**. **The build** section is outside it in the other direction: it was
written on that same machine, and the wrapper it adds was run there as far as
`--version` and no further.

### The core sync

- **`emu_host_path_caps()` is defined, and that is what makes the port build
  at all.** The v1.36 core declares this function and deliberately does not
  define it, so a port that syncs without supplying it fails to link:
  `undefined symbol: emu_host_path_caps()` from `hbios_dispatch.cc`. It
  answers HBIOS `HBF_HOST_CAPS` (0xE9), the probe the new `W8.COM` makes
  before it will hand a host path to the emulator, and `W8` believes the
  answer - which is why the assertion has to be written by the code it is
  about. It replaced a design where the core returned the bit as a constant,
  under which a port that had never thought about guest paths claimed to be
  safe just by compiling.
- **The guest path is now reduced in the C++ shim, not only in Kotlin.** Both
  `emu_host_file_open_read()` and `emu_host_file_open_write()` cut the
  incoming string to a single leaf component before anything else sees it,
  using a copy of the shared `emu_host_path_basename()` (CMakeLists does not
  compile `emu_io_common.cc`; it would collide on ten symbols this port
  defines for Android). The Kotlin checks are still there and still run, but a
  UI layer was the wrong place for the only copy: `emu_host_path_caps()`
  speaks for the shim, and a second consumer of the write name would not have
  inherited a guarantee that lived in the caller. See `romwbw_emu`
  `docs/DOWNSTREAM_2026-08-25.md` section 0 for the iOS bug this shape exists
  to prevent - there an unreduced `..` reached `removeItem` and took the
  user's entire disk library with it.
- **The exported name is lowercased**, as the CLI and browser backends do. The
  CCP uppercases the whole command line before the emulator sees it, so the
  typed case is already gone and the convention is all that is left; a backend
  that picks differently makes the same `W8` command produce differently-named
  files on different front ends.
- **`emu_host_file_get_write_name()` reports the effective destination**, per
  the tightened v1.36 contract: the full `Exports/` path rather than an echo
  of what the guest asked for. `W8` prints that string, and on Android it
  answers the hardest question a transfer raises - `Exports` lives under
  `getExternalFilesDir()`, which the stock Files app has hidden since Android
  11. Kotlin hands the path down once at startup through a new
  `nativeSetHostExportsDir`, since the C++ cannot ask Android where that
  folder is. Visible to users once the disk images carry the `w8.com` that
  asks (`HBF_HOST_GETNAME`, 0xE8).
- **Deleted `emu_console_check_ctrl_c_exit()`.** v1.36 removed the declaration
  from `emu_io.h` and every other port has dropped its copy; this one had no
  caller, because CMakeLists does not compile `romwbw_emu.cc`. Dead code that
  looks like live ^C interception is a trap for the next person auditing that
  question.
- **`emu_console_check_escape()` takes the v1.36 contract and becomes a
  no-op.** It has no caller in this build either, and the old body popped the
  head of the input queue whenever it matched `escape_char` - with no test for
  `escape_char == 0`, which the contract defines as "reserve no key at all".
  The on-screen Ctrl button reaches the whole '@' to '_' window, so Ctrl+@ can
  queue a real NUL that a caller passing 0 would have eaten. Every Ctrl-letter
  belongs to CP/M.
- `emu_rename()` is deliberately *not* defined here, unlike the Windows port.
  It is declared in `emu_io.h` and defined in `emu_io_common.cc`, which this
  port does not compile - but nothing calls it either, because
  `emu_file_save()` is one of this port's stubs. Android writes through JNI.

### Fixed

- **`R8` imported the wrong file rather than admitting it could not find
  yours.** When the requested name was not in `Imports`, it fell back to *the
  first file in the folder* and handed that to CP/M under the name the guest
  asked for - and `R8` printed its usual success line, so the resulting CP/M
  file was real, plausible, and somebody else's contents. A name the user did
  type is never a request for a different file, and a miss is now reported. An
  empty name still means "no preference" and still takes the first file, which
  is what the older bare-FCB `R8` sends when the guest gave it nothing.
  Measured on the emulator: with a decoy file present, `R8 NOTTHERE.TXT` now
  logs "No file found in Imports folder" instead of importing the decoy.
- **F1 to F12 did nothing at all.** `handleKeyDown` had no case for them and
  `unicodeChar` is 0 for function keys, so they fell through to `else -> -1`
  and were dropped. They now send the VT220/xterm sequences every sibling port
  sends - F1-F4 as ESC O P..S, F5 and up as CSI n ~. Nothing on Android
  competes for them, so no setting gates them. Measured: F1 reaches CP/M as
  ^[OP.
- **A hardware Ctrl only made control bytes for A-Z.** Ctrl+[ (ESC), Ctrl+\
  (FS), Ctrl+] (GS), Ctrl+^ (RS), Ctrl+_ (US), Ctrl+@ and Ctrl+Space (NUL)
  produced nothing, because the test was a keycode range over the letters. The
  hardware path now accepts the same '@' to '_' window the on-screen Ctrl
  button already accepted - the software path being the better one was
  backwards. It asks the layout what the key would have typed rather than
  hard-coding keycodes, because the key carrying '[' is not
  `KEYCODE_LEFT_BRACKET` on every layout. Measured: Ctrl+] and Ctrl+\ reach
  CP/M as ^] and ^\.
- **TAB was dropped on the floor.** The normal-state parser handled ESC, CR,
  LF, BS and BEL and then printed anything from 0x20 up, so 0x09 matched
  nothing and vanished. Every program that lays out columns with tabs ran them
  together - including RomWBW's own boot banner, whose drive map is
  tab-indented, and `DIR`, whose four columns collapsed into a ragged line. It
  now advances to the next 8-column stop, the same rule as the other ports.
  Measured on the emulator: `DIR` comes out in columns and the drive map is
  indented.
- **The terminal bell stopped background audio instead of ducking it.** The
  tone was a bare `ToneGenerator(STREAM_SYSTEM)` with no `AudioAttributes`,
  and nothing anywhere in the app requested or abandoned audio focus - so a
  CP/M program that rings the bell, and some ring it per keystroke, killed
  whatever the user was listening to. It now declares itself a sonification
  and takes `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` for the length of the beep,
  giving it back straight after. The setting and its default (off) are
  unchanged, so this narrows a problem the setting had only hidden. This is
  the only sound the app can make: `emu_dsky_beep()` is an empty stub, so the
  HBIOS SND and DSKY paths are silent on Android.
- **A zero-byte export vanished.** `emu_host_file_close_write()` moved to
  `WRITE_READY` only when the buffer had bytes in it, so `W8` on an empty CP/M
  file told the guest it had succeeded and nothing appeared in `Exports`. An
  empty file is a real file - the CLI and Windows backends both create it, and
  `romwbw_emu` stopped dropping it in the browser backend for v1.36 - so the
  test is now on `HOST_FILE_WRITING` alone. Two other places had to move with
  it, because either would have swallowed the export by itself: the JNI
  `nativeGetHostFileWriteData` returned `null` whenever the byte count was
  zero, and `handleHostFileWrite` took `data.isEmpty()` as "nothing to write"
  and returned before creating the file. `emu_host_file_get_write_data()`
  returns `nullptr` for an empty buffer by contract, so the *state*, not the
  pointer, is now what says whether an export is waiting; `null` means there is
  none, and a zero-byte export arrives as a zero-length array.
  `ioscpm` had the same bug, in the same shape, in
  `emu_host_file_close_write()` - the two backends were written from the same
  buffering template - and it has since been fixed there too, in `15f48e9` on
  `origin/main`, which cites this commit.

  **Not built, unlike the rest of this section.** The machine this was written
  on has no SDK, NDK, Gradle or `javac`. What was done instead:
  `emu_io_android.cpp` was compiled for the host with `g++ -std=c++17` against
  a stub `jni.h`, the real `romwbw_emu` and `cpmemu` headers and the real core
  objects, and driven through a whole export. `W8` on an empty file reaches
  `WRITE_READY`, `emu_host_file_get_write_name()` still reports the full
  `Exports` path, the JNI hands back a non-null zero-length array, a two-byte
  export still carries its two bytes, and a guest path is still reduced to a
  leaf. The same program built against `9b68ab1` fails four of those checks,
  which is the regression this entry is about. The state test is
  `WRITE_READY` alone: asked mid-write, the JNI returns `null`. `9b68ab1` did
  not - with the test on the byte count, a call made while the guest was still
  handing bytes down returned a partial export as if it were a finished one.
  Nothing calls it there either - `checkHostFileState()` dispatches only on
  `WRITE_READY` (`MainActivity.kt:777`) - so it was latent both before and
  after, but the state test is where it stops being possible. The Kotlin line
  was read, not compiled.

### Added

- **Terminal scrollback is a setting**, as it is on `z80cpmw`. It was
  hardcoded at 1000 lines with no way to change or disable it. The slider
  steps through 0 (Off), 100, 250, 500, 1000, 2000, 5000 and 10000, because
  the useful values are too far apart for a per-line slider. Lowering it trims
  the history at once rather than waiting for the next scroll, so a user who
  chooses Off does not keep a screen they can still drag back through.
- **Copy takes the scrollback with it.** `copyScreenToClipboard` copied only
  the live rows, which is the wrong half of what the user can see - the point
  of scrollback is the output that has already left the screen, and a long
  `DIR` is exactly what someone reaches for Copy to keep.
- A comment on `setupToolbar` recording that the toolbar is click-only
  deliberately: an ActionBar, a Toolbar with menu items or an options menu all
  switch on Android's `alphabeticShortcut` handling, which claims Ctrl-letters
  before the focused view sees them. That is the `^R` bug `z80cpmw` shipped,
  and every Ctrl-letter belongs to CP/M.

### The build

- **A POSIX `gradlew` is tracked at last, so this repository can be built off
  Windows.** `gradle/wrapper/gradle-wrapper.jar` and `.properties` have been
  tracked since the project started and pin Gradle 8.13, but only `gradlew.bat`
  was there to use them - every non-Windows contributor was stopped at step one
  of this repository's own top-priority item, which is building the un-built
  fix above. The script is Gradle 8.13's own `gradlew`, byte for byte apart
  from `DEFAULT_JVM_OPTS`, which follows `gradlew.bat`'s `"-Xmx64m" "-Xms64m"`
  rather than Gradle's own project setting, so the two scripts now agree about
  the JVM they launch. Mode 755, LF endings like the `.bat`. It is a new
  untracked file: `git add gradlew`, then check `git ls-files -s gradlew`
  reports mode `100755`, or every cloner gets a script they cannot run.
- **`gradle.properties` pinned `org.gradle.java.home` to an absolute Windows
  path**, `C:\Program Files\Android\openjdk\jdk-21.0.8`, in a tracked file, and
  had done since `f4fa7fe`, this repository's second commit. Gradle rejects it
  outright on any other host - *Value ... given for org.gradle.java.home Gradle
  property is invalid* -
  before it reads a single build script, so a POSIX `gradlew` on its own would
  have moved the wall one step rather than removed it. Found by running the new
  wrapper, not by reading it. The line is commented out with the reason beside
  it; per-machine JDK selection belongs in the user's own
  `~/.gradle/gradle.properties`. A Windows shell build now needs `JAVA_HOME`
  set instead - Android Studio picks its own JDK and never read this property -
  which is the one thing this change could break and is a `[WINDOWS]` item in
  `todo.txt` until someone confirms it.
- **`README.md`'s Build Steps never mentioned a wrapper**, because there was no
  usable one; they now give `./gradlew assembleDebug` and
  `gradlew.bat assembleDebug`, say that both read the properties file pinning
  8.13, and say that `cpmemu` and `romwbw_emu` have to be checked out *beside*
  this repository because `CMakeLists.txt` compiles the core in place and stops
  with a `FATAL_ERROR` if they are not.

### Docs

- **`WIP.md` still said the keyboard-aware scrolling and scrollback work was
  uncommitted**, and `todo.txt` sends the next person to `WIP.md` to resume it.
  It has been committed since `690da30` (2026-07-25), which is on `master` and
  on `origin/master`; the doc was telling the one person who might go and check
  the device that there was a working tree to protect. Corrected, with the
  baseline pointed at the current file rather than at `5ae1bdd` - `a523d40` and
  `9b68ab1` have both touched `TerminalView.kt` since. Its "TO DO on resume"
  list was stale in the same direction: the Settings entry for `scrollbackLines`
  and the Copy-takes-scrollback change are both done and are in this section.
  What is still open there is unchanged - nobody has watched it run.
- **`todo.txt` claimed an "Import File..." picker this app does not have.** The
  `W8`/`R8` sandbox item described arrival by staging as already covered by a
  picker, which made half of parity item 4(a) look done. There is no picker in
  the tree: no `registerForActivityResult`, `ACTION_OPEN_DOCUMENT`,
  `ACTION_CREATE_DOCUMENT`, `GetContent` or `DocumentFile` under
  `app/src/main/java`, and `AndroidManifest.xml` has only `MAIN`/`LAUNCHER`.
  `Import File...` is an `ioscpm` feature. The item now says both directions are
  open, which is what `z80cpmw`'s `FEATURE_PARITY.md` Android cell already said.
  The item stays open: a save-as and an import picker are still unwritten.
- **`todo.txt` is rewritten around what each of its items needs from a person.**
  Every item left in it needs a device, a publish, or the release order, and the
  file did not say so; it now has four headed sections that do. Nothing moved to
  this file - none of the five items is finished - and the corrections below are
  what the pass found rather than what it fixed.
- **The un-built change is the first thing in the file now.** Nothing in this
  tree has been through the NDK since `9b68ab1`, and the zero-byte export fix is
  `c06fa58`, which is `master` - so a compile failure in it is a compile failure
  at HEAD, and the next person to open this repository is the first who can find
  out. The entry now carries the route as well as the gap: the missing POSIX
  `gradlew`, which stopped a non-Windows host before anything else and has since
  been written and tracked - see **The build** above - `CMakeLists.txt`
  compiles the core in place
  from `../romwbw_emu/src` and `../cpmemu/src` so both must be checked out beside
  this repository, `ndkVersion` is `28.0.13004108`, and `assembleDebug` answers
  the compile question without a keystore. The status of the fix is unchanged: it
  is still not built, and **Verified** below still says so.
- **The `ioscpm` cite in that item pointed at a path that does not exist.** It
  read `emu_io_ios.mm:483`; the file is `iOSCPM/Core/emu_io_ios.mm`. Re-checked
  at `ioscpm` HEAD `6b1b731` on 2026-08-26: the bug is still there, and the entry
  now names `emu_host_file_close_write()` and the
  `HOST_FILE_WRITING && !g_host_write_buffer.empty()` test rather than a line
  number. That checkout also had an **uncommitted** fix in its working tree,
  citing `c06fa58`; the entry recorded it as uncommitted, not as done. It has
  landed since, in `ioscpm` `15f48e9` on `origin/main`, and the whole passage is
  out of `todo.txt` now - a sibling's closed bug is not this repository's open
  work. Worth knowing for the next person who greps that tree: its local branch
  is two commits behind, so the working copy still shows the old test.
- **The device item no longer sends the reader to `WIP.md`.** It said "resume
  from `WIP.md`", which is the file that had to be corrected last round for
  describing work as uncommitted. The entry is now self-contained: six numbered
  checks (fixed 24-row screen in both orientations, output that scrolls off, the
  drag gestures and the snap-back, the keyboard inset, the scrollback slider
  lowered with history on screen, and Copy with a non-empty history), the
  `adb logcat` filter and what the two tags print, and the tuning knobs listed as
  judgement calls rather than as work. The tablet's APK is explained instead of
  recommended: it is labelled versionCode 19 / 1.18 but was built from the fix,
  and it predates `a523d40`, `9b68ab1` and `c06fa58`, each of which touched
  `TerminalView.kt` or `MainActivity.kt`, so it is the wrong build to check
  against. The `1.18` CHANGELOG wording is marked as the owner's decision, since
  whether that entry describes the release or the tree is not a thing a checker
  can settle.
- **"The bundled disk images" describes an app this is not.** `cpmdroid` ships no
  disk image at all - `app/src/main/assets` holds `emu_avw.rom` and nothing else,
  and there is no `.img` anywhere in the tree. Every disk arrives through
  `DiskCatalogRepository`'s download from the pinned `ioscpm` release, so
  "refresh the bundled images" read as a change to make here when it is a release
  action in another repository. The item says so now. Nothing else in it moved.
- **The parity item was stale in both halves.** It credited `z80cpmw` `944cf9f`
  with settling the `c26aeb7` dispute and left "nothing reports the drift" as the
  open part. Both have moved: `5df0dee` (2026-08-26) found three `c26aeb7`-era
  claims still standing in `FEATURE_PARITY.md` outside the column it had swept -
  two in *Suggested priority order*, naming `buildKeyRow` and
  `TerminalView.sendNamedKey` as a worked example that has never existed here,
  and one in a row-4 bullet crediting this port with a share sheet and an in-app
  path display it does not have - and `tools/check-sibling-drift.sh` now exists
  there and reports the drift mechanically, including a recorded commit that is
  not an object in the tree it names. It reads only, so it is safe to run from
  here; on 2026-08-26 it reported this port drifted, read at `9b68ab1` against a
  HEAD of `c06fa58`. All thirteen Android cells of that snapshot table were
  re-verified against this source at `c06fa58` and every one reads true; the
  entry lists what was checked. What stays open is this repository's half:
  nothing here tells someone changing this tree that another repository's
  document describes it.
- **`todo.txt` cites symbols and greppable strings instead of `file:line`,** the
  convention `z80cpmw` wrote down in `5df0dee` after five of its own eight
  `file:line` cites were invalidated within twelve hours by its own next two
  commits. Worth recording that this file's four had *not* rotted:
  `DiskCatalogRepository.kt:27` still lands on `RELEASE_TAG`, `README.md:47` on
  the copy-into-`Imports` step, `ContentView.swift:231` on the `Import File...`
  label, and `emu_io_ios.mm:483` on the bad test - only its directory was wrong.
  That is the argument rather than an exception to it: they were right this
  morning and nothing on the page said so, which is what a line number cannot
  carry.
- **Nothing in this tree said that another repository describes it.** `z80cpmw`'s
  `FEATURE_PARITY.md` reads thirteen front-end features out of *this source* at a
  recorded commit, so a change made here goes stale there, and the person making
  it is the last one who could notice and the first one who does not. `README.md`'s
  Related Projects section now opens with what that column is, which of this
  port's features are rows in it, and the read-only drift reporter
  (`sh ../z80cpmw/tools/check-sibling-drift.sh`) that says how far the reading
  has fallen behind. The two files that back the most rows carry a one-line
  header saying so: `TerminalView.kt` (rows 1, 2, 3, 9 and 13) and
  `data/DiskCatalogRepository.kt` (row 5, the pinned `RELEASE_TAG`). Row 6 is
  deliberately not claimed on the latter - help fetching stays on
  `releases/latest` in `HelpActivity`. Those two comment lines are the only
  change to any source file in this pass, and they were not compiled.
- **`todo.txt` is 228 lines down to 79, and the checks a person has to run by
  hand are in a new `MANUAL_CHECKS.md`.** The file had been growing by closing
  items: every finished thing left behind a paragraph explaining that it was
  finished, longer than the item had been, and roughly two lines in three were
  not open work. Deleted outright: the `ioscpm` zero-byte paragraph (that bug is
  fixed, see above), the whole `c26aeb7` / `FEATURE_PARITY` history (settled in
  `z80cpmw` `944cf9f` and `5df0dee`), the thirteen-cell re-reading of the parity
  table (a reading, not a task, and duplicated in this section), a frozen
  snapshot of the drift reporter's output (already one commit stale when it was
  written), and the second half of the `W8`/`R8` item (a post-mortem of a
  correction that is in `c06fa58`'s message and in this section). What survives
  is five items, each tagged with what the machine picking it up has to have -
  `[ANDROID]`, `[WINDOWS]`, `[ANDROID DEVICE]`, `[DECISION]`, `[RELEASE]` - so
  the next session on another OS can see at a glance what it can take. The two
  device checklists moved to `MANUAL_CHECKS.md` in full, in the order they should
  be run and with what right looks like at each step; that file says at the top
  that a check is deleted once someone runs it, and the result goes under
  **Verified** here.

### Verified

- Built with the NDK for all four ABIs and run on an API 36 emulator: the app
  boots, RomWBW reaches the boot loader, CP/M 2.2 comes up, and the new
  `Host exports dir:` line shows the JNI hand-down landing before any transfer
  can start.
- `W8 R8.COM` exports to `Exports/r8.com`, with the destination logged as the
  full path and the containment check passing.
- The Settings slider reads 1000, drags to Off, and persists as
  `scrollback_lines` in the preferences file.
- The terminal parser is thinner than `z80cpmw`'s FEATURE_PARITY.md claimed -
  its CSI dispatch is `H f A B C D J K m` and nothing else, its SGR handles
  foreground only, and ESC followed by anything but `[` is discarded, so there
  is no VT52, no DECSTBM, no DECSC/DECRC, no answerback and no background
  colour. That column has been corrected in `z80cpmw`; the gap itself is row 13
  and is not closed here.
- Not built with the NDK and not run anywhere: the zero-byte export fix, the
  last bullet under **Fixed**. It was checked by compiling
  `emu_io_android.cpp` for the host against a stub `jni.h`, which is not the
  same as compiling it for Android and is not the same as watching `W8` on a
  device. What that host run did and did not cover is written into the bullet.
- Not verified, and it cannot be from here: `HBF_HOST_CAPS` and
  `HBF_HOST_GETNAME` reaching a guest. The disk images this port downloads still
  carry the pre-`98eb6a1` `w8.com`, which neither probes nor asks. (This entry
  said "bundled"; nothing is bundled - see the `todo.txt` bullet above.) See
  `todo.txt`.
- Nothing was built or run in the 2026-08-26 documentation pass. It changed
  `todo.txt` and this file and no source, on a machine with no Android SDK, NDK,
  Gradle or `javac`. Every claim it makes about this tree was checked by reading
  or grepping the working tree at `c06fa58`; every claim about a sibling was
  checked against that checkout at the commit named beside it.
- The new `gradlew` was run, twice and by two passes, on macOS 27 arm64:
  `JAVA_HOME=... GRADLE_USER_HOME=<scratch> ./gradlew --version` downloaded the
  pinned `gradle-8.13-bin.zip` and printed `Gradle 8.13`, launcher JVM 21.0.6,
  exit 0. So the script, the tracked wrapper jar and the pin in
  `gradle-wrapper.properties` all work together, and `GradleWrapperMain` really
  ran rather than merely being reached. It was also checked with `sh -n` under
  `sh`, `bash`, `dash` and `zsh`; through a symlink, a daisy-chained relative
  symlink and a foreign working directory with `CDPATH` set, all three resolving
  `APP_HOME` to this repository; and against an invalid `JAVA_HOME`, which
  refuses with the same wording as `gradlew.bat`. The Gradle user home was in
  scratch and was deleted afterwards, so nothing landed in `~/.gradle`.
- **Nothing past `--version` was attempted, and no Android build happened.** The
  machine has no Android SDK - `ANDROID_HOME` is empty and there is no
  `~/Library/Android/sdk` - and the only JVM on it is a stripped runtime bundled
  inside another application, whose `bin` holds `java` and nothing else and whose
  module list has no `java.instrument`, so a Gradle daemon cannot fork there.
  Read none of the above as evidence that this app compiles. The zero-byte export
  fix is still not built and still not run.
- The 2026-08-27 pass changed no app behaviour. Its only edits to compiled files
  are two comment lines, and they were not compiled; the rest is `gradlew`,
  `gradle.properties`, `README.md`, `todo.txt`, `MANUAL_CHECKS.md` and this file.
  No device or emulator was involved at any point.

## Version 1.19 (versionCode 20)

- Synced with emulator core **v1.35**, which pins the RomWBW release it
  emulates (v3.5.1) in `src/romwbw_pin.h` and now refuses a ROM built for a
  different release, or one whose HBIOS configuration block is corrupt,
  instead of starting a CPU that produces no output at all. This port
  compiles the core in place from `../romwbw_emu/src`, so it builds with no
  CMake change.
- **Refreshed the bundled `emu_avw.rom`.** The shipped copy predated the
  upstream rebuild; the ROM is now the one that reproduces from
  `src/emu_hbios.asm`. Verified with `romwbw_emu/roms/verify_romwbw_pin.sh`.
- A rejected ROM now logs *why* (corrupt HCB, or built for a different
  RomWBW release), rather than a bare "Failed to load ROM". The user-facing
  toast is unchanged.
- Reboot no longer assumes the cached ROM reloads: a rejection is logged
  instead of leaving a running CPU with no ROM behind it.
- Not affected by the v1.35 shared file-I/O hardening: this port's
  `emu_file_*` and `emu_disk_*` are deliberate stubs (Android does file I/O
  through JNI and keeps disks in memory), so there was nothing to harden.

**Not built or published when this was written.** No Android SDK, NDK or JDK
was available at the time, and the repo ships only `gradlew.bat`; the C++ was
reviewed and type-checked against the real core headers with clang, but
neither Gradle nor the NDK had compiled it. That gap is now closed - the
Unreleased section above was built and run on a machine with the toolchain,
and this code went through the same compiler on the way. Still unpublished.

## Version 1.18 (versionCode 19)

- Version bump for a fresh Google Play submission. Already targets Android 16 (API 36), which meets Play's Aug 31 2026 target-API requirement (min is API 35).
- The pinned ioscpm `v1.4.5` disk catalog is now published upstream, so the downloadable disk list loads (that release was missing when 1.17 was built, and the catalog fetch returned HTTP 404). No app code changes since 1.17.

## Version 1.17 (versionCode 18)

- Synced with emulator core v1.34: disk write failures surface as HBIOS I/O errors instead of silent data loss, disk offsets are computed in 64-bit, and out-of-bounds guest writes no longer grow images (W8 exports are handed to the UI asynchronously on Android, so an export write failure is reported with a toast)
- Bumped target to Android 16 (API 36)
- Pinned disk catalog to ioscpm release v1.4.5
- Broken w8.com (UPPERCASE export names) is auto-patched at disk load
- Fixed Reboot reverting disks to a stale snapshot
- Clearing a disk slot now actually unmounts it
- Disk saves are atomic and serialized with emulation (no more torn images)
- R8 filename matching is case-insensitive; R8/W8 filenames are sanitized
- Periodic saves are time-based
- Help and README corrections (first hard disk is boot unit 2)

## Version 1.16 (versionCode 17)

- Code cleanup: shared HTTP client, deduplicated disk-loading logic
- Fixed a reboot race condition

## Version 1.15 (versionCode 16)

- Idle power saving to reduce battery drain when waiting for input

## Version 1.13 (versionCode 14)

Recorded late — this release shipped in January 2026 and was never written up
here. The items came off `todo.txt` in the cleanup that added this entry.

- **The Boot/Restart button now asks first.** It opens a "Restart Emulator"
  confirmation with a Cancel, so a tap meant for the neighbouring Pause button
  no longer resets the machine and loses in-progress work. A user reported
  exactly that. There is no unconfirmed restart path left — the dialog is the
  only caller of `bootEmulation()`.
- **Fixed the downloaded-disk write warning checkbox reading as off on a clean
  install.** The preference was stored as a negative
  (`manifest_write_warning_suppressed`, default `false`) behind a checkbox
  labelled "Suppress downloaded disk warnings", so a fresh install showed it
  unchecked and looked as though the protection were off. A user reported
  exactly that. The warning itself was active either way — only the wording was
  inverted. The key is `warn_manifest_writes` now, defaults to **on**, and the
  checkbox reads "Warn when writing to downloaded disks", so a ticked box
  matches the behaviour.
- Added a stored settings version (`prefs_version`) and a `migrateIfNeeded()`
  run from `MainActivity.onCreate`, so a later default change can be pushed out
  to existing installs. It does not act on the old key: the migration removes
  `warn_manifest_writes`, which an install upgrading from 1.12 does not have
  yet, so it is a no-op on that path. Those installs land on the corrected
  default because the renamed key is no longer read; the old
  `manifest_write_warning_suppressed` entry is left orphaned in
  SharedPreferences.
- **Added an "Enable terminal bell sound" setting, off by default.** The BEL
  character used to beep unconditionally, which paused whatever the device was
  already playing; with the setting off no audio object is constructed at all,
  so background audio and audiobooks keep running. Note this only narrows the
  problem — see `todo.txt` for the audio-focus half, which is still open.
- Documented the RC2014 MIDI module research in `docs/midi.md`: the Mk2 module
  has no clock of its own, so MIDI timing still falls to the emulated CPU (or a
  virtual CTC) and cannot be handed off to the hardware. The write-up also
  splits the work into what belongs in the shared `hbios_dispatch` and what is
  Android-only, with effort estimates.

## Version 1.2 (versionCode 3)

- Renamed package from `com.romwbw.cpmdroid` to `com.awohl.cpmdroid`
- Fixed layout issues with navigation bar on phones and tablets
- Added `fitsSystemWindows` support for proper system bar handling

### Tested on:
- Samsung physical tablet
- Pixel Phone emulator
- Pixel Tablet emulator

## Version 1.1 (versionCode 2)

- Added `recalculateSize()` method for forced layout recalculation
- Fixed terminal layout after reboot button press
- Fixed terminal layout after returning from help activity
- Bumped target API to 35

## Version 1.0 (versionCode 1)

- Initial release
