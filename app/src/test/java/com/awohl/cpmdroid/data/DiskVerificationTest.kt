package com.awohl.cpmdroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** classifyInstalledImage and the one equivalent prior image. */
class DiskVerificationTest {

    private val comboCatalog = "0ca4ec60cb8bca71b8f0287c4b634c3126887be483db9b59b41bdff424f89303"
    private val comboPrior = "89b8ae1aaa6867dc515c3511b34c4f0c311a77e99ff71066f5a774bef99cde1d"
    private val other = "1111111111111111111111111111111111111111111111111111111111111111"

    @Test
    fun theCatalogsOwnBytesMatch() {
        assertEquals(InstalledImage.MATCHES_CATALOG,
            classifyInstalledImage(comboCatalog, comboCatalog, migratedFromPreV0 = false))
        assertEquals("compared without case", InstalledImage.MATCHES_CATALOG,
            classifyInstalledImage(comboCatalog.uppercase(), comboCatalog, migratedFromPreV0 = false))
    }

    @Test
    fun theMigratedComboImageIsEquivalent() {
        assertEquals(InstalledImage.EQUIVALENT_PRIOR,
            classifyInstalledImage(comboPrior, comboCatalog, migratedFromPreV0 = true))
    }

    /** The provenance is what makes it an exception and not a hole. */
    @Test
    fun thePriorHashWithoutTheMigrationIsNotEquivalent() {
        assertEquals(InstalledImage.DIFFERS,
            classifyInstalledImage(comboPrior, comboCatalog, migratedFromPreV0 = false))
        assertFalse(isEquivalentPriorImage(false, comboPrior, comboCatalog))
    }

    @Test
    fun aMigratedImageWithOtherBytesDiffers() {
        assertEquals("a damaged or unrelated image is not blessed by the migration",
            InstalledImage.DIFFERS,
            classifyInstalledImage(other, comboCatalog, migratedFromPreV0 = true))
        assertEquals("the pair is keyed on the catalog hash too",
            InstalledImage.DIFFERS,
            classifyInstalledImage(comboPrior, other, migratedFromPreV0 = true))
    }

    @Test
    fun aRepublishedImageDiffers() {
        assertEquals(InstalledImage.DIFFERS,
            classifyInstalledImage(other, comboCatalog, migratedFromPreV0 = false))
    }

    @Test
    fun aCatalogEntryWithNoHashCannotBeShownToDiffer() {
        assertEquals(InstalledImage.MATCHES_CATALOG,
            classifyInstalledImage(other, "", migratedFromPreV0 = false))
        assertTrue(isEquivalentPriorImage(true, comboPrior.uppercase(), comboCatalog.uppercase()))
    }
}
