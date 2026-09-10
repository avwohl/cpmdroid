# CP/M 2.2 User Guide

This guide covers using CP/M 2.2 in the emulator.

## Getting Started

### First Boot

1. Stop the emulator if it is running, then open **Settings**. On Android Settings will not open while the emulator runs - it answers "Stop emulator before changing settings" and does nothing else
2. Open the disk catalog (on Android, **Browse Disk Catalog**, or **Select disk** beside a disk slot) and download "CP/M 2.2", or "Combo" for more software
3. Return to the main screen and start the emulator
4. At the boot prompt `Boot [H=Help]:`, type the unit number of the disk and press Enter. Units 0 and 1 are the RAM and ROM memory disks, so the first attached hard disk is unit `2`. Typing `0` answers `*** No system image on disk`.

The Combo image is downloaded into disk slot 0 on first launch - the catalog marks it as the default for that slot - so a fresh install can skip to step 3 and type `2` there.

You'll see the CP/M prompt:

```
A>
```

The `A>` means you're on drive A. CP/M supports up to 16 drives, A through P; the drive map printed at boot shows which ones your disks provide.

### Switching Drives

Type a drive letter followed by a colon:

```
A>B:
B>A:
```

## Built-in Commands

CP/M has several commands built into the Console Command Processor (CCP):

### DIR - Directory Listing

List all files:
```
A>DIR
```

List files matching a pattern:
```
A>DIR *.COM
A>DIR READ*.TXT
```

Wildcards:
- `*` matches any characters
- `?` matches a single character

### TYPE - Display File Contents

```
A>TYPE README.TXT
```

Press any key to pause scrolling, Ctrl+C to stop.

### REN - Rename Files

```
A>REN NEWNAME.TXT=OLDNAME.TXT
```

### ERA - Erase Files

```
A>ERA MYFILE.TXT
A>ERA *.BAK
```

### SAVE - Save Memory to File

Save n pages (256 bytes each) from address 0100h:
```
A>SAVE 10 MYFILE.COM
```

### USER - Change User Area

CP/M supports 16 user areas (0-15):
```
A>USER 1
A>USER 0
```

Files in different user areas are separate.

## Running Programs

Type the program name without the .COM extension:
```
A>STAT
A>PIP
A>ED MYFILE.TXT
```

## Standard Utilities

The CP/M 2.2 disk includes these utilities:

### STAT - Statistics

Show disk space:
```
A>STAT
```

Show file sizes:
```
A>STAT *.*
```

Show drive characteristics:
```
A>STAT DSK:
```

### PIP - Peripheral Interchange Program

Copy files:
```
A>PIP B:=A:MYFILE.TXT
A>PIP DEST.TXT=SOURCE.TXT
```

Copy multiple files:
```
A>PIP B:=A:*.COM
```

Copy with options (V=verify):
```
A>PIP B:=A:*.TXT[V]
```

### ED - Line Editor

Edit a file:
```
A>ED MYFILE.TXT
```

ED commands (at `*` prompt):
- `nA` - Append n lines from file to buffer
- `nL` - List n lines
- `I` - Insert mode (end with Ctrl+Z)
- `nD` - Delete n lines
- `S` - Substitute text
- `E` - Save and exit
- `Q` - Quit without saving

### ASM - 8080 Assembler

Assemble source code:
```
A>ASM MYPROG
```

Reads MYPROG.ASM, creates MYPROG.HEX and MYPROG.PRN.

### LOAD - Create COM from HEX

Convert HEX to executable:
```
A>LOAD MYPROG
```

Creates MYPROG.COM from MYPROG.HEX.

### DDT - Dynamic Debugging Tool

Debug programs:
```
A>DDT MYPROG.COM
```

DDT commands:
- `D` - Dump memory
- `L` - List (disassemble)
- `S` - Set memory
- `G` - Go (execute)
- `T` - Trace
- `X` - Examine registers

### SUBMIT - Batch Processing

Run commands from file:
```
A>SUBMIT BATCH
```

Reads BATCH.SUB and executes commands.

## File Types

| Extension | Description |
|-----------|-------------|
| .COM | Executable program |
| .ASM | Assembly source code |
| .HEX | Intel hex format |
| .PRN | Assembler listing |
| .TXT | Text file |
| .SUB | Submit batch file |
| .BAK | Backup file |
| .$$ | Temporary file |

## Control Keys

| Key | Function |
|-----|----------|
| Ctrl+C | Cancel/warm boot |
| Ctrl+S | Pause output |
| Ctrl+Q | Resume output |
| Ctrl+P | Toggle printer echo |
| Ctrl+H or Backspace | Delete character |
| Ctrl+U | Delete line |
| Ctrl+R | Retype line |
| Ctrl+X | Delete line |

## Tips for the Emulator

### Using the Combo Disk

The Combo disk includes multiple slices:
- Slice 0: CP/M 2.2 with utilities
- Slice 1: Games and applications
- Additional slices with more software

The slice you boot becomes A:. B: and C: are the RAM and ROM memory disks, so the rest of that disk's slices start at D:. The drive map printed at boot shows the exact letters.

Boot from Combo, then explore:
```
A>DIR
A>D:
D>DIR
```

### Host File Transfer

The Combo disk includes R8 and W8 utilities for transferring files between CP/M and the host:

Export a file to the host:
```
A>W8 MYFILE.TXT
```

Import a file from the host:
```
A>R8 MYFILE.TXT
```

The host folder that W8 writes to and R8 reads from differs per platform - see the "File Transfer (R8/W8)" topic for the exact location on iOS, macOS, Android and Windows.

### Saving Your Work

CP/M disk images are stored in the app's own data folder on the host. Any files you create or modify are saved to the disk image automatically.

### Multiple Disks

You can have up to 4 disk units loaded. Use Settings to assign different disk images to different units (stop the emulator first on Android).

## Common Tasks

### Create a Text File

```
A>ED HELLO.TXT
*I
This is my text file.
It can have multiple lines.
^Z
*E
```

### Compile and Run Assembly

1. Create source file:
```
A>ED HELLO.ASM
```

2. Assemble:
```
A>ASM HELLO
```

3. Load:
```
A>LOAD HELLO
```

4. Run:
```
A>HELLO
```

### Backup Files

```
A>PIP B:=A:*.*[V]
```

## Troubleshooting

**"BDOS ERR ON A: R/O"**
Disk is read-only. Warm boot (Ctrl+C) and try again.

**"NO FILE"**
File not found. Check spelling and use DIR to list files.

**"DISK FULL"**
Delete unnecessary files or use STAT to check space.

**Screen corruption**
Some programs may leave the screen in an odd state. Warm boot with Ctrl+C to reset.

## Further Reading

- CP/M 2.2 manuals are available online at various retrocomputing sites
- The HELP.COM utility on some disks provides online documentation
- Experiment with DIR to discover what's on each disk image
