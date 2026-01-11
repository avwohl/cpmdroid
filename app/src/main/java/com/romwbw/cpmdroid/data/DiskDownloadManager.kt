package com.romwbw.cpmdroid.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class DiskDownloadManager(private val context: Context) {

    private val catalogRepo = DiskCatalogRepository()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    fun getDisksDir(): File {
        val dir = File(context.getExternalFilesDir(null), "Disks")
        if (!dir.exists()) dir.mkdirs()
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
        try {
            val url = catalogRepo.getDownloadUrl(diskInfo.filename)
            val request = Request.Builder().url(url).build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Download failed: HTTP ${response.code}")
                )
            }

            val body = response.body
                ?: return@withContext Result.failure(Exception("Empty response"))

            val totalBytes = body.contentLength()
            val destFile = getDiskFile(diskInfo.filename)
            val tempFile = File(destFile.parent, "${diskInfo.filename}.tmp")

            FileOutputStream(tempFile).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        bytesRead += read
                        onProgress?.invoke(bytesRead, totalBytes)
                    }
                }
            }

            // Verify SHA256 if provided
            if (diskInfo.sha256.isNotEmpty()) {
                val actualHash = calculateSha256(tempFile)
                if (!actualHash.equals(diskInfo.sha256, ignoreCase = true)) {
                    tempFile.delete()
                    return@withContext Result.failure(
                        Exception("SHA256 mismatch: expected ${diskInfo.sha256}, got $actualHash")
                    )
                }
            }

            // Rename temp to final
            if (destFile.exists()) destFile.delete()
            tempFile.renameTo(destFile)
            Result.success(destFile)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun deleteDisk(filename: String): Boolean {
        return getDiskFile(filename).delete()
    }

    fun loadDiskData(filename: String): ByteArray? {
        val file = getDiskFile(filename)
        return if (file.exists()) file.readBytes() else null
    }
}
