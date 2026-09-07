package com.awohl.cpmdroid.data

/**
 * One `roms[]` entry of a v0 catalog document.
 *
 * [id] is the stable identity - "emu_avw" - and [filename] is where the bytes
 * are, version-qualified the same way a disk's is
 * ("emu_avw-v0-3.6.0.rom"). Two releases' ROMs therefore sit in one flat
 * directory beside their disks without shadowing each other, which is the whole
 * point of the `-v0-<ver>` suffix.
 *
 * [downloadUrl] is `base_url` + [filename], built at parse time, for the same
 * reason DiskInfo carries one: a ROM that knows its own URL cannot be paired
 * with another release's base after a version switch.
 *
 * [hcbVerByte] and [hcbUpdByte] are the HBIOS configuration block bytes the
 * catalog read back out of the built ROM at file offsets 0x105/0x106 - the two
 * bytes `emu_validate_rom_hcb` will read for itself after the download. They
 * are hex STRINGS in the document ("0x36", "0x00"), like `hbios.ver_byte`, and
 * null here when the catalog does not publish them. Checking them costs
 * nothing and saves fetching 512 KB the core would then refuse; it does not
 * replace that refusal, which stays the last line of defence.
 *
 * `description` is read by nothing and so is not carried, the same way the
 * index entry drops the fields nothing here asks about. Dropping an unknown or
 * unused field IS the tolerance CATALOG_SCHEMA 6.1 requires; carrying it to
 * look thorough only invites somebody to display it untranslated.
 */
data class RomInfo(
    val id: String,
    val filename: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val isDefault: Boolean,
    val downloadUrl: String,
    val hcbVerByte: Int? = null,
    val hcbUpdByte: Int? = null
)

/**
 * Everything needed to verify a ROM already on disk, with no catalog in hand.
 *
 * The size and sha256 are the catalog's claims about [filename], recorded when
 * the ROM was fetched. Without them a launch with no network could not check a
 * ROM it already had, and "verify every time it is used" would quietly become
 * "verify when we happen to be online" - which is the same as not verifying,
 * since a corrupt ROM boots to nothing in exactly the offline case.
 */
data class RomClaim(
    val filename: String,
    val size: Long,
    val sha256: String,
    /**
     * The catalog ID these bytes were fetched for, or null for a claim written
     * by a build that stored no ID.
     *
     * Here so that the offline fast path can tell "the ROM I have is the ROM I
     * want" from "the ROM I have is the one I wanted last time". Without it,
     * changing the ROM in Settings would be recorded in preferences and then
     * ignored on every launch, because a verified file already sitting on disk
     * answers the question before the catalog is ever opened. Null is treated as
     * "not the one you asked for" whenever a pick exists, which costs one fetch
     * on the first launch after upgrading and nothing afterwards.
     */
    val romId: String? = null
)

/** The catalog's claims about [rom], in the shape that outlives the document. */
fun RomInfo.claim(): RomClaim = RomClaim(filename, size, sha256, id)

/**
 * Which of a release's ROMs to use: the one the user picked, else the one
 * flagged default, else the first.
 *
 * [preferredId] is a catalog ID and never a filename - see
 * SettingsRepository.selectedRomId(). An ID that this release does not publish
 * falls through to the default rather than failing: roms[] is per-release, so
 * picking emu_rcz80 under 3.6.0 and then switching to a release that publishes
 * only emu_avw is an ordinary thing to do, and it must land on a bootable ROM
 * rather than on nothing. The pick is kept in preferences either way, so
 * switching back restores it.
 *
 * The rest is required by CATALOG_SCHEMA 6.1, and so is what this does NOT do.
 * It never indexes by position, never looks for "emu_avw" by name, and never
 * assumes the array has two entries or any entries at all - a future release may
 * publish a different ROM set, and 3.5.1 and 3.6.0 already publish different
 * disk sets. No entry flagged `default` is normal rather than an error: the
 * generator enforces one default per catalog today, and a client that crashed on
 * zero or two would be broken by a catalog the schema still permits.
 *
 * Null means the catalog publishes no ROM for this release, which is a real
 * answer - see RomFailure.NoRomPublished. Since this app bundles no ROM, null is
 * fatal to booting that release rather than merely inconvenient.
 */
