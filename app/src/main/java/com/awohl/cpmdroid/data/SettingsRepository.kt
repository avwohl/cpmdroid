package com.awohl.cpmdroid.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SettingsRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "cpmdroid_prefs"
        private const val KEY_ROM_NAME = "rom_name"
        private const val KEY_DISK_SLOT_PREFIX = "disk_slot_"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_WRAP_LINES = "wrap_lines"
        private const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"
        private const val KEY_WARN_MANIFEST_WRITES = "warn_manifest_writes"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_PREFS_VERSION = "prefs_version"
        private const val CURRENT_PREFS_VERSION = 2
        private const val KEY_NVRAM = "nvram"

        private const val DEFAULT_ROM = "emu_avw.rom"
        private const val DEFAULT_FONT_SIZE = 14
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSettings(): EmulatorSettings {
        return EmulatorSettings(
            romName = prefs.getString(KEY_ROM_NAME, DEFAULT_ROM) ?: DEFAULT_ROM,
            diskSlots = (0..3).map { prefs.getString("$KEY_DISK_SLOT_PREFIX$it", null) },
            fontSize = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE),
            wrapLines = prefs.getBoolean(KEY_WRAP_LINES, false)
        )
    }

    fun saveSettings(settings: EmulatorSettings) {
        prefs.edit {
            putString(KEY_ROM_NAME, settings.romName)
            settings.diskSlots.forEachIndexed { index, filename ->
                if (filename != null) {
                    putString("$KEY_DISK_SLOT_PREFIX$index", filename)
                } else {
                    remove("$KEY_DISK_SLOT_PREFIX$index")
                }
            }
            putInt(KEY_FONT_SIZE, settings.fontSize)
            putBoolean(KEY_WRAP_LINES, settings.wrapLines)
        }
    }

    fun setDiskSlot(slot: Int, filename: String?) {
        prefs.edit {
            if (filename != null) {
                putString("$KEY_DISK_SLOT_PREFIX$slot", filename)
            } else {
                remove("$KEY_DISK_SLOT_PREFIX$slot")
            }
        }
    }

    fun setFontSize(size: Int) {
        prefs.edit { putInt(KEY_FONT_SIZE, size) }
    }

    fun isFirstLaunchDone(): Boolean = prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)

    fun markFirstLaunchDone() {
        prefs.edit { putBoolean(KEY_FIRST_LAUNCH_DONE, true) }
    }

    fun isWarnManifestWritesEnabled(): Boolean =
        prefs.getBoolean(KEY_WARN_MANIFEST_WRITES, true)

    fun setWarnManifestWritesEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WARN_MANIFEST_WRITES, enabled) }
    }

    fun isSoundEnabled(): Boolean =
        prefs.getBoolean(KEY_SOUND_ENABLED, false)

    fun setSoundEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SOUND_ENABLED, enabled) }
    }

    fun migrateIfNeeded() {
        val version = prefs.getInt(KEY_PREFS_VERSION, 1)
        if (version < CURRENT_PREFS_VERSION) {
            prefs.edit {
                // Version 2: New warn_manifest_writes setting defaults to true
                // Reset to default so all users get warnings enabled
                remove(KEY_WARN_MANIFEST_WRITES)
                putInt(KEY_PREFS_VERSION, CURRENT_PREFS_VERSION)
            }
        }
    }

    fun getSavedNvramSetting(): String? = prefs.getString(KEY_NVRAM, null)

    fun saveNvramSetting(setting: String) {
        prefs.edit { putString(KEY_NVRAM, setting) }
    }
}
