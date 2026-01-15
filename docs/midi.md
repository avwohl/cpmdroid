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

## References

- RomWBW GitHub: https://github.com/wwarthen/RomWBW
- MAME AY-3-8910 emulation: https://github.com/mamedev/mame/blob/master/src/devices/sound/ay8910.cpp
- RC2014 YM2149 sound card: https://z80kits.com/shop/ym2149-sound-card/
- libayemu (C): https://github.com/asashnov/libayemu
- psg-rs (Rust): https://github.com/thedjinn/psg-rs
