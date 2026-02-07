# CPMDroid - CP/M Emulator for Android

A Z80/CP/M emulator for Android phones and tablets, built on the [RomWBW](https://github.com/wwarthen/RomWBW) HBIOS platform.

## Features

- **Full Z80 emulation** with accurate instruction timing
- **RomWBW HBIOS** compatibility for authentic CP/M experience
- **VT100/ANSI terminal** with escape sequence support (runs Zork, WordStar, etc.)
- **Multiple disk support** - up to 4 disk units with hd1k format (8MB slices)
- **Download disk images** from RomWBW project - no bundled copyrighted content
- **Hardware keyboard support** - Bluetooth and USB keyboards
- **Control strip** - Ctrl, Esc, Tab, Copy, Paste buttons for touch input
- **Help system** - Built-in documentation downloaded from GitHub
- **R8/W8 file transfer** - Transfer files between Android and CP/M

## Getting Started

1. **First launch** automatically downloads a default boot disk
2. **Open Settings** (gear icon) to configure disks and options
3. **Download additional disk images** from the disk catalog
4. **Press Play** to start the emulator
5. At boot menu, press `0` to boot from disk

### Boot Menu Keys
- `h` - Help
- `l` - List ROM applications
- `d` - List disk devices
- `0-9` - Boot from device number

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

Disk images are downloaded from the official [RomWBW](https://github.com/wwarthen/RomWBW) project:

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

- [80un](https://github.com/avwohl/80un) - Unpacker for CP/M compression and archive formats (LBR, ARC, squeeze, crunch, CrLZH)
- [cpmemu](https://github.com/avwohl/cpmemu) - CP/M 2.2 emulator with Z80/8080 CPU emulation and BDOS/BIOS translation to Unix filesystem
- [ioscpm](https://github.com/avwohl/ioscpm) - Z80/CP/M emulator for iOS and macOS with RomWBW HBIOS compatibility
- [learn-ada-z80](https://github.com/avwohl/learn-ada-z80) - Ada programming examples for the uada80 compiler targeting Z80/CP/M
- [mbasic](https://github.com/avwohl/mbasic) - Modern MBASIC 5.21 Interpreter & Compilers
- [mbasic2025](https://github.com/avwohl/mbasic2025) - MBASIC 5.21 source code reconstruction - byte-for-byte match with original binary
- [mbasicc](https://github.com/avwohl/mbasicc) - C++ implementation of MBASIC 5.21
- [mbasicc_web](https://github.com/avwohl/mbasicc_web) - WebAssembly MBASIC 5.21
- [mpm2](https://github.com/avwohl/mpm2) - MP/M II multi-user CP/M emulator with SSH terminal access and SFTP file transfer
- [romwbw_emu](https://github.com/avwohl/romwbw_emu) - Hardware-level Z80 emulator for RomWBW with 512KB ROM + 512KB RAM banking and HBIOS support
- [scelbal](https://github.com/avwohl/scelbal) - SCELBAL BASIC interpreter - 8008 to 8080 translation
- [uada80](https://github.com/avwohl/uada80) - Ada compiler targeting Z80 processor and CP/M 2.2 operating system
- [ucow](https://github.com/avwohl/ucow) - Unix/Linux Cowgol to Z80 compiler
- [um80_and_friends](https://github.com/avwohl/um80_and_friends) - Microsoft MACRO-80 compatible toolchain for Linux: assembler, linker, librarian, disassembler
- [upeepz80](https://github.com/avwohl/upeepz80) - Universal peephole optimizer for Z80 compilers
- [uplm80](https://github.com/avwohl/uplm80) - PL/M-80 compiler targeting Intel 8080 and Zilog Z80 assembly language
- [z80cpmw](https://github.com/avwohl/z80cpmw) - Z80 CP/M emulator for Windows (RomWBW)

