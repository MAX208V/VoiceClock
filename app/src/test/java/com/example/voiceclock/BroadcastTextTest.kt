package com.example.voiceclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * 校验播报文案拼装与细粒度开关。
 *
 * 重点：
 * - 时间可按开关只播时间、只播日期、只播星期
 * - 天气可按开关只播气温
 */
class BroadcastTextTest {

    private fun time(value: String) = ZonedDateTime.parse(value + "+08:00[Asia/Shanghai]")

    private fun weather(
        temp: Double = 12.4,
        feels: Double = 10.8,
        code: Int = 2,
        precip: Double = 0.0,
        isDay: Boolean = true
    ) = WeatherClient.Weather(
        temperature = temp,
        apparentTemperature = feels,
        humidity = 68,
        precipitation = precip,
        weatherCode = code,
        windSpeed = 9.0,
        isDay = isDay
    )

    // ---------- 时间：全开 ----------

    @Test fun timeAllEnabled() {
        val text = BroadcastText.timeSentence(time("2026-11-20T13:01:00"), Extras())
        assertEquals("现在是11月20日 星期五 下午1点1分", text)
    }

    @Test fun zeroMinuteReadsAsZheng() {
        val text = BroadcastText.timeSentence(time("2026-11-20T12:00:00"), Extras())
        assertEquals("现在是11月20日 星期五 中午12点整", text)
    }

    // ---------- 时间：只播当前时间，不播日期星期 ----------

    @Test fun clockOnlySkipsDateAndWeekday() {
        val extras = Extras(speakDate = false, speakWeekday = false, speakPeriod = true)
        val text = BroadcastText.timeSentence(time("2026-11-20T13:01:00"), extras)
        assertEquals("现在是下午1点1分", text)
        assertFalse(text!!.contains("11月"))
        assertFalse(text.contains("星期"))
    }

    @Test fun clockOnlyWithoutPeriodUses24Hour() {
        val extras = Extras(speakDate = false, speakWeekday = false, speakPeriod = false)
        val text = BroadcastText.timeSentence(time("2026-11-20T13:01:00"), extras)
        assertEquals("现在是13点1分", text)
    }

    @Test fun dateAndWeekdayOnlyWithoutClock() {
        val extras = Extras(speakClock = false)
        val text = BroadcastText.timeSentence(time("2026-11-20T13:01:00"), extras)
        assertEquals("现在是11月20日 星期五", text)
    }

    @Test fun allTimePartsDisabledReturnsNull() {
        val extras = Extras(speakClock = false, speakDate = false, speakWeekday = false, speakPeriod = false)
        assertNull(BroadcastText.timeSentence(time("2026-11-20T13:01:00"), extras))
        assertFalse(extras.hasTimeContent)
    }

    // ---------- 天气：只播气温 ----------

    @Test fun weatherTemperatureOnly() {
        val extras = Extras(speakWeatherText = false, speakApparent = false, speakPrecipitation = false)
        val text = BroadcastText.weatherSentence(weather(), extras, null)
        assertEquals("天气，气温 12 摄氏度", text)
        assertFalse(text!!.contains("多云"))
        assertFalse(text.contains("体感"))
    }

    @Test fun weatherConditionOnly() {
        val extras = Extras(speakTemperature = false, speakApparent = false, speakPrecipitation = false)
        val text = BroadcastText.weatherSentence(weather(), extras, null)
        assertEquals("天气，多云", text)
    }

    @Test fun weatherAllFieldsWithPlace() {
        val extras = Extras()
        val text = BroadcastText.weatherSentence(weather(precip = 2.5), extras, "上海")
        assertEquals("天气，上海，多云，气温 12 摄氏度，体感 10 度，降水量 2.5 毫米", text)
    }

    @Test fun weatherWeatherDisabledEntirely() {
        val extras = Extras(weatherEnabled = false)
        assertNull(BroadcastText.weatherSentence(weather(), extras, null))
        assertFalse(extras.hasWeatherContent)
    }

    @Test fun weatherSkipsApparentWhenSameAsTemperature() {
        val extras = Extras(speakWeatherText = false, speakPrecipitation = false)
        val text = BroadcastText.weatherSentence(weather(temp = 12.0, feels = 12.0), extras, null)
        assertEquals("天气，气温 12 摄氏度", text)
        assertFalse(text!!.contains("体感"))
    }

    @Test fun weatherNullReturnsNull() {
        assertNull(BroadcastText.weatherSentence(null, Extras(), null))
    }

    // ---------- 整句拼装 ----------

    @Test fun buildJoinsAllSectionsWithFullStop() {
        val text = BroadcastText.build("现在是下午1点1分", "天气，多云", "明天是小雪")
        assertEquals("现在是下午1点1分。天气，多云。明天是小雪。", text)
    }

    @Test fun buildSkipsBlankSections() {
        assertEquals("现在是下午1点1分。", BroadcastText.build("现在是下午1点1分", null, "  "))
        assertEquals("", BroadcastText.build(null, null, null))
    }

    @Test fun buildDoesNotDuplicateFullStop() {
        assertEquals("现在是下午1点1分。天气，多云。", BroadcastText.build("现在是下午1点1分。", "天气，多云。", null))
    }

    @Test fun simpleWeatherSentenceWrapsText() {
        assertEquals("天气，多云，气温 12 摄氏度", BroadcastText.simpleWeatherSentence("多云，气温 12 摄氏度"))
    }

    @Test fun periodOfCoversAllDayParts() {
        assertEquals("凌晨", BroadcastText.periodOf(3))
        assertEquals("上午", BroadcastText.periodOf(9))
        assertEquals("中午", BroadcastText.periodOf(12))
        assertEquals("下午", BroadcastText.periodOf(15))
        assertEquals("晚上", BroadcastText.periodOf(21))
    }

    @Test fun shortTimeIsCompact() {
        assertEquals("现在是13点5分", BroadcastText.shortTime(time("2026-11-20T13:05:00")))
        assertEquals("现在是7点整", BroadcastText.shortTime(time("2026-11-20T07:00:00")))
    }
}
