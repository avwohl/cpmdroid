# Changelog

## Version 1.29 (versionCode 31)

No ROM in this repository. `app/src/main/assets/emu_avw.rom` is deleted, nothing
in the tree ships an image of any kind, and every ROM comes from the
`romwbw_disks` interface-v0 catalog for the release that is selected. The user
picks which ROM, and a machine that has never been told otherwise follows the
release the catalog itself marks current.

**BUILT AND RUN ON A DEVICE - THE FIRST TIME IN THIS REPOSITORY.** Every entry
above this one says NOT BUILT, NOT COMPILED or NOT RUN. This one was built with
the real toolchain (AGP/Gradle 8.13, JDK 21, NDK 28, all four ABIs), installed on
an Android 16 emulator, and driven by hand. That is why the list of defects below
is longer than the change: three of them could not have been found by reading,
and one of them shipped in the sibling port and was caught by a person installing
it rather than by anything automated.

    ./gradlew :app:test          # 56 tests, 0 failures  (was 52)
    ./gradlew :app:assembleDebug # 12.8 MB APK, 512 KB smaller than 1.28

### The release now follows the catalog

`selectRomwbwVersion()` already fell back to the index entry flagged
`default: true`. It had never once fired. `selectedRomwbwVersion()` answered a
compile-time constant - `V0_BUNDLED_ROMWBW = "3.5.1"`, the release the bundled
ROM declared - so the "preferred" release was always 3.5.1, 3.5.1 was always on
offer, and the index's own default was unreachable code. A fresh install
downloaded the older release for as long as that constant stood, which is the
cost `romwbw_disks` exists to remove, still being paid one level below where
anybody was looking.

`selected_romwbw.v0` is now one key and it IS the preference, exactly as
z80cpmw's `romwbwVersion` is. Unset means nothing has settled on a release and
the index's own default wins; set means use that one while the index still offers
it. `CatalogLoader` writes the resolved answer there, so:

- a fresh install, and an install upgrading from a build whose release was
  implied by the ROM in its own APK, both land on whatever the catalog currently
  marks current - which is the move this release exists to make;
- after that the machine stays where it is. A RomWBW release published later does
  not shift it.

That last point is deliberate rather than a shortfall. Moving a machine between
RomWBW releases changes its disk set and its NVRAM namespace and costs a fresh
~49 MB download; doing it once to escape a bundled ROM is worth it, doing it
silently on every future publication is not. New ROMs and new disks *within* the
selected release still arrive with no app release, because the catalog is re-read
on every fetch - and that is the part `romwbw_disks` exists for.

There is deliberately no pin flag. A draft of this release had one: a second
preference recording whether a human had chosen the release, so that the app
could keep following the catalog forever otherwise. It shipped in no build,
because it was wrong twice over. It was a bit that only the Settings dialog could
set and that **nothing could clear** - `clearRomwbwPin()` was written and never
called, so one visit to the release picker would have taken a device off the
catalog until its data was wiped. And it bought nothing the single key does not:
z80cpmw has run this arrangement with one string and no flag throughout.

`V0_BUNDLED_ROMWBW` is renamed `V0_LEGACY_ROMWBW` and still reads "3.5.1". It is
a historical fact and nothing else: the release every pre-v0 disk name was
renamed to, and the one the per-release preference keys were seeded under. It can
never move without stranding what is on disk under `.v0.3.5.1`. The rename is the
point - under the old name the next reader would have "fixed" it to 3.6.0.

### The user picks the ROM

Settings had a read-only `Bundled ROM: emu_avw.rom` label. `emu_rcz80` has been
published since the migration and was unreachable in every build ever shipped.

There is now a **ROM** row with a Change button listing the selected release's
`roms[]`, marked with which is the catalog's default and which is on the device.
What is stored is the catalog **ID**, never the filename shown beside it - the
two are the same type and look alike, and seeding a filename into the field that
holds an ID is exactly the bug that shipped in the sibling port and corrupted the
preference on OK.

Picking one fetches it there and then, with the progress bar a disk download
gets, and puts the previous choice back if it cannot be got. Recording the pick
and stopping would have sent the user back to a machine still running the old ROM
until it was restarted, and then to a dialog asking permission for the download
their choice already implied.

### Four defects that only running it could find

**The wrong release was named on a fresh install.** `startMachine()` runs from
`onCreate` before any index has been fetched, where `selectedRomwbwVersion()`
still answers the legacy anchor. The first launch therefore reported *"RomWBW
3.5.1 has no ROM on this device yet"* and offered to download 3.5.1's ROM, on a
device the catalog would have put on 3.6.0. `hasResolvedRelease()` now gates it:
an install that has never resolved a release against a published index resolves
one first, then fetches. Nothing is asked before downloading - a first launch
already fetches a 49 MB starter disk unprompted, so stopping to ask for 512 KB
would present the ordinary cost of setting the app up as though it were a fault.

**Reboot, with no ROM, reset the core and started nothing.** It confirmed
("Restart Emulator"), destroyed and recreated the emulator state, cleared the
screen and the `ROM needed` status with it, and said nothing - so the one thing
telling the user what to do next disappeared. It routes to `startMachine()` now,
which is what the play button does from the same state.

**A ROM change was invisible to a running machine.** `onResume` compared the
selected *release* and the disk slots, which was enough when the ROM was a
property of the release. It compares the ROM pick too now: choosing `emu_rcz80`
for the release already selected changes the bytes the CPU executes without
changing the release, and without this the machine kept running `emu_avw` while
Settings said otherwise.

**"Has no ROM" was said when a ROM was present.** A device holding `emu_avw` with
`emu_rcz80` selected was told the release had no ROM at all - false on its face,
since Settings names the file. `RomFailure.PickedRomNotFetched` says which ROM is
missing.

### The ROM is no longer frozen at first fetch

The offline fast path - "a verified file is already here, use it" - answered
before the catalog was ever opened, so a ROM pick could be stored and then
ignored on every launch. `RomClaim` carries the catalog ID the bytes were fetched
for, and the fast path is taken only when that is the ID that is wanted. A claim
written by an older build carries no ID and is accepted while nothing has been
picked, which costs one fetch after upgrading and nothing afterwards.

### The cost, named rather than discovered

**A first launch with no network cannot start.** There is no ROM in the package
and a ROM cannot be verified without the catalog that publishes its size and
hash. It is reported, not left as an app that appears to do nothing: a dialog
titled *"CPMDroid needs to set up once"* names the failure, says what the first
start downloads and that later starts are offline, and offers Try again. The
status strip reads `Setup needed`.

Start, Reboot and Settings all stay enabled in that state. This is checked rather
than assumed, because the sibling port shipped the opposite in its 1.0.26-beta -
Start greyed out on an install with no ROM, which is a deadlock, since Start is
what fetches the ROM - and its scripted driver bypassed the menu state entirely,
so only a person installing it noticed. `MANUAL_CHECKS.md` now carries that rule
and the two checks.

### What was verified on the device

- Fresh install, online: resolved to RomWBW 3.6.0 (`default: true` in the index,
  where 3.5.1 is `default: false`), fetched `emu_avw-v0-3.6.0.rom`, fetched the
  24-disk 3.6.0 catalog and `hd1k_combo-v0-3.6.0.img`, and booted on the 3.6.0
  boot loader.
- ROM picker: lists EMU AVW (default - downloaded) and EMU RCZ80. Selecting RCZ80
  wrote `rom_id.v0.3.6.0=emu_rcz80` - the ID - fetched `emu_rcz80-v0-3.6.0.rom`,
  and booted to `RCBus [RCZ80_std] Boot Loader` instead of
  `RetroBrew SBC [SBC_simh_std]`. That is the first time either of the two
  published ROMs other than the default has run in this app.
- Fresh install, airplane mode: the setup dialog above, `Setup needed`, and
  Start/Reboot/Settings all reported `enabled="true"` by `uiautomator`.
- Deleting the fetched ROM and restarting: `RomWBW 3.6.0 needs its ROM,
  emu_avw-v0-3.6.0.rom, which is not on the device`, with Download ROM.
- The atomic download is real: the 49 MB image is written as `.tmp` and renamed.

### The tests could not have caught the drift they were written to catch

Nothing in the suite read a file outside this repository, so no change to
`romwbw_disks` could ever turn it red - which is how six assertions came to
describe a generation-1 document. `theFixturesStillMatchThePublishedDocuments`
compares `src/test/resources` byte for byte against the sibling checkout and says
where they first differ. It skips, loudly and by name, when there is no sibling
(CI, or a lone clone), and an explicit `-Dromwbw.disks.dir` that names nothing
fails rather than skipping. Proved by perturbing one SHA-256 in a fixture and
watching it go red.

The three fixtures are refreshed to the published documents - 3,310 / 11,826 /
15,062 bytes, the last two being the sizes the index itself states.

### Tooling and documentation

- `tools/check-shipped-disks.sh`: the bundled-ROM check is gone. It asserted that
  every port ships a ROM in its own repository, which is now false for this repo
  and for `z80cpmw`, so it failed ports that were correct. Its header claimed the
  file was "identical in five repositories"; measured, it is identical in three,
  diverged in `z80cpmw`, and absent from `ioscpm` and `romwbw_disks`.
- `.gitignore` now refuses `*.rom` and `*.img`. The deleted asset hashed to
  `4b11402a...`, exactly what the catalog publishes for `emu_avw-v0-3.5.1.rom` -
  a byte-identical second copy that cost 512 KB in every clone and every APK to
  be right by luck, with nothing in the tree able to say which day it stopped
  being right.
- README, PRIVACY_POLICY, the in-app help and the Play description no longer say
  disks come from the `ioscpm` release area pinned at `v1.4.5`. That host and that
  pin were both wrong, and the privacy policy is the document that tells a user
  what leaves their device.

### Still open

`ioscpm` has NOT migrated: `EmulatorViewModel.swift` still pins
`releaseTag = "v1.4.12"` against the `ioscpm` release area, and
`iOSCPM/Resources/emu_avw.rom` is still tracked. It is the last of the four ports
on the old arrangement.

The release AAB in `app/build/outputs/` predates this change and must be rebuilt
before anything is uploaded.

## Version 1.28 (versionCode 30)

The ROM comes from the catalog. It is the last version-coupled thing in this
app, and closing it is why `romwbw_disks` exists: until now a new RomWBW release
could not reach a user without a store release of this app, however good the
catalog was.

**NOT BUILT AS AN APK, AND NOT RUN ON A DEVICE.** Same machine, same absence:
no Android SDK, no NDK, no JDK that Kotlin 1.9.20 will start under. Nothing here
has been through Gradle, aapt2, R8, dex or the NDK. What it has been through is
1.27's recipe, rebuilt from scratch because nothing of it survived on this box:

    # 1. every Kotlin source, type-checked against real classes
    kotlinc 2.2.0 (on the JDK 25 that is here) -cp <30 jars>
      org.robolectric:android-all 16-robolectric-13921718   # android.*
      androidx appcompat 1.7.0, recyclerview 1.3.2, activity 1.9.3,
        lifecycle 2.8.7 (the -android artifacts), core/core-ktx 1.15.0,
        material 1.12.0, fragment, savedstate, collection and their transitives
      com.squareup.okhttp3:okhttp 4.12.0, okio 3.6.0,
        kotlinx-coroutines-core-jvm 1.7.3, org.json 20231013
    # -> 22 app sources plus 2 stubs, exit 0. With warnings on, two, and
    #    neither is new: the ProgressDialog deprecation SettingsActivity has
    #    carried since 1.22, and displayMetrics.scaledDensity in TerminalView,
    #    a file this release does not touch.

    # 2. all four JVM test suites, run for real
    java -cp <out>:json:junit:hamcrest:app/src/test/resources \
      org.junit.runner.JUnitCore ...CatalogParsingTest ...RomSelectionTest \
      ...V0MigrationTest ...JniNameParityTest
    # -> OK (24), OK (11), OK (14), OK (3). 52 tests, all passing.

`R`, `BuildConfig` and `ActivitySettingsBinding` are AGP-generated and are
hand-written stubs again, the `R` one built by scanning every `R.*` reference in
the tree. The compiler is 2.2.0 rather than the project's 1.9.20, so a 1.9-only
rejection would not show up here. None of it reaches a device, and the things
most likely to be wrong still need one: the JNI binding, the dialogs, and
everything about storage. `MANUAL_CHECKS.md` section 8 is where those go.

versionCode moves 29 -> 30 and versionName 1.27 -> 1.28. 1.27 has been uploaded
to nobody and could have absorbed this, but its entry below says in as many
words that no ROM is downloaded and that another release boots with a mismatch
warning - folding this in would leave that description attached to a build that
does the opposite, and the manual checks are filed per release. A versionCode is
free; a record that says the wrong thing is not.

### The ROM is a catalog asset, and the bundled one is the fallback

`roms[]` was parsed by nothing. It is read now, the same way `disks[]` is: keyed
on `id`, with the entry flagged `default` chosen and the first entry taken when
none is - never by array position, never by looking for `emu_avw`, and an absent
or empty array meaning "this release publishes no ROM" rather than "this
document is broken". All three are what CATALOG_SCHEMA 6.1 asks for, and all
three are exercised in `RomSelectionTest`.

The ROM lands in the same flat directory as the disks under its catalog
filename - `emu_avw-v0-3.6.0.rom` - so two releases' ROMs coexist exactly as
their disks do. `getDownloadedDisks()` filters on `.img`, so it does not appear
among them and the catalog dialog's ticks are unchanged.

**The bundled `assets/emu_avw.rom` stays, and stays first.** A fresh install
selects the release that ROM declares, takes the bundled branch, and boots with
no network and no index fetch at all. Which release it declares is read from the
image with `emu_romwbw_release_of_image()` rather than from a constant, so
replacing the asset moves the decision with it.

### The ROM lands before the emulator starts, or nothing starts

This is where a ROM differs from a disk. A missing disk is an empty drive; a ROM
from another release is a guest that prints
`*** WARNING: HBIOS/CBIOS Version Mismatch ***` and then misbehaves, which is
the pairing this whole exercise removes. So:

