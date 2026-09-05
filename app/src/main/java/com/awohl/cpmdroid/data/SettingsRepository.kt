package com.awohl.cpmdroid.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File

class SettingsRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "cpmdroid_prefs"
        // The BUNDLED ROM's asset name, and nothing else. It names a file
        // inside the APK, opened with assets.open(), which is why the v0 rename
        // pass refuses it: a v0 name here shows "ROM not found" on every launch
        // with no way back, the Settings row being read-only. Fetching a
        // release's ROM from the catalog does not change what this key means -
        // a downloaded ROM is named by the catalog and remembered under the
        // per-release keys below, so this one still says what the package
        // carries, and no migration is needed for it.
        private const val KEY_ROM_NAME = "rom_name"
        private const val KEY_DISK_SLOT_PREFIX = "disk_slot_"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_WRAP_LINES = "wrap_lines"
        private const val KEY_SCROLLBACK_LINES = "scrollback_lines"
        private const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"
        private const val KEY_WARN_MANIFEST_WRITES = "warn_manifest_writes"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_PREFS_VERSION = "prefs_version"
        private const val CURRENT_PREFS_VERSION = 3
        private const val KEY_NVRAM = "nvram"

        // Flag for the v0 rename pass, per (interface, RomWBW version) because
        // that is what makes a stored disk name mean anything: the pass maps
        // names to one release, and a build that ever maps them to another has
        // a different question to answer and gets its own key.
        //
        // Deliberately NOT a CURRENT_PREFS_VERSION bump, although this is where
        // every earlier migration went. That stamp is one number for all of
        // them, and version 2's step removes warn_manifest_writes to put it back
        // to its default; declining to write the stamp - which this pass must be
        // able to do, since a rename can fail and it has to be asked again next
        // launch - would re-run that removal on every launch and take the user's
        // choice away each time. This is the first migration here that can
        // legitimately not finish, so it is the first that needs a flag of its
        // own.
        private const val KEY_DISK_NAMES_MIGRATED_PREFIX = "disk_names_migrated.v0."

        /**
         * Which run of the rename pass this build is, and why there is a second
         * one.
         *
         * Release A shipped the pass and set its flag. Release A did NOT change
         * any URL, so between A and B this app still downloaded pre-v0 names -
         * a user who fetched a disk under 1.26 has `hd1k_cpm3.img` on disk and
         * in a slot, acquired *after* the flag said the migration was done.
         * Nothing loses data: the file is there under the name the slot gives,
         * so it boots. What it does is disappear from the catalog, whose entry
         * is now `hd1k_cpm3-v0-3.5.1.img`, so its downloaded tick goes and the
         * user is invited to fetch 8 MB they already have.
         *
         * So the flag gets a pass number and this release runs the pass once
         * more. Re-running costs nothing - it is idempotent by construction,
         * leaves names that already carry `-v0-` alone, and renames only the
         * twenty stems the catalog has ever published. There is no third pass:
         * after this release every downloaded name is a v0 one, because the
         * catalog it downloads from only publishes those.
         */
        private const val DISK_NAMES_MIGRATION_PASS = 2

        // Flag for the one-shot copy of the pre-v0 single-release state into
        // the bundled release's namespace. Separate from the rename flag above
        // because it can succeed on a launch where the rename cannot: the copy
        // touches only preferences, so external storage being unavailable does
        // not stop it, and the slots must reach their new keys either way or
        // every user's configured disks read as empty.
        private const val KEY_NAMESPACE_SEEDED_PREFIX = "per_version_seeded.v0."

        /**
         * The RomWBW release whose disks and NVRAM this app is showing.
         *
         * Not per-release itself - it is the pointer, and there is one of it.
         * Defaults to the release the bundled ROM was built for, which is the
         * only one a fresh install can boot.
         */
        private const val KEY_SELECTED_ROMWBW = "selected_romwbw.v0"

        private const val DEFAULT_ROM = "emu_avw.rom"
        private const val DEFAULT_FONT_SIZE = 14
        private const val DEFAULT_SCROLLBACK_LINES = 1000
        // The seek bar cannot offer every value, so it offers these. 0 is
        // "off", which the terminal already understands.
        val SCROLLBACK_CHOICES = intArrayOf(0, 100, 250, 500, 1000, 2000, 5000, 10000)

        /**
         * The suffix that scopes a key to one (interface, RomWBW release).
         *
         * Everything whose validity depends on the release goes behind one of
         * these. A 3.5.1 disk under a 3.6.0 ROM makes the guest print
         * `*** WARNING: HBIOS/CBIOS Version Mismatch ***`, and an NVRAM setting
         * means a different thing under each release - "0.5" is the WordStar 4
         * slice under 3.5.1 and the 'wp' slice under 3.6.0, because upstream
         * renamed it. Sharing one key across releases would not corrupt
         * anything; it would quietly do the wrong thing.
         */
        private fun scope(romwbwVersion: String) = ".v0.$romwbwVersion"

        private fun diskSlotKey(slot: Int, romwbwVersion: String) =
            KEY_DISK_SLOT_PREFIX + slot + scope(romwbwVersion)

        private fun nvramKey(romwbwVersion: String) = KEY_NVRAM + scope(romwbwVersion)

        private fun generationKey(romwbwVersion: String) =
            "catalog_generation" + scope(romwbwVersion)

        // The catalog's claims about the ROM fetched for one release. Three
        // keys rather than one blob because they are read on the launch path,
        // where a parse failure would have to be handled as "no ROM" anyway.
        private fun romFileKey(romwbwVersion: String) = "rom_file" + scope(romwbwVersion)

        private fun romSizeKey(romwbwVersion: String) = "rom_size" + scope(romwbwVersion)

        private fun romSha256Key(romwbwVersion: String) = "rom_sha256" + scope(romwbwVersion)
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The RomWBW release currently selected, defaulting to the bundled one.
     *
     * Read on every slot and NVRAM access rather than cached, so that a change
     * made on the Settings screen is visible to the next read from anywhere -
     * including MainActivity.onResume, whose whole job is to notice that the
     * slots are not what they were when it paused.
     */
    fun selectedRomwbwVersion(): String =
        prefs.getString(KEY_SELECTED_ROMWBW, null) ?: V0_BUNDLED_ROMWBW

    /**
     * Select a RomWBW release.
     *
     * Nothing is deleted here and nothing is copied between releases. The
     * previous release's slots, NVRAM and downloaded images all stay exactly
     * where they are, which is what makes 3.5.1 -> 3.6.0 -> 3.5.1 a round trip
     * rather than a loss - the mistake this app is deliberately not repeating
     * is the iOS one, where a single catalogVersion key made every switch delete
     * the user's library.
     */
    fun setSelectedRomwbwVersion(romwbwVersion: String) {
        prefs.edit { putString(KEY_SELECTED_ROMWBW, romwbwVersion) }
    }

    /** The four disk slots stored for one release, nulls included, in slot order. */
    fun diskSlotsFor(romwbwVersion: String): List<String?> =
        (0..3).map { prefs.getString(diskSlotKey(it, romwbwVersion), null) }

    fun getSettings(): EmulatorSettings {
        return EmulatorSettings(
            romName = prefs.getString(KEY_ROM_NAME, DEFAULT_ROM) ?: DEFAULT_ROM,
            diskSlots = diskSlotsFor(selectedRomwbwVersion()),
            fontSize = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE),
            wrapLines = prefs.getBoolean(KEY_WRAP_LINES, false),
            scrollbackLines = prefs.getInt(KEY_SCROLLBACK_LINES, DEFAULT_SCROLLBACK_LINES)
                .coerceIn(0, SCROLLBACK_CHOICES.last())
        )
    }

    fun saveSettings(settings: EmulatorSettings) {
        // The slots go to the release selected NOW, not the one selected when
        // the caller took its snapshot. SettingsActivity re-reads them from
        // getSettings() immediately before calling this for exactly that reason:
        // without it, switching release and then leaving the screen would write
        // the old release's disk names into the new release's keys, and the
        // guest would boot a 3.5.1 disk against a 3.6.0 ROM.
        val version = selectedRomwbwVersion()
        prefs.edit {
            putString(KEY_ROM_NAME, settings.romName)
            settings.diskSlots.forEachIndexed { index, filename ->
                if (filename != null) {
                    putString(diskSlotKey(index, version), filename)
                } else {
                    remove(diskSlotKey(index, version))
                }
            }
            putInt(KEY_FONT_SIZE, settings.fontSize)
            putBoolean(KEY_WRAP_LINES, settings.wrapLines)
            putInt(KEY_SCROLLBACK_LINES, settings.scrollbackLines)
        }
    }

    fun setDiskSlot(slot: Int, filename: String?) {
        val key = diskSlotKey(slot, selectedRomwbwVersion())
        prefs.edit {
            if (filename != null) {
                putString(key, filename)
            } else {
                remove(key)
            }
        }
    }

    fun setFontSize(size: Int) {
        prefs.edit { putInt(KEY_FONT_SIZE, size) }
    }

    fun isFirstLaunchDone(): Boolean = prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)

    fun markFirstLaunchDone() {
        prefs.edit { putBoolean(KEY_FIRST_LAUNCH_DONE, true) }
    }

    fun isWarnManifestWritesEnabled(): Boolean =
        prefs.getBoolean(KEY_WARN_MANIFEST_WRITES, true)

    fun setWarnManifestWritesEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WARN_MANIFEST_WRITES, enabled) }
    }

    fun isSoundEnabled(): Boolean =
        prefs.getBoolean(KEY_SOUND_ENABLED, false)

    fun setSoundEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SOUND_ENABLED, enabled) }
    }

    /**
     * Record the catalog `generation` seen for one release, and say whether it
     * moved.
     *
     * Recorded and reported, and nothing else. `generation` advances when a
     * release's artifacts change, and the obvious use for that - delete the
     * images the catalog names so they are fetched again - is the iOS bug this
     * app must not acquire: the same code there, keyed on one global
     * catalogVersion, wiped a user's library on every version switch. Here the
     * staleness question is already answered per file, by the size and sha256
     * DiskDownloadManager checks on every download, so there is nothing for a
     * counter to add except a reason to delete something.
     *
     * It is stored per release because that is what it means - 3.5.1
     * generation 1 and 3.6.0 generation 1 are unrelated numbers.
     */
    fun noteCatalogGeneration(romwbwVersion: String, generation: Int): Boolean {
        val key = generationKey(romwbwVersion)
        val previous = prefs.getInt(key, -1)
        if (previous == generation) return false
        prefs.edit { putInt(key, generation) }
        // -1 is "never seen", which is every user's first fetch and not a change.
        return previous != -1
    }

    /**
     * What the catalog said about the ROM fetched for [romwbwVersion], or null
     * if none ever was.
     *
     * Recorded so that a launch with no network can still verify the ROM it
     * already has. Without it, checking the size and sha256 "every time it is
     * used" would silently become "whenever we happen to be online", and
     * offline is exactly when a corrupt ROM is least recoverable.
     *
     * Per release, like the disk slots and the NVRAM setting, because that is
     * what it means: 3.5.1's ROM and 3.6.0's are different files with different
     * hashes, and both can sit in the directory at once.
     */
    fun romClaim(romwbwVersion: String): RomClaim? {
        val filename = prefs.getString(romFileKey(romwbwVersion), null) ?: return null
        if (filename.isEmpty()) return null
        return RomClaim(
            filename = filename,
            size = prefs.getLong(romSizeKey(romwbwVersion), 0L),
            sha256 = prefs.getString(romSha256Key(romwbwVersion), "") ?: ""
        )
    }

    /**
     * Remember what the catalog promised about a release's ROM.
     *
     * Written only after the bytes have been fetched and verified, so the claim
     * and the file agree at the moment it is stored. They can disagree later -
     * a file removed by hand, a restore from backup that brought the
     * preferences and not the 512 KB - and that is the case readVerifiedRom
     * exists to catch rather than to assume away.
     */
    fun setRomClaim(romwbwVersion: String, claim: RomClaim) {
        prefs.edit {
            putString(romFileKey(romwbwVersion), claim.filename)
            putLong(romSizeKey(romwbwVersion), claim.size)
            putString(romSha256Key(romwbwVersion), claim.sha256)
        }
    }

    /**
     * Every stored-state migration, in one place, before anything reads that
     * state.
     *
     * [disksDir] and [persistedDisksDir] are the two image directories, or null
     * when external storage is unavailable - see
     * DiskDownloadManager.getDisksDirOrNull(). Returns what the v0 rename pass
     * did, or null when it had already been done, so the caller can log it: on a
     * device this is the only account anyone gets of a pass that renamed the
     * user's files.
     */
    fun migrateIfNeeded(disksDir: File?, persistedDisksDir: File?): V0MigrationResult? {
        val version = prefs.getInt(KEY_PREFS_VERSION, 1)
        if (version < CURRENT_PREFS_VERSION) {
            prefs.edit {
                // Version 2: New warn_manifest_writes setting defaults to true
                // Reset to default so all users get warnings enabled
                remove(KEY_WARN_MANIFEST_WRITES)
                putInt(KEY_PREFS_VERSION, CURRENT_PREFS_VERSION)
            }
        }
        // Order matters and is the whole safety argument. The seed hands back
        // the four slot values it settled on, and the rename pass takes them as
        // an argument rather than reading them again, so nothing here depends on
        // an apply()'d write being visible to the next read.
        val slots = seedBundledNamespace()
        return migrateDiskNamesToV0(disksDir, persistedDisksDir, slots)
    }

    /**
     * Copy the pre-v0 single-release state into the bundled release's
     * namespace, once, and return the four slot values that namespace now
     * holds.
     *
     * Everything a user had before this release was implicitly about RomWBW
     * 3.5.1 - it is the only release any shipped build could boot - so the
     * unsuffixed `disk_slot_0..3` and `nvram` keys ARE that release's state and
     * are copied across unchanged. Whether they still hold pre-v0 names is not
     * this function's problem: the rename pass runs immediately after and works
     * on whatever it is handed.
     *
     * The old keys are left in place rather than removed. They cost a few bytes
     * and they are the only thing an older build reinstalled over this one would
     * find - a user who downgrades should not discover an app that has forgotten
     * every disk they configured.
     *
     * A key that already exists is never overwritten, and the flag makes the
     * whole pass one-shot, because both of those are what stop a slot the user
     * deliberately CLEARED under 3.5.1 from being refilled from the legacy key
     * on the next launch.
     */
    private fun seedBundledNamespace(): List<String?> {
        val seededKey = KEY_NAMESPACE_SEEDED_PREFIX + V0_BUNDLED_ROMWBW
        if (prefs.getBoolean(seededKey, false)) return diskSlotsFor(V0_BUNDLED_ROMWBW)

        val legacySlots = (0..3).map { prefs.getString("$KEY_DISK_SLOT_PREFIX$it", null) }
        val seeded = (0..3).map { index ->
            val key = diskSlotKey(index, V0_BUNDLED_ROMWBW)
            if (prefs.contains(key)) prefs.getString(key, null) else legacySlots[index]
        }
        val legacyNvram = prefs.getString(KEY_NVRAM, null)

        prefs.edit {
            seeded.forEachIndexed { index, filename ->
                if (filename != null) putString(diskSlotKey(index, V0_BUNDLED_ROMWBW), filename)
            }
            val bundledNvramKey = nvramKey(V0_BUNDLED_ROMWBW)
            if (legacyNvram != null && !prefs.contains(bundledNvramKey)) {
                putString(bundledNvramKey, legacyNvram)
            }
            putBoolean(seededKey, true)
        }
        return seeded
    }

    /**
     * Move the four disk slots and both image directories onto v0 names.
     *
     * The slots and the flag go into ONE edit block, so a kill mid-write cannot
     * leave the flag set over slots that did not move. The other order - files
     * renamed, preferences not - is the one that costs the user something: the
     * apply() here is asynchronous while renameTo() is not, so the pass has to
     * be safe to run again rather than merely guarded, and migrateDiskNames()
     * is written that way.
     *
     * A slot is only ever written, never removed: this pass has no business
     * emptying a slot the user filled.
     *
     * [slots] is the bundled release's four slot values as
     * [seedBundledNamespace] settled them, and the bundled release is the only
     * one this pass touches: v0NameOf() maps a pre-v0 name to V0_BUNDLED_ROMWBW
     * and nothing else, and no other release's namespace can contain a pre-v0
     * name in the first place, because no build that could write one had any
     * other release to write it under.
     */
    private fun migrateDiskNamesToV0(
        disksDir: File?,
        persistedDisksDir: File?,
        slots: List<String?>
    ): V0MigrationResult? {
        val migratedKey = KEY_DISK_NAMES_MIGRATED_PREFIX + V0_BUNDLED_ROMWBW +
            ".pass" + DISK_NAMES_MIGRATION_PASS
        if (prefs.getBoolean(migratedKey, false)) return null

        val result = migrateDiskNames(disksDir, persistedDisksDir, slots)

        prefs.edit {
            result.slots.forEachIndexed { index, filename ->
                if (filename != null && filename != slots[index]) {
                    putString(diskSlotKey(index, V0_BUNDLED_ROMWBW), filename)
                }
            }
            if (result.complete) putBoolean(migratedKey, true)
        }
        return result
    }

    fun getSavedNvramSetting(): String? =
        prefs.getString(nvramKey(selectedRomwbwVersion()), null)

    fun saveNvramSetting(setting: String) {
        prefs.edit { putString(nvramKey(selectedRomwbwVersion()), setting) }
    }
}
