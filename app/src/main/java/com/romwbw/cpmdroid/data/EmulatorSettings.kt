package com.romwbw.cpmdroid.data

data class EmulatorSettings(
    val romName: String = "emu_avw.rom",
    val diskSlots: List<String?> = listOf(null, null, null, null),
    val bootString: String = "",
    val fontSize: Int = 14
)
