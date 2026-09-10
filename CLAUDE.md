# cpmdroid

The Android client. Kotlin plus one C++ file, one Gradle module (`:app`),
`applicationId com.awohl.cpmdroid`. **The default branch is `master`**, not
`main` like its siblings.

`DiskCatalogRepository.kt` has cited this file since before it existed. It is
here now.

## It compiles its siblings, by relative path

`app/src/main/cpp/CMakeLists.txt` reaches five levels up:

    ROMWBW_EMU_SRC  ../../../../../romwbw_emu/src
    CPMEMU_SRC      ../../../../../cpmemu/src

Not a submodule, not symlinks, not vendored copies - the sibling checkouts
themselves, compiled in place. `hbios_dispatch.cc`, `hbios_cpu.cc` and
`emu_init.cc` come from romwbw_emu; `qkz80*.cc` from cpmemu. Missing either is
a `FATAL_ERROR`, which is the only guard there is.

**So a change in romwbw_emu or cpmemu changes this app**, with nothing here to
review it. The one file this repository owns is
`app/src/main/cpp/emu_io_android.cpp`, the JNI bridge.

## A green build proves almost nothing

Four gaps, and they compound:

- `isMinifyEnabled = false` for release, so R8 checks no native binding.
- The NDK build cannot see the Kotlin and kotlinc cannot see the C++.
- A JNI name that does not match is an `UnsatisfiedLinkError` at the first
  call **on a device**, not at build time. Past the *name*, nothing is checked
  at all: the translation unit has only ever been `-fsyntax-only` compiled, so
  a descriptor mismatch surfaces nowhere earlier.
- **Nothing in CI compiles this app.** The one workflow
  (`.github/workflows/store-version.yml`) runs `tools/check-store-version.sh`
  daily and needs no toolchain. `./gradlew :app:test` is opt-in and the
  README's build steps do not invoke it.

`JniNameParityTest` is what stands in for all of that: it greps both source
files and compares the `native*` / `Java_com_awohl_cpmdroid_EmulatorEngine_*`
sets in both directions, with a guard-on-the-guard asserting both lists are
non-empty - because a regex that stopped matching would pass by comparing two
empty sets. Run `./gradlew :app:test` before believing a JNI change.

Two build-file decisions that are traps if reversed, both in
`app/build.gradle.kts`: `testImplementation("org.json:json:20231013")` shadows
android.jar's stub, and `testOptions.unitTests.isReturnDefaultValues` is
deliberately **never** set - with it on, losing that dependency would make every
`JSONObject` call return a default and the parser tests would pass while parsing
nothing.

## One index URL, and it is not in the file that fetches

    SettingsRepository.DEFAULT_INDEX_URL        the only literal
    DiskCatalogRepository.INDEX_URL             a re-export of it

One literal, in the file that owns the setting which can replace it. Two copies
would be two sources of truth about which catalog is in play. It resolves
`$ROMWBW_INDEX_URL` -> the stored `catalog_index_url` -> the default, the same
precedence `romwbw-get` uses.

It names **no release tag** - `releases/latest/download/` - and
`IndexUrlTest.theDefaultUrlNamesNoReleaseTag` asserts that. Note that this test
carries fixture URLs on a deliberately fake fork (`github.com/someone/...`);
`tools/check-shipped-disks.sh` once fetched one of them and reported this port's
index unreachable.

Everything is verified: the catalog document against the index's size and
sha256 **before parsing**, disks and ROMs on download, ROMs **again on every
load** against a stored `RomClaim`, and help topics against the index's help
block. Reads are bounded at 1 MB against the declared length *and* the running
total, because a chunked response declares nothing.

## Things that look like bugs and are not

- **`indexScope` is empty for the default catalog** and `"@" + fnv1a32(url)`
  for any other, appended to every per-release preference key and to the
  storage folders. Empty-for-default is load-bearing: making it uniform would
  strand a user's library behind a key nothing reads. The hash is the same
  function, folded the same way, as ioscpm's Swift and z80cpmw's C++.
- **`V0_LEGACY_ROMWBW = "3.5.1"`** is a historical anchor, not a default. It
  was renamed from `V0_BUNDLED_ROMWBW` precisely so nobody "corrects" it to
  3.6.0. Moving it strands everything under `.v0.3.5.1`.
