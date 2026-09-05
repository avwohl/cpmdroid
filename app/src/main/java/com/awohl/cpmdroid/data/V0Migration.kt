package com.awohl.cpmdroid.data

import java.io.File

/*
 * The interface-v0 rename of stored disk names, and only that.
 *
 * A v0 asset name carries the RomWBW release it was built for -
 * hd1k_combo-v0-3.5.1.img where the catalog used to say hd1k_combo.img - so that
 * a 3.5.1 image and a 3.6.0 one can sit in the same flat directory without
 * either shadowing the other. Everything on a device today is on the pre-v0
 * side of that change, and the bare filename is the ONLY identity any of it
 * has: the four disk_slot_N preferences, the downloads in Disks/, and the
 * copies in ModifiedDisks/ that hold whatever the user has written from inside
 * CP/M. All three have to move together. Rename Disks/ and the preferences but
 * not ModifiedDisks/ and nothing reports an error - loadDiskDataWithPersistence
 * simply stops finding the persisted copy under the new name, falls back to the
 * pristine download, and the user boots a disk that looks right with every hour
 * of work they ever did on it left in a file nothing will open again.
 *
 * Renames, never copies. Nineteen of the twenty published 3.5.1 images are
 * byte-identical to their pre-v0 selves (only hd1k_combo's bytes moved), so
 * File.renameTo keeps the size and modification time a 51 MB image already has;
 * a copy would spend those bytes a second time, on the main thread, for a file
 * the user already has.
 *
 * No Context, no SharedPreferences and no android.* import here on purpose.
 * v0NameOf() is a pure function of a stored name and migrateDiskNames() takes
 * its two directories as arguments, so both run under a plain JVM test with
 * temporary directories - see app/src/test/.../V0MigrationTest.kt. This project
 * has no instrumented runner and no device in reach, so a testable shape is the
 * only coverage available to a pass that renames the user's files.
 */

/**
 * The RomWBW release this build runs, and so the only `<ver>` a pre-v0 name can
 * migrate to.
 *
 * It is what the bundled `app/src/main/assets/emu_avw.rom` declares in its HBIOS
 * configuration block - bytes 0x105/0x106 read 0x35 0x10, which is 3.5.1 - and
 * the two have to agree, because a 3.5.1 disk under a 3.6.0 ROM makes the guest
 * print `*** WARNING: HBIOS/CBIOS Version Mismatch ***`.
 *
 * That agreement is now checked rather than assumed: MainActivity reads the
 * ROM's first 264 bytes through emu_romwbw_release_of_image() on every launch
 * and logs an error if it disagrees with this line. It is checked and not
 * corrected, deliberately. This constant is a historical fact as much as a
 * current one - it is the release every pre-v0 name was renamed TO, and the one
 * the per-release preference keys were seeded under - so silently following a
 * swapped ROM would strand state that has already been written under the old
 * value. Moving this needs a code change and a migration together; the log line
 * is what asks for both.
 */
const val V0_BUNDLED_ROMWBW = "3.5.1"

/** The infix every v0 asset name carries between its stem and its extension. */
private const val V0_INFIX = "-v0-"

/** The extension of every disk the catalog has ever published. */
private const val DISK_EXTENSION = ".img"

/**
 * The twenty disk stems the catalog has ever named. Nothing else is migrated.
 *
 * These are verbatim the twenty `<filename>` stems in the pre-v0 catalog this
 * app fetches (ioscpm `release_assets/disks.xml`, `version="13"`), and they are
 * also exactly the twenty ids in `catalog-v0-3.5.1.json`. A stored name outside
 * this set is the user's own import - staged in by hand, or renamed by them -
 * and must come out of the pass untouched.
 *
 * Two absences are deliberate:
 *
 * `emu_avw` is not here, although the v0 catalog really does publish
 * `roms[] id emu_avw` as `emu_avw-v0-3.5.1.rom`. `rom_name` sits in the same
 * preferences file as `disk_slot_0..3` and holds a bare filename of exactly the
 * same shape, so "rename every stored bare filename" is the natural reading and
 * the wrong one: MainActivity opens that name with `assets.open()`, against a
 * file inside the APK, so a renamed `rom_name` throws IOException and the app
 * shows "ROM not found" on every launch, for every user, with no way back - the
 * Settings row that displays it is read-only text.
 *
 * The five ids that exist only under 3.6.0 - `hd1k_infocom`, `hd1k_cobol`,
 * `hd1k_wp`, `hd1k_dos65`, `hd1k_msx` - are not here either. No catalog this app
 * has ever fetched named them, so a file called `hd1k_msx.img` on a device is a
 * user import, and there is no `hd1k_msx-v0-3.5.1.img` for it to become.
 */
