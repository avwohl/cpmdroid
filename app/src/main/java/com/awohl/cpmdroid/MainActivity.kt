package com.awohl.cpmdroid

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.awohl.cpmdroid.data.DiskDownloadManager
import com.awohl.cpmdroid.data.EmulatorSettings
import com.awohl.cpmdroid.data.RomFailure
import com.awohl.cpmdroid.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val FRAME_DELAY_MS = 16L // ~60fps when actively executing
        private const val IDLE_DELAY_MS = 100L // 10Hz when waiting for keyboard input
    }

    private lateinit var terminalView: TerminalView
    private lateinit var playPauseButton: ImageButton
    private lateinit var bootButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var statusText: TextView

    // Control strip buttons
    private lateinit var ctrlButton: Button
    private lateinit var escButton: Button
    private lateinit var tabButton: Button
    private lateinit var copyButton: Button
    private lateinit var pasteButton: Button

    // Toolbar buttons
    private lateinit var filesButton: ImageButton
    private lateinit var helpButton: ImageButton
    private lateinit var aboutButton: ImageButton

    // Controlify mode: next key becomes control character
    private var controlifyMode = false

    // Download progress overlay views
    private lateinit var downloadOverlay: FrameLayout
    private lateinit var downloadTitle: TextView
    private lateinit var downloadFileName: TextView
    private lateinit var downloadProgressBar: ProgressBar
    private lateinit var downloadPercent: TextView

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var downloadManager: DiskDownloadManager

    private val emulator = EmulatorEngine()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var romLoaded = false
    private var running = false
    private var initialFocusDone = false
    private var lastDiskSlots: List<String?> = emptyList()
    private var cameFromSettings = false

    // The release the running machine's ROM and disks belong to, snapshotted
    // the same way lastDiskSlots is and compared in onResume. Changing release
    // in Settings changes the ROM, not only the four slots, so the disk-only
    // reload that already runs there is not enough: it would leave 3.6.0 disks
    // mounted under the ROM 3.5.1 was started with, which is the pairing this
    // whole path exists to make impossible.
    private var lastRomwbwVersion: String? = null

    // The ROM pick the running machine was started under, snapshotted the same
    // way lastRomwbwVersion is. A release change is not the only way the ROM can
    // change now that the user picks one: choosing emu_rcz80 for the release
    // already selected changes the bytes without changing the release, and
    // without this the machine would keep running the ROM it started on while
    // Settings said otherwise. Null means "the release default", which is a
    // distinct value from any id and compares correctly.
    private var lastRomId: String? = null

    // True once a machine has been started in this process. A second
    // loadRomAndDisks() - which only a release change causes - is a reboot onto
    // a different ROM, not a first boot, so it goes through the same reset the
    // Reboot button uses rather than loading a new ROM under a CPU that is
    // mid-instruction against the old one.
    private var machineStartedOnce = false

    // Set by checkFirstLaunchAndLoad and consumed once the ROM is resolved: the
    // starter disk is fetched after the ROM, never before.
    private var needsDefaultDisk = false

    // The release a ROM resolution is running for, or null when none is. Main
    // thread only, set in startMachine() and cleared where the resolution
    // ends - at the load, or at the dialog that says why there is none.
    //
    // It answers two questions with one field. Asking again for the release
    // already on its way is a no-op, which matters because the play button now
    // re-runs the resolution and the download overlay does not consume the tap
    // that reaches it. And a resolution that finishes AFTER another one started
    // is dropped: hashing 512 KB takes long enough to be overtaken by a release
    // switch, and applying its bytes would mount one release's disks under
    // another release's ROM - the pairing every other line of this path exists
    // to prevent.
    private var resolvingRomFor: String? = null

    // True while the first-run index fetch is in flight. Separate from
    // resolvingRomFor, which is keyed on a release: before the index arrives
    // there is no release to key on, and using the anchor as one would let a
    // second tap start a second fetch under a name that means nothing.
    private var resolvingRelease = false

    private var runLoopCount = 0
    // Elapsed-time based (iteration counts stretched ~6x whenever the guest
    // idles at a prompt, because idle iterations use IDLE_DELAY_MS)
    private var lastNvramSaveMs = 0L
    private var lastDiskSaveMs = 0L

    // Filenames of the disks actually mounted per unit. saveDirtyDisks uses
    // this (not the current Settings slots) so data is always persisted under
    // the filename it belongs to, even mid-way through a slot reassignment.
    private val loadedDiskFilenames = arrayOfNulls<String>(16)

    // Units whose last save failed. The core's dirty flag does not survive a
    // reboot (loadDisk clears it), but the modified bytes do - this keeps the
    // retry alive until a save succeeds or the unit is reloaded/closed.
    private val failedSaveUnits = mutableSetOf<Int>()
    private val runLoop: Runnable = object : Runnable {
        override fun run() {
            if (running && romLoaded) {
                val self = this
                if (runLoopCount++ < 5) {
                    Log.i(TAG, "runLoop #$runLoopCount: executing batch")
                }
                executor.execute {
                    val shouldContinue = emulator.runBatch()
                    if (runLoopCount <= 5) {
                        Log.i(TAG, "runLoop #$runLoopCount: batch returned $shouldContinue")
                    }

                    // Check host file state for R8/W8 transfers
                    checkHostFileState()

                    // Periodically save NVRAM (~5 seconds)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNvramSaveMs >= 5_000) {
                        lastNvramSaveMs = now
                        saveNvramIfNeeded()
                    }

                    // Periodically save dirty disks (~20 seconds)
                    if (now - lastDiskSaveMs >= 20_000) {
                        lastDiskSaveMs = now
                        saveDirtyDisks()
                    }

                    if (shouldContinue) {
                        val delay = if (emulator.isWaitingForInput()) IDLE_DELAY_MS else FRAME_DELAY_MS
                        mainHandler.postDelayed(self, delay)
                    } else {
                        Log.w(TAG, "runLoop: batch returned false, stopping")
                    }
                }
            } else {
                if (runLoopCount < 5) {
                    Log.w(TAG, "runLoop: skipped (running=$running, romLoaded=$romLoaded)")
                }
            }
        }
    }

    // Imports/Exports folders for R8/W8 file transfer.
    //
    // Nullable, and resolved on each use rather than held in a lazy: the lazy
    // hid the fact that getExternalFilesDir(null) can return null, and
    // File(null, "Imports") is a RELATIVE path in the process working
    // directory - a folder no file manager, no adb pull and no user will ever
    // find. transferDir() answers null instead and says why in the log; the two
    // handlers below report it where they already report every other outcome.
    // A caching lazy would also pin the first answer for the life of the
    // process, which is wrong for a state the platform can change under us.
    private val importsDir: File?
        get() = transferDir(this, IMPORTS_DIR_NAME)
    private val exportsDir: File?
        get() = transferDir(this, EXPORTS_DIR_NAME)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Handle window insets for edge-to-edge AND keyboard
        val rootLayout = findViewById<View>(R.id.rootLayout)

        // Track inset values from both methods
        var lastSystemBarsBottom = 0
        var lastImeBottom = 0
        var lastMeasuredKeyboardHeight = 0

        // Method 1: WindowInsets (works for most standard keyboards)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())

            lastSystemBarsBottom = systemBars.bottom
            lastImeBottom = ime.bottom

            // Use the larger of all methods: systemBars, IME insets, or measured keyboard height
            val bottomInset = maxOf(systemBars.bottom, ime.bottom, lastMeasuredKeyboardHeight)

            Log.i(TAG, "Insets: systemBars.bottom=${systemBars.bottom}, ime.bottom=${ime.bottom}, measured=$lastMeasuredKeyboardHeight, using bottom=$bottomInset")

            // Apply padding to the root layout
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, bottomInset)

            // Recalculate terminal size after insets change (fixes first-launch cutoff)
            if (::terminalView.isInitialized) {
                terminalView.post { terminalView.recalculateSize() }
            }

            WindowInsetsCompat.CONSUMED
        }

        // Method 2: ViewTreeObserver fallback for third-party keyboards that don't report IME insets correctly
        // This measures the actual visible display frame to detect keyboard presence
        rootLayout.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            rootLayout.getWindowVisibleDisplayFrame(r)

            // Get the screen height from the root view's actual size (after initial layout)
            val screenHeight = rootLayout.rootView.height

            // Calculate keyboard height as the difference between screen and visible area
            // Subtract status bar / navigation bar space that's already in the visible frame
            val keyboardHeight = screenHeight - r.bottom

            // Only consider it a keyboard if it's reasonably sized (> 100px)
            // This filters out small navigation bar differences
            if (keyboardHeight > 100) {
                if (keyboardHeight != lastMeasuredKeyboardHeight) {
                    lastMeasuredKeyboardHeight = keyboardHeight
                    Log.i(TAG, "Measured keyboard height: $keyboardHeight (screen=$screenHeight, visible.bottom=${r.bottom})")

                    // If measured height is larger than what WindowInsets reported, update padding
                    val currentBottom = maxOf(lastSystemBarsBottom, lastImeBottom)
                    if (keyboardHeight > currentBottom) {
                        Log.i(TAG, "Using measured keyboard height $keyboardHeight instead of insets $currentBottom")
                        rootLayout.setPadding(
                            rootLayout.paddingLeft,
                            rootLayout.paddingTop,
                            rootLayout.paddingRight,
                            keyboardHeight
                        )
                        if (::terminalView.isInitialized) {
                            terminalView.post { terminalView.recalculateSize() }
                        }
                    }
                }
            } else if (lastMeasuredKeyboardHeight > 0) {
                // Keyboard was hidden — restore the padding to just the system bars
                // and recalc. Without this the keyboard-height bottom padding lingers
                // as a blank strip along the bottom (the ime inset may report 0 while
                // this measured value was still driving the padding).
                lastMeasuredKeyboardHeight = 0
                Log.i(TAG, "Keyboard hidden (measured height: $keyboardHeight)")
                rootLayout.setPadding(
                    rootLayout.paddingLeft,
                    rootLayout.paddingTop,
                    rootLayout.paddingRight,
                    lastSystemBarsBottom
                )
                if (::terminalView.isInitialized) {
                    terminalView.post { terminalView.recalculateSize() }
                }
            }
        }

        settingsRepo = SettingsRepository(this)
        // downloadManager before the migration, because the migration renames
        // the files in its two directories. This is also the only place the
        // rename may run: it must be finished before checkFirstLaunchAndLoad()
        // at the end of onCreate, which reads a slot naming a file it cannot
        // find as "first launch" and writes the catalog's default disk over the
        // user's slot 0; and it must be before any ROM is loaded, because
        // saveDirtyDisks() writes each unit back under the name it was loaded
        // from and would put a pre-v0 name back into ModifiedDisks after the
        // pass had moved it. Do not add a second entry point in SettingsActivity.
        downloadManager = DiskDownloadManager(this)
        val nameMigration = settingsRepo.migrateIfNeeded(
            downloadManager.getDisksDirOrNull(),
            downloadManager.getPersistedDisksDirOrNull()
        )
        if (nameMigration != null) {
            Log.i(TAG, "v0 disk-name migration: renamed=${nameMigration.renamed}, " +
                "complete=${nameMigration.complete}, slots=${nameMigration.slots}")
        }
        logRomwbwSelection()

        terminalView = findViewById(R.id.terminalView)
        playPauseButton = findViewById(R.id.playPauseButton)
        bootButton = findViewById(R.id.bootButton)
        settingsButton = findViewById(R.id.settingsButton)
        statusText = findViewById(R.id.statusText)

        // Control strip buttons
        ctrlButton = findViewById(R.id.ctrlButton)
        escButton = findViewById(R.id.escButton)
        tabButton = findViewById(R.id.tabButton)
        copyButton = findViewById(R.id.copyButton)
        pasteButton = findViewById(R.id.pasteButton)

        // Toolbar buttons
        filesButton = findViewById(R.id.filesButton)
        helpButton = findViewById(R.id.helpButton)
        aboutButton = findViewById(R.id.aboutButton)

        // Download progress overlay
        downloadOverlay = findViewById(R.id.downloadOverlay)
        downloadTitle = findViewById(R.id.downloadTitle)
        downloadFileName = findViewById(R.id.downloadFileName)
        downloadProgressBar = findViewById(R.id.downloadProgressBar)
        downloadPercent = findViewById(R.id.downloadPercent)

        setupEmulator()
        setupToolbar()
        setupControlStrip()

        checkFirstLaunchAndLoad()
    }

    /**
     * Wake the run loop immediately after input is queued.
     * Cancels any pending delayed post (which may be 100ms away during idle)
     * and reposts immediately so input is processed without lag.
     */
    private fun wakeRunLoop() {
        if (running && romLoaded) {
            mainHandler.removeCallbacks(runLoop)
            mainHandler.post(runLoop)
        }
    }

    private fun setupEmulator() {
        emulator.init()
        // The native side reduces a guest path to a leaf and then has to say
        // where that leaf will land, because W8 prints it. Only Kotlin knows
        // the answer, so hand it down once, before any transfer can start.
        //
        // When there is no external storage there is no answer to hand down, so
        // the call is simply not made: emu_host_file_open_write() then leaves
        // g_host_exports_dir empty and W8 prints a bare filename instead of a
        // path that does not exist. That is the core's own "no exports dir"
        // state, and it beats inventing one here - the alternative would be
        // telling the CP/M user a path nothing can open. (setHostExportsDir
        // takes a non-null String, and EmulatorEngine.kt is not ours to widen.)
        val exports = exportsDir
        if (exports != null) {
            emulator.setHostExportsDir(exports.absolutePath)
        } else {
            Log.w(TAG, "No Exports folder: external storage unavailable")
        }
        emulator.setOutputListener { data ->
            mainHandler.post {
                terminalView.processOutput(data)
            }
        }
        // Set up terminal input - characters typed go to emulator
        // with controlify conversion if Ctrl mode is active
        terminalView.setInputListener { ch ->
            val charToSend = if (controlifyMode) {
                // Convert to control character: A-Z and a-z become 1-26
                val upper = if (ch in 'a'.code..'z'.code) ch - 32 else ch
                if (upper in '@'.code..'_'.code) {
                    controlifyMode = false
                    updateCtrlButtonState()
                    upper - '@'.code  // '@'=0, 'A'=1, ... 'Z'=26
                } else {
                    controlifyMode = false
                    updateCtrlButtonState()
                    ch
                }
            } else {
                ch
            }
            emulator.queueInput(charToSend)
            wakeRunLoop()
        }
    }

    private fun setupControlStrip() {
        // Ctrl button toggles controlify mode
        ctrlButton.setOnClickListener {
            controlifyMode = !controlifyMode
            updateCtrlButtonState()
        }

        // Esc sends escape character (0x1B)
        escButton.setOnClickListener {
            controlifyMode = false
            updateCtrlButtonState()
            terminalView.returnToLive()
            emulator.queueInput(0x1B)
            wakeRunLoop()
        }

        // Tab sends tab character (0x09)
        tabButton.setOnClickListener {
            controlifyMode = false
            updateCtrlButtonState()
            terminalView.returnToLive()
            emulator.queueInput(0x09)
            wakeRunLoop()
        }

        // Copy screen to clipboard
        copyButton.setOnClickListener {
            if (terminalView.copyScreenToClipboard()) {
                Toast.makeText(this, "Screen copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        // Paste from clipboard
        pasteButton.setOnClickListener {
            terminalView.pasteFromClipboard()
        }
    }

    private fun updateCtrlButtonState() {
        if (controlifyMode) {
            ctrlButton.setBackgroundColor(0xFF2196F3.toInt()) // Blue when active
        } else {
            ctrlButton.setBackgroundColor(0xFF555555.toInt()) // Gray when inactive
        }
    }

    /**
     * The toolbar is plain ImageButtons with click listeners, and that is
     * deliberate - do not turn it into an ActionBar, a Toolbar with menu
     * items, or an options menu.
     *
     * Any of those switches on Android's alphabeticShortcut handling, which
     * claims Ctrl-letter presses before the focused view sees them. Every
     * Ctrl-letter belongs to CP/M (see "Ctrl-A..Ctrl-Z Belong to the Guest" in
     * romwbw_emu/DOWNSTREAM.md); z80cpmw shipped exactly that bug, where Ctrl+R
     * rebooted the machine instead of reaching WordStar. Today there is no
     * res/menu, no alphabeticShortcut, no onCreateOptionsMenu, and the theme is
     * Material3.DayNight.NoActionBar - keep it that way.
     */
    private fun setupToolbar() {
        playPauseButton.setOnClickListener {
            when {
                running -> stopEmulation()
                // No ROM was resolved, so there is nothing to start and
                // startEmulation() would do nothing at all. Ask again instead:
                // the release's ROM may have arrived since, and if it has not,
                // the dialog that says what is missing comes back rather than
                // the button silently doing nothing.
                !romLoaded -> startMachine()
                else -> startEmulation()
            }
        }

        bootButton.setOnClickListener {
            // With no ROM there is no machine to restart, and resetting the
            // core would destroy and recreate a state that never held anything
            // - clearing the screen and the "ROM needed" status with it, so the
            // one thing telling the user what to do next disappears and nothing
            // starts. Ask for the ROM again instead, which is what the play
            // button does from the same state.
            if (!romLoaded) {
                startMachine()
            } else {
                showRestartConfirmDialog()
            }
        }

        settingsButton.setOnClickListener {
            if (running) {
                Toast.makeText(this, "Stop emulator before changing settings", Toast.LENGTH_SHORT).show()
            } else {
                cameFromSettings = true
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }

        // Deliberately NOT guarded by "Stop emulator first" the way the
        // settings button is. Settings has to refuse while running because it
        // reloads disks and resets the machine under a live guest; moving a
        // file in or out of Imports/Exports conflicts with nothing, and the
        // workflow this exists for is "W8 MYFILE.TXT at the CP/M prompt, then
        // share it", which a refusal would break. The interlock that matters
        // comes from the lifecycle instead: starting an activity runs onPause
        // here, which waits for the in-flight batch and stops the run loop, so
        // no R8/W8 handshake can be halfway through a file while that screen is
        // in front. onResume starts the emulator again, exactly as for Help.
        filesButton.setOnClickListener {
            startActivity(Intent(this, FileTransferActivity::class.java))
        }

        helpButton.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }

        aboutButton.setOnClickListener {
            showAboutDialog()
        }
    }

    private fun getVersionString(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    /**
     * versionCode of the APK that is actually installed. Worth showing next to
     * the name because it is the number Play orders releases by, so an install
     * that silently did not replace an older one shows up here as a number that
     * went backwards.
     */
    private fun getVersionCode(): Long {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * When the running APK was written, read from the file backing this very
     * process. Unlike anything baked into BuildConfig at compile time, this
     * cannot survive into a later build: if it reports last month, then last
     * month's APK is what is running, whatever the source tree says. This is
     * the one field that answers "did my install actually take?".
     */
    private fun getApkBuildTime(): String {
        return try {
            val written = File(applicationInfo.sourceDir).lastModified()
            if (written > 0) {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(written))
            } else {
                "unknown"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun createVersionBanner(): ByteArray {
        return ("CPMDroid v${getVersionString()} (${getVersionCode()}) " +
            "${BuildConfig.GIT_SHA} ${getApkBuildTime()}\r\n").toByteArray()
    }

    /**
     * Load disks from settings, configure slice counts and manifest flags.
     * Shared by loadRomAndDisks() and reloadDisksFromSettings().
     */
    private fun loadDisksAndConfigureSlices(settings: EmulatorSettings, logPrefix: String = "loaded") {
        var diskCount = 0
        settings.diskSlots.forEachIndexed { index, filename ->
            if (filename != null) {
                val (diskData, isPersisted) = downloadManager.loadDiskDataWithPersistence(filename)
                if (diskData != null) {
                    if (emulator.loadDisk(index, diskData)) {
                        val source = if (isPersisted) "persisted" else "catalog"
                        Log.i(TAG, "Disk $index $logPrefix from $source: $filename (${diskData.size} bytes)")
                        emulator.setDiskIsManifest(index, true)
                        loadedDiskFilenames[index] = filename
                        failedSaveUnits.remove(index)
                        diskCount++
                    } else {
                        Log.e(TAG, "Disk $index failed to load: $filename")
                    }
                } else {
                    Log.w(TAG, "Disk $index file not found: $filename")
                }
            } else if (emulator.isDiskLoaded(index)) {
                // Slot cleared in Settings: unmount, or the old disk stays
                // resident (and reappears on reboot) collecting guest writes
                // that saveDirtyDisks would never persist.
                emulator.closeDisk(index)
                loadedDiskFilenames[index] = null
                failedSaveUnits.remove(index)
                Log.i(TAG, "Disk $index closed (slot cleared)")
            }
        }

        val autoSlices = when {
            diskCount <= 1 -> 8
            diskCount == 2 -> 4
            else -> 2
        }
        Log.i(TAG, "Disk count: $diskCount, auto slices: $autoSlices")

        for (i in 0 until 16) {
            if (emulator.isDiskLoaded(i)) {
                emulator.setDiskSliceCount(i, autoSlices)
            }
        }

        applyManifestWarningPreference()
    }

    /** Re-apply the user's manifest write warning preference to all disks. */
    private fun applyManifestWarningPreference() {
        if (!settingsRepo.isWarnManifestWritesEnabled()) {
            for (i in 0 until 16) {
                emulator.setDiskWarningSuppressed(i, true)
            }
        }
    }

    private fun showAboutDialog() {
        val version = getVersionString()
        val versionCode = getVersionCode()
        val apkBuilt = getApkBuildTime()

        // Two RomWBW facts, because they answer different questions and can
        // disagree: which release's catalog the disk slots and the fetched ROM
        // belong to, and which releases this build's emulator core has been
        // checked against. A release the core cannot run is never offered, so
        // the pair can only disagree if a pinned release outlived core support.
        val selectedRomwbw = settingsRepo.selectedRomwbwVersion()
        val romwbwLine = "$selectedRomwbw selected, core supports " +
            RomwbwSupport.supportedList()

        // Three separate identities, because they answer different questions.
        // "Built" is the installed file's own timestamp and settles whether an
        // install took. "Source" is the commit, and is checkable against the
        // repo - a hash that is not HEAD means this binary is not the tree you
        // are looking at. A +dirty suffix means it was built over uncommitted
        // edits, so no commit describes it exactly.
        AlertDialog.Builder(this)
            .setTitle("About CPMDroid")
            .setMessage("""
                CPMDroid v$version ($versionCode)
                Built: $apkBuilt
                Source: ${BuildConfig.GIT_SHA} of ${BuildConfig.SOURCE_DATE}

                A Z80 CP/M emulator for Android using RomWBW HBIOS.

                RomWBW release: $romwbwLine

                Features:
                - VT100 terminal emulation
                - Multiple disk image support
                - Downloadable OS disk images

                Source code:
                github.com/avwohl/cpmdroid

                Based on RomWBW by Wayne Warthen
                romwbw.net
            """.trimIndent())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showRestartConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("Restart Emulator")
            .setMessage("Restart the emulator? Disk contents are saved; programs in progress and the RAM disk will be reset.")
            .setPositiveButton("Restart") { _, _ ->
                bootEmulation()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManifestWriteWarningDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Disk Write Warning")
            .setMessage("""
                You are writing to a downloaded disk image.

                This disk may be replaced when the app updates, and your changes could be lost.

                To preserve your data, copy files to a different disk or export them using W8.
            """.trimIndent())
            .setPositiveButton("OK", null)
            .create()
        // Dismiss on Return key
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP) {
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        dialog.show()
    }

    /**
     * What release this machine is on, and what the core can run.
     *
     * Two facts rather than three, because there is no ROM in the package to be
     * the third. [V0_LEGACY_ROMWBW] is not among them: it is a historical
     * namespace anchor, not a release this build prefers, so there is nothing
     * left for it to disagree with.
     */
    private fun logRomwbwSelection() {
        Log.i(TAG, "Selected RomWBW ${settingsRepo.selectedRomwbwVersion()}" +
            (if (settingsRepo.hasResolvedRelease()) "" else " (not resolved yet)") +
            "; core supports " + RomwbwSupport.supportedList())
    }

    private fun checkFirstLaunchAndLoad() {
        val settings = settingsRepo.getSettings()
        val slot0Disk = settings.diskSlots.getOrNull(0)
        val needsDownload = !settingsRepo.isFirstLaunchDone() ||
            (slot0Disk != null && !downloadManager.isDiskDownloaded(slot0Disk)) ||
            (slot0Disk == null && downloadManager.getDownloadedDisks().isEmpty())

        Log.i(TAG, "checkFirstLaunchAndLoad: firstLaunchDone=${settingsRepo.isFirstLaunchDone()}, " +
            "slot0=$slot0Disk, needsDownload=$needsDownload")

        // The ROM is settled first, and the starter disk only after. The other
        // order costs a user with no ROM 49 MB of images before a dialog tells
        // them the machine cannot start anyway - and that state is reachable
        // without doing anything odd: a restore from backup brings the
        // preferences naming a release and need not bring its 512 KB of ROM.
        needsDefaultDisk = needsDownload
        startMachine()
    }

    /**
     * Resolve the ROM for the selected release, then start on it - or say what
     * is missing and start on nothing.
     *
     * The ROM has to land BEFORE the emulator does anything, and that is what
     * makes it different from a disk. A missing disk is an empty drive; a ROM
     * from the wrong release is a guest that prints
     * `*** WARNING: HBIOS/CBIOS Version Mismatch ***` and then misbehaves, and
     * the whole reason this app fetches the ROM from the catalog at all is to
     * stop that pairing existing. So there is no path here that starts a
     * machine on some other release's ROM because the selected one was awkward
     * to get: falling back would recreate the exact mismatch, and would do it
     * invisibly.
     *
     * The cost, named rather than discovered: a first launch with no network
     * cannot start. This app carries no ROM, and a ROM cannot be verified
     * without the catalog that publishes its size and hash, so there is nothing
     * to boot until one fetch has succeeded. Every later launch is offline -
     * the claim is stored with the file and re-checked against it - and the
     * no-ROM state is reported through romUnavailable() with a Download button
     * rather than left as a machine that does nothing.
     */
    private fun startMachine() {
        val settings = settingsRepo.getSettings()

        // An install that has never resolved a release against a published
        // index has no business asking whether THAT release's ROM is here.
        // selectedRomwbwVersion() answers the legacy namespace anchor until an
        // index arrives, so going on from here would report "RomWBW 3.5.1 has
        // no ROM on this device yet" and offer to fetch 3.5.1's ROM on a device
        // the catalog would have put on 3.6.0. Resolve first, then start.
        if (!settingsRepo.hasResolvedRelease()) {
            resolveReleaseThenStart(settings)
            return
        }

        val selected = settingsRepo.selectedRomwbwVersion()

        if (resolvingRomFor == selected) {
            Log.i(TAG, "RomWBW $selected ROM is already being resolved; not asking twice")
            return
        }
        resolvingRomFor = selected

        lastRomwbwVersion = selected

        // What the catalog promised about this release's ROM when it was
        // fetched. Without a claim there is nothing to check the file against,
        // and a 512 KB file of unknown provenance is exactly what must not be
        // handed to the emulator. Nothing is fetched here without being asked:
        // the network belongs to the dialog's Download button, not to a launch.
        //
        // A claim for a ROM that is no longer the selected one is not a claim
        // about what to boot, so it is treated as no claim at all and
        // fetchRomForRelease() is left to get the right one.
        val wantedRomId = settingsRepo.selectedRomId(selected)
        lastRomId = wantedRomId
        val stored = settingsRepo.romClaim(selected)
        val claim = stored?.takeIf { wantedRomId == null || it.romId == wantedRomId }
        if (claim == null) {
            // Which of the two it is matters to the person reading it: nothing
            // fetched for this release at all, or a ROM that is here and is not
            // the one they chose.
            val why = if (stored != null && wantedRomId != null) {
                RomFailure.PickedRomNotFetched(selected, wantedRomId)
            } else {
                RomFailure.NeverFetched(selected)
            }
            romUnavailable(settings, selected, why)
            return
        }
        // Off the main thread: 512 KB read plus a SHA-256 over it, on the
        // launch path, on whatever storage the device has.
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                downloadManager.readVerifiedRom(selected, claim)
            }
            // Overtaken while it was hashing: these are the bytes of a release
            // nothing is selecting any more.
            if (resolvingRomFor != selected) return@launch
            bytes.fold(
                onSuccess = { romResolved(settings, selected, it) },
                onFailure = { romUnavailable(settings, selected, it) }
            )
        }
    }

    /**
     * First launch: find out which release this machine should be on, then go.
     *
     * The index is what decides. An install that has never picked a release by
     * hand follows the entry the index marks `default: true`, which is the
     * mechanism that lets a newly published RomWBW release reach users with no
     * app release - and the reason this app no longer starts life pinned to
     * whichever release it happened to ship a ROM for.
     *
     * Nothing is asked before downloading. A first launch already fetches a
     * 49 MB starter disk unprompted, so stopping to ask permission for a 512 KB
     * ROM would present the ordinary cost of setting the app up as though it
     * were a fault - the mistake the sibling port made and backed out. A dialog
     * appears only when the fetch cannot be done at all.
     */
    private fun resolveReleaseThenStart(settings: EmulatorSettings) {
        if (resolvingRelease) {
            Log.i(TAG, "The release is already being resolved; not asking twice")
            return
        }
        resolvingRelease = true

        showDownloadProgress("Setting up CPMDroid", "Reading the catalog...")
        lifecycleScope.launch {
            val result = try {
                loadSelectedCatalog(downloadManager, settingsRepo)
            } finally {
                resolvingRelease = false
                hideDownloadProgress()
            }
            result.fold(
                onSuccess = { selection ->
                    val version = selection.selected.romwbwVersion
                    Log.i(TAG, "First run resolved to RomWBW $version " +
                        "(${selection.catalog.disks.size} disks, " +
                        "${selection.catalog.roms.size} ROMs published)")
                    // Straight to the fetch. There is no claim yet by
                    // definition, so going back through startMachine() would
                    // only reach romUnavailable() and put a dialog in front of
                    // a user who has asked for nothing but a working machine.
                    fetchRomThenStart(settingsRepo.getSettings(), version)
                },
                onFailure = { releaseUnresolved(settings, it) }
            )
        }
    }

    /**
     * The first fetch did not happen, so there is not even a release to name.
     *
     * Deliberately a different message from romUnavailable(): that one knows
     * which release needs which file, and this one knows neither. This is the
     * cost of carrying no ROM, stated plainly rather than left as an app that
     * appears to do nothing.
     */
    private fun releaseUnresolved(settings: EmulatorSettings, error: Throwable) {
        Log.e(TAG, "Could not resolve a RomWBW release: ${error.message}", error)
        statusText.text = "Setup needed"
        statusText.setTextColor(0xFFFF8800.toInt())

        AlertDialog.Builder(this)
            .setTitle("CPMDroid needs to set up once")
            .setMessage(
                "${error.message}\n\n" +
                    "CPMDroid does not carry a ROM. The first start downloads the ROM and a " +
                    "starter disk for the current RomWBW release and checks them against the " +
                    "catalog. After that it works offline. Nothing else is needed."
            )
            .setPositiveButton("Try again") { _, _ -> startMachine() }
            .setCancelable(true)
            .show()
    }

    /**
     * The ROM is in hand: fetch the starter disk if this is that kind of
     * launch, then start.
     *
     * [romBytes] has already been verified against the catalog's size and
     * sha256 - there is no other way for a ROM to reach this app.
     * [romwbwVersion] is the release those bytes are for, and is carried rather
     * than re-read because the starter-disk fetch below can change what is
     * selected while it runs.
     */
    private fun romResolved(
        settings: EmulatorSettings,
        romwbwVersion: String,
        romBytes: ByteArray
    ) {
        if (needsDefaultDisk) {
            needsDefaultDisk = false
            downloadDefaultDisk(romwbwVersion, romBytes)
        } else {
            loadRomAndDisks(settings, romBytes)
        }
    }

    /**
     * The selected release has no usable ROM: name it, and offer to fetch it.
     *
     * There is deliberately no button that starts anyway. The message names the
     * release, the file and the reason, because "it did not start" with no
     * further detail is indistinguishable from a crash, and the causes - never
     * fetched, deleted since, or on disk and not what the catalog describes -
     * want different responses.
     *
     * There is also no "use the release this app came with" button any more,
     * because there is no ROM in the package for it to mean. On a first launch
     * with no network this dialog IS the app: Download is the only way forward,
     * and saying so plainly is better than a machine that appears to do nothing.
     */
    private fun romUnavailable(
        settings: EmulatorSettings,
        romwbwVersion: String,
        error: Throwable
    ) {
        Log.e(TAG, "No usable ROM for RomWBW $romwbwVersion: ${error.message}", error)
        resolvingRomFor = null
        statusText.text = "ROM needed"
        statusText.setTextColor(0xFFFF8800.toInt())

        AlertDialog.Builder(this)
            .setTitle("RomWBW $romwbwVersion needs its ROM")
            .setMessage(
                "${error.message}\n\n" +
                    "CPMDroid downloads the ROM for the release it is set to, and checks it " +
                    "against the catalog's hash before booting it. It will not start a machine " +
                    "on one release's disks with another release's ROM: CP/M prints " +
                    "*** WARNING: HBIOS/CBIOS Version Mismatch *** and behaves unpredictably. " +
                    "Nothing already downloaded is affected."
            )
            .setPositiveButton("Download ROM") { _, _ ->
                fetchRomThenStart(settings, romwbwVersion)
            }
            // Dismissable, because a modal with one button that can keep failing
            // - on a plane, behind a captive portal - is a trap. Dismissing
            // starts nothing: the status strip stays on "ROM needed", and the
            // play button brings this back rather than doing nothing.
            .setCancelable(true)
            .show()
    }

    /**
     * Fetch the release's ROM with the progress the user would get for a disk,
     * then start on it.
     *
     * 512 KB is quick, and on a bad connection it is the one thing standing
     * between the user and a machine that boots, so it uses the same overlay a
     * disk download does rather than looking like a hang. The fetch is waited
     * on - the machine starts from its completion, not from a timer.
     */
    private fun fetchRomThenStart(
        settings: EmulatorSettings,
        romwbwVersion: String
    ) {
        // The file's own name is not known until the catalog has been read, and
        // the index and the catalog are two round trips before a byte of ROM
        // moves - so the overlay says what is happening from the first moment
        // rather than sitting blank until the transfer starts.
        showDownloadProgress("Downloading the RomWBW $romwbwVersion ROM", "Reading the catalog...")
        resolvingRomFor = romwbwVersion
        lifecycleScope.launch {
            val result = fetchRomForRelease(
                downloadManager, settingsRepo, romwbwVersion
            ) { bytesRead, totalBytes ->
                val percent = if (totalBytes > 0) (bytesRead * 100 / totalBytes).toInt() else 0
                runOnUiThread { updateDownloadProgress(percent) }
            }
            hideDownloadProgress()
            // Same rule as the launch path: a fetch that finished after the
            // user moved to another release is not the ROM to start on.
            if (resolvingRomFor != romwbwVersion) return@launch
            result.fold(
                onSuccess = { romResolved(settings, romwbwVersion, it) },
                onFailure = { romUnavailable(settings, romwbwVersion, it) }
            )
        }
    }

    /**
     * The starter disk, fetched after the ROM and never before.
     *
     * [romwbwVersion] is the release [romBytes] belong to. It has to be carried
     * because loadSelectedCatalog() below writes a new selection back when the
     * stored one is no longer on offer - a release withdrawn upstream, or one a
     * rebuilt core no longer runs - and it then downloads THAT release's
     * starter disk. Loading the ROM this coroutine started with on top of that
     * would pair one release's disks with another release's ROM, which is the
     * one outcome none of this is allowed to produce.
     */
    private fun downloadDefaultDisk(romwbwVersion: String, romBytes: ByteArray) {
        statusText.text = "First launch setup..."
        Log.i(TAG, "First launch - fetching disk catalog...")

        lifecycleScope.launch {
            // index -> the releases this core can run -> the selected one's
            // catalog. Two round trips where there was one, and no tag
            // interpolated into either of them.
            val catalogResult = loadSelectedCatalog(downloadManager, settingsRepo)
            val selection = catalogResult.getOrNull()

            if (selection != null) {
                val catalog = selection.catalog
                Log.i(TAG, "RomWBW ${selection.selected.romwbwVersion} catalog fetched: " +
                    "${catalog.disks.size} disks, generation ${catalog.generation}")
                catalog.disks.forEach { disk ->
                    Log.d(TAG, "  - ${disk.id} ${disk.filename}: defaultSlot=${disk.defaultSlot}")
                }

                // Whichever entry carries defaultSlot 0 - one does today
                // (hd1k_combo) and the field is optional, so no entry carrying
                // it is a catalog that simply nominates no starter disk, not an
                // error.
                val defaultDisk = catalog.disks.find { it.defaultSlot == 0 }

                if (defaultDisk != null && !downloadManager.isDiskDownloaded(defaultDisk.filename)) {
                    Log.i(TAG, "Will download default disk: ${defaultDisk.filename}")

                    // Show progress overlay
                    showDownloadProgress("Downloading ${defaultDisk.name}", defaultDisk.filename)

                    val result = downloadManager.downloadDisk(defaultDisk) { bytesRead, totalBytes ->
                        val percent = if (totalBytes > 0) (bytesRead * 100 / totalBytes).toInt() else 0
                        runOnUiThread {
                            updateDownloadProgress(percent)
                        }
                    }

                    hideDownloadProgress()

                    result.fold(
                        onSuccess = { file ->
                            Log.i(TAG, "Downloaded disk: ${file.absolutePath} (${file.length()} bytes)")
                            settingsRepo.setDiskSlot(0, defaultDisk.filename)
                            settingsRepo.markFirstLaunchDone()
                            Toast.makeText(this@MainActivity,
                                "Downloaded ${defaultDisk.name}", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { e ->
                            Log.e(TAG, "Failed to download default disk: ${e.message}", e)
                            settingsRepo.markFirstLaunchDone()
                        }
                    )
                } else if (defaultDisk != null) {
                    Log.i(TAG, "Default disk already downloaded: ${defaultDisk.filename}")
                    settingsRepo.setDiskSlot(0, defaultDisk.filename)
                    settingsRepo.markFirstLaunchDone()
                } else {
                    Log.w(TAG, "No default disk found in catalog (defaultSlot=0)")
                    settingsRepo.markFirstLaunchDone()
                }
            } else {
                // Said out loud, not only logged. This is a first launch: the
                // user is looking at an emulator with no disks, and the four
                // reasons that can happen - no index, no catalog, a catalog that
                // did not verify, or a build whose core can run nothing
                // published - want four different responses from them. Only the
                // last one is not fixed by trying again later.
                val error = catalogResult.exceptionOrNull()
                Log.e(TAG, "Could not fetch disk catalog: ${error?.message}", error)
                Toast.makeText(
                    this@MainActivity,
                    error?.message ?: "Could not fetch the disk catalog",
                    Toast.LENGTH_LONG
                ).show()
                settingsRepo.markFirstLaunchDone()
            }

            // The ROM in hand is only the right one if the release did not move
            // underneath it. When it did, resolve again for the release that is
            // selected now rather than start on the bytes of the one that was:
            // needsDefaultDisk has already been consumed, so this goes straight
            // to the load or to the dialog that says why it cannot.
            val nowSelected = settingsRepo.selectedRomwbwVersion()
            if (nowSelected != romwbwVersion) {
                Log.w(TAG, "The catalog fetch moved the selection $romwbwVersion -> " +
                    "$nowSelected; resolving its ROM rather than starting on the old one")
                startMachine()
                return@launch
            }

            // Settings are re-read rather than reused: setDiskSlot(0, ...) above
            // may have just changed them, and the snapshot this coroutine
            // started with predates that.
            loadRomAndDisks(settingsRepo.getSettings(), romBytes)
        }
    }

    private fun showDownloadProgress(title: String, fileName: String) {
        downloadTitle.text = title
        downloadFileName.text = fileName
        downloadProgressBar.progress = 0
        downloadPercent.text = "0%"
        downloadOverlay.visibility = View.VISIBLE
    }

    private fun updateDownloadProgress(percent: Int) {
        downloadProgressBar.progress = percent
        downloadPercent.text = "$percent%"
    }

    private fun hideDownloadProgress() {
        downloadOverlay.visibility = View.GONE
    }

    /**
     * Start the machine on a ROM that has already been resolved.
     *
     * [romBytes] has already been verified against the catalog's size and
     * sha256 - see DiskDownloadManager.readVerifiedRom - and arrives as bytes
     * rather than a File so nothing can swap the file between the check and the
     * load. There is no other source: this app carries no ROM, so a machine
     * either starts on catalog bytes or does not start.
     */
    private fun loadRomAndDisks(settings: EmulatorSettings, romBytes: ByteArray) {
        // The resolution ends here and not at romResolved(), so that the
        // starter-disk fetch between the two is covered by it as well: for as
        // long as this is set, a play-button tap is a no-op rather than a
        // second resolution running beside the first.
        resolvingRomFor = null

        // Apply display settings
        terminalView.customFontSize = settings.fontSize.toFloat()
        terminalView.wrapLines = settings.wrapLines
        terminalView.soundEnabled = settingsRepo.isSoundEnabled()
        terminalView.scrollbackLines = settings.scrollbackLines

        // Log current settings for debugging
        Log.i(TAG, "Settings: RomWBW=${settingsRepo.selectedRomwbwVersion()}, " +
            "ROM=${settingsRepo.selectedRomId(settingsRepo.selectedRomwbwVersion()) ?: "(catalog default)"}")
        settings.diskSlots.forEachIndexed { index, filename ->
            Log.i(TAG, "Disk slot $index: ${filename ?: "(empty)"}")
        }

        executor.execute {
            try {
                val romData = romBytes
                Log.i(TAG, "ROM loaded from the catalog download for RomWBW " +
                    "${settingsRepo.selectedRomwbwVersion()} (${romData.size} bytes)")

                if (emulator.loadRom(romData)) {
                    loadDisksAndConfigureSlices(settings)
                    emulator.completeInit()

                    // Swapping the ROM under a machine that has already run
                    // leaves the CPU's registers and RAM belonging to the
                    // previous release. The reset path is the one that is
                    // already exercised on every Reboot: it recreates the state
                    // and reloads both caches, which loadRom and loadDisk have
                    // just refreshed.
                    if (machineStartedOnce) {
                        Log.i(TAG, "Rebooting onto the newly loaded ROM")
                        emulator.reset()
                    }
                    machineStartedOnce = true

                    // Restore NVRAM from saved preferences (for boot config persistence)
                    val savedNvramSetting = settingsRepo.getSavedNvramSetting()
                    if (!savedNvramSetting.isNullOrEmpty()) {
                        emulator.setNvramSetting(savedNvramSetting)
                        Log.i(TAG, "NVRAM restored: \"$savedNvramSetting\"")
                    }

                    romLoaded = true

                    mainHandler.post {
                        terminalView.processOutput(createVersionBanner())

                        // updateStatus() repaints both the text and the colour,
                        // which is what takes the orange "ROM needed" state off
                        // the strip once a ROM has actually been resolved.
                        updateStatus()
                        startEmulation()

                        // Trigger focus if window already has focus
                        if (hasWindowFocus() && !initialFocusDone) {
                            initialFocusDone = true
                            terminalView.post {
                                terminalView.requestFocus()
                            }
                        }

                        // Send CR immediately to trigger ROM prompt display
                        // (ROM may be waiting for input before showing boot menu)
                        mainHandler.postDelayed({
                            emulator.queueInput(0x0D)
                        }, 500)
                    }
                } else {
                    mainHandler.post {
                        statusText.text = "ROM load failed"
                        Toast.makeText(this, "Failed to load ROM", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: IOException) {
                // A catalog ROM is already bytes in memory by the time it gets
                // here, so this is the disk side: an image that could not be
                // read off external storage.
                Log.e(TAG, "Could not read a disk image while starting", e)
                mainHandler.post {
                    statusText.text = "Disk read failed"
                    Toast.makeText(this, "A disk image could not be read", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startEmulation() {
        if (!running && romLoaded) {
            running = true
            emulator.start()
            mainHandler.post(runLoop)
            updateStatus()
            Log.i(TAG, "Emulation started")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && romLoaded && running && !initialFocusDone) {
            initialFocusDone = true
            terminalView.post {
                terminalView.requestFocus()
            }
        }
    }

    private fun stopEmulation() {
        running = false
        emulator.stop()
        mainHandler.removeCallbacks(runLoop)
        // Flush on stop (v1.34 platform contract); serialized on the executor
        // behind any in-flight batch, and a no-op when nothing is dirty.
        executor.execute { saveDirtyDisks() }
        updateStatus()
        Log.i(TAG, "Emulation stopped")
    }

    private fun bootEmulation() {
        stopEmulation()
        terminalView.clear()
        terminalView.recalculateSize()

        // Run reset on executor to avoid racing with an in-flight nativeRun
        executor.execute {
            saveDirtyDisks()  // Save any modified disks before reset
            emulator.reset()
            applyManifestWarningPreference()

            mainHandler.post {
                val settings = settingsRepo.getSettings()
                terminalView.customFontSize = settings.fontSize.toFloat()
                terminalView.wrapLines = settings.wrapLines
                terminalView.soundEnabled = settingsRepo.isSoundEnabled()
                terminalView.scrollbackLines = settings.scrollbackLines

                terminalView.processOutput(createVersionBanner())
                startEmulation()

                terminalView.post { terminalView.requestFocus() }
                mainHandler.postDelayed({ emulator.queueInput(0x0D) }, 500)
            }
        }
    }

    /**
     * Check and handle host file transfer state (R8/W8 utilities).
     * Called from the run loop on the executor thread.
     */
    private fun checkHostFileState() {
        when (emulator.getHostFileState()) {
            EmulatorEngine.HOST_FILE_WAITING_READ -> handleHostFileRead()
            EmulatorEngine.HOST_FILE_WRITE_READY -> handleHostFileWrite()
        }

        // Check for manifest disk write warning (fires once per session)
        // Delay slightly so the Return key from the command is consumed first
        if (emulator.checkManifestWriteWarning()) {
            mainHandler.postDelayed({ showManifestWriteWarningDialog() }, 100)
        }
    }

    /**
     * Handle R8 file read request.
     * Looks for the file in the Imports folder.
     */
    private fun handleHostFileRead() {
        // Already reduced to a single leaf component by the native side
        // (emu_host_file_open_read), so a guest path cannot name a directory
        // here. The check below is a backstop now, not the only line of
        // defence - which is what it used to be.
        val suggestedName = emulator.getHostFileReadName()
        Log.i(TAG, "R8: Looking for file: $suggestedName")

        val imports = importsDir
        if (imports == null) {
            // Reported, not silently treated as "no file": the user is about to
            // be told to put something in a folder that does not exist.
            Log.w(TAG, "R8: no Imports folder, external storage unavailable")
            emulator.hostFileCancel()
            mainHandler.post {
                Toast.makeText(this@MainActivity,
                    getString(R.string.transfer_storage_unavailable), Toast.LENGTH_SHORT).show()
            }
            return
        }

        // R8 reads only from the Imports folder.
        if (suggestedName.contains('/') || suggestedName.contains('\\') ||
            suggestedName.contains("..")) {
            Log.w(TAG, "R8: Rejecting path-like name: $suggestedName")
            emulator.hostFileCancel()
            mainHandler.post {
                Toast.makeText(this@MainActivity,
                    "R8: Use a plain filename from the Imports folder", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Exact name, then case-insensitive - the CP/M CCP uppercases the
        // command tail, so the case the user typed is gone before we see it.
        //
        // There is deliberately no "otherwise take the first file in Imports"
        // fallback any more. It turned a miss into a silent success: R8
        // NOTTHERE.TXT imported whatever else happened to be sitting in the
        // folder, under the name the guest asked for, and R8 printed its usual
        // success line - so the CP/M file was real, plausible, and somebody
        // else's contents. A name the user did type is never a request for a
        // different file. romwbw_emu docs/DOWNSTREAM_2026-08-25.md section 0
        // describes the same substitution on iOS.
        //
        // An empty name still means "no preference", which is what the older
        // bare-FCB R8 sends when the guest gave it nothing to work with, so
        // that one case keeps the first-file behaviour.
        val fileToRead = if (suggestedName.isNotEmpty()) {
            // resolveInsideDir rather than a bare File(imports, name): the same
            // helper the write side uses, so the containment is proved where
            // the access happens even though the test above has already
            // rejected anything path-shaped.
            val exact = resolveInsideDir(imports, suggestedName)
            if (exact != null && exact.exists() && exact.isFile) exact
            else imports.listFiles()?.firstOrNull {
                it.isFile && it.name.equals(suggestedName, ignoreCase = true)
            }
        } else {
            imports.listFiles()?.firstOrNull { it.isFile }
        }

        if (fileToRead != null && fileToRead.exists()) {
            try {
                val data = fileToRead.readBytes()
                Log.i(TAG, "R8: Providing file ${fileToRead.name} (${data.size} bytes)")
                // The resolved file goes down with the bytes, so R8's
                // "Reading:" line names what was opened rather than repeating
                // what was typed. absolutePath rather than name, matching the
                // CLI's realpath() and this port's own write side: Imports
                // lives under getExternalFilesDir(), which the stock Files app
                // has hidden since Android 11, so the leaf alone answers
                // "which file" only for someone who already knows where to
                // look. The core truncates it from the left with a leading
                // "..." if it will not fit R8's 255-byte buffer, which keeps
                // the informative end.
                emulator.provideHostFileData(data, fileToRead.absolutePath)
                mainHandler.post {
                    Toast.makeText(this@MainActivity,
                        "R8: Loaded ${fileToRead.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                // Throwable, not Exception, and the difference is the machine
                // hanging. readBytes() reads the whole file into one array and
                // answers a huge one with OutOfMemoryError, which is an Error:
                // a catch on Exception misses it, this handler never reaches
                // hostFileCancel(), and the guest stays parked in
                // HOST_FILE_WAITING_READ with nothing but Reboot to get it out.
                // The import paths cap what they stage (MAX_IMPORT_BYTES in
                // HostTransfer.kt), but a file staged with adb or by a file
                // manager has no cap on it, and a file that cannot be read for
                // ANY reason has to end in a cancel rather than in silence.
                Log.e(TAG, "R8: Error reading file", e)
                emulator.hostFileCancel()
                mainHandler.post {
                    Toast.makeText(this@MainActivity,
                        "R8: Error reading file", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Log.w(TAG, "R8: No file found in Imports folder")
            emulator.hostFileCancel()
            mainHandler.post {
                Toast.makeText(this@MainActivity,
                    "R8: No file in Imports folder", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Handle W8 file write completion.
     * Saves the file to the Exports folder.
     */
    private fun handleHostFileWrite() {
        val data = emulator.getHostFileWriteData()
        // The effective destination, already reduced to a leaf and joined to
        // the Exports folder by the native side (emu_host_file_open_write).
        // It is the same string W8 printed to the CP/M user, so writing
        // anywhere else would make that message a lie.
        val destination = emulator.getHostFileWriteName()

        // An empty array is a zero-byte export, not a failure: W8 on an empty
        // CP/M file has to produce an empty host file, as the CLI and Windows
        // backends do and as the browser backend started doing in v1.36. Only
        // null means there is nothing to collect.
        if (data == null) {
            Log.w(TAG, "W8: No data to write")
            emulator.hostFileWriteDone()
            return
        }

        val exports = exportsDir
        if (exports == null) {
            // Nowhere to put it. Saying so beats writing a relative path into
            // the process working directory, which is what File(null, name)
            // used to do here.
            Log.w(TAG, "W8: no Exports folder, external storage unavailable")
            mainHandler.post {
                Toast.makeText(this@MainActivity,
                    getString(R.string.transfer_storage_unavailable), Toast.LENGTH_SHORT).show()
            }
            emulator.hostFileWriteDone()
            return
        }

        // Defence in depth behind the native reduction: resolve the path and
        // insist it is still inside Exports. Kotlin's File(dir, name) has the
        // same traversal property as the iOS appendingPathComponent that cost
        // that port a user's whole Documents folder - it does not escape "..".
        // Nothing here deletes, so the worst case was never that bad, but the
        // rule is that the containment is checked where the write happens.
        //
        // The test itself now lives in resolveInsideDir (HostTransfer.kt), of
        // which this is no longer the only caller: the save-as, share and
        // delete actions on the File Transfer screen run the same one. Two
        // divergent copies of this check is precisely the failure ioscpm build
        // 52 recorded, where the reducing layer and the checking layer each
        // assumed the other had done it.
        val outputFile = resolveInsideDir(exports, destination.ifEmpty { "export.bin" })
        if (outputFile == null) {
            Log.w(TAG, "W8: Refusing a destination outside Exports: $destination")
            mainHandler.post {
                Toast.makeText(this@MainActivity,
                    "W8: Export refused (outside Exports folder)", Toast.LENGTH_SHORT).show()
            }
            emulator.hostFileWriteDone()
            return
        }

        try {
            outputFile.writeBytes(data)
            Log.i(TAG, "W8: Saved ${outputFile.name} (${data.size} bytes)")
            mainHandler.post {
                Toast.makeText(this@MainActivity,
                    "W8: Saved ${outputFile.name}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "W8: Error saving file", e)
            mainHandler.post {
                Toast.makeText(this@MainActivity,
                    "W8: Error saving file", Toast.LENGTH_SHORT).show()
            }
        }
        emulator.hostFileWriteDone()
    }

    private fun updateStatus() {
        if (running) {
            statusText.text = "Running"
            statusText.setTextColor(0xFF00FF00.toInt())
            playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            statusText.text = "Stopped"
            statusText.setTextColor(0xFF888888.toInt())
            playPauseButton.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    override fun onResume() {
        super.onResume()
        // Force layout recalculation after returning from other activities
        terminalView.recalculateSize()

        // A release change is not a disk change with extra steps: the ROM
        // changes too. Settings fetches and verifies the new release's ROM
        // before it lets the switch happen, so this is normally a re-verify and
        // a reload of what is already on the device - but it goes through
        // startMachine() rather than the disk-only path below, because the one
        // outcome that must stay impossible is 3.6.0 disks mounted under the
        // ROM that 3.5.1 was started with.
        val selectedNow = settingsRepo.selectedRomwbwVersion()
        val romIdNow = settingsRepo.selectedRomId(selectedNow)
        val releaseChanged = lastRomwbwVersion != null && selectedNow != lastRomwbwVersion
        // A ROM change within one release takes the same path. The disks stay
        // valid - they belong to the release, not to the ROM - but the bytes the
        // CPU is executing do not, and leaving them would run emu_avw while
        // Settings said emu_rcz80 with nothing to show the difference.
        val romChanged = lastRomwbwVersion != null && !releaseChanged && romIdNow != lastRomId
        if (releaseChanged || romChanged) {
            if (releaseChanged) {
                Log.i(TAG, "RomWBW release changed $lastRomwbwVersion -> $selectedNow; " +
                    "reloading the ROM as well as the disks")
            } else {
                Log.i(TAG, "ROM changed ${lastRomId ?: "(default)"} -> " +
                    "${romIdNow ?: "(default)"} for RomWBW $selectedNow; reloading it")
            }
            if (romLoaded) {
                // stopEmulation() queues a flush of anything dirty under the
                // OUTGOING mapping - loadedDiskFilenames still names those
                // disks - and saveDirtyDisks() returns immediately when
                // romLoaded is false. So the flag is cleared BEHIND that flush
                // on the same single-threaded executor rather than in front of
                // it, which is also where loadRomAndDisks sets it back to true.
                stopEmulation()
                executor.execute { romLoaded = false }
            }
            // Not gated on romLoaded. Switching release from the "ROM needed"
            // state is exactly how a user recovers from it, and leaving that
            // case to the play button would show them a stale dialog's
            // aftermath on a screen that had just been told to change release.
            cameFromSettings = false
            startMachine()
            return
        }

        if (romLoaded) {
            // Reload settings in case they changed
            val settings = settingsRepo.getSettings()
            terminalView.customFontSize = settings.fontSize.toFloat()
            terminalView.wrapLines = settings.wrapLines
            terminalView.soundEnabled = settingsRepo.isSoundEnabled()
            terminalView.scrollbackLines = settings.scrollbackLines

            // Check if disk settings changed while in Settings
            val diskSettingsChanged = lastDiskSlots.isNotEmpty() && settings.diskSlots != lastDiskSlots
            if (diskSettingsChanged) {
                Log.i(TAG, "Disk settings changed, reloading disks...")
                reloadDisksFromSettings(settings)
                Toast.makeText(this, "Disk settings updated. Press Reboot to apply.", Toast.LENGTH_SHORT).show()
            }

            // Don't auto-resume after Settings - user should manually start/reboot
            if (cameFromSettings) {
                cameFromSettings = false
                updateStatus()  // Update UI to show stopped state
            } else {
                startEmulation()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Run the save on the executor (serialized behind any in-flight
        // nativeRun batch, so no torn disk snapshot) and wait for it: the
        // process may be killed any time after onPause returns. The wait is
        // capped below the ~5s input-dispatch ANR budget; on timeout the save
        // keeps running on the executor as a best effort.
        try {
            executor.submit {
                saveNvramIfNeeded()
                saveDirtyDisks()
            }.get(4, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "onPause save failed", e)
        }
        // Save current disk slots to detect changes on resume
        lastDiskSlots = settingsRepo.getSettings().diskSlots
        stopEmulation()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEmulation()
        // Drain the executor before nativeDestroy: an in-flight batch or a
        // queued reset touching freed native state is a use-after-free.
        var drained = false
        try {
            executor.execute { saveDirtyDisks() }
            executor.shutdown()
            drained = executor.awaitTermination(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy save failed", e)
        }
        if (drained) {
            emulator.destroy()
        } else {
            // A task is still touching native state; freeing it now would be a
            // use-after-free. Leak it - the process is exiting anyway.
            Log.e(TAG, "Executor did not drain before destroy; skipping nativeDestroy")
        }
    }

    /**
     * Reload disks from settings when disk configuration changes.
     * Called from onResume when returning from Settings with changed disk slots.
     */
    private fun reloadDisksFromSettings(settings: EmulatorSettings) {
        executor.execute {
            // Save under the outgoing mapping before anything is replaced
            saveDirtyDisks()
            loadDisksAndConfigureSlices(settings, "reloaded")
            emulator.completeInit()

            val savedNvramSetting = settingsRepo.getSavedNvramSetting()
            if (!savedNvramSetting.isNullOrEmpty()) {
                emulator.setNvramSetting(savedNvramSetting)
            }

            emulator.reset()
            applyManifestWarningPreference()
            Log.i(TAG, "Disks reloaded and emulator reset")
        }
    }

    /**
     * Save NVRAM to preferences if it has changed since last save.
     * Called periodically from run loop and on pause.
     */
    private fun saveNvramIfNeeded() {
        if (!romLoaded) return

        // Check dirty flag first (set by C++ API calls)
        if (emulator.hasNvramChange()) {
            val setting = emulator.getNvramSetting()  // clears dirty flag
            settingsRepo.saveNvramSetting(setting)
            Log.i(TAG, "NVRAM saved (dirty flag): \"$setting\"")
            return
        }

        // Also check if NVRAM differs from saved (catches SYSCONF changes)
        if (emulator.isNvramInitialized()) {
            val currentSetting = emulator.getNvramSetting()
            val savedSetting = settingsRepo.getSavedNvramSetting() ?: ""
            if (currentSetting != savedSetting) {
                settingsRepo.saveNvramSetting(currentSetting)
                Log.i(TAG, "NVRAM saved (diff): \"$currentSetting\" (was \"$savedSetting\")")
            }
        }
    }

    /**
     * Save dirty disks to persistent storage.
     * Called periodically from run loop and on pause/exit.
     */
    private fun saveDirtyDisks() {
        if (!romLoaded) return

        loadedDiskFilenames.forEachIndexed { index, filename ->
            if (filename != null &&
                (emulator.isDiskDirty(index) || index in failedSaveUnits)) {
                val diskData = emulator.getDiskData(index)
                if (diskData != null) {
                    if (downloadManager.savePersistedDisk(filename, diskData)) {
                        emulator.clearDiskDirty(index)
                        failedSaveUnits.remove(index)
                        Log.i(TAG, "Disk $index saved: $filename (${diskData.size} bytes)")
                    } else {
                        // Retried at the next flush point (failedSaveUnits
                        // also survives a reboot, which clears dirty flags)
                        failedSaveUnits.add(index)
                        Log.e(TAG, "Failed to save disk $index: $filename")
                        mainHandler.post {
                            Toast.makeText(this,
                                "Failed to save disk: $filename", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
}
