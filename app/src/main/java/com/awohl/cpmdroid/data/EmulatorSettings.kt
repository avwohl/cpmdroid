package com.awohl.cpmdroid.data

// No romName. The ROM is not a property of the app any more: it belongs to the
// selected RomWBW release, is named by that release's catalog, and is chosen by
// catalog ID through SettingsRepository.selectedRomId(). A field here would be a
// second place for that answer to live and a filename where an ID belongs.
data class EmulatorSettings(
    val diskSlots: List<String?> = listOf(null, null, null, null),
    val fontSize: Int = 14,
    val wrapLines: Boolean = false,
    // Lines of terminal history kept above the live screen; 0 turns
    // scrollback off. z80cpmw calls the same setting "Terminal scrollback"
    // and defaults to the same 1000.
    val scrollbackLines: Int = 1000
)
