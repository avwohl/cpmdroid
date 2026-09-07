# CPMDroid - CP/M Emulator for Android

A Z80/CP/M emulator for Android phones and tablets, built on the [RomWBW](https://github.com/wwarthen/RomWBW) HBIOS platform.

## Features

- **Full Z80 emulation** with accurate instruction timing
- **RomWBW HBIOS** compatibility for authentic CP/M experience
- **ANSI/VT100 terminal with VT52** - cursor motion, a scrolling region,
  save/restore, the insert and delete commands, 16-colour SGR foreground and
  background, per-cell bold, underline, blink and reverse, and scrollback.
  `todo.txt` lists the few sequences that are still absent, most of which no
  port in the family has. This still does not claim "runs Zork, WordStar, etc.",
  because nobody has measured that - the parser is now wide enough that it is a
  reasonable thing to go and try, which is a different statement.
- **Multiple disk support** - up to 4 disk units with hd1k format (8MB slices)
- **Nothing bundled** - the ROM and every disk image come from the interface-v0
  catalog in [romwbw_disks](https://github.com/avwohl/romwbw_disks), and each
  download is checked against the size and SHA-256 that catalog publishes
- **Hardware keyboard support** - Bluetooth and USB keyboards
- **Control strip** - Ctrl, Esc, Tab, Copy, Paste buttons for touch input
- **Help system** - seven topics bundled in the app, refreshed from GitHub when
  there is a network and cached after that, so it works offline
- **R8/W8 file transfer** - move files between Android and CP/M, with an in-app
  Imports/Exports browser, save-as and share out, and a picker in. CPMDroid is
  also a share target, so other apps can send it a file

## Getting Started

1. **First launch** needs a network connection: it fetches the ROM for the
   selected RomWBW release, then a default boot disk. Later launches work
   offline - the catalog's claim about the ROM is stored beside the file and
   re-checked against it
2. **Open Settings** (the wrench, in the toolbar) to configure disks and
   options. Stop the emulator first - Settings refuses to open while it runs
3. **Download additional disk images** from the disk catalog
4. **Press Play** to start the emulator
5. At boot menu, press `2` to boot from the first hard disk (units 0 and 1 are the RAM and ROM memory disks and carry no OS)

### Boot Menu Keys
- `h` - Help
- `l` - List ROM applications
- `d` - List disk devices
- `w` - Save your choice as the autoboot default
- `0-9` - Boot from device number (the first hard disk is unit 2)

### Control Strip
- **Ctrl** - Toggle control key mode (next key becomes control character)
- **Esc** - Send escape character
- **Tab** - Send tab character
- **Copy** - Copy screen to clipboard
- **Paste** - Paste clipboard as keyboard input

## File Transfer (R8/W8)

Transfer files between Android and CP/M using the R8/W8 utilities:

- **Imports folder**: `Android/data/com.awohl.cpmdroid/files/Imports/`
- **Exports folder**: `Android/data/com.awohl.cpmdroid/files/Exports/`

Since Android 11 the stock Files app does not show `Android/data` at all, so
those two paths are where the files live rather than somewhere you can browse
to. Use the **File transfer** button in the toolbar, which is the app's own view
of both folders.

To import a file to CP/M:
1. **File transfer > Import file...**, and pick it. Or share it to CPMDroid from
   any other app. Either way it is renamed to something CP/M can address:
   `My Long Archive.tar.gz` becomes `my-long-.gz`, and the app tells you which
   name it got
2. In CP/M, run: `R8 MY-LONG-.GZ`

To export a file from CP/M:
1. In CP/M, run: `W8 FILENAME.EXT`. It prints the full host path it wrote to
2. **File transfer**, then **Save as...** to put it anywhere on the device, or
   **Share** to send it to another app

Staging a file into `Imports/` by hand with a third-party file manager still
works, and is no longer the only way.

## ROMs and Disk Images

**Nothing is bundled.** The APK carries the emulator core and the help topics; it
carries no ROM and no disk image. Both come from the interface-v0 catalog in
[romwbw_disks](https://github.com/avwohl/romwbw_disks), and the only content
address compiled into the app is the index that catalog starts from (`INDEX_URL`
in `data/DiskCatalogRepository.kt`; the in-app help has its own). There is no
pinned release tag any more: the index names every published RomWBW release and
points at that release's own catalog, that catalog publishes a `base_url`, and
every asset is that `base_url` plus a filename - nothing is assembled here from a
version number. Everything fetched is measured against the size and SHA-256 the
catalog publishes, and bytes that do not match are not kept.

Two things are yours to choose, both in Settings and both filled from the catalog
rather than from this build:

- **RomWBW Release** - the index, filtered to the releases the emulator core says
  it can boot. An install that has not settled on a release yet - a fresh one, or
  one upgrading from a build that carried its own ROM - takes the index's own
  default the first time it reads it. After that it stays where it is, and this
  row is what moves it. A RomWBW release published later will not switch a
  machine by itself, because that would change its disk set and its NVRAM
  namespace under it; new ROMs and new disks *within* the selected release still
  arrive with no app update. Each release keeps its own disk slots, NVRAM and
  ROM, so switching is a round trip that loses nothing
- **ROM** - the ROMs the selected release publishes (`emu_avw` and `emu_rcz80`
  today). What is stored is the catalog's ID rather than a filename, because the
  filename carries the release

Some of what the catalog carries - the list is the selected release's, and it
changes from one release to the next:

| Disk | Description | License |
|------|-------------|---------|
| CP/M 2.2 | Classic Digital Research OS | Free (Lineo) |
| ZSDOS | Enhanced CP/M with timestamps | Free |
| NZCOM | ZCPR3 command processor | Free |
| CP/M 3 (Plus) | Banked memory support | Free |
| ZPM3 | Z-System CP/M 3 | Free |
| Word processing | WordStar 4 in RomWBW 3.5.1, the Word Processing image that replaced it in 3.6.0 | Abandonware |

Downloaded ROMs and images are stored in app-specific storage and work offline.
The exception is a fresh install: there is no ROM in the package, and a ROM
cannot be verified without the catalog that publishes its size and hash, so the
first launch needs one successful fetch. The app says so, with a Download button,
rather than starting a machine on bytes it cannot check.

## Technical Details

### Architecture

```
+-------------------------------------+
|         Android UI (Kotlin)         |
+-------------------------------------+
|       EmulatorEngine (JNI)          |
+-------------------------------------+
|       HBIOSEmulator (C++)           |
|  +-----------+-----------------+    |
|  |   qkz80   |  HBIOSDispatch  |    |
|  | (Z80 CPU) |  (HBIOS calls)  |    |
|  +-----------+-----------------+    |
+-------------------------------------+
```

### Dependencies

This project uses code from sibling directories:
- `../cpmemu/src/` - qkz80 Z80 CPU emulator
- `../romwbw_emu/src/` - HBIOS dispatch, memory banking

### VT100 Terminal Emulation

The terminal supports ANSI/VT100 escape sequences, and VT52:
- Cursor positioning (`ESC[row;colH`), movement (`ESC[A/B/C/D`) and absolute
  column/row (`ESC[G`, `ESC[d`)
- Screen/line clearing (`ESC[2J`, `ESC[K`) and character erase (`ESC[X`)
- Insert/delete characters (`ESC[@`, `ESC[P`) and lines (`ESC[L`, `ESC[M`)
- Scrolling region (`ESC[t;br`) and scroll up/down (`ESC[S`, `ESC[T`)
- Save/restore cursor and rendition (`ESC 7` / `ESC 8`, `ESC[s` / `ESC[u`)
- Text colours (CGA 16-colour palette, foreground and background) and per-cell
  bold, underline, blink and reverse (`ESC[1m`, `4m`, `5m`, `7m`)
- Private modes: VT52/ANSI (`ESC[?2h/l`), autowrap (`?7`), cursor visibility
  (`?25`)
- Device queries: cursor position (`ESC[6n`), device attributes (`ESC[c`)
- VT52 mode, entered by `ESC[?2l` or auto-detected from any VT52-*exclusive*
  escape (`ESC A B C F G I J K Y`); `ESC H` is VT52 home only once VT52 is
  already in force, because in ANSI that byte is HTS

The deliberate divergence most visible in ordinary output: `ESC[0m` resets the
foreground to green rather than light grey, because a green phosphor screen is
this app's identity. It is not the only one - `ESC[1m` picks a bold face without
brightening the colour, `ESC[104m` stays bright where the Windows port folds it
onto `ESC[44m`, and `ESC[39m`/`ESC[49m` work here and in neither sibling.
`todo.txt` lists all four under `[DELIBERATE]`, with the reason for each.

### Disk Format

Uses RomWBW hd1k format:
- 8MB per slice
- Up to 8 slices per disk (64MB total)
- 1024 directory entries per slice
- Compatible with all RomWBW disk images

## Building

### Requirements
- Android Studio, or just a JDK and the SDK - the tracked wrapper pins Gradle
  8.13, and a shell build needs `JAVA_HOME` set (`gradle.properties` no longer
  pins one, because an absolute path there broke every other host)
- JDK 21
- Android SDK: `compileSdk`/`targetSdk` 36, `minSdk` 24 (Android 7.0)
- Android NDK `28.0.13004108`, which `app/build.gradle.kts` pins by version

### Build Steps
1. Clone sibling projects (cpmemu, romwbw_emu) beside this one - `CMakeLists.txt`
   compiles the core in place from `../romwbw_emu/src` and `../cpmemu/src`, so
   CMake stops with a `FATAL_ERROR` if they are missing
2. Open project in Android Studio, or build from a shell with the wrapper -
   `./gradlew assembleDebug` on Linux/macOS, `gradlew.bat assembleDebug` on
   Windows. Both scripts read `gradle/wrapper/gradle-wrapper.properties`, which
   pins Gradle 8.13 and downloads it on first run
3. Sync Gradle
4. Build and run

## Related Projects

**This port is described from outside it.** `z80cpmw`'s
[FEATURE_PARITY.md](https://github.com/avwohl/z80cpmw/blob/master/FEATURE_PARITY.md)
carries an Android column that describes CPMDroid row by row - thirteen
front-end features, each read out of *this source* at a recorded commit rather
than from the CHANGELOG or the release notes. So a change made here goes stale
there, and the person making it is the last one who could notice and the first
one who does not. The rows this repository backs are the terminal parser and
its escape sequences, key handling and the control strip, the fixed `Imports/`
and `Exports/` transfer folders, the disk-catalog client and the RomWBW release it
follows, help fetching, NVRAM autoboot, the font-size and scrollback settings, and the
Dazzler/DSKY stubs. Touch any of those and that column needs re-reading.

The commits each column was read at are recorded in that file's
`sibling-readings` block, and a script beside it reports how far the checkouts
have moved since:

    sh ../z80cpmw/tools/check-sibling-drift.sh

It reads only - it never writes to a sibling - and exits non-zero when any
column is behind the tree it describes.

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

