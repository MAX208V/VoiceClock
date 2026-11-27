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
                remindWorkStart = true, remindBeforeStart = true, remindOnStart = true)),
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
            ranges = listOf(TimeRange(9, 0, 18, 0, advanceStartMinutes = 20,
                remindWorkStart = true, remindBeforeStart = true)),
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

    // ---------- 上班 / 下班提醒总开关 ----------

    @Test fun workStartMasterOffBlocksStartReminders() {
        val settings = OffWorkSettings(
            enabled = true,
            ranges = listOf(TimeRange(9, 0, 18, 0,
                remindWorkStart = false,
                remindBeforeStart = true, remindOnStart = true,
                advanceStartMinutes = 10)),
            weekdays = setOf(1, 2, 3, 4, 5)
        )
        val kinds = settings.upcomingReminders(date("2026-11-27T07:00:00"), days = 0).map { it.kind }
        assertFalse("上班总开关关闭后不应有上班前提醒", kinds.contains(ReminderKind.BEFORE_START))
        assertFalse("上班总开关关闭后不应有上班到点提醒", kinds.contains(ReminderKind.ON_START))
        assertTrue("下班提醒不受影响", kinds.contains(ReminderKind.ON_END))
    }

    @Test fun workEndMasterOffBlocksEndReminders() {
        val settings = OffWorkSettings(
            enabled = true,
            ranges = listOf(TimeRange(9, 0, 18, 0,
                remindWorkEnd = false,
                remindBeforeEnd = true, remindOnEnd = true,
                advanceEndMinutes = 5)),
            weekdays = setOf(1, 2, 3, 4, 5)
        )
        val kinds = settings.upcomingReminders(date("2026-11-27T07:00:00"), days = 0).map { it.kind }
        assertFalse(kinds.contains(ReminderKind.BEFORE_END))
        assertFalse(kinds.contains(ReminderKind.ON_END))
    }

    @Test fun workStartMasterOnWithDetailsOffStillSilent() {
        val settings = OffWorkSettings(
            enabled = true,
            ranges = listOf(TimeRange(9, 0, 18, 0,
                remindWorkStart = true,
                remindBeforeStart = false, remindOnStart = false)),
            weekdays = setOf(1, 2, 3, 4, 5)
        )
        val kinds = settings.upcomingReminders(date("2026-11-27T07:00:00"), days = 0).map { it.kind }
        assertFalse(kinds.contains(ReminderKind.ON_START))
        assertFalse(kinds.contains(ReminderKind.BEFORE_START))
    }

    // ---------- 自定义休息时间 ----------

    @Test fun customBreakOverridesNaturalGap() {
        // 两段之间自然间隙是 12:00–13:30，但自定义成 12:10–13:00
        val settings = OffWorkSettings(
            enabled = true,
            ranges = listOf(
                TimeRange(9, 0, 12, 0, breakStartMinuteOfDay = 12 * 60 + 10, breakEndMinuteOfDay = 13 * 60),
                TimeRange(13, 30, 18, 0)
            ),
            weekdays = setOf(1, 2, 3, 4, 5)
        )
        val reminders = settings.upcomingReminders(date("2026-11-27T07:00:00"), days = 0)
        val breakStart = reminders.first { it.kind == ReminderKind.BREAK_START }
        assertEquals(12, breakStart.at.hour)
        assertEquals(10, breakStart.at.minute)
        val breakEnd = reminders.first { it.kind == ReminderKind.BREAK_END }
        assertEquals(13, breakEnd.at.hour)
        assertEquals(0, breakEnd.at.minute)
        // 自定义生效后不应再出现自然间隙推导出的 13:30 休息结束
        assertEquals("休息相关提醒不应重复", 2,
            reminders.count { it.kind == ReminderKind.BREAK_START || it.kind == ReminderKind.BREAK_END })
    }

    @Test fun hasCustomBreakFlag() {
        assertFalse(TimeRange().hasCustomBreak)
        assertTrue(TimeRange(breakStartMinuteOfDay = 720, breakEndMinuteOfDay = 780).hasCustomBreak)
        // 只填一半不算有效自定义
        assertFalse(TimeRange(breakStartMinuteOfDay = 720, breakEndMinuteOfDay = -1).hasCustomBreak)
    }

    // ---------- 编码向后兼容 ----------

    @Test fun decodeOldFormatUsesDefaults() {
        // 旧版只有 13 个字段：末尾 4 个新字段应回落到默认值
        val old = "9,0,18,0,1,10,5,0,0,1,1,1,1"
        val decoded = OffWorkSettingsStorage.decodeRangeForTest(old)
        assertTrue(decoded != null)
        assertEquals(false, decoded!!.remindWorkStart)
        assertEquals(true, decoded.remindWorkEnd)
        assertFalse(decoded.hasCustomBreak)
    }

    @Test fun encodeDecodeRoundTrip() {
        val origin = TimeRange(8, 30, 17, 45,
            enabled = false, advanceStartMinutes = 15, advanceEndMinutes = 3,
            remindWorkStart = true, remindOnStart = true,
            remindBeforeEnd = false, remindOnEnd = false,
            remindBreakStart = false, remindBreakEnd = true,
            breakStartMinuteOfDay = 11 * 60 + 30, breakEndMinuteOfDay = 12 * 60 + 45)
        val text = OffWorkSettingsStorage.encodeRangeForTest(origin)
        val back = OffWorkSettingsStorage.decodeRangeForTest(text)
        assertEquals(origin, back)
    }

    // ---------- 休息前提醒 / 任意分钟数 ----------

    @Test fun beforeBreakUsesNaturalGapStart() {
        val settings = OffWorkSettings(
            enabled = true,
            ranges = listOf(
                TimeRange(9, 0, 12, 0, remindBeforeBreak = true, advanceBreakMinutes = 7),
                TimeRange(13, 30, 18, 0)
            ),
            weekdays = setOf(1, 2, 3, 4, 5)
        )
        val beforeBreak = settings.upcomingReminders(date("2026-11-27T07:00:00"), days = 0)
            .first { it.kind == ReminderKind.BEFORE_BREAK }
        // 自然间隙休息开始 = 12:00，提前 7 分钟 = 11:53
        assertEquals(11, beforeBreak.at.hour)
        assertEquals(53, beforeBreak.at.minute)
        assertEquals(7, beforeBreak.minutes)
    }

    @Test fun beforeBreakUsesCustomBreakStart() {
        val settings = OffWorkSettings(
            enabled = true,
            ranges = listOf(
                TimeRange(9, 0, 12, 0,
                    remindBeforeBreak = true, advanceBreakMinutes = 20,
                    breakStartMinuteOfDay = 12 * 60 + 30,
                    breakEndMinuteOfDay = 13 * 60 + 30),
                TimeRange(13, 30, 18, 0)
            ),
            weekdays = setOf(1, 2, 3, 4, 5)
        )
        val beforeBreak = settings.upcomingReminders(date("2026-11-27T07:00:00"), days = 0)
            .first { it.kind == ReminderKind.BEFORE_BREAK }
        // 自定义休息开始 12:30，提前 20 分钟 = 12:10
        assertEquals(12, beforeBreak.at.hour)
        assertEquals(10, beforeBreak.at.minute)
    }

    @Test fun beforeBreakWhenLastRangeProducesNothing() {
        val settings = OffWorkSettings(
            enabled = true,
            ranges = listOf(TimeRange(9, 0, 18, 0, remindBeforeBreak = true, advanceBreakMinutes = 5)),
            weekdays = setOf(1, 2, 3, 4, 5)
        )
        val kinds = settings.upcomingReminders(date("2026-11-27T07:00:00"), days = 0).map { it.kind }
        assertFalse("单段没有休息，不应有休息前提醒", kinds.contains(ReminderKind.BEFORE_BREAK))
    }

    @Test fun arbitraryAdvanceMinutesAreHonoured() {
        // 非整档的分钟数（如 7、13、23）必须原样生效
        val settings = OffWorkSettings(
            enabled = true,
            ranges = listOf(TimeRange(9, 0, 18, 0,
                remindWorkStart = true, remindBeforeStart = true, advanceStartMinutes = 23,
                remindBeforeEnd = true, advanceEndMinutes = 13)),
            weekdays = setOf(1, 2, 3, 4, 5)
        )
        val reminders = settings.upcomingReminders(date("2026-11-27T07:00:00"), days = 0)
        val beforeStart = reminders.first { it.kind == ReminderKind.BEFORE_START }
        assertEquals(8, beforeStart.at.hour)
        assertEquals(37, beforeStart.at.minute)
        val beforeEnd = reminders.first { it.kind == ReminderKind.BEFORE_END }
        assertEquals(17, beforeEnd.at.hour)
        assertEquals(47, beforeEnd.at.minute)
    }

    @Test fun beforeBreakText() {
        assertEquals("还有 8 分钟休息", ReminderKind.BEFORE_BREAK.text(8))
    }

    @Test fun encodeDecodeKeepsBeforeBreakFields() {
        val origin = TimeRange(9, 0, 12, 0,
            remindBeforeBreak = true, advanceBreakMinutes = 17,
            breakStartMinuteOfDay = 12 * 60, breakEndMinuteOfDay = 13 * 60)
        val back = OffWorkSettingsStorage.decodeRangeForTest(
            OffWorkSettingsStorage.encodeRangeForTest(origin)
        )
        assertEquals(origin, back)
    }

    @Test fun advanceMinutesAreClampedToUpperBound() {
        // 超出上限的值在解码时应被钳制，避免异常数据导致离谱排程
        val encoded = "9,0,18,0,1,999,999,0,0,1,1,1,1,0,1,-1,-1,0,999"
        val decoded = OffWorkSettingsStorage.decodeRangeForTest(encoded)
        assertEquals(MAX_ADVANCE_MINUTES, decoded!!.advanceStartMinutes)
        assertEquals(MAX_ADVANCE_MINUTES, decoded.advanceEndMinutes)
        assertEquals(MAX_ADVANCE_MINUTES, decoded.advanceBreakMinutes)
    }

    @Test fun vibrateDefaults() {
        val settings = OffWorkSettings()
        assertTrue(settings.vibrate)
        assertEquals(VibrateMode.SHORT, settings.vibrateMode)
        assertEquals(setOf(1, 2, 3, 4, 5), settings.weekdays)
    }
}
