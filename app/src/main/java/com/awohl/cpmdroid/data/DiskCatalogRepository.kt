// Backs row 5 of z80cpmw/FEATURE_PARITY.md. There is no release tag to repin
// any more: what dates that column now is which RomWBW releases index-v0.json
// publishes and which of them this build's core will load.
package com.awohl.cpmdroid.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Shared OkHttpClient — single connection pool and thread pool for all network I/O. */
internal val sharedHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES)
    .followRedirects(true)
    .build()

/**
 * Why a catalog fetch failed, in the terms a user can act on.
 *
 * There are now two round trips where there was one, and they fail for
 * unrelated reasons: the index can be unreachable while every catalog is fine,
 * a catalog can 404 on a release the index still lists, and the version list
 * can come back whole and contain nothing this build can run. Settings showed
 * "Failed to load disk catalog. Check your internet connection." for all of
 * them, which is wrong advice for two of the three - the connection is not the
 * problem when this binary's core supports no published release.
 */
sealed class CatalogFailure(message: String) : Exception(message) {

    /** The index URL did not answer, or did not answer with the index. */
    class IndexUnavailable(reason: String) :
        CatalogFailure("Could not read the catalog index: $reason")

    /**
     * The index parsed and this build can run none of what it publishes.
     *
     * A real, reportable condition rather than an empty list to shrug at: it
     * means romwbw_disks has stopped publishing every RomWBW release this
     * binary's emulator core has been checked against. [coreSupports] is
     * emu_romwbw_supported_list(), so the message names what would have to
     * appear for the app to work again.
     */
    class NoRunnableVersion(val coreSupports: String) :
        CatalogFailure(
            "The catalog publishes no RomWBW release this build can run " +
                "(this build's emulator supports $coreSupports)"
        )

    /**
     * The index no longer offers a release something is still asking for.
     *
     * Distinct from NoRunnableVersion, which says this build can run nothing at
     * all: here the index answered, this build can run some of what it
     * publishes, and the one release being asked about is not among them -
     * withdrawn upstream, or dropped by a core the app was rebuilt against.
     * The disks and preferences for it are untouched; there is simply nothing
     * to fetch for it.
     */
    class VersionNotOffered(val romwbwVersion: String) :
        CatalogFailure("The catalog index no longer publishes RomWBW $romwbwVersion")

    /** The selected release's catalog URL did not answer. */
    class CatalogUnavailable(val romwbwVersion: String, reason: String) :
        CatalogFailure("Could not download the RomWBW $romwbwVersion catalog: $reason")

    /**
     * The catalog came back but is not the document the index describes.
     *
     * Checked before parsing, not after: the size and hash in the index are
     * what distinguish a truncated or substituted catalog from a valid one, and
     * a parser that runs first would happily read half a document and report a
     * short disk list as though it were the whole catalog.
     */
    class CatalogCorrupt(val romwbwVersion: String, reason: String) :
        CatalogFailure("The RomWBW $romwbwVersion catalog did not verify: $reason")

    /** Parsed, verified, and lists no disks. */
    class CatalogEmpty(val romwbwVersion: String) :
        CatalogFailure("The RomWBW $romwbwVersion catalog lists no disks")
}

class DiskCatalogRepository {

    companion object {
        /**
         * The only URL this app compiles in.
         *
         * It replaces RELEASE_TAG = "v1.4.12" and the two ioscpm URLs built out
         * of it. Nothing may rebuild an asset URL from a tag again: this index
         * names each release's catalog_url, that catalog names its own
         * base_url, and every asset is base_url + filename. The failure that
         * shape removes is not hypothetical - a shipped release pinned a tag
         * GitHub answered 404 for, and no user of that build could download
         * anything at all.
         *
         * It goes through releases/latest/download and names no release tag.
         *
         * This paragraph used to argue the opposite - "deliberately a fixed tag
         * (`catalog-v0`) rather than releases/latest/download: this is the
         * interface-v0 index, and a v1 would live alongside it at a different
         * URL rather than replace it here" - and that was the mistake, stated
         * plainly enough to be worth correcting rather than deleting. A v1 at a
         * different URL is unreachable from a constant naming the old one, so
         * that arrangement made a v1 mean "rebuild every client on every
         * platform", which is the coupling the catalog exists to remove.
         *
         * A v1 ships as index-v1.json BESIDE index-v0.json on whichever release
         * carries the Latest flag: v0 clients keep reading v0, v1 clients read
         * v1, nobody rebuilds anything. HelpActivity floating on
         * releases/latest was right all along; the difference was the error.
         */
        /**
         * The default index, which is an ADDRESS and not a document: no
         * catalog of any kind is in this package.
         *
         * ONE literal, in SettingsRepository, because that is where the setting
         * that can replace it lives and two copies of this string would be two
         * sources of truth about which catalog is in play - the thing CLAUDE.md
         * names as the bug. What is actually fetched is
         * SettingsRepository.indexUrlInUse, which is this unless the device has
         * been pointed elsewhere.
         */
        const val INDEX_URL = SettingsRepository.DEFAULT_INDEX_URL

        /**
         * Cap on a document read into memory.
         *
         * The published index is 2.5 KB and the largest catalog is 14.7 KB, so
         * this is four orders of magnitude of headroom and exists for one case:
         * a URL that answers with something that is not a catalog at all. A
         * disk image on the same release is 49 MB, and reading one into a String
         * on a phone is an OutOfMemoryError, not an error message.
         */
        private const val MAX_DOCUMENT_BYTES = 1L * 1024 * 1024
    }

