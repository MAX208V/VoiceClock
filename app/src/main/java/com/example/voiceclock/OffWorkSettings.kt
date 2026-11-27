package com.example.voiceclock

import android.content.Context
import java.time.ZonedDateTime

enum class VibrateMode { SHORT, LONG, DOUBLE }

/** 提醒种类。文案在此集中定义，避免多处硬编码不一致。 */
enum class ReminderKind {
    /** 上班前 N 分钟。 */
    BEFORE_START,
    /** 上班到点。 */
    ON_START,
    /** 下班前 N 分钟。 */
    BEFORE_END,
    /** 下班到点。 */
    ON_END,
    /** 休息开始（上一段下班那一刻）。 */
    BREAK_START,
    /** 休息结束（下一段上班那一刻）。 */
    BREAK_END;

    /** 播报正文。 */
    fun text(minutes: Int): String = when (this) {
        BEFORE_START -> "还有 $minutes 分钟上班"
        ON_START -> "上班时间到了"
        BEFORE_END -> "还有 $minutes 分钟下班"
        ON_END -> "下班时间到了"
        BREAK_START -> "休息时间到了"
        BREAK_END -> "休息结束，继续上班"
    }
}

/**
 * 一个工作时段，例如 09:00–12:00。
 *
 * 休息由相邻两段之间的间隙自动推导（上一段 endTime → 下一段 startTime），
 * 因此不需要单独配置休息段。
 */
data class TimeRange(
    val startHour: Int = 9,
    val startMinute: Int = 0,
    val endHour: Int = 18,
    val endMinute: Int = 0,
    val enabled: Boolean = true,
    /** 提前多少分钟提醒上班（0 表示不提前）。 */
    val advanceStartMinutes: Int = 10,
    /** 提前多少分钟提醒下班（0 表示不提前）。 */
    val advanceEndMinutes: Int = 5,
    val remindBeforeStart: Boolean = false,
    val remindOnStart: Boolean = false,
    val remindBeforeEnd: Boolean = true,
    val remindOnEnd: Boolean = true,
    val remindBreakStart: Boolean = true,
    val remindBreakEnd: Boolean = true
) {
    fun startOn(day: ZonedDateTime): ZonedDateTime =
        day.withHour(startHour).withMinute(startMinute).withSecond(0).withNano(0)

    fun endOn(day: ZonedDateTime): ZonedDateTime =
        day.withHour(endHour).withMinute(endMinute).withSecond(0).withNano(0)

    /** 该段是否跨午夜（结束时刻不晚于开始时刻）。 */
    val crossesMidnight: Boolean
        get() = endHour < startHour || (endHour == startHour && endMinute <= startMinute)

    /** 界面显示用，如「09:00–18:00」。 */
    fun label(): String = String.format(
        java.util.Locale.US, "%02d:%02d–%02d:%02d", startHour, startMinute, endHour, endMinute
    )
}

/** 一次待触发的提醒。 */
data class Reminder(
    val at: ZonedDateTime,
    val kind: ReminderKind,
    /** 提前提醒时为提前的分钟数，用于拼文案。 */
    val minutes: Int = 0
) {
    /** 用于生成 PendingIntent 的稳定标识（同一天同一时刻同种类唯一）。 */
    fun key(): String = "$kind@${at.toInstant().toEpochMilli()}"
}

data class OffWorkSettings(
    val enabled: Boolean = false,
    val ranges: List<TimeRange> = listOf(TimeRange()),
    val weekdays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val onlyHeadset: Boolean = false,
    val vibrate: Boolean = true,
    val vibrateMode: VibrateMode = VibrateMode.SHORT
) {
    /** 按上班时刻排序的启用段。 */
    fun activeRanges(): List<TimeRange> =
        ranges.filter { it.enabled }.sortedBy { it.startHour * 60 + it.startMinute }

    /**
     * 从 now 出发，找到不早于 now 且落在 weekdays 里的第一个「该时刻」。
     * 纯函数，便于单元测试。
     */
    fun nextAllowed(now: ZonedDateTime, target: ZonedDateTime): ZonedDateTime? {
        var candidate = target
        if (candidate.isBefore(now)) candidate = candidate.plusDays(1)
        repeat(8) {
            if (weekdays.contains(candidate.dayOfWeek.value) && !candidate.isBefore(now)) return candidate
            candidate = candidate.plusDays(1)
        }
        return null
    }

    /**
     * 推导未来 [days] 天内的所有提醒（已按时间升序，且过滤掉早于 now 的）。
     *
     * 休息判断：排序后相邻两段的间隙即为休息区间，
     * 前一段的结束 → BREAK_START，后一段的开始 → BREAK_END。
     */
    fun upcomingReminders(now: ZonedDateTime, days: Int = 3): List<Reminder> {
        if (!enabled) return emptyList()
        val active = activeRanges()
        if (active.isEmpty()) return emptyList()

        // 相邻段之间的间隙：前一段 index → 后一段 index
        val hasBreakAfter = BooleanArray(active.size) { index ->
            index + 1 < active.size
        }

        val result = ArrayList<Reminder>()
        for (dayOffset in 0..days) {
            val day = now.plusDays(dayOffset.toLong())
            if (!weekdays.contains(day.dayOfWeek.value)) continue

            for ((index, range) in active.withIndex()) {
                val start = range.startOn(day)
                val end = if (range.crossesMidnight) range.endOn(day).plusDays(1) else range.endOn(day)

                // 上班前
                if (range.remindBeforeStart && range.advanceStartMinutes > 0) {
                    add(result, now, start.minusMinutes(range.advanceStartMinutes.toLong()),
                        ReminderKind.BEFORE_START, range.advanceStartMinutes)
                }
                // 上班到点
                if (range.remindOnStart) add(result, now, start, ReminderKind.ON_START)

                // 下班前
                if (range.remindBeforeEnd && range.advanceEndMinutes > 0) {
                    add(result, now, end.minusMinutes(range.advanceEndMinutes.toLong()),
                        ReminderKind.BEFORE_END, range.advanceEndMinutes)
                }
                // 下班到点；若后面还有一段，这一时刻同时是「休息开始」
                val hasNext = hasBreakAfter[index]
                if (hasNext && range.remindBreakStart) {
                    add(result, now, end, ReminderKind.BREAK_START)
                } else if (range.remindOnEnd) {
                    add(result, now, end, ReminderKind.ON_END)
                }
                // 休息结束 = 下一段上班时刻
                if (index > 0 && active[index - 1].enabled && range.remindBreakEnd) {
                    add(result, now, start, ReminderKind.BREAK_END)
                }
            }
        }
        return result.sortedBy { it.at.toInstant() }.distinctBy { it.key() }
    }

    /** 下一次提醒（供界面展示）。 */
    fun nextReminder(now: ZonedDateTime): Reminder? = upcomingReminders(now).firstOrNull()

    /** 按 kind 取对应的开关。 */
    private fun add(
        out: MutableList<Reminder>,
        now: ZonedDateTime,
        at: ZonedDateTime,
        kind: ReminderKind,
        minutes: Int = 0
    ) {
        if (at.isBefore(now)) return
        out.add(Reminder(at, kind, minutes))
    }
}

