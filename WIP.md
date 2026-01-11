# CPMDroid - Work In Progress

## Current State (2026-01-10)

### Completed
- Android project structure created
- Gradle build configuration (Kotlin DSL)
- NDK/CMake setup referencing shared source from:
  - `../romwbw_emu/src/` - Core emulator (hbios_dispatch, hbios_cpu, emu_init)
  - `../cpmemu/src/` - Z80 CPU (qkz80, qkz80_mem, qkz80_errors)
- JNI bridge (`emu_io_android.cpp`) implementing full `emu_io.h` interface
  - Uses HBIOSCPUDelegate pattern with AndroidEmulatorDelegate class
- Terminal view with VT100 escape sequence parsing
- Basic UI with text input and send/enter buttons
- Toolbar with Start/Stop/Reset/Settings buttons
- Settings screen with:
  - ROM selection
  - 4 disk slots with catalog browser
  - Boot string configuration
  - Font size (8-24)
- Disk catalog system:
  - Fetches disks.xml from GitHub releases
  - Downloads disks to external files dir with progress
  - SHA256 checksum verification
- First-launch auto-download of default combo disk (hd1k_combo.img)
- ROM file: emu_avw.rom in assets
- **Build succeeds** for all architectures (arm64-v8a, armeabi-v7a, x86, x86_64)

### Ready for Testing
- Install APK on device/emulator
- First launch should auto-download combo disk (~50MB)
- Open Settings to browse disk catalog and assign disks to slots
- Test Start/Stop/Reset buttons
- Test terminal input/output
- Test font size changes

### TODO
- [ ] Test on actual device/emulator
- [ ] Add physical keyboard support
- [ ] Add landscape orientation support
- [ ] Performance optimization if needed
- [ ] Disk save/writeback to storage

### Known Issues
- No adaptive icons (using simple vector drawable)
- Auxiliary devices (printer, punch, reader) are stubs
- Dazzler display is stubbed out (not used)

### Dependencies
This project requires the following sibling directories:
```
../romwbw_emu/src/   - hbios_dispatch.cc, hbios_cpu.cc, emu_init.cc, headers
../cpmemu/src/       - qkz80.cc, qkz80_reg_set.cc, qkz80_mem.cc, qkz80_errors.cc, headers
```

### Build Instructions
1. Set JAVA_HOME to Android Studio's JDK (e.g., `C:\Program Files\Android\openjdk\jdk-21.0.8`)
2. Run `gradlew.bat assembleDebug`
3. APK output: `app/build/outputs/apk/debug/app-debug.apk`

Or open in Android Studio and build from there.

### Disk Catalog URL
https://github.com/avwohl/ioscpm/releases/latest/download/disks.xml

### Storage Locations
- Disks: `/storage/emulated/0/Android/data/com.romwbw.cpmdroid/files/Disks/`
- Settings: SharedPreferences
