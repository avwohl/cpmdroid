package com.awohl.cpmdroid.data

import com.awohl.cpmdroid.data.SettingsRepository.Companion.DEFAULT_INDEX_URL
import com.awohl.cpmdroid.data.SettingsRepository.Companion.indexUrlProblem
import com.awohl.cpmdroid.data.SettingsRepository.Companion.isCustomIndex
import com.awohl.cpmdroid.data.SettingsRepository.Companion.resolveIndexUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The catalog index setting: what the Settings field accepts, and what it
 * resolves to.
 *
 * The field is how a romwbw_disks release is tested before it is published, so
 * the thing to get right is that it REFUSES almost nothing. What is at the
 * other end decides whether an index is any good, and it is fetched, size- and
 * hash-checked immediately afterwards; anything rejected here has to be
 * something that could not possibly work.
 */
class IndexUrlTest {

    @Test
    fun emptyMeansTheDefaultIndex() {
        assertNull("clearing the field is how you get back", indexUrlProblem(""))
        assertNull(indexUrlProblem("   "))
        assertEquals(DEFAULT_INDEX_URL, resolveIndexUrl(""))
        assertEquals(DEFAULT_INDEX_URL, resolveIndexUrl(null))
        assertFalse(isCustomIndex(""))
        assertFalse(isCustomIndex(DEFAULT_INDEX_URL))
    }

    @Test
    fun anIndexAnywhereIsAccepted() {
        // A fork, a draft release, a laptop serving a directory, a plain IP with
        // a port. None of these is unusual and all of them are the point.
        val fine = listOf(
            "https://github.com/someone/romwbw_disks/releases/latest/download/index-v0.json",
            "https://example.invalid/catalogs/index-v0.json",
            "http://192.168.1.20:8000/index-v0.json",
            "https://example.invalid/catalog?iface=v0",
            "https://example.invalid/some/path/with/no/extension"
        )
        for (url in fine) {
            assertNull("$url should be accepted", indexUrlProblem(url))
            assertTrue("$url is not the default index", isCustomIndex(url))
            assertEquals(url, resolveIndexUrl(url))
        }
    }

    @Test
    fun whitespaceIsTrimmedRatherThanRefused() {
        val padded = "  https://example.invalid/index-v0.json  "
        assertNull(indexUrlProblem(padded))
        assertEquals("https://example.invalid/index-v0.json", resolveIndexUrl(padded))
    }

    @Test
    fun somethingThatCouldNotPossiblyWorkIsRefused() {
        // Not a URL at all - a repo name, or a file path pasted by hand.
        assertNotNull(indexUrlProblem("avwohl/romwbw_disks"))
        assertNotNull(indexUrlProblem("/sdcard/index-v0.json"))
        assertNotNull(indexUrlProblem("ftp://example.invalid/index-v0.json"))
        // A host with no document on it: there is nothing at that address to
        // parse, and the failure it would produce says nothing about why.
        assertNotNull(indexUrlProblem("https://example.invalid"))
        assertNotNull(indexUrlProblem("https://example.invalid/"))
        assertNotNull(indexUrlProblem("https://"))
    }

    @Test
    fun theGitHubPageUrlIsNamedForWhatItIs() {
        // The mistake a person actually makes: copying the address bar while
        // looking at the file on GitHub. It answers 200 with HTML, so without
        // this the report is "the response was not a catalog index" - which
        // reads as a broken catalog rather than as the wrong URL.
        val problem = indexUrlProblem(
            "https://github.com/avwohl/romwbw_disks/blob/master/catalog/v0/index.json"
        )
        assertNotNull(problem)
        assertTrue(
            "the message should point at the raw URL: $problem",
            problem!!.contains("raw", ignoreCase = true)
        )
    }

    @Test
    fun theDefaultUrlNamesNoReleaseTag() {
        // The property the whole two-level catalog rests on: romwbw_disks can
        // move, rename or re-cut the release the index sits on, and a shipped
        // client follows it. A tag here would have to be repinned by an app
        // release, which is the coupling this design exists to remove.
        assertTrue(DEFAULT_INDEX_URL.contains("/releases/latest/download/"))
        assertFalse(DEFAULT_INDEX_URL.contains("/releases/download/"))
        assertTrue(DEFAULT_INDEX_URL.endsWith("index-v0.json"))
    }
}
