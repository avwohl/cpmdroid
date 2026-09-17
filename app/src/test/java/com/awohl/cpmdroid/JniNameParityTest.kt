package com.awohl.cpmdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every `external fun` in EmulatorEngine.kt has a JNI export with the matching
 * name, and vice versa.
 *
 * This is the one failure in this project with no guard anywhere else. The two
 * lists are kept in step by hand; minification is off, so R8 raises nothing; the
 * NDK build cannot see the Kotlin and the Kotlin compiler cannot see the C++;
 * and a name that does not match fails as an `UnsatisfiedLinkError` at the first
 * call on a device, not at build time.
 *
 * Both lists lost two names on the same commit - `nativeRomwbwReleaseSupported`
 * and `nativeRomwbwSupportedList`, whose core functions romwbw_emu v1.44
 * deleted. Removing an export without its declaration, or the other way round,
 * is exactly what this test is for, so the literal list below moved with them.
 *
 * It reads the two source files as text rather than reflecting over anything,
 * because that is the only way to compare a Kotlin declaration with a C symbol
 * without a device to load the library on. That also means it costs nothing and
 * runs anywhere `./gradlew :app:test` runs.
 */
class JniNameParityTest {

    /**
     * Gradle runs a unit test with the module directory as its working
     * directory, so `src/...` is the usual answer; the repository root is
     * checked too, for a runner that starts higher up.
     */
    private fun source(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "cannot find $relative from ${File(".").absolutePath} - " +
                    "tried ${candidates.map { it.path }}"
            )
    }

    private val kotlinNames: Set<String>
        get() = Regex("external fun (native[A-Za-z0-9_]*)")
            .findAll(source("src/main/java/com/awohl/cpmdroid/EmulatorEngine.kt").readText())
            .map { it.groupValues[1] }
            .toSet()

    private val jniNames: Set<String>
        get() = Regex("JNIEXPORT[^;{]*?\\bJava_com_awohl_cpmdroid_EmulatorEngine_(native[A-Za-z0-9_]*)")
            .findAll(source("src/main/cpp/emu_io_android.cpp").readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun everyDeclaredNativeHasAnExportAndEveryExportIsDeclared() {
        val kotlin = kotlinNames
        val jni = jniNames

        // Named individually rather than as one set comparison: the message a
        // failure prints is the whole value of this test, and "these are
        // declared with nothing behind them" is what the reader needs.
        assertEquals(
            "declared in EmulatorEngine.kt with no JNI export behind them",
            emptySet<String>(),
            kotlin - jni
        )
        assertEquals(
            "exported from emu_io_android.cpp and declared nowhere in Kotlin",
            emptySet<String>(),
            jni - kotlin
        )
    }

    /**
     * A guard on the guard: a regex that stopped matching would make the test
     * above pass by comparing two empty sets. 33 is what both sides hold today,
     * down from 35 when the two release-gate natives went.
     */
    @Test
    fun bothListsAreNonEmpty() {
        assertTrue("no external fun found - has the regex or the file moved?", kotlinNames.size > 30)
        assertTrue("no JNI export found - has the regex or the file moved?", jniNames.size > 30)
    }

    /**
     * The RomWBW release native, by name, on both sides - and the two that are
     * gone, on neither.
     *
     * `nativeRomwbwReleaseOfImage` is the one left of three. It is also the one
     * with no Kotlin caller: `EmulatorEngine.romwbwReleaseOfImage()` wraps it
     * and nothing calls that, so this test is the whole of what guards its
     * binding. The other two were called on every launch and on every index
     * entry until romwbw_emu v1.44 deleted `emu_romwbw_release_supported()` and
     * `emu_romwbw_supported_list()`; asserting they are absent keeps a
     * half-revert - a Kotlin declaration restored without its export, or an
     * export restored against a core that no longer defines the function -
     * failing here rather than at the first call on a device.
     */
    @Test
    fun theRomwbwReleaseNativeIsBoundOnBothSidesAndTheGateNativesAreGone() {
        assertTrue(
            "nativeRomwbwReleaseOfImage is not declared in EmulatorEngine.kt",
            "nativeRomwbwReleaseOfImage" in kotlinNames
        )
        assertTrue(
            "nativeRomwbwReleaseOfImage has no JNI export",
            "nativeRomwbwReleaseOfImage" in jniNames
        )
        for (gone in listOf("nativeRomwbwReleaseSupported", "nativeRomwbwSupportedList")) {
            assertTrue(
                "$gone is declared again in EmulatorEngine.kt; the core function behind " +
                    "it was deleted in romwbw_emu v1.44",
                gone !in kotlinNames
            )
            assertTrue(
                "$gone is exported again from emu_io_android.cpp; the core function behind " +
                    "it was deleted in romwbw_emu v1.44",
                gone !in jniNames
            )
        }
    }
}