private val CATALOG_DISK_STEMS = setOf(
    "hd1k_combo",
    "hd1k_cpm22",
    "hd1k_zsdos",
    "hd1k_zpm3",
    "hd1k_cpm3",
    "hd1k_nzcom",
    "hd1k_qpm",
    "hd1k_games",
    "hd1k_aztecc",
    "hd1k_bascomp",
    "hd1k_cowgol",
    "hd1k_fortran",
    "hd1k_hitechc",
    "hd1k_tpascal",
    "hd1k_z80asm",
    "hd1k_ws4",
    "hd1k_z3plus",
    "hd1k_bp",
    "hd1k_msxroms1",
    "hd1k_msxroms2"
)

/**
 * [storedName] under the v0 convention, or null when it must be left alone.
 *
 * Null is the answer for four different reasons and they are all the same
 * reason: this pass may only touch names it can prove the catalog published.
 * A name that already carries `-v0-` is left alone so that running the pass
 * twice is a no-op - which it has to be, because the flag that guards it is
 * written with apply() while renameTo() is immediate, so a process killed
 * between the two comes back to already-renamed files and an unset flag.
 */
fun v0NameOf(storedName: String): String? {
    // Everything before the LAST dot, so "hd1k_combo.img.1234.tmp" yields the
    // stem "hd1k_combo.img.1234" and falls out below rather than being treated
    // as hd1k_combo with an odd extension. A leading dot (dot == 0) is a name
    // with no stem, and a trailing one is a name with no extension.
    val dot = storedName.lastIndexOf('.')
    if (dot <= 0 || dot == storedName.length - 1) return null

    val stem = storedName.substring(0, dot)
    if (storedName.substring(dot) != DISK_EXTENSION) return null
    if (stem.contains(V0_INFIX)) return null
    if (stem !in CATALOG_DISK_STEMS) return null

    return stem + V0_INFIX + V0_BUNDLED_ROMWBW + DISK_EXTENSION
}

/**
 * What one run of [migrateDiskNames] did.
 *
 * [complete] is the only field the caller must act on: it is false when
 * something the pass wanted to do did not happen, and a caller that stamps its
 * one-shot flag on a false here marks the migration permanently done over files
 * that are still on the pre-v0 side of it.
 */
data class V0MigrationResult(
    /** What the four disk_slot_N keys should hold now, in slot order. */
    val slots: List<String?>,
    /** Files actually renamed, across both directories. */
    val renamed: Int,
    /** True when every rename this pass wanted either happened or was moot. */
    val complete: Boolean
)

/** Why one rename did not happen, which is not the same as whether it failed. */
private enum class RenameOutcome { RENAMED, NOTHING_TO_DO, FAILED }

/**
 * Rename every pre-v0 catalog image in both directories, and say what the four
 * disk slots should now hold.
 *
 * Nothing is ever deleted, here or anywhere in this pass. An unrecognised name
 * is a user's own image; a name that is already v0 is a pass that has run
 * before; a destination that already exists is a file worth more than the one
 * that would replace it. All three are left where they are.
 *
 * [slots] is the four stored disk_slot_N values, nulls included, in slot order.
 */
