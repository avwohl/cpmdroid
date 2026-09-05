package com.awohl.cpmdroid.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/*
 * The v0 rename, checked where it can be checked.
 *
 * This is the project's first test source set. It exists because the pass under
 * it renames the user's files and rewrites the preferences that name them, on a
 * device nobody here has, in an app with no instrumented runner - and because
 * V0Migration.kt was written with no Context and no SharedPreferences precisely
 * so that a plain JVM test could reach all of it. `./gradlew :app:test` runs it;
 * nothing in this file needs the Android SDK, an emulator or a device.
 *
 * What it cannot reach is the half that lives in SharedPreferences: whether the
 * slots and the flag land in one edit block, and whether the pass has finished
 * before checkFirstLaunchAndLoad() reads a slot. MANUAL_CHECKS.md section 6 has
 * those.
 *
 * The name pairs below are written out rather than derived from
 * CATALOG_DISK_STEMS. Deriving them would test the code against itself: the
 * point of the literal list is that an edit to the stem set has to be made here
 * too, against the twenty filenames the published catalogs actually carry.
 */
class V0MigrationTest {

    /** Pre-v0 catalog filename to the filename catalog-v0-3.5.1.json publishes. */
    private val publishedPairs = listOf(
        "hd1k_combo.img" to "hd1k_combo-v0-3.5.1.img",
        "hd1k_cpm22.img" to "hd1k_cpm22-v0-3.5.1.img",
        "hd1k_zsdos.img" to "hd1k_zsdos-v0-3.5.1.img",
        "hd1k_zpm3.img" to "hd1k_zpm3-v0-3.5.1.img",
        "hd1k_cpm3.img" to "hd1k_cpm3-v0-3.5.1.img",
        "hd1k_nzcom.img" to "hd1k_nzcom-v0-3.5.1.img",
        "hd1k_qpm.img" to "hd1k_qpm-v0-3.5.1.img",
        "hd1k_games.img" to "hd1k_games-v0-3.5.1.img",
        "hd1k_aztecc.img" to "hd1k_aztecc-v0-3.5.1.img",
        "hd1k_bascomp.img" to "hd1k_bascomp-v0-3.5.1.img",
        "hd1k_cowgol.img" to "hd1k_cowgol-v0-3.5.1.img",
        "hd1k_fortran.img" to "hd1k_fortran-v0-3.5.1.img",
        "hd1k_hitechc.img" to "hd1k_hitechc-v0-3.5.1.img",
        "hd1k_tpascal.img" to "hd1k_tpascal-v0-3.5.1.img",
        "hd1k_z80asm.img" to "hd1k_z80asm-v0-3.5.1.img",
        "hd1k_ws4.img" to "hd1k_ws4-v0-3.5.1.img",
        "hd1k_z3plus.img" to "hd1k_z3plus-v0-3.5.1.img",
        "hd1k_bp.img" to "hd1k_bp-v0-3.5.1.img",
        "hd1k_msxroms1.img" to "hd1k_msxroms1-v0-3.5.1.img",
        "hd1k_msxroms2.img" to "hd1k_msxroms2-v0-3.5.1.img"
    )

    private lateinit var root: File
    private lateinit var disks: File
    private lateinit var modified: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("v0migration").toFile()
        disks = File(root, "Disks").also { it.mkdirs() }
        modified = File(root, "ModifiedDisks").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        // setWritable(true) first: one test takes a directory read-only to make
        // renameTo fail, and a read-only directory cannot have its entries
        // unlinked, so without this the tree survives the run.
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    private fun write(dir: File, name: String, contents: String) {
        File(dir, name).writeText(contents)
    }

    private fun read(dir: File, name: String): String? {
        val file = File(dir, name)
        return if (file.exists()) file.readText() else null
    }

    @Test
    fun everyPublishedNameMapsToItsPublishedV0Name() {
        publishedPairs.forEach { (old, new) ->
            assertEquals("mapping $old", new, v0NameOf(old))
        }
    }

    @Test
    fun aNameThatIsAlreadyV0IsLeftAlone() {
        // Idempotence is not a nicety here. The flag guarding the pass is
        // written with apply() while the renames are immediate, so a process
        // killed between them comes back to exactly this input.
        publishedPairs.forEach { (_, new) -> assertNull(new, v0NameOf(new)) }
        assertNull(v0NameOf("hd1k_combo-v0-3.6.0.img"))
    }

    @Test
    fun theBundledRomNameIsRefused() {
        // The one rename that stops the app booting at all: MainActivity opens
        // rom_name with assets.open(), against a file inside the APK.
        assertNull(v0NameOf("emu_avw.rom"))
        assertNull(v0NameOf("emu_rcz80.rom"))
        assertNull(v0NameOf("emu_avw-v0-3.5.1.rom"))
    }

