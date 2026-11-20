package com.example.voiceclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * 校验节气算法。
 *
 * 基准数据来源：美国海军天文台（USNO）四季时刻 API，时间为 UTC。
 * https://aa.usno.navy.mil/api/seasons
 *
 * 算法本身误差在 10 分钟以内，因此断言使用分钟容差，不做精确时刻比较。
 */
class SolarTermsTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun indexOf(name: String): Int {
        val index = SolarTerms.names.indexOf(name)
        assertTrue("节气名称应存在: $name", index >= 0)
        return index
    }

    /** 断言 UTC 时刻与基准相差不超过 toleranceMinutes 分钟。 */
    private fun assertUtcNear(name: String, year: Int, expected: String, toleranceMinutes: Long = 20L) {
        val actual = SolarTerms.termUtc(year, indexOf(name))
        val expectedTime = ZonedDateTime.parse(expected + "Z")
        val diff = Math.abs(ChronoUnit.MINUTES.between(expectedTime, actual))
        assertTrue(
            "$year $name 偏差 ${diff} 分钟，算法=$actual，基准=$expectedTime",
            diff <= toleranceMinutes
        )
    }

    @Test fun namesAreOrderedFromLesserCold() {
        assertEquals(24, SolarTerms.names.size)
        assertEquals("小寒", SolarTerms.names.first())
        assertEquals("冬至", SolarTerms.names.last())
    }

    @Test fun matchesUsnoFor2026() {
        assertUtcNear("春分", 2026, "2026-03-20T14:46:00")
        assertUtcNear("夏至", 2026, "2026-06-21T08:24:00")
        assertUtcNear("秋分", 2026, "2026-09-23T00:05:00")
        assertUtcNear("冬至", 2026, "2026-12-21T20:50:00")
    }

    @Test fun matchesUsnoFor2027() {
        assertUtcNear("春分", 2027, "2027-03-20T20:25:00")
        assertUtcNear("夏至", 2027, "2027-06-21T14:11:00")
        assertUtcNear("秋分", 2027, "2027-09-23T06:02:00")
        assertUtcNear("冬至", 2027, "2027-12-22T02:42:00")
    }

    /**
     * 时区换算回归测试。
     * 2026 冬至 UTC 为 12-21 20:50，北京时间应为 12-22 凌晨 4 点多。
     * 若忘记时区换算，会错成 12-21，进而导致整日错报。
     */
    @Test fun convertsUtcToShanghaiCorrectly() {
        val dongzhi = SolarTerms.term(2026, indexOf("冬至"), shanghai)
        assertEquals("月份", 12, dongzhi.monthValue)
        assertEquals("日期应为 22 日而非 21 日", 22, dongzhi.dayOfMonth)
        assertEquals("小时", 4, dongzhi.hour)
        // 允许 1 分钟误差
        assertTrue("分钟应接近 50，实际 ${dongzhi.minute}", Math.abs(dongzhi.minute - 50) <= 1)
    }

    /** 上午交节的节气，日期不受时区换算影响，用于对称验证。 */
    @Test fun afternoonTermKeepsSameDayInShanghai() {
        val chunfen = SolarTerms.term(2026, indexOf("春分"), shanghai)
        assertEquals(3, chunfen.monthValue)
        assertEquals(20, chunfen.dayOfMonth)
        assertEquals(22, chunfen.hour)
    }

    @Test fun allTermsAreInChronologicalOrderAndReasonablySpaced() {
        for (year in listOf(2024, 2025, 2026, 2027, 2028)) {
            val list = SolarTerms.termsOfYear(year, shanghai)
            assertEquals(24, list.size)
            for (i in 1 until list.size) {
                assertTrue("$year 第 $i 个节气应晚于前一个", list[i].second.isAfter(list[i - 1].second))
                val days = ChronoUnit.DAYS.between(list[i - 1].second, list[i].second)
                assertTrue("$year ${list[i].first} 间距异常: $days 天", days in 13..17)
            }
        }
    }

    @Test fun todayIsReportedOnTheTermDay() {
        val info = SolarTerms.infoFor(LocalDate.of(2026, 11, 22), shanghai)
        assertTrue(info.isToday)
        assertEquals("今天是小雪", info.speech)
        assertEquals("今日小雪", info.title)
    }

    @Test fun tomorrowIsReportedForTheDayBefore() {
        val info = SolarTerms.infoFor(LocalDate.of(2026, 11, 21), shanghai)
        assertFalse(info.isToday)
        assertEquals("明天是小雪", info.speech)
    }

    @Test fun distanceIsReportedOtherwise() {
        val info = SolarTerms.infoFor(LocalDate.of(2026, 11, 15), shanghai)
        assertFalse(info.isToday)
        assertEquals("距离小雪还有 7 天", info.speech)
    }

    @Test fun crossYearBoundaryFindsNextTerm() {
        val info = SolarTerms.infoFor(LocalDate.of(2026, 12, 31), shanghai)
        assertTrue(info.speech.contains("小寒"))
        assertTrue(info.speech.startsWith("距离"))
    }
}