    private val client = sharedHttpClient

    /**
     * Fetch and parse index-v0.json.
     *
     * Returns every entry the document describes, unfiltered - deciding which
     * of them this build can run needs the emulator core and belongs to the
     * caller (see runnableRomwbwVersions). An index that parses to nothing is
     * returned as an empty list rather than as a failure, because "the index
     * answered and lists no releases" and "the index did not answer" are
     * different conditions and the caller reports them differently.
     */
    suspend fun fetchIndex(): Result<List<RomwbwVersion>> = withContext(Dispatchers.IO) {
        try {
            // The index in use, which is INDEX_URL unless this device has been
            // pointed somewhere else. Read from SettingsRepository rather than
            // held here, so that applying a new one takes effect on the next
            // fetch without this object being rebuilt.
            val request = Request.Builder()
                .url(SettingsRepository.indexUrlInUse).build()

            // use{}, not a bare execute(): the not-successful arm returns
            // without ever reading the body, and okhttp hands a connection back
            // to the pool only when the body is closed. Every call site in this
            // app shares sharedHttpClient, so those leaks all land in one pool.
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        CatalogFailure.IndexUnavailable("HTTP ${response.code} ${response.message}")
                    )
                }
                val bytes = readBounded(response, MAX_DOCUMENT_BYTES)
                    ?: return@withContext Result.failure(
                        CatalogFailure.IndexUnavailable("response was not a catalog index")
                    )
                if (bytes.isEmpty()) {
                    return@withContext Result.failure(
                        CatalogFailure.IndexUnavailable("empty response")
                    )
                }
                Result.success(parseRomwbwIndex(String(bytes, Charsets.UTF_8)))
            }
        } catch (e: Exception) {
            // Includes JSONException, which is what a GitHub HTML error page
            // parses to - so it is reported as "not the index", not as a
            // network failure the user could fix by moving nearer a router.
            Result.failure(CatalogFailure.IndexUnavailable(e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * Fetch, verify and parse one release's catalog document.
     *
     * [entry] comes from the index and is used verbatim: its catalog_url is
     * requested as published, and its catalog_size and catalog_sha256 are what
     * the body is checked against. Both checks are skipped when the index does
     * not publish them, so an index that stops carrying a hash degrades to an
     * unverified download rather than to no downloads at all.
     */
    suspend fun fetchCatalog(entry: RomwbwVersion): Result<DiskCatalog> =
        withContext(Dispatchers.IO) {
            val version = entry.romwbwVersion
            try {
                val request = Request.Builder().url(entry.catalogUrl).build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            CatalogFailure.CatalogUnavailable(
                                version, "HTTP ${response.code} ${response.message}"
                            )
                        )
                    }

                    val bytes = readBounded(response, MAX_DOCUMENT_BYTES)
                        ?: return@withContext Result.failure(
                            CatalogFailure.CatalogCorrupt(version, "response was too large to be a catalog")
                        )

                    if (entry.catalogSize > 0 && bytes.size.toLong() != entry.catalogSize) {
                        return@withContext Result.failure(
                            CatalogFailure.CatalogCorrupt(
                                version,
                                "got ${bytes.size} bytes, index says ${entry.catalogSize}"
                            )
                        )
                    }
                    if (entry.catalogSha256.isNotEmpty()) {
                        val actual = sha256Hex(bytes)
                        if (!actual.equals(entry.catalogSha256, ignoreCase = true)) {
                            return@withContext Result.failure(
                                CatalogFailure.CatalogCorrupt(
                                    version,
                                    "sha256 $actual, index says ${entry.catalogSha256}"
                                )
                            )
                        }
                    }

                    val catalog = parseDiskCatalog(String(bytes, Charsets.UTF_8))
                        ?: return@withContext Result.failure(
                            CatalogFailure.CatalogCorrupt(version, "no base_url in the document")
                        )
                    if (catalog.disks.isEmpty()) {
                        return@withContext Result.failure(CatalogFailure.CatalogEmpty(version))
                    }
                    Result.success(catalog)
                }
            } catch (e: JSONException) {
                // Separate from the arm below: a body that is not JSON came back
                // over a connection that worked, so calling it unavailable would
                // send the user to check their network. It reaches here only
                // when the index publishes no hash for this catalog - with one,
                // the verification above rejects it first and says so.
                Result.failure(
                    CatalogFailure.CatalogCorrupt(version, "not a catalog document: ${e.message}")
                )
            } catch (e: Exception) {
                Result.failure(
                    CatalogFailure.CatalogUnavailable(version, e.message ?: e.javaClass.simpleName)
                )
            }
        }

    /**
     * The whole body, or null when it would be larger than [limit].
     *
     * Not ResponseBody.bytes(): that reads whatever arrives, and the point here
     * is to stop before an unexpected 49 MB response becomes an
     * OutOfMemoryError. The declared length is checked first so an honest
     * oversized response costs no transfer at all, and the running total is
     * checked too, because a chunked response declares nothing.
     */
    private fun readBounded(response: Response, limit: Long): ByteArray? {
        val body = response.body ?: return ByteArray(0)
        if (body.contentLength() > limit) return null
        val collected = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (collected.size().toLong() + read > limit) return null
                collected.write(buffer, 0, read)
            }
        }
        return collected.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
