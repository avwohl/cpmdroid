# Quick Start Guide for CPMDroid

## First Launch

When you first launch CPMDroid, the Combo disk image (`hd1k_combo.img`) is
downloaded and assigned to disk slot 0. You'll see the RomWBW boot loader
screen.

## Toolbar

Five buttons and a status label, left to right. There is no gear icon and no
overflow menu - this is the whole toolbar.

| Control | Function |
|---------|----------|
| Start/Stop | Start the emulator, or stop it |
| Boot/Reboot | Reset the machine and reboot, after a confirmation |
| Running / Stopped | Not a button - the emulator's current state |
| ? | Help topics (this screen) |
| Settings | ROM, disk slots, font size, scrollback and display options |
| About | Version and credits |

**Settings only opens while the emulator is stopped.** Tapping it during a
session raises "Stop emulator before changing settings" and does nothing else,
so stop first, change what you came for, then start and reboot.

## Control Strip

The strip below the terminal stands in for the keys a soft keyboard has no room
for:

| Button | Function |
|--------|----------|
| Ctrl | Fold the next key into a control character, then turn itself off |
| Esc | Send escape (0x1B) |
| Tab | Send tab (0x09) |
| Copy | Copy the terminal screen to the clipboard |
| Paste | Paste clipboard text as keyboard input |

## Booting CP/M

1. At the boot prompt `Boot [H=Help]:`, type `2` and press Enter
2. Unit `2` is the first hard disk, the image in slot 0. Units 0 and 1 are the
   RAM and ROM memory disks and carry no operating system, so typing `0`
   answers `*** No system image on disk` - it is not a broken download
3. Plain `2` boots slice 0; type `2.3` for a specific slice. A second
   configured disk is unit 3
4. You'll see the `A>` prompt when CP/M is ready

At the boot prompt, `D` lists the disk units actually attached, `L` lists the
ROM applications, and `W` opens RomWBW Configure, whose Boot Options page sets
the autoboot default.

## Basic Commands

| Command | Description |
|---------|-------------|
| `DIR` | List files in current drive |
| `DIR D:` | List files on drive D |
| `TYPE filename` | Display text file contents |
| `ERA filename` | Delete a file |
| `REN new=old` | Rename a file |
| `D:` | Switch to drive D |

## Drive Letters

Before you boot an OS from a hard disk - while a ROM application is running,
for instance - **A:** is the RAM disk, **B:** is the ROM disk, and the slices
of your configured images follow from **C:**.

Booting rearranges all of it. RomWBW hands out the letters as it boots: the
slice you booted becomes **A:**, the two memory disks follow as **B:** (RAM)
and **C:** (ROM), and the slices left over take the letters after that.

With the default setup - one disk image in slot 0, booted with `2`:

| Drive | Contents |
|-------|----------|
| `A:` | The slice you booted (Disk 0, slice 0) |
| `B:` | RAM disk (temporary storage, cleared on restart) |
| `C:` | ROM disk (read-only utilities) |
| `D:-F:` | The rest of Disk 0, slices 1-3 |

A second configured disk continues from `G:`. Boot a different slice and that
slice becomes `A:` instead, with the others following in the same order. The
drive map CBIOS prints at boot is the authority - read it rather than counting.

## Running Programs

Type the program name without the .COM extension:
```
A>MBASIC
A>WS
A>ZORK1
```

## Control Keys

A Ctrl keystroke is folded to its ASCII control byte and passed straight to
CP/M: Ctrl+A through Ctrl+Z give 0x01-0x1A, Ctrl+@ gives NUL, and
Ctrl+[ \ ] ^ and _ give 0x1B-0x1F. There is no emulator console - **Ctrl+E** is
WordStar cursor-up, not a debugger.

With a hardware or Bluetooth keyboard you type all of those directly, and
Ctrl+Space also gives NUL. With no hardware keyboard the control strip stands
in: **Ctrl** folds the next key you type and then turns itself off again. It
covers the same `@` through `_` range, so for NUL tap **Ctrl** then `@` rather
than **Ctrl** then Space.

Scrollback is a drag or a key combination. Pull the terminal down to walk back
through the history and up to return. With a hardware keyboard, Shift+PageUp
and Shift+PageDown move a screen at a time and Ctrl+Home and Ctrl+End jump to
the oldest line and back to the live prompt; the app answers those four itself,
so plain PageUp, PageDown, Home and End still reach CP/M. The view stays where
you put it while CP/M keeps printing, so you can read a listing that is still
being written; typing anything returns you to the live prompt. Scrollback works
with the keyboard open too. How much history is kept is **Terminal Scrollback**
in Settings.

Copy and paste are the **Copy** and **Paste** buttons on the control strip -
Ctrl+C is a CP/M keystroke here, not a copy.

## File Transfer

Use the R8 and W8 utilities to move files between Android and CP/M:

1. Put the file in the **Imports** folder on the device
2. In CP/M, run `R8 FILENAME.EXT` to import it
3. Run `W8 FILENAME.EXT` to export to the **Exports** folder

Both folders are under
`/storage/emulated/0/Android/data/com.awohl.cpmdroid/files/`, which Android 11
and later hide from the stock Files app. See the "File Transfer (R8/W8)" topic
for how to reach them anyway.

## Changing Disk Images

1. Stop the emulator - Settings will not open while it is running
2. Tap Settings
3. Assign disk slots 0-3 from the downloaded images, or browse the catalog to
   download more
4. Go back and tap Boot/Reboot, then confirm Restart - that reloads the disks
   and starts the emulator, so there is no separate Start to press

## Tips

- Tap the terminal screen to show the soft keyboard
- Use landscape mode for a wider terminal display
- Adjust **Font Size** in Settings to suit the screen
- Turn on **Wrap long lines** in Settings to wrap at the screen edge instead of
  truncating
