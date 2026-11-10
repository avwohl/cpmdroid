package com.awohl.cpmdroid.data

/**
 * What an installed disk image is, measured against the catalog entry that
 * names it.
 *
 * Nothing re-read an installed image before this: the catalog document's sha256
 * is verified, and a download's bytes are verified as they arrive, but
 * isDiskDownloaded() decides by filename. Disks/ holds only what a verified
 * download or the v0 migration put there - the guest writes to ModifiedDisks/ -
 * so an image there that differs from its catalog entry is one the catalog has
 * republished since, or one the storage has damaged.
 */
enum class InstalledImage {
    /** The bytes are the ones the catalog names. */
    MATCHES_CATALOG,

    /**
     * Not the catalog's bytes, but a pre-v0 image the v0 migration renamed and
     * that is known to be equivalent - see [isEquivalentPriorImage]. Treated as
     * current: replacing it buys nothing.
     */
    EQUIVALENT_PRIOR,

    /** Different bytes, with no reason to think them equivalent. */
    DIFFERS
}

/**
 * Is [installedSha256] a pre-v0 image already known to be equivalent to the one
 * [catalogSha256] names?
 *
 * hd1k_combo-v0-3.5.1.img, and nothing else - z80cpmw's
 * diskv0::isEquivalentPriorImage and ioscpm's
 * CatalogMigration.equivalentPriorImage carry the same one pair. hd1k_combo is
 * the one image of twenty whose bytes differ between the pre-v0 catalog and
 * catalog-v0-3.5.1.json, and not anywhere a guest can reach: both are
 * 51,380,224 bytes, slices 1-5 are byte-identical, slice 0 lists the same 94
 * files and all 94 extract byte-identical. The 2,342 bytes that differ are CP/M
 * slack between files (romwbw_disks docs/FINDINGS.md section 10). Every
 * migrated device holds that image under a hash the catalog does not name, and
 * offering it a 49 MB update would replace slack with different slack.
 *
 * [migratedFromPreV0] is what keeps this an exception and not a hole: only the
 * v0 migration records it (V0MigrationResult.migratedPristine), and a download
 * clears it, so a fresh download can never be blessed by it, and it cannot
 * bless a damaged or unrelated image either - the hash still has to be the one.
 */
fun isEquivalentPriorImage(
    migratedFromPreV0: Boolean,
    installedSha256: String,
    catalogSha256: String
): Boolean {
    if (!migratedFromPreV0) return false
    val installed = installedSha256.lowercase()
    val catalog = catalogSha256.lowercase()
    return EQUIVALENT_PRIOR_IMAGES.any { it.catalog == catalog && it.prior == installed }
}

private data class EquivalentPair(val catalog: String, val prior: String)

// A list rather than a pair of constants because the shape is what a second
// entry would need, but there is deliberately only one.
private val EQUIVALENT_PRIOR_IMAGES = listOf(
    EquivalentPair(
        catalog = "0ca4ec60cb8bca71b8f0287c4b634c3126887be483db9b59b41bdff424f89303",
        prior = "89b8ae1aaa6867dc515c3511b34c4f0c311a77e99ff71066f5a774bef99cde1d"
    )
)

/**
 * Classify an installed image. A catalog entry with no sha256 states nothing to
 * compare against, so the image cannot be shown to differ and counts as
 * matching.
 */
fun classifyInstalledImage(
    installedSha256: String,
    catalogSha256: String,
    migratedFromPreV0: Boolean
): InstalledImage = when {
    catalogSha256.isEmpty() -> InstalledImage.MATCHES_CATALOG
    installedSha256.equals(catalogSha256, ignoreCase = true) -> InstalledImage.MATCHES_CATALOG
    isEquivalentPriorImage(migratedFromPreV0, installedSha256, catalogSha256) ->
        InstalledImage.EQUIVALENT_PRIOR
    else -> InstalledImage.DIFFERS
}