fun selectRom(roms: List<RomInfo>, preferredId: String? = null): RomInfo? {
    if (preferredId != null) {
        roms.firstOrNull { it.id == preferredId }?.let { return it }
    }
    return roms.firstOrNull { it.isDefault } ?: roms.firstOrNull()
}

/**
 * Why a machine cannot start on the release it is set to.
 *
 * Every one of these is reported, and none of them is answered by booting some
 * other release's ROM. There is no longer one in the package to substitute, but
 * the rule would hold anyway: it would pair one release's ROM with another
 * release's disks silently, which is the exact failure fetching the ROM from the
 * catalog exists to remove - and it would do it invisibly, where the only symptom is a
 * warning line inside the guest.
 */
sealed class RomFailure(message: String) : Exception(message) {

    /** The catalog for this release lists no ROM at all. */
    class NoRomPublished(val romwbwVersion: String) :
        RomFailure("The RomWBW $romwbwVersion catalog publishes no ROM")

    /**
     * Nothing has ever been fetched for this release, so there is not even a
     * filename to check.
     *
     * Distinct from NotDownloaded, which knows the file and cannot find it.
     * This is the state every fresh install begins in, and the one a newly
     * selected release leaves behind if the fetch never happened - or that a
     * restore from backup produces, since the preferences travel and 512 KB of
     * ROM may not.
     */
    class NeverFetched(val romwbwVersion: String) :
        RomFailure("RomWBW $romwbwVersion has no ROM on this device yet")

    /**
     * A ROM for this release is here, but not the one that is selected.
     *
     * Distinct from NeverFetched, and the distinction is the whole reason it
     * exists: telling somebody who has just chosen emu_rcz80 that "RomWBW 3.6.0
     * has no ROM on this device yet" is false on its face - there is one, they
     * can see it named on the Settings screen - and it hides the actual cause,
     * which is that the ROM they picked has not been fetched.
     */
    class PickedRomNotFetched(val romwbwVersion: String, val romId: String) :
        RomFailure("The $romId ROM for RomWBW $romwbwVersion has not been downloaded yet")

    /** The release's ROM was fetched once and is no longer on disk. */
    class NotDownloaded(val romwbwVersion: String, val filename: String) :
        RomFailure("RomWBW $romwbwVersion needs its ROM, $filename, which is not on the device")

    /**
     * The transfer did not produce a usable file.
     *
     * Separate from DidNotVerify, and the separation is the whole point of
     * having it: a connection that dropped, a 404 and a hash that did not match
     * are three different things to tell somebody, and the first two are not
     * "your ROM is corrupt". [reason] is what the transfer said verbatim -
     * "Download truncated: got N of 524288 bytes" and "SHA256 mismatch: ..."
     * both arrive here, because a download that arrived wrong never became a
     * file - so the sentence has to work for a connection problem and for a bad
     * copy alike, and "could not be downloaded: <what happened>" does.
     */
    class CouldNotFetch(
        val romwbwVersion: String,
        val filename: String,
        val reason: String
    ) : RomFailure("The RomWBW $romwbwVersion ROM $filename could not be downloaded: $reason")

    /**
     * The file is there and is not the ROM the catalog describes.
     *
     * Reported, never repaired behind the user's back beyond the one
     * re-download the fetch path already does. A 512 KB file that hashes wrong
     * is the one file whose corruption produces a guest that boots to nothing,
     * so it must not be handed to the emulator, and it must not be replaced by
     * a different release's ROM either.
     */
    class DidNotVerify(
        val romwbwVersion: String,
        val filename: String,
        val reason: String
    ) : RomFailure("The RomWBW $romwbwVersion ROM $filename did not verify: $reason")

    /**
     * The catalog's ROM declares HBIOS bytes that are not this release's.
     *
     * Caught before spending the download rather than after: the catalog
     * publishes the two HCB bytes it read back out of the built image, and the
     * index publishes what the release's ROM must carry, so a document that
     * disagrees with itself is visible for the cost of a comparison.
     * `emu_validate_rom_hcb` would refuse the same image after the transfer;
     * this only means the user is told before waiting for it.
     */
    class WrongRelease(
        val romwbwVersion: String,
        val filename: String,
        val declared: String
    ) : RomFailure(
        "$filename declares RomWBW HBIOS $declared, which is not what RomWBW " +
            "$romwbwVersion publishes"
    )
}