fun migrateDiskNames(
    disksDir: File?,
    persistedDisksDir: File?,
    slots: List<String?>
): V0MigrationResult {
    // Both directories hang off getExternalFilesDir(null), which really can
    // return null on an unavailable volume, and File(null, "Disks") is a
    // RELATIVE "Disks" in the process working directory - a folder no file
    // manager, no adb pull and no user will ever find (the same accident
    // HostTransfer.transferDir() guards against). A pass that walked that would
    // list nothing, rename nothing and report success, and the caller would
    // stamp the migration done while the real ModifiedDisks/ still held the
    // user's work under pre-v0 names. Refuse instead, and be asked again next
    // launch.
    if (disksDir == null || persistedDisksDir == null) {
        return V0MigrationResult(slots, renamed = 0, complete = false)
    }

    var renamed = 0
    var complete = true

    for (name in candidateNames(disksDir, persistedDisksDir, slots)) {
        val v0 = v0NameOf(name) ?: continue

        // The pristine copy first and the written-to copy LAST, per name.
        // Preferences are rewritten only after every rename below, so a process
        // killed in the middle of this loop comes back with the slot still
        // naming the pre-v0 file - and under that name loadDiskDataWithPersistence
        // still finds ModifiedDisks/<old>, which is the user's work. Do it the
        // other way round and the same kill leaves the persisted copy under a
        // name nothing asks for, the pristine download answers instead, and the
        // work is invisible until the next launch finishes the pass.
        val pristine = renameWithin(disksDir, name, v0)
        if (pristine == RenameOutcome.RENAMED) renamed++
        if (pristine == RenameOutcome.FAILED) {
            // And for the same reason, do not move the written-to copy of a name
            // whose pristine copy could not move: leaving both under the old
            // name keeps the pair readable by the preference that still names
            // it, and the retry next launch starts from a state the app boots
            // correctly rather than from a half of one.
            complete = false
            continue
        }

        val written = renameWithin(persistedDisksDir, name, v0)
        if (written == RenameOutcome.RENAMED) renamed++
        if (written == RenameOutcome.FAILED) {
            complete = false
            // Put the pristine copy back, for the same reason the branch above
            // declines to move the written-to one: the pair has to answer to a
            // single name. The slot below stays on the old name here, because
            // ModifiedDisks/ still holds the user's work under it - and a slot
            // naming a file that is no longer in Disks/ is exactly what
            // checkFirstLaunchAndLoad() reads as "first launch". It then
            // downloads whatever the catalog marks defaultSlot 0 and writes THAT
            // over slot 0, so half a rename does not merely fail, it hands the
            // user's configuration to the recovery path. Only ever undoes what
            // this pass just did: a NOTHING_TO_DO pristine is either absent or
            // already v0, and neither is ours to move.
            if (pristine == RenameOutcome.RENAMED &&
                renameWithin(disksDir, v0, name) == RenameOutcome.RENAMED
            ) {
                renamed--
            }
        }
    }

    val migratedSlots = slots.map { stored ->
        if (stored == null) return@map null
        val v0 = v0NameOf(stored) ?: return@map stored

        // The preference follows the file, and only the file. If either
        // directory still holds this name with no v0 file beside it, the rename
        // for it did not happen and the slot stays where the bytes are.
        // Rewriting it anyway is the expensive mistake in this whole migration:
        // checkFirstLaunchAndLoad() reads a slot naming a file
        // isDiskDownloaded() cannot find as "first launch", downloads whatever
        // the catalog marks defaultSlot 0, and writes THAT over the user's
        // slot-0 choice. The recovery path is what destroys the configuration.
        if (strandedUnderOldName(disksDir, stored, v0) ||
            strandedUnderOldName(persistedDisksDir, stored, v0)
        ) {
            stored
        } else {
            v0
        }
    }

    return V0MigrationResult(migratedSlots, renamed, complete)
}

/**
 * Every name this pass might have to rename: what the slots point at, plus what
 * is actually in the two directories.
 *
 * The slots alone are not enough - a user can have downloaded twenty images and
 * assigned four - and the directories alone are not enough either, because
 * Auto Backup restores this app's preferences and its 51 MB images under
 * different practical limits, so a device can come back with a slot naming a
 * file that is not there. Neither list is a subset of the other.
 */
private fun candidateNames(
    disksDir: File,
    persistedDisksDir: File,
    slots: List<String?>
): Set<String> {
    val names = LinkedHashSet<String>()
    slots.forEach { if (it != null) names.add(it) }
    listOf(disksDir, persistedDisksDir).forEach { dir ->
        dir.listFiles()?.forEach { if (it.isFile) names.add(it.name) }
    }
    return names
}

private fun renameWithin(dir: File, from: String, to: String): RenameOutcome {
    val source = File(dir, from)
    if (!source.exists()) return RenameOutcome.NOTHING_TO_DO

    // A destination that already exists is kept and the source is left beside
    // it. The alternatives are both worse: overwriting throws away whichever
    // copy the user wrote to more recently, and deleting the source throws away
    // a file this pass cannot prove anything about. An orphan costs storage;
    // either of those costs data.
    val destination = File(dir, to)
    if (destination.exists()) return RenameOutcome.NOTHING_TO_DO

    return if (source.renameTo(destination)) RenameOutcome.RENAMED else RenameOutcome.FAILED
}

/** True when [dir] still holds [old] and has no [new] beside it. */
private fun strandedUnderOldName(dir: File, old: String, new: String): Boolean =
    File(dir, old).exists() && !File(dir, new).exists()
