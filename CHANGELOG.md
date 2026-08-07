# Changelog

## Version 1.19 (versionCode 20)

- Synced with emulator core **v1.35**, which pins the RomWBW release it
  emulates (v3.5.1) in `src/romwbw_pin.h` and now refuses a ROM built for a
  different release, or one whose HBIOS configuration block is corrupt,
  instead of starting a CPU that produces no output at all. This port
  compiles the core in place from `../romwbw_emu/src`, so it builds with no
  CMake change.
- **Refreshed the bundled `emu_avw.rom`.** The shipped copy predated the
  upstream rebuild; the ROM is now the one that reproduces from
  `src/emu_hbios.asm`. Verified with `romwbw_emu/roms/verify_romwbw_pin.sh`.
- A rejected ROM now logs *why* (corrupt HCB, or built for a different
  RomWBW release), rather than a bare "Failed to load ROM". The user-facing
  toast is unchanged.
- Reboot no longer assumes the cached ROM reloads: a rejection is logged
  instead of leaving a running CPU with no ROM behind it.
- Not affected by the v1.35 shared file-I/O hardening: this port's
  `emu_file_*` and `emu_disk_*` are deliberate stubs (Android does file I/O
  through JNI and keeps disks in memory), so there was nothing to harden.

**Not built or published.** No Android SDK, NDK or JDK was available when
these changes were made, and the repo ships only `gradlew.bat`. The C++ was
reviewed and the new expressions were type-checked against the real core
headers with clang, but neither Gradle nor the NDK has compiled them. Build
before releasing.

## Version 1.18 (versionCode 19)

- Version bump for a fresh Google Play submission. Already targets Android 16 (API 36), which meets Play's Aug 31 2026 target-API requirement (min is API 35).
- The pinned ioscpm `v1.4.5` disk catalog is now published upstream, so the downloadable disk list loads (that release was missing when 1.17 was built, and the catalog fetch returned HTTP 404). No app code changes since 1.17.

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
