# WIP — Terminal keyboard fix + scrollback (Android)

**Status as of 2026-07-25:** implemented, built, installed on the test tablet, **awaiting
visual confirmation + possible tuning.**

**Correction, 2026-08-26:** the rest of this file was written before the checkpoint
commit and still said the work was uncommitted. It is committed — `690da30`
(*WIP: Android terminal keyboard-aware scrolling + scrollback*, 2026-07-25), on
`master` and on `origin/master`, and every later commit builds on it. Nothing here
is at risk of being lost, and there is no working tree to restore before resuming.
What is still true is the part that needs a person: **no one has watched it run.**
It has also never gone into a published build, so a clean install from the store
still shows the black band and still drops lines. See `todo.txt` for the current
statement of that item.

## Baseline
- Written against HEAD = `5ae1bdd` (*Bump to 1.18 / versionCode 19*).
- The changes below landed as `690da30` on top of that:
  - `app/src/main/java/com/awohl/cpmdroid/TerminalView.kt` (~152 lines)
  - `app/src/main/java/com/awohl/cpmdroid/MainActivity.kt` (~14 lines)
- The tree has moved on since: the version is 1.19 / versionCode 20 as of
  `d22308f`, and `a523d40` and `9b68ab1` have both touched `TerminalView.kt`
  again. Diff against the current file, not against `5ae1bdd`.

## What was done (three linked fixes)

1. **Soft keyboard covered the CP/M prompt** — CONFIRMED FIXED by the user.
   - `MainActivity`: the keyboard-hidden branch (Method 2, the third-party-keyboard
     fallback) now **restores the root padding to the system bars + recalcs** — before,
     it only reset its tracking var, leaving a blank strip / not restoring the view.
   - `TerminalView.onDraw`: when the view is too short for all rows (keyboard up), it
     **scrolls within the live screen to keep the cursor visible** instead of drawing 24
     rows off the bottom behind the keyboard. Font is NOT shrunk (readable on phones).
   - Note: the user's tablet uses a **third-party IME (Smart Keyboard Pro)**, but it *does*
     report IME insets correctly here (ime.bottom=636), so detection was never the issue.

2. **Huge black area below the content (esp. portrait)** — root cause: the old
   "dynamic rows fill the screen" made the live grid ~55 rows in portrait, but a fresh CP/M
   boot only prints ~15 lines → rows 16–55 render black. Fix = fixed live screen (below).

3. **Scrollback was missing on Android** (Windows/z80cpmw has it: `scrollbackLines`
   default 1000, mouse-wheel / Shift+PageUp-Down). Now implemented on Android.

### Design chosen by the user (of 3 options offered)
**"Fixed screen, prompt at bottom + scrollback."** Implemented in `TerminalView.kt`:
- **Live screen fixed at `MIN_ROWS` (24)** — standard CP/M size (was dynamic). `calculateFontSize`
  now sets `newRows = MIN_ROWS` and adds a **height cap** so all 24 rows fit the FULL
  (keyboard-hidden) height, tracked via new `fullHeight` (reset on rotation, kept when the
  keyboard shrinks the view — so the keyboard case scrolls, not shrinks).
- **Scrollback history** (`historyChars` / `historyColors` ArrayDeques, capped at
  `scrollbackLines`=1000). `scrollUp()` pushes the top line into history before scrolling.
- **Rendering** (two modes in `onDraw`):
  - viewport ≥ 24 rows: **live screen anchored at the bottom**, history fills above; user
    drag scrolls into history (`userScrollUp`, clamped).
  - viewport < 24 rows (keyboard up): scroll within the live screen to the cursor.
- **Drag gesture** in `onTouchEvent`: drag DOWN = scroll back into history, drag UP = toward
  live; a tap (no drag) still shows the keyboard. New output snaps to live (`processOutput`
  resets `userScrollUp=0`).

## Installed / test state
- Release APK built + installed on **Samsung SM-X200 (Galaxy Tab A8, Android 14)**,
  versionCode 19 / versionName 1.18, signed with the release keystore.
- App verified running with **no crash**; `calculateFontSize` healthy (rows=24, normal font).
- **NOT yet visually confirmed by the user** for the scrollback/black-area/portrait changes
  (the tablet went to sleep/locked before the visual check).

## TO DO on resume
1. **User visually verifies on the tablet** (unlock first):
   - Fixed 24-row screen looks right in portrait *and* landscape (no giant black void).
   - Generate output (boot + `DIR`, or `D`/`L` at boot menu) so lines scroll off.
   - **Drag down** pages back into history; **drag up** / new output snaps to live.
   - Keyboard-up keeps the prompt visible (already confirmed pre-scrollback).
2. Likely tuning knobs: scroll **direction** feel, how many lines per drag, fresh-boot blank
   space above the prompt (empty history), whether to also let drag scroll while keyboard up.
3. ~~**Consider a Settings entry** for `scrollbackLines`~~ — **done.** A seek bar in
   Settings steps through `SettingsRepository.SCROLLBACK_CHOICES` (0 = off), and the
   value reaches `TerminalView` live. Add to the list above: lowering it while history
   is on screen must not leave the view scrolled past the end. The setter clamps
   `userScrollUp`, but only a device shows whether it feels right.
4. ~~**Copy button** copies only the live screen~~ — **done.** `copyScreenToClipboard()`
   prepends `historyChars`; with scrollback off it is the live screen alone, as before.
5. ~~**commit** the two files~~ — **done** (`690da30`). The `CHANGELOG.md` half is not:
   the 1.18 entry still says "No app code changes since 1.17". Published 1.18 came from
   `5ae1bdd`, before this commit, so that sentence describes the shipped APK correctly
   and only the source tree briefly carried more under that version number. Which of the
   two the entry is meant to describe is the owner's call, and `todo.txt` ties it to the
   visual confirmation.

## Build / install / inspect commands
```bash
# build (PowerShell drives gradlew reliably from Git Bash; cmd.exe /c did NOT inherit cwd)
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command \
  "Set-Location 'C:\temp\src\cpmdroid'; & '.\gradlew.bat' assembleRelease --console=plain; exit \$LASTEXITCODE"

ADB="C:/Users/amwoh/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" install -r app/build/outputs/apk/release/app-release.apk
"$ADB" shell am start -n com.awohl.cpmdroid/.MainActivity
# screenshots came back black only because the screen was DOZING — wake first:
"$ADB" shell input keyevent KEYCODE_WAKEUP   # (then unlock; device has a PIN)
"$ADB" exec-out screencap -p > shot.png
# live logs: TAG "TerminalView" logs onSizeChanged / calculateFontSize; "MainActivity" logs Insets/keyboard
"$ADB" logcat -d | grep -E 'TerminalView|MainActivity: (Insets|Keyboard)'
```

## Cross-repo context (already DONE this session, not WIP)
- **z80cpmw**: Settings-window singleton fix + 1.0.18 built/signed (beta MSIX + unsigned Store
  MSIX, uploaded to Partner Center); CHANGELOG done; committed + pushed (`69ec835`).
- **ioscpm**: published the missing **`v1.4.5`** prerelease (mirror of v1.4.11, v3.5.1 disks,
  w8-fixed combo) — this fixed the disk-catalog **404 on BOTH z80cpmw and cpmdroid** (both
  pin `v1.4.5`); added `docs/DISK_CATALOG_PINNING.md` (pin the iOS app too). Pushed.
