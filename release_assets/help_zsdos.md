# ZSDOS User Guide for Z80CPM

ZSDOS (Z-System DOS) is an enhanced CP/M 2.2 compatible operating system with date/time stamping and many improvements.

## What is ZSDOS?

ZSDOS is a drop-in replacement for the CP/M 2.2 BDOS (Basic Disk Operating System). It provides:

- **Date/time stamping** of files (create, modify, access times)
- **Automatic disk relog** - no more "R/O" errors when changing disks
- **Z80 optimization** - faster than the 8080-based CP/M
- **1GB disk support** - larger than CP/M's limits
- **32MB file size support**
- **Public files and directories** - share files across user areas
- **Better error messages** - shows filename in errors
- **Path searching** - find programs in multiple directories

## Getting Started

1. Download the "ZSDOS" disk image in Z80CPM Settings
2. Boot from it (press `0` at boot menu)
3. You'll see the familiar `A>` prompt

ZSDOS is fully compatible with CP/M 2.2 - all your CP/M programs work unchanged.

## Key Differences from CP/M 2.2

### No More R/O Errors

In CP/M 2.2, if the system detects a disk change, you get "BDOS ERR: R/O" and must reboot. ZSDOS automatically relogs changed disks - just keep working.

### Date/Time Stamps

Files can have timestamps showing when they were created and modified. Use enhanced directory utilities to see them:

```
A>SD
```

The SD utility shows file dates alongside names.

### Better Error Messages

Instead of cryptic codes, ZSDOS shows:
```
File not found: MYFILE.TXT
```

### Public Directory

Files in user area 0 can be made "public" - accessible from any user area:
```
A>USER 5
A>DIR           ; shows files in user 5
A>STAT.COM      ; runs STAT from user 0 (public)
```

## ZSDOS-Specific Commands

### ZSCONFIG - Configure ZSDOS

```
A>ZSCONFIG
```

Interactive configuration of ZSDOS features:
- Enable/disable date stamping
- Configure public directory
- Set error handling mode
- Enable/disable auto-relog

### Date/Time Utilities

Show current date/time:
```
A>DATE
```

Set date/time (if supported):
```
A>DATE SET
```

### Enhanced Directory

```
A>SD            ; sorted directory with dates
A>SD *.COM      ; filter by pattern
```

## File Date Stamping

ZSDOS supports two stamping methods:

1. **DateStamper** - stores dates in !!!TIME&.DAT files
2. **CP/M Plus style** - stores dates in directory entries

The disk image is pre-configured. When you create or modify files, timestamps are automatically recorded.

## Path Searching

ZSDOS can search multiple directories for programs:

```
A>PATH A0: B0: C0:
```

Now when you type a command, ZSDOS looks in:
1. Current directory
2. A: user 0
3. B: user 0
4. C: user 0

## Compatibility Notes

### What Works

- All CP/M 2.2 programs
- All standard utilities (PIP, STAT, ED, etc.)
- Most Z-System utilities
- Programs expecting CP/M BDOS calls

### What's Different

- Some copy-protected software may not work (rare)
- Programs that directly access BDOS internals may behave differently
- Date display requires date-aware utilities

## Tips for Z80CPM

### Best Starter Choice

For most users, ZSDOS offers the best balance of compatibility and features. If you want date stamps on your files, use ZSDOS.

### Using with Other Disks

ZSDOS can read/write all CP/M 2.2 format disks. Mount a games or applications disk as B: and access it normally:

```
A>B:
B>DIR
B>ZORK
```

### File Transfer

R8/W8 utilities work with ZSDOS just like CP/M 2.2:
```
A>W8 MYFILE.TXT    ; export to host
A>R8 NEWFILE.TXT   ; import from host
```

## Troubleshooting

**"Clock not available"**
Date stamping requires a system clock. The emulator provides one automatically.

**Files show no dates**
Use a date-aware directory utility like SD rather than plain DIR.

**Program not found**
Check if PATH is set. Use `PATH` command to see/set search path.

## Further Reading

- [ZSDOS Manual](https://deramp.com/downloads/mfe_archive/040-Software/Z80%20CPM/ZSDOS/ZSDOS%20Manual.TXT)
- [Z-System Documentation](https://www.retrobrewcomputers.org/doku.php?id=playground%3Awwarthen%3Azsystem)
