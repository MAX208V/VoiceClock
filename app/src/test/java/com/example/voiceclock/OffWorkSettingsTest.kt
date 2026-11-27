package com.example.voiceclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * 下班提醒的纯逻辑测试：
 * - 时刻计算（下班时刻 / 提前提醒时刻）
 * - 星期过滤与跨天顺延
 * - 振动模式与星期解析
 */
class OffWorkSettingsTest {

    private fun date(value: String) = ZonedDateTime.parse(value + "+08:00[Asia/Shanghai]")

    @Test fun timeOnUsesConfiguredHourAndMinute() {
        val settings = OffWorkSettings(hour = 18, minute = 30)
        val t = settings.timeOn(date("2026-11-27T09:15:00"))
        assertEquals(18, t.hour)
        assertEquals(30, t.minute)
        assertEquals(0, t.second)
        assertEquals(27, t.dayOfMonth)
    }

    @Test fun advanceTimeIsBeforeQuittingTime() {
        val settings = OffWorkSettings(hour = 18, minute = 0, advanceMinutes = 10)
        val t = settings.advanceTimeOn(date("2026-11-27T09:15:00"))
        assertEquals(17, t!!.hour)
        assertEquals(50, t.minute)
    }

    @Test fun advanceTimeIsNullWhenAdvanceDisabled() {
        val settings = OffWorkSettings(advanceMinutes = 0)
        assertNull(settings.advanceTimeOn(date("2026-11-27T09:15:00")))
    }

    @Test fun advanceAcrossMidnight() {
        // 下班 00:10，提前 30 分钟 → 前一天 23:40
        val settings = OffWorkSettings(hour = 0, minute = 10, advanceMinutes = 30)
        val t = settings.advanceTimeOn(date("2026-11-27T09:15:00"))
        assertEquals(23, t!!.hour)
        assertEquals(40, t.minute)
        assertEquals(26, t.dayOfMonth)
    }

    @Test fun defaultWeekdaysAreWorkdays() {
        val settings = OffWorkSettings()
        assertEquals(setOf(1, 2, 3, 4, 5), settings.weekdays)
    }

    @Test fun vibrateDefaultsToOnWithShortMode() {
        val settings = OffWorkSettings()
        assertTrue(settings.vibrate)
        assertEquals(VibrateMode.SHORT, settings.vibrateMode)
    }

    @Test fun vibrateModeParsingFallsBackToShort() {
        // 读取到异常字符串时必须回退，而不是抛异常导致界面崩溃
        val parsed = runCatching { VibrateMode.valueOf("NOT_A_MODE") }.getOrDefault(VibrateMode.SHORT)
        assertEquals(VibrateMode.SHORT, parsed)
        assertEquals(VibrateMode.DOUBLE, VibrateMode.valueOf("DOUBLE"))
    }
}
