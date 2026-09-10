package com.awohl.cpmdroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * The help list, against the document that is actually published.
 *
 * The fixture is src/test/resources/index-v0.json - the same byte-for-byte copy
 * of romwbw_disks catalog/v0/index.json that CatalogParsingTest reads, and kept
 * honest by the same drift check there. Help is a block INSIDE that document
 * now rather than a second one beside it, so there is nothing extra to keep in
 * step: if the index fixture is current, the help fixture is current.
 *
 * What is worth testing here is exactly what changed. The parser this replaces
 * read required fields through getters inside one try/catch, so one topic the
 * build did not understand returned null for the whole document and took the
 * other six with it; and it composed "<base_url>null" for an entry with no
 * filename. Both are asserted against below.
 */
class HelpCatalogParsingTest {

    private fun fixture(name: String): String =
        // Line endings normalised, and only line endings - see the same helper
        // in CatalogParsingTest for why the checked-out bytes are not always
        // the published ones on Windows.
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }
            .toString(Charsets.UTF_8).replace("\r\n", "\n")

    private fun index() = fixture("index-v0.json")

    //-------------------------------------------------------------------------
    // The published document
    //-------------------------------------------------------------------------

    @Test
    fun theHelpBlockOfThePublishedIndexIsTheTopicList() {
        val help = parseHelpIndex(index())
        assertNotNull("index-v0.json carries a help block", help)
        help!!

        assertEquals(
            "https://github.com/avwohl/romwbw_disks/releases/download/help-v0/",
            help.baseUrl
        )
        assertEquals(
            listOf("quick_start", "cpm22", "zsdos", "nzcom", "zpm3", "qpm", "disk_transfer"),
            help.topics.map { it.id }
        )

        val quickStart = help.topics.first()
        // `name` in the index, `title` in the model - the legacy document spelled
        // it the second way and both have to land in the same field.
        assertEquals("Quick Start Guide", quickStart.title)
        assertEquals("Getting started with the emulator", quickStart.description)
        assertEquals("help_quick_start.md", quickStart.filename)
        assertNull("published topics are routed by filename, not by url", quickStart.url)
        assertEquals(7387L, quickStart.size)
        assertEquals(
            "5948d8f451cc80761c3234caaff8d12cb7c30e7b7b6b4a4ee0f7c86ef2d1486d",
            quickStart.sha256
        )
    }

    @Test
    fun everyPublishedTopicCanBeFetchedAndVerified() {
        val help = parseHelpIndex(index())!!
        for (topic in help.topics) {
            assertEquals(
                topic.id + " is base_url + filename with nothing inserted",
                help.baseUrl + topic.filename,
                helpTopicUrl(help, topic)
            )
            assertTrue(topic.id + " publishes a size", topic.size > 0)
            assertEquals(topic.id + " publishes a sha256", 64, topic.sha256.length)
        }
    }

    //-------------------------------------------------------------------------
    // What the offline tiers can hold
    //-------------------------------------------------------------------------

    @Test
    fun theStandaloneDocumentThisAppUsedToFetchStillParses() {
        // Verbatim the shape of the help_index.json 1.29 and earlier fetched and
        // saved as their offline copy. Every device that ran one of those builds
        // has this on disk, and refusing it would take help away from precisely
        // the reader the cache exists for.
        val legacy = LEGACY_INDEX

        val help = parseHelpIndex(legacy)
        assertNotNull(help)
        assertEquals("Quick Start Guide", help!!.topics.single().title)
        assertEquals(
            "https://github.com/avwohl/cpmdroid/releases/latest/download/help_quick_start.md",
            helpTopicUrl(help, help.topics.single())
        )
        // No size and no hash in that shape, so nothing is checked rather than
        // everything failing to verify.
        assertEquals(0L, help.topics.single().size)
        assertEquals("", help.topics.single().sha256)
    }

    @Test
    fun anAbsoluteUrlOverridesTheBase() {
        val help = parseHelpIndex(TWO_ROUTES)!!
        assertEquals("https://example.invalid/help/a.md", helpTopicUrl(help, help.topics[0]))
        assertEquals("https://elsewhere.invalid/b.md", helpTopicUrl(help, help.topics[1]))
    }

    //-------------------------------------------------------------------------
    // Tolerance: the failure this parser was rewritten to stop
    //-------------------------------------------------------------------------

    @Test
    fun oneUnusableTopicDoesNotTakeTheOthersWithIt() {
        val help = parseHelpIndex(MIXED_TOPICS)!!
        assertEquals(listOf("good", "later"), help.topics.map { it.id })
    }

    @Test
    fun aTopicWithNoNameIsShownUnderItsId() {
        assertEquals("quick_start", parseHelpIndex(UNNAMED_TOPIC)!!.topics.single().title)
    }

    @Test
    fun aDocumentWithNothingUsableIsNull() {
        // An index published before the help block existed. The client shows the
        // list it has instead, and this is the return value that sends it there.
        assertNull(parseHelpIndex(NO_HELP_BLOCK))
        assertNull(parseHelpIndex(NO_TOPICS))
        assertNull(parseHelpIndex(EMPTY_TOPICS))
        // Not JSON at all - GitHub's HTML 404 page is what this looks like.
        assertNull(parseHelpIndex("<!DOCTYPE html><title>Not Found</title>"))
        assertNull(parseHelpIndex(""))
    }

    //-------------------------------------------------------------------------
    // Verifying what arrives
    //-------------------------------------------------------------------------

    @Test
    fun bytesAreCheckedAgainstWhatTheIndexPublished() {
        val body = "hello\n".toByteArray(Charsets.UTF_8)
        val sha = "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03"

        // The real thing passes, hashed the way the index generator hashes it -
        // this check has to accept and not only reject.
        assertNull(helpTopicMismatch(body, body.size.toLong(), sha))
        assertNull("hex case is not part of the claim", helpTopicMismatch(body, 0L, sha.uppercase()))

        // Nothing published: nothing checked. An index that stops carrying a
        // hash must not stop a reader from reading.
        assertNull(helpTopicMismatch(body, 0L, ""))

        assertNotNull("a body of the wrong length is refused", helpTopicMismatch(body, 99L, ""))
        assertNotNull(
            "a body of the right length that is not the topic is refused",
            helpTopicMismatch("hellO\n".toByteArray(Charsets.UTF_8), body.size.toLong(), sha)
        )
    }

    //-------------------------------------------------------------------------
    // The copy in the APK
    //-------------------------------------------------------------------------

    /**
     * The bundled index is the same shape and the same list as the published one.
     *
     * It is read only when neither the network nor this reader's saved copy can
     * be, which is exactly when nobody is watching - so nothing else would ever
     * notice it having drifted into a shape this build no longer parses. That is
     * not hypothetical: the copy it replaces named a base_url under
     * `avwohl/cpmdroid/releases/latest/download/`, which nothing publishes any
     * more, so every topic URL built from it would have 404ed.
     *
     * Sizes and hashes are deliberately NOT compared. They describe the bytes
     * romwbw_disks serves, this file is a floor rather than a mirror, and a
     * stale one costs an offline reader the newest wording and nothing else.
     */
    @Test
    fun theBundledIndexListsThePublishedTopics() {
        val bundled = moduleFile("src/main/assets/help/help_index.json")
        val help = parseHelpIndex(bundled.readText())
        assertNotNull("assets/help/help_index.json parses with the shipped parser", help)
        help!!

        val published = parseHelpIndex(index())!!
        assertEquals(published.baseUrl, help.baseUrl)
        assertEquals(published.topics.map { it.id }, help.topics.map { it.id })

        for (topic in help.topics) {
            val name = topic.filename!!
            assertTrue(
                "assets/help/" + name + " is listed in the bundled index and is not in the APK",
                moduleFile("src/main/assets/help/" + name).isFile
            )
        }
    }

    /**
     * A path under the app module, whichever directory the runner started in.
     *
     * Gradle runs unit tests with the module as the working directory and an IDE
     * may use the project root, so both are tried rather than one being assumed.
     */
    private fun moduleFile(path: String): File {
        val here = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(File(here, path), File(here, "app/" + path))
        return candidates.firstOrNull { it.exists() } ?: candidates.first()
    }

    private companion object {
        const val LEGACY_INDEX = """
            {
              "version": 1,
              "base_url": "https://github.com/avwohl/cpmdroid/releases/latest/download/",
              "topics": [
                {
                  "id": "quick_start",
                  "title": "Quick Start Guide",
                  "description": "Getting started with CPMDroid",
                  "filename": "help_quick_start.md"
                }
              ]
            }
        """

        const val TWO_ROUTES = """
            {"help": {"base_url": "https://example.invalid/help/", "topics": [
              {"id": "a", "name": "A", "filename": "a.md"},
              {"id": "b", "name": "B", "url": "https://elsewhere.invalid/b.md"}
            ]}}
        """

        /**
         * Four entries, two of them usable: one with no id at all, and one with
         * neither a filename nor a url, so there is nothing to fetch and nothing
         * to name a bundled copy with. The fourth carries a field this build has
         * never heard of, which CATALOG_SCHEMA 6.1 requires be ignored.
         */
        const val MIXED_TOPICS = """
            {"help": {"base_url": "https://example.invalid/help/", "topics": [
              {"id": "good", "name": "Good", "filename": "good.md"},
              {"name": "No id at all", "filename": "orphan.md"},
              {"id": "unreachable", "name": "Neither filename nor url"},
              {"id": "later", "name": "Later", "filename": "later.md", "future_field": 7}
            ]}}
        """

        const val UNNAMED_TOPIC = """
            {"help": {"base_url": "https://example.invalid/help/", "topics": [
              {"id": "quick_start", "filename": "help_quick_start.md"}
            ]}}
        """

        const val NO_HELP_BLOCK = """{"romwbw_versions": []}"""
        const val NO_TOPICS = """{"help": {"base_url": "https://x.invalid/"}}"""
        const val EMPTY_TOPICS = """{"help": {"base_url": "https://x.invalid/", "topics": []}}"""
    }
}
