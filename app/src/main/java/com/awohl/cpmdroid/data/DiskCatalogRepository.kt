package com.awohl.cpmdroid.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.concurrent.TimeUnit

/** Shared OkHttpClient — single connection pool and thread pool for all network I/O. */
internal val sharedHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES)
    .followRedirects(true)
    .build()

class DiskCatalogRepository {

    companion object {
        // Pinned to an explicit ioscpm release (matching the Windows port):
        // the core's built-in HBIOS identifies as RomWBW v3.5.1, and boot
        // slices from other RomWBW releases print a HBIOS/CBIOS mismatch
        // warning. Bump this tag together with core/ROM upgrades. Help
        // content (HelpActivity) deliberately stays on releases/latest.
        private const val RELEASE_TAG = "v1.4.5"
        private const val CATALOG_URL =
            "https://github.com/avwohl/ioscpm/releases/download/$RELEASE_TAG/disks.xml"
        private const val DOWNLOAD_BASE_URL =
            "https://github.com/avwohl/ioscpm/releases/download/$RELEASE_TAG/"
    }

    private val client = sharedHttpClient

    suspend fun fetchCatalog(): Result<List<DiskInfo>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(CATALOG_URL)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("HTTP ${response.code}: ${response.message}")
                )
            }

            val xml = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response"))

            val disks = parseDisksXml(xml)
            Result.success(disks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseDisksXml(xml: String): List<DiskInfo> {
        val disks = mutableListOf<DiskInfo>()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        var eventType = parser.eventType
        var currentDisk: MutableMap<String, String>? = null
        var currentTag: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "disk" -> currentDisk = mutableMapOf()
                        else -> currentTag = parser.name
                    }
                }
                XmlPullParser.TEXT -> {
                    if (currentDisk != null && currentTag != null) {
                        val text = parser.text?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            currentDisk[currentTag] = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "disk" && currentDisk != null) {
                        val filename = currentDisk["filename"] ?: ""
                        if (filename.isNotEmpty()) {
                            disks.add(
                                DiskInfo(
                                    filename = filename,
                                    name = currentDisk["name"] ?: filename,
                                    description = currentDisk["description"] ?: "",
                                    size = currentDisk["size"]?.toLongOrNull() ?: 0,
                                    license = currentDisk["license"] ?: "",
                                    sha256 = currentDisk["sha256"] ?: "",
                                    defaultSlot = currentDisk["defaultSlot"]?.toIntOrNull()
                                )
                            )
                        }
                        currentDisk = null
                    }
                    currentTag = null
                }
            }
            eventType = parser.next()
        }
        return disks
    }

    fun getDownloadUrl(filename: String): String = "$DOWNLOAD_BASE_URL$filename"
}
