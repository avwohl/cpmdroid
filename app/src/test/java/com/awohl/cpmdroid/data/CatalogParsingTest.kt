package com.awohl.cpmdroid.data

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/*
 * The two-level catalog parsers, against the documents that are actually
 * published.
 *
 * src/test/resources holds byte-for-byte copies of romwbw_disks
 * catalog/v0/index.json and the two catalog documents - 3310, 11826 and 15062
 * bytes as published. The last two are exactly the catalog_size the index
 * declares, and their sha256 is exactly the catalog_sha256 it declares, which
 * theCatalogFixturesAreTheDocumentsTheIndexFixtureDescribes() checks: an edit to
 * one of these files by hand stops being a copy loudly rather than quietly
 * becoming a paraphrase. Parsing the real thing is the point: every field name
 * here was read out of those files, not remembered, and the fields this app does
 * not use (upstream, cbios, slices, rom_count, notes) are in the fixtures too,
 * so "ignore unknown fields" is exercised by every test in the file rather than
 * only by the one named after it.
 *
 * A COPY NOTHING COMPARES TO ITS ORIGINAL DRIFTS, AND THAT USED TO BE INVISIBLE
 * HERE. These were taken at generation 1, when catalog-v0-3.6.0.json still read
 * `"status": "preview"`. romwbw_disks published generation 2 and promoted 3.6.0
 * out of preview; this copy did not follow, so six assertions went on describing
 * a ROM hash no catalog serves and a preview release that had been stable for
 * days - and passed every time, because a self-contained fixture is only ever
 * compared to itself. theFixturesStillMatchThePublishedDocuments() is the
 * answer: it reads the sibling romwbw_disks checkout when there is one and fails
 * on any difference, and skips visibly when there is not, so the suite still
 * needs no network and no sibling to run everywhere else.
 *
 * These need the REAL org.json, which is why app/build.gradle.kts puts
 * org.json:json on the unit-test classpath: android.jar's own org.json is a
 * stub whose every method throws "not mocked". The stub is deliberately left
 * un-defused - testOptions.unitTests.isReturnDefaultValues stays off - because
 * with it on, a missing dependency would make JSONObject return empty defaults
 * and these tests would pass while parsing nothing.
 */
class CatalogParsingTest {

    private fun fixtureBytes(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    private fun fixture(name: String): String =
        // Line endings normalised, and only line endings. These are checked in
        // with LF and Git hands them to a Windows working tree as CRLF, so the
        // bytes on this disk are not always the bytes that were published;
        // everything below - the size and hash checks included - is about the
        // document, which is the same either way.
        fixtureBytes(name).toString(Charsets.UTF_8).replace("\r\n", "\n")

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
            "942803d1ed67bcd8c6e0a9b730f9a08775618535b8e9affc58c56830839a78fd",
            older.catalogSha256
        )
        assertEquals(2, older.generation)

