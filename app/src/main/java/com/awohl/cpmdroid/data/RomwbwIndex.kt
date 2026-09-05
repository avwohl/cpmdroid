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
 * are what the emulator core is asked about, and what a ROM carries at 0x105
 * and 0x106, so keeping them as numbers here means nothing downstream has to
 * parse a version string to compare two versions.
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
    val generation: Int
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

/**
 * The entries this build's emulator core will actually load a ROM for.
 *
 * [isRunnable] is emu_romwbw_release_supported() through JNI, asked rather than
 * assumed. A client can be compiled against a newer or an older core than it
 * expects - the core is built from a sibling checkout, not a versioned
 * dependency - so a hardcoded "offer everything" list would offer a release
 * this binary refuses, and a hardcoded "offer 3.5.1" would hide one it could
 * run. Neither failure is visible until a user picks the wrong row.
 */
fun runnableRomwbwVersions(
    all: List<RomwbwVersion>,
    isRunnable: (verByte: Int, updByte: Int) -> Boolean
): List<RomwbwVersion> = all.filter { isRunnable(it.verByte, it.updByte) }

/**
 * Which entry to select: the one the user chose if it is still on offer, else
 * the index's own default, else the first survivor.
 *
 * The fallback chain matters in both directions. A stored selection that has
 * disappeared from the index - a release withdrawn, or a core that no longer
 * supports it - must not leave the app with no catalog at all; and 6.1 warns
 * that although the generator enforces exactly one `default: true`, a client
 * should still cope with zero or two rather than crash. Two is resolved by
 * taking the first, which is the order the index publishes.
 */
fun selectRomwbwVersion(
    available: List<RomwbwVersion>,
    preferred: String?
): RomwbwVersion? {
    if (available.isEmpty()) return null
    if (preferred != null) {
        available.firstOrNull { it.romwbwVersion == preferred }?.let { return it }
    }
    return available.firstOrNull { it.isDefault } ?: available.first()
}
