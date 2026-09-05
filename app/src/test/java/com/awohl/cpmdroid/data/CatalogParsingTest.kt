package com.awohl.cpmdroid.data

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The two-level catalog parsers, against the documents that are actually
 * published.
 *
 * src/test/resources holds byte-for-byte copies of romwbw_disks
 * catalog/v0/index.json and the two catalog documents - 3310, 11826 and 14694
 * bytes, the last two exactly the catalog_size the index declares for them, so
 * a copy that drifts fails the size assertion below rather than quietly
 * becoming a paraphrase. Parsing the real thing is the point: every field name
 * here was read out of those files, not remembered, and the fields this app
 * does not use (upstream, cbios, slices, rom_count, notes) are in the fixtures
 * too, so "ignore unknown fields" is exercised by every test in the file rather
 * than only by the one named after it.
 *
 * The index copy was refreshed when the ROM download landed, and the refresh
 * matters: 3.6.0 was published `preview` and not default, and is now `stable`
 * and default. That is the state this whole feature exists for - a client that
 * preselected the default release and had no way to get its ROM would pair
 * 3.6.0 disks with the bundled 3.5.1 ROM - so the fixture has to be the
 * document that says so.
 *
 * These need the REAL org.json, which is why app/build.gradle.kts puts
 * org.json:json on the unit-test classpath: android.jar's own org.json is a
 * stub whose every method throws "not mocked". The stub is deliberately left
 * un-defused - testOptions.unitTests.isReturnDefaultValues stays off - because
 * with it on, a missing dependency would make JSONObject return empty defaults
 * and these tests would pass while parsing nothing.
 */
class CatalogParsingTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun index() = fixture("index-v0.json")
    private fun catalog351() = fixture("catalog-v0-3.5.1.json")
    private fun catalog360() = fixture("catalog-v0-3.6.0.json")

    //-------------------------------------------------------------------------
    // index-v0.json
    //-------------------------------------------------------------------------

    @Test
    fun bothPublishedReleasesAreParsed() {
        val versions = parseRomwbwIndex(index())
        assertEquals(2, versions.size)

        val older = versions[0]
        assertEquals("3.5.1", older.romwbwVersion)
        assertEquals("RomWBW 3.5.1", older.label)
        assertEquals("stable", older.status)
        assertFalse(older.isPreview)
        assertEquals(0x35, older.verByte)
        assertEquals(0x10, older.updByte)
        assertEquals(
            "https://github.com/avwohl/romwbw_disks/releases/download/" +
                "v0-romwbw-3.5.1/catalog-v0-3.5.1.json",
            older.catalogUrl
        )
        assertEquals(11826L, older.catalogSize)
        assertEquals(
            "7a5411b329be606c2bcc7b8d2b051b8fca9a2906f780d65fc98221cb6b61ed65",
            older.catalogSha256
        )
        assertEquals(1, older.generation)

        // The default moved to 3.6.0 when it was promoted out of preview, and
        // the bundled ROM did not move with it - which is the pairing the ROM
        // download exists to make impossible.
        val current = versions[1]
        assertEquals("3.6.0", current.romwbwVersion)
        assertEquals("stable", current.status)
        assertFalse(current.isPreview)
        assertTrue(current.isDefault)
        assertFalse(older.isDefault)
        assertEquals(0x36, current.verByte)
        assertEquals(0x00, current.updByte)
        assertEquals(14694L, current.catalogSize)
    }

    @Test
    fun hbiosBytesParseInBothSpellings() {
        assertEquals(0x35, parseHbiosByte("0x35"))
        assertEquals(0x35, parseHbiosByte("0X35"))
        assertEquals(0x35, parseHbiosByte("35"))
        assertEquals(0x00, parseHbiosByte("0x00"))
        assertEquals(0x0f, parseHbiosByte("0xf"))
        assertNull(parseHbiosByte(null))
        assertNull(parseHbiosByte(""))
        assertNull(parseHbiosByte("0x"))
        assertNull(parseHbiosByte("0x351"))
        assertNull(parseHbiosByte("zz"))
    }

    /**
     * One unusable entry costs that entry and nothing else.
     *
     * This is the whole reason the parser is not shaped like
     * HelpActivity.parseHelpIndex, whose required-field getters sit in one
     * try/catch that returns null for the document. Under that shape,
     * publishing a single malformed RomWBW 3.7.0 entry would take the catalog
     * away from every already-shipped client at once, including for the
     * releases still described perfectly.
     */
    @Test
    fun anEntryWithNoHbiosBytesIsSkippedAndTheRestSurvive() {
        val document = JSONObject(index())
        document.getJSONArray("romwbw_versions").getJSONObject(0).remove("hbios")

        val versions = parseRomwbwIndex(document.toString())
        assertEquals(1, versions.size)
        assertEquals("3.6.0", versions[0].romwbwVersion)
    }

    @Test
    fun anEntryWithNoCatalogUrlIsSkipped() {
        val document = JSONObject(index())
        document.getJSONArray("romwbw_versions").getJSONObject(1).remove("catalog_url")

        val versions = parseRomwbwIndex(document.toString())
        assertEquals(1, versions.size)
        assertEquals("3.5.1", versions[0].romwbwVersion)
    }

    /** Adding a field is not an interface break, so it must not read as one. */
    @Test
    fun unknownFieldsAtEveryLevelAreIgnored() {
        val document = JSONObject(index())
        document.put("something_added_in_2027", "whatever")
        val entry = document.getJSONArray("romwbw_versions").getJSONObject(0)
        entry.put("signing_key_url", "https://example.invalid/key")
        entry.getJSONObject("hbios").put("platform_name", "SBC")

        val versions = parseRomwbwIndex(document.toString())
        assertEquals(2, versions.size)
        assertEquals(0x35, versions[0].verByte)
    }

    @Test
    fun anIndexWithNoVersionsArrayParsesToNothingRatherThanThrowing() {
        assertEquals(0, parseRomwbwIndex("""{"schema":"romwbw-disks-index"}""").size)
    }

    /**
     * A GitHub 404 page is HTML, and the caller reports "that was not the
     * index" rather than a per-entry problem it never got to.
     */
    @Test(expected = JSONException::class)
    fun somethingThatIsNotJsonThrows() {
        parseRomwbwIndex("<!DOCTYPE html><html><body>Not Found</body></html>")
    }

    //-------------------------------------------------------------------------
    // Which releases to offer
    //-------------------------------------------------------------------------

    @Test
    fun onlyReleasesTheCoreAcceptsAreOffered() {
        val versions = parseRomwbwIndex(index())

        // A core checked against 3.5.1 alone - which is every client build
        // before romwbw_emu v1.39.
        val only351 = runnableRomwbwVersions(versions) { ver, upd -> ver == 0x35 && upd == 0x10 }
        assertEquals(1, only351.size)
        assertEquals("3.5.1", only351[0].romwbwVersion)

        // A core checked against both, which is what this build compiles.
        assertEquals(2, runnableRomwbwVersions(versions) { _, _ -> true }.size)

        // And a core that can run nothing published: a real condition, reported
        // rather than papered over, because no retry fixes it.
        assertTrue(runnableRomwbwVersions(versions) { _, _ -> false }.isEmpty())
    }

    @Test
    fun theStoredChoiceWinsWhenItIsStillOnOffer() {
        val versions = parseRomwbwIndex(index())
        assertEquals("3.6.0", selectRomwbwVersion(versions, "3.6.0")?.romwbwVersion)
    }

    @Test
    fun aStoredChoiceThatIsGoneFallsBackToTheIndexDefault() {
        val versions = parseRomwbwIndex(index())
        assertEquals("3.6.0", selectRomwbwVersion(versions, "3.4.0")?.romwbwVersion)
        assertEquals("3.6.0", selectRomwbwVersion(versions, null)?.romwbwVersion)
    }

    /**
     * The generator enforces exactly one `default: true`, and a client still has
     * to cope with zero or two rather than crash.
     */
    @Test
    fun withNoDefaultTheFirstSurvivorIsUsedAndAnEmptyListIsNull() {
        val versions = parseRomwbwIndex(index()).map { it.copy(isDefault = false) }
        assertEquals("3.5.1", selectRomwbwVersion(versions, null)?.romwbwVersion)
        assertNull(selectRomwbwVersion(emptyList(), "3.5.1"))
    }

    //-------------------------------------------------------------------------
    // catalog-v0-<ver>.json
    //-------------------------------------------------------------------------

    @Test
    fun thePublishedThreeFiveOneCatalogParses() {
        val catalog = parseDiskCatalog(catalog351())!!
        assertEquals("3.5.1", catalog.romwbwVersion)
        assertEquals("stable", catalog.status)
        assertEquals(1, catalog.generation)
        assertEquals(
            "https://github.com/avwohl/romwbw_disks/releases/download/v0-romwbw-3.5.1/",
            catalog.baseUrl
        )
        assertEquals(20, catalog.disks.size)

        val combo = catalog.disks.first { it.id == "hd1k_combo" }
        assertEquals("hd1k_combo-v0-3.5.1.img", combo.filename)
        assertEquals("Combo (Recommended)", combo.name)
        assertEquals(51380224L, combo.size)
        assertEquals(
            "0ca4ec60cb8bca71b8f0287c4b634c3126887be483db9b59b41bdff424f89303",
            combo.sha256
        )
        assertEquals("Mixed", combo.license)
        assertEquals(0, combo.defaultSlot)

        // base_url ends in "/" by contract and nothing inserts a separator -
        // the fixup iOS had to delete would have produced a doubled slash here.
        assertEquals(
            "https://github.com/avwohl/romwbw_disks/releases/download/" +
                "v0-romwbw-3.5.1/hd1k_combo-v0-3.5.1.img",
            combo.downloadUrl
        )
    }

    /** defaultSlot is optional, and 0 is a real slot rather than "absent". */
    @Test
    fun onlyTheStarterDiskCarriesADefaultSlot() {
        val catalog = parseDiskCatalog(catalog351())!!
        val withSlot = catalog.disks.filter { it.defaultSlot != null }
        assertEquals(1, withSlot.size)
        assertEquals("hd1k_combo", withSlot[0].id)
        assertEquals(0, withSlot[0].defaultSlot)
    }

    /**
     * Disks appear and disappear between releases, and that is not a break.
     * hd1k_ws4 exists under 3.5.1 and not under 3.6.0, where upstream's slice 5
     * became 'wp'.
     */
    @Test
    fun theThreeSixZeroCatalogHasADifferentDiskSet() {
        val catalog = parseDiskCatalog(catalog360())!!
        assertEquals("3.6.0", catalog.romwbwVersion)
        assertEquals("preview", catalog.status)
        assertEquals(24, catalog.disks.size)
        assertTrue(catalog.disks.none { it.id == "hd1k_ws4" })
        assertNotNull(catalog.disks.firstOrNull { it.id == "hd1k_wp" })
        assertTrue(catalog.disks.all { it.filename.endsWith("-v0-3.6.0.img") })
    }

    @Test
    fun oneUnusableDiskEntryCostsThatEntryAlone() {
        val document = JSONObject(catalog351())
        document.getJSONArray("disks").getJSONObject(4).remove("id")

        val catalog = parseDiskCatalog(document.toString())!!
        assertEquals(19, catalog.disks.size)
        assertNotNull(catalog.disks.firstOrNull { it.id == "hd1k_combo" })
    }

    @Test
    fun unknownDiskFieldsAreIgnored() {
        val document = JSONObject(catalog351())
        document.put("published_by", "a future release")
        document.getJSONArray("disks").getJSONObject(0).put("compression", "none")

        val catalog = parseDiskCatalog(document.toString())!!
        assertEquals(20, catalog.disks.size)
    }

    //-------------------------------------------------------------------------
    // roms[], which is what stops the ROM being version-coupled
    //-------------------------------------------------------------------------

    @Test
    fun thePublishedRomsAreParsedWithTheirUrlsAndHcbBytes() {
        val catalog = parseDiskCatalog(catalog360())!!
        assertEquals(2, catalog.roms.size)

        val avw = catalog.roms.first { it.id == "emu_avw" }
        assertEquals("emu_avw-v0-3.6.0.rom", avw.filename)
        assertEquals("EMU AVW", avw.name)
        assertEquals(524288L, avw.size)
        assertEquals(
            "2f4a6252400276e2306d180704d33f70c92651d11a3e0780cd8a58e9921e8c4e",
            avw.sha256
        )
        assertTrue(avw.isDefault)
        assertEquals(
            "https://github.com/avwohl/romwbw_disks/releases/download/" +
                "v0-romwbw-3.6.0/emu_avw-v0-3.6.0.rom",
            avw.downloadUrl
        )
        // hcb.version/hcb.update, the two bytes emu_validate_rom_hcb reads at
        // 0x105/0x106. Hex strings in the document, like hbios.ver_byte, and
        // the same values the index publishes for this release.
        assertEquals(0x36, avw.hcbVerByte)
        assertEquals(0x00, avw.hcbUpdByte)

        val rcz80 = catalog.roms.first { it.id == "emu_rcz80" }
        assertFalse(rcz80.isDefault)
        assertEquals("emu_rcz80-v0-3.6.0.rom", rcz80.filename)
    }

    /** The 3.5.1 ROMs are a different file set under the same two ids. */
    @Test
    fun eachReleasePublishesItsOwnRomFilenames() {
        val roms351 = parseDiskCatalog(catalog351())!!.roms
        val roms360 = parseDiskCatalog(catalog360())!!.roms

        assertEquals(roms351.map { it.id }, roms360.map { it.id })
        assertTrue(roms351.all { it.filename.endsWith("-v0-3.5.1.rom") })
        assertTrue(roms360.all { it.filename.endsWith("-v0-3.6.0.rom") })
        // Different bytes under the same id: the whole reason a stored slot or
        // ROM name has to carry the release.
        assertTrue(
            roms351.first { it.id == "emu_avw" }.sha256 !=
                roms360.first { it.id == "emu_avw" }.sha256
        )
        assertEquals(0x35, roms351.first { it.id == "emu_avw" }.hcbVerByte)
        assertEquals(0x10, roms351.first { it.id == "emu_avw" }.hcbUpdByte)
    }

    /**
     * An absent roms[] is a release with no ROM, not a document to reject: 6.1
     * says do not hardcode two entries and do not assume emu_avw is there.
     */
    @Test
    fun aCatalogWithNoRomsArrayStillParsesAndOffersNoRom() {
        val document = JSONObject(catalog351())
        document.remove("roms")

        val catalog = parseDiskCatalog(document.toString())!!
        assertEquals(20, catalog.disks.size)
        assertTrue(catalog.roms.isEmpty())
        assertNull(selectRom(catalog.roms))
    }

    @Test
    fun oneUnusableRomEntryCostsThatEntryAlone() {
        val document = JSONObject(catalog351())
        document.getJSONArray("roms").getJSONObject(0).remove("filename")

        val catalog = parseDiskCatalog(document.toString())!!
        assertEquals(1, catalog.roms.size)
        assertEquals("emu_rcz80", catalog.roms[0].id)
        // With the flagged default gone, the survivor is what is offered.
        assertEquals("emu_rcz80", selectRom(catalog.roms)?.id)
    }

    @Test
    fun aRomEntryWithNoHcbBlockParsesWithNoBytesToCheck() {
        val document = JSONObject(catalog351())
        document.getJSONArray("roms").getJSONObject(0).remove("hcb")

        val rom = parseDiskCatalog(document.toString())!!.roms.first { it.id == "emu_avw" }
        assertNull(rom.hcbVerByte)
        assertNull(rom.hcbUpdByte)
        assertEquals("emu_avw-v0-3.5.1.rom", rom.filename)
    }

    /**
     * Refused whole rather than parsed into rows whose download URLs are bare
     * filenames: a relative URL handed to OkHttp throws, and twenty rows that
     * each fail on tap is a worse report than one that says so.
     */
    @Test
    fun aCatalogWithNoBaseUrlIsRefused() {
        val document = JSONObject(catalog351())
        document.remove("base_url")
        assertNull(parseDiskCatalog(document.toString()))
    }

    @Test
    fun aCatalogWithNoDisksParsesToAnEmptyList() {
        val document = JSONObject(catalog351())
        document.remove("disks")

        val catalog = parseDiskCatalog(document.toString())!!
        assertTrue(catalog.disks.isEmpty())
    }

    /**
     * The disk ids the pre-v0 migration knows about are exactly the 3.5.1 disk
     * set, which is what makes V0Migration.CATALOG_DISK_STEMS the right list to
     * refuse a user's own import against. If a future 3.5.1 catalog adds a disk,
     * this fails and says which side to change.
     */
    @Test
    fun theThreeFiveOneDiskIdsAreTheStemsTheRenamePassKnows() {
        val ids = parseDiskCatalog(catalog351())!!.disks.map { it.id }.toSet()
        assertEquals(20, ids.size)
        for (id in ids) {
            assertEquals(
                "$id should map to a v0 name",
                "$id-v0-3.5.1.img",
                v0NameOf("$id.img")
            )
        }
    }
}
