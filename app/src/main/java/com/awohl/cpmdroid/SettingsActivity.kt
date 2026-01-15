package com.awohl.cpmdroid

import android.app.ProgressDialog
import android.os.Bundle
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

        // Boot string
        binding.bootStringEdit.setText(currentSettings.bootString)

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
        val sizeStr = formatSize(diskInfo.size)
        AlertDialog.Builder(this)
            .setTitle("Download ${diskInfo.name}?")
            .setMessage("Size: $sizeStr\n\n${diskInfo.description}")
            .setPositiveButton("Download") { _, _ ->
                downloadDisk(diskInfo, slotToAssign, parentDialog)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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

        lifecycleScope.launch {
            val result = downloadManager.downloadDisk(diskInfo) { bytesRead, totalBytes ->
                val percent = if (totalBytes > 0) (bytesRead * 100 / totalBytes).toInt() else 0
                runOnUiThread {
                    progressDialog.progress = percent
                    progressDialog.setMessage("$percent% (${formatSize(bytesRead)})")
                }
            }

            progressDialog.dismiss()

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

    private fun assignDiskToSlot(slot: Int, filename: String) {
        val newSlots = currentSettings.diskSlots.toMutableList()
        newSlots[slot] = filename
        currentSettings = currentSettings.copy(diskSlots = newSlots)
        updateDiskSlotDisplays()
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    private fun saveSettings() {
        currentSettings = currentSettings.copy(
            bootString = binding.bootStringEdit.text.toString(),
            fontSize = binding.fontSizeSeekBar.progress
        )
        settingsRepo.saveSettings(currentSettings)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
