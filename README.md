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
5. At the boot menu, type `2` and Enter to boot the first hard disk.
   [docs/usage.md](docs/usage.md) lists the other boot menu keys

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

## Documentation

- [docs/usage.md](docs/usage.md) - boot menu keys, the control strip, and R8/W8 file transfer with the Imports and Exports folders
- [docs/roms_and_disks.md](docs/roms_and_disks.md) - where the ROM and the disk images come from, and the ROM, RomWBW Release and Catalog Index settings
- [docs/technical_details.md](docs/technical_details.md) - architecture, sibling dependencies, terminal emulation, disk format
- [docs/midi.md](docs/midi.md) - MIDI support research notes
- [CHANGELOG.md](CHANGELOG.md) - what changed in each version

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

