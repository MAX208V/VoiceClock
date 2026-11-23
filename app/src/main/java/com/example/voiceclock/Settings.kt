package com.example.voiceclock

import android.content.Context
import java.time.ZonedDateTime

/**
 * 提示音来源。
 *
 * DEFAULT —— 内置三声「滴」（res/raw/time_chime.wav）
 * SYSTEM  —— 用户从系统铃声选择器选定的铃声（存 URI）
 * SILENT  —— 不播提示音，直接念正文
 */
enum class ChimeSource { DEFAULT, SYSTEM, SILENT }

/**
 * 播报内容的细粒度开关。
 *
 * 时间：时间本体 / 日期 / 星期 / 时段词
 * 天气：天气现象 / 当前气温 / 体感温度 / 降水量
 * 节气：是否播报
 */
data class Extras(
    // ---- 时间 ----
    val speakClock: Boolean = true,
    val speakDate: Boolean = true,
    val speakWeekday: Boolean = true,
    val speakPeriod: Boolean = true,
    // ---- 天气 ----
    val weatherEnabled: Boolean = true,
    val speakWeatherText: Boolean = true,
    val speakTemperature: Boolean = true,
    val speakApparent: Boolean = true,
    val speakPrecipitation: Boolean = true,
    // ---- 节气 ----
    val solarTermEnabled: Boolean = true,
    // ---- 定位与音频 ----
    val autoLocate: Boolean = true,
    val duckOthers: Boolean = true,
    val chimeSource: ChimeSource = ChimeSource.DEFAULT,
    val chimeUri: String = "",
    val chimeLabel: String = "",
    /** 提示音截断长度（秒）；0 表示完整播放。 */
    val chimeMaxSeconds: Int = 0,
    val placeName: String = "",
    val latitude: Double = Double.NaN,
    val longitude: Double = Double.NaN
) {
    val hasLocation: Boolean
        get() = !latitude.isNaN() && !longitude.isNaN()

    /** 时间部分是否有任何内容可播报。 */
    val hasTimeContent: Boolean
        get() = speakClock || speakDate || speakWeekday || speakPeriod

    /** 天气部分是否有任何内容可播报。 */
    val hasWeatherContent: Boolean
        get() = weatherEnabled && (speakWeatherText || speakTemperature || speakApparent || speakPrecipitation)

    companion object {
        /** 提示音可选时长���秒）：0 = 完整。 */
        val CHIME_SECONDS = listOf(0, 5, 3, 2, 1)

        fun chimeSecondsLabel(seconds: Int): String =
            if (seconds <= 0) "完整播放" else "$seconds 秒"
    }
}

/** 本地设置读写。 */
object Settings {
    private const val PREFS = "settings"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Schedule {
        val p = prefs(context)
        val days = p.getString("days", "1,2,3,4,5,6,7")!!.split(',').mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }.toSet()
        val slots = p.getString("slots", "0-0")!!.split(',').mapNotNull {
            val values = it.split('-').mapNotNull(String::toIntOrNull)
            if (values.size == 2 && values.all { n -> n in 0..1439 }) TimeSlot(values[0], values[1]) else null
        }
        return Schedule(days, slots, p.getInt("mode", 0).coerceIn(0, 3), p.getInt("interval", 30).coerceAtLeast(1))
    }

    fun save(context: Context, schedule: Schedule) {
        prefs(context).edit()
            .putString("days", schedule.days.sorted().joinToString(","))
            .putString("slots", schedule.slots.joinToString(",") { "${it.start}-${it.end}" })
            .putInt("mode", schedule.mode).putInt("interval", schedule.interval).apply()
    }

    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
    }

    // ---- 天气、节气与音频 ----

    fun extras(context: Context): Extras {
        val p = prefs(context)
        val sourceRaw = p.getString("chimeSource", ChimeSource.DEFAULT.name) ?: ChimeSource.DEFAULT.name
        val source = runCatching { ChimeSource.valueOf(sourceRaw) }.getOrDefault(ChimeSource.DEFAULT)
        return Extras(
            speakClock = p.getBoolean("speakClock", true),
            speakDate = p.getBoolean("speakDate", true),
            speakWeekday = p.getBoolean("speakWeekday", true),
            speakPeriod = p.getBoolean("speakPeriod", true),
            weatherEnabled = p.getBoolean("weatherEnabled", true),
            speakWeatherText = p.getBoolean("speakWeatherText", true),
            speakTemperature = p.getBoolean("speakTemperature", true),
            speakApparent = p.getBoolean("speakApparent", true),
            speakPrecipitation = p.getBoolean("speakPrecipitation", true),
            solarTermEnabled = p.getBoolean("solarTermEnabled", true),
            autoLocate = p.getBoolean("autoLocate", true),
            duckOthers = p.getBoolean("duckOthers", true),
            chimeSource = source,
            chimeUri = p.getString("chimeUri", "") ?: "",
            chimeLabel = p.getString("chimeLabel", "") ?: "",
            chimeMaxSeconds = p.getInt("chimeMaxSeconds", 0).coerceAtLeast(0),
            placeName = p.getString("placeName", "") ?: "",
            latitude = p.getFloat("latitude", Float.NaN).toDouble(),
            longitude = p.getFloat("longitude", Float.NaN).toDouble()
        )
    }

    fun saveExtras(context: Context, extras: Extras) {
        prefs(context).edit()
            .putBoolean("speakClock", extras.speakClock)
            .putBoolean("speakDate", extras.speakDate)
            .putBoolean("speakWeekday", extras.speakWeekday)
            .putBoolean("speakPeriod", extras.speakPeriod)
            .putBoolean("weatherEnabled", extras.weatherEnabled)
            .putBoolean("speakWeatherText", extras.speakWeatherText)
            .putBoolean("speakTemperature", extras.speakTemperature)
            .putBoolean("speakApparent", extras.speakApparent)
            .putBoolean("speakPrecipitation", extras.speakPrecipitation)
            .putBoolean("solarTermEnabled", extras.solarTermEnabled)
            .putBoolean("autoLocate", extras.autoLocate)
            .putBoolean("duckOthers", extras.duckOthers)
            .putString("chimeSource", extras.chimeSource.name)
            .putString("chimeUri", extras.chimeUri)
            .putString("chimeLabel", extras.chimeLabel)
            .putInt("chimeMaxSeconds", extras.chimeMaxSeconds)
            .putString("placeName", extras.placeName)
            .putFloat("latitude", extras.latitude.toFloat())
            .putFloat("longitude", extras.longitude.toFloat())
            .apply()
    }

    /** 保存定位得到的坐标，并记录时间戳。 */
    fun saveLocation(context: Context, latitude: Double, longitude: Double) {
        prefs(context).edit()
            .putFloat("latitude", latitude.toFloat())
            .putFloat("longitude", longitude.toFloat())
            .putLong("locationAt", ZonedDateTime.now().toInstant().toEpochMilli())
            .apply()
    }

    /** 上次成功定位的时间戳，用于判断缓存是否过期。 */
    fun locationTimestamp(context: Context): Long = prefs(context).getLong("locationAt", 0L)

    /** 缓存天气文本，天气获取失败时可兑底播报。 */
    fun cachedWeather(context: Context): String = prefs(context).getString("cachedWeather", "") ?: ""

    fun saveCachedWeather(context: Context, text: String) {
        prefs(context).edit().putString("cachedWeather", text).apply()
    }
}
