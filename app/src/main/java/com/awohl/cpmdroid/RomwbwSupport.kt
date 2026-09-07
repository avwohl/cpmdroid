package com.awohl.cpmdroid

/**
 * What the emulator core can run.
 *
 * The answer comes from the core rather than from a constant in Kotlin. The core
 * is compiled in place out of a sibling checkout, so this binary can be built
 * against a newer or an older romwbw_emu than the version list was written
 * against; a compile-time answer would be wrong in one of those two directions
 * with nothing to notice it - the pin an earlier release deleted was wrong in
 * exactly that way, for two days, in every port at once.
 *
 * There is deliberately no "what is the bundled ROM" question here any more.
 * This app ships no ROM: every ROM is fetched from the catalog for the selected
 * release and checked against the size and sha256 that catalog publishes, so the
 * only thing worth asking the core is which releases it will accept at all.
 *
 * The EmulatorEngine held here is only a handle for two natives that read no
 * emulator state: nothing in this file initialises, loads or runs anything.
 * MainActivity's own engine is a different instance and is unaffected, because
 * there is no per-instance native state to share.
 */
object RomwbwSupport {

    private val engine by lazy { EmulatorEngine() }

    /** True when this build's core will load a ROM declaring these HBIOS bytes. */
    fun isRunnable(verByte: Int, updByte: Int): Boolean =
        engine.romwbwReleaseSupported(verByte, updByte)

    /** The releases this core has been checked against, e.g. "3.5.1, 3.6.0". */
    fun supportedList(): String = engine.romwbwSupportedList()
}
