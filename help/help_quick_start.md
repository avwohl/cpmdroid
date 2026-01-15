# CPMDroid Quick Start Guide

Welcome to CPMDroid, a CP/M emulator for Android devices.

## First Launch

On first launch, CPMDroid will automatically download a starter disk image containing CP/M and common utilities. This requires an internet connection.

## Basic Controls

### Toolbar Buttons
- **Play/Pause** - Start or pause the emulator
- **Reboot** - Restart the emulator (warm boot)
- **Settings** - Configure disks, font size, and boot options
- **Help** - Access this help system

### Keyboard
Use the on-screen keyboard to type commands. The emulator accepts standard CP/M commands.

**Special Keys:**
- **Enter** - Execute command
- **Backspace** - Delete character
- **Ctrl** - Hold for control key combinations (e.g., Ctrl+C to break)

## Settings

Access Settings from the toolbar to:

- **Disk Slots** - Assign disk images to slots 0-3
- **Boot String** - Commands sent automatically after boot
- **Font Size** - Adjust terminal text size (8-24pt)
- **Browse Catalog** - Download additional disk images
- **Clear Boot Config** - Reset NVRAM if stuck in autoboot

## Disk Images

CPMDroid supports up to 4 disk images (slots 0-3). Use the Settings screen to:

1. Tap the **+** button to select a disk from the catalog
2. Tap the **X** button to clear a slot
3. Download new disks from the Browse Catalog option

After changing disk settings, use the **Reboot** button to load the new configuration.

## Boot Menu

At startup, the RomWBW boot menu displays available operating systems:

```
Boot [H=Help]:
```

Type a number to select an OS, or press Enter for the default. Common options:
- **0** - Boot from disk in slot 0
- **H** - Display help

## File Transfer

Use the R8 and W8 utilities to transfer files:

- **R8 filename** - Read file from host to CP/M
- **W8 filename** - Write file from CP/M to host

See the File Transfer help topic for details.

## Tips

- The emulator pauses when you switch apps or access Settings
- Use **Clear Boot Config** in Settings if autoboot prevents access to the boot menu
- Disk changes require a reboot to take effect
- Connect a physical keyboard via Bluetooth or USB for easier typing

## Getting Help

- Use the Help menu to access detailed guides for CP/M, ZSDOS, and other operating systems
- Visit the GitHub repository for updates and bug reports
