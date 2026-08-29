package com.awohl.cpmdroid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.awohl.cpmdroid.data.sharedHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date

data class HelpTopic(
    val id: String,
    val title: String,
    val description: String,
    val filename: String?,  // Relative to base_url
    val url: String?        // Full URL (overrides base_url + filename)
)

data class HelpIndex(
    val version: Int,
    val baseUrl: String,
    val topics: List<HelpTopic>
)

/**
 * Which of the three copies of a piece of help text the reader is looking at.
 *
 * The order is fixed and lives in resolveHelpIndex and HelpTopicActivity's
 * resolveContent: download, then cache, then the copy in the APK. Never the
 * bundled copy first - a correction published to the release has to be able to
 * reach a reader without an app update, and the bundled files are a floor, not
 * a source of truth.
 */
internal enum class HelpSource { DOWNLOADED, CACHED, BUNDLED }

/**
 * What to say in the action bar's subtitle about where this text came from.
 *
 * Null for a live download, because a reader who is looking at the current text
 * has nothing to be warned about; the subtitle is otherwise the only thing that
 * distinguishes a topic corrected last week from the copy frozen into the APK
 * at build time, and a reader who cannot tell those apart cannot judge what
 * they are reading. The subtitle carries it rather than a banner in the pane
 * because neither help layout has a status view, and this signal is not worth
 * the reflow of one.
 */
internal fun helpSourceNote(source: HelpSource, savedWhen: String?): String? = when (source) {
    HelpSource.DOWNLOADED -> null
    HelpSource.CACHED -> if (savedWhen != null) "offline copy, saved $savedWhen" else "offline copy"
    HelpSource.BUNDLED -> "bundled with the app"
}

/**
 * The two offline tiers behind the help viewer: what this reader was shown last
 * time (filesDir/help) and what the APK shipped with (assets/help).
 *
 * filesDir rather than cacheDir, because the whole point is a topic that is
 * still there on a train weeks later, and the platform is free to evict cacheDir
 * whenever it wants storage back - which would delete exactly the copy that was
 * saved for the moment there is no network.
 */
internal object HelpAssets {

    // The bundled copies are packaged under this directory inside assets/, so
    // they cannot collide with emu_avw.rom sitting at the assets root.
    private const val ASSET_DIR = "help"

    // A ceiling on what is read back into memory and what is stored. The seven
    // published topics together are a few tens of kilobytes and the largest is
    // about five, so this is orders of magnitude clear of anything real. Its
    // job is to bound a read of a file something else may have grown, not to
    // police the length of a help topic.
    private const val MAX_CACHE_BYTES = 1024L * 1024L

