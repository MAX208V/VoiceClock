package com.example.voiceclock

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 基于 Open-Meteo（https://open-meteo.com）的免费天气查询。
 *
 * 免 API Key，按经纬度查询，无需注册。只依赖标准库 HttpURLConnection 与 org.json，
 * 不引入额外第三方依赖，保持安装包体积。
 */
object WeatherClient {

    /** 天气数据。 */
    data class Weather(
        val temperature: Double,
        val apparentTemperature: Double,
        val humidity: Int,
        val precipitation: Double,
        val weatherCode: Int,
        val windSpeed: Double,
        val isDay: Boolean
    ) {
        /** WMO 天气代码转中文描述。 */
        val description: String
            get() = describe(weatherCode)

        /** 适合播报的一句话。 */
        fun speech(place: String?): String {
            val temp = temperature.toInt()
            val builder = StringBuilder()
            if (!place.isNullOrBlank()) builder.append(place).append('，')
            builder.append(description)
            // 夜间只在晴朗类天气前加修饰，避免「夜间中雨」这种别扭表达。
            if (!isDay && weatherCode in 0..3) builder.append("（夜间）")
            builder.append("，气温 ").append(temp).append(" 摄氏度")
            val feels = apparentTemperature.toInt()
            if (feels != temp) builder.append("，体感 ").append(feels).append(" 度")
            if (precipitation > 0.0) builder.append("，降水量 ").append(format1(precipitation)).append(" 毫米")
            return builder.toString()
        }
    }

    private fun format1(value: Double) = String.format(Locale.US, "%.1f", value)

    /** WMO 天气代码转中文。 */
    fun describe(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "大部晴朗"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "有雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "霰"
        80 -> "阵雨"
        81 -> "强阵雨"
        82 -> "暴雨"
        85 -> "阵雪"
        86 -> "强阵雪"
        95 -> "雷阵雨"
        96, 99 -> "雷阵雨伴冰雹"
        else -> "未知天气"
    }

    /**
     * 查询当前天气。失败返回 failure，并附带可读错误信息。
     * 必须在后台线程调用（内部为阻塞 IO）。
     */
    fun fetch(latitude: Double, longitude: Double, timeoutMillis: Int = 8000): Result<Weather> {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${format4(latitude)}" +
            "&longitude=${format4(longitude)}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,is_day" +
            "&timezone=auto"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "VoiceClock/2.0")
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                return Result.failure(IllegalStateException("天气服务返回 HTTP $status"))
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            Result.success(parse(body))
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            connection?.disconnect()
        }
    }

    private fun format4(value: Double) = String.format(Locale.US, "%.4f", value)

    /** 解析 Open-Meteo 响应。独立出来便于单元测试。 */
    fun parse(body: String): Weather {
        val root = JSONObject(body)
        val current = root.getJSONObject("current")
        val temperature = current.getDouble("temperature_2m")
        return Weather(
            temperature = temperature,
            apparentTemperature = current.optDouble("apparent_temperature", temperature),
            humidity = current.optInt("relative_humidity_2m", 0),
            precipitation = current.optDouble("precipitation", 0.0),
            weatherCode = current.optInt("weather_code", -1),
            windSpeed = current.optDouble("wind_speed_10m", 0.0),
            isDay = current.optInt("is_day", 1) == 1
        )
    }
}
