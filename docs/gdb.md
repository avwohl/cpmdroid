# Debug a program with GDB or udb80

CPMDroid has a GDB stub. GDB and udb80 can debug the program that runs in the
emulated machine. The debugger talks the GDB remote serial protocol over a TCP
socket. The stub is the same code as in romwbw_emu. The source is in the cpmemu
and romwbw_emu repositories, and CPMDroid compiles the source in place.

The stub is off by default.

## What you need

- A computer with GDB, or with udb80.
- GDB 18.1 or newer with the Z80 target. The Homebrew `gdb` formula has the
  Z80 target.
- For udb80: `pip install "um80>=0.4.2"`. udb80 needs no GDB.
- For `adb forward`: the Android platform tools, and a USB cable or wireless
  debugging.

## Turn the stub on

1. Open Settings.
2. Tick **Enable GDB debug stub**.
3. Type a port in the port field. The default is 1234.
4. Go back to the terminal and press the Boot button.

The setting takes effect at the next boot. A change of a disk slot in
Settings ends the debugger session. A new session starts at once. A change of
the ROM does the same. When the stub is on, the machine
does not run at boot. The machine waits for a debugger. The status bar shows
`GDB: connect to ADDRESS:PORT`. A message on the screen shows the commands to
type on the computer.

To stop the wait, untick the setting and press Boot.

## Connect from a computer on a USB cable

The stub listens on 127.0.0.1 on the device. Only programs on the device can
reach that address. Use `adb forward` to reach the stub from the computer:

```
adb forward tcp:1234 tcp:1234
gdb
(gdb) set architecture z80
(gdb) target remote :1234
```

For udb80, use the `.dbg` file that `ul80 --debug-info` wrote:

```
adb forward tcp:1234 tcp:1234
udb80 PROG.dbg --connect 127.0.0.1:1234 --wait-program
```

If you chose a different port, use that port in both places. This way is the
safer one. No other computer on the network can reach the stub.

## Connect over the network

1. In Settings, tick **Allow connections from the network**.
2. Press the Boot button.
3. Read the address in the status bar, for example
   `GDB: connect to 192.168.1.20:1234`.
4. On the computer, type `target remote 192.168.1.20:1234`.
   For udb80, type `udb80 PROG.dbg --connect 192.168.1.20:1234 --wait-program`.

The phone and the computer must be on the same network. The stub then listens
on every network interface of the device. The status bar shows the Wi-Fi
address when the device has one. When the device has no Wi-Fi address, the
status bar shows the address of another interface, for example the mobile data
interface or a hotspot. The computer can not always reach that address. Check
the address in the Wi-Fi settings of the phone. When no address is found, the
status bar shows `(phone address)`.

**WARNING:** The stub has no password. Anyone who can reach the port can read
and write the memory of the emulated machine and control the machine. Use the
network switch only on a network that you trust, and only while you debug.
Untick the setting when you finish.

## Find a CP/M program

CP/M loads a program at 0100H in RAM bank 8EH. The debugger cannot start the
program, and the program is not in memory at connect time. Give the debugger
the length and the SHA-256 of the program, or the first bytes. The stub then
stops when the program starts.

With udb80, use `--wait-program`. udb80 reads the length and the SHA-256 of
the program from the `.dbg` file. Then start the program from the CP/M prompt
in the terminal on the device.

With GDB, type this before `continue`:

```
(gdb) monitor wait-program sha256 LENGTH HEX64
(gdb) continue
```

Then start the program at the CP/M prompt. GDB stops with `Program received signal SIGTRAP` at the first line of the
program, at 0100H in bank 8EH. LENGTH and HEX64 are `image.length` and
`image.sha256` of `PROG.dbg`.

This needs a build that compiled the cpmemu stub
of 2026-11-05 or later (cpmemu `365dfc2`). With an older build, GDB continues
past the stop with no message. Then use `monitor break-bank 8E`,
`break *0x100` and `continue`, which stop at the first program that CP/M
starts at 0100H. The romwbw_emu document
[GDB.md, Find the program with GDB](https://github.com/avwohl/romwbw_emu/blob/main/docs/GDB.md#find-the-program-with-gdb) gives the reasons and the
measurements.

## What happens while the debugger holds the machine

The stub runs on a thread of its own. The thread runs the CPU one instruction
at a time. The normal run loop of the app does not run the CPU in this time.
The terminal still shows the output of the program. When the program waits for
a key, you can type on the device as usual.

The Pause button does not stop the CPU while a debugger holds the machine. Only
the debugger stops the CPU then.

When the debugger detaches or goes away, the stub thread ends. The status bar
then shows `Running` again. The run loop runs the machine again. The `k` command of the debugger has the same
effect as a detach.

## Limits

- The calls from the app wait for the stub thread to release the CPU for one
  instruction. A disk save is one such call.
- One debugger for each boot. After a detach, press Boot to debug again.
- The machine waits at boot as long as the stub is on and no debugger comes.
- A single step does not move the program while the program waits for a key.
  Type a key first.
- No watchpoints. The stub answers `Z2` to `Z4` with an empty reply.
- The stub works only while the app runs. If Android stops the app, the
  debugger loses the connection. Keep the app in front.
- The debugger sees the 64 KB that the CPU sees. Use `monitor bank XX` to view
  another bank in the low 32 KB.

## Test status

The Android NDK built the native code for four ABIs. Nobody ran the result on
a device. A host build of the same C++ with a fake JNI
interface was run on macOS. GDB 18.1 with the Z80 target connected to the host
build, stepped, set a breakpoint and detached. The host test covers the wait,
the connect, the steps, the breakpoint, `^C`, the detach and the restart at
reboot. See `MANUAL_CHECKS.md` for the checks that need a device.

## More documents

- [gdb_stub.md in cpmemu](https://github.com/avwohl/cpmemu/blob/main/docs/gdb_stub.md):
  the protocol, the commands and the shadow call stack.
- [gdb.md in um80_and_friends](https://github.com/avwohl/um80_and_friends/blob/main/docs/gdb.md):
  source-level debugging with GDB.
- [udb80.md in um80_and_friends](https://github.com/avwohl/um80_and_friends/blob/main/docs/udb80.md):
  the udb80 debugger.
