package com.example.voiceclock

import java.time.ZonedDateTime

/**
 * 播报文案拼装。
 *
 * 纯函数，不依赖 Android 框架，可直接单元测试。
 */
object BroadcastText {

    /** 数据载体。 */
    data class Parts(
        val time: ZonedDateTime,
        val weatherSpeech: String?,
        val solarTermSpeech: String?
    )

    private val weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

    /**
     * 构造完整播报语句。
     *
     * 示例：「现在时间，11月20日 星期五 下午1点1分。天气，多云，气温 12 摄氏度。明天是小雪。」
     */
    fun build(parts: Parts): String {
        val sentences = mutableListOf<String>()
        sentences.add(ensureEnd(timeSentence(parts.time)))
        parts.weatherSpeech?.takeIf { it.isNotBlank() }
            ?.let { sentences.add(ensureEnd("天气，${it.trim()}")) }
        parts.solarTermSpeech?.takeIf { it.isNotBlank() }
            ?.let { sentences.add(ensureEnd(it.trim())) }
        return sentences.joinToString("")
    }

    /** 确保句子以中文句号结尾，避免 TTS 把两句连读成一个词。 */
    private fun ensureEnd(sentence: String): String {
        val trimmed = sentence.trim()
        return if (trimmed.isEmpty() || trimmed.endsWith("。") || trimmed.endsWith("！") || trimmed.endsWith("？")) trimmed
        else "$trimmed。"
    }

    /** 时间语句：现在时间，11月20日 星期五 下午1点1分。 */
    fun timeSentence(time: ZonedDateTime): String {
        val weekday = weekdays.getOrElse(time.dayOfWeek.value - 1) { "" }
        val period = when (time.hour) {
            0 -> "凌晨"
            in 1..5 -> "凌晨"
            in 6..11 -> "上午"
            12 -> "中午"
            in 13..17 -> "下午"
            else -> "晚上"
        }
        val hour12 = when {
            time.hour == 0 -> 12
            time.hour == 12 -> 12
            time.hour > 12 -> time.hour - 12
            else -> time.hour
        }
        val minute = if (time.minute == 0) "整" else "${time.minute}分"
        return "现在时间，${time.monthValue}月${time.dayOfMonth}日 $weekday $period$hour12点$minute。"
    }

    /** 试听用的简短时间语句。 */
    fun shortTime(time: ZonedDateTime): String {
        val minute = if (time.minute == 0) "整" else "${time.minute}分"
        return "现在时间，${time.hour}点$minute"
    }
}
