# CPMDroid - CP/M Emulator for Android

A Z80/CP/M emulator for Android phones and tablets, built on the
[RomWBW](https://github.com/wwarthen/RomWBW) HBIOS platform.

## Features

- **Z80 emulation** with the RomWBW HBIOS interface
- **ANSI/VT100 terminal with VT52** - cursor motion, a scrolling region,
  save/restore, insert and delete, 16-colour SGR foreground and background,
  per-cell bold, underline, blink and reverse, and scrollback
- **Four disk slots**, hd1k format - a single 8 MB slice, or a multi-slice
  combo image
- **Nothing bundled** - the ROM and every disk image come from the interface-v0
  catalog in [romwbw_disks](https://github.com/avwohl/romwbw_disks), each
  download checked against the size and SHA-256 that catalog publishes
- **Hardware keyboard support** - Bluetooth and USB
- **Control strip** - Ctrl, Esc, Tab, Copy and Paste buttons for touch input
- **Help system** - seven topics shipped in the APK as a floor, refreshed from
  the catalog index when there is a network and cached after that
- **R8/W8 file transfer** - move files between Android and CP/M, with an in-app
  Imports/Exports browser, save-as and share out, and a picker in. CPMDroid is
  also a share target, so other apps can send it a file

## Getting Started

1. **First launch needs a network connection.** It fetches the ROM for the
   selected RomWBW release, then a default boot disk. Later launches work
   offline - the catalog's claim about the ROM is stored beside the file and
   re-checked against it
2. **Open Settings** (the gear icon in the toolbar) to configure disks and
   options. Stop the emulator first; Settings refuses to open while it runs
3. **Download disk images** from the disk catalog
4. **Press Play**
5. At the boot menu, type `2` and Enter to boot the first hard disk

### Boot Menu Keys

Every command is read as a line, so nothing happens until you press Enter.

- `2` - boot the first hard disk, slice 0; `2.3` for slice 3
- `c` - boot CP/M 2.2 from ROM
- `d` - list the disk devices
- `w` - SYSCONF, where a choice can be saved as the autoboot default
- `h` - the full menu. On RomWBW 3.6.0 this lists the ROM applications too; on
  3.5.1 they are under `l`, which 3.6.0 answers with `*** Invalid command`

Units 0 and 1 are the on-board RAM and ROM memory disks and carry no operating
system, so booting `0` answers `*** No boot record` on RomWBW 3.6.0 and
`*** No system image on disk` on 3.5.1.

### Control Strip

- **Ctrl** - toggle control key mode (the next key becomes a control character)
- **Esc** - send escape
- **Tab** - send tab
- **Copy** - copy the screen to the clipboard
- **Paste** - paste the clipboard as keyboard input

## File Transfer (R8/W8)

`R8` and `W8` are CP/M programs that live on the disk, and only the
`hd1k_combo` image carries them - boot a single-OS image and there is no `R8`
or `W8` to run.

- **Imports folder**: `Android/data/com.awohl.cpmdroid/files/Imports/`
- **Exports folder**: `Android/data/com.awohl.cpmdroid/files/Exports/`

Since Android 11 the stock Files app does not show `Android/data` at all, so
those are where the files live rather than somewhere you can browse to. Use the
**File transfer** button in the toolbar, which is the app's own view of both
folders.

To import a file into CP/M:

1. **File transfer > Import file...**, and pick it. Or share it to CPMDroid
   from another app. Either way it is renamed to something CP/M can address -
   `My Long Archive.tar.gz` becomes `my-long-.gz` - and the app tells you which
   name it got
2. In CP/M, run `R8 MY-LONG-.GZ`

To export a file from CP/M:

1. In CP/M, run `W8 FILENAME.EXT`. It prints the full host path it wrote to
2. **File transfer**, then **Save as...** to put it anywhere on the device, or
   **Share** to send it to another app

Staging a file into `Imports/` by hand with a third-party file manager still
works.

## ROMs and Disk Images

**Nothing is bundled.** The APK carries the emulator core and an offline copy
of the help topics; it carries no ROM and no disk image.

Both come from the interface-v0 catalog in
[romwbw_disks](https://github.com/avwohl/romwbw_disks). The only content
address compiled into the app is the index that catalog starts from
(`DEFAULT_INDEX_URL` in `data/SettingsRepository.kt`) - one URL for the whole
app, help included, since 1.30. There is no pinned release tag: the index names
every published RomWBW release and points at that release's own catalog, that
catalog publishes a `base_url`, and every asset is that `base_url` plus a
filename, so nothing is assembled here from a version number. Everything
fetched is measured against the size and SHA-256 the catalog publishes, and
bytes that do not match are not kept.

Three things are yours to choose, all in Settings, in the order the screen
shows them:

- **ROM** - the ROMs the selected release publishes. What is stored is the
  catalog's ID rather than a filename, because the filename carries the release.

- **RomWBW Release** - the index, filtered to the releases the emulator core
  says it can boot. A release published later will not switch a machine by
  itself, since that would change its disk set and its NVRAM namespace
  underneath it; new ROMs and disks *within* the selected release arrive with
  no app update. Each release keeps its own disk slots, NVRAM and ROM, so
  switching is a round trip that loses nothing.

- **Catalog Index** - which catalog everything else comes from. Empty is the
  default; a URL here points CPMDroid at another index, and the release list,
  the ROM list, the disk catalog and the in-app help all follow it. Apply
  fetches it and says what it found rather than storing a URL whose first test
  would be a machine that will not start. Each index keeps its own disks, ROM,
  NVRAM and boot config. `$ROMWBW_INDEX_URL` overrides it for the run, and the
  field says so and is disabled while it is set.

What disks exist, and what each one is licensed under, are questions for the
selected release's catalog - the app shows both, and the list changes from one
release to the next. Between them the published releases carry CP/M 2.2,
CP/M 3, ZSDOS, ZPM3, NZCOM and QPM, games, Infocom adventures and language
toolchains.

Downloaded ROMs and images are stored in app-specific storage and work offline.
The exception is a fresh install: there is no ROM in the package, and a ROM
cannot be verified without the catalog that publishes its size and hash, so the
first launch needs one successful fetch. The app says so, with a Download
button, rather than starting a machine on bytes it cannot check.

## Technical Details

### Architecture

```
+-------------------------------------+
|         Android UI (Kotlin)         |
+-------------------------------------+
|       EmulatorEngine (JNI)          |
+-------------------------------------+
|   AndroidEmulatorDelegate (C++)     |
|  +-----------+-----------------+    |
|  |   qkz80   |  HBIOSDispatch  |    |
|  | (Z80 CPU) |  + banked_mem   |    |
|  +-----------+-----------------+    |
+-------------------------------------+
```

### Dependencies

Sibling checkouts, compiled in place by `CMakeLists.txt`:

- `../cpmemu/src/` - the qkz80 Z80 CPU core
- `../romwbw_emu/src/` - HBIOS dispatch and memory banking

### Terminal Emulation

ANSI/VT100 and VT52:

- Cursor positioning (`ESC[row;colH`), movement (`ESC[A/B/C/D`) and absolute
  column/row (`ESC[G`, `ESC[d`)
- Screen and line clearing (`ESC[2J`, `ESC[K`) and character erase (`ESC[X`)
- Insert and delete characters (`ESC[@`, `ESC[P`) and lines (`ESC[L`, `ESC[M`)
- Scrolling region (`ESC[t;br`) and scroll up/down (`ESC[S`, `ESC[T`)
- Save/restore cursor and rendition (`ESC 7` / `ESC 8`, `ESC[s` / `ESC[u`)
- Colours (CGA 16-colour palette, foreground and background) and per-cell bold,
  underline, blink and reverse
- Private modes: VT52/ANSI (`ESC[?2h/l`), autowrap (`?7`), cursor visibility
  (`?25`)
- Device queries: cursor position (`ESC[6n`), device attributes (`ESC[c`)
- VT52 mode, entered by `ESC[?2l` or auto-detected from any VT52-*exclusive*
  escape (`ESC A B C F G I J K Y`); `ESC H` is VT52 home only once VT52 is
  already in force, because in ANSI that byte is HTS

Four divergences are deliberate, and `todo.txt` lists them under `[DELIBERATE]`
with the reason for each. The one visible in ordinary output: `ESC[0m` resets
the foreground to green rather than light grey, because a green phosphor screen
is this app's identity.

### Disk Format

RomWBW hd1k: 8 MB per slice, 1024 directory entries per slice. The eight slices
are divided among the disks you attach rather than given to each - one disk gets
8, two get 4 each, three or four get 2 each - so adding a disk shortens the
others. The catalog's recommended image is the 51,380,224-byte six-slice combo
rather than a bare 8 MB slice.

## Building

**Requirements:** a JDK meeting Android Gradle Plugin 8.13.2's floor, which is
17 (the module itself compiles to Java 8); Android SDK with `compileSdk`/`targetSdk` 36 and
`minSdk` 24 (Android 7.0); Android NDK `28.0.13004108`, which
`app/build.gradle.kts` pins by version. The tracked wrapper pins Gradle 8.13,
and a shell build needs `JAVA_HOME` set.

1. Clone the sibling projects `cpmemu` and `romwbw_emu` beside this one -
   `CMakeLists.txt` compiles the core in place from `../romwbw_emu/src` and
   `../cpmemu/src`, and stops with a `FATAL_ERROR` if they are missing
2. Open the project in Android Studio, or build from a shell with the wrapper:
   `./gradlew assembleDebug`, or `gradlew.bat assembleDebug` on Windows
3. Sync Gradle, then build and run

`assembleRelease` produces an APK for sideloading. Play takes an app bundle
from `./gradlew :app:bundleRelease` instead.

## License

GPLv3.

### Third-Party Licenses

- **RomWBW**, whose code is in banks 1-15 of every ROM this app fetches:
  GPL-3.0-or-later
- **qkz80**, the CPU core: GPL v3
- **The disk images** carry third-party CP/M software under their own terms.
  Each catalog entry states what it believes it is under in its `license`
  field, which the app displays; that field is the authority, not this file.

## Related Projects

`z80cpmw`'s
[FEATURE_PARITY.md](https://github.com/avwohl/z80cpmw/blob/master/FEATURE_PARITY.md)
carries an Android column describing this port row by row, read out of this
source at a recorded commit. Changing the terminal parser, key handling,
Imports/Exports, the catalog client, help fetching, NVRAM autoboot, the
font-size and scrollback settings or the Dazzler/DSKY stubs means that column
needs re-reading - and correcting it is an edit in z80cpmw, not here.

The other repositories in and around this family:

- [80un](https://github.com/avwohl/80un) - Unpacker for the CP/M archive and compression formats LBR, ARC, squeeze, crunch, and CrLZH.
- [cpmemu](https://github.com/avwohl/cpmemu) - Z80/CP/M emulator for Linux and Windows, with Z80 and 8080 CPU cores. It translates the BDOS and BIOS calls of CP/M 2.2 programs to the host file system.
- [ioscpm](https://github.com/avwohl/ioscpm) - Z80/CP/M emulator for iOS and macOS. It emulates the RomWBW HBIOS interface and runs CP/M 2.2 and CP/M 3.
- [learn-ada-z80](https://github.com/avwohl/learn-ada-z80) - Collection of more than 90 Ada example programs for uada80, the Ada compiler for the Z80 processor and CP/M.
- [mbasic](https://github.com/avwohl/mbasic) - Python interpreter for MBASIC 5.21, the Microsoft BASIC-80 for CP/M. Two compiler backends compile the programs to CP/M .COM files or to JavaScript.
- [mbasic2025](https://github.com/avwohl/mbasic2025) - Reconstruction of the lost source code of MBASIC 5.21, the Microsoft BASIC-80 for CP/M. The MACRO-80 source code assembles to a binary that matches mbasic.com byte for byte.
- [mbasicc](https://github.com/avwohl/mbasicc) - C++17 interpreter for MBASIC 5.21, the Microsoft BASIC-80 for CP/M. It runs on Linux and macOS.
- [mbasicc_web](https://github.com/avwohl/mbasicc_web) - Web browser interpreter for MBASIC 5.21, the Microsoft BASIC-80 for CP/M. Emscripten compiles the mbasicc interpreter to WebAssembly.
- [mpm2](https://github.com/avwohl/mpm2) - Z80 emulator for MP/M II, the multi-user CP/M operating system. Users connect over SSH, and SFTP clients transfer files.
- [romwbw_emu](https://github.com/avwohl/romwbw_emu) - Hardware-level Z80/CP/M emulator for Linux and macOS. It emulates the RomWBW HBIOS interface and switches banks in 512 KB of ROM and 512 KB of RAM.
- [scelbal](https://github.com/avwohl/scelbal) - Floating-point BASIC interpreter for the 8080 processor and CP/M. A translator converts the original 8008 source code to 8080 source code.
- [uada80](https://github.com/avwohl/uada80) - Ada compiler for the Z80 processor and CP/M 2.2. It compiles a subset of Ada 2012 to CP/M .COM files.
- [uc80](https://github.com/avwohl/uc80) - C compiler for the Z80 processor and CP/M. It optimizes for small code size.
- [ucow](https://github.com/avwohl/ucow) - Cowgol compiler for the Z80 processor and CP/M. It runs on Linux in Python.
- [um80_and_friends](https://github.com/avwohl/um80_and_friends) - Linux toolchain that is compatible with Microsoft MACRO-80. It has an assembler, a linker, a librarian, and a disassembler.
- [upeepz80](https://github.com/avwohl/upeepz80) - Peephole optimizer for Z80 compilers that write lowercase Z80 assembly language. It shortens jumps to jr, builds djnz loops, and removes dead stores.
- [uplm80](https://github.com/avwohl/uplm80) - PL/M-80 compiler for the Z80 processor and CP/M. It writes Intel 8080 and Zilog Z80 assembly language.
- [z80cpmw](https://github.com/avwohl/z80cpmw) - Z80/CP/M emulator for Windows. It emulates the RomWBW HBIOS interface and boots CP/M from disk images.

## See Also

- [RomWBW](https://github.com/wwarthen/RomWBW) - The original RomWBW project by Wayne Warthen

