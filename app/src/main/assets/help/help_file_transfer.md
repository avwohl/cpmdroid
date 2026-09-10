# File Transfer (R8/W8)

The R8 and W8 utilities transfer files between the host device and CP/M.

## R8 - Read from Host

Brings a file in from the host and copies it into CP/M.

### Usage
```
R8 filename.ext
```

### Example
```
A>R8 MYFILE.TXT
```

This copies `MYFILE.TXT` from the host into the current CP/M drive. Give the
name of a file that is already in the folder for your platform — R8 reports an
error if it cannot find that name, and never substitutes another file.

Android has one exception, and it is not a fallback for a name you typed: an
`R8` with no name at all sends the app no name, the app reads that as no
preference, and it hands over one of the files already sitting in **Imports**.
Which one is whatever order the filesystem returns, not the first
alphabetically, so type the name you mean unless you truly do not care.

## W8 - Write to Host

Sends a file out from CP/M to the host.

### Usage
```
W8 filename.ext
```

### Example
```
A>W8 OUTPUT.TXT
```

This copies `OUTPUT.TXT` from the current CP/M drive out to the host.

On Android, W8 prints the full host path it wrote to and the app raises a
**W8: Saved ...** toast naming the file. Read the path: it is the one place the
Exports folder is spelled out on the device itself, and the folder is not one
the stock Files app will show you.

## Folder Locations

There is no per-transfer Save/Open dialog: R8 reads from a fixed folder and W8
writes to one. Which folder depends on the platform. iOS, iPadOS, macOS and
Android use a separate **Imports** and **Exports** pair; Windows uses a single
data folder for both. A file that lives anywhere else has to be copied into the
folder first — except on Windows, where R8 also takes a full host path.

### iOS / iPadOS
The app's Documents folder is published to the Files app:

- **Imports**: Files → On My iPhone/iPad → **Z80CPM** → Imports
- **Exports**: Files → On My iPhone/iPad → **Z80CPM** → Exports

### macOS
The app is sandboxed, so the folders live inside its container:

- **Imports**: ~/Library/Containers/com.awohl.cpm/Data/Documents/Imports
- **Exports**: ~/Library/Containers/com.awohl.cpm/Data/Documents/Exports

Finder hides that container, so use the **Open Imports Folder** and
**Open Exports Folder** menu items to get to them.

### Android
The folders are in the app's own external files directory:

- **Imports**: /storage/emulated/0/Android/data/com.awohl.cpmdroid/files/Imports
- **Exports**: /storage/emulated/0/Android/data/com.awohl.cpmdroid/files/Exports

Android 11 and later hide `Android/data` from the stock Files app, so that app
cannot browse there no matter how you navigate. Reach the folders with a
third-party file manager, over USB in MTP mode, or with `adb push` and
`adb pull`.

The app has its own view of both folders, and it is the way round all of that:
the **File transfer** button in the toolbar lists what is in Imports and
Exports, imports a file with a picker, and saves or shares an exported one out
to anywhere on the device. CPMDroid is also a share target, so another app can
send it a file directly. An imported name is rewritten to something CP/M can
address - `My Long Archive.tar.gz` becomes `my-long-.gz`, and the app tells you
which name it got.

Staging files by hand still works. A copyable form of the last one:

```
adb pull /storage/emulated/0/Android/data/com.awohl.cpmdroid/files/Exports/OUTPUT.TXT
```

To import that way, put the file into the matching **Imports** folder, then run
`R8 FILENAME.EXT`.

### Windows
There is no Imports/Exports split. One flat data folder holds the disk images
and the R8/W8 transfers together:

- **Data folder**: %LOCALAPPDATA%\z80cpmw\data

Settings shows it as **Data folder (disks and R8/W8 transfers)** with an
**Open Folder** button beside it. An MSIX install — the Store build and the signed
sideload beta — has Windows redirect that write into a per-package folder under
`%LOCALAPPDATA%\Packages`, ending in `LocalCache\Local`, so the real folder is
not the literal path above. The app displays the redirected path, so open the
one Settings shows.

R8 on Windows also accepts a full host path, so it is not limited to that
folder: `R8 C:\Users\me\Desktop\GETKEY.COM` reads the file where it sits. W8
does not do the same on the disk images shipping today — the utility passes on
only the filename the CCP parsed into the FCB, so a path you type is discarded
and every export lands in the data folder. That is a property of the R8 and W8
programs on the images rather than of the app, and it is expected to change when
the images are refreshed.

## Tips

- Filenames must follow CP/M conventions (8.3 format)
- Files are transferred as binary (no conversion)
- R8.COM and W8.COM are in user 0 of slice 0 of the Combo disk image. RomWBW maps the slice you boot as A:, so booting that slice puts them on A: — B: and C: are the RAM and ROM memory disks, not part of the image
