package com.awohl.cpmdroid

import android.app.ProgressDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.awohl.cpmdroid.data.*
import com.awohl.cpmdroid.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var catalogRepo: DiskCatalogRepository
    private lateinit var downloadManager: DiskDownloadManager

    private var currentSettings: EmulatorSettings = EmulatorSettings()
    private var cachedCatalog: List<DiskInfo>? = null

    private var downloadGate: DownloadProgressGate? = null

    private val diskNameViews = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle window insets for edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top, bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Settings"

        settingsRepo = SettingsRepository(this)
        catalogRepo = DiskCatalogRepository()
        downloadManager = DiskDownloadManager(this)

        currentSettings = settingsRepo.getSettings()

        setupUI()
        loadSettings()
    }

    private fun setupUI() {
        // ROM display (read-only)
        binding.romNameText.text = currentSettings.romName

        // Setup disk slot views
        diskNameViews.clear()
        diskNameViews.add(binding.disk0Name)
        diskNameViews.add(binding.disk1Name)
        diskNameViews.add(binding.disk2Name)
        diskNameViews.add(binding.disk3Name)

        // Setup disk slot buttons
        setupDiskSlotButtons(0, binding.selectDisk0, binding.clearDisk0)
        setupDiskSlotButtons(1, binding.selectDisk1, binding.clearDisk1)
        setupDiskSlotButtons(2, binding.selectDisk2, binding.clearDisk2)
        setupDiskSlotButtons(3, binding.selectDisk3, binding.clearDisk3)

        // Font size
        binding.fontSizeSeekBar.progress = currentSettings.fontSize
        binding.fontSizeText.text = "${currentSettings.fontSize}pt"
        binding.fontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.fontSizeText.text = "${progress}pt"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Terminal scrollback. The seek bar steps through a fixed list of
        // sizes rather than a range, because the useful values are far apart
        // and a per-line slider would be unusable at 10000.
        val scrollbackIndex = SettingsRepository.SCROLLBACK_CHOICES
            .indexOfFirst { it >= currentSettings.scrollbackLines }
            .let { if (it < 0) SettingsRepository.SCROLLBACK_CHOICES.lastIndex else it }
        binding.scrollbackSeekBar.progress = scrollbackIndex
        binding.scrollbackText.text = scrollbackLabel(scrollbackIndex)
        binding.scrollbackSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.scrollbackText.text = scrollbackLabel(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Wrap lines checkbox
        binding.wrapLinesCheckbox.isChecked = currentSettings.wrapLines

        // Warn on manifest writes checkbox
        binding.warnManifestWritesCheckbox.isChecked = settingsRepo.isWarnManifestWritesEnabled()

        // Sound enabled checkbox
        binding.soundEnabledCheckbox.isChecked = settingsRepo.isSoundEnabled()

        // Browse catalog button
        binding.browseCatalogButton.setOnClickListener {
            showDiskCatalogDialog(slotToAssign = null)
        }

        // Clear boot config button
        binding.clearBootConfigButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Boot Config?")
                .setMessage("This will clear saved autoboot settings (NVRAM). The boot menu will be shown on next launch.")
                .setPositiveButton("Clear") { _, _ ->
                    settingsRepo.saveNvramSetting("")
                    Toast.makeText(this, "Boot config cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupDiskSlotButtons(slot: Int, selectButton: ImageButton, clearButton: ImageButton) {
        selectButton.setOnClickListener {
            showDiskCatalogDialog(slotToAssign = slot)
        }
        clearButton.setOnClickListener {
            clearDiskSlot(slot)
        }
    }

    private fun loadSettings() {
        updateDiskSlotDisplays()
    }

    private fun updateDiskSlotDisplays() {
        for (i in 0..3) {
            val filename = currentSettings.diskSlots.getOrNull(i)
            diskNameViews[i].text = filename ?: "(empty)"
        }
    }

    private fun clearDiskSlot(slot: Int) {
        val newSlots = currentSettings.diskSlots.toMutableList()
        newSlots[slot] = null
        currentSettings = currentSettings.copy(diskSlots = newSlots)
        settingsRepo.setDiskSlot(slot, null)
        updateDiskSlotDisplays()
    }

    private fun showDiskCatalogDialog(slotToAssign: Int?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_disk_catalog, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.diskRecyclerView)
        val loadingProgress = dialogView.findViewById<ProgressBar>(R.id.loadingProgress)
        val errorText = dialogView.findViewById<TextView>(R.id.errorText)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (slotToAssign != null) "Select Disk for Slot $slotToAssign" else "Disk Catalog")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        recyclerView.layoutManager = LinearLayoutManager(this)
        loadingProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val catalog = cachedCatalog ?: run {
                val result = catalogRepo.fetchCatalog()
                result.getOrNull()?.also { cachedCatalog = it }
            }

            loadingProgress.visibility = View.GONE

            if (catalog != null) {
                val downloadedDisks = downloadManager.getDownloadedDisks().toSet()
                recyclerView.adapter = DiskCatalogAdapter(catalog, downloadedDisks) { diskInfo ->
                    handleDiskSelection(diskInfo, slotToAssign, dialog)
                }
            } else {
                errorText.visibility = View.VISIBLE
                errorText.text = "Failed to load disk catalog. Check your internet connection."
            }
        }
    }

    private fun handleDiskSelection(diskInfo: DiskInfo, slotToAssign: Int?, dialog: AlertDialog) {
        if (downloadManager.isDiskDownloaded(diskInfo.filename)) {
            if (slotToAssign != null) {
                assignDiskToSlot(slotToAssign, diskInfo.filename)
                dialog.dismiss()
                Toast.makeText(this, "${diskInfo.name} assigned to Slot $slotToAssign", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "${diskInfo.name} is already downloaded", Toast.LENGTH_SHORT).show()
            }
        } else {
            showDownloadConfirmation(diskInfo, slotToAssign, dialog)
        }
    }

    private fun showDownloadConfirmation(diskInfo: DiskInfo, slotToAssign: Int?, parentDialog: AlertDialog) {
        val sizeStr = formatDiskSize(diskInfo.size)
        AlertDialog.Builder(this)
            .setTitle("Download ${diskInfo.name}?")
            .setMessage("Size: $sizeStr\n\n${diskInfo.description}")
            .setPositiveButton("Download") { _, _ ->
                downloadDisk(diskInfo, slotToAssign, parentDialog)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * The download and its dialog now die together, which is the deliberate
     * divergence from the Windows sibling.
     *
     * z80cpmw let the transfer finish after its Settings dialog closed - its
     * DiskCatalog is owned by MainWindow and outlives every dialog, so there was
     * something for the completion to land in. Here there is not: this Activity
     * has no android:configChanges, so a rotation destroys it; the manager is a
     * per-Activity field; and the only scopes in this app are lifecycleScope and
     * withContext. An application-scoped CoroutineScope inside DiskDownloadManager
     * was rejected because the manager holds an Activity as its Context and would
     * pin it for the whole transfer, and WorkManager was rejected as a new
     * dependency for one screen. So the loop is made cancellable instead, and
     * rotating mid-download stops it visibly rather than spending 49MB of metered
     * data on a file whose slot assignment is thrown away on arrival.
     */
    private fun downloadDisk(diskInfo: DiskInfo, slotToAssign: Int?, parentDialog: AlertDialog) {
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(this).apply {
            setTitle("Downloading ${diskInfo.name}")
            setMessage("0%")
            isIndeterminate = false
            max = 100
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            show()
        }

        // A previous transfer's dialog cannot still be up - this one is modal
        // and not cancelable - but its gate may still be holding a reference to
        // it, so it is closed rather than dropped.
        downloadGate?.close()
        val gate = DownloadProgressGate()
        gate.attach(progressDialog)
        downloadGate = gate

        lifecycleScope.launch {
            var lastPercent = -1
            val result = downloadManager.downloadDisk(diskInfo) { bytesRead, totalBytes ->
                val percent = if (totalBytes > 0) (bytesRead * 100 / totalBytes).toInt() else 0
                // One post per whole percent, not one per 8KB block. The
                // callback fires about 6200 times for the 49MB combo image, and
                // each one used to become a main-thread message that redrew the
                // dialog with the number it was already showing. The throttle
                // lives here rather than in DiskDownloadManager so the manager's
                // contract stays "once per block" for a caller that wants bytes.
                if (percent != lastPercent) {
                    lastPercent = percent
                    gate.postProgress(percent, bytesRead)
                }
            }

            gate.close()
            if (downloadGate === gate) downloadGate = null

            result.fold(
                onSuccess = {
                    Toast.makeText(this@SettingsActivity,
                        "Downloaded ${diskInfo.name}", Toast.LENGTH_SHORT).show()
                    if (slotToAssign != null) {
                        assignDiskToSlot(slotToAssign, diskInfo.filename)
                        parentDialog.dismiss()
                    }
                    // Refresh the catalog display
                    cachedCatalog = null
                },
                onFailure = { e ->
                    Toast.makeText(this@SettingsActivity,
                        "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    /**
     * A slot change is written through the moment it is made, not left for
     * onPause.
     *
     * saveSettings rewrites all four disk_slot_N keys out of the snapshot this
     * Activity loaded in onCreate, and the slots are the one part of the
     * settings that something else changes while this screen exists -
     * MainActivity's first-launch download calls setDiskSlot(0, ...) straight
     * on the repository. Deferring the write meant the later pause quietly put
     * the stale set back. It also lets MainActivity.onResume, which compares
     * diskSlots against what it saw when it paused, notice the change at all.
     */
    private fun assignDiskToSlot(slot: Int, filename: String) {
        val newSlots = currentSettings.diskSlots.toMutableList()
        newSlots[slot] = filename
        currentSettings = currentSettings.copy(diskSlots = newSlots)
        settingsRepo.setDiskSlot(slot, filename)
        updateDiskSlotDisplays()
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    override fun onDestroy() {
        // The transfer itself goes with the Activity: it runs in lifecycleScope
        // and DiskDownloadManager's read loop now checks for that. The dialog
        // does not. It is setCancelable(false), so the user cannot take it away
        // either, and without this the framework tears the window down
        // underneath and logs a WindowLeaked. Closing the gate is the half that
        // matters - after it, a progress post already on its way finds nothing
        // to write to.
        downloadGate?.close()
        downloadGate = null
        super.onDestroy()
    }

    private fun scrollbackLabel(index: Int): String {
        val lines = SettingsRepository.SCROLLBACK_CHOICES.getOrElse(index) { 1000 }
        return if (lines == 0) "Off" else lines.toString()
    }

    private fun saveSettings() {
        currentSettings = currentSettings.copy(
            // Re-read rather than trusting the snapshot taken in onCreate.
            // SettingsRepository.saveSettings rewrites all four disk_slot_N
            // keys, so without this the pause puts this screen's idea of the
            // slots back over anything that changed them since - see
            // assignDiskToSlot for who does that.
            diskSlots = settingsRepo.getSettings().diskSlots,
            fontSize = binding.fontSizeSeekBar.progress,
            wrapLines = binding.wrapLinesCheckbox.isChecked,
            scrollbackLines = SettingsRepository.SCROLLBACK_CHOICES
                .getOrElse(binding.scrollbackSeekBar.progress) { 1000 }
        )
        settingsRepo.saveSettings(currentSettings)
        // Save warn manifest writes setting separately
        settingsRepo.setWarnManifestWritesEnabled(binding.warnManifestWritesCheckbox.isChecked)
        // Save sound enabled setting separately
        settingsRepo.setSoundEnabled(binding.soundEnabledCheckbox.isChecked)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

/**
 * Everything a download's progress callback is allowed to reach.
 *
 * The callback runs on an OkHttp read thread for the whole of a transfer, so
 * whatever it captures is held - and reached from that thread - for as long as
 * the transfer lasts. It captures one of these and nothing else. The old
 * callback named runOnUiThread, which is an Activity method, so a destroyed
 * SettingsActivity stayed reachable from the network thread for the length of a
 * 49MB image; a volatile flag checked inside the body would have stopped the
 * writes without releasing the Activity, which is why this holds its own
 * Handler instead. formatDiskSize is a top-level function in
 * com.awohl.cpmdroid.data, so naming it captures nothing either.
 *
 * The dialog reference is what close() clears, and clearing it is the same act
 * as closing the gate: after that, a post already queued finds nothing to write
 * to.
 */
@Suppress("DEPRECATION")
private class DownloadProgressGate {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var dialog: ProgressDialog? = null

    fun attach(target: ProgressDialog) {
        dialog = target
    }

    fun postProgress(percent: Int, bytesRead: Long) {
        handler.post {
            val target = dialog ?: return@post
            target.progress = percent
            target.setMessage("$percent% (${formatDiskSize(bytesRead)})")
        }
    }

    /**
     * Take the dialog down and stop anything further reaching it. Called from
     * the completion and from onDestroy, both on the main thread, which is
     * where dismiss() has to happen.
     */
    fun close() {
        val target = dialog
        dialog = null
        handler.removeCallbacksAndMessages(null)
        if (target != null && target.isShowing) target.dismiss()
    }
}
