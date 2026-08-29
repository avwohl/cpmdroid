# CPMDroid - CP/M Emulator for Android

A Z80/CP/M emulator for Android phones and tablets, built on the [RomWBW](https://github.com/wwarthen/RomWBW) HBIOS platform.

## Features

- **Full Z80 emulation** with accurate instruction timing
- **RomWBW HBIOS** compatibility for authentic CP/M experience
- **ANSI/VT terminal** - cursor motion, erase, 16-colour SGR foreground and
  background, scrollback. Deliberately partial: there is no VT52 mode, no
  scrolling region, and no bold/underline/reverse. `todo.txt` lists what is
  missing; this used to claim "runs Zork, WordStar, etc.", which outran what
  anybody had measured.
- **Multiple disk support** - up to 4 disk units with hd1k format (8MB slices)
- **Download disk images** from the [ioscpm](https://github.com/avwohl/ioscpm) releases - no bundled copyrighted content
- **Hardware keyboard support** - Bluetooth and USB keyboards
- **Control strip** - Ctrl, Esc, Tab, Copy, Paste buttons for touch input
- **Help system** - seven topics bundled in the app, refreshed from GitHub when
  there is a network and cached after that, so it works offline
- **R8/W8 file transfer** - move files between Android and CP/M, with an in-app
  Imports/Exports browser, save-as and share out, and a picker in. CPMDroid is
  also a share target, so other apps can send it a file

## Getting Started

1. **First launch** automatically downloads a default boot disk
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

## Disk Images

Disk images are downloaded from the [avwohl/ioscpm](https://github.com/avwohl/ioscpm) GitHub release, pinned at tag v1.4.5:

| Disk | Description | License |
|------|-------------|---------|
| CP/M 2.2 | Classic Digital Research OS | Free (Lineo) |
| ZSDOS | Enhanced CP/M with timestamps | Free |
| NZCOM | ZCPR3 command processor | Free |
| CP/M 3 (Plus) | Banked memory support | Free |
| ZPM3 | Z-System CP/M 3 | Free |
| WordStar 4 | Word processor | Abandonware |

Downloaded images are stored in app-specific storage and work offline.

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

The terminal supports ANSI/VT100 escape sequences:
- Cursor positioning (`ESC[row;colH`)
- Screen/line clearing (`ESC[2J`, `ESC[K`)
- Text colors (CGA 16-color palette)
- Cursor movement (`ESC[A/B/C/D`)

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
and `Exports/` transfer folders, the pinned disk-catalog release tag, help
fetching, NVRAM autoboot, the font-size and scrollback settings, and the
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

