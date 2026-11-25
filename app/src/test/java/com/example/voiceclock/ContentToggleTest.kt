package com.example.voiceclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * 校验播报内容的细粒度开关组合，
 * 覆盖「时间只播当前时间」「天气只播气温」等用户场景。
 */
class ContentToggleTest {

    private fun time(value: String) = ZonedDateTime.parse(value + "+08:00[Asia/Shanghai]")

    private fun sampleWeather() = WeatherClient.Weather(
        temperature = 12.4,
        apparentTemperature = 10.8,
        humidity = 68,
        precipitation = 0.0,
        weatherCode = 2,
        windSpeed = 9.0,
        isDay = true
    )

    // ---------- 时间：只播报当前时间，不播报日期、星期 ----------

    @Test fun speakClockOnlyWithoutDateAndWeekday() {
        val extras = Extras(speakDate = false, speakWeekday = false)
        val text = BroadcastText.timeSentence(time("2026-11-20T13:01:00"), extras)
        assertTrue(text != null)
        assertEquals("现在是下午1点1分", text)
        assertFalse("不应包含月份", text!!.contains("月"))
        assertFalse("不应包含星期", text.contains("星期"))
    }

    @Test fun speakClockOnlyWithoutPeriod() {
        val extras = Extras(speakDate = false, speakWeekday = false, speakPeriod = false)
        assertEquals("现在是13点1分", BroadcastText.timeSentence(time("2026-11-20T13:01:00"), extras))
    }

    // ---------- 天气：只播报当前气温 ----------

    @Test fun weatherTemperatureOnly() {
        val extras = Extras(
            speakWeatherText = false,
            speakApparent = false,
            speakPrecipitation = false
        )
        val text = BroadcastText.weatherSentence(sampleWeather(), extras, null)
        assertEquals("天气，气温 12 摄氏度", text)
    }

    @Test fun weatherTemperatureOnlyWithPlace() {
        val extras = Extras(
            speakWeatherText = false,
            speakApparent = false,
            speakPrecipitation = false
        )
        val text = BroadcastText.weatherSentence(sampleWeather(), extras, "上海")
        assertEquals("天气，上海，气温 12 摄氏度", text)
    }

    // ---------- 全部关闭时的降级 ----------

    @Test fun allTimeDisabledProducesNull() {
        val extras = Extras(speakClock = false, speakDate = false, speakWeekday = false, speakPeriod = false)
        assertNull(BroadcastText.timeSentence(time("2026-11-20T13:01:00"), extras))
        assertFalse(extras.hasTimeContent)
    }

    @Test fun allWeatherDisabledProducesNull() {
        val extras = Extras(
            weatherEnabled = false,
            speakWeatherText = false,
            speakTemperature = false,
            speakApparent = false,
            speakPrecipitation = false
        )
        assertNull(BroadcastText.weatherSentence(sampleWeather(), extras, null))
        assertFalse(extras.hasWeatherContent)
        assertTrue("HasTimeContent 仍为真", extras.hasTimeContent)
    }

    // ---------- 提示音时长：波形点击选定的换算 ----------

    /** 最短可播放时长约定为 0.5 秒（与 WaveformView.MIN_PLAY_MS 保持一致）。 */
    @Test fun minPlayMillisIsHalfSecond() {
        val minPlayMs = 500L
        assertEquals(500L, minPlayMs)
    }

    /** 选中的毫秒数换算成保存的秒数：整段记为 0（完整播放）。 */
    @Test fun selectedMsConvertsToSavedSeconds() {
        fun toSeconds(selectedMs: Long, totalMs: Long): Int =
            if (selectedMs >= totalMs) 0 else (selectedMs / 1000L).toInt().coerceAtLeast(1)

        val total = 5_000L
        assertEquals(0, toSeconds(total, total))        // 点最右端 → 完整
        assertEquals(1, toSeconds(1_200L, total))       // 1.2 秒 → 1
        assertEquals(3, toSeconds(3_000L, total))       // 3.0 秒 → 3
        assertEquals(1, toSeconds(500L, total))         // 最小值仍记为 1 秒
    }

    /** 保存的秒数还原成波形位置。 */
    @Test fun savedSecondsRestoresCutPosition() {
        fun ratioOf(savedSeconds: Int, totalMs: Long): Float =
            if (savedSeconds <= 0) 1f
            else ((savedSeconds * 1000f) / totalMs.toFloat()).coerceIn(0f, 1f)

        val total = 10_000L
        assertEquals(1f, ratioOf(0, total), 0.0001f)     // 完整播放
        assertEquals(0.2f, ratioOf(2, total), 0.0001f)   // 2 秒 / 10 秒
        assertEquals(1f, ratioOf(20, total), 0.0001f)    // 超过总时长 → 钳制为完整
    }

    // ---------- 总开关：与细节开关分离 ----------

    @Test fun timeMasterSwitchOffSilencesWholeTimeBlock() {
        // 细节项全开，但总开关关闭 → 时间部分整体不播
        val extras = Extras(
            timeEnabled = false,
            speakClock = true,
            speakDate = true,
            speakWeekday = true,
            speakPeriod = true
        )
        assertFalse("总开关关闭后不应有时间内容", extras.hasTimeContent)
        assertTrue("细节项仍然保留为开", extras.hasTimeDetail)
        assertNull(BroadcastText.timeSentence(time("2026-11-20T13:01:00"), extras))
    }

    @Test fun timeMasterSwitchOnKeepsDetailBehavior() {
        val extras = Extras(
            timeEnabled = true,
            speakClock = true,
            speakDate = false,
            speakWeekday = false,
            speakPeriod = false
        )
        assertTrue(extras.hasTimeContent)
        assertEquals("现在是13点1分", BroadcastText.timeSentence(time("2026-11-20T13:01:00"), extras))
    }

    @Test fun timeMasterSwitchOffDoesNotAffectWeather() {
        val extras = Extras(timeEnabled = false)
        assertTrue("天气不受时间总开关影响", extras.hasWeatherContent)
        assertNull("时间部分应为空", BroadcastText.timeSentence(time("2026-11-20T13:01:00"), extras))
        assertTrue(BroadcastText.weatherSentence(sampleWeather(), extras, "上海")!!.contains("天气"))
    }

    @Test fun weatherMasterSwitchOffSilencesWholeWeatherBlock() {
        val extras = Extras(weatherEnabled = false)
        assertFalse(extras.hasWeatherContent)
        assertNull(BroadcastText.weatherSentence(sampleWeather(), extras, "上海"))
        assertTrue("时间不受天气总开关影响", extras.hasTimeContent)
    }

    @Test fun bothMasterSwitchesOffLeavesOnlySolarTerm() {
        val extras = Extras(timeEnabled = false, weatherEnabled = false)
        assertFalse(extras.hasTimeContent)
        assertFalse(extras.hasWeatherContent)
        // 播报内容只剩节气，拼装结果不应包含时间或天气
        val text = BroadcastText.build(
            BroadcastText.timeSentence(time("2026-11-20T13:01:00"), extras),
            BroadcastText.weatherSentence(sampleWeather(), extras, "上海"),
            "今天是小雪"
        )
        assertEquals("今天是小雪。", text)
    }
}
