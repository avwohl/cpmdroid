package com.awohl.cpmdroid

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.awohl.cpmdroid.data.helpTopicMismatch
import com.awohl.cpmdroid.data.sharedHttpClient
import okhttp3.Request
import java.io.IOException

class HelpTopicActivity : AppCompatActivity() {

    /** Resolved text plus where it came from; content is null when no tier answered. */
    private data class ResolvedText(
        val content: String?,
        val source: HelpSource?,
        val savedWhen: String?,
        val error: String?
    )

    private lateinit var contentText: TextView
    private lateinit var loadingProgress: ProgressBar

    // The two offline keys, both handed over by HelpActivity.openHelpTopic. The
    // id keys the cache and the filename keys the bundled copy; either can be
    // absent, and an absent one means that tier simply has nothing for this
    // topic rather than that a name should be invented for it.
    private var topicId: String? = null
    private var topicAsset: String? = null

    // What the catalog says this topic's bytes are, handed over with it. 0 and
    // "" mean the index published no claim, which is a check skipped rather
    // than a check failed - the same degradation a catalog document gets when
    // the index carries no hash for it.
    private var topicSize: Long = 0L
    private var topicSha256: String = ""

    private val httpClient = sharedHttpClient

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help_topic)

        // Handle window insets for edge-to-edge
        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top, bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val topicTitle = intent.getStringExtra("topic_title") ?: "Help"
        val topicUrl = intent.getStringExtra("topic_url") ?: ""
        topicId = intent.getStringExtra("topic_id")
        topicAsset = intent.getStringExtra("topic_asset")
        topicSize = intent.getLongExtra("topic_size", 0L)
        topicSha256 = intent.getStringExtra("topic_sha256") ?: ""

        title = topicTitle

        contentText = findViewById(R.id.contentText)
        loadingProgress = findViewById(R.id.loadingProgress)

        // Always resolve, even with no url. An empty url only rules out the
        // first tier; a topic the reader has already opened, or one the APK
        // shipped, is still there to show, and refusing to look was how a
        // reader on a train got "No content available." for a guide they read
        // yesterday.
        loadContent(topicUrl)
    }

    private fun loadContent(url: String) {
        loadingProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val resolved = resolveContent(url)

            loadingProgress.visibility = View.GONE

            val markdown = resolved.content
            val source = resolved.source
            if (markdown != null && source != null) {
                supportActionBar?.subtitle = helpSourceNote(source, resolved.savedWhen)
                // Simple markdown to plain text conversion
                contentText.text = convertMarkdownToPlainText(markdown)
            } else {
                contentText.text = "Failed to load content: ${resolved.error ?: "no offline copy"}"
            }
        }
    }

    /**
     * Download, then cache, then the copy in the APK - the same order as
     * resolveHelpIndex, and never the bundled copy first, so a topic corrected
     * in a release still reaches a reader without an app update.
     */
    private suspend fun resolveContent(url: String): ResolvedText = withContext(Dispatchers.IO) {
        var error: String? = null
        val cacheName = topicId?.let { it + ".md" }

        if (url.isNotEmpty()) {
            val fetched = fetchContent(url)
            val downloaded = fetched.getOrNull()
            if (downloaded != null) {
                // Stored on the way past, and this is the only writeCached of a
                // topic anywhere, so what the reader was shown and what is on
                // disk cannot drift apart. A refused download - truncated,
                // blank, or an HTTP error - never gets here, which is what
                // stops a fragment replacing a whole topic the reader has.
                if (cacheName != null) {
                    HelpAssets.writeCached(this@HelpTopicActivity, cacheName, downloaded)
                }
                return@withContext ResolvedText(downloaded, HelpSource.DOWNLOADED, null, null)
            }
            error = fetched.exceptionOrNull()?.message
        }

        if (cacheName != null) {
            val cached = HelpAssets.readCached(this@HelpTopicActivity, cacheName)
            if (cached != null) {
                return@withContext ResolvedText(
                    cached, HelpSource.CACHED,
                    HelpAssets.cachedWhen(this@HelpTopicActivity, cacheName), error
                )
            }
        }

        val asset = topicAsset
        if (asset != null) {
            val bundled = HelpAssets.readBundled(this@HelpTopicActivity, asset)
            if (bundled != null) {
                return@withContext ResolvedText(bundled, HelpSource.BUNDLED, null, error)
            }
        }

        ResolvedText(null, null, null, error)
    }

    private suspend fun fetchContent(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .build()

            // use{}, not a bare execute(): the HTTP-error arm below returns
            // without reading the body, which never gives its connection back
            // to sharedHttpClient's pool - and that arm is the ordinary one for
            // a release with no help assets attached, once per topic tapped.
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }

                val body = response.body
                    ?: return@withContext Result.failure(IOException("Empty response"))

                // Judged, not just read, because there is now a cache behind
                // this: an accepted fragment would be written over a complete
                // offline copy and then labelled as the live one. Bytes rather
                // than the decoded string, since Content-Length counts bytes and
                // a multi-byte character would make the two disagree for a
                // response that was perfectly whole. A response with no declared
                // length is deliberately NOT refused - a chunked reply has none.
                val declared = body.contentLength()
                val bytes = body.bytes()
                if (declared >= 0 && bytes.size.toLong() != declared) {
                    return@withContext Result.failure(
                        IOException("Truncated: got ${bytes.size} of $declared bytes")
                    )
                }

                // Checked before it is decoded, shown or cached. A response
                // that is whole and is not this topic reaches here with a
                // correct Content-Length and would otherwise be written over
                // the copy the reader already had.
                val mismatch = helpTopicMismatch(bytes, topicSize, topicSha256)
                if (mismatch != null) {
                    return@withContext Result.failure(
                        IOException("Not the published topic: $mismatch")
                    )
                }

                val content = bytes.toString(Charsets.UTF_8)
                // isBlank, not a null check on the body. Response.body is
                // non-null for a response that came back from execute() and
                // string() answers "" for an empty body, so the null branch this
                // replaces could never fire: an empty 200 rendered a blank pane
                // and would now poison the cache with it.
                if (content.isBlank()) {
                    return@withContext Result.failure(IOException("Empty response"))
                }
                Result.success(content)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Convert basic markdown to readable plain text.
     * Handles headers, bold, lists, code blocks, and tables.
     */
    private fun convertMarkdownToPlainText(markdown: String): String {
        val lines = markdown.lines()
        val result = StringBuilder()
        var inCodeBlock = false
        val tableBuffer = mutableListOf<String>()

        for (line in lines) {
            var processed = line

            // Code block toggle
            if (processed.startsWith("```")) {
                inCodeBlock = !inCodeBlock
                if (inCodeBlock) {
                    result.append("---\n")
                } else {
                    result.append("---\n")
                }
                continue
            }

            if (inCodeBlock) {
                // Keep code as-is with indentation
                result.append("  $processed\n")
                continue
            }

            // Detect table rows (lines containing | )
            if (processed.trim().startsWith("|") && processed.trim().endsWith("|")) {
                tableBuffer.add(processed)
                continue
            } else if (tableBuffer.isNotEmpty()) {
                // End of table, format and output it
                result.append(formatTable(tableBuffer))
                tableBuffer.clear()
            }

            // Headers: # Title -> TITLE with underline
            if (processed.startsWith("# ")) {
                val title = processed.substring(2).trim().uppercase()
                result.append("\n$title\n")
                result.append("=".repeat(title.length) + "\n\n")
                continue
            }
            if (processed.startsWith("## ")) {
                val title = processed.substring(3).trim()
                result.append("\n$title\n")
                result.append("-".repeat(title.length) + "\n\n")
                continue
            }
            if (processed.startsWith("### ")) {
                val title = processed.substring(4).trim()
                result.append("\n$title\n\n")
                continue
            }

            // Remove bold markers **text** -> text
            processed = processed.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")

            // Remove italic markers *text* -> text
            processed = processed.replace(Regex("\\*(.+?)\\*"), "$1")

            // Remove inline code markers `text` -> text
            processed = processed.replace(Regex("`(.+?)`"), "$1")

            // Convert bullet points
            if (processed.trimStart().startsWith("- ")) {
                val indent = processed.length - processed.trimStart().length
                processed = " ".repeat(indent) + "• " + processed.trimStart().substring(2)
            }

            result.append(processed + "\n")
        }

        // Handle table at end of file
        if (tableBuffer.isNotEmpty()) {
            result.append(formatTable(tableBuffer))
        }

        return result.toString().trim()
    }

    /**
     * Format a markdown table with properly aligned columns.
     */
    private fun formatTable(rows: List<String>): String {
        if (rows.isEmpty()) return ""

        // Parse all rows into cells
        val parsedRows = rows.mapNotNull { row ->
            val trimmed = row.trim()
            // Skip separator rows (|---|---|)
            if (trimmed.matches(Regex("\\|[-:\\s|]+\\|"))) {
                null
            } else {
                trimmed
                    .removeSurrounding("|")
                    .split("|")
                    .map { it.trim() }
            }
        }

        if (parsedRows.isEmpty()) return ""

        // Calculate max width for each column
        val columnCount = parsedRows.maxOfOrNull { it.size } ?: 0
        val columnWidths = (0 until columnCount).map { col ->
            parsedRows.maxOfOrNull { row -> row.getOrElse(col) { "" }.length } ?: 0
        }

        // Build formatted table
        val result = StringBuilder()
        parsedRows.forEachIndexed { index, row ->
            val formattedRow = row.mapIndexed { col, cell ->
                cell.padEnd(columnWidths.getOrElse(col) { 0 })
            }.joinToString("  ")
            result.append(formattedRow + "\n")

            // Add separator after header row
            if (index == 0) {
                val separator = columnWidths.joinToString("  ") { "-".repeat(it) }
                result.append(separator + "\n")
            }
        }
        result.append("\n")

        return result.toString()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
