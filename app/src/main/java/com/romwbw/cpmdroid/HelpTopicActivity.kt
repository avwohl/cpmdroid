package com.romwbw.cpmdroid

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class HelpTopicActivity : AppCompatActivity() {

    private lateinit var contentText: TextView
    private lateinit var loadingProgress: ProgressBar

    private val httpClient = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help_topic)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val topicTitle = intent.getStringExtra("topic_title") ?: "Help"
        val topicUrl = intent.getStringExtra("topic_url") ?: ""

        title = topicTitle

        contentText = findViewById(R.id.contentText)
        loadingProgress = findViewById(R.id.loadingProgress)

        if (topicUrl.isNotEmpty()) {
            loadContent(topicUrl)
        } else {
            contentText.text = "No content available."
        }
    }

    private fun loadContent(url: String) {
        loadingProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = fetchContent(url)

            loadingProgress.visibility = View.GONE

            result.fold(
                onSuccess = { markdown ->
                    // Simple markdown to plain text conversion
                    contentText.text = convertMarkdownToPlainText(markdown)
                },
                onFailure = { e ->
                    contentText.text = "Failed to load content: ${e.message}"
                }
            )
        }
    }

    private suspend fun fetchContent(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP ${response.code}"))
            }

            val content = response.body?.string() ?: return@withContext Result.failure(IOException("Empty response"))
            Result.success(content)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Convert basic markdown to readable plain text.
     * Handles headers, bold, lists, and code blocks.
     */
    private fun convertMarkdownToPlainText(markdown: String): String {
        val lines = markdown.lines()
        val result = StringBuilder()
        var inCodeBlock = false

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

        return result.toString().trim()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
