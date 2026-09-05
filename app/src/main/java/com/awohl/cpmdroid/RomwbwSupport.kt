package com.awohl.cpmdroid

import android.content.Context
import android.util.Log
import java.io.InputStream

/**
 * What the emulator core can run, and what the bundled ROM is.
 *
 * Both answers come from the core rather than from a constant in Kotlin. The
 * core is compiled in place out of a sibling checkout, so this binary can be
 * built against a newer or an older romwbw_emu than the one the version list
 * was written against, and the ROM in assets/ can be replaced without anybody
 * remembering to edit a string. A compile-time answer would be wrong in one of
 * those two directions with nothing to notice it - the pin this release
 * deletes was wrong in exactly that way, for two days, in every port at once.
 *
 * The EmulatorEngine held here is only a handle for three natives that read no
 * emulator state: nothing in this file initialises, loads or runs anything.
 * MainActivity's own engine is a different instance and is unaffected, because
 * there is no per-instance native state to share.
 */
object RomwbwSupport {

    private const val TAG = "RomwbwSupport"

    /**
     * The HBIOS configuration block sits at 0x100..0x107, so this prefix is all
     * emu_romwbw_release_of_image() reads. 264 bytes off the asset stream
     * instead of 524288 is what makes it reasonable to ask this question on the
     * main thread during onCreate.
     */
    private const val ROM_HEADER_BYTES = 264

    private val engine by lazy { EmulatorEngine() }

    @Volatile
    private var bundledRelease: String? = null

    @Volatile
    private var bundledReleaseAsked = false

    /** True when this build's core will load a ROM declaring these HBIOS bytes. */
    fun isRunnable(verByte: Int, updByte: Int): Boolean =
        engine.romwbwReleaseSupported(verByte, updByte)

    /** The releases this core has been checked against, e.g. "3.5.1, 3.6.0". */
    fun supportedList(): String = engine.romwbwSupportedList()

    /**
     * The RomWBW release the bundled ROM declares, e.g. "3.5.1", or null when
     * the asset is missing or carries no HBIOS configuration block.
     *
     * Cached because it cannot change while the process lives - it is a
     * property of a file inside the APK - and because both MainActivity and
     * SettingsActivity ask.
     *
     * Null is a real answer and not an error to swallow: it means the app has
     * no bootable ROM, which is the same condition MainActivity already reports
     * as "ROM not found" when it tries to open the asset for real.
     */
    @Synchronized
    fun bundledRomRelease(context: Context, romName: String): String? {
        if (bundledReleaseAsked) return bundledRelease
        bundledReleaseAsked = true
        bundledRelease = try {
            context.assets.open(romName).use { readPrefix(it, ROM_HEADER_BYTES) }
                .let { engine.romwbwReleaseOfImage(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read the RomWBW release out of assets/$romName", e)
            null
        }
        Log.i(TAG, "Bundled ROM $romName declares RomWBW ${bundledRelease ?: "(unreadable)"}; " +
            "this core supports ${supportedList()}")
        return bundledRelease
    }

    /**
     * Up to [count] bytes, however many reads that takes.
     *
     * A single read() on an asset stream is allowed to return fewer bytes than
     * asked for, and a short read here would hand the core a buffer with no
     * configuration block in it and get back "this ROM has no version".
     */
    private fun readPrefix(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = input.read(buffer, filled, count - filled)
            if (read <= 0) break
            filled += read
        }
        return if (filled == count) buffer else buffer.copyOf(filled)
    }
}
