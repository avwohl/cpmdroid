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
        // ROM: chosen from the selected release's catalog roms[].
        updateRomDisplay()
        binding.changeRomButton.setOnClickListener {
            showRomDialog()
        }

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

        is CatalogFailure.VersionNotOffered ->
            "${error.message}\n\nAnything already downloaded for it is untouched."

        // The ROM failures reach here too, because the picker fetches a ROM
        // and the same dialog reports it. They are separated from the catalog
        // ones because none of them is a connection problem the user can fix by
        // moving nearer a router - the catalog answered in every case.
        is RomFailure.NoRomPublished ->
            "${error.message}\n\nThere is nothing to boot it with, so this release " +
                "cannot be selected."

        is RomFailure.WrongRelease ->
            "${error.message}\n\nThe ROM was not downloaded. This is a problem with the " +
                "published catalog, not with this device."

        is RomFailure.CouldNotFetch ->
            "${error.message}\n\nThe release was not switched, and whatever is already on " +
                "this device is untouched."

        is RomFailure.DidNotVerify ->
            "${error.message}\n\nIt was fetched again and still did not match, so it was " +
                "not used."

        // Every other ROM failure, so that none of them can be described as a
        // disk-catalog failure by the arm below. RomFailure is sealed, so this
        // covers whatever is added to it later rather than letting it fall
        // through silently to the wrong sentence.
        is RomFailure -> "${error.message}\n\nNothing was changed."

        else -> "Failed to load the disk catalog: ${error?.message ?: "unknown error"}"
    }

    /**
     * The ROM row: which of the release's published ROMs this machine boots.
     *
     * Drawn from what is already known rather than from a fetch, because this
     * runs in onCreate and on every return to the screen. Before any catalog has
     * been read the honest answer is the stored pick, or the release's default -
     * which is what the note says rather than pretending to name a file.
     */
    private fun updateRomDisplay() {
        val version = settingsRepo.selectedRomwbwVersion()
        val pickedId = settingsRepo.selectedRomId(version)
        val roms = cachedSelection?.takeIf { it.selected.romwbwVersion == version }?.catalog?.roms
        val rom = roms?.let { selectRom(it, pickedId) }

        binding.romNameText.text = when {
            rom != null -> rom.name
            pickedId != null -> pickedId
            else -> "Default for this release"
        }

        val claim = settingsRepo.romClaim(version)
        binding.romNote.text = when {
            rom != null && claim?.romId == rom.id ->
                "${rom.filename}, downloaded and verified against the catalog on every start."
            rom != null ->
                "${rom.filename}. Not on this device yet; CPMDroid fetches it before it starts."
            claim != null ->
                "${claim.filename}, downloaded and verified against the catalog on every start."
            else ->
                "Chosen from what RomWBW $version publishes. Tap Change to see the list."
        }
    }

    /**
     * Offer the ROMs the selected release publishes.
     *
     * A release's catalog is needed for this - the index does not carry roms[] -
     * so it goes through the same loadSelectedCatalog() the disk list uses and
     * reuses its cached answer when there is one.
     */
    private fun showRomDialog() {
        val version = settingsRepo.selectedRomwbwVersion()
        val cached = cachedSelection?.takeIf { it.selected.romwbwVersion == version }
        if (cached != null) {
            showRomChoices(version, cached.catalog.roms)
            return
        }

        val progress = AlertDialog.Builder(this)
            .setTitle("ROM")
            .setMessage("Reading the RomWBW $version catalog...")
            .setCancelable(false)
            .create()
        progress.show()

        lifecycleScope.launch {
            // finally, for the same reason showRomwbwVersionDialog() has one:
            // leaving this screen mid-fetch cancels the coroutine, and a
            // setCancelable(false) dialog the user cannot dismiss would be torn
            // down by the framework with a WindowLeaked in the log.
            val result = try {
                loadSelectedCatalog(downloadManager, settingsRepo).onSuccess {
                    cachedSelection = it
                    knownVersions = it.runnable
                }
            } finally {
                if (progress.isShowing) progress.dismiss()
            }
            result.fold(
                onSuccess = { selection ->
                    updateRomwbwVersionDisplay()
                    updateRomDisplay()
                    showRomChoices(selection.selected.romwbwVersion, selection.catalog.roms)
                },
                onFailure = {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("ROM")
                        .setMessage(catalogErrorMessage(it))
                        .setPositiveButton("OK", null)
                        .show()
                }
            )
        }
    }

    /**
     * The list itself.
     *
     * What is stored is the catalog ID, never the filename shown beside it. The
     * two are the same type and look alike, and seeding a filename into the
     * field that holds an ID is precisely the bug that shipped in the sibling
     * port and corrupted the preference on OK.
     */
    private fun showRomChoices(version: String, roms: List<RomInfo>) {
        if (roms.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("ROM")
                .setMessage("The RomWBW $version catalog publishes no ROM, so there is nothing to choose.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val current = selectRom(roms, settingsRepo.selectedRomId(version))
        var chosen = roms.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        val labels = roms.map { rom ->
            val marks = buildList {
                if (rom.isDefault) add("default")
                if (settingsRepo.romClaim(version)?.romId == rom.id) add("downloaded")
            }
            if (marks.isEmpty()) rom.name else "${rom.name}\n${marks.joinToString(" - ")}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("ROM for RomWBW $version")
            .setSingleChoiceItems(labels, chosen) { _, which -> chosen = which }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Select") { _, _ ->
                val rom = roms.getOrNull(chosen) ?: return@setPositiveButton
                if (rom.id == current?.id) return@setPositiveButton
                // The ID. Never rom.filename - see the KDoc above.
                settingsRepo.setSelectedRomId(version, rom.id)
                updateRomDisplay()
                // Fetched here, not left for the next launch to discover.
                // Recording the pick and stopping would send the user back to a
                // machine that starts on the old ROM until it is restarted, and
                // then meets a dialog asking permission for the download their
                // choice already implied - the same "ordinary consequence
                // presented as a fault" the release switch was fixed for.
                downloadRomThenApply(version, rom)
            }
            .show()
    }

    /**
     * The RomWBW release row above the disk slots.
     *
     * The note underneath used to warn that a release the bundled ROM was not
     * built for would boot with `*** WARNING: HBIOS/CBIOS Version Mismatch ***`,
     * because this app had no way to get any ROM but the one in its own
     * package. There is no package ROM now: every release fetches its own and
     * verifies it, so the note says whether this release's ROM is here, and the
     * mismatch is no longer a state the app can be left in.
     */
    private fun updateRomwbwVersionDisplay() {
        val version = settingsRepo.selectedRomwbwVersion()
        val entry = knownVersions.firstOrNull { it.romwbwVersion == version }
        binding.romwbwVersionText.text = entry?.label ?: "RomWBW $version"

        val lines = mutableListOf<String>()
        if (entry?.isPreview == true) {
            lines.add("PREVIEW: published as not yet recommended.")
        }
        lines.add(
            if (romLooksPresent(version)) {
                "Boots ${settingsRepo.romClaim(version)?.filename}, downloaded from " +
                    "the catalog and verified on every start. Disk slots and boot config " +
                    "are kept separately for each release."
            } else {
                "This release needs its own ROM from the catalog, and it is not on " +
                    "this device. CPMDroid will offer to fetch it before it starts."
            }
        )
        binding.romwbwVersionNote.text = lines.joinToString("\n")
    }

    /**
     * Does this release look as though its ROM is here?
     *
     * A stat of a file whose size the catalog already told us, not a hash. It
     * decides a label, and the 512 KB read and SHA-256 that would make it
     * authoritative do not belong on a thread that is drawing a screen -
     * RomwbwSupport reads 264 bytes rather than 524288 for exactly that reason.
     *
     * Nothing acts on it. Selecting a release goes through
     * downloadRomThenSwitch, which verifies for real and re-fetches if it has
     * to, and MainActivity verifies again from the bytes it is about to load.
     * So the worst this can be is a row that says "ROM downloaded" about a file
     * that turns out to be corrupt, and the very next step catches it.
     */
    private fun romLooksPresent(romwbwVersion: String): Boolean {
        val claim = settingsRepo.romClaim(romwbwVersion) ?: return false
        return downloadManager.romFileLooksPresent(claim)
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
        val labels = runnable.map { romwbwChoiceLabel(it) }.toTypedArray()

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
                when {
                    // A ROM that looks present is still verified before the
                    // switch, and re-fetched if it does not hold up - but that
                    // costs nothing when it does, and needs no network, so it
                    // is not worth a dialog asking permission to download.
                    romLooksPresent(entry.romwbwVersion) -> downloadRomThenSwitch(entry)

                    else -> confirmRomDownload(entry, current)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * One row of the picker: the release, its published status, and where its
     * ROM comes from.
     *
     * `status` is free text copied from the version metadata, not an enum, so
     * an unrecognised value is displayed rather than refused - only "preview"
     * is singled out, because that one is a claim about whether the release is
     * ready and the user is entitled to see it before choosing.
     *
     * The ROM half of the row used to read "no ROM in this build", which was
     * true and is not any more: every release's ROM is one download away, and
     * none of them is in the package.
     */
    private fun romwbwChoiceLabel(entry: RomwbwVersion): CharSequence {
        val marks = mutableListOf<String>()
        if (entry.isPreview) {
            marks.add("PREVIEW - not yet recommended")
        } else if (entry.status.isNotEmpty()) {
            marks.add(entry.status)
        }
        marks.add(
            if (romLooksPresent(entry.romwbwVersion)) "ROM downloaded" else "ROM will be downloaded"
        )
        return entry.label + "\n" + marks.joinToString(" - ")
    }

    /**
     * Switching to a release whose ROM is not here yet: fetch it first, and
     * only switch if it arrives.
     *
     * The order is the point. Switching first and fetching afterwards would
     * leave the app pointed at a release it cannot start on if the fetch failed,
     * and the recovery from that is a dialog on the next launch that the user
     * never needed to see. Booting the new release's disks against the bundled
     * ROM instead is the one thing that is never offered: that is the pairing
     * that makes CP/M print *** WARNING: HBIOS/CBIOS Version Mismatch ***, and
     * removing it is what fetching the ROM from the catalog is for.
     */
    private fun confirmRomDownload(entry: RomwbwVersion, current: String) {
        AlertDialog.Builder(this)
            .setTitle("Switch to ${entry.label}?")
            .setMessage(
                "${entry.label} has its own ROM, which has not been downloaded yet. " +
                    "It is about half a megabyte and is checked against the catalog's " +
                    "hash before it is ever used.\n\n" +
                    "Nothing is deleted. Your RomWBW $current disks, slots and boot config " +
                    "stay where they are and come back when you switch back."
            )
            .setPositiveButton("Download and switch") { _, _ -> downloadRomThenSwitch(entry) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Get the release's ROM in hand - verifying what is already here, fetching
     * what is not - and switch only if that works.
     *
     * Titled "Preparing" rather than "Downloading" because it is often neither:
     * a ROM already on the device that still verifies needs no index, no
     * catalog and no transfer, and this returns almost at once. When there IS a
     * transfer it reports it the way a disk download does - half a megabyte is
     * quick, and on a bad connection it is the thing between the user and a
     * machine that boots, so it must not look like a hang.
     *
     * The switch happens in the success arm and nowhere else: this is the "gate
     * on a completion callback" half of the contract, not a timer.
     */
    /**
     * Fetch a newly picked ROM now, and put the choice back if it cannot be got.
     *
     * The pick is written before the fetch so that fetchRomForRelease() resolves
     * the ID that was just chosen; it is reverted on failure, because a stored
     * pick whose bytes never arrived would leave the machine unable to start on
     * a release that was working a moment ago.
     */
    private fun downloadRomThenApply(version: String, rom: RomInfo) {
        val previousId = settingsRepo.romClaim(version)?.romId

        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(this).apply {
            setTitle("Preparing ${rom.name}")
            setMessage("Checking its ROM...")
            isIndeterminate = false
            max = 100
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            show()
        }

        downloadGate?.close()
        val gate = DownloadProgressGate()
        gate.attach(progressDialog)
        downloadGate = gate

        lifecycleScope.launch {
            var lastPercent = -1
            val result = fetchRomForRelease(
                downloadManager, settingsRepo, version
            ) { bytesRead, totalBytes ->
                val percent = if (totalBytes > 0) (bytesRead * 100 / totalBytes).toInt() else 0
                if (percent != lastPercent) {
                    lastPercent = percent
                    gate.postProgress(percent, bytesRead)
                }
            }

            gate.close()
            if (downloadGate === gate) downloadGate = null

            result.fold(
                onSuccess = {
                    updateRomDisplay()
                    updateRomwbwVersionDisplay()
                    Toast.makeText(
                        this@SettingsActivity,
                        "${rom.name} is ready. The machine will use it when it next starts.",
                        Toast.LENGTH_LONG
                    ).show()
                },
                onFailure = { error ->
                    settingsRepo.setSelectedRomId(version, previousId)
                    updateRomDisplay()
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("${rom.name} was not used")
                        .setMessage(catalogErrorMessage(error))
                        .setPositiveButton("OK", null)
                        .show()
                }
            )
        }
    }

    private fun downloadRomThenSwitch(entry: RomwbwVersion) {
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(this).apply {
            setTitle("Preparing ${entry.label}")
            setMessage("Checking its ROM...")
            isIndeterminate = false
            max = 100
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            show()
        }

        downloadGate?.close()
        val gate = DownloadProgressGate()
        gate.attach(progressDialog)
        downloadGate = gate

        lifecycleScope.launch {
            var lastPercent = -1
            val result = fetchRomForRelease(
                downloadManager, settingsRepo, entry.romwbwVersion
            ) { bytesRead, totalBytes ->
                val percent = if (totalBytes > 0) (bytesRead * 100 / totalBytes).toInt() else 0
                if (percent != lastPercent) {
                    lastPercent = percent
                    gate.postProgress(percent, bytesRead)
                }
            }

            gate.close()
            if (downloadGate === gate) downloadGate = null

            result.fold(
                onSuccess = {
                    // lastPercent moves only when bytes actually crossed the
                    // network, so this says which of the two things happened
                    // rather than claiming a download that never ran.
                    val what = if (lastPercent >= 0) "Downloaded" else "Verified"
                    Toast.makeText(
                        this@SettingsActivity,
                        "$what the ${entry.label} ROM", Toast.LENGTH_SHORT
                    ).show()
                    applyRomwbwVersion(entry)
                },
                // Not switched. The release stays where it was, which is the
                // one that still has a ROM behind it.
                onFailure = { showRomwbwProblem(it) }
            )
        }
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
        // Only ever reached with a ROM behind it: a release whose downloaded
        // ROM verified just now, or one whose ROM was fetched and verified by
        // downloadRomThenSwitch immediately above.
        //
        settingsRepo.setSelectedRomwbwVersion(entry.romwbwVersion)
        cachedSelection = null
        currentSettings = settingsRepo.getSettings()
        updateDiskSlotDisplays()
        updateRomwbwVersionDisplay()
        updateRomDisplay()

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
