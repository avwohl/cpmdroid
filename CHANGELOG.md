# Changelog

## Version 1.17 (versionCode 18)

- Synced with emulator core v1.34: disk write failures surface as HBIOS I/O errors instead of silent data loss, disk offsets are computed in 64-bit, and out-of-bounds guest writes no longer grow images (W8 exports are handed to the UI asynchronously on Android, so an export write failure is reported with a toast)
- Bumped target to Android 16 (API 36)
- Pinned disk catalog to ioscpm release v1.4.5
- Broken w8.com (UPPERCASE export names) is auto-patched at disk load
- Fixed Reboot reverting disks to a stale snapshot
- Clearing a disk slot now actually unmounts it
- Disk saves are atomic and serialized with emulation (no more torn images)
- R8 filename matching is case-insensitive; R8/W8 filenames are sanitized
- Periodic saves are time-based
- Help and README corrections (first hard disk is boot unit 2)

## Version 1.16 (versionCode 17)

- Code cleanup: shared HTTP client, deduplicated disk-loading logic
- Fixed a reboot race condition

## Version 1.15 (versionCode 16)

- Idle power saving to reduce battery drain when waiting for input

## Version 1.2 (versionCode 3)

- Renamed package from `com.romwbw.cpmdroid` to `com.awohl.cpmdroid`
- Fixed layout issues with navigation bar on phones and tablets
- Added `fitsSystemWindows` support for proper system bar handling

### Tested on:
- Samsung physical tablet
- Pixel Phone emulator
- Pixel Tablet emulator

## Version 1.1 (versionCode 2)

- Added `recalculateSize()` method for forced layout recalculation
- Fixed terminal layout after reboot button press
- Fixed terminal layout after returning from help activity
- Bumped target API to 35

## Version 1.0 (versionCode 1)

- Initial release
