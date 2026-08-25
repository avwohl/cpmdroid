package com.awohl.cpmdroid.data

data class EmulatorSettings(
    val romName: String = "emu_avw.rom",
    val diskSlots: List<String?> = listOf(null, null, null, null),
    val fontSize: Int = 14,
    val wrapLines: Boolean = false,
    // Lines of terminal history kept above the live screen; 0 turns
    // scrollback off. z80cpmw calls the same setting "Terminal scrollback"
    // and defaults to the same 1000.
    val scrollbackLines: Int = 1000
)
