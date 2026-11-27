package com.example.voiceclock

import android.content.Context
import java.time.ZonedDateTime

enum class VibrateMode { SHORT, LONG, DOUBLE }

data class OffWorkSettings(
    val enabled: Boolean = false,
    val hour: Int = 18,
    val minute: Int = 0,
    val advanceMinutes: Int = 5,
    val weekdays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val onlyHeadset: Boolean = false,
    val vibrate: Boolean = true,
    val vibrateMode: VibrateMode = VibrateMode.SHORT
) {
    fun timeOn(day: ZonedDateTime) = day.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
    fun advanceTimeOn(day: ZonedDateTime): ZonedDateTime? =
        if (advanceMinutes <= 0) null else timeOn(day).minusMinutes(advanceMinutes.toLong())

    /**
     * 从 now 出发，找到不早于 now 且落在 weekdays 里的第一个「该时刻」。
     *
     * 例如 now=周五 19:00、下班 18:00、只选工作日 → 下周一 18:00。
     * 纯函数，便于单元测试。
     */
    fun nextOccurrence(now: ZonedDateTime, target: ZonedDateTime): ZonedDateTime? {
        var candidate = target
        if (candidate.isBefore(now)) {
            // 顺延到明天同一时刻，再按星期往后找
            candidate = candidate.plusDays(1)
        }
        repeat(8) {
            if (weekdays.contains(candidate.dayOfWeek.value) && !candidate.isBefore(now)) return candidate
            candidate = candidate.plusDays(1)
        }
        return null
    }

    /** 下一次提前提醒时刻（未开启提前提醒时为 null）。 */
    fun nextAdvance(now: ZonedDateTime): ZonedDateTime? =
        advanceTimeOn(now)?.let { nextOccurrence(now, it) }

    /** 下一次到点提醒时刻。 */
    fun nextOnTime(now: ZonedDateTime): ZonedDateTime? =
        nextOccurrence(now, timeOn(now))
}

object OffWorkSettingsStorage {
    private const val PREFS = "off_work_settings"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): OffWorkSettings {
        val p = prefs(context)
        val weekdaysRaw = p.getString("weekdays", "1,2,3,4,5") ?: "1,2,3,4,5"
        val weekdays = weekdaysRaw.split(',').mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }.toSet()
        return OffWorkSettings(
            enabled = p.getBoolean("enabled", false),
            hour = p.getInt("hour", 18).coerceIn(0, 23),
            minute = p.getInt("minute", 0).coerceIn(0, 59),
            advanceMinutes = p.getInt("advanceMinutes", 5).coerceAtLeast(0),
            weekdays = weekdays.takeIf { it.isNotEmpty() } ?: setOf(1, 2, 3, 4, 5),
            onlyHeadset = p.getBoolean("onlyHeadset", false),
            vibrate = p.getBoolean("vibrate", true),
            vibrateMode = runCatching { VibrateMode.valueOf(p.getString("vibrateMode", VibrateMode.SHORT.name) ?: VibrateMode.SHORT.name) }.getOrDefault(VibrateMode.SHORT)
        )
    }

    fun save(context: Context, settings: OffWorkSettings) {
        prefs(context).edit()
            .putBoolean("enabled", settings.enabled)
            .putInt("hour", settings.hour)
            .putInt("minute", settings.minute)
            .putInt("advanceMinutes", settings.advanceMinutes)
            .putString("weekdays", settings.weekdays.sorted().joinToString(","))
            .putBoolean("onlyHeadset", settings.onlyHeadset)
            .putBoolean("vibrate", settings.vibrate)
            .putString("vibrateMode", settings.vibrateMode.name)
            .apply()
    }
}
