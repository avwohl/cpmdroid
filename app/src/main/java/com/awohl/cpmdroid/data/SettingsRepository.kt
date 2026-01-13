package com.awohl.cpmdroid.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SettingsRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "cpmdroid_prefs"
        private const val KEY_ROM_NAME = "rom_name"
        private const val KEY_DISK_SLOT_PREFIX = "disk_slot_"
        private const val KEY_BOOT_STRING = "boot_string"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"

        private const val DEFAULT_ROM = "emu_avw.rom"
        private const val DEFAULT_FONT_SIZE = 14
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSettings(): EmulatorSettings {
        return EmulatorSettings(
            romName = prefs.getString(KEY_ROM_NAME, DEFAULT_ROM) ?: DEFAULT_ROM,
            diskSlots = (0..3).map { prefs.getString("$KEY_DISK_SLOT_PREFIX$it", null) },
            bootString = prefs.getString(KEY_BOOT_STRING, "") ?: "",
            fontSize = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
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
            putString(KEY_BOOT_STRING, settings.bootString)
            putInt(KEY_FONT_SIZE, settings.fontSize)
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
}
