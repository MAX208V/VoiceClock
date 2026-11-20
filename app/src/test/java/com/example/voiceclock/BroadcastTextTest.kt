package com.example.voiceclock

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 校验天气解析与播报文案拼装。 */
class BroadcastTextTest {
    private fun time(value: String) = ZonedDateTime.parse(value + "+08:00[Asia/Shanghai]")

    // ---------- 天气解析 ----------

    private val sample = """
    {"latitude":31.25,"longitude":121.5,"timezone":"Asia/Shanghai",
     "current":{"time":"2026-11-20T13:00","temperature_2m":12.4,"relative_humidity_2m":68,
     "apparent_temperature":10.8,"precipitation":0.0,"weather_code":2,"wind_speed_10m":9.2,"is_day":1}}
    """

    @Test fun parsesOpenMeteoCurrentBlock() {
        val weather = WeatherClient.parse(sample)
        assertEquals(12.4, weather.temperature, 0.001)
        assertEquals(10.8, weather.apparentTemperature, 0.001)
        assertEquals(68, weather.humidity)
        assertEquals(2, weather.weatherCode)
        assertEquals("多云", weather.description)
        assertTrue(weather.isDay)
    }

    @Test fun parsesEvenWhenOptionalFieldsAreMissing() {
        val minimal = """{"current":{"temperature_2m":5.0}}"""
        val weather = WeatherClient.parse(minimal)
        assertEquals(5.0, weather.temperature, 0.001)
        assertEquals(5.0, weather.apparentTemperature, 0.001)
        assertEquals(0, weather.humidity)
        assertEquals(0.0, weather.precipitation, 0.001)
        assertTrue(weather.isDay)
    }

    @Test fun describesAllWmoCodes() {
        assertEquals("晴", WeatherClient.describe(0))
        assertEquals("小雨", WeatherClient.describe(61))
        assertEquals("大雪", WeatherClient.describe(75))
        assertEquals("雷阵雨", WeatherClient.describe(95))
        assertEquals("未知天气", WeatherClient.describe(1234))
    }

    @Test fun buildsReadableWeatherSpeech() {
        val weather = WeatherClient.parse(sample)
        assertEquals("上海，多云，气温 12 摄氏度，体感 10 度", weather.speech("上海"))
    }

    @Test fun weatherSpeechOmitsPlaceWhenBlank() {
        val weather = WeatherClient.parse(sample)
        assertTrue(weather.speech(null).startsWith("多云"))
        assertTrue(weather.speech("").startsWith("多云"))
    }

    @Test fun weatherSpeechMentionsNightRainAndPrecipitation() {
        val night = """{"current":{"temperature_2m":8.0,"apparent_temperature":8.0,"precipitation":2.5,"weather_code":63,"is_day":0}}"""
        val speech = WeatherClient.parse(night).speech(null)
        // 降水类天气不应加白天/夜间修饰
        assertEquals("中雨，气温 8 摄氏度，降水量 2.5 毫米", speech)
    }

    @Test fun clearNightWeatherIsMarked() {
        val night = """{"current":{"temperature_2m":18.0,"apparent_temperature":18.0,"weather_code":0,"is_day":0}}"""
        assertEquals("晴（夜间），气温 18 摄氏度", WeatherClient.parse(night).speech(null))
    }

    // ---------- 文案拼装 ----------

    @Test fun timeSentenceIncludesDateWeekdayAndPeriod() {
        assertEquals("现在时间，11月20日 星期五 下午1点1分。", BroadcastText.timeSentence(time("2026-11-20T13:01:00")))
        assertEquals("现在时间，11月20日 星期五 中午12点整。", BroadcastText.timeSentence(time("2026-11-20T12:00:00")))
        assertEquals("现在时间，11月20日 星期五 凌晨5点30分。", BroadcastText.timeSentence(time("2026-11-20T05:30:00")))
        assertEquals("现在时间，11月20日 星期五 晚上9点整。", BroadcastText.timeSentence(time("2026-11-20T21:00:00")))
        assertEquals("现在时间，11月20日 星期五 上午0点15分。", BroadcastText.timeSentence(time("2026-11-20T00:15:00")))
    }

    @Test fun fullBroadcastJoinsAllThreeParts() {
        val text = BroadcastText.build(
            BroadcastText.Parts(time("2026-11-20T13:01:00"), "多云，气温 12 摄氏度", "明天是小雪")
        )
        assertEquals("现在时间，11月20日 星期五 下午1点1分。天气，多云，气温 12 摄氏度。明天是小雪。", text)
    }

    @Test fun missingPartsAreSkipped() {
        assertEquals(
            "现在时间，11月20日 星期五 下午1点1分。",
            BroadcastText.build(BroadcastText.Parts(time("2026-11-20T13:01:00"), null, null))
        )
        assertEquals(
            "现在时间，11月20日 星期五 下午1点1分。",
            BroadcastText.build(BroadcastText.Parts(time("2026-11-20T13:01:00"), "  ", ""))
        )
    }

    @Test fun shortTimeIsCompact() {
        assertEquals("现在时间，13点5分", BroadcastText.shortTime(time("2026-11-20T13:05:00")))
        assertEquals("现在时间，7点整", BroadcastText.shortTime(time("2026-11-20T07:00:00")))
    }
}
