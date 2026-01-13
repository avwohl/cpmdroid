package com.awohl.cpmdroid.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.concurrent.TimeUnit

class DiskCatalogRepository {

    companion object {
        private const val CATALOG_URL =
            "https://github.com/avwohl/ioscpm/releases/latest/download/disks.xml"
        private const val DOWNLOAD_BASE_URL =
            "https://github.com/avwohl/ioscpm/releases/latest/download/"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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
