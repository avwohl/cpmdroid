package com.awohl.cpmdroid

import android.content.Context
import android.content.Intent
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
import com.awohl.cpmdroid.data.SettingsRepository
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
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

    // Imports/Exports folders for R8/W8 file transfer
    private val importsDir: File by lazy {
        File(getExternalFilesDir(null), "Imports").apply { mkdirs() }
    }
    private val exportsDir: File by lazy {
        File(getExternalFilesDir(null), "Exports").apply { mkdirs() }
    }

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
        settingsRepo.migrateIfNeeded()
        downloadManager = DiskDownloadManager(this)

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
        emulator.setHostExportsDir(exportsDir.absolutePath)
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
            emulator.queueInput(0x1B)
            wakeRunLoop()
        }

        // Tab sends tab character (0x09)
        tabButton.setOnClickListener {
            controlifyMode = false
            updateCtrlButtonState()
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
            if (running) {
                stopEmulation()
            } else {
                startEmulation()
            }
        }

        bootButton.setOnClickListener {
            showRestartConfirmDialog()
        }

        settingsButton.setOnClickListener {
            if (running) {
                Toast.makeText(this, "Stop emulator before changing settings", Toast.LENGTH_SHORT).show()
            } else {
                cameFromSettings = true
                startActivity(Intent(this, SettingsActivity::class.java))
            }
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

    private fun createVersionBanner(): ByteArray {
        return "CPMDroid v${getVersionString()} (${BuildConfig.BUILD_TIME})\r\n".toByteArray()
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
        val buildTime = BuildConfig.BUILD_TIME

        AlertDialog.Builder(this)
            .setTitle("About CPMDroid")
            .setMessage("""
                CPMDroid v$version
                Built: $buildTime

                A Z80 CP/M emulator for Android using RomWBW HBIOS.

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

    private fun checkFirstLaunchAndLoad() {
        val settings = settingsRepo.getSettings()
        val slot0Disk = settings.diskSlots.getOrNull(0)
        val needsDownload = !settingsRepo.isFirstLaunchDone() ||
            (slot0Disk != null && !downloadManager.isDiskDownloaded(slot0Disk)) ||
            (slot0Disk == null && downloadManager.getDownloadedDisks().isEmpty())

        Log.i(TAG, "checkFirstLaunchAndLoad: firstLaunchDone=${settingsRepo.isFirstLaunchDone()}, " +
            "slot0=$slot0Disk, needsDownload=$needsDownload")

        if (needsDownload) {
            // First launch or disk files missing - try to download default disk
            downloadDefaultDisk()
        } else {
            loadRomAndDisks()
        }
    }

    private fun downloadDefaultDisk() {
        statusText.text = "First launch setup..."
        Log.i(TAG, "First launch - fetching disk catalog...")

        lifecycleScope.launch {
            val catalogResult = downloadManager.fetchCatalog()
            val catalog = catalogResult.getOrNull()

            if (catalog != null) {
                Log.i(TAG, "Catalog fetched: ${catalog.size} disks")
                catalog.forEach { disk ->
                    Log.d(TAG, "  - ${disk.filename}: defaultSlot=${disk.defaultSlot}")
                }

                // Find disk with defaultSlot = 0
                val defaultDisk = catalog.find { it.defaultSlot == 0 }

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
                Log.e(TAG, "Could not fetch disk catalog: ${catalogResult.exceptionOrNull()?.message}")
                settingsRepo.markFirstLaunchDone()
            }

            loadRomAndDisks()
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

    private fun loadRomAndDisks() {
        val settings = settingsRepo.getSettings()

        // Apply display settings
        terminalView.customFontSize = settings.fontSize.toFloat()
        terminalView.wrapLines = settings.wrapLines
        terminalView.soundEnabled = settingsRepo.isSoundEnabled()
        terminalView.scrollbackLines = settings.scrollbackLines

        // Log current settings for debugging
        Log.i(TAG, "Settings: ROM=${settings.romName}")
        settings.diskSlots.forEachIndexed { index, filename ->
            Log.i(TAG, "Disk slot $index: ${filename ?: "(empty)"}")
        }

        executor.execute {
            try {
                // Load ROM from assets
                val romName = settings.romName
                val romData = assets.open(romName).use { it.readBytes() }
                Log.i(TAG, "ROM loaded from assets: $romName (${romData.size} bytes)")

                if (emulator.loadRom(romData)) {
                    loadDisksAndConfigureSlices(settings)
                    emulator.completeInit()

                    // Restore NVRAM from saved preferences (for boot config persistence)
                    val savedNvramSetting = settingsRepo.getSavedNvramSetting()
                    if (!savedNvramSetting.isNullOrEmpty()) {
                        emulator.setNvramSetting(savedNvramSetting)
                        Log.i(TAG, "NVRAM restored: \"$savedNvramSetting\"")
                    }

                    romLoaded = true

                    mainHandler.post {
                        terminalView.processOutput(createVersionBanner())

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
                Log.e(TAG, "ROM not found in assets: ${settings.romName}", e)
                mainHandler.post {
                    statusText.text = "ROM not found"
                    Toast.makeText(this, "Place ${settings.romName} in assets", Toast.LENGTH_LONG).show()
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
            val exact = File(importsDir, suggestedName)
            if (exact.exists() && exact.isFile) exact
            else importsDir.listFiles()?.firstOrNull {
                it.isFile && it.name.equals(suggestedName, ignoreCase = true)
            }
        } else {
            importsDir.listFiles()?.firstOrNull { it.isFile }
        }

        if (fileToRead != null && fileToRead.exists()) {
            try {
                val data = fileToRead.readBytes()
                Log.i(TAG, "R8: Providing file ${fileToRead.name} (${data.size} bytes)")
                emulator.provideHostFileData(data)
                mainHandler.post {
                    Toast.makeText(this@MainActivity,
                        "R8: Loaded ${fileToRead.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
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

        // Defence in depth behind the native reduction: resolve the path and
        // insist it is still inside Exports. Kotlin's File(dir, name) has the
        // same traversal property as the iOS appendingPathComponent that cost
        // that port a user's whole Documents folder - it does not escape "..".
        // Nothing here deletes, so the worst case was never that bad, but the
        // rule is that the containment is checked where the write happens.
        val outputFile = if (destination.contains('/') || destination.contains('\\')) {
            File(destination)
        } else {
            File(exportsDir, destination.ifEmpty { "export.bin" })
        }
        val exportsRoot = exportsDir.canonicalPath
        val resolved = try {
            outputFile.canonicalPath
        } catch (e: Exception) {
            Log.e(TAG, "W8: Cannot resolve destination", e)
            emulator.hostFileWriteDone()
            return
        }
        if (resolved != exportsRoot && !resolved.startsWith(exportsRoot + File.separator)) {
            Log.w(TAG, "W8: Refusing a destination outside Exports: $resolved")
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
