package com.awohl.cpmdroid.data

import org.json.JSONObject
import java.security.MessageDigest

/*
 * The in-app help topics, as index-v0.json publishes them.
 *
 * HELP IS A CATALOG ENTRY. It used to be a document of its own, attached to
 * THIS repository's latest release - `avwohl/cpmdroid/releases/latest/download/
 * help_index.json` was compiled into HelpActivity - which made a typo fix in a
 * help topic need an app release, and made whichever cpmdroid release carried
 * the Latest flag load-bearing forever. romwbw_disks publishes the topics on its
 * own `help-v0` tag now and names that tag in the index's `help` block, beside
 * the RomWBW releases, so the location is data like every other asset location:
 * base_url plus a filename, with a size and a sha256 to check what arrives.
 * Nothing in this app knows the tag's name, which is what lets romwbw_disks
 * rename it, re-cut it or move it to another host with no release here.
 *
 * The parser is a pure function of a String, with no Context and no android.*
 * import beyond org.json, so app/src/test can hand it the document that is
 * actually published rather than a paraphrase of it.
 */

/**
 * One row of the help list.
 *
 * [size] and [sha256] are what the topic's bytes are checked against on
 * arrival - help was the only content this family published that nothing
 * verified. Both are absent from the legacy document shape and from any entry
 * the index routes at an absolute [url], and an absent one is a check that is
 * skipped rather than one that fails, the same degradation a catalog document
 * gets when the index publishes no hash for it.
 */
data class HelpTopic(
    val id: String,
    val title: String,
    val description: String,
    /** Relative to [HelpIndex.baseUrl], and also the name of the bundled copy. */
    val filename: String?,
    /** A whole URL, which overrides base_url + filename when the index gives one. */
    val url: String?,
    val size: Long,
    val sha256: String
)

/** Where the topics live, and what they are. */
data class HelpIndex(
    val baseUrl: String,
    val topics: List<HelpTopic>
)

/**
 * A help list out of either document shape, or null when there is no usable one.
 *
 * TWO SHAPES, because three tiers read this and they do not all hold the same
 * document. The live one is index-v0.json, whose `help` block carries
 * `base_url` and topics named by `name`. The other is the standalone
 * help_index.json this app used to fetch: no `help` block, `topics` at the root,
 * and a `title` instead of a `name`. That shape is still on the disk of every
 * device that ran 1.29 or earlier, saved as its offline copy, and refusing it
 * would take help away from exactly the reader who has no network - the one the
 * cache exists for.
 *
 * Per-entry tolerance is deliberate and is the difference from the parser this
 * replaces, which read required fields through getters inside one try/catch: a
 * single topic shaped in a way this build does not understand returned null for
 * the whole document, and would have taken the other six with it. An entry with
 * no id, and no way to reach its bytes, is dropped; the rest are returned.
 *
 * Null means no tier answered here and the caller should try the next one:
 * unparseable, no topics array, or nothing in it that could be fetched or shown.
 */
fun parseHelpIndex(json: String): HelpIndex? {
    val root = try {
        JSONObject(json)
    } catch (e: Exception) {
        return null
    }

    // The `help` block when there is one, and the root itself when there is
    // not. An index published before that block existed, and the legacy
    // standalone document, both land on the second arm.
    val block = root.optJSONObject("help") ?: root

    val baseUrl = block.optString("base_url")
    val entries = block.optJSONArray("topics") ?: return null

    val topics = ArrayList<HelpTopic>(entries.length())
    for (i in 0 until entries.length()) {
        val entry = entries.optJSONObject(i) ?: continue

        val id = entry.optString("id")
        if (id.isEmpty()) continue

        val filename = entry.optString("filename").ifEmpty { null }
        val url = entry.optString("url").ifEmpty { null }
        // Neither a filename to append to the base nor a URL of its own, and
        // there is no bundled copy to fall back to either, because the bundled
        // copy is keyed on the filename. The old code composed "<base_url>null"
        // for this entry and fetched that.
        if (filename == null && url == null) continue

        topics.add(
            HelpTopic(
                id = id,
                // `name` is what the index calls it and `title` is what the
                // legacy document called it; the id is the last resort, so a
                // row is never blank.
                title = entry.optString("name")
                    .ifEmpty { entry.optString("title") }
                    .ifEmpty { id },
                description = entry.optString("description"),
                filename = filename,
                url = url,
                size = entry.optLong("size", 0L),
                sha256 = entry.optString("sha256")
            )
        )
    }

    if (topics.isEmpty()) return null
    // A missing base_url is NOT a reason to refuse the document. A topic that
    // cannot be reached over the network still has a cached copy and a copy in
    // the APK, and helpTopicUrl answers "" for it, which is what sends the
    // reader straight to those. Refusing here would be the one thing the offline
    // tiers exist to prevent: an unreadable list in front of readable text.
    return HelpIndex(baseUrl, topics)
}

/**
 * Where a topic's bytes are: its own URL, else the base plus its filename, else
 * "" for a row with no network source at all.
 *
 * NOTHING is inserted between the base and the filename. `base_url` ends with
 * "/" in the published document - that is the field's whole job, since the
 * three clients used to disagree about the separator - so a base that arrives
 * without one produces a URL that visibly fails rather than one this client
 * quietly repairs and the others do not.
 */
fun helpTopicUrl(index: HelpIndex, topic: HelpTopic): String =
    topic.url ?: topic.filename?.let { if (index.baseUrl.isEmpty()) "" else index.baseUrl + it } ?: ""

/**
 * Null when [bytes] are what the index said this topic is, and the reason when
 * they are not.
 *
 * The same rule the catalog documents and the ROM get: a claim of 0 or "" is a
 * check that is SKIPPED, because an index that stops publishing a hash must not
 * stop a reader from reading. What it catches is the case Content-Length cannot
 * - a whole, well-formed response that is not the document the list described,
 * which for help is what a moved tag or a rewritten asset looks like.
 *
 * A failure here is deliberately the same kind of failure as a 404: the caller
 * falls back to the copy this reader saved and then to the copy in the APK, so
 * a topic that cannot be verified shows text that can be, with the subtitle
 * saying where it came from.
 */
fun helpTopicMismatch(bytes: ByteArray, expectedSize: Long, expectedSha256: String): String? {
    if (expectedSize > 0 && bytes.size.toLong() != expectedSize) {
        return "got ${bytes.size} bytes, the catalog says $expectedSize"
    }
    if (expectedSha256.isNotEmpty()) {
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            return "sha256 $actual, the catalog says $expectedSha256"
        }
    }
    return null
}
