package com.awohl.cpmdroid.data

import org.json.JSONObject

/*
 * index-v0.json - the list of RomWBW releases romwbw_disks publishes.
 *
 * This document is the top of a two-level catalog and the only URL this app
 * compiles in. It names, per RomWBW release, the HBIOS version bytes a ROM for
 * that release carries and the URL of that release's own catalog. Nothing here
 * builds a URL out of a tag any more: the index gives a catalog_url verbatim
 * and the catalog gives a base_url verbatim, so the one thing that used to go
 * wrong - a client interpolating a release tag it had been repinned to and
 * asking GitHub for an asset that was never attached to it - has no way to
 * happen. A whole ioscpm release shipped that way and answered 404 for every
 * user until the next one.
 *
 * The parser is a pure function of a String, in a file with no Context and no
 * android.* import beyond org.json, so app/src/test can hand it the documents
 * that are actually published rather than a paraphrase of them.
 */

/** The status romwbw_disks publishes for a release it is not recommending yet. */
const val ROMWBW_STATUS_PREVIEW = "preview"

/**
 * One entry of `romwbw_versions[]`.
 *
 * [verByte] and [updByte] are `hbios.ver_byte` / `hbios.upd_byte` parsed out of
 * their hex-string spelling ("0x35", "0x10") into the two bytes RomWBW actually
 * packs a version into - ver = major<<4 | minor, upd = update<<4 | patch. They
 * are what a ROM carries at 0x105 and 0x106, and what the catalog publishes for
 * its own ROMs, so keeping them as numbers here means nothing downstream has to
 * parse a version string to compare two versions. They describe the
 * ROM-to-disk-image pairing; they are no longer asked of the emulator core,
 * which has had no opinion about a release since romwbw_emu v1.44.
 *
 * Fields this app does not use are deliberately absent rather than carried:
 * `released`, `disks_xml_url`, `rom_count`, `disk_count`, `release_tag` and
 * `notes` are all read by nothing here, and CATALOG_SCHEMA 6.1 requires that an
 * unknown field be ignored rather than rejected, which is what dropping them
 * does. `notes` in particular must never be branched on - the published 3.6.0
 * note still describes a compile-time pin the core stopped having in v1.39.
 */
data class RomwbwVersion(
    val romwbwVersion: String,
    val label: String,
    val status: String,
    val isDefault: Boolean,
    val verByte: Int,
    val updByte: Int,
    val catalogUrl: String,
    val catalogSha256: String,
    val catalogSize: Long,
    val generation: Int,
    /**
     * `prerelease` - upstream does not call this a release.
     *
     * True only for a RomWBW pre-release the publisher carries deliberately, and
     * ABSENT - not false - on every real release, which is why it is read with a
     * defaulting accessor. CATALOG_SCHEMA.md 2.3 requires that: the field is
     * emitted only when true, so a released version's index entry stays byte
     * identical to the one already on its immutable tag, and a reader that
     * demanded the key would drop every stable release ever published.
     *
     * NOT the same thing as [isPreview], which reads `status`. `status` is free
     * text - section 6 says so - and a client that hid entries by matching
     * status words would break the first time a new word was published. This has
     * exactly one meaning and is safe to branch on.
     */
    val isPrerelease: Boolean = false
) {
    /** True for a release published as not-yet-recommended, which the UI marks. */
    val isPreview: Boolean get() = status.equals(ROMWBW_STATUS_PREVIEW, ignoreCase = true)
}

/**
 * "0x35" -> 0x35, or null when the field is missing or not that shape.
 *
 * A hex STRING, because that is what the schema publishes and changing it to an
 * integer is listed as a v0 -> v1 break. Bare "35" is accepted as well: the two
 * spellings mean the same byte and refusing one would turn a cosmetic edit
 * upstream into an app that offers no releases at all.
 */
fun parseHbiosByte(text: String?): Int? {
    if (text.isNullOrEmpty()) return null
    val digits = if (text.startsWith("0x", ignoreCase = true)) text.substring(2) else text
    if (digits.isEmpty() || digits.length > 2) return null
    return digits.toIntOrNull(16)
}

/**
 * Every usable entry of an index document, in the order it publishes them.
 *
 * Per-entry tolerance is the point. An entry missing the four things this app
 * cannot work without - a version string, a catalog URL, and both HBIOS bytes -
 * is skipped and the rest of the list is returned, because the alternative is
 * what HelpActivity.parseHelpIndex does: required-field getters inside one
 * try/catch, so a single malformed entry returns null for the whole document.
 * Under that shape, publishing one bad RomWBW 3.7.0 entry would take the
 * catalog away from every shipped client at once, including for the releases
 * that were still perfectly described.
 *
 * Throws JSONException when the response is not a JSON object at all. That is
 * the one failure that is not per-entry - it means the URL answered with
 * something that is not the index, GitHub's HTML 404 page being the likely
 * one - and the caller reports it differently from an index that parsed and
 * turned out to be empty.
 */
