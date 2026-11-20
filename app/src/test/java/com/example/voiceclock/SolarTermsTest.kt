package com.example.voiceclock

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 校验节气算法与已知天文年历一致（东八区）。 */
class SolarTermsTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun date(name: String, year: Int): LocalDate {
        val index = SolarTerms.names.indexOf(name)
        assertTrue("节气名称应存在: $name", index >= 0)
        return SolarTerms.term(year, index, zone).toLocalDate()
    }

    @Test fun namesAreOrderedFromLesserCold() {
        assertEquals(24, SolarTerms.names.size)
        assertEquals("小寒", SolarTerms.names.first())
        assertEquals("冬至", SolarTerms.names.last())
    }

    @Test fun knownDates2026() {
        // 数据来源：2026 年中国天文年历（东八区）
        assertEquals(LocalDate.of(2026, 1, 5), date("小寒", 2026))
        assertEquals(LocalDate.of(2026, 2, 4), date("立春", 2026))
        assertEquals(LocalDate.of(2026, 3, 20), date("春分", 2026))
        assertEquals(LocalDate.of(2026, 4, 5), date("清明", 2026))
        assertEquals(LocalDate.of(2026, 6, 21), date("夏至", 2026))
        assertEquals(LocalDate.of(2026, 9, 23), date("秋分", 2026))
        assertEquals(LocalDate.of(2026, 11, 7), date("立冬", 2026))
        assertEquals(LocalDate.of(2026, 11, 22), date("小雪", 2026))
        assertEquals(LocalDate.of(2026, 12, 22), date("冬至", 2026))
    }

    @Test fun knownDatesAcrossYears() {
        assertEquals(LocalDate.of(2024, 2, 4), date("立春", 2024))
        assertEquals(LocalDate.of(2024, 12, 21), date("冬至", 2024))
        assertEquals(LocalDate.of(2025, 2, 3), date("立春", 2025))
        assertEquals(LocalDate.of(2025, 12, 21), date("冬至", 2025))
        assertEquals(LocalDate.of(2027, 3, 21), date("春分", 2027))
        assertEquals(LocalDate.of(2027, 12, 22), date("冬至", 2027))
    }

    @Test fun termsAreStrictlyIncreasingWithinYear() {
        for (year in listOf(2024, 2025, 2026, 2027, 2028)) {
            val list = SolarTerms.termsOfYear(year, zone)
            assertEquals(24, list.size)
            for (i in 1 until list.size) {
                assertTrue("$year 第 $i 个节气应晚于前一个", list[i].second.isAfter(list[i - 1].second))
            }
            // 每个节气相隔约 14-16 天
            for (i in 1 until list.size) {
                val days = java.time.temporal.ChronoUnit.DAYS.between(list[i - 1].second, list[i].second)
                assertTrue("$year ${list[i].first} 间距异常: $days", days in 13..17)
            }
        }
    }

    @Test fun todayIsReportedOnTheTermDay() {
        val info = SolarTerms.infoFor(LocalDate.of(2026, 11, 22), zone)
        assertTrue(info.isToday)
        assertEquals("今天是小雪", info.speech)
        assertEquals("今日小雪", info.title)
    }

    @Test fun tomorrowIsReportedForTheDayBefore() {
        val info = SolarTerms.infoFor(LocalDate.of(2026, 11, 21), zone)
        assertFalse(info.isToday)
        assertEquals("明天是小雪", info.speech)
    }

    @Test fun distanceIsReportedOtherwise() {
        val info = SolarTerms.infoFor(LocalDate.of(2026, 11, 15), zone)
        assertFalse(info.isToday)
        assertEquals("距离小雪还有 7 天", info.speech)
    }

    @Test fun crossYearBoundaryFindsNextTerm() {
        // 2026-12-31 之后的下一个节气是 2027 年的小寒
        val info = SolarTerms.infoFor(LocalDate.of(2026, 12, 31), zone)
        assertTrue(info.speech.contains("小寒"))
        assertTrue(info.speech.startsWith("距离"))
    }
}
