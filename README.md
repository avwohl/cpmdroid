# CPMDroid - CP/M Emulator for Android

A Z80/CP/M emulator for Android phones and tablets, built on the [RomWBW](https://github.com/wwarthen/RomWBW) HBIOS platform.

## Features

- **Full Z80 emulation** with accurate instruction timing
- **RomWBW HBIOS** compatibility for authentic CP/M experience
- **VT100/ANSI terminal** with escape sequence support (runs Zork, WordStar, etc.)
- **Multiple disk support** - up to 4 disk units with hd1k format (8MB slices)
- **Download disk images** from the [ioscpm](https://github.com/avwohl/ioscpm) releases - no bundled copyrighted content
- **Hardware keyboard support** - Bluetooth and USB keyboards
- **Control strip** - Ctrl, Esc, Tab, Copy, Paste buttons for touch input
- **Help system** - Built-in documentation downloaded from GitHub
- **R8/W8 file transfer** - Transfer files between Android and CP/M

## Getting Started

1. **First launch** automatically downloads a default boot disk
2. **Open Settings** (gear icon) to configure disks and options
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

To import a file to CP/M:
1. Copy file to Imports folder using a file manager
2. In CP/M, run: `R8 FILENAME.EXT`

To export a file from CP/M:
1. In CP/M, run: `W8 FILENAME.EXT`
2. File appears in Exports folder

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
- Android Studio Hedgehog (2023.1) or later
- Android SDK 24+ (Android 7.0)
- Android NDK 27+

### Build Steps
1. Clone sibling projects (cpmemu, romwbw_emu)
2. Open project in Android Studio
3. Sync Gradle
4. Build and run
## Related Projects

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

