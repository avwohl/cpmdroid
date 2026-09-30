# Using CPMDroid

The boot menu, the control strip and R8/W8 file transfer. The first-launch
steps are in [../README.md](../README.md#getting-started).

## Boot Menu Keys

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

## Control Strip

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
