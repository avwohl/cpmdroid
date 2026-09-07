package com.awohl.cpmdroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Which ROM a release uses, and where it has to come from.
 *
 * Both are pure functions of values a catalog and a bundled image supply, and
 * both decide whether a machine starts at all - so they are the half of the ROM
 * download that can be settled off a device, and this is where that is done.
 * The half that cannot is the storage: reading 512 KB back and hashing it needs
 * a real getExternalFilesDir(), and MANUAL_CHECKS.md carries it.
 */
class RomSelectionTest {

    private fun rom(
        id: String,
        isDefault: Boolean = false,
        version: String = "3.6.0"
    ) = RomInfo(
        id = id,
        filename = "$id-v0-$version.rom",
        name = id.uppercase(),
        size = 524288L,
        sha256 = "0".repeat(64),
        isDefault = isDefault,
        downloadUrl = "https://example.invalid/$id-v0-$version.rom"
    )

    //-------------------------------------------------------------------------
    // Which entry of roms[]
    //-------------------------------------------------------------------------

    @Test
    fun theEntryFlaggedDefaultWins() {
        val first = rom("emu_rcz80")
        val flagged = rom("emu_avw", isDefault = true)

        // Deliberately with the flagged entry second, because taking roms[0]
        // gives the right answer on both published catalogs today and would go
        // wrong the first time the order changed - which reordering a manifest
        // is explicitly allowed to do.
        assertSame(flagged, selectRom(listOf(first, flagged)))
    }

    @Test
    fun withNothingFlaggedTheFirstEntryIsUsedAndThatIsNormal() {
        val first = rom("emu_rcz80")
        val second = rom("emu_avw")
        assertSame(first, selectRom(listOf(first, second)))
    }

    /**
     * The generator enforces one default per catalog, and a client still has to
     * cope with two rather than crash. First wins, which is the order the
     * document publishes.
     */
    @Test
    fun twoDefaultsResolveToTheFirstRatherThanFailing() {
        val a = rom("emu_avw", isDefault = true)
        val b = rom("emu_rcz80", isDefault = true)
        assertSame(a, selectRom(listOf(a, b)))
    }

    @Test
    fun anEmptyRomListHasNoAnswer() {
        assertNull(selectRom(emptyList()))
    }

    /**
     * Never by name. A catalog that publishes a different ROM set is not a
     * broken catalog - 6.1 says so in as many words, and the disk sets already
     * differ between the two published releases.
     */
    @Test
    fun aCatalogWithoutEmuAvwStillOffersItsOwnDefault() {
        val roms = listOf(rom("emu_duo"), rom("emu_sbc", isDefault = true))
        assertEquals("emu_sbc", selectRom(roms)?.id)
    }

    @Test
    fun theClaimIsWhatOutlivesTheDocument() {
        val entry = rom("emu_avw", isDefault = true)
        val claim = entry.claim()
        assertEquals("emu_avw-v0-3.6.0.rom", claim.filename)
        assertEquals(524288L, claim.size)
        assertEquals(entry.sha256, claim.sha256)
    }

    //-------------------------------------------------------------------------
    // Which ROM a release boots
    //-------------------------------------------------------------------------

    /**
     * The pick wins over the flag. This is what makes the Settings ROM row do
     * anything at all: emu_rcz80 has been published since the migration and was
     * unreachable in every build until a pick could override `default: true`.
     */
    @Test
    fun aPickedRomWinsOverTheDefaultFlag() {
        val roms = listOf(rom("emu_avw", isDefault = true), rom("emu_rcz80", isDefault = false))
        assertEquals("emu_rcz80", selectRom(roms, "emu_rcz80")?.id)
    }

    /**
     * A pick this release does not publish falls back rather than failing.
     * roms[] is per-release, so choosing emu_rcz80 under one release and
     * switching to another that publishes only emu_avw is an ordinary thing to
     * do, and it has to land on a bootable ROM. The pick stays in preferences,
     * so switching back restores it.
     */
    @Test
    fun aPickThisReleaseDoesNotPublishFallsBackToTheDefault() {
        val roms = listOf(rom("emu_avw", isDefault = true))
        assertEquals("emu_avw", selectRom(roms, "emu_rcz80")?.id)
    }

    /** No pick is the ordinary case: the flagged default. */
    @Test
    fun withNoPickTheFlaggedDefaultIsUsed() {
        val roms = listOf(rom("emu_rcz80", isDefault = false), rom("emu_avw", isDefault = true))
        assertEquals("emu_avw", selectRom(roms, null)?.id)
    }

    /**
     * A claim carries the id it was fetched for, which is what lets an offline
     * launch tell "the ROM I have is the one I want" from "the one I wanted
     * last time". Without it a pick would be stored and then ignored forever,
     * because a verified file already on disk answers first.
     */
    @Test
    fun aClaimRecordsWhichRomItWasFetchedFor() {
        assertEquals("emu_rcz80", rom("emu_rcz80", isDefault = false).claim().romId)
    }

    //-------------------------------------------------------------------------
    // What is said when there is no usable ROM
    //-------------------------------------------------------------------------

    /**
     * Every one of these names the release, and the ones that can name a file
     * name that too. "It did not start" with no further detail is
     * indistinguishable from a crash, and the causes want different responses:
     * fetch it, or go back to the release the package can boot.
     */
    @Test
    fun everyRomFailureNamesTheReleaseAndTheFile() {
        assertTrue(RomFailure.NeverFetched("3.6.0").message!!.contains("3.6.0"))
        assertTrue(RomFailure.NoRomPublished("3.6.0").message!!.contains("3.6.0"))

        val missing = RomFailure.NotDownloaded("3.6.0", "emu_avw-v0-3.6.0.rom")
        assertTrue(missing.message!!.contains("3.6.0"))
        assertTrue(missing.message!!.contains("emu_avw-v0-3.6.0.rom"))

        val bad = RomFailure.DidNotVerify("3.6.0", "emu_avw-v0-3.6.0.rom", "sha256 abc")
        assertTrue(bad.message!!.contains("emu_avw-v0-3.6.0.rom"))
        assertTrue(bad.message!!.contains("sha256 abc"))

        val wrong = RomFailure.WrongRelease("3.6.0", "emu_avw-v0-3.6.0.rom", "35 10")
        assertTrue(wrong.message!!.contains("35 10"))
        assertTrue(wrong.message!!.contains("3.6.0"))
    }

    /**
     * A transfer that never produced a file is not a ROM that failed its hash,
     * and must not be described as one: a dropped connection reported as "did
     * not verify" sends the user to look at their storage instead of their
     * signal. Both sentences have to work for the same field, because the
     * reason a download failed can be either.
     */
    @Test
    fun aFailedTransferIsNotACorruptFile() {
        val dropped = RomFailure.CouldNotFetch(
            "3.6.0", "emu_avw-v0-3.6.0.rom", "Connection reset"
        )
        assertTrue(dropped.message!!.contains("3.6.0"))
        assertTrue(dropped.message!!.contains("emu_avw-v0-3.6.0.rom"))
        assertTrue(dropped.message!!.contains("Connection reset"))
        assertFalse(dropped.message!!.contains("did not verify"))
    }
}
