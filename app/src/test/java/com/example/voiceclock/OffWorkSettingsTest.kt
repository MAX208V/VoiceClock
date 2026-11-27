package com.example.voiceclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * 上下班提醒的纯逻辑测试：
 * - 多个时段的提醒推导
 * - 相邻时段间隙自动生成「休息开始 / 休息结束」
 * - 各类提醒的独立开关
 * - 星期过滤与跨天顺延
 */
class OffWorkSettingsTest {

    private fun date(value: String) = ZonedDateTime.parse(value + "+08:00[Asia/Shanghai]")

    /** 两个时段：上午班 09:00–12:00、下午班 13:30–18:00。 */
    private fun twoShifts() = OffWorkSettings(
        enabled = true,
        ranges = listOf(
            TimeRange(9, 0, 12, 0, advanceStartMinutes = 10, advanceEndMinutes = 5),
            TimeRange(13, 30, 18, 0, advanceStartMinutes = 10, advanceEndMinutes = 5)
        ),
        weekdays = setOf(1, 2, 3, 4, 5)
    )

    private fun kindsOn(settings: OffWorkSettings, now: ZonedDateTime, day: Int) =
        settings.upcomingReminders(now, days = 0)
            .filter { it.at.dayOfMonth == day }
            .map { it.kind }

    // ---------- 基础推导 ----------

    @Test fun morningShiftProducesAllReminders() {
        val settings = OffWorkSettings(
            enabled = true,
            ranges = listOf(TimeRange(9, 0, 12, 0, advanceStartMinutes = 10, advanceEndMinutes = 5,
                remindBeforeStart = true, remindOnStart = true)),
            weekdays = setOf(1, 2, 3, 4, 5)
        )
        val now = date("2026-11-27T07:00:00")
        val kinds = settings.upcomingReminders(now, days = 0).map { it.kind }
        assertTrue(kinds.contains(ReminderKind.BEFORE_START))
        assertTrue(kinds.contains(ReminderKind.ON_START))
        assertTrue(kinds.contains(ReminderKind.BEFORE_END))
        assertTrue(kinds.contains(ReminderKind.ON_END))
    }

    @Test fun advanceReminderUsesConfiguredMinutes() {
        val settings = OffWorkSettings(
            enabled = true,
            ranges = listOf(TimeRange(9, 0, 18, 0, advanceStartMinutes = 20, remindBeforeStart = true)),
            weekdays = setOf(1, 2, 3, 4, 5)
        )
        val now = date("2026-11-27T07:00:00")
        val before = settings.upcomingReminders(now, days = 0)
            .first { it.kind == ReminderKind.BEFORE_START }
        assertEquals(8, before.at.hour)
        assertEquals(40, before.at.minute)
        assertEquals(20, before.minutes)
    }

    // ---------- 休息：相邻时段间隙 ----------

    @Test fun gapBetweenShiftsBecomesBreak() {
        val settings = twoShifts()
        val now = date("2026-11-27T07:00:00")
        val reminders = settings.upcomingReminders(now, days = 0)

        val breakStart = reminders.first { it.kind == ReminderKind.BREAK_START }
        assertEquals(12, breakStart.at.hour)
        assertEquals(0, breakStart.at.minute)

        val breakEnd = reminders.first { it.kind == ReminderKind.BREAK_END }
        assertEquals(13, breakEnd.at.hour)
        assertEquals(30, breakEnd.at.minute)
    }

    @Test fun lastShiftEndsWithOnEndInsteadOfBreakStart() {
        val settings = twoShifts()
        val now = date("2026-11-27T07:00:00")
        val at18 = settings.upcomingReminders(now, days = 0).filter { it.at.hour == 18 && it.at.minute == 0 }
        assertEquals(1, at18.size)
        assertEquals(ReminderKind.ON_END, at18.first().kind)
    }

    @Test fun singleShiftHasNoBreak() {
        val settings = OffWorkSettings(
            enabled = true,
            ranges = listOf(TimeRange(9, 0, 18, 0)),
            weekdays = setOf(1, 2, 3, 4, 5)
        )
        val kinds = settings.upcomingReminders(date("2026-11-27T07:00:00"), days = 0).map { it.kind }
        assertFalse(kinds.contains(ReminderKind.BREAK_START))
        assertFalse(kinds.contains(ReminderKind.BREAK_END))
    }

    @Test fun breakRemindersCanBeDisabledIndependently() {
        val settings = OffWorkSettings(
            enabled = true,
            ranges = listOf(
                TimeRange(9, 0, 12, 0, remindBreakStart = false, remindBreakEnd = false),
                TimeRange(13, 30, 18, 0, remindBreakStart = false, remindBreakEnd = false)
            ),
            weekdays = setOf(1, 2, 3, 4, 5)
        )
        val kinds = settings.upcomingReminders(date("2026-11-27T07:00:00"), days = 0).map { it.kind }
        assertFalse(kinds.contains(ReminderKind.BREAK_START))
        assertFalse(kinds.contains(ReminderKind.BREAK_END))
    }

    // ---------- 独立开关 ----------

    @Test fun onStartReminderIsOffByDefault() {
        val range = TimeRange()
        assertFalse("上班到点默认不提醒", range.remindOnStart)
        assertTrue("下班到点默认提醒", range.remindOnEnd)
        assertTrue("休息开始默认提醒", range.remindBreakStart)
    }

