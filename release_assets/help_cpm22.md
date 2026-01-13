# CP/M 2.2 User Guide for Z80CPM

This guide covers using CP/M 2.2 in the Z80CPM emulator on iOS and macOS.

## Getting Started

### First Boot

1. Open Z80CPM and tap the **gear icon** (Settings)
2. Scroll to **Download Disk Images** and download "CP/M 2.2" (or "Combo" for more software)
3. Return to main screen and tap **Play**
4. At the boot menu, press `0` to boot from disk

You'll see the CP/M prompt:

```
A>
```

The `A>` means you're on drive A. CP/M supports drives A through D.

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

## Tips for Z80CPM

### Using the Combo Disk

The Combo disk includes multiple slices:
- Slice 0 (A:): CP/M 2.2 with utilities
- Slice 1 (B:): Games and applications
- Additional slices with more software

Boot from Combo, then explore:
```
A>DIR
A>B:
B>DIR
```

### Host File Transfer

The Combo disk includes R8 and W8 utilities for transferring files between CP/M and your device:

Export a file to host (appears in Exports folder):
```
A>W8 MYFILE.TXT
```

Import a file from host (place in Imports folder):
```
A>R8 MYFILE.TXT
```

Access the Imports/Exports folders via the menu on Mac, or Files app on iOS.

### Saving Your Work

CP/M disk images are stored in the app's Documents folder. Any files you create or modify are saved to the disk image automatically.

### Multiple Disks

You can have up to 4 disk units loaded. Use Settings to assign different disk images to different units.

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
