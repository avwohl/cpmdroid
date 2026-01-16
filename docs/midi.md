# MIDI Support Research Notes

## Current Status

RomWBW does not have native MIDI support built into HBIOS. MIDI on RC2014/Z80 systems is typically handled via external hardware modules.

## RC2014 MIDI Module

A MIDI module is available for RC2014 systems:
- **Source**: https://www.tindie.com/products/shieladixon/midi-module-designed-for-rc2014/
- **Version**: Mk2 includes its own serial chip (more plug-and-play)
- **API**: Simple Z80 assembly/C API for sending/receiving MIDI

### RomWBW Limitation

Interrupts have not yet been made to work with RomWBW, so on that system it's necessary to poll the data port frequently. A tracker application is available that works for 32k machines and CP/M/RomWBW machines.

### RC2014 MIDI Driver Research (January 2026)

**Question**: Does the RC2014 driver have its own clock so we don't need to slow our emulator?

**Findings**:
1. **No dedicated clock for MIDI timing** - The Mk2 module has its own serial chip but no independent timer/clock for MIDI event timing
2. **Timing relies on host system** - Either via:
   - Z80 interrupts (preferred but not working with RomWBW)
   - Polling loops (current RomWBW workaround)
   - External CTC (Z80 Counter/Timer Circuit) card
3. **Accurate playback requires CTC** - Projects note that "accurate timing generation for music playback requires timer interrupts" from a Z80CTC card or Z180 built-in CTC

**Implication for CPMDroid**: We cannot avoid emulator timing considerations by relying on the MIDI module's hardware. MIDI timing would still need to be managed by:
- The emulated CPU cycle count
- A virtual CTC implementation
- Or accepting that real-time MIDI playback may not be perfectly timed

### Implementation Effort Assessment

**What could be shared in hbios_dispatch (platform-independent)**:
- MIDI message parsing and assembly
- Virtual serial port emulation for MIDI I/O ports
- Note/controller/program change buffering
- MIDI file parsing (if we want to support .MID playback)
- Timing queue management (notes scheduled for future playback)

**What would be Android-only**:
- Android MIDI API integration (`android.media.midi`)
- Connection to Android synthesizers or external MIDI devices
- Audio output via `MediaPlayer` or `AudioTrack`
- UI for MIDI device selection

**Estimated work**:
- **Minimal (beep/tone only)**: 2-4 hours - Just wire up `emu_dsky_beep()` to Android's `ToneGenerator`
- **Basic MIDI out**: 1-2 days - Serial port emulation + Android MIDI API for note on/off
- **Full MIDI support**: 1-2 weeks - CTC emulation, proper timing, MIDI file support

## Potential CPMDroid Implementation

To add MIDI support to CPMDroid, possible approaches:

1. **Serial Port Passthrough**: Emulate MIDI module as a serial device, pass MIDI data to Android's MIDI API
2. **Virtual MIDI**: Generate MIDI events from emulated I/O ports and send to Android synthesizer
3. **Sound Chip to MIDI**: Convert AY-3-8910/YM2149 register writes to MIDI note events

## Related: AY-3-8910/YM2149 Sound

RomWBW has native support for the AY-3-8910 PSG sound chip:
- HBIOS drivers for AY-3-8910 and SN76489
- `TUNE.COM` plays PT2, PT3, MYM files
- Configuration: `AY38910ENABLE .SET TRUE`

Some emulators convert PSG output to MIDI for playback on modern systems.

## Current Sound Architecture

CPMDroid already has sound infrastructure:

**Shared (hbios_dispatch.cc)**:
- `handleSND()` - HBIOS sound function dispatcher
- `HBF_SNDNOTE` - Converts MIDI note numbers to periods
- `HBF_SNDPLAY` / `HBF_SNDBEEP` - Calls `emu_dsky_beep()`
- 4-channel volume/period tracking

**Android-specific (emu_io_android.cpp)**:
- `emu_dsky_beep()` - Currently a stub (not implemented)

**UI (TerminalView.kt)**:
- `playBell()` - Terminal BEL character beep via ToneGenerator
- Controlled by user setting (off by default to avoid interrupting background audio)

## References

- RomWBW GitHub: https://github.com/wwarthen/RomWBW
- MAME AY-3-8910 emulation: https://github.com/mamedev/mame/blob/master/src/devices/sound/ay8910.cpp
- RC2014 YM2149 sound card: https://z80kits.com/shop/ym2149-sound-card/
- libayemu (C): https://github.com/asashnov/libayemu
- psg-rs (Rust): https://github.com/thedjinn/psg-rs
- RC2014 MIDI Module: https://www.tindie.com/products/shieladixon/midi-module-designed-for-rc2014/
- Hackaday MIDI Interface: https://hackaday.io/project/198195-midi-interface-for-rc2014