fun parseRomwbwIndex(json: String): List<RomwbwVersion> {
    val root = JSONObject(json)
    val entries = root.optJSONArray("romwbw_versions") ?: return emptyList()

    val versions = ArrayList<RomwbwVersion>(entries.length())
    for (i in 0 until entries.length()) {
        val entry = entries.optJSONObject(i) ?: continue

        val romwbwVersion = entry.optString("romwbw_version")
        val catalogUrl = entry.optString("catalog_url")
        if (romwbwVersion.isEmpty() || catalogUrl.isEmpty()) continue

        val hbios = entry.optJSONObject("hbios")
        val verByte = parseHbiosByte(hbios?.optString("ver_byte"))
        val updByte = parseHbiosByte(hbios?.optString("upd_byte"))
        if (verByte == null || updByte == null) continue

        versions.add(
            RomwbwVersion(
                romwbwVersion = romwbwVersion,
                // The label is what the picker shows; falling back to the
                // version itself keeps a row readable rather than blank.
                label = entry.optString("label").ifEmpty { "RomWBW $romwbwVersion" },
                // Free text, not an enum - "stable" and "preview" are what is in
                // use, and 6.1 says display an unknown value rather than fail on
                // it. Only the preview comparison branches on it.
                status = entry.optString("status"),
                isDefault = entry.optBoolean("default", false),
                // Absent means false, which is what every real release says
                // about itself - see the note on isPrerelease.
                isPrerelease = entry.optBoolean("prerelease", false),
                verByte = verByte,
                updByte = updByte,
                catalogUrl = catalogUrl,
                // Absent means "cannot be checked", which fetchCatalog treats as
                // a skipped check rather than as a failure: an index that stops
                // publishing a hash must not stop the app from downloading.
                catalogSha256 = entry.optString("catalog_sha256"),
                catalogSize = entry.optLong("catalog_size", 0L),
                generation = entry.optInt("generation", 0)
            )
        )
    }
    return versions
}

/*
 * THERE IS NO PER-ENTRY FILTER HERE, AND ADDING ONE BACK IS A REGRESSION.
 *
 * runnableRomwbwVersions() stood here and dropped every entry the emulator core
 * would not load a ROM for, asking emu_romwbw_release_supported() through JNI.
 * romwbw_emu v1.44 deleted that function, and the argument is worth keeping
 * where the filter was: a RomWBW release number is the HBIOS-to-CBIOS pairing,
 * a fact about a ROM and a disk image. What the core depends on is the
 * emulator-to-ROM interface - two I/O ports and the HBIOS functions
 * hbios_dispatch.cc services - which this catalog versions in its own name, v0.
 * Every release a v0 index publishes speaks it; an interface change the core
 * could not service would be published as index-v1.json, which this client
 * ignores by name. So the filter could only ever hide a release the user could
 * have booted, and offering every entry is the correct behaviour rather than a
 * relaxation of a check.
 *
 * [RomwbwVersion.verByte] and [RomwbwVersion.updByte] stay parsed and stay
 * used: they are the ROM-to-disk-image pairing, checked against the catalog's
 * own HCB bytes in DiskDownloadManager.fetchAndReadRom before a ROM is
 * downloaded (RomFailure.WrongRelease).
 */

/**
 * Which entry to select: the one the user chose if it is still on offer, else
 * the index's own default, else the first survivor.
 *
 * The fallback chain matters in both directions. A stored selection that has
 * disappeared from the index - a release withdrawn upstream - must not leave
 * the app with no catalog at all; and 6.1 warns that although the generator
 * enforces exactly one `default: true`, a client should still cope with zero or
 * two rather than crash. Two is resolved by taking the first, which is the
 * order the index publishes.
 */
fun selectRomwbwVersion(
    available: List<RomwbwVersion>,
    preferred: String?,
    showPrerelease: Boolean = false
): RomwbwVersion? {
    if (available.isEmpty()) return null

    // The user's own choice wins while the index still publishes it AND while it
    // is a release they have asked to be offered. The second half is what makes
    // turning the setting off MOVE a machine that is sitting on a pre-release,
    // rather than leaving it on a release its own picker will not list. It is
    // applied here rather than at the call sites so that nothing can forget it.
    if (preferred != null) {
        available.firstOrNull { it.romwbwVersion == preferred && isOffered(it, showPrerelease) }
            ?.let { return it }
    }

    // Both fallbacks run over the OFFERED entries. The index promises `default`
    // is never on a pre-release - romwbw_disks' own tools refuse that
    // combination - so this changes nothing about a well-formed document. It is
    // here because a fallback is exactly where a pre-release would become
    // somebody's release by accident.
    available.firstOrNull { it.isDefault && isOffered(it, showPrerelease) }?.let { return it }
    available.firstOrNull { isOffered(it, showPrerelease) }?.let { return it }

    // Nothing offered at all: an index of nothing but pre-releases, with the
    // setting off. Hiding a row from a picker and refusing to run are different
    // answers, and returning null here would be the second - a hard failure over
    // a document that is publishing perfectly good pre-releases. So the filter
    // yields and the ordinary rules decide.
    return available.firstOrNull { it.isDefault } ?: available.first()
}

/**
 * Is this entry offered to the user at all?
 *
 * A PRE-RELEASE IS NOT, UNLESS ASKED FOR. CATALOG_SCHEMA.md 2.3: a client "MUST
 * NOT offer a prerelease entry by default - hide it behind an explicit opt-in".
 *
 * One place, so the picker and the automatic choice cannot disagree about one
 * machine - which is the failure the deleted release filter kept producing.
 *
 * This is NOT the per-entry filter the comment above selectRomwbwVersion's
 * predecessor forbids, and the difference is the axis. That one asked whether
 * this build could run a release the index published, which it always could.
 * This one asks whether upstream has released it at all.
 */
fun isOffered(entry: RomwbwVersion, showPrerelease: Boolean): Boolean =
    !entry.isPrerelease || showPrerelease
