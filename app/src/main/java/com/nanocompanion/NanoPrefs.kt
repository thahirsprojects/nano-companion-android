package com.nanocompanion

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NanoPrefs(context: Context) {

    companion object {
        private const val FILE = "nano_prefs"

        private const val KEY_NAME = "buddy_name"
        private const val KEY_ACTIVE_START = "active_start"
        private const val KEY_ACTIVE_END = "active_end"
        private const val KEY_WATER_MINUTES = "water_minutes"
        private const val KEY_EYE_STYLE = "eye_style"
        private const val KEY_FORWARD_NOTIFICATIONS = "forward_notifications"
        private const val KEY_HIDE_CONTENT = "hide_content"
        private const val KEY_FORWARD_MUSIC = "forward_music"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_MUTED_APPS = "muted_apps"
        private const val KEY_SEEN_APPS = "seen_apps"
        private const val KEY_WATER_DAY = "water_day"
        private const val KEY_WATER_COUNT = "water_count"

        private const val KEY_SOUND_ON = "sound_on"
        private const val KEY_SOUND_VOLUME = "sound_volume"
        private const val KEY_SOUND_FLAGS = "sound_flags"

        private const val KEY_MUTE_UNTIL = "mute_until"
        private const val KEY_FOLLOW_DND = "follow_dnd"
        private const val KEY_SHOW_BATTERY = "show_battery"

        // 24 days fits Nano's millis() comparison; used for "until I unmute"
        // (resent on every connect, so it never runs out in practice).
        const val MAX_MUTE_MINUTES = 60L * 24L * 24L

        private const val MAX_SEEN_APPS = 80

        // Sound flags, matching SF_* in the firmware.
        const val SF_NOTIFY = 1
        const val SF_CALL = 2
        const val SF_WATER = 4
        const val SF_FIND = 8
        const val SF_TOUCH = 16
        const val SF_BOOT = 32
        const val SF_BIRTHDAY = 64
        const val SF_QUIET_OFF_HOURS = 128
        const val SF_DEFAULT =
            SF_NOTIFY or SF_CALL or SF_WATER or SF_FIND or SF_TOUCH or SF_BOOT or SF_BIRTHDAY

        val VOLUMES = listOf("Low", "Medium", "High")

        // Sound ids for the P| preview command, matching the firmware's Sound enum.
        val PREVIEW_SOUNDS = listOf(
            "Chirp" to 0, "Ring" to 1, "Water" to 2, "Find" to 3,
            "Happy" to 4, "Startup" to 6, "Birthday" to 7
        )

        const val MAX_NAME_LENGTH = 8
        const val MIN_WATER_MINUTES = 5
        const val MAX_WATER_MINUTES = 240

        val EYE_STYLES = listOf("Normal", "Happy", "Surprised", "Angry")

        fun formatMinutes(minutes: Int): String =
            String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var buddyName: String
        get() = prefs.getString(KEY_NAME, "Nano") ?: "Nano"
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    // Minutes after midnight.
    var activeStart: Int
        get() = prefs.getInt(KEY_ACTIVE_START, 9 * 60)
        set(value) = prefs.edit().putInt(KEY_ACTIVE_START, value).apply()

    var activeEnd: Int
        get() = prefs.getInt(KEY_ACTIVE_END, 17 * 60 + 30)
        set(value) = prefs.edit().putInt(KEY_ACTIVE_END, value).apply()

    var waterMinutes: Int
        get() = prefs.getInt(KEY_WATER_MINUTES, 20)
        set(value) = prefs.edit().putInt(KEY_WATER_MINUTES, value).apply()

    var eyeStyle: Int
        get() = prefs.getInt(KEY_EYE_STYLE, 0)
        set(value) = prefs.edit().putInt(KEY_EYE_STYLE, value).apply()

    var forwardNotifications: Boolean
        get() = prefs.getBoolean(KEY_FORWARD_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_FORWARD_NOTIFICATIONS, value).apply()

    var hideContent: Boolean
        get() = prefs.getBoolean(KEY_HIDE_CONTENT, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_CONTENT, value).apply()

    var forwardMusic: Boolean
        get() = prefs.getBoolean(KEY_FORWARD_MUSIC, true)
        set(value) = prefs.edit().putBoolean(KEY_FORWARD_MUSIC, value).apply()

    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, false)
        set(value) = prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()

    var soundOn: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_ON, value).apply()

    var soundVolume: Int
        get() = prefs.getInt(KEY_SOUND_VOLUME, 1)
        set(value) = prefs.edit().putInt(KEY_SOUND_VOLUME, value).apply()

    var soundFlags: Int
        get() = prefs.getInt(KEY_SOUND_FLAGS, SF_DEFAULT)
        set(value) = prefs.edit().putInt(KEY_SOUND_FLAGS, value).apply()

    // Wall-clock time (ms) until which Nano is muted; Long.MAX_VALUE = until unmuted.
    var muteUntil: Long
        get() = prefs.getLong(KEY_MUTE_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_MUTE_UNTIL, value).apply()

    val isMuted: Boolean
        get() = muteUntil > System.currentTimeMillis()

    // Z|minutes for the time left (0 = not muted). "Until unmuted" is sent
    // as a long mute; the app clears it when you unmute.
    fun muteCommand(): String {
        val left = muteUntil - System.currentTimeMillis()
        val minutes = if (left <= 0) 0L else ((left + 59_999L) / 60_000L).coerceAtMost(MAX_MUTE_MINUTES)
        return "Z|$minutes"
    }

    var followDnd: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW_DND, true)
        set(value) = prefs.edit().putBoolean(KEY_FOLLOW_DND, value).apply()

    var showBattery: Boolean
        get() = prefs.getBoolean(KEY_SHOW_BATTERY, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_BATTERY, value).apply()

    fun hasSound(flag: Int): Boolean = soundFlags and flag != 0

    fun setSound(flag: Int, enabled: Boolean) {
        soundFlags = if (enabled) soundFlags or flag else soundFlags and flag.inv()
    }

    // B|on|volume 0-2|flags
    fun soundCommand(): String =
        "B|${if (soundOn) 1 else 0}|$soundVolume|$soundFlags"

    var mutedApps: Set<String>
        get() = prefs.getStringSet(KEY_MUTED_APPS, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_MUTED_APPS, value.toSet()).apply()

    // Apps that have posted notifications, as package -> label.
    val seenApps: Map<String, String>
        get() = prefs.getStringSet(KEY_SEEN_APPS, emptySet())
            .orEmpty()
            .mapNotNull { entry ->
                val tab = entry.indexOf('\t')
                if (tab <= 0) null else entry.substring(0, tab) to entry.substring(tab + 1)
            }
            .toMap()

    fun rememberApp(packageName: String, label: String) {
        val current = prefs.getStringSet(KEY_SEEN_APPS, emptySet()).orEmpty()
        if (current.any { it.startsWith("$packageName\t") }) return
        if (current.size >= MAX_SEEN_APPS) return

        prefs.edit()
            .putStringSet(KEY_SEEN_APPS, current + "$packageName\t$label")
            .apply()
    }

    val waterToday: Int
        get() = if (prefs.getString(KEY_WATER_DAY, null) == today()) {
            prefs.getInt(KEY_WATER_COUNT, 0)
        } else {
            0
        }

    fun recordWater() {
        prefs.edit()
            .putString(KEY_WATER_DAY, today())
            .putInt(KEY_WATER_COUNT, waterToday + 1)
            .apply()
    }

    fun resetWater() {
        prefs.edit()
            .putString(KEY_WATER_DAY, today())
            .putInt(KEY_WATER_COUNT, 0)
            .apply()
    }

    // S|name|HH:MM start|HH:MM end|water minutes
    fun settingsCommand(): String {
        val name = NanoText.clean(buddyName, MAX_NAME_LENGTH).ifBlank { "Nano" }

        return "S|$name|${formatMinutes(activeStart)}|" +
            "${formatMinutes(activeEnd)}|$waterMinutes"
    }

    fun eyeStyleCommand(): String = "E|$eyeStyle"

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