- **`generation` is recorded and acted on in no way.** The obvious use -
  delete the images the catalog names so they are fetched again - is the iOS
  bug this app must not acquire: the same code there, keyed on one global
  catalog version, wiped a user's library on every version switch.
- **A migrated device comes up on 3.5.1 and moves to 3.6.0** the first time a
  catalog is read, with four empty slots. That is the design working.

## Standing prohibitions

- **No ROM and no disk image belongs here.** `.gitignore` refuses `*.rom` and
  `*.img` unanchored, with 35 lines of why: the ROM that was deleted hashed to
  exactly what the catalog publishes, so it had not drifted - and nothing in
  the tree could ever have said which day that stopped being true.
- **Never fall back to another release's ROM.** Falling back recreates the
  version mismatch invisibly.
- **Never migrate `rom_name`.** "Rename every stored bare filename" is the
  natural reading and the one that bricks the app; `V0MigrationTest` asserts
  ROM names are refused.
- **Never delete a ROM**, including a bad one - re-download and rename a
  verified transfer over it.
- **Store the catalog ID, never the filename.** A sibling port seeded a
  filename into that field and corrupted the preference on OK.
- **Never branch on the index's `notes` field.** The published 3.6.0 note still
  describes a compile-time pin the core stopped having in v1.39.
- **Parse per entry, tolerantly.** One bad entry must not take the document
  away; `HelpActivity.parseHelpIndex` is the in-tree anti-precedent.
- **There should never be a `DISK_NAMES_MIGRATION_PASS` 3.** If one seems
  needed, something started writing pre-v0 names again and that reason matters
  more than the sweep.

## Releasing

**Uploaded is not released, released is not rolled out, and rolled out is not
installed.** Nothing in this tree may say it shipped until Play serves it, and
`tools/check-store-version.sh` - the one thing CI runs - is what measures that.
Do not pre-emptively write a versionCode into the changelog to save a trip;
that is the exact failure the script was built to catch, in z80cpmw, where a
changelog wrong by two releases had already sent a re-read to the wrong commit.

A versionCode costs nothing; a record that says the wrong thing does. Take a
new one per *described* build. Play refuses an upload at a versionCode it has
merely SEEN, test tracks included - check the App bundle explorer before
reusing one.

`./gradlew :app:bundleRelease` is what Play takes. The README's
`assembleRelease` APK is for sideloading; uploading one gets a rejection rather
than an error worth reading.

Signing credentials resolve from **outside** the checkout. A release build with
nothing resolved comes out **unsigned and still succeeds** - run
`./gradlew :app:signingReport` and check the alias and SHA-256 rather than
trusting exit 0.

## What z80cpmw says about this repo

`z80cpmw/FEATURE_PARITY.md` carries an Android column describing this code from
outside it, gated by z80cpmw's own CI. Touching the terminal parser, key
handling, Imports/Exports, the catalog client, help fetching, NVRAM autoboot,
the font/scrollback settings or the Dazzler/DSKY stubs means that column needs
re-reading - **and correcting it is an edit in z80cpmw**, not here. Its
`shipped:` field is compared against what Play serves, so it goes red when this
app ships and that line is not updated.

## Documentation discipline

`MANUAL_CHECKS.md` holds checks nobody has run yet - **delete one once it has
been run**; the result belongs in `CHANGELOG.md` under **Verified**.
`todo.txt` holds open items only, tagged `[ANY]`, `[ANDROID]`,
`[ANDROID DEVICE or EMULATOR]`, `[RELEASE]`, `[PARITY]`, `[DELIBERATE]`; items
marked `[DELIBERATE, do not report this as a gap again]` mean it. Cites name a
symbol or a greppable string, not a file:line - a line number reads as evidence
while carrying none.

Commit messages here carry a **NOT COMPILED / NOT BUILT / NOT RUN** disclosure
naming exactly what was and was not exercised. Keep doing that; on a machine
with no Android SDK it is the only honest thing to write.

`docs/release_notes.txt` stops at 1.11 and `docs/bug1.txt` is an old user
report. Neither is a current record. `CHANGELOG.md` is.
