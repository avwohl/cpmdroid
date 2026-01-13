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
