package com.awohl.cpmdroid.data

data class DiskInfo(
    val filename: String,
    val name: String,
    val description: String,
    val size: Long,
    val license: String,
    val sha256: String,
    val defaultSlot: Int? = null
)

fun formatDiskSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
