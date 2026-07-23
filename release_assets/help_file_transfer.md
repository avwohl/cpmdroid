# File Transfer (R8/W8)

The R8 and W8 utilities transfer files between Android and CP/M.

## R8 - Read from Host

Copies a file from the app's Imports folder into CP/M.

### Usage
```
R8 filename.ext
```

### Example
```
A>R8 MYFILE.TXT
```

This copies `MYFILE.TXT` from the Imports folder to the current CP/M drive. Run
`R8` with no name to read the first file in the folder.

## W8 - Write to Host

Copies a file from CP/M to the app's Exports folder.

### Usage
```
W8 filename.ext
```

### Example
```
A>W8 OUTPUT.TXT
```

This copies `OUTPUT.TXT` from the current CP/M drive to the Exports folder, and
shows a "W8: Saved …" message.

## Folder Locations

The Imports and Exports folders are in the app's private external storage:

- **Imports**: /storage/emulated/0/Android/data/com.awohl.cpmdroid/files/Imports
- **Exports**: /storage/emulated/0/Android/data/com.awohl.cpmdroid/files/Exports

## Finding these folders on Android

Since Android 11, the system Files app and the file picker **hide the
`Android/data/…` folders**, even though the app writes there without needing any
storage permission. To get an exported file off the device, use one of:

- a **third-party file manager** that can browse `Android/data/…`, or
- a **computer over USB** (MTP): open
  `Android/data/com.awohl.cpmdroid/files/Exports`, or
- **adb**:
  `adb pull /storage/emulated/0/Android/data/com.awohl.cpmdroid/files/Exports/OUTPUT.TXT`

To import, place the file in the matching **Imports** folder the same way, then
run `R8 FILENAME.EXT`.

## Tips

- Filenames must follow CP/M conventions (8.3 format)
- Files are transferred as binary (no conversion)
- The Combo disk includes R8.COM and W8.COM on drive B:
