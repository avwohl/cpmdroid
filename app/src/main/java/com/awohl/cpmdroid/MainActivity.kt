package com.awohl.cpmdroid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.awohl.cpmdroid.data.DiskCatalogRepository
import com.awohl.cpmdroid.data.DiskDownloadManager
import com.awohl.cpmdroid.data.EmulatorSettings
import com.awohl.cpmdroid.data.SettingsRepository
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val FRAME_DELAY_MS = 16L // ~60fps
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
    private var lastNvramSaveCount = 0
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

                    // Periodically save NVRAM (~5 seconds = 300 iterations at 16ms)
                    if (runLoopCount - lastNvramSaveCount >= 300) {
                        lastNvramSaveCount = runLoopCount
                        saveNvramIfNeeded()
                    }

                    if (shouldContinue) {
                        mainHandler.postDelayed(self, FRAME_DELAY_MS)
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
                // Keyboard was hidden
                lastMeasuredKeyboardHeight = 0
                Log.i(TAG, "Keyboard hidden (measured height: $keyboardHeight)")
            }
        }

        settingsRepo = SettingsRepository(this)
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

    private fun setupEmulator() {
        emulator.init()
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
        }

        // Tab sends tab character (0x09)
        tabButton.setOnClickListener {
            controlifyMode = false
            updateCtrlButtonState()
            emulator.queueInput(0x09)
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

    private fun setupToolbar() {
        playPauseButton.setOnClickListener {
            if (running) {
                stopEmulation()
            } else {
                startEmulation()
            }
        }

        bootButton.setOnClickListener {
            bootEmulation()
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

    private fun showManifestWriteWarningDialog() {
        AlertDialog.Builder(this)
            .setTitle("Disk Write Warning")
            .setMessage("""
                You are writing to a downloaded disk image.

                This disk may be replaced when the app updates, and your changes could be lost.

                To preserve your data, copy files to a different disk or export them using W8.
            """.trimIndent())
            .setPositiveButton("OK", null)
            .setNeutralButton("Don't warn again") { _, _ ->
                settingsRepo.setManifestWriteWarningSuppressed(true)
                // Also suppress for current session
                for (i in 0 until 16) {
                    emulator.setDiskWarningSuppressed(i, true)
                }
            }
            .show()
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
            val catalogResult = DiskCatalogRepository().fetchCatalog()
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
                    // Load disks from external storage
                    var diskCount = 0
                    settings.diskSlots.forEachIndexed { index, filename ->
                        if (filename != null) {
                            val diskData = downloadManager.loadDiskData(filename)
                            if (diskData != null) {
                                if (emulator.loadDisk(index, diskData)) {
                                    Log.i(TAG, "Disk $index loaded: $filename (${diskData.size} bytes)")
                                    // Mark as manifest disk (downloaded from catalog, may be overwritten on update)
                                    emulator.setDiskIsManifest(index, true)
                                    diskCount++
                                } else {
                                    Log.e(TAG, "Disk $index failed to load: $filename")
                                }
                            } else {
                                Log.w(TAG, "Disk $index file not found: $filename")
                            }
                        }
                    }

                    // Apply manifest write warning suppression from user preferences
                    if (settingsRepo.isManifestWriteWarningSuppressed()) {
                        for (i in 0 until 16) {
                            emulator.setDiskWarningSuppressed(i, true)
                        }
                    }

                    // Set slice count for drive letter assignment (like CLI does)
                    // 1 disk = 8 slices, 2 disks = 4 each, 3+ = 2 each
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

                    emulator.completeInit()

                    // Restore NVRAM from saved preferences (for boot config persistence)
                    val savedNvramSetting = settingsRepo.getSavedNvramSetting()
                    if (!savedNvramSetting.isNullOrEmpty()) {
                        emulator.setNvramSetting(savedNvramSetting)
                        Log.i(TAG, "NVRAM restored: \"$savedNvramSetting\"")
                    }

                    romLoaded = true

                    mainHandler.post {
                        // Display version string on terminal before ROM output
                        val versionBanner = "CPMDroid v${getVersionString()} (${BuildConfig.BUILD_TIME})\r\n"
                        terminalView.processOutput(versionBanner.toByteArray())

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
        updateStatus()
        Log.i(TAG, "Emulation stopped")
    }

    private fun bootEmulation() {
        stopEmulation()
        terminalView.clear()
        terminalView.recalculateSize()
        emulator.reset()

        // Reload settings in case they changed
        val settings = settingsRepo.getSettings()
        terminalView.customFontSize = settings.fontSize.toFloat()
        terminalView.wrapLines = settings.wrapLines

        // Display version string on terminal before ROM output
        val versionBanner = "CPMDroid v${getVersionString()} (${BuildConfig.BUILD_TIME})\r\n"
        terminalView.processOutput(versionBanner.toByteArray())

        startEmulation()

        // Focus terminal after reboot
        terminalView.post {
            terminalView.requestFocus()
        }

        // Send CR to trigger ROM prompt display (same as initial boot)
        mainHandler.postDelayed({
            emulator.queueInput(0x0D)
        }, 500)
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
        if (emulator.checkManifestWriteWarning()) {
            mainHandler.post { showManifestWriteWarningDialog() }
        }
    }

    /**
     * Handle R8 file read request.
     * Looks for the file in the Imports folder.
     */
    private fun handleHostFileRead() {
        val suggestedName = emulator.getHostFileReadName()
        Log.i(TAG, "R8: Looking for file: $suggestedName")

        // First try exact name, then try first file in Imports folder
        val targetFile = if (suggestedName.isNotEmpty()) {
            val exact = File(importsDir, suggestedName)
            if (exact.exists() && exact.isFile) exact else null
        } else null

        val fileToRead = targetFile ?: importsDir.listFiles()?.firstOrNull { it.isFile }

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
        val filename = emulator.getHostFileWriteName()

        if (data == null || data.isEmpty()) {
            Log.w(TAG, "W8: No data to write")
            emulator.hostFileWriteDone()
            return
        }

        val outputFile = File(exportsDir, filename)
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
        saveNvramIfNeeded()
        // Save current disk slots to detect changes on resume
        lastDiskSlots = settingsRepo.getSettings().diskSlots
        stopEmulation()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEmulation()
        executor.shutdown()
        emulator.destroy()
    }

    /**
     * Reload disks from settings when disk configuration changes.
     * Called from onResume when returning from Settings with changed disk slots.
     */
    private fun reloadDisksFromSettings(settings: EmulatorSettings) {
        executor.execute {
            var diskCount = 0
            settings.diskSlots.forEachIndexed { index, filename ->
                if (filename != null) {
                    val diskData = downloadManager.loadDiskData(filename)
                    if (diskData != null) {
                        if (emulator.loadDisk(index, diskData)) {
                            Log.i(TAG, "Disk $index reloaded: $filename (${diskData.size} bytes)")
                            emulator.setDiskIsManifest(index, true)
                            diskCount++
                        } else {
                            Log.e(TAG, "Disk $index failed to reload: $filename")
                        }
                    } else {
                        Log.w(TAG, "Disk $index file not found: $filename")
                    }
                }
            }

            // Update slice counts
            val autoSlices = when {
                diskCount <= 1 -> 8
                diskCount == 2 -> 4
                else -> 2
            }
            for (i in 0 until 16) {
                if (emulator.isDiskLoaded(i)) {
                    emulator.setDiskSliceCount(i, autoSlices)
                }
            }

            // Reinitialize to update disk tables
            emulator.completeInit()

            // Restore NVRAM setting
            val savedNvramSetting = settingsRepo.getSavedNvramSetting()
            if (!savedNvramSetting.isNullOrEmpty()) {
                emulator.setNvramSetting(savedNvramSetting)
            }

            // Reset to apply changes
            emulator.reset()
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
}
