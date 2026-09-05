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
 * catalog/v0/index.json and the two catalog documents - 2942, 11826 and 14694
 * bytes, the last two exactly the catalog_size the index declares for them, so
 * a copy that drifts fails the size assertion below rather than quietly
 * becoming a paraphrase. Parsing the real thing is the point: every field name
 * here was read out of those files, not remembered, and the fields this app
 * does not use (upstream, cbios, slices, rom_count, notes, roms) are in the
 * fixtures too, so "ignore unknown fields" is exercised by every test in the
 * file rather than only by the one named after it.
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

        val stable = versions[0]
        assertEquals("3.5.1", stable.romwbwVersion)
        assertEquals("RomWBW 3.5.1", stable.label)
        assertEquals("stable", stable.status)
        assertTrue(stable.isDefault)
        assertFalse(stable.isPreview)
        assertEquals(0x35, stable.verByte)
        assertEquals(0x10, stable.updByte)
        assertEquals(
            "https://github.com/avwohl/romwbw_disks/releases/download/" +
                "v0-romwbw-3.5.1/catalog-v0-3.5.1.json",
            stable.catalogUrl
        )
        assertEquals(11826L, stable.catalogSize)
        assertEquals(
            "7a5411b329be606c2bcc7b8d2b051b8fca9a2906f780d65fc98221cb6b61ed65",
            stable.catalogSha256
        )
        assertEquals(1, stable.generation)

        val preview = versions[1]
        assertEquals("3.6.0", preview.romwbwVersion)
        assertEquals("preview", preview.status)
        assertTrue(preview.isPreview)
        assertFalse(preview.isDefault)
        assertEquals(0x36, preview.verByte)
        assertEquals(0x00, preview.updByte)
        assertEquals(14694L, preview.catalogSize)
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
        assertEquals("3.5.1", selectRomwbwVersion(versions, "3.4.0")?.romwbwVersion)
        assertEquals("3.5.1", selectRomwbwVersion(versions, null)?.romwbwVersion)
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

    /**
     * roms[] is not read at all, so neither its contents nor its presence may
     * matter: 6.1 says do not hardcode two entries and do not assume emu_avw is
     * there.
     */
    @Test
    fun aCatalogWithNoRomsArrayStillParses() {
        val document = JSONObject(catalog351())
        document.remove("roms")

        val catalog = parseDiskCatalog(document.toString())!!
        assertEquals(20, catalog.disks.size)
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
