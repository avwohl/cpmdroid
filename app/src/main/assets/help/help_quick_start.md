# Quick Start Guide

## First Launch

**The first launch needs a network connection.** The app carries no ROM and no
disk image. It fetches the ROM the selected RomWBW release publishes, checks it
against the size and SHA-256 that release's catalog gives for it, and only then
starts. With no network it says so and offers to download rather than booting
bytes it cannot check.

Once the ROM is in hand the **Combo** disk image is downloaded and assigned to
disk slot 0 - the catalog marks it as the default for that slot. Its name
carries the release it belongs to, so under RomWBW 3.6.0 it is
`hd1k_combo-v0-3.6.0.img`. Then you get the RomWBW boot loader screen.

Every launch after that works offline. What the catalog said about the ROM is
stored beside it and re-checked against the file, so nothing is fetched again
unless it is missing or no longer matches.

## Where the ROM and the disks come from

Everything is downloaded from a catalog, and nothing about it is compiled into
the app except the address of the catalog's index. That is what lets a new ROM,
a new disk image or a whole new RomWBW release reach an installed app with no
update from the store.

Three rows in Settings come out of that catalog rather than out of the app:

- **ROM** - the ROMs the selected release publishes. The one you pick is
  fetched, if it is not already on the device, and checked before the machine
  starts
- **RomWBW Release** - the release everything else follows. A new install takes
  whichever release the catalog marks current, the first time it reads it, and
  then stays there; this row is what moves it afterwards. Each release keeps its
  own disk slots, boot settings and ROM, so switching and switching back loses
  nothing
- **Catalog index** - which catalog all of the above comes from. Empty is the
  default one. A URL here points the app at another index, which is how a
  release is tested before it is published; each index keeps its own downloads
  and settings

Every download is checked against the size and SHA-256 the catalog publishes,
and bytes that do not match are not kept.

## Booting CP/M

1. At the boot prompt `Boot [H=Help]:`, type the unit number of the hard disk
   you want to boot and press Enter - with the default image that is `2`
2. Units 0 and 1 are the RAM and ROM memory disks and carry no operating
   system, so the first attached hard disk (Disk 0, the Combo image) is unit 2
   and the next attached disk is unit 3. Typing `0` answers
   `*** No system image on disk` - that is the memory disk answering, not a
   broken download
3. Plain `2` boots slice 0; type `2.3` for a specific slice
4. You'll see the `A>` prompt when CP/M is ready

At the boot prompt, `D` lists the disk units that are actually attached, `L`
lists the ROM applications, and `W` opens RomWBW Configure, whose Boot Options
page sets the autoboot default.

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
for instance - **A:** is the RAM disk, **B:** is the ROM disk, and the slices of
your configured images follow from **C:**.

Booting rearranges all of it. RomWBW hands out the letters as it boots: the
slice you booted becomes **A:**, the two memory disks follow as **B:** (RAM) and
**C:** (ROM), and the slices left over take the letters after that - the rest of
the first disk before the second disk.

With the default setup, one Combo image in slot 0 booted with `2`:

| Drive | Contents |
|-------|----------|
| `A:` | The slice you booted (Disk 0, slice 0) |
| `B:` | RAM disk (temporary storage, cleared on restart) |
| `C:` | ROM disk (read-only utilities) |
| `D:` onwards | The rest of Disk 0's slices, then any other disk you attach |

The Combo image is six slices, so it alone accounts for `D:` through `H:`. Boot
a different slice and that slice becomes `A:` instead, with the others following
in the same order. **The drive map CBIOS prints at boot is the authority** -
read it rather than counting, because the slice count belongs to the image and
changes with the release.

## Running Programs

Just type the program name without the .COM extension:
```
A>MBASIC
A>WS
A>ZORK1
```

## Control Keys

Every Ctrl keystroke is folded to its ASCII control byte and passed straight to
CP/M: Ctrl+A through Ctrl+Z give 0x01-0x1A, Ctrl+@ and Ctrl+Space give NUL,
Ctrl+[ \ ] ^ and _ give 0x1B-0x1F, and Ctrl+? or Ctrl+Backspace give DEL.
There is no emulator console - **Ctrl+E** is WordStar cursor-up, not a debugger.

On Windows and macOS you type all of these on the keyboard. On iOS, iPadOS and
Android the buttons beside the terminal stand in when no hardware keyboard is
attached: **Ctrl** folds the next key you type and then turns itself off again,
and **Esc** and **Tab** send those keys directly. The control strip covers the
same `@` through `_` range, so with no hardware keyboard, NUL is **Ctrl** then
`@` rather than **Ctrl** then Space.

The app keeps only a few keys for itself: Shift+Page Up / Shift+Page Down and
Ctrl+Home / Ctrl+End scroll the terminal history on Windows, macOS, iOS and
iPadOS; on Android you scroll by dragging the screen, and those four key
combinations work there too with a hardware keyboard attached. Unmodified Page
Up, Page Down, Home and End still reach the guest.

The view stays where you put it while CP/M keeps printing, so you can read a
listing that is still being written; typing anything returns you to the live
prompt. How much history is kept is a setting.

Copy and paste are on every port, reached differently:

- **iOS and iPadOS, by touch:** **press and hold** the terminal, then **drag**
  without lifting to select. The text highlights as you go, and lifting your
  finger brings up **Copy**, **Copy All** and **Paste**. Press and hold without
  dragging for the same menu with nothing selected. A one-finger drag on its own
  still scrolls the history, as it always has - it is the *holding still* that
  starts a selection.
- **macOS:** select with the pointer, then **Cmd+C**, or press and hold for the
  menu. **Cmd+V** pastes.
- **With a hardware keyboard** on any of the three: **Cmd+C** and **Cmd+V**.
  Cmd+C with nothing selected copies the whole screen.
- **Android:** the **Copy** and **Paste** buttons beside the terminal.
- **Windows:** select with the mouse and right-click for **Copy** and **Paste** -
  there Ctrl+C is a CP/M keystroke, not a copy.

Selection reads the screen as you see it, so scrolling back into the history and
selecting there copies what is on screen, not what the guest has printed since.

## Changing Disks

Open Settings to select different disk images for each disk slot, or to browse
the catalog and download more. On Android, stop the emulator first - Settings
refuses to open while it is running, and answers "Stop emulator before changing
settings".

After changing a slot, reboot the machine: that is what reloads the disks. The
slots belong to the selected RomWBW release, so a release you have not used
before starts with none assigned, and the ones you had come back when you select
that release again.
