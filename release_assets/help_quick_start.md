# Quick Start Guide for CPMDroid

## First Launch

When you first launch CPMDroid, a default disk image is automatically downloaded and configured. You'll see the RomWBW boot loader screen.

## Toolbar Buttons

| Button | Function |
|--------|----------|
| Play/Pause | Start or pause emulation |
| Reboot | Reset the emulator and reboot |
| ? | Help topics (this screen) |
| Settings | Configure disks, ROM, and display |
| About | Version and credits |

## Control Strip

The control strip provides quick access to special keys:

| Button | Function |
|--------|----------|
| Ctrl | Next key becomes control character (Ctrl+C, etc.) |
| Esc | Send escape character |
| Tab | Send tab character |
| Copy | Copy terminal screen to clipboard |
| Paste | Paste clipboard text as keyboard input |

## Booting CP/M

1. At the boot prompt `Boot [H=Help]:`, type `0` and press Enter
2. This boots the default OS from your configured disk
3. You'll see the `A>` prompt when CP/M is ready

## Basic Commands

| Command | Description |
|---------|-------------|
| `DIR` | List files in current drive |
| `DIR B:` | List files on drive B |
| `TYPE filename` | Display text file contents |
| `ERA filename` | Delete a file |
| `REN new=old` | Rename a file |
| `B:` | Switch to drive B |

## Drive Letters

- **A:** RAM disk (temporary storage, cleared on restart)
- **B:** ROM disk (read-only utilities)
- **C:-J:** Slices from your configured disk images

## Running Programs

Type the program name without the .COM extension:
```
A>MBASIC
A>WS
A>ZORK1
```

## File Transfer

Use R8 and W8 utilities to transfer files between Android and CP/M:

1. Place files in the **Imports** folder on your device
2. In CP/M, run `R8 FILENAME.EXT` to import
3. Run `W8 FILENAME.EXT` to export to **Exports** folder

The Imports/Exports folders are in Android/data/com.romwbw.cpmdroid/files/

## Changing Disk Images

1. Tap the Settings button
2. Select disk slots (0-3)
3. Choose from downloaded images or browse the catalog
4. Tap Reboot to use the new configuration

## Tips

- Tap the terminal screen to show the soft keyboard
- Use landscape mode for a wider terminal display
- Adjust font size in Settings for your screen
