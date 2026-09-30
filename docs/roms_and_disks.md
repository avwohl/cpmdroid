# ROMs and Disk Images

**Nothing is bundled.** The APK carries the emulator core and an offline copy
of the help topics; it carries no ROM and no disk image.

Both come from the interface-v0 catalog in
[romwbw_disks](https://github.com/avwohl/romwbw_disks). The only content
address compiled into the app is the index that catalog starts from
(`DEFAULT_INDEX_URL` in `data/SettingsRepository.kt`) - one URL for the whole
app, help included, since 1.30. There is no pinned release tag: the index names
every published RomWBW release and points at that release's own catalog, that
catalog publishes a `base_url`, and every asset is that `base_url` plus a
filename, so nothing is assembled here from a version number. Everything
fetched is measured against the size and SHA-256 the catalog publishes, and
bytes that do not match are not kept.

Three things are yours to choose, all in Settings, in the order the screen
shows them:

- **ROM** - the ROMs the selected release publishes. What is stored is the
  catalog's ID rather than a filename, because the filename carries the release.

- **RomWBW Release** - every release the index publishes, with none held back.
  The emulator core has no list of releases to check one against: what it
  depends on is the interface the catalog versions in its own name, v0, so a
  release a v0 index publishes is one this app can boot. A release published
  later will not switch a machine by itself, since that would change its disk
  set and its NVRAM namespace underneath it; new ROMs and disks *within* the
  selected release arrive with no app update. Each release keeps its own disk
  slots, NVRAM and ROM, so switching is a round trip that loses nothing.

- **Catalog Index** - which catalog everything else comes from. Empty is the
  default; a URL here points CPMDroid at another index, and the release list,
  the ROM list, the disk catalog and the in-app help all follow it. Apply
  fetches it and says what it found rather than storing a URL whose first test
  would be a machine that will not start. Each index keeps its own disks, ROM,
  NVRAM and boot config. `$ROMWBW_INDEX_URL` overrides it for the run, and the
  field says so and is disabled while it is set.

What disks exist, and what each one is licensed under, are questions for the
selected release's catalog - the app shows both, and the list changes from one
release to the next. Between them the published releases carry CP/M 2.2,
CP/M 3, ZSDOS, ZPM3, NZCOM and QPM, games, Infocom adventures and language
toolchains.

Downloaded ROMs and images are stored in app-specific storage and work offline.
The exception is a fresh install: there is no ROM in the package, and a ROM
cannot be verified without the catalog that publishes its size and hash, so the
first launch needs one successful fetch. The app says so, with a Download
button, rather than starting a machine on bytes it cannot check.
