# QP/M User Guide for Z80CPM

QP/M is a CP/M 2.2 compatible operating system with automatic date/time stamping and program search features.

## What is QP/M?

QP/M (Quick P/M) was developed by MICROCode Consulting as an enhanced alternative to CP/M 2.2:

- **100% CP/M 2.2 compatible** - all programs work
- **Automatic date/time stamps** - on create, modify, access
- **Common drive/user search** - find programs anywhere
- **Z80 optimized** - faster than 8080 code
- **Drop-in replacement** - replaces BDOS and CCP

## Getting Started

1. Download the "QPM" disk image in Z80CPM Settings
2. Boot from it (press `0` at boot menu)
3. You'll see the familiar `A>` prompt

QP/M looks and works like CP/M 2.2, with extra features running transparently.

## Key Features

### Automatic Date/Time Stamping

QP/M automatically records:
- **Creation date** - when file is first created
- **Modification date** - when file is written
- **Access date** - when file is read

No special commands needed - it happens automatically.

### Common Drive Search

QP/M can search for programs in multiple locations:

1. Current drive and user
2. Common user area on current drive (usually user 0)
3. Common drive (usually A:)

Example:
```
A>USER 5
A>STAT              ; not in B5
                    ; looks in B0
                    ; looks in A0 - found!
```

### Viewing Dates

Use QP/M's enhanced directory:
```
A>QD               ; shows dates with files
```

Or configure DIR to show dates.

## QP/M Commands

### Standard CP/M Commands

All CP/M commands work:
```
A>DIR
A>TYPE README.TXT
A>STAT
A>PIP B:=A:*.*
```

### QINSTALL - Configure QP/M

```
A>QINSTALL
```

Configure:
- Common drive (default A:)
- Common user (default 0)
- Date/time display format
- Drive search order

### QD - QP/M Directory

```
A>QD               ; directory with dates
A>QD *.COM         ; filter by pattern
```

Shows:
- Filename
- Size
- Creation date
- Modification date

### Date Utilities

Set system date/time:
```
A>DATE
```

## How Date Stamping Works

QP/M stores date information transparently alongside files. The system clock provides current date/time, and QP/M updates stamps when:

- **Creating a file** - sets creation stamp
- **Writing to a file** - updates modification stamp
- **Reading a file** - updates access stamp (if enabled)

## Common Drive/User Feature

### Setting Up

Use QINSTALL to configure:
- Common drive: Usually A:
- Common user: Usually 0

### How It Works

When you request a file:
1. QP/M looks in current location (e.g., B5)
2. If not found, looks in current drive user 0 (B0)
3. If not found, looks in common drive user 0 (A0)

This means your utilities in A0 are available everywhere.

### Example

```
A>USER 0
A>DIR STAT.COM     ; STAT is here
A>B:
B>USER 5
B>STAT             ; still works - found in A0
```

## Differences from CP/M 2.2

| Feature | CP/M 2.2 | QP/M |
|---------|----------|------|
| Date stamps | No | Automatic |
| Program search | Current only | Multi-location |
| Error messages | Cryptic | Clear |
| Z80 optimized | No | Yes |

## Compatibility

### What Works

- All CP/M 2.2 programs
- Standard utilities (PIP, STAT, ED, etc.)
- Most disk formats
- All CP/M 2.2 APIs

### What's Different

- Date stamps use disk space (minimal)
- Some very low-level programs may differ
- Configuration stored on disk

## Tips for Z80CPM

### Best Use Case

QP/M is ideal if you want:
- Date stamps on files
- Simple CP/M 2.2 compatibility
- No learning curve (looks like CP/M)

### Checking File Dates

```
A>QD MYFILE.TXT
```

### Organizing Programs

Put utilities in A0 (user 0 on drive A), then access them from anywhere.

## Troubleshooting

**Dates show wrong**
Check system clock is set:
```
A>DATE
```

**Can't find program**
Check common drive setting with QINSTALL.

**Disk full faster than expected**
Date stamps use minimal extra space - check for large files.

## Further Reading

- [QP/M Information](https://www.microcodeconsulting.com/z80/qpm.htm)
- [MICROCode Legacy Z80 Software](https://www.microcodeconsulting.com/z80/)
