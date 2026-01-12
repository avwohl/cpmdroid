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

- **Imports folder**: `Android/data/com.romwbw.cpmdroid/files/Imports/`
- **Exports folder**: `Android/data/com.romwbw.cpmdroid/files/Exports/`

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

- [RomWBW](https://github.com/wwarthen/RomWBW) - The original RomWBW project by Wayne Warthen
- [cpmemu](https://github.com/avwohl/cpmemu) - Portable Z80 CPU emulator core
- [romwbw_emu](https://github.com/avwohl/romwbw_emu) - CLI emulator for macOS/Linux
- [ioscpm](https://github.com/avwohl/ioscpm) - iOS/macOS version
- [z80cpmw](https://github.com/avwohl/z80cpmw) - Windows GUI version

## License

GNU General Public License v3.0 - see [LICENSE](LICENSE) for details.

### Third-Party Licenses
- **CP/M**: Released by Lineo for non-commercial use
- **RomWBW**: MIT License
- **qkz80**: GPL v3 (from cpmemu)

## Acknowledgments

- Wayne Warthen for RomWBW
- The CP/M and retrocomputing community