- Starting on release X requires X's ROM present and verified. There is no path
  that starts on the bundled ROM because X's was awkward to get. Falling back
  would recreate the exact mismatch, invisibly.
- When it is not there, a dialog names the release, the file and the reason and
  offers the two honest choices: **Download ROM**, or **Use RomWBW &lt;bundled&gt;**,
  which switches back to the release the package can boot and keeps everything
  belonging to the other one. The second button is absent when the bundled
  asset's own HBIOS block cannot be read, because then it has nowhere to send
  anyone.
- Settings fetches a release's ROM **before** it switches to it, not after. The
  switch happens in the download's success arm and nowhere else, so a failed
  fetch leaves the app pointed at a release that still has a ROM behind it. The
  old confirmation - "disks for this release can be downloaded, but booting them
  prints a version mismatch warning" - describes a state the app can no longer
  be left in, and is gone.
- Both fetches report progress the way a disk download does. Half a megabyte is
  quick, and on a bad connection it is the one thing between the user and a
  machine that boots.
- Changing release in Settings now reloads the ROM as well as the disks.
  `onResume` compared only the four slots, which would have left 3.6.0 disks
  mounted under the ROM 3.5.1 started with; the reload goes through the same
  reset the Reboot button uses.
- The play button asks again when there is no ROM. `startEmulation()` refuses
  when nothing is loaded, so with the dialog dismissed it would have done
  nothing at all and looked broken; it now re-runs the resolution, which either
  starts the machine or brings back the dialog that says what is missing.

### Verified before it is used, every time, and not only after a download

`size` and `sha256` are checked on every load, from the bytes that are handed to
the emulator rather than from a second pass over the file - so what was hashed
is what is loaded, and a file swapped between the two cannot slip through. A ROM
is 512 KB; the check costs milliseconds and it is the one file whose corruption
produces a guest that boots to nothing at all.

For that to work offline, the catalog's `filename`, `size` and `sha256` are
recorded per release when a ROM is fetched, and written only after the bytes
have verified. Without them, "verify every time" would quietly mean "verify when
we happen to be online", which is the same as not verifying: offline is exactly
when a corrupt ROM is least recoverable.

Switching back to a release whose ROM was fetched months ago needs no network:
the recorded claim is checked against the file before the index is touched, so
the fast path is a verification rather than a way round one.

A file that fails is re-downloaded **once**. Twice is not a flaky connection,
and a client that spends 512 KB on every launch to fail the same way is worse
than one that says what is wrong. Nothing is deleted on the way: the fetch
renames a fully verified transfer over the bad copy, so a failed attempt leaves
what was there. `emu_validate_rom_hcb` is still the last line of defence and is
untouched - hashing the file does not make it redundant, and a ROM it refuses is
still refused with the core's own message.

The catalog's `hcb.version` / `hcb.update` are compared against the index's
`hbios.ver_byte` / `upd_byte` before the transfer starts, so a document that
disagrees with itself costs a comparison rather than 512 KB.

### Two failures that look alike and are not

A transfer that never produced a file is reported as
`could not be downloaded`, not as a ROM that `did not verify`. They arrive at
the same dialog and want opposite responses - one is a signal problem and the
other is a bad copy on the device - and calling a dropped connection a corrupt
ROM sends the user to look at their storage. `RomFailure.CouldNotFetch` carries
what the transfer said verbatim, so a truncation and a 404 both read correctly;
`DidNotVerify` is now only ever the copy that IS there, which is what makes
Settings' "it was fetched again and still did not match" true.

### One resolution at a time, and the last one wins

Two things could have started a machine on the wrong ROM, and one field settles
both. `startMachine()` refuses to run twice for the release already on its way -
which matters because the play button re-runs the resolution and the download
overlay does not consume the tap that lands on it - and a resolution that
finishes after another has started is dropped rather than applied, because
hashing 512 KB is long enough to be overtaken by a release switch. The
first-launch starter-disk fetch is inside that window too: `loadSelectedCatalog`
writes a new selection back when the stored release is no longer on offer, and
the ROM resolved before it then belongs to the release that was, so the
resolution is re-run rather than the old bytes loaded.

Changing release while the machine is stopped now reloads as well. `onResume`
only acted when a ROM was already loaded, so switching release out of the "ROM
needed" state - which is exactly how somebody recovers from it - did nothing
visible until the play button was pressed.

### One downloader, not two

`downloadDisk` became a thin call onto `downloadAsset`, and the ROM uses the
same one. A second downloader would have been ninety lines of the same care
taken again - the nonce in the scratch name, the cancellation check inside the
read loop, the size and hash checks before the rename, the shared in-flight set -
and the ROM is the file where getting any of it wrong is least visible.

### `rom_name` still means what it always meant

It names the file inside the APK that `assets.open()` opens, and nothing about
this release changes that - which is why there is no migration for it. A
downloaded ROM is named by the catalog and remembered under new per-release keys
(`rom_file.v0.<release>` and its size and hash), so the two never have to mean
the same thing. The v0 rename pass still refuses `rom_name` and
`V0MigrationTest` still asserts it.

### The test fixture caught up with what is published

`app/src/test/resources/index-v0.json` was a copy of the index taken when 3.6.0
was `preview` and 3.5.1 was `default`. 3.6.0 is now published `stable` and
`default`, and that is not a detail: it is the state that makes this release
necessary. A client that preselected the index default and had no way to fetch
its ROM would pair 3.6.0 disks with the bundled 3.5.1 ROM. The fixture is the
live document again, and the assertions moved with it.

### What did not change

The bundled ROM, its packaging entry, and the fact that a first launch needs no
network. `emu_validate_rom_hcb`. The disk download path, the slice
configuration, the manifest-warning behaviour, the v0 rename pass, and every
per-release preference key that already existed. Nothing gained the ability to
delete a user's image or a user-supplied ROM.

## Version 1.27 (versionCode 29)

The interface-v0 catalog repoint and the RomWBW release picker - releases B and
C of three. Release A (1.26) renamed what was already on the device; this one
changes where things come from and lets the user choose which RomWBW release
they come from.

**NOT BUILT AS AN APK, AND NOT RUN ON A DEVICE.** This machine still has no
Android SDK, no NDK and no JDK that Kotlin 1.9.20 will start under, so nothing
here has been through Gradle, aapt2, R8, dex or the NDK, and no `.so` has been
compiled for any ABI, let alone linked. What it *has* been through is more than
1.26 managed, and the recipe is worth keeping because it needed no SDK at all:

    # 1. every Kotlin source in the app, type-checked against the real classes
    kotlinc 2.2.0 (running on the JDK 25 that is here) -cp <25 jars>
      org.robolectric:android-all 16-robolectric-13921718   # android.*
      androidx appcompat 1.7.0, recyclerview 1.3.2, activity 1.9.3,
        lifecycle 2.8.7, core/core-ktx 1.15.0 and their transitives
      com.squareup.okhttp3:okhttp 4.12.0, okio 3.6.0,
        kotlinx-coroutines-core-jvm 1.7.3, org.json 20231013
    # -> 23 source files, exit 0, and with warnings on the only one raised is
    #    the pre-existing ProgressDialog deprecation.

    # 2. all three JVM test suites, run for real
    java -cp <out>:junit:hamcrest:org.json:app/src/test/resources \
      org.junit.runner.JUnitCore ...CatalogParsingTest ...V0MigrationTest \
      ...JniNameParityTest
    # -> OK (20 tests), OK (14 tests), OK (3 tests). 1.26's suite had never
    #    been run either; this is its first pass.

Three things stand in for what is missing, and they are the reason this is a
type-check rather than a build: `R`, `BuildConfig` and `ActivitySettingsBinding`
are AGP-generated and were replaced by hand-written stubs, the `R` one built by
scanning the 81 `R.*` references in the tree so it cannot silently omit an id
somebody uses. The compiler is 2.2.0 rather than the project's 1.9.20, so a
1.9-only rejection would not show up here.

None of that reaches a device, and three of the four things most likely to be
wrong need one: JNI binding, the picker's dialogs, and anything about storage.
`MANUAL_CHECKS.md` section 7 is where those go, and its check 2 is not optional -
the new JNI names have no build-time guard whatsoever.

versionCode moves 28 -> 29 and versionName 1.26 -> 1.27. B and C carry one
number between them rather than two, but A keeps its own, because the ordering
is what makes the pair safe rather than the release count: the rename runs in
`MainActivity.onCreate` before anything can fetch a catalog, so a device that
arrives at 1.27 without ever having run 1.26 still renames its files before a v0
name can be downloaded beside a pre-v0 one.

### The release tag is gone, and nothing rebuilds a URL from one

`RELEASE_TAG = "v1.4.12"` and the two ioscpm URLs interpolated from it are
deleted. In their place is one compiled-in URL, `index-v0.json`, and two levels
of document: the index names each RomWBW release's `catalog_url`, and that
catalog names its own `base_url`. Every asset URL is `base_url + filename`,
concatenated with no separator inserted, because `base_url` ends in `/` by
contract.

This is not tidying. A whole ioscpm release shipped with its pin naming a tag
GitHub answered 404 for, and every user of that build could download nothing at
all; the shape that allowed it - a client assembling an asset URL out of a
constant it had been repinned to - no longer exists here. `DiskInfo` carries its
own `downloadUrl`, built when the document was parsed, so a disk cannot be
paired with another release's base either.

The catalog document is verified before it is parsed, against the
`catalog_size` and `catalog_sha256` the index publishes - 11826 bytes and
`7a5411b3...` for 3.5.1 today. Parsing first would let a truncated response
become a short disk list that looked like a whole catalog.

### One malformed entry does not take the catalog away

`parseDisksXml` is gone and the JSON parsers that replace it are per-entry
tolerant: an index entry with no HBIOS bytes, or a disk entry with no `id`, is
skipped and the rest of the document is used. The in-repo precedent does the
opposite - `HelpActivity.parseHelpIndex` puts required-field getters inside one
try/catch and returns null for the whole document - and copying it would mean
that publishing one bad RomWBW 3.7.0 entry took the disk catalog away from every
already-shipped client at once, including for the releases still described
perfectly. Unknown fields are ignored at every level, which is what the
compatibility rules require and what makes `upstream`, `slices`, `cbios` and
`notes` free to appear.

`roms[]` is not read at all. This build boots the ROM inside its own package and
has no path that loads one from storage, so parsing an array it could not act on
would only invite the assumption that `emu_avw` is in it.

**Run, and passing:** `app/src/test/.../CatalogParsingTest.kt`, 20 tests, against
byte-for-byte copies of the published `index.json` (2942 bytes) and both catalogs
(11826 and 14694 - exactly the sizes the index declares, so a fixture that drifts
fails the size assertion rather than quietly becoming a paraphrase). It asserts
the 20 ids under 3.5.1 and the 24 under 3.6.0, that `hd1k_ws4` is absent from
3.6.0 and `hd1k_wp` present, that `defaultSlot` is on `hd1k_combo` alone, that
removing one entry's `id` costs that entry and leaves nineteen, that a missing
`base_url` refuses the document, and that removing `roms` changes nothing.

### Settings has four catalog messages where it had one

"Failed to load disk catalog. Check your internet connection." covered every
failure. With a second round trip it would now be wrong about most of them, so
`CatalogFailure` carries the reason and Settings says which: the index did not
answer, this build's core can run nothing the catalog publishes, that one
release's catalog did not answer, or it answered and did not verify. Only the
first is a connection problem, and the second is not fixed by retrying at all -
it means no published RomWBW release matches this binary, which needs a new
build. The first-launch download path says the same things in a toast rather
than only in logcat, because a user whose first launch produced no disks is
otherwise told nothing.

### The RomWBW release is chosen, and the core is asked which are possible

Settings gains a RomWBW Release row next to the ROM. It lists what
`index-v0.json` publishes, filtered by `emu_romwbw_release_supported()` through
a new JNI call, and marks each row: `preview` for a release published as not yet
recommended, and whether this build has a ROM for it. Asking the core rather
than hardcoding matters because the core is compiled in place from a sibling
checkout, so this binary can be newer or older than the release list it was
written against; a hardcoded answer is wrong in one direction or the other with
nothing to notice it.

Three natives are new - `nativeRomwbwReleaseSupported`,
`nativeRomwbwSupportedList` and `nativeRomwbwReleaseOfImage` - and they are the
only ones in the file that read no emulator state, which is what lets Settings
call them without owning an engine. `RomwbwSupport` also reads the bundled ROM's
own HBIOS block (the first 264 bytes of `assets/emu_avw.rom`, not all 524288) to
answer which release this build can actually boot, rather than trusting a
constant: the pin this release deletes was wrong for two days in every port at
once, and no constant can report that it has gone stale.

**Run:** the whole JNI translation unit type-checks against the real core
headers -

    clang++ -std=c++17 -Wall -Wextra -fsyntax-only -I<android/log.h stub> \
      -I/home/wohl/emsdk/upstream/emscripten/third_party/jni \
      -I../romwbw_emu/src -I../cpmemu/src -Iapp/src/main/cpp \
      app/src/main/cpp/emu_io_android.cpp

exit 0, no warnings. That is a syntax and type check against the headers, not a
build: no object file, no `.so`, and no linker has ever looked for these
symbols.

The name parity that had no guard anywhere now has one.
`app/src/test/.../JniNameParityTest.kt` reads both source files as text and
compares the `external fun` names against the
`Java_com_awohl_cpmdroid_EmulatorEngine_*` exports in each direction, plus a
check that neither list came back empty - a regex that stopped matching would
otherwise let it pass by comparing nothing to nothing. 35 on each side today,
and it runs wherever `./gradlew :app:test` runs. Run here: OK (3 tests). It
cannot see what the NDK exports from the built `.so`, which is why
`MANUAL_CHECKS.md` section 7 still has a device check for the same thing.

### Disk slots, NVRAM and the catalog generation are stored per release

