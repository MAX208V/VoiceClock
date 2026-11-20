package com.example.voiceclock

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.floor
import kotlin.math.sin

/**
 * 二十四节气计算。
 *
 * 采用 Meeus《Astronomical Algorithms》的太阳视黄经近似公式（精度约 0.01°，
 * 换算到节气时刻误差通常在 1 分钟以内），对 1900-2100 年有效。
 * 节气定义为太阳视黄经达到 15° 的整倍数：小寒 285°、大寒 300°……立春 315°……冬至 270°。
 *
 * 全部为纯函数，不依赖 Android 框架，可直接单元测试。
 */
object SolarTerms {

    /** 按时间顺序排列的二十四节气名称（从小寒开始）。 */
    val names = listOf(
        "小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明", "谷雨",
        "立夏", "小满", "芒种", "夏至", "小暑", "大暑", "立秋", "处暑",
        "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至"
    )

    private const val DEG = Math.PI / 180.0

    /** 第 index 个节气（0=小寒）对应的太阳视黄经度数。 */
    private fun targetDegree(index: Int): Double = norm360(285.0 + index * 15.0)

    private fun norm360(value: Double): Double {
        var v = value % 360.0
        if (v < 0) v += 360.0
        return v
    }

    /** 儒略日（UT）。ut 为当日零点起算的小时数。 */
    private fun toJulianDay(year: Int, month: Int, day: Int, ut: Double): Double {
        var y = year
        var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5 + ut / 24.0
    }

    /** 太阳视黄经（度），jd 为力学时儒略日（此处近似使用 UTC）。 */
    private fun sunLongitude(jd: Double): Double {
        val t = (jd - 2451545.0) / 36525.0
        val l0 = 280.46646 + t * (36000.76983 + t * 0.0003032)
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val mr = m * DEG
        val c = sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * mr) * (0.019993 - 0.000101 * t) +
            sin(3 * mr) * 0.000289
        val omega = 125.04 - 1934.136 * t
        return norm360(l0 + c - 0.00569 - 0.00478 * sin(omega * DEG))
    }

    private fun diff(jd: Double, target: Double): Double {
        var d = sunLongitude(jd) - target
        while (d > 180.0) d -= 360.0
        while (d < -180.0) d += 360.0
        return d
    }

    /** 求某年太阳视黄经等于 target 的时刻（UTC 儒略日）。 */
    private fun solve(year: Int, target: Double): Double {
        val lo = toJulianDay(year, 1, 1, 0.0)
        val hi = toJulianDay(year + 1, 1, 1, 0.0)
        val steps = 1460
        var prevJd = lo
        var prev = diff(lo, target)
        for (i in 1..steps) {
            val jd = lo + (hi - lo) * i / steps
            val cur = diff(jd, target)
            if (prev <= 0.0 && cur > 0.0) {
                var a = prevJd
                var b = jd
                repeat(60) {
                    val mid = (a + b) / 2.0
                    if (diff(mid, target) <= 0.0) a = mid else b = mid
                }
                return (a + b) / 2.0
            }
            prevJd = jd
            prev = cur
        }
        // 理论上不会发生；返回年初以避免异常。
        return lo
    }

    /** 儒略日转本地时间（指定时区）。 */
    private fun toZoned(jd: Double, zone: ZoneId): ZonedDateTime {
        var z = floor(jd + 0.5)
        val f = jd + 0.5 - z
        var a = z
        if (z >= 2299161.0) {
            val alpha = floor((z - 1867216.25) / 36524.25)
            a = z + 1 + alpha - floor(alpha / 4.0)
        }
        val b = a + 1524
        val c = floor((b - 122.1) / 365.25)
        val d = floor(365.25 * c)
        val e = floor((b - d) / 30.6001)
        val day = b - d - floor(30.6001 * e) + f
        val month = if (e < 14) (e - 1).toInt() else (e - 13).toInt()
        val year = if (month > 2) (c - 4716).toInt() else (c - 4715).toInt()
        val dayInt = floor(day).toInt()
        val dayFraction = day - dayInt
        val startOfDay = LocalDate.of(year, month, dayInt).atStartOfDay(zone)
        return startOfDay.plusSeconds(Math.round(dayFraction * 86400.0))
    }

    /** 某年第 index 个节气（0=小寒）的时刻。 */
    fun term(year: Int, index: Int, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
        toZoned(solve(year, targetDegree(index)), zone)

    /** 某年全部 24 个节气，按时间升序。 */
    fun termsOfYear(year: Int, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, ZonedDateTime>> =
        (0 until 24).map { names[it] to term(year, it, zone) }

    /** 节气播报信息。 */
    data class Info(
        /** 用于播报的完整语句，例如「明天是小雪」或「距离冬至还有 7 天」。 */
        val speech: String,
        /** 界面展示用的简短标题，例如「明日小雪」。 */
        val title: String,
        /** 今天是否为节气当天。 */
        val isToday: Boolean
    )

    /**
     * 生成给定日期对应的节气播报。
     *
     * 规则：今天交节 → 「今天是 XX」；明天交节 → 「明天是 XX」；
     * 否则播报距离下一个节气还有多少天。
     */
    fun infoFor(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Info {
        // 取前后两年，覆盖 12 月底到次年 1 月初的跨年情况。
        val all = (date.year - 1..date.year + 1)
            .flatMap { termsOfYear(it, zone) }
            .sortedBy { it.second }

        val today = all.firstOrNull { it.second.toLocalDate() == date }
        if (today != null) {
            return Info("今天是${today.first}", "今日${today.first}", true)
        }

        val next = all.firstOrNull { it.second.toLocalDate().isAfter(date) }
            ?: return Info("", "", false)
        val days = java.time.temporal.ChronoUnit.DAYS.between(date, next.second.toLocalDate()).toInt()
        return when (days) {
            1 -> Info("明天是${next.first}", "明日${next.first}", false)
            else -> Info("距离${next.first}还有 $days 天", "距${next.first} $days 天", false)
        }
    }
}