object OffWorkSettingsStorage {
    private const val PREFS = "off_work_settings"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 读取设置。
     *
     * 兼容旧版本（只有 hour/minute/advanceMinutes 的单条配置）：
     * 若从未写过 ranges，则把旧的单条下班时间迁移成第一条时段，
     * 保证升级后用户原来的设置不丢。
     */
    fun load(context: Context): OffWorkSettings {
        val p = prefs(context)
        val weekdaysRaw = p.getString("weekdays", "1,2,3,4,5") ?: "1,2,3,4,5"
        val weekdays = weekdaysRaw.split(',').mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }.toSet()
        return OffWorkSettings(
            enabled = p.getBoolean("enabled", false),
            ranges = loadRanges(p),
            weekdays = weekdays.takeIf { it.isNotEmpty() } ?: setOf(1, 2, 3, 4, 5),
            onlyHeadset = p.getBoolean("onlyHeadset", false),
            vibrate = p.getBoolean("vibrate", true),
            vibrateMode = runCatching {
                VibrateMode.valueOf(p.getString("vibrateMode", VibrateMode.SHORT.name) ?: VibrateMode.SHORT.name)
            }.getOrDefault(VibrateMode.SHORT)
        )
    }

    private fun loadRanges(p: android.content.SharedPreferences): List<TimeRange> {
        val raw = p.getString("ranges", null)
        if (raw.isNullOrBlank()) {
            // 旧配置迁移：老版本只有一个下班时刻
            return listOf(
                TimeRange(
                    startHour = 9,
                    startMinute = 0,
                    endHour = p.getInt("hour", 18).coerceIn(0, 23),
                    endMinute = p.getInt("minute", 0).coerceIn(0, 59),
                    advanceEndMinutes = p.getInt("advanceMinutes", 5).coerceAtLeast(0)
                )
            )
        }
        val parsed = raw.split(';').mapNotNull { decodeRange(it) }
        return parsed.takeIf { it.isNotEmpty() } ?: listOf(TimeRange())
    }

    /** 单条时段编码：字段以 ',' 分隔，顺序固定，便于无依赖解析。 */
    private fun encodeRange(r: TimeRange): String = listOf(
        r.startHour, r.startMinute, r.endHour, r.endMinute,
        if (r.enabled) 1 else 0,
        r.advanceStartMinutes, r.advanceEndMinutes,
        if (r.remindBeforeStart) 1 else 0,
        if (r.remindOnStart) 1 else 0,
        if (r.remindBeforeEnd) 1 else 0,
        if (r.remindOnEnd) 1 else 0,
        if (r.remindBreakStart) 1 else 0,
        if (r.remindBreakEnd) 1 else 0
    ).joinToString(",")

    private fun decodeRange(text: String): TimeRange? {
        val v = text.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (v.size < 13) return null
        return TimeRange(
            startHour = v[0].coerceIn(0, 23),
            startMinute = v[1].coerceIn(0, 59),
            endHour = v[2].coerceIn(0, 23),
            endMinute = v[3].coerceIn(0, 59),
            enabled = v[4] == 1,
            advanceStartMinutes = v[5].coerceAtLeast(0),
            advanceEndMinutes = v[6].coerceAtLeast(0),
            remindBeforeStart = v[7] == 1,
            remindOnStart = v[8] == 1,
            remindBeforeEnd = v[9] == 1,
            remindOnEnd = v[10] == 1,
            remindBreakStart = v[11] == 1,
            remindBreakEnd = v[12] == 1
        )
    }

    fun save(context: Context, settings: OffWorkSettings) {
        prefs(context).edit()
            .putBoolean("enabled", settings.enabled)
            .putString("ranges", settings.ranges.joinToString(";") { encodeRange(it) })
            .putString("weekdays", settings.weekdays.sorted().joinToString(","))
            .putBoolean("onlyHeadset", settings.onlyHeadset)
            .putBoolean("vibrate", settings.vibrate)
            .putString("vibrateMode", settings.vibrateMode.name)
            .apply()
    }
}
