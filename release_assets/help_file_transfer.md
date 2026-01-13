# File Transfer (R8/W8)

The R8 and W8 utilities allow transferring files between the host system and CP/M.

## R8 - Read from Host

Copies a file from the host's Imports folder into CP/M.

### Usage
```
R8 filename.ext
```

### Example
```
A>R8 MYFILE.TXT
```

This copies `MYFILE.TXT` from your Imports folder to the current CP/M drive.

## W8 - Write to Host

Copies a file from CP/M to the host's Exports folder.

### Usage
```
W8 filename.ext
```

### Example
```
A>W8 OUTPUT.TXT
```

This copies `OUTPUT.TXT` from the current CP/M drive to your Exports folder.

## Folder Locations

### iOS
- **Imports**: Files app → iOSCPM → Imports
- **Exports**: Files app → iOSCPM → Exports

### macOS (Catalyst)
- **Imports**: ~/Library/Containers/com.awohl.iOSCPM/Data/Documents/Imports
- **Exports**: ~/Library/Containers/com.awohl.iOSCPM/Data/Documents/Exports

Use the menu items **File → Open Imports Folder** and **File → Open Exports Folder** for quick access.

## Tips

- Filenames must follow CP/M conventions (8.3 format)
- Files are transferred as binary (no conversion)
- The Combo disk includes R8.COM and W8.COM on drive B:
