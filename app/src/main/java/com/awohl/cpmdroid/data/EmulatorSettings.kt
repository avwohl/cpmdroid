package com.awohl.cpmdroid.data

data class EmulatorSettings(
    val romName: String = "emu_avw.rom",
    val diskSlots: List<String?> = listOf(null, null, null, null),
    val fontSize: Int = 14,
    val wrapLines: Boolean = false
)
