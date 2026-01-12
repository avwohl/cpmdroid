package com.romwbw.cpmdroid

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

data class HelpTopic(
    val id: String,
    val title: String,
    val description: String,
    val file: String
)

data class HelpIndex(
    val version: Int,
    val baseUrl: String,
    val topics: List<HelpTopic>
)

class HelpActivity : AppCompatActivity() {

    companion object {
        private const val INDEX_URL = "https://github.com/avwohl/ioscpm/releases/latest/download/help_index.json"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorText: TextView

    private val httpClient = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Help"

        recyclerView = findViewById(R.id.topicRecyclerView)
        loadingProgress = findViewById(R.id.loadingProgress)
        errorText = findViewById(R.id.errorText)

        recyclerView.layoutManager = LinearLayoutManager(this)

        loadHelpIndex()
    }

    private fun loadHelpIndex() {
        loadingProgress.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        lifecycleScope.launch {
            val result = fetchHelpIndex()

            loadingProgress.visibility = View.GONE

            result.fold(
                onSuccess = { index ->
                    recyclerView.adapter = HelpTopicAdapter(index.topics, index.baseUrl) { topic, baseUrl ->
                        openHelpTopic(topic, baseUrl)
                    }
                },
                onFailure = { e ->
                    errorText.visibility = View.VISIBLE
                    errorText.text = "Failed to load help topics: ${e.message}"
                }
            )
        }
    }

    private suspend fun fetchHelpIndex(): Result<HelpIndex> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(INDEX_URL)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP ${response.code}"))
            }

            val json = response.body?.string() ?: return@withContext Result.failure(IOException("Empty response"))
            val jsonObj = JSONObject(json)

            val version = jsonObj.optInt("version", 1)
            val baseUrl = jsonObj.optString("base_url", "https://github.com/avwohl/ioscpm/releases/latest/download/")

            val topicsArray = jsonObj.getJSONArray("topics")
            val topics = mutableListOf<HelpTopic>()

            for (i in 0 until topicsArray.length()) {
                val topicObj = topicsArray.getJSONObject(i)
                topics.add(HelpTopic(
                    id = topicObj.getString("id"),
                    title = topicObj.getString("title"),
                    description = topicObj.optString("description", ""),
                    file = topicObj.getString("file")
                ))
            }

            Result.success(HelpIndex(version, baseUrl, topics))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun openHelpTopic(topic: HelpTopic, baseUrl: String) {
        val intent = Intent(this, HelpTopicActivity::class.java).apply {
            putExtra("topic_title", topic.title)
            putExtra("topic_url", baseUrl + topic.file)
        }
        startActivity(intent)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

class HelpTopicAdapter(
    private val topics: List<HelpTopic>,
    private val baseUrl: String,
    private val onClick: (HelpTopic, String) -> Unit
) : RecyclerView.Adapter<HelpTopicAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleText: TextView = view.findViewById(R.id.topicTitle)
        val descriptionText: TextView = view.findViewById(R.id.topicDescription)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = View.inflate(parent.context, R.layout.item_help_topic, null)
        view.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT
        )
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val topic = topics[position]
        holder.titleText.text = topic.title
        holder.descriptionText.text = topic.description
        holder.descriptionText.visibility = if (topic.description.isEmpty()) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener { onClick(topic, baseUrl) }
    }

    override fun getItemCount() = topics.size
}
