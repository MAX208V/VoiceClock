package com.example.voiceclock

import java.time.ZonedDateTime

/**
 * 播报文案拼装。
 *
 * 按 Extras 中的开关动态拼接，支持任意组合：
 * - 只播时间不播日期星期
 * - 天气只播当前气温
 * 全部为纯函数，不依赖 Android 框架，可直接单元测试。
 */
object BroadcastText {

    private val weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

    /**
     * 构造完整播报语句。
     *
     * 参数均为已按开关裁剪好的片段，null 或空白会被跳过。
     */
    fun build(timeSentence: String?, weatherSentence: String?, solarTermSentence: String?): String {
        val sentences = ArrayList<String>(3)
        timeSentence?.takeIf { it.isNotBlank() }?.let { sentences.add(ensureEnd(it)) }
        weatherSentence?.takeIf { it.isNotBlank() }?.let { sentences.add(ensureEnd(it)) }
        solarTermSentence?.takeIf { it.isNotBlank() }?.let { sentences.add(ensureEnd(it)) }
        return sentences.joinToString("")
    }

    /** 确保句子以中文句号结尾，避免 TTS 把两句连读成一个词。 */
    private fun ensureEnd(sentence: String): String {
        val trimmed = sentence.trim()
        if (trimmed.isEmpty()) return trimmed
        val ends = trimmed.endsWith("。") || trimmed.endsWith("！") || trimmed.endsWith("？")
        return if (ends) trimmed else trimmed + "。"
    }

    /** 时段词：凌晨 / 上午 / 中午 / 下午 / 晚上。 */
    fun periodOf(hour: Int): String = when (hour) {
        0 -> "凌晨"
        in 1..5 -> "凌晨"
        in 6..11 -> "上午"
        12 -> "中午"
        in 13..17 -> "下午"
        else -> "晚上"
    }

    /**
     * 时间语句。
     *
     * 根据开关输出不同形式：
     * - 全开：现在时间，11月20日 星期五 下午1点1分。
     * - 只时间：现在是下午1点1分。
     * - 只时间（无时段词）：现在是13点1分。
     * - 只日期星期：今天是11月20日 星期五。
     * - 无任何时间内容：null
     */
    fun timeSentence(time: ZonedDateTime, extras: Extras): String? {
        if (!extras.hasTimeContent) return null

        val minutePart = if (time.minute == 0) "整" else "${time.minute}分"
        val hour12 = when {
            time.hour == 0 -> 12
            time.hour == 12 -> 12
            time.hour > 12 -> time.hour - 12
            else -> time.hour
        }

        val pieces = ArrayList<String>(4)
        if (extras.speakDate) pieces.add("${time.monthValue}月${time.dayOfMonth}日")
        if (extras.speakWeekday) {
            val weekday = weekdays.getOrElse(time.dayOfWeek.value - 1) { "" }
            if (weekday.isNotEmpty()) pieces.add(weekday)
        }
        if (extras.speakClock) {
            val period = if (extras.speakPeriod) periodOf(time.hour) else ""
            val hourPart = if (extras.speakPeriod) "${hour12}点" else "${time.hour}点"
            pieces.add("${period}${hourPart}${minutePart}")
        }

        if (pieces.isEmpty()) return null

        val body = pieces.joinToString(" ")
        // 只有时间本体时用「现在是」，否则用「现在是」+ 日期等
        return "现在是${body}"
    }

    /**
     * 天气语句。
     *
     * 按开关组合：例如只开气温 → 「上海，气温 12 摄氏度」。
     */
    fun weatherSentence(weather: WeatherClient.Weather?, extras: Extras, placeName: String?): String? {
        if (!extras.hasWeatherContent) return null
        if (weather == null) return null

        val temp = weather.temperature.toInt()
        val feels = weather.apparentTemperature.toInt()
        val pieces = ArrayList<String>(4)

        val prefix = placeName?.takeIf { it.isNotBlank() }
        if (prefix != null) pieces.add(prefix)

        if (extras.speakWeatherText) {
            val desc = weather.description
            if (!weather.isDay && weather.weatherCode in 0..3) pieces.add("$desc（夜间）") else pieces.add(desc)
        }
        if (extras.speakTemperature) pieces.add("气温 $temp 摄氏度")
        if (extras.speakApparent && feels != temp) pieces.add("体感 $feels 度")
        if (extras.speakPrecipitation && weather.precipitation > 0.0) {
            pieces.add("降水量 " + String.format(java.util.Locale.US, "%.1f", weather.precipitation) + " 毫米")
        }

        if (pieces.isEmpty()) return null
        return "天气，" + pieces.joinToString("，")
    }

    /** 缓存用的简短天气描述（用于天气获取失败时的兑底播报）。 */
    fun simpleWeatherSentence(weatherText: String): String = "天气，$weatherText"

    /** 试听用的简短时间语句。 */
    fun shortTime(time: ZonedDateTime): String {
        val minutePart = if (time.minute == 0) "整" else "${time.minute}分"
        return "现在是${time.hour}点${minutePart}"
    }
}