    @Test
    fun namesTheCatalogNeverPublishedAreLeftAlone() {
        listOf(
            // Only ever published under 3.6.0; there is no 3.5.1 file to become.
            "hd1k_infocom.img", "hd1k_cobol.img", "hd1k_wp.img",
            "hd1k_dos65.img", "hd1k_msx.img",
            // The user's own.
            "my_disk.img", "hd1k_combo_backup.img", "hd1k_combo.old.img",
            "hd1k_combo.bak", "HD1K_COMBO.IMG", "notes.txt",
            // Scratch files downloadDisk and savePersistedDisk leave behind.
            "hd1k_combo.img.123456789.tmp",
            // Shapes with no stem, no extension or nothing at all.
            "", ".img", "hd1k_combo.", "hd1k_combo", "."
        ).forEach { assertNull(it, v0NameOf(it)) }
    }

    @Test
    fun bothDirectoriesMoveAndTheSlotFollows() {
        write(disks, "hd1k_combo.img", "pristine")
        write(modified, "hd1k_combo.img", "the user's work")

        val result = migrateDiskNames(disks, modified, listOf("hd1k_combo.img", null, null, null))

        assertEquals(2, result.renamed)
        assertTrue(result.complete)
        assertEquals(listOf("hd1k_combo-v0-3.5.1.img", null, null, null), result.slots)
        assertEquals("pristine", read(disks, "hd1k_combo-v0-3.5.1.img"))
        assertEquals("the user's work", read(modified, "hd1k_combo-v0-3.5.1.img"))
        assertFalse(File(disks, "hd1k_combo.img").exists())
        assertFalse(File(modified, "hd1k_combo.img").exists())
    }

    @Test
    fun theWrittenToCopyMovesWithNoDownloadBesideIt() {
        // ModifiedDisks/ without Disks/ is reachable: deleteDisk() removes the
        // download and leaves the persisted copy. This is the file that is not
        // re-downloadable, so it has to move on its own.
        write(modified, "hd1k_zsdos.img", "hours inside CP/M")

        val result = migrateDiskNames(disks, modified, listOf("hd1k_zsdos.img", null, null, null))

        assertTrue(result.complete)
        assertEquals("hd1k_zsdos-v0-3.5.1.img", result.slots[0])
        assertEquals("hours inside CP/M", read(modified, "hd1k_zsdos-v0-3.5.1.img"))
    }

    @Test
    fun everythingTheCatalogNeverNamedSurvivesUntouched() {
        write(disks, "my_disk.img", "imported by hand")
        write(modified, "my_disk.img", "written by hand")
        write(disks, "emu_avw.rom", "not a disk")
        write(disks, "hd1k_combo.img.99.tmp", "scratch")

        val result = migrateDiskNames(disks, modified, listOf("my_disk.img", null, null, null))

        assertEquals(0, result.renamed)
        assertTrue(result.complete)
        assertEquals("my_disk.img", result.slots[0])
        assertEquals("imported by hand", read(disks, "my_disk.img"))
        assertEquals("written by hand", read(modified, "my_disk.img"))
        assertEquals("not a disk", read(disks, "emu_avw.rom"))
        assertEquals("scratch", read(disks, "hd1k_combo.img.99.tmp"))
    }

    @Test
    fun runningItASecondTimeChangesNothing() {
        write(disks, "hd1k_games.img", "pristine")
        write(modified, "hd1k_games.img", "the user's work")

        val first = migrateDiskNames(disks, modified, listOf("hd1k_games.img", null, null, null))
        // Second run from the ORIGINAL slots, not the migrated ones: that is the
        // state a kill after the renames and before the preferences reach disk
        // leaves behind.
        val second = migrateDiskNames(disks, modified, listOf("hd1k_games.img", null, null, null))

        assertEquals(first.slots, second.slots)
        assertEquals(0, second.renamed)
        assertTrue(second.complete)
        assertEquals("the user's work", read(modified, "hd1k_games-v0-3.5.1.img"))
    }

    @Test
    fun anExistingV0FileIsKeptAndTheOldOneIsNotDeleted() {
        // Both names present at once is reachable after release A ships alone:
        // the catalog this build fetches still serves pre-v0 names, so a disk
        // downloaded again after the pass arrives under the old one.
        write(disks, "hd1k_bp.img", "downloaded again")
        write(disks, "hd1k_bp-v0-3.5.1.img", "migrated earlier")

        val result = migrateDiskNames(disks, modified, listOf("hd1k_bp.img", null, null, null))

        assertEquals(0, result.renamed)
        assertTrue(result.complete)
        assertEquals("hd1k_bp-v0-3.5.1.img", result.slots[0])
        assertEquals("migrated earlier", read(disks, "hd1k_bp-v0-3.5.1.img"))
        assertEquals("downloaded again", read(disks, "hd1k_bp.img"))
    }

