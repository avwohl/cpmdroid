# CPMDroid - Work In Progress

## Current State (2026-01-10)

### Completed
- Android project structure created
- Gradle build configuration (Kotlin DSL)
- NDK/CMake setup referencing shared source from:
  - `../romwbw_emu/src/` - Core emulator (hbios_dispatch, hbios_cpu, emu_init)
  - `../cpmemu/src/` - Z80 CPU (qkz80)
- JNI bridge (`emu_io_android.cpp`) implementing full `emu_io.h` interface
- Terminal view with VT100 escape sequence parsing
- Basic UI with text input and send/enter buttons
- GitHub repository: https://github.com/avwohl/cpmdroid

### Not Yet Tested
- Actual build with Android Studio (need to verify CMake paths work)
- ROM loading from assets
- Emulator execution loop
- Terminal rendering performance

### TODO
- [ ] Test build in Android Studio
- [ ] Add ROM file to assets (romwbw.rom)
- [ ] Add disk images to assets
- [ ] Test on actual device/emulator
- [ ] Add file picker for loading custom ROMs/disks
- [ ] Add settings screen (font size, colors)
- [ ] Add physical keyboard support
- [ ] Add landscape orientation support
- [ ] Performance optimization if needed

### Known Issues
- Gradle wrapper JAR not included (Android Studio will download)
- No adaptive icons (using simple vector drawable)
- Auxiliary devices (printer, punch, reader) are stubs

### Dependencies
This project requires the following sibling directories:
```
../romwbw_emu/src/   - hbios_dispatch.cc, hbios_cpu.cc, emu_init.cc, headers
../cpmemu/src/       - qkz80.cc, qkz80_reg_set.cc, headers
```

### Build Instructions
1. Open project in Android Studio
2. Place `romwbw.rom` in `app/src/main/assets/`
3. (Optional) Place disk images as `disk0.img`, `disk1.img`, etc.
4. Sync Gradle
5. Build and run on device/emulator (API 24+)
