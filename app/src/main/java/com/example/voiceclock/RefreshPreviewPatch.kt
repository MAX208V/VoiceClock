    /** 后台取定位+天气，刷新预览。 */
    private fun refreshPreview() {
        infoPreview.text = "正在获取天气与节气…"
        val now = ZonedDateTime.now()
        val ctx = this
        val extras = Settings.extras(ctx)
        val termInfo = if (extras.solarTermEnabled) SolarTerms.infoFor(now.toLocalDate(), now.zone) else null

        io.execute {
            var text = ""
            if (extras.weatherEnabled) {
                val latch = java.util.concurrent.CountDownLatch(1)
                var point: LocationHelper.Point? = null
                if (extras.autoLocate && LocationHelper.hasPermission(ctx)) {
                    main.post { LocationHelper.request(ctx, 6_000L) { p -> point = p; latch.countDown() } }
                    try { latch.await(7, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
                }
                val lat = point?.latitude ?: extras.latitude
                val lon = point?.longitude ?: extras.longitude
                if (point != null) Settings.saveLocation(ctx, lat, lon)
                if (!lat.isNaN() && !lon.isNaN()) {
                    val result = WeatherClient.fetch(lat, lon)
                    result.getOrNull()?.let { w ->
                        text = w.speech(extras.placeName.takeIf { it.isNotBlank() })
                        Settings.saveCachedWeather(ctx, text)
                    }
                    if (result.isFailure) text = "天气获取失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                } else if (!extras.autoLocate || !LocationHelper.hasPermission(ctx)) {
                    text = "未开启定位或未授权，且无缓存坐标"
                } else {
                    text = "定位超时"
                }
            }
            val weatherLine = text
            main.post {
                val isReal = weatherLine.isNotEmpty() &&
                    !weatherLine.startsWith("定位") &&
                    !weatherLine.startsWith("天气获取失败") &&
                    !weatherLine.startsWith("未开启")
                lastWeatherSpeech = if (isReal) weatherLine else null
                val termLine = termInfo?.let { "今日节气：${it.title}（${it.speech}）" } ?: "今日节气：未开启"
                val formattedTime = now.format(DateTimeFormatter.ofPattern("M月d日 EEEE HH:mm", Locale.SIMPLIFIED_CHINESE))
                val coords = Settings.extras(ctx)
                val coordsLine = if (coords.hasLocation)
                    "\n坐标：" + String.format(Locale.US, "%.4f, %.4f", coords.latitude, coords.longitude) else ""
                infoPreview.text = "时间：$formattedTime\n天气：${if (weatherLine.isEmpty()) "未开启" else weatherLine}\n$termLine$coordsLine"
            }
        }
    }
