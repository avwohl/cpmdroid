package com.awohl.cpmdroid.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class DiskDownloadManager(private val context: Context) {

    companion object {
        /** Catalog downloads, named by catalog filename. */
        private const val DISKS_DIR_NAME = "Disks"

        /** The user's written-to copies, named by the same catalog filename. */
        private const val MODIFIED_DISKS_DIR_NAME = "ModifiedDisks"

        // Scratch files older than this are assumed abandoned. The sweep is age
        // gated rather than unconditional because rotating the Settings screen
        // recreates the Activity in the SAME process while the previous
        // download coroutine may still be writing: unlinking a live scratch
        // file would leave that writer filling an inode with no name, and the
        // rename would then fail for a transfer that had actually succeeded.
        private const val STALE_TEMP_AGE_MS = 60L * 60L * 1000L

        // Disk filenames with a transfer in flight. Static because the manager
        // is constructed per Activity - SettingsActivity and MainActivity each
        // build their own - so a per-instance set would not see the download
        // the other screen started, which is precisely the collision this is
        // here to refuse.
        private val downloadsInFlight = HashSet<String>()

        private fun claimDownload(filename: String): Boolean =
            synchronized(downloadsInFlight) { downloadsInFlight.add(filename) }

        private fun releaseDownload(filename: String) {
            synchronized(downloadsInFlight) { downloadsInFlight.remove(filename) }
        }

        private fun isScratchOfLiveDownload(scratchName: String): Boolean =
            synchronized(downloadsInFlight) {
                downloadsInFlight.any { scratchName.startsWith(it + ".") }
            }
    }

    private val catalogRepo = DiskCatalogRepository()

    private val client = sharedHttpClient

    /** index-v0.json: every RomWBW release romwbw_disks publishes, unfiltered. */
    suspend fun fetchIndex(): Result<List<RomwbwVersion>> = catalogRepo.fetchIndex()

    /**
     * One release's catalog, verified against the size and hash its index entry
     * publishes.
     *
     * Two round trips where there was one, and the entry has to come from the
     * index rather than be built here: its catalog_url is used verbatim, which
     * is the whole point of the two-level shape.
     */
    suspend fun fetchCatalog(entry: RomwbwVersion): Result<DiskCatalog> =
        catalogRepo.fetchCatalog(entry)

    fun getDisksDir(): File {
        val dir = File(context.getExternalFilesDir(null), DISKS_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * [getDisksDir] as the v0 rename pass has to see it: null when external
     * storage is unavailable, rather than the relative "Disks" in the process
     * working directory that File(null, "Disks") yields - the accident
     * HostTransfer.transferDir() already guards against, and MainActivity's
     * comment on it calls "a folder no file manager, no adb pull and no user
     * will ever find".
     *
     * A second accessor rather than a nullable return on the existing one,
     * because every other caller in the app - download, load, delete, the
     * catalog dialog's downloaded ticks - wants a File and has no answer for
     * null beyond the one it already gives when the file is missing. The
     * migration is the one caller that must refuse to act rather than act on an
     * empty directory: it is what writes the flag saying the rename is done.
     */
    fun getDisksDirOrNull(): File? = externalSubdirOrNull(DISKS_DIR_NAME)

    /** [getPersistedDisksDir] with the same guard, and for the same reason. */
    fun getPersistedDisksDirOrNull(): File? = externalSubdirOrNull(MODIFIED_DISKS_DIR_NAME)

    private fun externalSubdirOrNull(name: String): File? {
        val root = context.getExternalFilesDir(null) ?: return null
        val dir = File(root, name)
        if (!dir.mkdirs() && !dir.isDirectory) return null
        return dir
    }

    fun getDiskFile(filename: String): File = File(getDisksDir(), filename)

    fun isDiskDownloaded(filename: String): Boolean = getDiskFile(filename).exists()

    fun getDownloadedDisks(): List<String> {
        return getDisksDir().listFiles()
            ?.filter { it.isFile && it.name.endsWith(".img") }
            ?.map { it.name }
            ?: emptyList()
    }

    suspend fun downloadDisk(
        diskInfo: DiskInfo,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        // One transfer per disk at a time, checked before anything is opened.
        // The read loop below only notices cancellation at a block boundary, so
        // leaving the Settings screen mid-download can leave the old coroutine
        // running for another moment while the user taps Download again - and
        // without this guard both writers would go at the same disk, and the
        // second would spend another 49MB of the user's data allowance on a
        // file that was already arriving.
        if (!claimDownload(diskInfo.filename)) {
            return@withContext Result.failure(
                Exception(diskInfo.filename + " is already downloading")
            )
        }

        var tempFile: File? = null
        try {
            sweepStaleTempFiles(getDisksDir())

            // The URL the catalog gave this entry - base_url + filename, built
            // when the document was parsed. Nothing reconstructs it from a
            // release tag any more, and nothing can pair this disk with another
            // release's base: a DiskInfo carries its own.
            val url = diskInfo.downloadUrl
            if (url.isEmpty()) {
                return@withContext Result.failure(
                    Exception("No download URL for " + diskInfo.filename)
                )
            }
            val request = Request.Builder().url(url).build()

            // use{}, not a bare execute(): the early return below leaves the
            // body unread, and an unclosed body never returns its connection to
            // sharedHttpClient's pool. A 404 here is not hypothetical - a whole
            // release shipped with the catalog pinned to a tag that did not
            // exist - so this is the arm that ran, and leaked, in the field.
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Download failed: HTTP ${response.code}")
                    )
                }

                val body = response.body
                    ?: return@withContext Result.failure(Exception("Empty response"))

                val totalBytes = body.contentLength()
                val destFile = getDiskFile(diskInfo.filename)

                // A nonce in the scratch name, because the old fixed
                // "<filename>.tmp" was one name shared by every attempt at a
                // disk: two in-flight transfers truncated and then wrote over
                // each other, and each one's cleanup deleted the other's file.
                // The sibling puts a process id here; that would not separate
                // two coroutines inside one process, which is the racer here.
                val temp = File(
                    destFile.parentFile,
                    diskInfo.filename + "." + System.nanoTime() + ".tmp"
                )
                tempFile = temp

                // Hashed as it is written rather than by re-reading the finished
                // file: the combo image is 49MB, and reading it a second time off
                // a phone's storage to learn something the bytes passing through
                // could have told us is a second 49MB of I/O for nothing.
                val digest = MessageDigest.getInstance("SHA-256")
                var bytesRead: Long = 0

                FileOutputStream(temp).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var read: Int

                        while (input.read(buffer).also { read = it } != -1) {
                            // The blocking read above cannot be interrupted and
                            // Dispatchers.IO does not interrupt its threads, so
                            // this is the ONLY place a cancelled caller becomes
                            // visible. Without it, closing the screen that asked
                            // for the download still pulled the whole image and
                            // fired the callback below ~6200 times on the way,
                            // for a transfer nobody would ever be shown.
                            ensureActive()
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            bytesRead += read
                            onProgress?.invoke(bytesRead, totalBytes)
                        }
                    }
                }

                // Both promises are checked, and separately, because either one
                // can be the one that is absent: a catalog entry may carry no
                // <size>, and a chunked response carries no Content-Length. A
                // connection dropped at 90% used to be renamed straight over the
                // good copy and handed to the emulator as a bootable image.
                if (diskInfo.size > 0 && bytesRead != diskInfo.size) {
                    return@withContext Result.failure(
                        Exception("Download truncated: got $bytesRead of ${diskInfo.size} bytes")
                    )
                }
                if (totalBytes >= 0 && bytesRead != totalBytes) {
                    return@withContext Result.failure(
                        Exception("Download truncated: got $bytesRead of $totalBytes bytes")
                    )
                }

                if (diskInfo.sha256.isNotEmpty()) {
                    val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actualHash.equals(diskInfo.sha256, ignoreCase = true)) {
                        return@withContext Result.failure(
                            Exception("SHA256 mismatch: expected ${diskInfo.sha256}, got $actualHash")
                        )
                    }
                }

                // Rename FIRST, and unlink the previous image only if the rename
                // will not replace it by itself. The old order deleted destFile
                // and then discarded renameTo's return value, so a rename that
                // failed left the user with no image at all and a Result.success
                // claiming otherwise.
                var published = temp.renameTo(destFile)
                if (!published && destFile.exists() && destFile.delete()) {
                    published = temp.renameTo(destFile)
                }
                if (!published) {
                    return@withContext Result.failure(
                        Exception("Could not save " + diskInfo.filename)
                    )
                }
                Result.success(destFile)
            }

        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // Nothing else reclaims the scratch file now that its name carries a
            // nonce. The fixed name used to be cleaned up by accident, when the
            // next attempt at the same disk truncated it; unique names took that
            // away, which is why this is not optional. It is a no-op after a
            // rename that worked, since the path no longer exists.
            tempFile?.delete()
            releaseDownload(diskInfo.filename)
        }
    }

    /**
     * Remove scratch files left behind by transfers that died before renaming.
     *
     * Both gates matter. The age gate covers a process that was killed mid
     * transfer, where nothing in memory remembers the file. The in-flight check
     * covers the transfer that is merely slow: a scratch file being written
     * right now must not be unlinked, or its writer finishes into an inode with
     * no name and the rename fails for a download that succeeded.
     */
    private fun sweepStaleTempFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_TEMP_AGE_MS
        dir.listFiles()
            ?.filter {
                it.isFile && it.name.endsWith(".tmp") &&
                    it.lastModified() < cutoff && !isScratchOfLiveDownload(it.name)
            }
            ?.forEach { it.delete() }
    }

    fun deleteDisk(filename: String): Boolean {
        return getDiskFile(filename).delete()
    }

    fun loadDiskData(filename: String): ByteArray? {
        val file = getDiskFile(filename)
        return if (file.exists()) file.readBytes() else null
    }

    // =========================================================================
    // Disk Persistence - for saving modified disks
    // =========================================================================

    /**
     * Get the directory for persisted (modified) disk images.
     * These are kept separate from downloaded catalog disks.
     */
    fun getPersistedDisksDir(): File {
        val dir = File(context.getExternalFilesDir(null), MODIFIED_DISKS_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Get the file path for a persisted disk image.
     */
    fun getPersistedDiskFile(filename: String): File = File(getPersistedDisksDir(), filename)

    /**
     * Check if a persisted (modified) version of a disk exists.
     */
    fun hasPersistedDisk(filename: String): Boolean = getPersistedDiskFile(filename).exists()

    /**
     * Load a persisted disk if it exists, otherwise fall back to the catalog version.
     * Returns the disk data and whether it was from the persisted version.
     */
    fun loadDiskDataWithPersistence(filename: String): Pair<ByteArray?, Boolean> {
        val persistedFile = getPersistedDiskFile(filename)
        if (persistedFile.exists()) {
            return Pair(persistedFile.readBytes(), true)
        }
        val catalogFile = getDiskFile(filename)
        if (catalogFile.exists()) {
            return Pair(catalogFile.readBytes(), false)
        }
        return Pair(null, false)
    }

    /**
     * Save modified disk data to the persisted disks directory.
     */
    fun savePersistedDisk(filename: String, data: ByteArray): Boolean {
        val file = getPersistedDiskFile(filename)
        // Write-temp-then-rename (like downloadDisk): a crash or disk-full
        // mid-write must not leave a truncated image that would shadow the
        // good catalog copy on every future launch. The nonce and the finally
        // are here for the same reasons they are there - a fixed scratch name
        // is one name shared by every writer, and a disk-full inside writeBytes
        // used to return false with the partial file still on disk and nothing
        // that would ever remove it.
        val tmp = File(file.parentFile, file.name + "." + System.nanoTime() + ".tmp")
        // No initialiser: every path through the try/catch below assigns it,
        // and a false here would just be a value the compiler can see is never read.
        var published: Boolean
        try {
            sweepStaleTempFiles(getPersistedDisksDir())
            tmp.writeBytes(data)
            published = tmp.renameTo(file)
            if (!published && file.exists() && file.delete()) {
                published = tmp.renameTo(file)
            }
        } catch (e: Exception) {
            published = false
        } finally {
            tmp.delete()
        }
        return published
    }

    /**
     * Delete a persisted disk (revert to catalog version).
     */
    fun deletePersistedDisk(filename: String): Boolean {
        return getPersistedDiskFile(filename).delete()
    }
}
