package com.awohl.cpmdroid.data

import org.json.JSONObject

/*
 * catalog-v0-<ver>.json - one RomWBW release's disks, and where to get them.
 *
 * This is what disks.xml was, plus a roms[] array and a base_url. The parser it
 * replaces walked XML elements and collected child text; the shape of the data
 * is the same, so the change that matters is not XML to JSON but that the
 * download base now comes out of the document instead of being interpolated
 * from a compile-time release tag.
 *
 * roms[] is deliberately not read. This build boots the ROM bundled in its own
 * assets and has no path that loads one from storage, so parsing an array it
 * could not act on would only invite somebody to assume emu_avw is in it -
 * which 6.1 says explicitly not to do, and which a release that publishes a
 * different default ROM would break. Ignoring the array is the behaviour the
 * compatibility rules ask for, not an omission.
 */

/** A parsed catalog document: what to show, and where its bytes live. */
data class DiskCatalog(
    val romwbwVersion: String,
    val status: String,
    /**
     * Advances only when this release's artifacts actually change, and is
     * scoped per RomWBW release. It is a value to COMPARE, never to compute
     * from - 6.1 warns it can jump by more than 1 - and in this app it is
     * recorded and logged and nothing else. See SettingsRepository.noteCatalogGeneration
     * for why it must never become a reason to delete a downloaded image.
     */
    val generation: Int,
    val baseUrl: String,
    val disks: List<DiskInfo>
)

/**
 * Parse a catalog document, or return null when `base_url` is missing.
 *
 * A catalog with no base is refused as a whole rather than parsed into entries
 * whose download URLs would be bare filenames: a relative URL handed to OkHttp
 * throws, and twenty rows that all fail on tap is a worse report than one that
 * says the document was unusable. Every other field is per-entry tolerant.
 *
 * Throws JSONException when the body is not a JSON object - the caller
 * distinguishes that from an empty catalog, because they mean different things
 * about what went wrong.
 */
fun parseDiskCatalog(json: String): DiskCatalog? {
    val root = JSONObject(json)

    // No fallback to a constructed base. The whole point of v0 is that the
    // document says where its assets are; guessing here would put back the
    // tag-interpolated URL this release exists to delete.
    val baseUrl = root.optString("base_url")
    if (baseUrl.isEmpty()) return null

    val entries = root.optJSONArray("disks")
    val disks = ArrayList<DiskInfo>(entries?.length() ?: 0)
    for (i in 0 until (entries?.length() ?: 0)) {
        val entry = entries?.optJSONObject(i) ?: continue

        // id and filename are the two fields nothing downstream can do without:
        // the id is how a disk is identified across releases and the filename
        // is both the storage name and the URL suffix. Anything else missing
        // costs a row some display text; these missing cost it its identity, so
        // the row is dropped and the other nineteen are kept. One bad entry
        // must not take the catalog away - the in-repo precedent that does
        // exactly that is HelpActivity.parseHelpIndex.
        val id = entry.optString("id")
        val filename = entry.optString("filename")
        if (id.isEmpty() || filename.isEmpty()) continue

        disks.add(
            DiskInfo(
                id = id,
                filename = filename,
                name = entry.optString("name").ifEmpty { id },
                description = entry.optString("description"),
                size = entry.optLong("size", 0L),
                license = entry.optString("license"),
                sha256 = entry.optString("sha256"),
                downloadUrl = baseUrl + filename,
                // Present on hd1k_combo alone today, and optional by contract.
                // -1 as the sentinel rather than 0, because 0 is a real slot and
                // is in fact the value the one entry that carries this uses.
                defaultSlot = entry.optInt("defaultSlot", -1).takeIf { it >= 0 }
            )
        )
    }

    return DiskCatalog(
        romwbwVersion = root.optString("romwbw_version"),
        status = root.optString("status"),
        generation = root.optInt("generation", 0),
        baseUrl = baseUrl,
        disks = disks
    )
}