    /**
     * Whitelist for a name that is about to become a path.
     *
     * Topic ids and topic filenames both arrive over the network, out of an
     * index this app does not control, and both end up concatenated onto a
     * directory. Refusing everything except a single unremarkable path
     * component is what turns away "..", a separator, a colon, a control
     * character and anything above ASCII - and it does not have to enumerate
     * what is dangerous. A leading dot or dash is refused separately: with
     * every separator already gone, a leading dot is the only way left to
     * climb, and a leading dash is what anything downstream reading a command
     * line would take for an option.
     */
    fun isSafeAssetName(name: String): Boolean {
        if (name.isEmpty() || name.length > 96) return false
        if (name[0] == '.' || name[0] == '-') return false
        return name.all { c ->
            (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
                c == '_' || c == '-' || c == '.'
        }
    }

    private fun cacheDir(context: Context): File = File(context.filesDir, ASSET_DIR)

    private fun cacheFile(context: Context, name: String): File? =
        if (isSafeAssetName(name)) File(cacheDir(context), name) else null

    /**
     * The copy saved from a previous successful download, or null.
     *
     * A zero-byte file is a miss rather than a topic: a blank pane cannot be
     * told apart from a topic that loaded and had nothing to say, and the one
     * thing this cache must never do is show a fragment as though it were
     * whole.
     */
    fun readCached(context: Context, name: String): String? {
        val file = cacheFile(context, name) ?: return null
        if (!file.isFile) return null
        val length = file.length()
        if (length <= 0L || length > MAX_CACHE_BYTES) return null
        return try {
            val text = file.readText()
            if (text.isBlank()) null else text
        } catch (e: Exception) {
            null
        }
    }

    /** When readCached's copy was written, for the subtitle, or null. */
    fun cachedWhen(context: Context, name: String): String? {
        val file = cacheFile(context, name) ?: return null
        val stamp = file.lastModified()
        if (stamp <= 0L) return null
        return DateFormat.getDateInstance().format(Date(stamp))
    }

    /**
     * Store text under a scratch name and rename it into place.
     *
     * Writing in place was rejected outright: the file being replaced is the
     * only offline copy the reader has, so a truncating open that then fails
     * would leave them with less than they had. The nonce is nanoTime rather
     * than the sibling's process id, because the racers here are two coroutines
     * inside one process and a pid cannot tell those apart. Blank content is
     * refused for the same reason readCached refuses a zero-byte file.
     */
    fun writeCached(context: Context, name: String, content: String): Boolean {
        if (content.isBlank() || content.length > MAX_CACHE_BYTES) return false
        val file = cacheFile(context, name) ?: return false
        val dir = cacheDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) return false

        val temp = File(dir, file.name + "." + System.nanoTime() + ".tmp")
        // No initialiser: every path through the try/catch below assigns it,
        // and a false here would just be a value the compiler can see is never read.
        var published: Boolean
        try {
            temp.writeText(content)
            published = temp.renameTo(file)
            if (!published && file.exists() && file.delete()) {
                published = temp.renameTo(file)
            }
        } catch (e: Exception) {
            published = false
        } finally {
            // A no-op after a rename that worked, and the whole point after one
            // that did not: a unique scratch name has nothing that would ever
            // come back and reclaim it.
            temp.delete()
        }
        return published
    }

    /**
     * The copy packaged into the APK, or null when this build shipped without
     * one. Absence is normal and not an error: a topic the index routes at an
     * absolute url has no bundled twin at all.
     */
    fun readBundled(context: Context, name: String): String? {
        if (!isSafeAssetName(name)) return null
        return try {
            context.assets.open("$ASSET_DIR/$name").use { input ->
                val text = input.readBytes().toString(Charsets.UTF_8)
                if (text.isBlank()) null else text
            }
        } catch (e: Exception) {
            null
        }
    }
}

class HelpActivity : AppCompatActivity() {

    companion object {
        private const val INDEX_URL = "https://github.com/avwohl/cpmdroid/releases/latest/download/help_index.json"

        // One name for both offline tiers: the cached index and the bundled
        // index are the same document from two places, and assets/help holds it
        // under this name too.
        private const val INDEX_NAME = "help_index.json"
    }

    /** An index plus where it came from; index is null when no tier answered. */
    private data class ResolvedIndex(
        val index: HelpIndex?,
        val source: HelpSource?,
        val savedWhen: String?,
        val error: String?
    )

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorText: TextView

    private val httpClient = sharedHttpClient

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        // Handle window insets for edge-to-edge
        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top, bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

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
            val resolved = resolveHelpIndex()

            loadingProgress.visibility = View.GONE