        // 3.6.0 is stable and is the index's default. That flag is the whole
        // release selection now: a fresh install follows it, and only a pick in
        // Settings pins something else. The build this replaced could not
        // follow it at all - a compile-time constant held every install on
        // 3.5.1, because 3.5.1 was what the ROM in assets/ declared.
        val current = versions[1]
        assertEquals("3.6.0", current.romwbwVersion)
        assertEquals("stable", current.status)
        assertFalse(current.isPreview)
        assertTrue(current.isDefault)
        assertFalse(older.isDefault)
        assertEquals(0x36, current.verByte)
        assertEquals(0x00, current.updByte)
        assertEquals(15062L, current.catalogSize)
        assertEquals(
            "4b4de2967482ab3f218df0ca065a9bdb9998b895792b720b2be3cddc3a6edbb1",
            current.catalogSha256
        )
        assertEquals(2, current.generation)
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
        assertEquals("3.5.1", selectRomwbwVersion(versions, "3.5.1")?.romwbwVersion)
    }

    @Test
    fun aStoredChoiceThatIsGoneFallsBackToTheIndexDefault() {
        val versions = parseRomwbwIndex(index())
        assertEquals("3.6.0", selectRomwbwVersion(versions, "3.4.0")?.romwbwVersion)
        // No stored choice is the fresh install, and it lands on whatever the
        // index flags rather than on a release this build was compiled around.
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
        assertEquals(2, catalog.generation)
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
        assertEquals("stable", catalog.status)
        assertEquals(2, catalog.generation)
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
            "01d1ca6d142e9b757d4fd98c2229f2e506dd8c3253839391c8f5d4f6263c6557",
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

    /**
     * Both published ROMs, whole, off the release the ROM picker was blind to.
     *
     * emu_rcz80 has been in the catalog since the migration and was unreachable
     * in every build that opened a ROM out of assets/, so it is the entry with
     * the least history of being looked at. The picker can only offer what the
     * parser carries and the download can only check what the parser carries, so
     * every field of both rows is read here rather than inferred from emu_avw's.
     */
    @Test
    fun bothPublishedRomsCarryTheirIdFilenameSizeHashAndDefaultFlag() {
        val roms = parseDiskCatalog(catalog351())!!.roms
        assertEquals(listOf("emu_avw", "emu_rcz80"), roms.map { it.id })

        val avw = roms.first { it.id == "emu_avw" }
        assertEquals("emu_avw-v0-3.5.1.rom", avw.filename)
        assertEquals("EMU AVW", avw.name)
        assertEquals(524288L, avw.size)
        assertEquals(
            "4b11402a29fad22de304775b7c415eb6a74600df06bd57828b9931a7e9693258",
            avw.sha256
        )
        assertTrue(avw.isDefault)

        val rcz80 = roms.first { it.id == "emu_rcz80" }
        assertEquals("emu_rcz80-v0-3.5.1.rom", rcz80.filename)
        assertEquals("EMU RCZ80", rcz80.name)
        assertEquals(524288L, rcz80.size)
        assertEquals(
            "03e646914628aea507eb8db560497292c728d26a127965b5b3cff6270af5feee",
            rcz80.sha256
        )
        assertFalse(rcz80.isDefault)
        assertEquals(
            "https://github.com/avwohl/romwbw_disks/releases/download/" +
                "v0-romwbw-3.5.1/emu_rcz80-v0-3.5.1.rom",
            rcz80.downloadUrl
        )
        assertEquals(0x35, rcz80.hcbVerByte)
        assertEquals(0x10, rcz80.hcbUpdByte)

        // What Settings stores is this id, and it resolves under either release
        // - which is what makes switching release a round trip rather than a
        // reset back to the flagged default.
        assertEquals("emu_rcz80", selectRom(roms, "emu_rcz80")?.id)
        assertEquals(
            "emu_rcz80-v0-3.6.0.rom",
            selectRom(parseDiskCatalog(catalog360())!!.roms, "emu_rcz80")?.filename
        )
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
        // ROM claim has to carry the release.
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

    //-------------------------------------------------------------------------
    // The fixtures are copies, and every copy above is only as good as this
    //-------------------------------------------------------------------------

    /**
     * The two catalog fixtures are the documents the index fixture describes.
     *
     * This needs nothing outside the repository, which is why it is worth
     * having on its own: the index publishes a catalog_size and a catalog_sha256
     * per release, so refreshing one of the three files without the other two
     * fails here on every machine, with no sibling checkout and no network. It
     * does not catch all three being refreshed to the same stale generation -
     * that is what the sibling comparison below is for.
     */
    @Test
    fun theCatalogFixturesAreTheDocumentsTheIndexFixtureDescribes() {
        val declared = parseRomwbwIndex(index()).associateBy { it.romwbwVersion }
        val fixtures = mapOf(
            "3.5.1" to "catalog-v0-3.5.1.json",
            "3.6.0" to "catalog-v0-3.6.0.json"
        )

        for ((version, resource) in fixtures) {
            val entry = declared[version]!!
            // The published bytes, not the checked-out ones - see fixture().
            val published = fixture(resource).toByteArray(Charsets.UTF_8)

            assertEquals(
                "$resource is not the size index-v0.json declares for $version",
                entry.catalogSize,
                published.size.toLong()
            )
            assertEquals(
                "$resource is not the document index-v0.json hashes for $version",
                entry.catalogSha256,
                MessageDigest.getInstance("SHA-256").digest(published)
                    .joinToString("") { "%02x".format(it) }
            )
        }
    }

    /**
     * The fixtures still match what romwbw_disks publishes.
     *
     * Nothing else in this suite reads a file outside this repository, so
     * nothing else can turn red when the catalog moves - which is how six
     * assertions here came to describe a generation-1 document for two days
     * after generation 2 was published. This is the comparison that was missing.
     *
     * It SKIPS when there is no romwbw_disks checkout beside this one, because
     * there is none on a CI runner and none on a machine that only clones this
     * repository, and the suite's other twenty-odd tests must keep running
     * there. The skip is announced rather than silent: a check that quietly
     * passes when it cannot find the thing it checks is the bug this test
     * exists to end. Point it somewhere else with -Dromwbw.disks.dir=... or
     * ROMWBW_DISKS_DIR when the checkout is not an ancestor's child.
     */
    @Test
    fun theFixturesStillMatchThePublishedDocuments() {
        val published = publishedCatalogDir()
        if (published == null) {
            println(NO_SIBLING)
        }
        assumeTrue(NO_SIBLING, published != null)
        published!!

        assertSamePublishedDocument(published, "index.json", "index-v0.json", index())
        assertSamePublishedDocument(
            published, "3.5.1/catalog.json", "catalog-v0-3.5.1.json", catalog351()
        )
        assertSamePublishedDocument(
            published, "3.6.0/catalog.json", "catalog-v0-3.6.0.json", catalog360()
        )
    }

    /**
     * romwbw_disks/catalog/v0 beside this checkout, or null.
     *
     * The Gradle unit-test working directory is the app module -
     * C:/…/cpmdroid/app, which was measured rather than assumed - so the sibling
     * is two levels up from where this runs and one level up from the project
     * root. Every ancestor is tried rather than exactly two, so the same test
     * also finds it when the runner picks the project root (an IDE) or when the
     * checkout sits one directory deeper (a git worktree).
     */
    private fun publishedCatalogDir(): File? {
        val override = System.getProperty("romwbw.disks.dir")
            ?: System.getenv("ROMWBW_DISKS_DIR")
        if (!override.isNullOrEmpty()) {
            // An override that names nothing FAILS rather than skipping. Only a
            // person typing it can produce it, and answering a typo with a
            // silent pass is the failure this whole test was written against.
            val fromOverride = File(override, "catalog/v0")
            if (!File(fromOverride, "index.json").isFile) {
                fail("no catalog/v0/index.json under $override, which was named explicitly")
            }
            return fromOverride
        }

        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "romwbw_disks/catalog/v0")
            if (File(candidate, "index.json").isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    /**
     * Compare a fixture with the published document it is a copy of, and say
     * where they first differ rather than printing fifteen kilobytes twice.
     */
    private fun assertSamePublishedDocument(
        catalogDir: File,
        publishedPath: String,
        fixtureName: String,
        fixture: String
    ) {
        val file = File(catalogDir, publishedPath)
        val live = file.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n")
        if (fixture == live) return

        val ours = fixture.lines()
        val theirs = live.lines()
        val at = (0 until maxOf(ours.size, theirs.size))
            .first { ours.getOrNull(it) != theirs.getOrNull(it) }
        fail(
            "src/test/resources/$fixtureName has drifted from ${file.path}.\n" +
                "First difference at line ${at + 1}:\n" +
                "  fixture:   ${ours.getOrNull(at) ?: "<end of file>"}\n" +
                "  published: ${theirs.getOrNull(at) ?: "<end of file>"}\n" +
                "Copy the published document over the fixture and fix the assertions " +
                "that describe it - the published side is the one that is right."
        )
    }

    private companion object {
        const val NO_SIBLING =
            "SKIPPED: no romwbw_disks checkout beside this one, so there is nothing on " +
                "this machine to compare the fixtures against. Clone it as a sibling of " +
                "cpmdroid, or point -Dromwbw.disks.dir / ROMWBW_DISKS_DIR at it, to have " +
                "this run."
    }
}