A 3.5.1 disk under a 3.6.0 ROM makes the guest print
`*** WARNING: HBIOS/CBIOS Version Mismatch ***`, and an NVRAM setting means a
different thing under each release - `0.5` is the WordStar 4 slice under 3.5.1
and the `wp` slice under 3.6.0, because upstream renamed it. So everything whose
validity depends on the release moved behind a `.v0.<release>` suffix, and a
one-shot pass copies the pre-v0 unsuffixed `disk_slot_0..3` and `nvram` into the
3.5.1 namespace before anything reads the new keys. The old keys are left in
place: they are all an older build reinstalled over this one would find.

The copy runs *before* the rename pass and hands it the values it settled on, so
nothing depends on an `apply()`ed write being visible to the next read - the
ordering hazard 1.26's notes describe. It is one-shot and never overwrites an
existing key, which is what stops a slot the user deliberately cleared from
being refilled from the legacy key on the next launch.

**Switching release deletes nothing.** Not the other release's slots, not its
NVRAM, and not a single downloaded image - the two releases' filenames differ,
so both sets sit in one flat directory without shadowing each other, which is
what the `-v0-<ver>` suffix was for. `generation` is recorded per release and
reported, and acted on in no other way: the obvious use for it, deleting the
images the catalog names so they are fetched again, is the iOS bug this app must
not acquire, and per-file `size` and `sha256` already answer the staleness
question on every download.

### tools/check-disk-pins.sh stops crying wolf about this port

The script greps each port's source for a `vX.Y.Z` literal. There is none here
any more, so the cpmdroid row would have printed `NO PIN FOUND` and exited 1 for
a port that was working correctly - and a gate that cries wolf is a gate that
stops being read, which is the exact failure it was written after.

Migrated ports now get a different question, and one that still fails loudly:
does the source name the v0 index, is the legacy pin really gone, does the
bundled ROM's own HBIOS block declare a release the index still publishes, and
does the built APK name `index-v0.json` rather than an old tag. The last one
replaces an artifact scan that would otherwise have gone quiet: a scan matching
nothing lands in the "no evidence either way" case, so a stale package would
never have been complained about.

**This copy of the script has diverged from the other four**, and its header now
says so. ioscpm and z80cpmw move to the same row as they migrate.

**Run:** `sh -n tools/check-disk-pins.sh` passes, and the four new helpers were
exercised against this tree - the index URL is found in
`DiskCatalogRepository.kt`, no legacy pin remains in it, `assets/emu_avw.rom`
reads `3.5.1` out of its HCB, and the published index carries that release. The
full script needs the network and the other two checkouts.

### What did not change

The bundled ROM stays bundled, and no ROM is downloaded. Selecting a release
this build has no ROM for is allowed, because the disks are still worth having,
but it is confirmed with a dialog that says in as many words that the guest will
print a version mismatch warning - the alternative was a picker with one row in
it, which is not a picker.

Nothing gained the ability to delete a user's image. The catalog dialog, the
download path, the slice configuration and the manifest-warning behaviour are
untouched apart from where their disk list comes from.

And nothing re-checks an image that is already on the device, which is worth
saying because this release makes that gap quieter rather than louder.
`hd1k_combo.img`'s sha256 is `be19984e...` under ioscpm v1.4.5 and v1.4.11,
`89b8ae1a...` under v1.4.12 and `0ca4ec60...` in the v0 catalog - three
different files under what used to be one name - so a device that downloaded it
under any pin now holds bytes the v0 catalog does not describe, under a v0 name,
with a downloaded tick beside it. The rename is still the right thing: it loses
nothing, and re-fetching 49 MB uninvited is not a migration's decision. But the
disagreement used to be visible as a name and is now not visible at all.
`todo.txt` carries it, and the fix - hashing an installed file against the
`sha256` this parser already reads - is now a small one.

## Version 1.26 (versionCode 28)

The interface-v0 storage migration, release A of three, plus the deletion of a
disk-image hot-patch that had outlived every image it was written for.

**NOT COMPILED, AND NOT RUN** - the machine this was written on has no Android
SDK, no NDK and no JDK that Kotlin 1.9.20 will start under (`./gradlew tasks`
dies with `IllegalArgumentException: 25.0.4` inside the compiler's embedded
version parser, before it ever looks for the SDK). What was run is named under
each heading, and `MANUAL_CHECKS.md` section 6 says what has to be pointed at a
real device before this ships.

versionCode moves 27 -> 28 and versionName 1.25 -> 1.26. 27 is spent on the
published 1.25, so the tree was un-uploadable as it stood; that is a
prerequisite of this release, not a part of it. An unsigned release APK is still
a *successful* build here, so run `:app:signingReport` rather than trusting an
exit code.

### Stored disk names move to the v0 convention

`romwbw_disks` publishes its catalog with the RomWBW release in every asset
name - `hd1k_combo-v0-3.5.1.img` where the catalog this app fetches today says
`hd1k_combo.img` - so that a 3.5.1 image and a 3.6.0 one can share one flat
directory. This release renames what is already on the device to match, and
changes no URL: the catalog fetched, the tag pinned and the names downloaded are
all exactly what 1.25 used. Splitting the rename from the repoint is deliberate.
A build that both renames every stored file and changes where files come from
gives nobody a way to tell which half broke.

The whole of it is in `data/V0Migration.kt`, which has no `Context`, no
`SharedPreferences` and no `android.*` import, because the alternative was
shipping a pass that renames the user's files with no way to exercise it off a
device. `SettingsRepository.migrateIfNeeded()` calls it once from
`MainActivity.onCreate`, before `checkFirstLaunchAndLoad()` and before any ROM
is loaded.

What it will not do is as much of the point as what it does:

- **It renames; it never copies.** Nineteen of the twenty published 3.5.1 images
  are byte-identical to their pre-v0 selves, so `File.renameTo` keeps the size
  and modification time a 51 MB image already has, and costs no download.
- **It never deletes.** An unrecognised name is a user's own import. A
  destination that already exists is kept and the source left beside it - an
  orphan costs storage, and either alternative costs data.
- **It leaves `rom_name` alone.** That key sits in the same preferences file as
  `disk_slot_0..3`, holds a bare filename of exactly the same shape, and the v0
  catalog really does publish `emu_avw-v0-3.5.1.rom` - so "rename every stored
  bare filename" is the natural reading and the one that bricks the app:
  `assets.open()` names a file inside the APK, and a renamed `rom_name` shows
  "ROM not found" on every launch with no way back, the Settings row being
  read-only. The set of migratable stems holds disk ids only, and
  `V0MigrationTest` asserts the ROM names are refused.
- **It migrates both image directories.** `Disks/` and `ModifiedDisks/`, in that
  order per name: preferences are rewritten only after every rename, so a
  process killed mid-pass comes back with the slot still naming the pre-v0 file,
  and under that name `loadDiskDataWithPersistence` still finds the user's
  written-to copy. The other order leaves the persisted copy under a name
  nothing asks for and the pristine download answering in its place - hours of
  CP/M work invisible, and no error anywhere.
- **A pair that cannot both move does not move at all.** A failed rename in
  `Disks/` already stops the `ModifiedDisks/` one for that name; the reverse -
  the download moved and the written-to copy could not - now puts the download
  back. Half a rename is worse than none: the slot correctly stays on the old
  name, because that is where the user's work still is, but `Disks/` no longer
  answers to it, so `isDiskDownloaded()` says false, `checkFirstLaunchAndLoad()`
  reads a first launch, and slot 0 is overwritten with the catalog's default.
  The failed pass has to leave a state the app boots, not one the recovery path
  finishes off.
- **A preference follows its file, not the convention.** If either directory
  still holds the old name with no v0 file beside it, the rename for it did not
  happen and the slot stays where the bytes are. Moving it anyway is the
  expensive mistake: `checkFirstLaunchAndLoad()` reads a slot naming a file
  `isDiskDownloaded()` cannot find as "first launch", downloads whatever the
  catalog marks `defaultSlot` 0, and writes *that* over the user's slot-0
  choice. The recovery path is what would destroy the configuration.
- **It is idempotent, not merely guarded.** Every `prefs.edit {}` here uses
  `apply()` while `renameTo` is immediate, so a kill between them comes back to
  already-renamed files and an unwritten flag. Running the pass twice is a
  no-op; the flag is an optimisation, not the correctness argument.
- **It refuses to declare victory over a directory it cannot see.**
  `getExternalFilesDir(null)` can return null, and `File(null, "Disks")` is a
  relative directory in the process working directory - the accident
  `HostTransfer.transferDir()` already guards. A pass that walked that would
  list nothing, rename nothing, report success, and leave the migration stamped
  done over a `ModifiedDisks/` still full of pre-v0 names. Two new accessors,
  `DiskDownloadManager.getDisksDirOrNull()` and `getPersistedDisksDirOrNull()`,
  answer null instead, and the flag is written only on a completed pass.

The flag is `disk_names_migrated.v0.3.5.1`, per (interface, RomWBW version), and
deliberately not a `CURRENT_PREFS_VERSION` bump: that stamp is one number for
every past migration and version 2's step removes `warn_manifest_writes`, so
declining to write it - which this pass must be able to do - would reset the
user's choice on every launch until a rename succeeded.

**Release B has to run this pass once more.** Between A and B this build still
downloads pre-v0 names, so a device can acquire one after the flag is set. B
either clears the flag or ships its own.

### The w8.com hot-patch is gone

`nativeLoadDisk` scanned every byte of every disk image handed to it for
`fe 41 d8 fe 5b d0 c6 00` and poked byte 7 to `0x20`, repairing a `w8.com` that
um80 0.3.42 miscompiled - `add a,'a'-'A'` assembled as `add a,0`, so W8 exported
uppercase filenames.

Checked rather than assumed, in Python over the images `romwbw_disks` publishes
(`grep -P` cannot match the trailing NUL and reports nothing): **none** of the 44
images under `build/v0-romwbw-3.5.1/` and `build/v0-romwbw-3.6.0/` contains that
sequence, and `build/utils/w8.com` carries the fixed `fe41d8fe5bd0c620` and the
`06 e9 cf` `HBF_HOST_CAPS` probe. So it repaired nothing.

It was not a dormant no-op either, which is the reason to delete it rather than
leave it. **Ten** of those images contain the fixed variant - `hd1k_bp`,
`hd1k_combo`, `hd1k_nzcom`, `hd1k_z3plus`, `hd1k_zpm3`, identically under both
RomWBW versions - and only `hd1k_combo` carries a `w8.com` at all. In the other
four that eight-byte window is an ordinary CP/M tolower idiom (`cp 'A'` /
`ret c` / `cp '['` / `ret nc` / `add a,N`) inside unrelated programs, one byte
away from the pattern. Any third-party or future image assembling `add a,0`
after that same five-byte prologue would have been rewritten in place - and in
the caller's `ByteArray` too, since ART hands out a direct pointer for a large
array. The comment left in its place says so, so nobody restores it.

`emu_io_android.cpp` was compiled by host `clang++ -std=c++17 -Wall -Wextra
-fsyntax-only` against the real `romwbw_emu` and `cpmemu` headers after the
deletion - with emsdk's `jni.h` and a stand-in for `android/log.h`, this JDK
having no `include/` of its own. Clean, no warnings. That is a translation unit
type-checked, not an NDK build and not four ABIs.

### The project has unit tests

