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
 * call on a device, not at build time. The three natives added for the RomWBW
 * release picker are the first ones in a long while, and they are called from
 * Settings, which is a screen a user can reach without ever having touched the
 * emulator.
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
     * above pass by comparing two empty sets. 35 is what both sides hold today.
     */
    @Test
    fun bothListsAreNonEmpty() {
        assertTrue("no external fun found - has the regex or the file moved?", kotlinNames.size > 30)
        assertTrue("no JNI export found - has the regex or the file moved?", jniNames.size > 30)
    }

    /** The three the RomWBW release picker calls, by name, on both sides. */
    @Test
    fun theRomwbwReleaseNativesAreBoundOnBothSides() {
        for (name in listOf(
            "nativeRomwbwReleaseSupported",
            "nativeRomwbwSupportedList",
            "nativeRomwbwReleaseOfImage"
        )) {
            assertTrue("$name is not declared in EmulatorEngine.kt", name in kotlinNames)
            assertTrue("$name has no JNI export", name in jniNames)
        }
    }
}
