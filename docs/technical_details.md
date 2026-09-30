# Technical Details

## Architecture

```
+-------------------------------------+
|         Android UI (Kotlin)         |
+-------------------------------------+
|       EmulatorEngine (JNI)          |
+-------------------------------------+
|   AndroidEmulatorDelegate (C++)     |
|  +-----------+-----------------+    |
|  |   qkz80   |  HBIOSDispatch  |    |
|  | (Z80 CPU) |  + banked_mem   |    |
|  +-----------+-----------------+    |
+-------------------------------------+
```

## Dependencies

Sibling checkouts, compiled in place by `CMakeLists.txt`:

- `../cpmemu/src/` - the qkz80 Z80 CPU core
- `../romwbw_emu/src/` - HBIOS dispatch and memory banking

## Terminal Emulation

ANSI/VT100 and VT52:

- Cursor positioning (`ESC[row;colH`), movement (`ESC[A/B/C/D`) and absolute
  column/row (`ESC[G`, `ESC[d`)
- Screen and line clearing (`ESC[2J`, `ESC[K`) and character erase (`ESC[X`)
- Insert and delete characters (`ESC[@`, `ESC[P`) and lines (`ESC[L`, `ESC[M`)
- Scrolling region (`ESC[t;br`) and scroll up/down (`ESC[S`, `ESC[T`)
- Save/restore cursor and rendition (`ESC 7` / `ESC 8`, `ESC[s` / `ESC[u`)
- Colours (CGA 16-colour palette, foreground and background) and per-cell bold,
  underline, blink and reverse
- Private modes: VT52/ANSI (`ESC[?2h/l`), autowrap (`?7`), cursor visibility
  (`?25`)
- Device queries: cursor position (`ESC[6n`), device attributes (`ESC[c`)
- VT52 mode, entered by `ESC[?2l` or auto-detected from any VT52-*exclusive*
  escape (`ESC A B C F G I J K Y`); `ESC H` is VT52 home only once VT52 is
  already in force, because in ANSI that byte is HTS

Four divergences are deliberate, and `todo.txt` lists them under `[DELIBERATE]`
with the reason for each. The one visible in ordinary output: `ESC[0m` resets
the foreground to green rather than light grey, because a green phosphor screen
is this app's identity.

## Disk Format

RomWBW hd1k: 8 MB per slice, 1024 directory entries per slice. The eight slices
are divided among the disks you attach rather than given to each - one disk gets
8, two get 4 each, three or four get 2 each - so adding a disk shortens the
others. The catalog's recommended image is the 51,380,224-byte six-slice combo
rather than a bare 8 MB slice.