`app/src/test/java/com/awohl/cpmdroid/data/V0MigrationTest.kt`, the first test
source set here, and one `testImplementation("junit:junit:4.13.2")` to run it
with. It covers the twenty published name mappings against the filenames the
catalogs actually carry, the ROM names, user imports, 3.6.0-only ids, scratch
files and degenerate shapes, both directories, idempotence, an occupied
destination, a rename that fails in either directory, and unavailable storage.
It had **never been run** when this release was written - see the preamble -
and it needs no SDK, no emulator and no device when somebody does run it:
`./gradlew :app:test`. (It has since run, under 1.27's host recipe.)

What *was* run, on this machine, is the in-repo precedent for exactly this
situation - `android_host_path_basename()` was checked by lifting it into a host
program and sweeping it, and the same was done here:

- `v0NameOf()` transcribed statement for statement into Python and swept over
  **200,070** cases: every filename the two published catalogs carry, the twenty
  in the pre-v0 `disks.xml` this build fetches, both ROM names, the 3.6.0-only
  ids, scratch and degenerate shapes, and 200,000 random strings over an
  alphabet of dots, dashes, separators and spaces. The stem set was parsed out
  of `V0Migration.kt` rather than retyped: it is exactly the legacy catalog's
  twenty stems and exactly `catalog-v0-3.5.1.json`'s twenty ids, the mapping is
  a bijection onto the twenty published v0 filenames, and it is idempotent
  everywhere.
- `migrateDiskNames()` modelled the same way and run over **768** combinations
  of `Disks/` state, `ModifiedDisks/` state, slot value, kill point and
  rename-failure mode. No combination loses or duplicates content, none rewrites
  a user's slot, and every one of them - after the retry the next launch
  performs before anything reads a slot - leaves the preference loading the
  written-to copy. Seven leave the on-disk state briefly inconsistent when the
  process dies between the renames and `apply()`; all seven are repaired by that
  retry, which is what the idempotence is for.
- A fifth invariant was added to that sweep afterwards and found the rollback
  bug above: **a slot whose download was in `Disks/` must still find one there
  when the pass returns**, because `isDiskDownloaded()` false is what
  `checkFirstLaunchAndLoad()` reads as a first launch. Exactly one of the 768
  violated it - `Disks/` renamed, `ModifiedDisks/` refused, no kill - and it is
  the state that would have cost the user their slot-0 choice. With the
  rollback, 768 of 768 hold.

Neither is the Kotlin. A transcription checks the reasoning, not the code, and
nothing here has been through a Kotlin compiler - though `V0MigrationTest`
covers the same case directly, and has since run.

## Version 1.25 (versionCode 27)

Two scrollback defects, both found on 2026-09-02 by reading this port against
z80cpmw and ioscpm rather than by running it, and four more found on 2026-09-03
by reading the fix. They take a number of their own rather than folding into
1.24. versionCode skips 26: Play has already refused one number this repo
believed was free, and the App bundle explorer, not this file, is the authority
on which are spent.

### The ROM refusal comment stopped describing the pin, because there is none

**NOT COMPILED** - no Android SDK or NDK on the machine this was written on.
Comment-only in `emu_io_android.cpp`; no statement, symbol or signature changed,
so the binary is unaffected.

`romwbw_emu` v1.39 deleted `ROMWBW_PIN_STR` and the compile-time version pin
behind it. Unlike `ioscpm` and `z80cpmw`, this port never displayed that value,
so nothing here failed to compile - which is exactly why it was worth checking
rather than assuming. What it did carry was a comment at the
`emu_validate_rom_hcb` call in `nativeLoadRom` explaining the refusal as "a ROM
built for a RomWBW release other than the pinned one", and that is now wrong in
a way that would mislead the next person reading the log it describes.

The core reads the RomWBW version out of whichever ROM it loads and runs any
release in `ROMWBW_SUPPORTED_RELEASES`. What `emu_validate_rom_hcb` still
refuses is a corrupt HBIOS configuration block, or a release this core has never
been checked against - a narrower thing, and the message now names both the
release it found and the list it accepts. The comment says that.

Worth recording for whoever does the catalog migration: because the core no
longer refuses a ROM for being the wrong release, the guest's
`*** WARNING: HBIOS/CBIOS Version Mismatch ***` is now the only thing enforcing
that a ROM and its disk images come from the same release.

### The view stops being yanked to the bottom by the guest

`processOutput` opened with `if (data.isNotEmpty()) userScrollUp = 0`, before the
bytes were even parsed, so any output at all threw the reader back to the live
prompt. Scrollback was usable only while the guest was idle - which rules out
reading a `DIR` or an assembly listing while it is still printing, and that is
most of the reason to have scrollback.

`scrollUp` now advances `userScrollUp` with each captured line while the user is
reading history, so the view stays on the same content as output arrives beneath
it. That is what both siblings do: z80cpmw advances `m_scrollOffset`, ioscpm
advances `scrollbackOffset`.

The snap now happens where the spec always said it should - on a key sent to
CP/M. `sendChar` returns to live, and paste goes through `sendChar`, so both
behave. `sendAnswerback` deliberately bypasses it, so a terminal query cannot
throw the user out of history; that function's comment anticipated `sendChar`
growing exactly this side effect, and now it has one.

### Scrollback works with the soft keyboard open

`onDraw` split on `viewportRows < rows`, and the short-viewport branch - the
keyboard being up - never read `userScrollUp` or `historyChars` at all. No
history was drawn however far the user dragged, while the drag and chord
handlers still moved the offset and still called `invalidate()`, so the feature
looked dead rather than unavailable.

There is now one drawing path. What changes with the keyboard is only where the
live screen sits inside the scrollable content, not whether there is any. The
scroll limit moved with it: `maxScrollLines()` counts the hidden live rows as
well as the history, because clamping to `historyChars.size` left a user with the
keyboard up unable to reach the top of their own screen on a fresh boot.

The live cursor is no longer painted into the history view - it is drawn only at
the live bottom, as z80cpmw gates on its offset and ioscpm does by passing
`showCursor` as `!isScrolledBack`.

### The new bound was not applied everywhere it had to be

`maxScrollLines()` arrived with the fix above and then reached only some of the
paths that move the view, which left the keyboard-open case it exists for broken
in two ways.

`scrollUp`'s follow-along anchor still clamped to `historyChars.size`. Above the
history - the range `maxScrollLines()` opened, and the only range a fresh boot
with the keyboard up has - that clamp runs backwards and pulls the view forward
instead of holding it. With a 10-row viewport of a 24-row screen, the first line
of output moved the view 14 rows toward live: the defect the first section
describes, still present in the case this release advertises. It clamps to
`maxScrollLines()` now.

Nothing re-clamped `userScrollUp` when the viewport changed size. `onDraw` clamps
a local copy and never writes back, so an offset that had gone out of range
stayed in the field, invisible, until the next captured line latched it.
Dismissing the soft keyboard is the way in: the limit drops by the hidden live
rows, the screen still draws correctly because `onDraw` clamped, and then
`scrollUp` pins the view to the oldest history line with the cursor suppressed.
The terminal looks frozen while CP/M keeps printing, and nothing on screen says
that typing gets you out. Before the snap-on-output was removed that state was
unreachable, which is why the clamp now lives where the geometry changes:
`onSizeChanged`, `setPadding` and `recalculateSize` call
`clampScrollToViewport()`.

Ctrl+Home and Ctrl+End were still measured in `historyChars.size`, so with the
keyboard up and nothing in history yet, Ctrl+End was `scrollHistoryBy(0)` - a
no-op, and the documented way back to the live prompt could not rescue a user
from the state above. Both take `maxScrollLines()` now.

The Esc and Tab buttons on the control strip queued their byte straight into the
emulator, bypassing `sendChar`, so they never returned the view to live and the
guest's reply was painted off-screen. That reads as a dead button, and the
control strip is the nearest keyboard in exactly the soft-keyboard case this
release is about. `sendChar`'s return-to-live is now `returnToLive()`, public,
and both buttons call it first.

### The help topic stops documenting the old behaviour

`help_quick_start.md` said "Any new output from CP/M snaps you back to the live
prompt" - the defect above, written down as a feature. It now says the view stays
where you put it while CP/M prints, that typing returns you to the live prompt,
and that scrollback works with the keyboard open. Both copies changed and are
still identical: `app/src/main/assets/help/` ships inside the APK as the offline
tier, while `release_assets/` is what `HelpActivity` fetches through
`releases/latest`, so the corrected wording reaches an online reader only once it
is attached to the release.

### Not verified on a screen

The 2026-09-02 changes were made on a machine with no SDK, no kotlinc and no
emulator; the 2026-09-03 ones were found by reading them. What backs the
arithmetic: a brace-balance check, a line-by-line read against both siblings, and
a port of `onDraw`'s windowing to a scratch model, which reproduces the old
keyboard-down output exactly for every combination of history size, viewport and
scroll offset tried, reproduces both defects fixed above, and sweeps every valid
offset over 40 history sizes, 24 viewports and 24 cursor rows without producing
an out-of-range window or a position the user cannot reach.

The 1.25 release build compiled all of it, against the NDK and a real SDK, so it
is no longer untested against a toolchain - and that is the whole of what the
build settles. Nobody has watched it run. todo.txt says what to point at it:
drag back with the keyboard closed, then open the keyboard and drag again; run a
long `DIR` and confirm the view stays put while it prints; type a key and confirm
it returns to live; confirm the cursor is absent while scrolled back and present
at the bottom.

## Version 1.24 (versionCode 25)

Renumbered from 1.23 / versionCode 24, which Play would not take - the
versionCode was already spoken for. Nothing below changed with the number;
it is the same work, and 1.23 never reached anyone.

### The version banner stops being able to lie

1.22 was installed on a tablet and greeted its owner with a build date from
July. The binary was innocent - the shipped APK carries `2026-08-31 16:29:09`,
and the banner had simply never been able to tell "this build" from "the build
that is running", so an install that did not take looked exactly like one that
did.

- **`BUILD_TIME` is gone.** It was `SimpleDateFormat(...).format(Date())`
  evaluated inside `buildConfigField`, which runs at *configuration* time. Two
  ways that goes wrong: Gradle's configuration cache freezes the value, after
  which the app reports a build time older than its own binary; and because it
  changed on every build it also defeated the up-to-date check on
  `generateBuildConfig` and every compile task downstream, so nothing incremental
  ever stayed incremental.
- **`GIT_SHA` and `SOURCE_DATE` replace it**, derived from `git rev-parse` and
  the commit date. A commit is a function of the source rather than of the
  clock, so it is correct by construction and stable across rebuilds of the same
  tree. A `+dirty` suffix marks a build made over uncommitted edits, which no
  commit describes exactly. Built without git, it degrades to `nogit`.
- **The build time now comes from the installed APK itself**, via
  `File(applicationInfo.sourceDir).lastModified()`. This is the field that
  answers the question that was actually asked: it is read from the file backing
  the running process, so it cannot survive into a later build the way a
  compiled-in constant can.
- **The banner and the About dialog both show `versionCode`.** That is the
  number Play orders releases by, so an install that silently did not replace an
  older one now shows up as a number that went backwards.

The boot banner is now `CPMDroid v1.23 (24) <sha> <when the APK was written>`,
and About adds the commit and its date under it.

### Signing credentials move out of the checkout

`keystore.properties` was read from the repo root and gitignored, which is the
combination that nearly lost the upload key: when `C:	emp\src` was deleted and
re-cloned, the file went with the directory and survived only as a deleted entry
in the Recycle Bin.

`app/build.gradle.kts` now resolves it from outside the checkout instead, taking
the first that exists: `-PcpmdroidKeystoreProperties=<path>`, the same-named
property in the user's own `~/.gradle/gradle.properties`, the
`CPMDROID_KEYSTORE_PROPERTIES` environment variable, or - still - the old repo
root location, so an existing checkout keeps working.

The path is per-machine configuration and is deliberately not hardcoded: that is
the mistake the root `gradle.properties` already documents backing out of, when
an absolute Windows java home in a tracked file stopped every other host before
it could read a build script.

Worth knowing, because nothing announces it: when none of the four locations
resolves, the release build still SUCCEEDS and produces an UNSIGNED artifact.
`:app:signingReport` is the check, not the exit code.

### Play's edge-to-edge warning: not acted on, deliberately

Play flags `Window.setStatusBarColor` and `setNavigationBarColor` as deprecated,
naming `BottomSheetDialog.onCreate`, `EdgeToEdgeUtils.applyEdgeToEdge` and
`SheetDialog.onCreate`. All three are Material's, none is reachable - this app
constructs no sheet of any kind and uses Material only as a theme parent. It was
investigated properly and left alone, because every route out costs more than it
saves:

- Upgrading Material does not help: 1.12, 1.13 and 1.14 all carry the same
  references, confirmed by extracting the AARs.
- Enabling R8 does not clear it either. It deletes `EdgeToEdgeUtils` as a class
  only by *inlining* it into `MaterialDatePicker`, and Play deobfuscates with the
  uploaded mapping, so the same origin is reported under the same name.
  `MaterialDatePicker` is itself a keep root seeded by fragment's own consumer
  rules, so no rule can drop it.
- Play saw six callers and named three. The other three are androidx.activity's
  `EdgeToEdgeApi23/26/29`, reached from this app's own `enableEdgeToEdge()` on
  every launch - Play exempts them. Upgrading androidx.activity would make it
  worse, adding an `EdgeToEdgeApi35` that calls both setters too.
- Only removing Material entirely would clear it, and five of the six attributes
  in `themes.xml` do not exist in AppCompat.

On targetSdk 36 the platform forces the bars transparent and ignores these
setters anyway, so the warning describes bytes rather than behaviour. It will
reappear on every release until Material leaves the project; reappearing is not
new information.

## Version 1.22 (versionCode 23)

The 1.19 entry below is folded into this release. versionCode 20 was
bumped in the tree on 2026-08-07 but was never built or uploaded, so its
changes reach users here for the first time. The last version to reach the
Play Store was 1.18 (versionCode 19).

Synced to emulator core **v1.36**, and took the four keyboard and terminal
gaps the cross-port sweep found here.

**Everything in this section has now been built with the NDK and run**, on
Windows with the SDK, NDK `28.0.13004108` and an API 36 emulator, on
2026-08-29. Earlier drafts of this preamble carved out the zero-byte export fix
and the ANSI colour fix as unbuilt, because the machines that wrote them had no
Android SDK; that carve-out is gone, and **Verified** says what was actually
watched rather than what was argued from the source. The exceptions are named
there too, and they are exceptions of a different kind: a tablet with a
third-party IME, a share completed to a real recipient, and the two HBIOS calls
that need a refreshed disk image to reach.

That round also did more than confirm. Running the colour check found that the
SGR **background** ranges had no implementation at all, which is why closing it
appears under **Fixed** rather than only under **Verified**; and parity item
4(a), the missing save-as, picker and share, is closed under **Added**.

**A second 2026-08-29 round, on a different machine, added everything under
*The terminal stops being the thinnest of the four* and *R8 says which file it
read* below, and it was NOT built with the NDK.** That machine had no Android
SDK and no emulator. Saying only that would understate it and saying nothing
would overstate it, so here is exactly what was run:

- The whole of `emu_io_android.cpp` was compiled by host `clang++ -std=c++17
  -Wall -Wextra` against the real `romwbw_emu` and `cpmemu` headers, with the
  JDK's own `jni.h` and a three-line stand-in for `android/log.h`. It is not the
  NDK and it is not four ABIs, but it is a full translation unit type-checked
  against the siblings' current source. No warnings.
- `TerminalView.kt` and `EmulatorEngine.kt` were compiled by `kotlinc` 2.2
  against **real Android framework classes** (`org.robolectric:android-all`, the
  API 36 build, from Maven Central). Not stubs: the actual `android.graphics`,
  `android.view` and `android.media` classes the file calls into. Clean, with
  one pre-existing deprecation warning about `DisplayMetrics.scaledDensity`.
  `MainActivity.kt` was not compiled - it needs AndroidX and a generated `R` -
  and its share of this round is one changed call site.
- `android_host_path_basename()` was checked against the shared original by
  extracting BOTH functions from their real source files into one host program
  and running 403,760 differential cases through them - every hand-written case
  the contract's prose names, every stem length either side of the 255-byte
  boundary, multi-byte sequences straddling that boundary from thirty offsets,
  and 400,000 random strings over an alphabet of separators, colons, dots and
  malformed UTF-8. **Zero mismatches, and nothing over the cap.**

What that does NOT cover is an APK, a device, or anything drawn on a screen.
Every terminal sequence added below is unwatched. `MANUAL_CHECKS.md` says what
to point at it.

### The terminal stops being the thinnest of the four

`z80cpmw/FEATURE_PARITY.md` row 13 called this port's parser "the thinnest of
the four", and the two `todo.txt` entries under it named what was missing
without arguing about it. Both are now closed. The reference throughout is
z80cpmw's `TerminalView.cpp`, because that is the port the parity document
measures the family against; where this port deliberately differs, the code says
so at the point of difference rather than here.

- **ESC followed by anything but `[` is no longer discarded**, which is the
  change every other one below depends on. `processEscape()` is a dispatch
  table now: `7`/`8` (DECSC/DECRC), `D`/`E`/`M` (IND, NEL, RI), `c` (RIS),
  `Z` (identify), `<` (exit VT52), `=`/`>` (keypad, accepted and ignored), the
  charset and line-size designators `( ) * + #` and space (consumed with their
  argument byte, through a new parser state), and the ten VT52 bytes.
- **VT52 mode.** Entered by `ESC [ ? 2 l` or auto-detected from any
  VT52-exclusive escape - receiving one is itself the signal that the guest is
  driving a VT52 - and left by `ESC <`, `ESC [ ? 2 h` or RIS. `ESC Y` takes its
  two coordinate bytes through two dedicated parser states. `ESC D`, `ESC E`
  and `ESC H` mean different things in the two modes and are dispatched on the
  mode already in force, never guessed.
- **DECSTBM**, the scrolling region, and with it a line feed that knows about
  one: at the region's bottom row the region scrolls and the cursor stays put,
  so a program can keep a status line below it. `IL`, `DL`, `SU`, `SD` and the
  reverse index all honour it; cursor addressing does not, because there is no
  origin mode here or in either sibling. A region that is inverted or one line
  tall is rejected whole.
- **DECSC/DECRC and SCP/RCP.** `ESC 7` saves the position AND the rendition -
  both colours, the attribute bits and reverse - so a program that saves, prints
  a highlighted status line and restores gets its rendition back too. `CSI s`
  saves the position alone and shares the same slot, as it does in both
  siblings.
- **The seven editing commands**: `ICH` (`@`), `DCH` (`P`), `ECH` (`X`), `IL`
  (`L`), `DL` (`M`), and the two scrolls `SU` (`S`) and `SD` (`T`). Also
  `CHA`/`HPA`
  (`G`, `` ` ``) and `VPA` (`d`). Every one fills what it vacates through the
  same blank helpers everything else uses, so an inserted blank takes the
  current background like any other erase.
- **The query replies.** `ESC Z` answers `ESC [ ? 1 ; 0 c` in ANSI mode and
  `ESC / Z` in VT52; `CSI c` answers the same Device Attributes string; `CSI 6 n`
  answers the cursor position and `CSI 5 n` answers "I am fine". The private
  forms (`ESC [ ? 6 n`, `ESC [ > c`) are deliberately silent, as they are in
  z80cpmw: they ask about something this terminal does not have.
- **Private modes are now acted on rather than swallowed**: DECANM (`?2`),
  DECAWM (`?7`) and DECTCEM (`?25`). The marker itself is remembered rather
  than merely consumed, which five finals need - and one of them needs it to
  avoid a bug. `ESC [ > 4 ; 2 m` is how an xterm-aware program asks about
  `modifyOtherKeys`; read as SGR its `4` turns underline on. The bare
  `ESC [ > m` is the worse half: with the marker consumed and no parameters
  left it is indistinguishable from `ESC [ m`, and resets the whole rendition -
  which is the bug z80cpmw's own changelog records fixing.
- **Deferred autowrap.** A glyph landing in the last column leaves the cursor on
  it with the wrap armed, and the wrap is taken by the NEXT glyph - so a line
  that exactly fills the width no longer costs a blank line under it. The user's
  "wrap long lines" setting and the guest's DECAWM are kept as separate
  questions, which they are: with wrapping off this port still truncates, and
  with wrapping on but DECAWM off the cursor now stays on the last column and
  overwrites it rather than throwing the rest of the line away.

- **Per-cell attributes: bold, underline, blink and reverse.** `SGR 1`, `4`, `5`
  and `7` were parsed into nothing; a cell carried a foreground and a background
  and no third thing. It now carries a flags byte with the same three bits and
  the same values as z80cpmw's `TCELL_BOLD`, `TCELL_UNDERLINE` and
  `TCELL_BLINK`, so the two ports' cell dumps can be compared directly, and
  `22`, `24` and `25` undo them.
  - Bold and underline select between **four `Paint` objects** built once and
    indexed by the flags, the same shape as z80cpmw's four `HFONT`s and for the
    same reason: changing a typeface invalidates the glyph cache behind it, and
    doing that per cell on a 24x80 grid would do it 1920 times a frame. The grid
    metrics still come from the plain face alone, so a bold face with a
    different advance cannot move the grid - and it cannot smear either, because
    every glyph is positioned individually.
  - **Bold does not brighten the colour here**, unlike z80cpmw, and that is a
    consequence of storage rather than a choice: that port packs a CGA attribute
    byte whose bit 3 *is* the bright half of the palette, so bold and bright
    cannot be separated there. A cell here holds a full ARGB foreground with the
    bit beside it, so `ESC[1m` picks the heavy face and leaves the colour alone,
    and `ESC[22m` undoes precisely what `ESC[1m` did.
  - **Reverse video is resolved into the two colours when a cell is filled**,
    not stored on the cell and not applied at paint time - which is what makes
    `SGR 7` and `SGR 27` exact inverses. This port needed one thing z80cpmw did
    not: its default background is a *sentinel* meaning "paint no rectangle",
    and reversing that would produce a foreground of "no background", which
    draws nothing at all. A reversed default cell takes the page colour as its
    foreground instead, so `ESC[7m` on an untouched screen inverts rather than
    going silent.
  - **Blink runs off a 500 ms tick that only exists while something is
    blinking.** It is armed when a blinking cell is written and each tick
    re-checks the live screen; the moment the last one is erased or scrolls away
    the loop ends. A session that never sees `SGR 5` - which is nearly every
    session - never schedules anything.

- **LF now carries an implicit carriage return.** Both siblings do this at the
  same point and z80cpmw's comment claims "both mobile ports do this", which was
  not true of this one: `newLine()` moved the row and left the column where it
  was. Ordinary CP/M output sends CR before LF and never noticed, but anything
  with bare LFs - a file that came from a Unix host, `TYPE`d - stair-stepped
  down and to the right.

- **Two sequences that arrive while a wrap is armed no longer lose the
  character after them.** VT52 `ESC J` and the VT52 direct cursor address
  `ESC Y` did not resolve a pending wrap, so the next glyph took the wrap first
  and landed a row below where it was addressed - and on the bottom row of a
  scrolling region, scrolled the region as well. `ESC Y` is a deliberate
  divergence from z80cpmw, which does not clear it: that port's own conformance
  suite asserts "a cursor move cancels an armed wrap" and `ESC Y` is the one
  cursor move exempt from it, so this sides with its tests against its code.
  ioscpm clears it.

- **A wrap that was armed and then bypassed no longer eats the rest of the
  line.** An intermediate draft of the deferred-wrap work replaced the old
  "cursor is past the edge" test with a bare early return, which is right for
  truncate mode and wrong for wrapping: a TAB, a `CUP`, a `CUF` or a restored
  cursor can leave the column past the wrap point, and every one of those
  clamps against the buffer width while the wrap point is the *visible* width -
  which is the smaller of the two at any font size above the default. The old
  code wrapped in that case; the draft discarded every remaining byte on the
  line, without writing it anywhere. Found in review, before it was ever built.

- **`ESC c` (RIS) reaches the machine-level reset, and that reset now covers the
  whole terminal.** `clear()` used to say in a comment that the guest could not
  reach it. It can now, which is what RIS means - so it puts back every mode the
  parser can be left in: the scrolling region, VT52, DECAWM, cursor visibility,
  the saved cursor and the parser state. The scrollback is still deliberately
  kept; losing the user's history is a product decision, not part of putting the
  terminal back to power-on.

- **`CSI 6 n` reports a column the guest can address.** The cursor legitimately
  sits one past the last column while a line is being truncated, and the first
  draft of the new report published that unclamped - column 81 on an
  80-column screen, which `CUP` cannot address and neither sibling can produce.
  The same state made `ESC 7` / `ESC 8` stop being an identity at the right
  margin, because the save kept it and the restore clamped it away.

### R8 says which file it read

- **`emu_host_file_get_read_name()` answered with the guest's own request**,
  basenamed and lowercased - a claim about what was opened, assembled out of
  what was asked for. Since the v1.36 core added `HBF_HOST_GETRNAME` (0xEA), R8
  prints that answer as fact on its `Reading:` line. The two agree whenever the
  exact name is sitting in `Imports` and part company everywhere else: on the
  case-insensitive fallback, and on the bare-FCB R8 that sends no name at all
  and is handed whichever file the filesystem lists first - where the line read
  `Reading: ` with nothing after it, for a file that was very much being read.
  - The shim now keeps **two** strings. The request is still what the Kotlin
    layer reads as its lookup key; the *source* comes back down beside the bytes
    through `nativeProvideHostFileData`, because the resolution happens in
    Kotlin against a folder the C++ cannot see. `todo.txt` named the two
    acceptable fixes and said which was the honest one; this is that one.
  - It is an **absolute path**, as the CLI's `realpath()` and the Windows port's
    `resolveRealPathExisting()` both are, and as this port's own write side
    already is. `Imports` lives under `getExternalFilesDir()`, which the stock
    Files app has hidden since Android 11, so a bare leaf answers "which file"
    only for someone who already knows where to look. The core truncates from
    the left with a leading `...` if it will not fit R8's 255-byte buffer.
  - The getter is now gated on `HOST_FILE_READING`, as the CLI's and the Windows
    port's are, and **both strings are cleared** on close and on cancel - neither
    was, so each outlived the transfer that set it. The gate matters more here
    than anywhere else because of *when* R8 asks: it calls 0xEA between the open
    and the read loop, and on this port an open only parks the request for the
    Kotlin layer's next poll. So the state at that moment is usually still
    `HOST_FILE_WAITING_READ`, this answers `""`, and R8 falls back to printing
    what was typed - which is the documented behaviour for a backend that cannot
    yet say, and is the truth at that moment.

### The C++ copy stops drifting from the shared original

- **`android_host_path_basename()` grew the `EMU_HOST_NAME_MAX` cap** it had
  been missing. The copy's own comment promised to stay in step with
  `romwbw_emu`'s `emu_host_path_basename()` and had not: the shared original
  caps a component at 255 bytes keeping the extension and never cutting inside a
  UTF-8 sequence, and this had no cap at all. Nothing shipped could reach it,
  because R8 and W8 both build the path in a 128-byte buffer - but that is a
  property of the guest programs on today's disk images, not of the function's
  contract, and the next caller does not inherit it. The three helpers are
  ported whole, and the differential run described in the preamble is what says
  so.
- **The shared original can return an empty name, and this port now refuses to
  pass one on.** `emu_host_path_cap_name()`'s head-keeping branch backs its cut
  off a UTF-8 continuation byte with no floor, so a component longer than 255
  bytes whose first 255 bytes are continuation bytes backs all the way to zero:
  `emu_host_path_basename(std::string(256, '\x80'), "download.bin")` is `""`.
  Its *other* branch guards exactly this. The declared contract in `emu_io.h`
  says the opposite in as many words - "a result of `""`, `"."`, `".."` or a
  bare drive letter is replaced by `fallback`" - and an empty leaf on the write
  side makes the reported destination the `Exports` **folder**. The guard is at
  this port's call site rather than inside the copy, so the copy stays
  byte-for-byte the shared function and the next cross-port sweep finds no drift
  to file. It is an upstream fix and it is in `todo.txt` as one.

### The core sync

- **`emu_host_path_caps()` is defined, and that is what makes the port build
  at all.** The v1.36 core declares this function and deliberately does not
  define it, so a port that syncs without supplying it fails to link:
  `undefined symbol: emu_host_path_caps()` from `hbios_dispatch.cc`. It
  answers HBIOS `HBF_HOST_CAPS` (0xE9), the probe the new `W8.COM` makes
  before it will hand a host path to the emulator, and `W8` believes the
  answer - which is why the assertion has to be written by the code it is
  about. It replaced a design where the core returned the bit as a constant,
  under which a port that had never thought about guest paths claimed to be
  safe just by compiling.
- **The guest path is now reduced in the C++ shim, not only in Kotlin.** Both
  `emu_host_file_open_read()` and `emu_host_file_open_write()` cut the
  incoming string to a single leaf component before anything else sees it,
  using a copy of the shared `emu_host_path_basename()` (CMakeLists does not
  compile `emu_io_common.cc`; it would collide on ten symbols this port
  defines for Android). The Kotlin checks are still there and still run, but a
  UI layer was the wrong place for the only copy: `emu_host_path_caps()`
  speaks for the shim, and a second consumer of the write name would not have
  inherited a guarantee that lived in the caller. See `romwbw_emu`
  `docs/DOWNSTREAM_2026-08-25.md` section 0 for the iOS bug this shape exists
  to prevent - there an unreduced `..` reached `removeItem` and took the
  user's entire disk library with it.
- **The exported name is lowercased**, as the CLI and browser backends do. The
  CCP uppercases the whole command line before the emulator sees it, so the
  typed case is already gone and the convention is all that is left; a backend
  that picks differently makes the same `W8` command produce differently-named
  files on different front ends.
- **`emu_host_file_get_write_name()` reports the effective destination**, per
  the tightened v1.36 contract: the full `Exports/` path rather than an echo
  of what the guest asked for. `W8` prints that string, and on Android it
  answers the hardest question a transfer raises - `Exports` lives under
  `getExternalFilesDir()`, which the stock Files app has hidden since Android
  11. Kotlin hands the path down once at startup through a new
  `nativeSetHostExportsDir`, since the C++ cannot ask Android where that
  folder is. Visible to users once the disk images carry the `w8.com` that
  asks (`HBF_HOST_GETNAME`, 0xE8).
- **Deleted `emu_console_check_ctrl_c_exit()`.** v1.36 removed the declaration
  from `emu_io.h` and every other port has dropped its copy; this one had no
  caller, because CMakeLists does not compile `romwbw_emu.cc`. Dead code that
  looks like live ^C interception is a trap for the next person auditing that
  question.
- **`emu_console_check_escape()` takes the v1.36 contract and becomes a
  no-op.** It has no caller in this build either, and the old body popped the
  head of the input queue whenever it matched `escape_char` - with no test for
  `escape_char == 0`, which the contract defines as "reserve no key at all".
  The on-screen Ctrl button reaches the whole '@' to '_' window, so Ctrl+@ can
  queue a real NUL that a caller passing 0 would have eaten. Every Ctrl-letter
  belongs to CP/M.
- `emu_rename()` is deliberately *not* defined here, unlike the Windows port.
  It is declared in `emu_io.h` and defined in `emu_io_common.cc`, which this
  port does not compile - but nothing calls it either, because
  `emu_file_save()` is one of this port's stubs. Android writes through JNI.

### Fixed

- **`R8` imported the wrong file rather than admitting it could not find
  yours.** When the requested name was not in `Imports`, it fell back to *the
  first file in the folder* and handed that to CP/M under the name the guest
  asked for - and `R8` printed its usual success line, so the resulting CP/M
  file was real, plausible, and somebody else's contents. A name the user did
  type is never a request for a different file, and a miss is now reported. An
  empty name still means "no preference" and still takes the first file, which
  is what the older bare-FCB `R8` sends when the guest gave it nothing.
  Measured on the emulator: with a decoy file present, `R8 NOTTHERE.TXT` now
  logs "No file found in Imports folder" instead of importing the decoy.
- **F1 to F12 did nothing at all.** `handleKeyDown` had no case for them and
  `unicodeChar` is 0 for function keys, so they fell through to `else -> -1`
  and were dropped. They now send the VT220/xterm sequences every sibling port
  sends - F1-F4 as ESC O P..S, F5 and up as CSI n ~. Nothing on Android
  competes for them, so no setting gates them. Measured: F1 reaches CP/M as
  ^[OP.
- **A hardware Ctrl only made control bytes for A-Z.** Ctrl+[ (ESC), Ctrl+\
  (FS), Ctrl+] (GS), Ctrl+^ (RS), Ctrl+_ (US), Ctrl+@ and Ctrl+Space (NUL)
  produced nothing, because the test was a keycode range over the letters. The
  hardware path now accepts the same '@' to '_' window the on-screen Ctrl
  button already accepted - the software path being the better one was
  backwards. It asks the layout what the key would have typed rather than
  hard-coding keycodes, because the key carrying '[' is not
  `KEYCODE_LEFT_BRACKET` on every layout. Measured: Ctrl+] and Ctrl+\ reach
  CP/M as ^] and ^\.
- **TAB was dropped on the floor.** The normal-state parser handled ESC, CR,
  LF, BS and BEL and then printed anything from 0x20 up, so 0x09 matched
  nothing and vanished. Every program that lays out columns with tabs ran them
  together - including RomWBW's own boot banner, whose drive map is
  tab-indented, and `DIR`, whose four columns collapsed into a ragged line. It
  now advances to the next 8-column stop, the same rule as the other ports.
  Measured on the emulator: `DIR` comes out in columns and the drive map is
  indented.
- **The terminal bell stopped background audio instead of ducking it.** The
  tone was a bare `ToneGenerator(STREAM_SYSTEM)` with no `AudioAttributes`,
  and nothing anywhere in the app requested or abandoned audio focus - so a
  CP/M program that rings the bell, and some ring it per keystroke, killed
  whatever the user was listening to. It now declares itself a sonification
  and takes `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` for the length of the beep,
  giving it back straight after. The setting and its default (off) are
  unchanged, so this narrows a problem the setting had only hidden. This is
  the only sound the app can make: `emu_dsky_beep()` is an empty stub, so the
  HBIOS SND and DSKY paths are silent on Android.
- **A zero-byte export vanished.** `emu_host_file_close_write()` moved to
  `WRITE_READY` only when the buffer had bytes in it, so `W8` on an empty CP/M
  file told the guest it had succeeded and nothing appeared in `Exports`. An
  empty file is a real file - the CLI and Windows backends both create it, and
  `romwbw_emu` stopped dropping it in the browser backend for v1.36 - so the
  test is now on `HOST_FILE_WRITING` alone. Two other places had to move with
  it, because either would have swallowed the export by itself: the JNI
  `nativeGetHostFileWriteData` returned `null` whenever the byte count was
  zero, and `handleHostFileWrite` took `data.isEmpty()` as "nothing to write"
  and returned before creating the file. `emu_host_file_get_write_data()`
  returns `nullptr` for an empty buffer by contract, so the *state*, not the
  pointer, is now what says whether an export is waiting; `null` means there is
  none, and a zero-byte export arrives as a zero-length array.
  `ioscpm` had the same bug, in the same shape, in
  `emu_host_file_close_write()` - the two backends were written from the same
  buffering template - and it has since been fixed there too, in `15f48e9` on
  `origin/main`, which cites this commit.

  **Not built, unlike the rest of this section.** The machine this was written
  on has no SDK, NDK, Gradle or `javac`. What was done instead:
  `emu_io_android.cpp` was compiled for the host with `g++ -std=c++17` against
  a stub `jni.h`, the real `romwbw_emu` and `cpmemu` headers and the real core
  objects, and driven through a whole export. `W8` on an empty file reaches
  `WRITE_READY`, `emu_host_file_get_write_name()` still reports the full
  `Exports` path, the JNI hands back a non-null zero-length array, a two-byte
  export still carries its two bytes, and a guest path is still reduced to a
  leaf. The same program built against `9b68ab1` fails four of those checks,
  which is the regression this entry is about. The state test is
  `WRITE_READY` alone: asked mid-write, the JNI returns `null`. `9b68ab1` did
  not - with the test on the byte count, a call made while the guest was still
  handing bytes down returned a partial export as if it were a finished one.
  Nothing calls it there either - `checkHostFileState()` dispatches only on
  `WRITE_READY` (`MainActivity.kt:777`) - so it was latent both before and
  after, but the state test is where it stops being possible. The Kotlin line
  was read, not compiled.

- **A program that asked for blue got red, and one that asked for red got
  blue.** The SGR handler indexed the CGA palette with the ANSI colour number
  taken straight out of the escape sequence - `cgaColors[p - 30]` - and the two
  orderings are not the same list. ANSI counts the primaries red, green, blue;
  a CGA attribute byte counts them blue, green, red. They agree on black,
  green, magenta and light grey and disagree on the other four, so `ESC[31m` drew
  blue, `ESC[34m` drew red, `ESC[33m` (yellow) drew cyan, and `ESC[36m` (cyan)
  drew brown. The bright range was wrong in the same four places among
  `ESC[91m`-`ESC[96m` (91, 93, 94 and 96; 92 and 95 were already right), because it indexed the same palette with the same number
  plus eight. Any CP/M program that colours its own output was affected - a
  menu that draws its highlight in red came up blue - and the wrongness was
  stable rather than random, which is why it could sit here this long looking
  like a deliberate palette.

  A new `ansiToCgaIndex()` beside the palette converts at the parse site, and
  only there. It is a swap of bit 0 and bit 2 - `0->0 1->4 2->2 3->6 4->1 5->5
  6->3 7->7`, its own inverse - and the bright branch maps the low three bits
  before adding eight. `cgaColors` itself is untouched and stays in CGA order,
  which is the point: that order is a real attribute byte, the value a guest
  can hand down through HBIOS VDA to `emu_video_set_attr()`, and reordering the
  palette would have fixed the escape sequences by breaking that. Android
  stores that byte without drawing it - `g_text_attr` in `emu_io_android.cpp`
  carries a comment saying exactly that - but `ioscpm` and `z80cpmw` draw from
  it directly. The default attribute does not move: 7 maps to 7 and 0 maps to
  0. The `0x08` intensity bit is not a colour index and never goes through the
  function.

  The same bug, from the same assumption, was fixed in `ioscpm` and `z80cpmw`
  in the same session. `romwbw_emu`'s web frontend renders through xterm.js and
  has always read SGR as ANSI, so this is the three native ports catching up to
  it rather than a new convention. Built and watched on 2026-08-29; see
  **Verified** for what the screen showed.
- **The SGR background ranges did nothing at all.** Found by running the check
  above rather than by reading: `ESC[40m`-`ESC[47m` and `ESC[100m`-`ESC[107m`
  reached a `when` with no branch for them, there was no per-cell background to
  put them in, and `drawRow` skipped any cell whose character was a space - so
  even a stored background could not have been drawn. `ioscpm` hit the same
  thing from the other side in `0dbab43`, where `ESC[44m` then `ESC[2J` filled
  the screen red; here it filled it with nothing, and the bright block was
  literally invisible, black on black. `bgBuffer` and `historyBg` now sit beside
  `colorBuffer` and `historyColors`, the background index goes through
  `ansiToCgaIndex()` exactly as the foreground does, `39` and `49` reset each
  half, and `drawRow` fills a cell rect before it tests for a glyph. A cell
  holding `DEFAULT_BG` is still skipped, so a session that sets no colour costs
  what it always did.
- **Erases paint the current background**, which is what a strict VT does and
  what both siblings already did. `blankCells`/`blankRow` are this port's shape
  of `ioscpm`'s `blankCell`, and all eight fill sites go through them. That is
  the fix `ESC[44m` then `ESC[2J` was asking for. One latent crash went with it:
  `clearLineToBeginning` looped `0..cursorCol`, and `putChar`'s no-wrap truncate
  path can leave `cursorCol == cols`, so `ESC[1K` there indexed one past the row.
- **Ctrl+arrow sent the plain arrow.** `handleKeyDown` computed the modifier and
  then never consulted it: the four `KEYCODE_DPAD_*` arms matched on the key code
  alone and returned first, so Ctrl+Left was byte-for-byte a bare Left. They now
  send the xterm forms `z80cpmw`'s `Keymap.h` and `ioscpm`'s `KeyMap.swift` both
  send. Verified byte for byte on a device - see **Verified**.
- **`ESC[38;5;33m` set the foreground from the `33`.** The SGR loop acted on
  every parsed parameter in turn, so the sub-parameters of `38` and `48` were
  read as colour codes. Both forms are now consumed - `;5;<n>` and
  `;2;<r>;<g>;<b>` - and the value is discarded rather than approximated onto
  the CGA palette, because `z80cpmw` discards it too and a port that guessed
  would put a colour on screen that no sibling shows for the same bytes.
- **An empty CSI parameter shifted every parameter after it.** `ESC[;5H` was
  split on `;` and `mapNotNull`-ed, which dropped the empty field and read it as
  `ESC[5H`. An empty parameter means "use the default", not "not there". The
  parse now keeps position, and the ECMA-48 default of 1 is applied to the value
  - so `ESC[0A` moves by one and `ESC[0;0H` homes, where both were no-ops.
- **The CSI parameter buffer had no bound**, so an unterminated escape grew a
  `StringBuilder` without limit. Bounded at 16 parameters, 6 digits and 9999 per
  value, as the siblings are; the excess is dropped and the final byte still
  executes, because a CSI abandoned mid-flight prints its own tail as glyphs.
- **A reset carried the dead session's colour and parser state into the new
  one** - this port's shape of the ordering bug `ioscpm` fixed in `0165dac`, and
  newly able to bite now that a clear paints a background.
- **Home, End, PageUp, PageDown, Insert and Forward-Delete reached CP/M as
  nothing.** They now send the sibling byte sequences.
- **A disk download outlived the dialog that asked for it.** The read loop only
  notices cancellation at a block boundary and `Dispatchers.IO` does not
  interrupt, so leaving the Settings screen pulled the whole 49 MB anyway and
  fired the progress callback thousands of times for a transfer nobody would be
  shown. It also **refused nothing**: a connection dropped at 90% was renamed
  straight over the good copy and handed to the emulator as a bootable image.
  The size and the catalog's `sha256` are both checked before anything is
  replaced, the hash is computed from the bytes as they arrive rather than by
  re-reading 49 MB, and the scratch file carries a nonce - the old fixed
  `<filename>.tmp` was one name shared by every attempt, so two downloads of one
  disk truncated each other. The rename is also checked now; the old code
  deleted the destination first and then discarded `renameTo`'s result, so a
  failed rename left the user with no image at all and a `Result.success`.
- **Four HTTP call sites leaked their response** on a non-2xx status. A 404 is
  not hypothetical here - a whole release shipped with the catalog pinned to a
  tag that did not exist - so that is the arm that ran, and leaked, in the field.

### Added

- **Files can leave the sandbox, and arrive from another app.** This is parity
  item 4(a), the largest open Android item this repository had, and it is closed
  in both directions and verified on a device. Nothing about the sandbox itself
  moved: the guest still cannot name a path, `emu_host_path_caps()` reports what
  it always did, and `app/src/main/cpp` is untouched. What was missing was never
  the boundary, it was the UI on top of it.
  - **A File transfer screen** on the toolbar, listing both folders with sizes
    and dates, and per row **Save as...**, **Share** and **Delete**. It exists
    because `ACTION_VIEW` on a folder cannot work on Android 11+: the stock Files
    app does not show `Android/data` at all, so before this the only way to reach
    an export was a third-party file manager, which `README.md` told people to
    go and find.
  - **Save as** through `ACTION_CREATE_DOCUMENT`, behind a small custom
    `ActivityResultContract` because `ActivityResultContracts.CreateDocument`
    fixes the MIME type at registration and each row has a different one.
  - **Share** through a `FileProvider` scoped to `Imports/` and `Exports/` and
    deliberately not to the external-files root, which also holds the user's
    downloaded disk library.
  - **Import** through `ACTION_OPEN_DOCUMENT`, mangling the host name to R8's
    own rules from `path_to_fcb` in `r8.asm` - basename, drop leading dots, stem
    to the first dot and type from the last, 8 and 3, `fcb_char` per character.
    The point is that it is a fixed point: run R8's parser over the name this
    produces and the same name comes back, so what the toast says is what the
    user can type. It also makes both R8 vintages agree, which matters while the
    shipped images still carry the older one.
  - **`ACTION_SEND` / `ACTION_VIEW` on `ImportReceiverActivity`**, a separate
    no-UI activity rather than an intent-filter on `MainActivity`, because the
    native engine is a process-global singleton and a second `MainActivity`
    would re-init it underneath the running one.
  - **A non-`content:` Uri is refused** at the single line where a Uri from
    outside this app is dereferenced. `ImportReceiverActivity` is exported, and
    an intent-filter constrains only implicit intents and only the intent's data
    Uri - never `EXTRA_STREAM`, and not at all for an explicit intent - so
    without this any app could have made CPMDroid open its *own* private data
    with its own uid and copy it where `R8` can read it. Imports are bounded at
    16 MB, against a CP/M 2.2 slice that holds 8.
- **Help works offline, and stops being written for iOS.** The seven topics now
  ship inside the APK under `app/src/main/assets/help`, a fetched topic is
  cached, and the order is network, then cache, then the bundled copy - so a
  reader with no network gets the text where they used to get an error. The
  shared `release_assets/` topics were rewritten upstream in `ioscpm` `7569745`
  to stop assuming iOS, and this port's fork of the file-transfer topic
  (`78e6ec6`) goes away with them. Taking that text verbatim would have
  regressed things, so it is a merge: upstream's cross-platform structure, this
  port's Android facts, and four things fixed that were wrong in both - the boot
  unit is **2** and not 0, Settings cannot be opened while the emulator runs, the
  toolbar has no gear icon, and Ctrl+? does not give DEL here. The dead
  top-level `help/` directory, which `78e6ec6` called "bundled" and which nothing
  read, is deleted.

- **Terminal scrollback is a setting**, as it is on `z80cpmw`. It was
  hardcoded at 1000 lines with no way to change or disable it. The slider
  steps through 0 (Off), 100, 250, 500, 1000, 2000, 5000 and 10000, because
  the useful values are too far apart for a per-line slider. Lowering it trims
  the history at once rather than waiting for the next scroll, so a user who
  chooses Off does not keep a screen they can still drag back through.
- **Copy takes the scrollback with it.** `copyScreenToClipboard` copied only
  the live rows, which is the wrong half of what the user can see - the point
  of scrollback is the output that has already left the screen, and a long
  `DIR` is exactly what someone reaches for Copy to keep.
- A comment on `setupToolbar` recording that the toolbar is click-only
  deliberately: an ActionBar, a Toolbar with menu items or an options menu all
  switch on Android's `alphabeticShortcut` handling, which claims Ctrl-letters
  before the focused view sees them. That is the `^R` bug `z80cpmw` shipped,
  and every Ctrl-letter belongs to CP/M.

### The build

- **A POSIX `gradlew` is tracked at last, so this repository can be built off
  Windows.** `gradle/wrapper/gradle-wrapper.jar` and `.properties` have been
  tracked since the project started and pin Gradle 8.13, but only `gradlew.bat`
  was there to use them - every non-Windows contributor was stopped at step one
  of this repository's own top-priority item, which is building the un-built
  fix above. The script is Gradle 8.13's own `gradlew`, byte for byte apart
  from `DEFAULT_JVM_OPTS`, which follows `gradlew.bat`'s `"-Xmx64m" "-Xms64m"`
  rather than Gradle's own project setting, so the two scripts now agree about
  the JVM they launch. Mode 755, LF endings like the `.bat`. It is a new
  untracked file: `git add gradlew`, then check `git ls-files -s gradlew`
  reports mode `100755`, or every cloner gets a script they cannot run.
- **`gradle.properties` pinned `org.gradle.java.home` to an absolute Windows
  path**, `C:\Program Files\Android\openjdk\jdk-21.0.8`, in a tracked file, and
  had done since `f4fa7fe`, this repository's second commit. Gradle rejects it
  outright on any other host - *Value ... given for org.gradle.java.home Gradle
  property is invalid* -
  before it reads a single build script, so a POSIX `gradlew` on its own would
  have moved the wall one step rather than removed it. Found by running the new
  wrapper, not by reading it. The line is commented out with the reason beside
  it; per-machine JDK selection belongs in the user's own
  `~/.gradle/gradle.properties`. A Windows shell build now needs `JAVA_HOME`
  set instead - Android Studio picks its own JDK and never read this property -
  which is the one thing this change could break and is a `[WINDOWS]` item in
  `todo.txt` until someone confirms it.
- **`README.md`'s Build Steps never mentioned a wrapper**, because there was no
  usable one; they now give `./gradlew assembleDebug` and
  `gradlew.bat assembleDebug`, say that both read the properties file pinning
  8.13, and say that `cpmemu` and `romwbw_emu` have to be checked out *beside*
  this repository because `CMakeLists.txt` compiles the core in place and stops
  with a `FATAL_ERROR` if they are not.

### Docs

- **`WIP.md` still said the keyboard-aware scrolling and scrollback work was
  uncommitted**, and `todo.txt` sends the next person to `WIP.md` to resume it.
  It has been committed since `690da30` (2026-07-25), which is on `master` and
  on `origin/master`; the doc was telling the one person who might go and check
  the device that there was a working tree to protect. Corrected, with the
  baseline pointed at the current file rather than at `5ae1bdd` - `a523d40` and
  `9b68ab1` have both touched `TerminalView.kt` since. Its "TO DO on resume"
  list was stale in the same direction: the Settings entry for `scrollbackLines`
  and the Copy-takes-scrollback change are both done and are in this section.
  What is still open there is unchanged - nobody has watched it run.
- **`todo.txt` claimed an "Import File..." picker this app does not have.** The
  `W8`/`R8` sandbox item described arrival by staging as already covered by a
  picker, which made half of parity item 4(a) look done. There is no picker in
  the tree: no `registerForActivityResult`, `ACTION_OPEN_DOCUMENT`,
  `ACTION_CREATE_DOCUMENT`, `GetContent` or `DocumentFile` under
  `app/src/main/java`, and `AndroidManifest.xml` has only `MAIN`/`LAUNCHER`.
  `Import File...` is an `ioscpm` feature. The item now says both directions are
  open, which is what `z80cpmw`'s `FEATURE_PARITY.md` Android cell already said.
  The item stays open: a save-as and an import picker are still unwritten.
- **`todo.txt` is rewritten around what each of its items needs from a person.**
  Every item left in it needs a device, a publish, or the release order, and the
  file did not say so; it now has four headed sections that do. Nothing moved to
  this file - none of the five items is finished - and the corrections below are
  what the pass found rather than what it fixed.
- **The un-built change is the first thing in the file now.** Nothing in this
  tree has been through the NDK since `9b68ab1`, and the zero-byte export fix is
  `c06fa58`, which is `master` - so a compile failure in it is a compile failure
  at HEAD, and the next person to open this repository is the first who can find
  out. The entry now carries the route as well as the gap: the missing POSIX
  `gradlew`, which stopped a non-Windows host before anything else and has since
  been written and tracked - see **The build** above - `CMakeLists.txt`
  compiles the core in place
  from `../romwbw_emu/src` and `../cpmemu/src` so both must be checked out beside
  this repository, `ndkVersion` is `28.0.13004108`, and `assembleDebug` answers
  the compile question without a keystore. The status of the fix is unchanged: it
  is still not built, and **Verified** below still says so.
- **The `ioscpm` cite in that item pointed at a path that does not exist.** It
  read `emu_io_ios.mm:483`; the file is `iOSCPM/Core/emu_io_ios.mm`. Re-checked
  at `ioscpm` HEAD `6b1b731` on 2026-08-26: the bug is still there, and the entry
  now names `emu_host_file_close_write()` and the
  `HOST_FILE_WRITING && !g_host_write_buffer.empty()` test rather than a line
  number. That checkout also had an **uncommitted** fix in its working tree,
  citing `c06fa58`; the entry recorded it as uncommitted, not as done. It has
  landed since, in `ioscpm` `15f48e9` on `origin/main`, and the whole passage is
  out of `todo.txt` now - a sibling's closed bug is not this repository's open
  work. Worth knowing for the next person who greps that tree: its local branch
  is two commits behind, so the working copy still shows the old test.
- **The device item no longer sends the reader to `WIP.md`.** It said "resume
  from `WIP.md`", which is the file that had to be corrected last round for
  describing work as uncommitted. The entry is now self-contained: six numbered
  checks (fixed 24-row screen in both orientations, output that scrolls off, the
  drag gestures and the snap-back, the keyboard inset, the scrollback slider
  lowered with history on screen, and Copy with a non-empty history), the
  `adb logcat` filter and what the two tags print, and the tuning knobs listed as
  judgement calls rather than as work. The tablet's APK is explained instead of
  recommended: it is labelled versionCode 19 / 1.18 but was built from the fix,
  and it predates `a523d40`, `9b68ab1` and `c06fa58`, each of which touched
  `TerminalView.kt` or `MainActivity.kt`, so it is the wrong build to check
  against. The `1.18` CHANGELOG wording is marked as the owner's decision, since
  whether that entry describes the release or the tree is not a thing a checker
  can settle.
- **"The bundled disk images" describes an app this is not.** `cpmdroid` ships no
  disk image at all - `app/src/main/assets` holds `emu_avw.rom` and nothing else,
  and there is no `.img` anywhere in the tree. Every disk arrives through
  `DiskCatalogRepository`'s download from the pinned `ioscpm` release, so
  "refresh the bundled images" read as a change to make here when it is a release
  action in another repository. The item says so now. Nothing else in it moved.
- **The parity item was stale in both halves.** It credited `z80cpmw` `944cf9f`
  with settling the `c26aeb7` dispute and left "nothing reports the drift" as the
  open part. Both have moved: `5df0dee` (2026-08-26) found three `c26aeb7`-era
  claims still standing in `FEATURE_PARITY.md` outside the column it had swept -
  two in *Suggested priority order*, naming `buildKeyRow` and
  `TerminalView.sendNamedKey` as a worked example that has never existed here,
  and one in a row-4 bullet crediting this port with a share sheet and an in-app
  path display it does not have - and `tools/check-sibling-drift.sh` now exists
  there and reports the drift mechanically, including a recorded commit that is
  not an object in the tree it names. It reads only, so it is safe to run from
  here; on 2026-08-26 it reported this port drifted, read at `9b68ab1` against a
  HEAD of `c06fa58`. All thirteen Android cells of that snapshot table were
  re-verified against this source at `c06fa58` and every one reads true; the
  entry lists what was checked. What stays open is this repository's half:
  nothing here tells someone changing this tree that another repository's
  document describes it.
- **`todo.txt` cites symbols and greppable strings instead of `file:line`,** the
  convention `z80cpmw` wrote down in `5df0dee` after five of its own eight
  `file:line` cites were invalidated within twelve hours by its own next two
  commits. Worth recording that this file's four had *not* rotted:
  `DiskCatalogRepository.kt:27` still lands on `RELEASE_TAG`, `README.md:47` on
  the copy-into-`Imports` step, `ContentView.swift:231` on the `Import File...`
  label, and `emu_io_ios.mm:483` on the bad test - only its directory was wrong.
  That is the argument rather than an exception to it: they were right this
  morning and nothing on the page said so, which is what a line number cannot
  carry.
- **Nothing in this tree said that another repository describes it.** `z80cpmw`'s
  `FEATURE_PARITY.md` reads thirteen front-end features out of *this source* at a
  recorded commit, so a change made here goes stale there, and the person making
  it is the last one who could notice and the first one who does not. `README.md`'s
  Related Projects section now opens with what that column is, which of this
  port's features are rows in it, and the read-only drift reporter
  (`sh ../z80cpmw/tools/check-sibling-drift.sh`) that says how far the reading
  has fallen behind. The two files that back the most rows carry a one-line
  header saying so: `TerminalView.kt` (rows 1, 2, 3, 9 and 13) and
  `data/DiskCatalogRepository.kt` (row 5, the pinned `RELEASE_TAG`). Row 6 is
  deliberately not claimed on the latter - help fetching stays on
  `releases/latest` in `HelpActivity`. Those two comment lines are the only
  change to any source file in this pass, and they were not compiled.
- **`todo.txt` is 228 lines down to 79, and the checks a person has to run by
  hand are in a new `MANUAL_CHECKS.md`.** The file had been growing by closing
  items: every finished thing left behind a paragraph explaining that it was
  finished, longer than the item had been, and roughly two lines in three were
  not open work. Deleted outright: the `ioscpm` zero-byte paragraph (that bug is
  fixed, see above), the whole `c26aeb7` / `FEATURE_PARITY` history (settled in
  `z80cpmw` `944cf9f` and `5df0dee`), the thirteen-cell re-reading of the parity
  table (a reading, not a task, and duplicated in this section), a frozen
  snapshot of the drift reporter's output (already one commit stale when it was
  written), and the second half of the `W8`/`R8` item (a post-mortem of a
  correction that is in `c06fa58`'s message and in this section). What survives
  is five items, each tagged with what the machine picking it up has to have -
  `[ANDROID]`, `[WINDOWS]`, `[ANDROID DEVICE]`, `[DECISION]`, `[RELEASE]` - so
  the next session on another OS can see at a glance what it can take. The two
  device checklists moved to `MANUAL_CHECKS.md` in full, in the order they should
  be run and with what right looks like at each step; that file says at the top
  that a check is deleted once someone runs it, and the result goes under
  **Verified** here.
- **The 2026-08-29 round applied that rule to itself.** Three checks were run,
  so all three are gone from `MANUAL_CHECKS.md` and their results are under
  **Verified**; what is left there is four checks an emulator could not make -
  the tablet with its third-party IME, the four feel questions, a share
  completed to a real recipient, and a share received from a real app.
  `todo.txt` loses the three build items and the two `[DECISION]` items that
  round settled, and gains what it found: the drift in
  `android_host_path_basename()` away from the shared original it promises to
  track, `emu_host_file_get_read_name()` answering the asked-for name rather
  than the opened one, and the list of terminal sequences that are absent rather
  than wrong. Two divergences are now marked **deliberate** in that file, with
  the reason, so a future cross-port sweep stops reporting them: SGR 0 resetting
  to green, and `ESC[104m` staying bright.
- **`README.md` told the user to do the one thing Android 11 prevents** - "copy
  file to Imports folder using a file manager" - which the app's own help topic
  already contradicted. Rewritten around the File transfer screen, with the
  hand-staging route kept as the fallback it now is. Three other claims went
  with it: the terminal is no longer advertised as running "Zork, WordStar,
  etc." (nobody measured that, and `todo.txt` lists what the parser does not
  have), the help system is no longer described as download-only, and the build
  requirements said NDK 27 and Android Studio Hedgehog where the tree pins NDK
  `28.0.13004108`, `compileSdk` 36 and JDK 21.

### Verified

**Read this section top to bottom rather than by bullet.** Three earlier passes
wrote here that the zero-byte export fix and the ANSI colour fix "were not built
and not run", and each was honest when it was written - the machines those
passes ran on had no Android SDK. The 2026-08-29 round had one. Those statements
are superseded and are not repeated below; what replaces them is not "it
probably works" but a list of things that were watched happening.

**What the 2026-08-29 round had.** Windows 11, JDK 21.0.8, Android SDK with NDK
`28.0.13004108`, and the `Medium_Phone_API_36.1` AVD. Every claim below was made
against a build produced that day from this tree.

#### The build

- **`./gradlew clean assembleDebug` succeeds from scratch: 52 tasks executed,
  zero up-to-date.** The native library compiles for all four ABIs -
  `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` - which means `emu_io_android.cpp`
  and the seven core files `CMakeLists.txt` pulls out of `../romwbw_emu/src` and
  `../cpmemu/src` were all compiled, at `-Wall -Wextra`, against those siblings'
  current heads. **Not one native warning.** The three warnings the build does
  emit are `javac`'s, about `source`/`target` 8 being obsolete, and predate this
  work.
- That settles a question `romwbw_emu`'s `todo.txt` asks of its downstreams: the
  `qkz80_MK_INT16` narrowings in `cpmemu`'s `qkz80_reg_pair.h` are **invisible
  here**. They are what `-Wshorten-64-to-32` and `-Wconversion` find, and neither
  is in `-Wall -Wextra`, which is what this port compiles with.
- **A Windows shell build works with `JAVA_HOME` set**, which is what `d4ee298`
  claimed when it commented out `org.gradle.java.home`. That line had pinned an
  absolute Windows JDK path in a tracked file and Gradle rejected it on every
  other host before reading a build script. Confirmed on the platform that
  originally needed it.

#### The zero-byte export (`c06fa58`)

Run in full, in CP/M 2.2 booted from `hd1k_combo.img`. **Boot unit 2, not 0** -
units 0 and 1 are the RAM and ROM disks and booting `0` prints
`*** No system image on disk`. That cost this round fifteen minutes and is now
in the help topics.

- `SAVE 0 EMPTY.TXT` then `DIR EMPTY.TXT` shows `A: EMPTY    TXT`.
- `W8 EMPTY.TXT` prints `Done: 0 bytes` and the full destination path, and **a
  real zero-byte `empty.txt` exists in `Exports/`**. That is the fix: the old
  code only reached `HOST_FILE_WRITE_READY` when the buffer had bytes in it, so
  the guest was told about a file that was never created.
- The regression half holds. `W8 R8.COM` prints `Done: 1792 bytes` and the host
  file is exactly 1792 bytes - the state test that replaced a byte-count test
  did not break a normal export.

#### ANSI colour, and the half nobody had looked for

- **Foreground is correct, and was watched.** A file of SGR escapes was staged
  in `Imports/`, imported with `R8`, and `TYPE`d - which is how an ESC byte gets
  past the CCP, and is worth writing down because the check had been open for
  want of a way to do it. All of `ESC[30m`-`ESC[37m` and `ESC[90m`-`ESC[97m`
  render correctly on screen: `31` red, `33` brown, `34` blue, `36` cyan, and the
  four that were already right unmoved. `ansiToCgaIndex()` is confirmed.
- **The backgrounds were not wrong; they did not exist.** `ESC[40m`-`ESC[47m`
  and `ESC[100m`-`ESC[107m` reached a `when` that had no branch for them, and
  `drawRow` skipped any cell whose character was a space, so a background could
  not have appeared even if it had been stored. On screen the bright block was
  *invisible* - black text on a background that never painted. This is the same
  bug `ioscpm` fixed in `0dbab43`, presenting differently: there the byte was
  stored in the wrong order and filled the screen red, here there was no byte.
- After the fix, watched again: every background renders and the ANSI-to-CGA
  conversion is right in both directions - **`41` is red and `44` is blue**, not
  swapped - and the bright range `100`-`107` is visible and correct.
- **The case the whole cross-port thread was about now behaves.** `ESC[44m`
  followed by `ESC[2J` fills the live 24-row screen solid blue, including rows
  that hold no text; `ESC[41m` followed by `ESC[K` erases to end of line in red
  across the full width.
- **The default look did not move.** A session that has never seen an SGR
  sequence is green on black exactly as before. `DEFAULT_FG` is still
  `Color.GREEN`, and a cell holding `DEFAULT_BG` is skipped by the same fast
  path as before, so the common case costs what it used to.

#### Keyboard and scrollback (`690da30`), first time anyone has watched it

On the AVD, portrait and landscape. The tablet half is still open and is now
check 1 of `MANUAL_CHECKS.md`.

- Fixed 24-row live screen, prompt at the bottom, content filling the view.
  **No black void below the content** - the bug this design replaced. Rotating
  to landscape resets `fullHeight` and re-sizes from the full height:
  `rows=24, cols=160`, prompt still visible, content still filling.
- Drag DOWN pages back into history and reveals output that had scrolled off;
  drag UP snaps back to live; a tap with no drag raises the keyboard.
- With the keyboard up: `ime.bottom=1006`, view height 1089 against a
  `fullHeight` of 2032 kept from before, `rows=24`, and the font **not** shrunk
  (29.5 either way). The prompt stays visible.
- The Settings scrollback slider runs 0 (Off) to 10000. Set to **Off** it clears
  the history: a drag afterwards reveals nothing and the view is not left
  scrolled past the end.
- `copyScreenToClipboard` prepending history is read from the source, and the
  history was confirmed non-empty on the device by dragging into it. **The
  clipboard itself was not read back** - this system image has no
  `cmd clipboard`, and `dumpsys clipboard` returns nothing.
- Settings cannot be opened while the emulator runs; the tap raises
  `Stop emulator before changing settings`. That is deliberate and is now said
  in the help topics, which previously told the reader to go to Settings without
  mentioning it.

#### Ctrl+arrows, byte for byte

At the CP/M prompt, where the CCP echoes `ESC` as `^[`, so the two cases are
distinguishable on screen. Plain Left produces `^[[D`; Ctrl+Left produces
`^[[1;5D`. That is the xterm form `z80cpmw`'s `Keymap.h` and `ioscpm`'s
`KeyMap.swift` both send. Before this round the four `KEYCODE_DPAD_*` arms
matched on the key code alone and returned before the modifier was consulted, so
Ctrl+Left was byte-for-byte a bare Left.

#### File transfer (parity item 4(a)), both directions

- **Out of the sandbox.** File transfer > `r8.com` > **Save as...** opens the
  system document picker with the name pre-filled; saving to `Downloads` puts a
  file there whose md5 equals the one in `Exports/` and whose length is 1792.
  A file written by `W8` inside CP/M has left the app's storage entirely, which
  is what item 4(a) asked for and what no version of this app could do before.
- **In through the picker.** `Import file...` then a file named
  `My Long Archive.tar.gz` - a name CP/M cannot express - lands in `Imports/` as
  **`my-long-.gz`**, and then `R8 MY-LONG-.GZ` inside CP/M prints
  `Creating: MY-LONG-.GZ` and `Done: 30 bytes`. The mangling is a fixed point
  under R8's own `path_to_fcb`, which is the property that matters: what the app
  says the file is called is what the user can type.
- **The share sheet opens** with a valid
  `content://com.awohl.cpmdroid.fileprovider/...` Uri, no `SecurityException`,
  and offers Quick Share, Chrome, Drive, Messages **and CPMDroid itself** - the
  last of those being `ImportReceiverActivity`'s intent-filter, so the app is a
  share target as well as a share source. No share was completed to a real
  recipient; an emulator has no account signed in. That is check 3 of
  `MANUAL_CHECKS.md`.
- The share sheet's own log asked for `Intent#setClipData()` so it could read
  the file for a preview; it now gets one.
- **The confused-deputy hole was found by review and closed, and the attack was
  then run against the fix.** `ImportReceiverActivity` is exported, and an
  intent-filter constrains only implicit intents and only the intent's data Uri
  - never `EXTRA_STREAM`, and not at all for an explicit intent. So any app
  could have named a `file://` Uri and had this app open its *own* private data
  with its own uid and copy it into `Imports/`, where `R8` can read it. Running
  that exact intent against the fixed build logs
  `W HostTransfer: Refusing a non-content Uri: file` and **nothing appears in
  `Imports/`**. Imports are also bounded at 16 MB now, against a CP/M 2.2 slice
  that holds 8.

#### Help, with the network switched off

`svc wifi disable` and `svc data disable`, then Help from the toolbar. The index
lists all seven topics and opening one shows its text, where before this round
both would have been an error - `HelpActivity`'s only source was HTTP. The index
also reads **"Getting started with CPMDroid"**, which is the shared asset no
longer calling itself iOSCPM. The eight files are in the APK under
`assets/help/`, byte-identical to the copies in `release_assets/`, so a reader
offline and a reader online see the same document.

#### What is still not verified

- **`HBF_HOST_CAPS` and `HBF_HOST_GETNAME` reaching a guest.** Unchanged: the
  disk images this port downloads carry the pre-`98eb6a1` `w8.com`, which
  neither probes nor asks. The refreshed image in `romwbw_emu/disks/` does, and
  `todo.txt` now says to push it at a device rather than wait for the release.
- **The tablet**, and its third-party IME. See `MANUAL_CHECKS.md` check 1.
- **Receiving a share from a real app.** The receive path was exercised only by
  an explicit `am start`, which is the attacker's shape and not the user's.
- The **download** size/`sha256` refusal is compiled and was not made to fire.
  Forcing it needs a deliberately corrupt or truncated asset, which means
  standing something up to serve one.

### Emulator core v1.35, and the refreshed ROM

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

**Drafted without a toolchain, shipped with one.** No Android SDK, NDK or JDK
was available when this was written; the C++ was reviewed and type-checked
against the real core headers with clang, but neither Gradle nor the NDK had
compiled it. That gap is closed: it was built with the NDK and run on an API 36
emulator, and it ships to users as part of 1.22.

## Version 1.18 (versionCode 19)

- Version bump for a fresh Google Play submission. Already targets Android 16 (API 36), which meets Play's Aug 31 2026 target-API requirement (min is API 35).
- The pinned ioscpm `v1.4.5` disk catalog is now published upstream, so the downloadable disk list loads (that release was missing when 1.17 was built, and the catalog fetch returned HTTP 404). No app code changes since 1.17.
- **That last sentence describes the published APK, and the owner settled on 2026-08-29 that this is what the entry is for.** It was queried because it is false of the source tree: `690da30`, the keyboard-aware scrolling and scrollback work, sat on `master` under this version number for a time. The shipped 1.18 binary was built from `5ae1bdd`, before that commit, so the sentence is exact about what users received. `690da30` belongs to the **1.22** section above, where it now appears - it was written here when that section was still headed *Unreleased*, and the pointer moved with the rename. It had never been in a published build when this was written; it is an ancestor of `v1.24`, so whether a store install is still the pre-scrollback terminal now depends on which of 1.22 and 1.24 actually reached users, which this file cannot settle.

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

## Version 1.13 (versionCode 14)

Recorded late — this release shipped in January 2026 and was never written up
here. The items came off `todo.txt` in the cleanup that added this entry.

- **The Boot/Restart button now asks first.** It opens a "Restart Emulator"
  confirmation with a Cancel, so a tap meant for the neighbouring Pause button
  no longer resets the machine and loses in-progress work. A user reported
  exactly that. There is no unconfirmed restart path left — the dialog is the
  only caller of `bootEmulation()`.
- **Fixed the downloaded-disk write warning checkbox reading as off on a clean
  install.** The preference was stored as a negative
  (`manifest_write_warning_suppressed`, default `false`) behind a checkbox
  labelled "Suppress downloaded disk warnings", so a fresh install showed it
  unchecked and looked as though the protection were off. A user reported
  exactly that. The warning itself was active either way — only the wording was
  inverted. The key is `warn_manifest_writes` now, defaults to **on**, and the
  checkbox reads "Warn when writing to downloaded disks", so a ticked box
  matches the behaviour.
- Added a stored settings version (`prefs_version`) and a `migrateIfNeeded()`
  run from `MainActivity.onCreate`, so a later default change can be pushed out
  to existing installs. It does not act on the old key: the migration removes
  `warn_manifest_writes`, which an install upgrading from 1.12 does not have
  yet, so it is a no-op on that path. Those installs land on the corrected
  default because the renamed key is no longer read; the old
  `manifest_write_warning_suppressed` entry is left orphaned in
  SharedPreferences.
- **Added an "Enable terminal bell sound" setting, off by default.** The BEL
  character used to beep unconditionally, which paused whatever the device was
  already playing; with the setting off no audio object is constructed at all,
  so background audio and audiobooks keep running. Note this only narrows the
  problem — see `todo.txt` for the audio-focus half, which is still open.
- Documented the RC2014 MIDI module research in `docs/midi.md`: the Mk2 module
  has no clock of its own, so MIDI timing still falls to the emulated CPU (or a
  virtual CTC) and cannot be handed off to the hardware. The write-up also
  splits the work into what belongs in the shared `hbios_dispatch` and what is
  Android-only, with effort estimates.

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
