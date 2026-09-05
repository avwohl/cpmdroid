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
    private lateinit var downloadManager: DiskDownloadManager

    private var currentSettings: EmulatorSettings = EmulatorSettings()

    /**
     * The last successful index+catalog fetch, or null.
     *
     * It carries the release it was fetched for, and every read below checks
     * that against the selected one. A plain list would survive a version
     * switch and show 3.5.1's twenty disks while 3.6.0 was selected, which does
     * not fail visibly - the names simply do not exist on the selected release's
     * tag, so every download 404s.
     */
    private var cachedSelection: CatalogSelection? = null

    /**
     * The index entries this build can run, from the last fetch of either kind.
     *
     * Kept separately from [cachedSelection] and deliberately NOT cleared when
     * the selected release changes: this is index data, the same list whichever
     * release is selected, and it is what lets the row above the disk slots say
     * "PREVIEW" for a release the user picked earlier without a fetch of its
     * own. Empty before the first fetch, which is why every reader falls back to
     * the bare version string.
     */
    private var knownVersions: List<RomwbwVersion> = emptyList()

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
        downloadManager = DiskDownloadManager(this)

        currentSettings = settingsRepo.getSettings()

        setupUI()
        loadSettings()
    }

    private fun setupUI() {
        // ROM display (read-only)
        binding.romNameText.text = currentSettings.romName

        // RomWBW release
        updateRomwbwVersionDisplay()
        binding.changeRomwbwButton.setOnClickListener {
            showRomwbwVersionDialog()
        }

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
            val cached = cachedSelection?.takeIf {
                it.selected.romwbwVersion == settingsRepo.selectedRomwbwVersion()
            }
            val result = if (cached != null) {
                Result.success(cached)
            } else {
                loadSelectedCatalog(downloadManager, settingsRepo).onSuccess {
                    cachedSelection = it
                    knownVersions = it.runnable
                }
            }

            loadingProgress.visibility = View.GONE

            result.fold(
                onSuccess = { selection ->
                    val downloadedDisks = downloadManager.getDownloadedDisks().toSet()
                    recyclerView.adapter =
                        DiskCatalogAdapter(selection.catalog.disks, downloadedDisks) { diskInfo ->
                            handleDiskSelection(diskInfo, slotToAssign, dialog)
                        }
                    updateRomwbwVersionDisplay()
                },
                onFailure = { error ->
                    errorText.visibility = View.VISIBLE
                    errorText.text = catalogErrorMessage(error)
                }
            )
        }
    }

    /**
     * What to say about a fetch that did not produce a catalog.
     *
     * One string used to cover all of this - "Failed to load disk catalog.
     * Check your internet connection." - and with a second round trip added it
     * would now be wrong about two of the four failures. The index answering
     * while a release's catalog does not is not a connection problem, and a
     * core that can run nothing the catalog publishes is not one either: no
     * amount of reconnecting fixes a build that needs replacing. CatalogFailure
     * already carries the specific reason; this only adds what to do about it.
     */
    private fun catalogErrorMessage(error: Throwable?): String = when (error) {
        is CatalogFailure.IndexUnavailable ->
            "${error.message}\n\nThat is the one address this app has. " +
                "Check your connection and try again."

        is CatalogFailure.NoRunnableVersion ->
            "${error.message}\n\nThis needs a newer CPMDroid, not a better connection."

        is CatalogFailure.CatalogUnavailable ->
            "${error.message}\n\nThe index itself was reached, so this is that one " +
                "release's catalog file."

        is CatalogFailure.CatalogCorrupt ->
            "${error.message}\n\nIt was not used. Nothing already downloaded is affected."

        is CatalogFailure.CatalogEmpty -> error.message ?: "The catalog lists no disks."

        else -> "Failed to load the disk catalog: ${error?.message ?: "unknown error"}"
    }

    /**
     * The RomWBW release row above the disk slots.
     *
     * The note underneath is the honest half. This build boots the ROM inside
     * its own package and has no path that loads one from storage, so selecting
     * a release the bundled ROM was not built for gets you that release's disks
     * and a guest that prints
     * `*** WARNING: HBIOS/CBIOS Version Mismatch ***` when it boots them. Saying
     * so here costs one line; not saying it costs a bug report that reads
     * "CP/M prints a warning and behaves oddly".
     */
    private fun updateRomwbwVersionDisplay() {
        val version = settingsRepo.selectedRomwbwVersion()
        val entry = knownVersions.firstOrNull { it.romwbwVersion == version }
        binding.romwbwVersionText.text = entry?.label ?: "RomWBW $version"

        val bundled = RomwbwSupport.bundledRomRelease(this, currentSettings.romName)
        val lines = mutableListOf<String>()
        if (entry?.isPreview == true) {
            lines.add("PREVIEW: published as not yet recommended.")
        }
        lines.add(
            when {
                bundled == null ->
                    "The bundled ROM's RomWBW release could not be read."
                bundled == version ->
                    "Matches the bundled ROM. Disk slots and boot config are kept " +
                        "separately for each release."
                else ->
                    "The bundled ROM is RomWBW $bundled, so disks for $version will boot " +
                        "with a HBIOS/CBIOS version mismatch warning."
            }
        )
        binding.romwbwVersionNote.text = lines.joinToString("\n")
    }

    /**
     * Offer the releases this build can actually run.
     *
     * Only the index is fetched, not a catalog: the picker needs labels,
     * statuses and HBIOS bytes, all of which are in the 2.5 KB index, and
     * pulling a release's whole catalog to draw a list of two rows would be a
     * download the user did not ask for.
     */
    private fun showRomwbwVersionDialog() {
        val progress = AlertDialog.Builder(this)
            .setTitle("RomWBW Release")
            .setMessage("Reading the catalog index...")
            .setCancelable(false)
            .create()
        progress.show()

        lifecycleScope.launch {
            // finally, because leaving this screen mid-fetch cancels the
            // coroutine and nothing after the fetch would run - and this dialog
            // is setCancelable(false), so the user cannot take it away either.
            // Without the dismiss the framework tears the window down
            // underneath and logs a WindowLeaked, which is the same trap
            // DownloadProgressGate was written for.
            val result = try {
                downloadManager.fetchIndex()
            } finally {
                if (progress.isShowing) progress.dismiss()
            }
            result.fold(
                onSuccess = { index ->
                    // Ask the core which of them this binary will load a ROM
                    // for. Offering a release it refuses would put a row in
                    // front of the user whose only outcome is a ROM load that
                    // fails after they have downloaded 49 MB of disks for it.
                    val runnable = runnableRomwbwVersions(index) { verByte, updByte ->
                        RomwbwSupport.isRunnable(verByte, updByte)
                    }
                    if (runnable.isEmpty()) {
                        showRomwbwProblem(
                            CatalogFailure.NoRunnableVersion(RomwbwSupport.supportedList())
                        )
                    } else {
                        knownVersions = runnable
                        updateRomwbwVersionDisplay()
                        showRomwbwChoices(runnable)
                    }
                },
                onFailure = { showRomwbwProblem(it) }
            )
        }
    }

    private fun showRomwbwProblem(error: Throwable) {
        AlertDialog.Builder(this)
            .setTitle("RomWBW Release")
            .setMessage(catalogErrorMessage(error))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showRomwbwChoices(runnable: List<RomwbwVersion>) {
        val current = settingsRepo.selectedRomwbwVersion()
        val bundled = RomwbwSupport.bundledRomRelease(this, currentSettings.romName)
        val labels = runnable.map { romwbwChoiceLabel(it, bundled) }.toTypedArray()

        // The stored release may not be in the list at all - it can be
        // withdrawn upstream, or stop being runnable when the core changes - and
        // -1 is what setSingleChoiceItems wants for "nothing checked".
        var chosen = runnable.indexOfFirst { it.romwbwVersion == current }

        AlertDialog.Builder(this)
            .setTitle("RomWBW Release")
            .setSingleChoiceItems(labels, chosen) { _, which -> chosen = which }
            .setPositiveButton("Select") { _, _ ->
                val entry = runnable.getOrNull(chosen) ?: return@setPositiveButton
                if (entry.romwbwVersion == current) return@setPositiveButton
                if (entry.romwbwVersion == bundled) {
                    applyRomwbwVersion(entry)
                } else {
                    confirmRomwbwMismatch(entry, bundled, current)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * One row of the picker: the release, its published status, and whether
     * this build has a ROM for it.
     *
     * `status` is free text copied from the version metadata, not an enum, so
     * an unrecognised value is displayed rather than refused - only "preview"
     * is singled out, because that one is a claim about whether the release is
     * ready and the user is entitled to see it before choosing.
     */
    private fun romwbwChoiceLabel(entry: RomwbwVersion, bundled: String?): CharSequence {
        val marks = mutableListOf<String>()
        if (entry.isPreview) {
            marks.add("PREVIEW - not yet recommended")
        } else if (entry.status.isNotEmpty()) {
            marks.add(entry.status)
        }
        marks.add(
            if (entry.romwbwVersion == bundled) "matches the bundled ROM"
            else "no ROM in this build"
        )
        return entry.label + "\n" + marks.joinToString(" - ")
    }

    private fun confirmRomwbwMismatch(
        entry: RomwbwVersion,
        bundled: String?,
        current: String
    ) {
        val romLine = if (bundled == null) {
            "This build's bundled ROM does not declare a readable RomWBW release."
        } else {
            "CPMDroid boots the ROM in its own package, which is RomWBW $bundled."
        }
        AlertDialog.Builder(this)
            .setTitle("Switch to ${entry.label}?")
            .setMessage(
                "$romLine\n\n" +
                    "Disks for ${entry.label} can be downloaded and assigned, but booting " +
                    "them makes CP/M print *** WARNING: HBIOS/CBIOS Version Mismatch *** " +
                    "and behave unpredictably.\n\n" +
                    "Nothing is deleted. Your RomWBW $current disks, slots and boot config " +
                    "stay where they are and come back when you switch back."
            )
            .setPositiveButton("Switch") { _, _ -> applyRomwbwVersion(entry) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Switch release: repoint, then re-read.
     *
     * Re-reading is not cosmetic. currentSettings still holds the previous
     * release's four slot names, and saveSettings() writes that list back on
     * every pause - into the keys of whichever release is selected then. It
     * re-reads the slots itself for exactly this reason, but this screen would
     * still be showing the wrong four names until it was left and reopened.
     *
     * Nothing is copied across and nothing is deleted. An empty slot list for a
     * release the user has never used is the correct state, not a loss, and
     * saying so is better than silently booting nothing.
     */
    private fun applyRomwbwVersion(entry: RomwbwVersion) {
        settingsRepo.setSelectedRomwbwVersion(entry.romwbwVersion)
        cachedSelection = null
        currentSettings = settingsRepo.getSettings()
        updateDiskSlotDisplays()
        updateRomwbwVersionDisplay()

        val assigned = currentSettings.diskSlots.count { it != null }
        val message = if (assigned == 0) {
            "${entry.label} selected. No disks are assigned for it yet - " +
                "use Browse Disk Catalog."
        } else {
            "${entry.label} selected. $assigned disk slot(s) restored."
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
                    cachedSelection = null
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