            val index = resolved.index
            val source = resolved.source
            if (index != null && source != null) {
                supportActionBar?.subtitle = helpSourceNote(source, resolved.savedWhen)
                recyclerView.adapter = HelpTopicAdapter(index.topics, index.baseUrl) { topic, baseUrl ->
                    openHelpTopic(topic, baseUrl)
                }
            } else {
                errorText.visibility = View.VISIBLE
                errorText.text = "Failed to load help topics: ${resolved.error ?: "no help available"}"
            }
        }
    }

    /**
     * The index is the load-bearing half of offline help. A cache of topics on
     * its own is unreachable text: with no network this used to stop here, the
     * adapter was never set, and the reader never got as far as a topic to
     * benefit from anything saved behind it.
     */
    private suspend fun resolveHelpIndex(): ResolvedIndex = withContext(Dispatchers.IO) {
        val fetched = fetchIndexJson()
        val downloaded = fetched.getOrNull()
        if (downloaded != null) {
            val parsed = parseHelpIndex(downloaded)
            if (parsed != null) {
                // Stored on the way past, and this is the only writeCached of
                // the index anywhere, so what the reader was shown and what is
                // on disk cannot drift apart. The return value is ignored on
                // purpose: a full disk is a reason to have no offline copy next
                // time, not a reason to withhold the list now.
                HelpAssets.writeCached(this@HelpActivity, INDEX_NAME, downloaded)
                return@withContext ResolvedIndex(parsed, HelpSource.DOWNLOADED, null, null)
            }
        }
        val error = fetched.exceptionOrNull()?.message

        val cached = HelpAssets.readCached(this@HelpActivity, INDEX_NAME)
        if (cached != null) {
            val parsed = parseHelpIndex(cached)
            if (parsed != null) {
                return@withContext ResolvedIndex(
                    parsed, HelpSource.CACHED,
                    HelpAssets.cachedWhen(this@HelpActivity, INDEX_NAME), error
                )
            }
        }

        val bundled = HelpAssets.readBundled(this@HelpActivity, INDEX_NAME)
        if (bundled != null) {
            val parsed = parseHelpIndex(bundled)
            if (parsed != null) {
                return@withContext ResolvedIndex(parsed, HelpSource.BUNDLED, null, error)
            }
        }

        ResolvedIndex(null, null, null, error)
    }

    /**
     * One parser for all three tiers, so the bundled index has to keep the same
     * schema as the published one rather than growing a private shape nobody
     * exercises. Null on anything malformed, which is what makes a poisoned
     * cache fall through to the copy in the APK instead of ending the search.
     */
    private fun parseHelpIndex(json: String): HelpIndex? {
        return try {
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
                    filename = topicObj.optString("filename").ifEmpty { null },
                    url = topicObj.optString("url").ifEmpty { null }
                ))
            }

            if (topics.isEmpty()) null else HelpIndex(version, baseUrl, topics)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchIndexJson(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(INDEX_URL)
                .build()

            // use{}, not a bare execute(): the HTTP-error arm below returns
            // without reading the body, which never gives its connection back
            // to sharedHttpClient's pool. INDEX_URL resolves through
            // releases/latest, so a release with no help_index.json attached
            // takes that arm for every reader, every time.
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
                // length is deliberately NOT refused - a chunked reply has none,
                // and "no length, no help" would break remote help the day the
                // host changed how it serves.
                val declared = body.contentLength()
                val bytes = body.bytes()
                if (declared >= 0 && bytes.size.toLong() != declared) {
                    return@withContext Result.failure(
                        IOException("Truncated: got ${bytes.size} of $declared bytes")
                    )
                }

                val json = bytes.toString(Charsets.UTF_8)
                // isBlank, not a null check on the body. Response.body is
                // non-null for a response that came back from execute() and
                // string() answers "" for an empty body, so the null branch this
                // replaces could never fire and an empty 200 would have poisoned
                // the cache with nothing.
                if (json.isBlank()) {
                    return@withContext Result.failure(IOException("Empty response"))
                }
                Result.success(json)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun openHelpTopic(topic: HelpTopic, baseUrl: String) {
        // An entry with neither a url nor a filename has no network source at
        // all; it used to compose "<base_url>null" and fetch that. An empty
        // string tells HelpTopicActivity to skip straight to its offline tiers.
        val assetName = topic.filename
        val topicUrl = topic.url ?: assetName?.let { baseUrl + it } ?: ""
        val intent = Intent(this, HelpTopicActivity::class.java).apply {
            putExtra("topic_title", topic.title)
            putExtra("topic_url", topicUrl)
            // The cache key. The id, not the URL: the two in-tree indexes route
            // the same topics differently - one by filename under base_url, one
            // by absolute url at the iOS release - and a key taken from the URL
            // would make a reader who switched between them lose everything
            // they had saved. Ids are stable across both.
            putExtra("topic_id", topic.id)
            // The bundled-asset key, and only when the index gives one. A topic
            // routed by an absolute url has no bundled twin, and the last
            // segment of that url is not a name this APK ships, so the extra
            // stays absent rather than carrying a guess for assets.open to miss.
            if (assetName != null) {
                putExtra("topic_asset", assetName)
            }
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
