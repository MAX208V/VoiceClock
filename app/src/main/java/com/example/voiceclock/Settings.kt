package com.example.voiceclock

import android.content.Context
import java.time.ZonedDateTime

/** 天气与节气播报的配置与缓存。 */
data class Extras(
    /** 是否播报天气。 */
    val weatherEnabled: Boolean = true,
    /** 是否自动定位（用系统最近位置）。 */
    val autoLocate: Boolean = true,
    /** 是否播报节气。 */
    val solarTermEnabled: Boolean = true,
    /** 播报时是否压低其他应用（false = 完全不干预，纯混音）。 */
    val duckOthers: Boolean = true,
    /** 城市名，仅用于播报前缀，可留空。 */
    val placeName: String = "",
    /** 缓存的纬度，未定位时为 NaN。 */
    val latitude: Double = Double.NaN,
    /** 缓存的经度，未定位时为 NaN。 */
    val longitude: Double = Double.NaN
) {
    val hasLocation: Boolean
        get() = !latitude.isNaN() && !longitude.isNaN()
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
        return Extras(
            weatherEnabled = p.getBoolean("weatherEnabled", true),
            autoLocate = p.getBoolean("autoLocate", true),
            solarTermEnabled = p.getBoolean("solarTermEnabled", true),
            duckOthers = p.getBoolean("duckOthers", true),
            placeName = p.getString("placeName", "") ?: "",
            latitude = p.getFloat("latitude", Float.NaN).toDouble(),
            longitude = p.getFloat("longitude", Float.NaN).toDouble()
        )
    }

    fun saveExtras(context: Context, extras: Extras) {
        prefs(context).edit()
            .putBoolean("weatherEnabled", extras.weatherEnabled)
            .putBoolean("autoLocate", extras.autoLocate)
            .putBoolean("solarTermEnabled", extras.solarTermEnabled)
            .putBoolean("duckOthers", extras.duckOthers)
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
