package com.awohl.cpmdroid

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors

/**
 * What is in Imports and Exports, and the four ways a file crosses the app
 * boundary: save-as, share, delete, and the import picker.
 *
 * WHY THIS SCREEN EXISTS AT ALL. The two folders live under
 * getExternalFilesDir(null), and from Android 11 the stock Files app does not
 * show Android/data, so nothing outside this app can list them. ACTION_VIEW on
 * a folder Uri cannot be made to work either. Until this screen, README told
 * the user to stage files with a file manager that can no longer see the
 * destination, and a W8 export was visible only over adb.
 *
 * WHAT IT IS NOT. It is UI on top of the leaf-only sandbox, not a relaxation of
 * it. Every file it acts on is one the user tapped in a listing built by
 * listFiles() on one of the two folders, and every action re-proves that file
 * against its folder with resolveInsideDir before it copies, shares or deletes.
 * No guest string reaches any of this: the guest cannot name a destination, and
 * a name the user picks in the system picker is a place to COPY BYTES TO, never
 * a path handed back to the emulator. Nothing here touches EmulatorEngine.
 *
 * WHY IT DOES NOT REFUSE TO OPEN WHILE THE EMULATOR RUNS, which is the
 * opposite of the settingsButton rule ("Stop emulator before changing
 * settings"). Settings has to refuse because it reloads disks and resets the
 * machine underneath a running guest. This screen has no such conflict, and
 * refusing would break the one workflow it exists for - W8 MYFILE.TXT at the
 * prompt, then Share. The interlock comes free from the lifecycle instead:
 * starting this activity runs MainActivity.onPause, which waits for the
 * in-flight batch and calls stopEmulation(), so the run loop that drives
 * handleHostFileRead/handleHostFileWrite is stopped for as long as this screen
 * is in front. An R8 or W8 transfer therefore cannot be halfway through a file
 * this screen is copying or deleting. MainActivity.onResume starts it again on
 * the way back, exactly as it does for Help.
 */
class FileTransferActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FileTransfer"

        // The pending save-as, remembered as folder + leaf rather than as a
        // File: ActivityResultRegistry re-delivers a result across process
        // death but knows nothing about our fields, and the SAF picker is a
        // separate task the system is free to kill us behind. Storing the two
        // strings lets the result re-resolve - and re-validate - the source
        // file instead of arriving with a null field and silently dropping the
        // save.
        private const val STATE_SAVE_FOLDER = "pendingSaveFolder"
        private const val STATE_SAVE_LEAF = "pendingSaveLeaf"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var pathsText: TextView
    private lateinit var importButton: Button

    // Copies run here, never on the main thread: a SAF target can be a cloud
    // provider, so both the read and the write can block on the network.
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pendingSaveFolder: String? = null
    private var pendingSaveLeaf: String? = null

    // Registered as property initializers because registerForActivityResult
    // throws IllegalStateException once the activity is STARTED - it cannot be
    // moved into the click listener or the row dialog where it would read
    // better.
    private val saveAsLauncher =
        registerForActivityResult(CreateNamedDocument()) { uri -> onSaveAsResult(uri) }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            onImportResult(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_transfer)

        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // All four edges, as MainActivity does. enableEdgeToEdge() draws
            // under the bars and this listener answers CONSUMED, so no child
            // gets a second chance at them: padding only top and bottom put the
            // rows and the Import/Close buttons under a landscape cutout or a
            // side gesture inset.
            view.updatePadding(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        title = getString(R.string.transfer_title)

        pendingSaveFolder = savedInstanceState?.getString(STATE_SAVE_FOLDER)
        pendingSaveLeaf = savedInstanceState?.getString(STATE_SAVE_LEAF)

        recyclerView = findViewById(R.id.transferRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        pathsText = findViewById(R.id.transferPaths)

        importButton = findViewById(R.id.importButton)
        importButton.setOnClickListener { startImport() }
        findViewById<Button>(R.id.closeButton).setOnClickListener { finish() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SAVE_FOLDER, pendingSaveFolder)
        outState.putString(STATE_SAVE_LEAF, pendingSaveLeaf)
    }

    // Rebuilt on every resume rather than watched: coming back from the picker,
    // from the share sheet, or from CP/M having run W8 all change the folders,
    // and a FileObserver would be a lot of machinery for a list of five rows.
    override fun onResume() {
        super.onResume()
        loadRows()
    }

    override fun onDestroy() {
        super.onDestroy()
        // shutdown, not shutdownNow: a copy already running holds a half-written
        // document on the user's side of the boundary and must be allowed to
        // finish it.
        io.shutdown()
    }

    private fun loadRows() {
        val imports = transferDir(this, IMPORTS_DIR_NAME)
        val exports = transferDir(this, EXPORTS_DIR_NAME)

        // getExternalFilesDir(null) can return null, and everything on this
        // screen hangs off it. Say so instead of showing two empty sections
        // that look like "you have no files".
        if (imports == null || exports == null) {
            pathsText.text = getString(R.string.transfer_storage_unavailable)
            recyclerView.adapter = FileTransferAdapter(emptyList()) { }
            importButton.isEnabled = false
            return
        }
        importButton.isEnabled = true
        pathsText.text = getString(
            R.string.transfer_paths,
            imports.parentFile?.absolutePath ?: imports.absolutePath
        )

        val rows = mutableListOf<TransferRow>()

        // Imports by name, because the user comes here to find out what to type.
        rows.add(TransferRow(header = getString(R.string.transfer_section_imports)))
        val staged = imports.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList<File>()
        if (staged.isEmpty()) {
            rows.add(TransferRow(note = getString(R.string.transfer_imports_empty)))
        } else {
            staged.forEach { rows.add(TransferRow(dir = imports, file = it)) }
        }

        // Exports newest first, because the interesting one is the file W8 just
        // wrote.
        rows.add(TransferRow(header = getString(R.string.transfer_section_exports)))
        val exported = exports.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList<File>()
        if (exported.isEmpty()) {
            rows.add(TransferRow(note = getString(R.string.transfer_exports_empty)))
        } else {
            exported.forEach { rows.add(TransferRow(dir = exports, file = it)) }
        }

        recyclerView.adapter = FileTransferAdapter(rows) { row -> showRowActions(row) }
    }

    /**
     * The same three actions for a staged import as for an export.
     *
     * Uniform on purpose: the action operates on a File the user tapped that
     * has already been proved to sit in one of the two folders, so there is
     * nothing for a per-section variant to protect. Save-as on an import is a
     * copy back out, and Delete is the only way to clear a staged file now that
     * no file manager can reach the folder.
     */
    private fun showRowActions(row: TransferRow) {
        val file = row.file ?: return
        val actions = arrayOf(
            getString(R.string.transfer_action_save_as),
            getString(R.string.transfer_action_share),
            getString(R.string.transfer_action_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> startSaveAs(row)
                    1 -> shareRow(row)
                    2 -> confirmDelete(row)
                }
            }
            .setNegativeButton(R.string.transfer_cancel, null)
            .show()
    }

    /**
     * Re-prove a row against the folder it claims to be in.
     *
     * The listing already came from listFiles() on that folder, so this can
     * only fail if the file moved, was replaced by a directory or turned into a
     * symlink out of the sandbox between the listing and the tap. Running it
     * anyway is the rule this app follows everywhere: the containment is
     * checked where the action happens, not where the name was produced, which
     * is exactly the check handleHostFileWrite() makes for W8.
     */
    private fun verifiedFile(row: TransferRow): File? {
        val dir = row.dir ?: return null
        val file = row.file ?: return null
        val resolved = resolveInsideDir(dir, file.name)
        // isFile, not merely contained: resolveInsideDir passes the folder
        // itself, and delete() on an empty Exports would take the folder.
        if (resolved == null || !resolved.isFile) {
            Log.w(TAG, "Row no longer resolves inside ${dir.name}: ${file.name}")
            return null
        }
        return resolved
    }

    private fun startSaveAs(row: TransferRow) {
        val file = verifiedFile(row) ?: run { toast(getString(R.string.transfer_file_gone)); return }
        val dir = row.dir ?: return

        pendingSaveFolder = dir.name
        pendingSaveLeaf = file.name
        try {
            saveAsLauncher.launch(Pair(file.name, transferMimeType(file.name)))
        } catch (e: Exception) {
            // A device can genuinely have no documents provider (a stripped
            // build, a locked-down profile). Without this the tap crashes.
            Log.e(TAG, "No ACTION_CREATE_DOCUMENT handler", e)
            pendingSaveFolder = null
            pendingSaveLeaf = null
            toast(getString(R.string.transfer_no_picker))
        }
    }

    private fun onSaveAsResult(uri: Uri?) {
        val folder = pendingSaveFolder
        val leaf = pendingSaveLeaf
        pendingSaveFolder = null
        pendingSaveLeaf = null

        if (uri == null) return // the user backed out of the picker
        if (folder == null || leaf == null) {
            toast(getString(R.string.transfer_file_gone))
            return
        }

        // Re-resolve and re-validate here, not only when the dialog was shown:
        // this callback can arrive in a fresh process, and the file may have
        // been deleted or replaced while the picker was up. transferDir()
        // accepts only the two folder constants, so a restored Bundle cannot
        // name a third place even in principle.
        val dir = transferDir(this, folder)
        if (dir == null) {
            toast(getString(R.string.transfer_storage_unavailable))
            return
        }
        val source = resolveInsideDir(dir, leaf)
        if (source == null || !source.isFile) {
            toast(getString(R.string.transfer_file_gone))
            return
        }

        io.execute {
            val message = copyOut(source, uri)
            mainHandler.post { toast(message) }
        }
    }

    private fun copyOut(source: File, uri: Uri): String {
        return try {
            // "wt", not the default "w": ACTION_CREATE_DOCUMENT can return a
            // document the user chose to overwrite, and "w" does not truncate
            // on a DocumentsProvider - a shorter export would leave the tail of
            // the previous file behind it.
            val sink = contentResolver.openOutputStream(uri, "wt")
            if (sink == null) {
                // A null stream is a failure, not a success with nothing to do:
                // the obvious "?.use { ... }" followed by a success message
                // reports a save that never happened.
                Log.e(TAG, "openOutputStream returned null for $uri")
                getString(R.string.transfer_save_failed, source.name)
            } else {
                sink.use { out -> FileInputStream(source).use { it.copyTo(out) } }
                Log.i(TAG, "Saved ${source.name} out through SAF")
                getString(R.string.transfer_saved, source.name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save as failed for ${source.name}", e)
            getString(R.string.transfer_save_failed, source.name)
        }
    }

    /**
     * Share one file through the app FileProvider.
     *
     * The File argument is always a row the user tapped, never a string that
     * came from getHostFileWriteName() or from anything else the guest said, so
     * no guest text reaches an Intent. The recipient gets a per-Uri read grant
     * that covers this one file, which is what FLAG_GRANT_READ_URI_PERMISSION
     * buys and why the provider is declared exported="false".
     */
    private fun shareRow(row: TransferRow) {
        val file = verifiedFile(row) ?: run { toast(getString(R.string.transfer_file_gone)); return }

        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            // res/xml/file_paths.xml covers Imports/ and Exports/ only. Landing
            // here means the file is outside both, which verifiedFile() should
            // already have refused - so it is a bug report, not a user error.
            Log.e(TAG, "No file_paths entry covers ${file.absolutePath}", e)
            toast(getString(R.string.transfer_share_failed))
            return
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = transferMimeType(file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            // The grant on EXTRA_STREAM alone reaches the app the user PICKS,
            // but not the chooser that shows them the choice, so the share
            // sheet cannot read the file to draw its preview. Measured on an
            // API 36 emulator, which says so in as many words:
            //   ChooserPreview: Could not read content://...fileprovider/... metadata.
            //   If a preview is desired, call Intent#setClipData() to ensure
            //   that the sharesheet is given permission.
            // The ClipData carries the same single Uri, so this widens what the
            // sheet can read and nothing else.
            clipData = ClipData.newUri(contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.transfer_share_chooser)))
        } catch (e: Exception) {
            Log.e(TAG, "No activity accepted the share", e)
            toast(getString(R.string.transfer_share_failed))
        }
    }

    private fun confirmDelete(row: TransferRow) {
        val file = row.file ?: return
        val dir = row.dir ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.transfer_delete_title, file.name))
            .setMessage(getString(R.string.transfer_delete_message, dir.name))
            .setPositiveButton(R.string.transfer_action_delete) { _, _ -> deleteRow(row) }
            .setNegativeButton(R.string.transfer_cancel, null)
            .show()
    }

    /**
     * Delete one plain file.
     *
     * File.delete(), never deleteRecursively and never on a directory. The
     * equivalent call on iOS was removeItem on a path that had resolved to the
     * parent of Exports, and it took the user's whole disk library with it
     * (ioscpm build 52). verifiedFile() is what makes the argument here a file
     * inside one of the two folders; delete() is what makes the blast radius
     * that one file even if it were not.
     */
    private fun deleteRow(row: TransferRow) {
        val file = verifiedFile(row) ?: run { toast(getString(R.string.transfer_file_gone)); return }
        val name = file.name
        io.execute {
            val deleted = try {
                file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Delete failed for $name", e)
                false
            }
            mainHandler.post {
                toast(
                    if (deleted) getString(R.string.transfer_deleted, name)
                    else getString(R.string.transfer_delete_failed, name)
                )
                loadRows()
            }
        }
    }

    /**
     * Pick one or more files and copy them into Imports.
     *
     * ACTION_OPEN_DOCUMENT rather than GetContent, so what comes back is a real
     * document Uri; multi-select because ioscpm's handleImportToInbox already
     * takes a list and one contract swap is the whole cost. No
     * takePersistableUriPermission: the bytes are copied immediately and the
     * app never refers to the source again.
     */
    private fun startImport() {
        try {
            importLauncher.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Log.e(TAG, "No ACTION_OPEN_DOCUMENT handler", e)
            toast(getString(R.string.transfer_no_picker))
        }
    }

    private fun onImportResult(uris: List<Uri>) {
        if (uris.isEmpty()) return // cancelled
        io.execute {
            val results = uris.map { stageImportStream(this@FileTransferActivity, it) }
            val message = importResultMessage(this@FileTransferActivity, results)
            mainHandler.post {
                // LENGTH_LONG: this Toast carries the name the user has to type
                // at the CP/M prompt, and 8.3 mangling means it is usually not
                // the name they picked.
                Toast.makeText(this@FileTransferActivity, message, Toast.LENGTH_LONG).show()
                loadRows()
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

/**
 * ACTION_CREATE_DOCUMENT with the MIME type chosen at launch time.
 *
 * ActivityResultContracts.CreateDocument fixes the type when the launcher is
 * REGISTERED, and registration has to happen before the activity is STARTED -
 * long before the user has picked which file to save. A list whose first row is
 * NOTES.TXT and whose second is R8.COM has no single right answer, and the
 * wrong one makes DocumentsUI offer to save the text file as a .bin. Hence a
 * contract that takes (suggested name, type) as its input instead.
 */
private class CreateNamedDocument : ActivityResultContract<Pair<String, String>, Uri?>() {

    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
