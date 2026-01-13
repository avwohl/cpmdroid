# NZCOM User Guide for Z80CPM

NZCOM (New Z-COM) is the "Automatic Z-System" - an easy way to run ZCPR3 on top of CP/M 2.2.

## What is NZCOM?

NZCOM provides ZCPR3 features as a loadable system that runs on CP/M 2.2:

- **Easy installation** - runs on any CP/M 2.2 system
- **ZCPR 3.4 command processor** - latest ZCPR features
- **ZSDOS included** - date/time stamping
- **Configurable** - customize via menus
- **Unix-like features** - aliases, paths, shells

## Getting Started

1. Download the "NZCOM" disk image in Z80CPM Settings
2. Boot from it (press `0` at boot menu)
3. Run NZCOM to load the Z-System:
   ```
   A>NZCOM
   ```
4. You'll see the ZCPR prompt: `A0>`

## How NZCOM Works

NZCOM loads several components into memory:

- **NZCPR** - ZCPR 3.4 command processor
- **ZSDOS** - Enhanced DOS with date stamping
- **RCP** - Resident Command Package
- **FCP** - Flow Control Package
- **IOP** - I/O Package

These replace the standard CP/M CCP and BDOS while running.

## ZCPR Features in NZCOM

### Multiple Commands

```
A0>DIR;STAT
```

### Search Path

```
A0>PATH A0: B0:
```

### Aliases

```
A0>ALIAS L DIR $1 /W
A0>L *.COM
```

### Named Directories

```
A0>GAMES:
```

### Shell Commands

```
A0>IF EXIST MYFILE.TXT
A0>  TYPE MYFILE.TXT
A0>FI
```

### Wheel Protection

System commands can be restricted:
```
A0>WHEEL ON        ; enable privileged mode
A0>WHEEL OFF       ; disable
```

## Key NZCOM Commands

### MKNZC - Configure NZCOM

Menu-driven configuration:
```
A0>MKNZC
```

Set up:
- Terminal type
- Clock driver
- Command search path
- Resident commands

### PATH - Set Search Path

```
A0>PATH A0: B0: C0:
A0>PATH            ; show current
```

### SD - Directory

```
A0>SD              ; sorted directory
A0>SD /D           ; show dates
```

### ZFILER - File Manager

```
A0>ZFILER
```

Visual file manager with tagging support.

### ZEX - Extended Submit

Powerful batch file processor:
```
A0>ZEX SETUP
```

## Configuring NZCOM

### First-Time Setup

1. Run MKNZC:
   ```
   A0>MKNZC
   ```
2. Select your terminal type
3. Configure the clock
4. Set default path
5. Save configuration

### Startup File

Create STARTZCM.COM for automatic setup when NZCOM loads.

## NZCOM vs. ZPM3

| Feature | NZCOM | ZPM3 |
|---------|-------|------|
| Base system | CP/M 2.2 | CP/M 3-like |
| Installation | Loadable | Boot disk |
| ZCPR version | 3.4 | 3.3 |
| Configuration | Menu-driven | Manual |
| Return to CP/M | Easy | Reboot |

## Tips for Z80CPM

### When to Use NZCOM

- You want ZCPR features but easy return to CP/M
- You want to experiment with Z-System
- You need maximum compatibility with CP/M 2.2

### Exiting NZCOM

To return to plain CP/M 2.2:
```
A0>BYE            ; or warm boot
```

### Best Utilities

- **SD** - directory listing
- **ZFILER** - file management
- **ALIAS** - command shortcuts
- **ZEX** - batch processing

## Troubleshooting

**"NZCOM not found"**
Make sure NZCOM.COM is on the disk and you're in the right directory.

**"TCAP not found"**
NZCOM needs terminal capability data. Run MKNZC to configure.

**Commands don't work after loading**
The resident commands may differ. Use SD instead of DIR, etc.

**Screen display problems**
Configure terminal type in MKNZC.

## Further Reading

- [NZCOM Manual](https://oldcomputers.dyndns.org/public/pub/manuals/zcpr/nzcom.pdf)
- [ZCPR 3.3 User's Guide](http://gaby.de/ftp/pub/cpm/znode51/specials/manuals/zcpr3.pdf)
