package com.awohl.cpmdroid

import android.util.Log
import com.awohl.cpmdroid.data.CatalogFailure
import com.awohl.cpmdroid.data.DiskCatalog
import com.awohl.cpmdroid.data.DiskDownloadManager
import com.awohl.cpmdroid.data.RomFailure
import com.awohl.cpmdroid.data.RomwbwVersion
import com.awohl.cpmdroid.data.SettingsRepository
import com.awohl.cpmdroid.data.claim
import com.awohl.cpmdroid.data.runnableRomwbwVersions
import com.awohl.cpmdroid.data.selectRom
import com.awohl.cpmdroid.data.selectRomwbwVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val runnable = fetchRunnableVersions(downloadManager).getOrElse { return Result.failure(it) }

    // Null until something has settled on a release, and then the index's own
    // `default: true` entry wins. That is what carries a fresh install - and an
    // install upgrading from a build whose release was implied by the ROM in its
    // own APK - onto whatever the catalog currently marks current, instead of
    // onto whichever release this app happened to ship a ROM for.
    val preferred = settingsRepo.preferredRomwbwVersion()
    val previous = settingsRepo.selectedRomwbwVersion()
    val selected = selectRomwbwVersion(runnable, preferred)
        ?: return Result.failure(CatalogFailure.NoRunnableVersion(RomwbwSupport.supportedList()))

    // The resolved answer is written back, not just worked around. Disk slots
    // and NVRAM are keyed on the selected release, so leaving the pointer where
    // it was would show one release's catalog against another release's slots.
    // Writing it back loses nothing: the old release's slots stay under their
    // own keys and come back untouched if it is selected again.
    //
    // Writing it here is what makes the catalog-following a ONE-TIME move rather
    // than a standing one: after this, the release is settled and a RomWBW
    // version published later will not shift a machine off the disks and NVRAM
    // it has been using. New ROMs and disks within this release still arrive on
    // every fetch, which is the part that needed no app release.
    if (selected.romwbwVersion != previous) {
        if (preferred != null) {
            Log.w(TAG, "Selected RomWBW $preferred is not on offer; falling back to " +
                "${selected.romwbwVersion}. Its slots and NVRAM are kept.")
        } else {
            Log.i(TAG, "Following the catalog: RomWBW ${selected.romwbwVersion} " +
                "(was $previous). Each release's slots and NVRAM are kept separately.")
        }
        settingsRepo.setSelectedRomwbwVersion(selected.romwbwVersion)
    }

    val catalog = fetchAndNote(downloadManager, settingsRepo, selected)
        .getOrElse { return Result.failure(it) }

    return Result.success(CatalogSelection(runnable, selected, catalog))
}

/**
 * The index entries this build's core will load a ROM for, or why there are
 * none.
 *
 * Shared by every path that walks the index, so "ask the core rather than
 * assume" cannot be true in one place and forgotten in another. A build whose
 * core has been checked against 3.5.1 and 3.6.0 offers both; one built against
 * an older core offers whatever that core knows, and neither is knowable from
 * this side.
 */
private suspend fun fetchRunnableVersions(
    downloadManager: DiskDownloadManager
): Result<List<RomwbwVersion>> {
    val index = downloadManager.fetchIndex().getOrElse { return Result.failure(it) }
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
    return Result.success(runnable)
}

private suspend fun fetchAndNote(
    downloadManager: DiskDownloadManager,
    settingsRepo: SettingsRepository,
    entry: RomwbwVersion
): Result<DiskCatalog> {
    val catalog = downloadManager.fetchCatalog(entry).getOrElse { return Result.failure(it) }

    // Recorded and logged; nothing is deleted on a change. See
    // SettingsRepository.noteCatalogGeneration for why that is deliberate.
    if (settingsRepo.noteCatalogGeneration(entry.romwbwVersion, catalog.generation)) {
        Log.i(TAG, "RomWBW ${entry.romwbwVersion} catalog generation is now " +
            "${catalog.generation}; downloaded images are kept and re-verified per file.")
    }
    return Result.success(catalog)
}

/**
 * index -> that release's catalog -> its ROM, downloaded if it is not already
 * here, and verified either way.
 *
 * Named rather than selected: Settings fetches the ROM for a release BEFORE
 * switching to it, so the release being asked about is deliberately not the one
 * `selectedRomwbwVersion()` returns. That ordering is the point - a switch that
 * completed before the ROM arrived would leave the machine set to a release it
 * cannot start on, and the honest recovery from that is a dialog the user never
 * needed to see.
 *
 * On success the catalog's `filename`, `size` and `sha256` are recorded for the
 * release, so a later launch can verify the same file with no network at all.
 * They are written only after the bytes verified, never before.
 */
suspend fun fetchRomForRelease(
    downloadManager: DiskDownloadManager,
    settingsRepo: SettingsRepository,
    romwbwVersion: String,
    onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
): Result<ByteArray> {
    // Already here and still what the catalog described: no index, no catalog,
    // no transfer. This is what makes switching back to a release whose ROM was
    // fetched last month work on a train - and it is a verify, not a shortcut
    // past one: the size and sha256 recorded when it was fetched are checked
    // against the bytes right now.
    //
    // Only when it is also the ROM that is wanted. A pick made on the Settings
    // screen is a preference, and a verified file already on disk would answer
    // this question before the catalog was ever opened - so choosing emu_rcz80
    // would be stored, ignored on every launch, and look like the setting did
    // nothing. A claim with no recorded id predates this and is accepted when
    // nothing has been picked.
    val wantedRomId = settingsRepo.selectedRomId(romwbwVersion)
    val stored = settingsRepo.romClaim(romwbwVersion)
    if (stored != null && (wantedRomId == null || stored.romId == wantedRomId)) {
        withContext(Dispatchers.IO) { downloadManager.readVerifiedRom(romwbwVersion, stored) }
            .onSuccess { return Result.success(it) }
            .onFailure { Log.i(TAG, "RomWBW $romwbwVersion ROM needs fetching: ${it.message}") }
    } else if (stored != null) {
        Log.i(TAG, "RomWBW $romwbwVersion ROM on disk is ${stored.romId ?: "(unrecorded)"}, " +
            "but $wantedRomId is selected; fetching it")
    }

    val runnable = fetchRunnableVersions(downloadManager).getOrElse { return Result.failure(it) }
    val entry = runnable.firstOrNull { it.romwbwVersion == romwbwVersion }
        ?: return Result.failure(CatalogFailure.VersionNotOffered(romwbwVersion))

    val catalog = fetchAndNote(downloadManager, settingsRepo, entry)
        .getOrElse { return Result.failure(it) }

    // The ID the user picked, else the entry flagged default, else the first -
    // never by position, never by looking for "emu_avw", and an empty array is a
    // release with no ROM rather than a document to reject.
    val rom = selectRom(catalog.roms, wantedRomId)
        ?: return Result.failure(RomFailure.NoRomPublished(romwbwVersion))

    Log.i(TAG, "RomWBW $romwbwVersion ROM is ${rom.name} (${rom.id}, ${rom.filename}, " +
        "${rom.size} bytes), chosen from ${catalog.roms.size} published")

    val bytes = downloadManager.fetchAndReadRom(
        romwbwVersion, rom, entry.verByte, entry.updByte, onProgress
    ).getOrElse { return Result.failure(it) }

    settingsRepo.setRomClaim(romwbwVersion, rom.claim())
    return Result.success(bytes)
}