    @Test
    fun aSlotWhoseFilesAreAbsentStillMoves() {
        // Auto Backup is on and a 51 MB image does not travel with a 1 KB
        // preferences file, so a restored device can name a disk it does not
        // have. Nothing is at risk, so the slot moves with the convention.
        val result = migrateDiskNames(disks, modified, listOf("hd1k_cpm3.img", null, null, null))

        assertEquals(0, result.renamed)
        assertTrue(result.complete)
        assertEquals("hd1k_cpm3-v0-3.5.1.img", result.slots[0])
    }

    @Test
    fun aSlotWhoseFileCouldNotMoveKeepsPointingAtIt() {
        write(disks, "hd1k_qpm.img", "pristine")
        write(modified, "hd1k_qpm.img", "the user's work")
        // Skipped rather than failed where a rename cannot be made to fail -
        // running as root, or a filesystem with no permission bits. The
        // behaviour under test is what the pass does with a failure, not
        // whether this platform can produce one.
        assumeTrue("the directory could not be made read-only", disks.setWritable(false))
        assumeTrue(
            "a read-only directory still allowed a rename",
            !File(disks, "hd1k_qpm.img").renameTo(File(disks, "probe.img"))
        )

        val result = migrateDiskNames(disks, modified, listOf("hd1k_qpm.img", null, null, null))

        assertFalse(result.complete)
        // The slot stays where the bytes are. Moving it would make
        // isDiskDownloaded() false, and checkFirstLaunchAndLoad() answers that
        // by writing the catalog's default disk over slot 0.
        assertEquals("hd1k_qpm.img", result.slots[0])
        // And the written-to copy is deliberately left beside it, so the pair
        // stays readable under the name the preference still holds.
        assertEquals("the user's work", read(modified, "hd1k_qpm.img"))
    }

    @Test
    fun aPristineCopyIsPutBackWhenTheWrittenToOneCannotFollowIt() {
        // The other half of the pair, and the one that costs something if it is
        // left alone: Disks/ moves, ModifiedDisks/ cannot, and the slot stays on
        // the old name because that is where the user's work still is. Without
        // the rollback, isDiskDownloaded() then answers false for that slot,
        // checkFirstLaunchAndLoad() reads it as a first launch, and slot 0 is
        // overwritten with whatever the catalog marks defaultSlot 0.
        write(disks, "hd1k_z3plus.img", "pristine")
        write(modified, "hd1k_z3plus.img", "the user's work")
        assumeTrue("the directory could not be made read-only", modified.setWritable(false))
        assumeTrue(
            "a read-only directory still allowed a rename",
            !File(modified, "hd1k_z3plus.img").renameTo(File(modified, "probe.img"))
        )

        val result = migrateDiskNames(disks, modified, listOf("hd1k_z3plus.img", null, null, null))

        assertFalse(result.complete)
        assertEquals(0, result.renamed)
        assertEquals("hd1k_z3plus.img", result.slots[0])
        // Both copies under the one name the preference holds, so the pair the
        // app loads is the pair the user wrote to.
        assertEquals("pristine", read(disks, "hd1k_z3plus.img"))
        assertEquals("the user's work", read(modified, "hd1k_z3plus.img"))
        assertNull(read(disks, "hd1k_z3plus-v0-3.5.1.img"))
    }

    @Test
    fun unavailableStorageChangesNothingAndSaysSo() {
        // getExternalFilesDir(null) answering null. The pass must not report
        // complete here: the caller stamps its one-shot flag on that, and the
        // real ModifiedDisks/ is still full of pre-v0 names.
        val slots = listOf("hd1k_combo.img", null, "my_disk.img", null)

        val result = migrateDiskNames(null, modified, slots)
        assertFalse(result.complete)
        assertEquals(0, result.renamed)
        assertEquals(slots, result.slots)

        val other = migrateDiskNames(disks, null, slots)
        assertFalse(other.complete)
        assertEquals(slots, other.slots)
    }

    @Test
    fun allFourSlotsMigrateIndependently() {
        publishedPairs.take(4).forEach { (old, _) -> write(disks, old, old) }
        val slots = publishedPairs.take(4).map { it.first }

        val result = migrateDiskNames(disks, modified, slots)

        assertEquals(publishedPairs.take(4).map { it.second }, result.slots)
        assertEquals(4, result.renamed)
    }
}
