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
 * roms[] is read the same way, and is what stops the ROM being the one
 * version-coupled thing left in this app. It is keyed on `id` with the
 * `default` flag deciding which entry to use, never on array position and never
 * by looking for "emu_avw": 6.1 forbids all three, and an absent or empty
 * roms[] is a release with no ROM rather than a malformed document.
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
    /**
     * This release's ROMs. Empty is a real state, not a parse failure - see
     * selectRom(), and RomFailure.NoRomPublished for what it costs.
     */
    val roms: List<RomInfo>,
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

    val romEntries = root.optJSONArray("roms")
    val roms = ArrayList<RomInfo>(romEntries?.length() ?: 0)
    for (i in 0 until (romEntries?.length() ?: 0)) {
        val entry = romEntries?.optJSONObject(i) ?: continue

        // Same two required fields as a disk, for the same two reasons: the id
        // is the identity across releases and the filename is both the storage
        // name and the URL suffix.
        val id = entry.optString("id")
        val filename = entry.optString("filename")
        if (id.isEmpty() || filename.isEmpty()) continue

        // hcb is optional here even though every published catalog carries it,
        // because a missing one costs only the pre-download check - the core
        // reads the same two bytes out of the image itself and refuses a
        // release it has not been run against, whatever this document says.
        val hcb = entry.optJSONObject("hcb")

        roms.add(
            RomInfo(
                id = id,
                filename = filename,
                name = entry.optString("name").ifEmpty { id },
                size = entry.optLong("size", 0L),
                sha256 = entry.optString("sha256"),
                isDefault = entry.optBoolean("default", false),
                downloadUrl = baseUrl + filename,
                // "version"/"update" here, not "ver_byte"/"upd_byte": the same
                // two bytes under different names, because hcb reports what was
                // read back out of the built ROM and hbios reports what the
                // release declares. Both are hex strings; hcb.platform beside
                // them is a decimal integer, which is why nothing here assumes
                // a uniform encoding.
                hcbVerByte = parseHbiosByte(hcb?.optString("version")),
                hcbUpdByte = parseHbiosByte(hcb?.optString("update"))
            )
        )
    }

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
        roms = roms,
        disks = disks
    )
}