    @Test fun disablingAllRemindersYieldsNothing() {
        val settings = OffWorkSettings(
            enabled = true,
            ranges = listOf(
                TimeRange(9, 0, 18, 0,
                    remindBeforeStart = false, remindOnStart = false,
                    remindBeforeEnd = false, remindOnEnd = false,
                    remindBreakStart = false, remindBreakEnd = false)
            ),
            weekdays = setOf(1, 2, 3, 4, 5)
        )
        assertTrue(settings.upcomingReminders(date("2026-11-27T07:00:00"), days = 3).isEmpty())
    }

    @Test fun disabledRangeIsIgnored() {
        val settings = OffWorkSettings(
            enabled = true,
            ranges = listOf(
                TimeRange(9, 0, 12, 0, enabled = false),
                TimeRange(13, 30, 18, 0)
            ),
            weekdays = setOf(1, 2, 3, 4, 5)
        )
        val reminders = settings.upcomingReminders(date("2026-11-27T07:00:00"), days = 0)
        assertTrue("停用的时段不应产生 12:00 提醒",
            reminders.none { it.at.hour == 12 && it.at.minute == 0 })
    }

    @Test fun masterSwitchOffYieldsNothing() {
        val settings = twoShifts().copy(enabled = false)
        assertTrue(settings.upcomingReminders(date("2026-11-27T07:00:00"), days = 3).isEmpty())
    }

    // ---------- 星期 ----------

    @Test fun fridayEveningSkipsWeekend() {
        val settings = OffWorkSettings(
            enabled = true,
            ranges = listOf(TimeRange(9, 0, 18, 0)),
            weekdays = setOf(1, 2, 3, 4, 5)
        )
        // 2026-11-27 是周五，19:00 已过 → 下一次落在 11-30 周一
        val next = settings.nextReminder(date("2026-11-27T19:00:00"))
        assertEquals(DayOfWeek.MONDAY, next!!.at.dayOfWeek)
        assertEquals(30, next.at.dayOfMonth)
    }

    @Test fun weekendOnlySchedule() {
        val settings = OffWorkSettings(
            enabled = true,
            ranges = listOf(TimeRange(9, 0, 18, 0)),
            weekdays = setOf(6, 7)
        )
        val next = settings.nextReminder(date("2026-11-27T19:00:00"))
        assertEquals(DayOfWeek.SATURDAY, next!!.at.dayOfWeek)
        assertEquals(28, next.at.dayOfMonth)
    }

    @Test fun emptyWeekdaysNeverFires() {
        val settings = OffWorkSettings(
            enabled = true,
            ranges = listOf(TimeRange(9, 0, 18, 0)),
            weekdays = emptySet()
        )
        assertNull(settings.nextReminder(date("2026-11-27T09:00:00")))
    }

    @Test fun pastRemindersAreFilteredOut() {
        val settings = twoShifts()
        // 上午 10:30 之后，09:00/10:50 之类都该被过滤
        val reminders = settings.upcomingReminders(date("2026-11-27T10:30:00"), days = 0)
        assertTrue(reminders.all { !it.at.isBefore(date("2026-11-27T10:30:00")) })
    }

    // ---------- 其它 ----------

    @Test fun remindersAreSortedAndUnique() {
        val settings = twoShifts()
        val reminders = settings.upcomingReminders(date("2026-11-27T07:00:00"), days = 2)
        val times = reminders.map { it.at.toInstant() }
        assertEquals("必须按时间升序", times.sorted(), times)
        assertEquals("同一时刻同类型不应重复", reminders.size, reminders.distinctBy { it.key() }.size)
    }

    @Test fun crossesMidnightShift() {
        // 22:00–06:00 夜班，结束时刻在次日
        val range = TimeRange(22, 0, 6, 0)
        assertTrue(range.crossesMidnight)
        val settings = OffWorkSettings(enabled = true, ranges = listOf(range), weekdays = setOf(1, 2, 3, 4, 5))
        val onEnd = settings.upcomingReminders(date("2026-11-27T07:00:00"), days = 1)
            .first { it.kind == ReminderKind.ON_END }
        assertEquals(6, onEnd.at.hour)
        assertEquals(28, onEnd.at.dayOfMonth)
    }

    @Test fun labelFormatting() {
        assertEquals("09:00–18:00", TimeRange(9, 0, 18, 0).label())
        assertEquals("13:30–17:45", TimeRange(13, 30, 17, 45).label())
    }

    @Test fun reminderTexts() {
        assertEquals("还有 10 分钟上班", ReminderKind.BEFORE_START.text(10))
        assertEquals("上班时间到了", ReminderKind.ON_START.text(0))
        assertEquals("还有 5 分钟下班", ReminderKind.BEFORE_END.text(5))
        assertEquals("下班时间到了", ReminderKind.ON_END.text(0))
        assertEquals("休息时间到了", ReminderKind.BREAK_START.text(0))
        assertEquals("休息结束，继续上班", ReminderKind.BREAK_END.text(0))
    }

    @Test fun vibrateDefaults() {
        val settings = OffWorkSettings()
        assertTrue(settings.vibrate)
        assertEquals(VibrateMode.SHORT, settings.vibrateMode)
        assertEquals(setOf(1, 2, 3, 4, 5), settings.weekdays)
    }
}
