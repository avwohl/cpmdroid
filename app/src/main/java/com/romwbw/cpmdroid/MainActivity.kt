package com.romwbw.cpmdroid

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.romwbw.cpmdroid.data.DiskCatalogRepository
import com.romwbw.cpmdroid.data.DiskDownloadManager
import com.romwbw.cpmdroid.data.SettingsRepository
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val FRAME_DELAY_MS = 16L // ~60fps
    }

    private lateinit var terminalView: TerminalView
    private lateinit var playPauseButton: ImageButton
    private lateinit var resetButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var statusText: TextView

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var downloadManager: DiskDownloadManager

    private val emulator = EmulatorEngine()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var romLoaded = false
    private var running = false

    private val runLoop: Runnable = object : Runnable {
        override fun run() {
            if (running && romLoaded) {
                val self = this
                executor.execute {
                    if (emulator.runBatch()) {
                        mainHandler.postDelayed(self, FRAME_DELAY_MS)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsRepo = SettingsRepository(this)
        downloadManager = DiskDownloadManager(this)

        terminalView = findViewById(R.id.terminalView)
        playPauseButton = findViewById(R.id.playPauseButton)
        resetButton = findViewById(R.id.resetButton)
        settingsButton = findViewById(R.id.settingsButton)
        statusText = findViewById(R.id.statusText)

        setupEmulator()
        setupToolbar()

        checkFirstLaunchAndLoad()
    }

    private fun setupEmulator() {
        emulator.init()
        emulator.setOutputListener { data ->
            mainHandler.post {
                terminalView.processOutput(data)
            }
        }
        // Set up terminal input - characters typed go directly to emulator
        terminalView.setInputListener { ch ->
            emulator.queueInput(ch)
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

        resetButton.setOnClickListener {
            resetEmulation()
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun checkFirstLaunchAndLoad() {
        if (!settingsRepo.isFirstLaunchDone()) {
            // First launch - try to download default disk
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
                    // Show progress dialog
                    @Suppress("DEPRECATION")
                    val progressDialog = ProgressDialog(this@MainActivity).apply {
                        setTitle("Downloading ${defaultDisk.name}")
                        setMessage("First-time setup...")
                        isIndeterminate = false
                        max = 100
                        setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                        setCancelable(false)
                        show()
                    }

                    val result = downloadManager.downloadDisk(defaultDisk) { bytesRead, totalBytes ->
                        val percent = if (totalBytes > 0) (bytesRead * 100 / totalBytes).toInt() else 0
                        runOnUiThread {
                            progressDialog.progress = percent
                            progressDialog.setMessage("$percent%")
                        }
                    }

                    progressDialog.dismiss()

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

    private fun loadRomAndDisks() {
        val settings = settingsRepo.getSettings()

        // Apply font size
        terminalView.customFontSize = settings.fontSize.toFloat()

        // Log current settings for debugging
        Log.i(TAG, "Settings: ROM=${settings.romName}, bootString='${settings.bootString}'")
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
                                    diskCount++
                                } else {
                                    Log.e(TAG, "Disk $index failed to load: $filename")
                                }
                            } else {
                                Log.w(TAG, "Disk $index file not found: $filename")
                            }
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
                    romLoaded = true

                    mainHandler.post {
                        updateStatus()
                        startEmulation()

                        // Send CR immediately to trigger ROM prompt display
                        // (ROM may be waiting for input before showing boot menu)
                        mainHandler.postDelayed({
                            emulator.queueInput(0x0D)
                        }, 500)

                        // Send boot string after a delay if configured
                        val bootString = settings.bootString
                        if (bootString.isNotEmpty()) {
                            mainHandler.postDelayed({
                                emulator.queueInputString(bootString)
                                emulator.queueInput(0x0D)
                            }, 2000)
                        }
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

    private fun stopEmulation() {
        running = false
        emulator.stop()
        mainHandler.removeCallbacks(runLoop)
        updateStatus()
        Log.i(TAG, "Emulation stopped")
    }

    private fun resetEmulation() {
        stopEmulation()
        terminalView.clear()
        emulator.reset()

        // Reload settings in case they changed
        val settings = settingsRepo.getSettings()
        terminalView.customFontSize = settings.fontSize.toFloat()

        startEmulation()

        // Re-send boot string if configured
        val bootString = settings.bootString
        if (bootString.isNotEmpty()) {
            mainHandler.postDelayed({
                emulator.queueInputString(bootString)
                emulator.queueInput(0x0D)
            }, 2000)
        }
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
        if (romLoaded) {
            // Reload settings in case they changed
            val settings = settingsRepo.getSettings()
            terminalView.customFontSize = settings.fontSize.toFloat()
            startEmulation()
        }
    }

    override fun onPause() {
        super.onPause()
        stopEmulation()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEmulation()
        executor.shutdown()
        emulator.destroy()
    }
}
