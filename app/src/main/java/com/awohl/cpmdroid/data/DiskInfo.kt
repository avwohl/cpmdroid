package com.awohl.cpmdroid.data

/**
 * One `disks[]` entry of a v0 catalog document.
 *
 * [id] is the stable identity - "hd1k_combo" - and [filename] is where the
 * bytes are, which is now version-qualified ("hd1k_combo-v0-3.5.1.img"). They
 * were the same string before v0 and are not any more, which is exactly why
 * CATALOG_SCHEMA 6.1 says to key on the id and never to parse the filename: the
 * same disk has a different filename under every RomWBW release, and two
 * releases' copies of it sit in the same directory.
 *
 * [downloadUrl] is the catalog's `base_url` concatenated with [filename], built
 * once at parse time. It is carried per entry rather than kept as repository
 * state because MainActivity and SettingsActivity each build their own
 * DiskDownloadManager and so their own DiskCatalogRepository, so a base held in
 * one of them would be invisible to the other - and because a DiskInfo that
 * knows its own URL cannot be paired with the wrong base after a version
 * switch. base_url ends in "/" by contract,
 * so nothing inserts a separator here; that fixup is what iOS had to remove.
 */
data class DiskInfo(
    val id: String,
    val filename: String,
    val name: String,
    val description: String,
    val size: Long,
    val license: String,
    val sha256: String,
    val downloadUrl: String,
    val defaultSlot: Int? = null
)

fun formatDiskSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
