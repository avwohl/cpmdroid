package com.awohl.cpmdroid

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class EmulatorEngine {
    companion object {
        private const val TAG = "EmulatorEngine"
        private const val INSTRUCTIONS_PER_BATCH = 50000

        // Host file state constants (must match emu_io.h)
        const val HOST_FILE_IDLE = 0
        const val HOST_FILE_WAITING_READ = 1
        const val HOST_FILE_READING = 2
        const val HOST_FILE_WRITING = 3
        const val HOST_FILE_WRITE_READY = 4

        init {
            System.loadLibrary("cpmdroid")
        }
    }

    private val running = AtomicBoolean(false)
    private var outputListener: ((ByteArray) -> Unit)? = null

    // Native methods
    private external fun nativeInit()
    private external fun nativeDestroy()
    private external fun nativeLoadRom(romData: ByteArray): Boolean
    private external fun nativeLoadDisk(unit: Int, diskData: ByteArray): Boolean
    private external fun nativeCompleteInit()
    private external fun nativeRun(instructionCount: Int)
    private external fun nativeStop()
    private external fun nativeQueueInput(ch: Int)
    private external fun nativeQueueInputString(str: String)
    private external fun nativeReset()
    private external fun nativeSetDiskSliceCount(unit: Int, slices: Int)
    private external fun nativeIsDiskLoaded(unit: Int): Boolean

    // Host file transfer native methods
    private external fun nativeGetHostFileState(): Int
    private external fun nativeGetHostFileReadName(): String
    private external fun nativeGetHostFileWriteName(): String
    private external fun nativeProvideHostFileData(data: ByteArray?)
    private external fun nativeGetHostFileWriteData(): ByteArray?
    private external fun nativeHostFileWriteDone()
    private external fun nativeHostFileCancel()

    // NVRAM boot configuration native methods (string-based API)
    private external fun nativeSetNvramSetting(setting: String)
    private external fun nativeGetNvramSetting(): String
    private external fun nativeHasNvramChange(): Boolean
    private external fun nativeIsNvramInitialized(): Boolean

    // Manifest disk write warning native methods
    private external fun nativeSetDiskIsManifest(unit: Int, isManifest: Boolean)
    private external fun nativeSetDiskWarningSuppressed(unit: Int, suppressed: Boolean)
    private external fun nativeCheckManifestWriteWarning(): Boolean

    // Disk persistence native methods
    private external fun nativeIsDiskDirty(unit: Int): Boolean
    private external fun nativeClearDiskDirty(unit: Int)
    private external fun nativeGetDiskData(unit: Int): ByteArray?

    fun init() {
        Log.i(TAG, "Initializing emulator engine")
        nativeInit()
    }

    fun destroy() {
        Log.i(TAG, "Destroying emulator engine")
        stop()
        nativeDestroy()
    }

    fun loadRom(romData: ByteArray): Boolean {
        Log.i(TAG, "Loading ROM: ${romData.size} bytes")
        return nativeLoadRom(romData)
    }

    fun loadDisk(unit: Int, diskData: ByteArray): Boolean {
        Log.i(TAG, "Loading disk unit $unit: ${diskData.size} bytes")
        return nativeLoadDisk(unit, diskData)
    }

    fun completeInit() {
        Log.i(TAG, "Completing initialization")
        nativeCompleteInit()
    }

    fun setOutputListener(listener: (ByteArray) -> Unit) {
        outputListener = listener
    }

    // Called from native code
    @Suppress("unused")
    fun onOutput(data: ByteArray) {
        outputListener?.invoke(data)
    }

    fun queueInput(ch: Int) {
        nativeQueueInput(ch)
    }

    fun queueInputString(str: String) {
        nativeQueueInputString(str)
    }

    fun runBatch(): Boolean {
        if (!running.get()) return false
        nativeRun(INSTRUCTIONS_PER_BATCH)
        return running.get()
    }

    fun start() {
        running.set(true)
    }

    fun stop() {
        running.set(false)
        nativeStop()
    }

    fun reset() {
        nativeReset()
    }

    fun setDiskSliceCount(unit: Int, slices: Int) {
        nativeSetDiskSliceCount(unit, slices)
    }

    fun isDiskLoaded(unit: Int): Boolean = nativeIsDiskLoaded(unit)

    fun isRunning(): Boolean = running.get()

    // Host file transfer methods
    fun getHostFileState(): Int = nativeGetHostFileState()
    fun getHostFileReadName(): String = nativeGetHostFileReadName()
    fun getHostFileWriteName(): String = nativeGetHostFileWriteName()
    fun provideHostFileData(data: ByteArray?) = nativeProvideHostFileData(data)
    fun getHostFileWriteData(): ByteArray? = nativeGetHostFileWriteData()
    fun hostFileWriteDone() = nativeHostFileWriteDone()
    fun hostFileCancel() = nativeHostFileCancel()

    // NVRAM boot configuration methods (string-based API)
    // Set boot option: "C" (CP/M), "Z" (ZSDOS), "0" (disk 0), "2.3" (disk 2 slice 3), "H" (menu), "" (clear)
    fun setNvramSetting(setting: String) = nativeSetNvramSetting(setting)
    // Get current boot option as string (e.g., "C", "2.3", "" if uninitialized)
    fun getNvramSetting(): String = nativeGetNvramSetting()
    // Check if NVRAM was modified since last getNvramSetting() call (dirty flag)
    fun hasNvramChange(): Boolean = nativeHasNvramChange()
    // Check if NVRAM has been initialized (signature = 'W')
    fun isNvramInitialized(): Boolean = nativeIsNvramInitialized()

    // Manifest disk write warning methods
    // Mark a disk as managed by app manifest (can be overwritten on update)
    fun setDiskIsManifest(unit: Int, isManifest: Boolean) = nativeSetDiskIsManifest(unit, isManifest)
    // Suppress warning for this disk (user checked "Don't warn about overwrites")
    fun setDiskWarningSuppressed(unit: Int, suppressed: Boolean) = nativeSetDiskWarningSuppressed(unit, suppressed)
    // Poll for manifest write warning - returns true once per session when user writes to manifest disk
    fun checkManifestWriteWarning(): Boolean = nativeCheckManifestWriteWarning()

    // Disk persistence methods - for saving modified disks
    // Check if disk has been modified since loading
    fun isDiskDirty(unit: Int): Boolean = nativeIsDiskDirty(unit)
    // Clear dirty flag after saving disk
    fun clearDiskDirty(unit: Int) = nativeClearDiskDirty(unit)
    // Get disk data for saving (returns null if not an in-memory disk)
    fun getDiskData(unit: Int): ByteArray? = nativeGetDiskData(unit)
}
