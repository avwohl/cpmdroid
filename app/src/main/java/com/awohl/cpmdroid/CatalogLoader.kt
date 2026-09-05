package com.awohl.cpmdroid

import android.util.Log
import com.awohl.cpmdroid.data.CatalogFailure
import com.awohl.cpmdroid.data.DiskCatalog
import com.awohl.cpmdroid.data.DiskDownloadManager
import com.awohl.cpmdroid.data.RomwbwVersion
import com.awohl.cpmdroid.data.SettingsRepository
import com.awohl.cpmdroid.data.runnableRomwbwVersions
import com.awohl.cpmdroid.data.selectRomwbwVersion

private const val TAG = "CatalogLoader"

/**
 * A finished catalog fetch: which releases were on offer, which one was used,
 * and its disks.
 *
 * [runnable] is carried alongside the catalog because the version picker needs
 * it and it was already paid for - the index fetch that produced the catalog
 * produced the list too, and asking for it again would be a second round trip
 * for a document that has not changed.
 */
data class CatalogSelection(
    val runnable: List<RomwbwVersion>,
    val selected: RomwbwVersion,
    val catalog: DiskCatalog
)

/**
 * index -> the releases this core can run -> the selected one's catalog.
 *
 * The whole Part B fetch path in one place, so that MainActivity's first-launch
 * download and the Settings catalog dialog cannot drift into asking two
 * different questions. There is no tag interpolation anywhere in it: the index
 * URL is compiled in, the catalog URL comes from the index, and the asset base
 * comes from the catalog.
 *
 * Every failure is a [CatalogFailure], and they are distinct on purpose - an
 * unreachable index, a core that can run nothing published, and an unreachable
 * or unverifiable catalog are three different things to tell a user, and until
 * this release Settings said "check your internet connection" for all of them.
 */
suspend fun loadSelectedCatalog(
    downloadManager: DiskDownloadManager,
    settingsRepo: SettingsRepository
): Result<CatalogSelection> {
    val index = downloadManager.fetchIndex().getOrElse { return Result.failure(it) }

    // Ask the core, do not assume. A build whose core has been checked against
    // 3.5.1 and 3.6.0 offers both; one built against an older core offers
    // whatever that core knows, and neither is knowable from this side.
    val runnable = runnableRomwbwVersions(index) { verByte, updByte ->
        RomwbwSupport.isRunnable(verByte, updByte)
    }
    if (runnable.isEmpty()) {
        // Not an empty list to shrug at. It means romwbw_disks publishes no
        // RomWBW release this binary can boot, which no amount of retrying or
        // reconnecting will change, and which nothing else in the app would
        // ever say out loud.
        Log.e(TAG, "Index lists ${index.size} release(s), none runnable by this core " +
            "(core supports ${RomwbwSupport.supportedList()})")
        return Result.failure(CatalogFailure.NoRunnableVersion(RomwbwSupport.supportedList()))
    }

    val stored = settingsRepo.selectedRomwbwVersion()
    val selected = selectRomwbwVersion(runnable, stored)
        ?: return Result.failure(CatalogFailure.NoRunnableVersion(RomwbwSupport.supportedList()))

    // A stored selection that is no longer on offer is written back, not just
    // worked around. Disk slots and NVRAM are keyed on the selected release, so
    // leaving the pointer at a release that cannot be fetched would show one
    // release's catalog against another release's slots. Writing it back loses
    // nothing: the old release's slots stay under their own keys and come back
    // untouched if it reappears.
    if (selected.romwbwVersion != stored) {
        Log.w(TAG, "Selected RomWBW $stored is not on offer; falling back to " +
            "${selected.romwbwVersion}. Its slots and NVRAM are kept.")
        settingsRepo.setSelectedRomwbwVersion(selected.romwbwVersion)
    }

    val catalog = downloadManager.fetchCatalog(selected).getOrElse { return Result.failure(it) }

    // Recorded and logged; nothing is deleted on a change. See
    // SettingsRepository.noteCatalogGeneration for why that is deliberate.
    if (settingsRepo.noteCatalogGeneration(selected.romwbwVersion, catalog.generation)) {
        Log.i(TAG, "RomWBW ${selected.romwbwVersion} catalog generation is now " +
            "${catalog.generation}; downloaded images are kept and re-verified per file.")
    }

    return Result.success(CatalogSelection(runnable, selected, catalog))
}
