# ZPM3 User Guide for Z80CPM

ZPM3 is an enhanced operating system combining CP/M 3 (Plus) compatibility with ZCPR3 command processor features.

## What is ZPM3?

ZPM3 (Z-System Plus/M3) was created by Simeon Cran and provides:

- **CP/M 3 (Plus) compatibility** - runs CP/M 3 software
- **ZCPR 3.3 command processor** - powerful shell features
- **More TPA** - larger program space than Z3Plus
- **Z80 optimization** - faster than original CP/M
- **Advanced command line** - aliases, flow control, multiple commands

## Getting Started

1. Download the "ZPM3" disk image in Z80CPM Settings
2. Boot from it (press `0` at boot menu)
3. You'll see a prompt like `A0>`

The `A0>` shows drive A, user 0. This is the ZCPR-style prompt.

## ZCPR3 Features

### Multiple Commands Per Line

Separate commands with semicolons:
```
A0>DIR;STAT
```

### Command Search Path

ZPM3 searches multiple locations for programs:
```
A0>PATH A0: B0:
A0>PATH             ; show current path
```

### Named Directories

Instead of just A:, B:, you can use named directories:
```
A0>CD GAMES         ; change to directory named GAMES
A0>GAMES:           ; also works
```

### GO Command

Re-run the last program without reloading:
```
A0>MYPROG           ; run program
A0>GO               ; run it again (instant)
```

### Aliases

Create command shortcuts:
```
A0>ALIAS D DIR $*
A0>D *.COM          ; same as DIR *.COM
```

### Flow Control

Scripts can make decisions:
```
IF EXIST MYFILE.TXT
  TYPE MYFILE.TXT
ELSE
  ECHO File not found
FI
```

## Key Commands

### CLS - Clear Screen

```
A0>CLS
```

### SETPTH - Set Search Path

```
A0>SETPTH A0: B0: C0:
```

### SD - Super Directory

Enhanced directory listing:
```
A0>SD              ; sorted, with sizes
A0>SD /A           ; all files
A0>SD *.COM /S     ; by size
```

### ARUNZ - Alias Runner

Execute alias scripts from files.

### ZFILER - File Manager

Visual file management shell:
```
A0>ZFILER
```

Tag files, copy, move, delete with a menu interface.

## Startup Configuration

When ZPM3 boots, it runs `STARTZPM.COM` if present. This sets up your environment:

```
; Example startup alias
PATH A0: B0:
ECHO Welcome to ZPM3!
```

## Differences from CP/M 2.2

| Feature | CP/M 2.2 | ZPM3 |
|---------|----------|------|
| Commands per line | 1 | Multiple (;) |
| Program search | Current dir only | Path-based |
| Named directories | No | Yes |
| Aliases | No | Yes |
| Flow control | No | Yes |
| Re-run command | No | GO |

## Compatibility

### What Works

- All CP/M 2.2 programs
- Most CP/M 3 (Plus) programs
- Z-System/ZCPR utilities
- Standard utilities (PIP, STAT, etc.)

### What's Different

- Prompt shows user number (A0> vs A>)
- Some resident commands differ
- Environment requires initialization

## Tips for Z80CPM

### Learning Curve

ZPM3 has more features than CP/M 2.2. Start with basic commands:
```
A0>DIR
A0>SD
A0>PATH
```

Then explore aliases and flow control as needed.

### Using SD Instead of DIR

The SD command is far more useful:
```
A0>SD              ; sorted directory
A0>SD /W           ; wide format
A0>SD /P           ; pause between pages
```

### Exploring the Disk

```
A0>SD *.COM        ; list all programs
A0>HELP            ; if available
```

## Troubleshooting

**Command not found**
Check if PATH is set:
```
A0>PATH
```

Set a path if empty:
```
A0>PATH A0:
```

**Alias doesn't work**
Make sure ARUNZ is in your path for alias execution.

**Screen looks wrong**
```
A0>CLS
```

## Further Reading

- [ZPM3 Documentation](https://github.com/wwarthen/CPU280/blob/master/ZPM3/zpm3.txt)
- [ZCPR 3.3 User's Guide](http://gaby.de/ftp/pub/cpm/znode51/specials/manuals/zcpr3.pdf)
- [Z3Plus Manual](https://oldcomputers.dyndns.org/public/pub/manuals/zcpr/z3plus.pdf)
