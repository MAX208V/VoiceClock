package com.example.voiceclock

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var status: TextView
    private lateinit var infoPreview: TextView
    private lateinit var mode: Spinner
    private lateinit var interval: Spinner
    private lateinit var slotList: LinearLayout
    private lateinit var weatherSwitch: Switch
    private lateinit var solarTermSwitch: Switch
    private lateinit var autoLocateSwitch: Switch
    private lateinit var duckSwitch: Switch
    private lateinit var cityInput: EditText
    private lateinit var toggleButton: Button
    private val days = mutableListOf<CheckBox>()
    private val slots = mutableListOf<TimeSlot>()
    private val intervals = listOf(1, 5, 10, 15, 30, 60)
    private var tts: TextToSpeech? = null
    private var speechReady = false
    private var speechInitialized = false
    private val previewTracker = SpeechTracker()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // 不在字段初始化阶段构造：构造期的 this 尚未完全就绪，早期版本因此启动闪退。
    private var previewFocus: AudioFocusHelper? = null

    /** 最近一次成功获取的天气播报文本，供试听拼接使用。 */
    @Volatile private var lastWeatherSpeech: String? = null

    /** 所有 UI 状态都在 onCreate 之后才可用，期间异常不能导致闪退。 */
    private lateinit var focus: AudioFocusHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        focus = AudioFocusHelper(applicationContext)
        previewFocus = focus

        try {
            buildUi()
        } catch (error: Throwable) {
            // 即使构建界面出错，也要给出可读提示，而不是直接闪退。
            val fallback = TextView(this).apply {
                text = "界面初始化失败：${error.message}\n${error.stackTraceToString().take(800)}"
                textSize = 13f
                setPadding(40, 40, 40, 40)
            }
            setContentView(ScrollView(this).apply { addView(fallback) })
        }
    }

    private fun buildUi() {
        SpeechHistory(this).use { it.clearExpired() }
        val saved = Settings.load(this)
        val extras = Settings.extras(this)
        val ctx = this

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }

        fun label(text: String, size: Float = 16f) = TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, dp(14), 0, dp(8))
            root.addView(this)
        }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { action() }
            root.addView(this)
        }

        label("语音报时播报器", 28f)
        label("定时播报：时间 + 天气（自动定位）+ 节气。播报前响三声由弱到强的滴，然后以半速播报。开启后仅在选定星期和时段内播报。")

        // ---------- 播报内容开关 ----------
        label("播报内容", 20f)
        weatherSwitch = Switch(this).apply {
            text = "播报天气（Open-Meteo，免密钥）"
            isChecked = extras.weatherEnabled
        }
        root.addView(weatherSwitch)
        autoLocateSwitch = Switch(this).apply {
            text = "自动定位（前台粗定位）"
            isChecked = extras.autoLocate
        }
        root.addView(autoLocateSwitch)
        cityInput = EditText(this).apply {
            hint = "城市名（仅用于播报前缀，可留空）"
            setText(extras.placeName)
        }
        root.addView(cityInput)
        solarTermSwitch = Switch(this).apply {
            text = "播报二十四节气"
            isChecked = extras.solarTermEnabled
        }
        root.addView(solarTermSwitch)

        // ---------- 音频 ----------
        label("音频", 20f)
        duckSwitch = Switch(this).apply {
            text = "播报时压低其他应用音量（关闭则完全混音）"
            isChecked = extras.duckOthers
        }
        root.addView(duckSwitch)
        label("播报会自动跟随耳机、蓝牙音箱等当前输出设备；不要手动切换。", 14f)

        infoPreview = TextView(this).apply {
            textSize = 15f
            setBackgroundColor(0x11000000)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(infoPreview)

        button("刷新定位与天气预览") { refreshPreview() }
        button("试听完整播报（时间+天气+节气）") { preview(buildPreviewText(), "试听") }
        button("试听当前时间") { preview(BroadcastText.shortTime(ZonedDateTime.now()), "试听") }
        button("查看今日报时记录") { startActivity(Intent(this, HistoryActivity::class.java)) }

        label("锁屏播报使用系统闹钟唤醒；系统可能显示下一次闹钟标记。", 14f)
        button("设置锁屏报时权限") { openAlarmPermission() }
        button("设置后台电池用量") {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }

        label("报时模式", 20f)
        mode = Spinner(this).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, Schedule.modes)
            setSelection(saved.mode.coerceIn(0, Schedule.modes.size - 1))
        }
        root.addView(mode)
        interval = Spinner(this).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, intervals.map { "每 $it 分钟" })
            setSelection(intervals.indexOf(saved.interval).coerceAtLeast(0))
        }
        root.addView(interval)
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                interval.visibility = if (position == 0) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        label("每周哪些天", 20f)
        val names = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        names.chunked(4).forEachIndexed { rowIndex, group ->
            val row = LinearLayout(ctx)
            group.forEachIndexed { i, name ->
                val day = rowIndex * 4 + i + 1
                val check = CheckBox(ctx).apply { text = name; isChecked = day in saved.days }
                days.add(check)
                row.addView(check, LinearLayout.LayoutParams(0, -2, 1f))
            }
            root.addView(row)
        }

        label("每天的报时时段", 20f)
        label("包含开始时间，不包含结束时间。开始和结束相同表示全天；结束早于开始表示跨午夜，凌晨部分属于前一天。", 14f)
        slots.addAll(saved.slots)
        slotList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(slotList)
        renderSlots()
        button("＋ 添加时段") { slots.add(TimeSlot(8 * 60, 22 * 60)); renderSlots() }

        // 保存即开启报时
        button("保存设置") {
            val schedule = readSchedule() ?: return@button
            Settings.save(ctx, schedule)
            saveExtras()
            enableBroadcast(schedule)
        }

        // 单一开关按钮：文本随状态变化
        toggleButton = Button(this).apply {
            setOnClickListener {
                if (Settings.enabled(ctx)) {
                    disableBroadcast()
                } else {
                    val schedule = readSchedule() ?: return@setOnClickListener
                    Settings.save(ctx, schedule)
                    saveExtras()
                    enableBroadcast(schedule)
                }
            }
            root.addView(this)
        }

        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(16), 0, dp(16)) }
        root.addView(status)
        updateToggleButton()
        showStatus(if (Settings.enabled(this)) "报时已开启" else "当前未开启", saved)

        setContentView(scroll)
        tts = TextToSpeech(this, this)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) {
                if (TimeSpeech.isAuxiliary(utteranceId)) return
                previewTracker.finish(utteranceId)?.let { record ->
                    SpeechHistory(ctx).use { it.add(record) }
                    focus.release()
                    main.post { if (::status.isInitialized) status.text = "试听播放完成，已保存报时记录" }
                }
            }

            override fun onError(utteranceId: String) {
                if (utteranceId.endsWith(":lead")) return
                previewTracker.fail(TimeSpeech.speechId(utteranceId))
                focus.release()
                main.post { if (::status.isInitialized) status.text = "试听失败，请检查系统语音和提示音播放" }
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                if (TimeSpeech.isAuxiliary(utteranceId)) return
                previewTracker.fail(TimeSpeech.speechId(utteranceId))
                focus.release()
            }
        })

        val wanted = mutableListOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_COARSE_LOCATION)
        val missing = wanted.filter {
            if (it == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33) false
            else checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 10)

        refreshPreview()
    }

    /** 保存配置并开启报时。 */
    private fun enableBroadcast(schedule: Schedule) {
        if (!ClockAlarms.permitted(this)) {
            status.text = "请允许“闹钟和提醒”，返回后再次点击开启，才能在锁屏时准时报播"
            openAlarmPermission()
            return
        }
        Settings.setEnabled(this, true)
        try {
            startForegroundService(Intent(this, ClockService::class.java).setAction(ClockService.START))
        } catch (error: Exception) {
            status.text = "开启失败：${error.message}"
            return
        }
        updateToggleButton()
        showStatus(
            if (schedule.allows(ZonedDateTime.now())) "已保存并开启，将立即播报" else "已保存并开启，当前不在播报时段",
            schedule
        )
    }

    /** 关闭报时。 */
    private fun disableBroadcast() {
        Settings.setEnabled(this, false)
        ClockAlarms.cancel(this)
        stopService(Intent(this, ClockService::class.java))
        updateToggleButton()
        status.text = "已关闭报时，设置已保留"
    }

    private fun updateToggleButton() {
        if (::toggleButton.isInitialized) {
            toggleButton.text = if (Settings.enabled(this)) "关闭报时" else "开启报时"
        }
    }

    private fun saveExtras() {
        val current = Settings.extras(this)
        Settings.saveExtras(
            this,
            current.copy(
                weatherEnabled = weatherSwitch.isChecked,
                autoLocate = autoLocateSwitch.isChecked,
                solarTermEnabled = solarTermSwitch.isChecked,
                duckOthers = duckSwitch.isChecked,
                placeName = cityInput.text.toString().trim()
            )
        )
    }

    /** 与正式播报相同的拼装逻辑。 */
    private fun buildPreviewText(): String {
        val now = ZonedDateTime.now()
        val extras = Settings.extras(this)
        val term = if (extras.solarTermEnabled) SolarTerms.infoFor(now.toLocalDate(), now.zone).speech else null
        return BroadcastText.build(BroadcastText.Parts(now, lastWeatherSpeech, term))
    }

    /** 后台取定位 + 天气 + 节气，刷新预览（节气计算不上主线程）。 */
    private fun refreshPreview() {
        infoPreview.text = "正在获取天气与节气…"
        val now = ZonedDateTime.now()
        val ctx = this
        val extras = Settings.extras(ctx)

        io.execute {
            // 节气在后台线程计算（带缓存）
            val termInfo = try {
                if (extras.solarTermEnabled) SolarTerms.infoFor(now.toLocalDate(), now.zone) else null
            } catch (_: Throwable) {
                null
            }

            var text = ""
            if (extras.weatherEnabled) {
                try {
                    val latch = CountDownLatch(1)
                    val holder = AtomicReference<LocationHelper.Point?>(null)
                    if (extras.autoLocate && LocationHelper.hasPermission(ctx)) {
                        main.post {
                            LocationHelper.request(ctx, 6_000L) { p ->
                                holder.set(p)
                                latch.countDown()
                            }
                        }
                        try { latch.await(7, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
                    }
                    val point = holder.get()
                    val lat = point?.latitude ?: extras.latitude
                    val lon = point?.longitude ?: extras.longitude
                    if (point != null && !lat.isNaN() && !lon.isNaN()) Settings.saveLocation(ctx, lat, lon)

                    if (!lat.isNaN() && !lon.isNaN()) {
                        val result = WeatherClient.fetch(lat, lon)
                        val weather = result.getOrNull()
                        if (weather != null) {
                            text = weather.speech(extras.placeName.takeIf { it.isNotBlank() })
                            Settings.saveCachedWeather(ctx, text)
                        } else {
                            text = "天气获取失败：" + (result.exceptionOrNull()?.message ?: "未知错误")
                        }
                    } else if (!extras.autoLocate || !LocationHelper.hasPermission(ctx)) {
                        text = "未开启定位或未授权，且无缓存坐标"
                    } else {
                        text = "定位超时"
                    }
                } catch (error: Throwable) {
                    text = "天气获取失败：${error.message ?: "未知错误"}"
                }
            }
            val weatherLine = text
            main.post {
                if (!::infoPreview.isInitialized) return@post
                try {
                    val isRealWeather = weatherLine.isNotEmpty() &&
                        weatherLine.indexOf("获取失败") < 0 &&
                        weatherLine.indexOf("定位") < 0 &&
                        weatherLine.indexOf("未开启") < 0 &&
                        weatherLine.indexOf("未授权") < 0
                    lastWeatherSpeech = if (isRealWeather) weatherLine else null
                    val termLine = if (termInfo != null) "今日节气：${termInfo.title}（${termInfo.speech}）" else "今日节气：未开启"
                    val formattedTime = now.format(
                        DateTimeFormatter.ofPattern("M月d日 EEEE HH:mm", Locale.SIMPLIFIED_CHINESE)
                    )
                    val weatherText = if (weatherLine.isEmpty()) "未开启" else weatherLine
                    val coords = Settings.extras(ctx)
                    val coordsLine = if (coords.hasLocation)
                        "\n坐标：" + String.format(Locale.US, "%.4f, %.4f", coords.latitude, coords.longitude)
                    else ""
                    infoPreview.text = "时间：$formattedTime\n天气：$weatherText\n$termLine$coordsLine"
                } catch (_: Throwable) {
                    infoPreview.text = "预览刷新失败"
                }
            }
        }
    }

    private fun renderSlots() {
        val ctx = this
        slotList.removeAllViews()
        slots.forEachIndexed { index, slot ->
            val row = LinearLayout(ctx)
            fun timeButton(start: Boolean) = Button(ctx).apply {
                text = Schedule.clock(if (start) slot.start else slot.end)
                setOnClickListener {
                    if (index >= slots.size) return@setOnClickListener
                    val value = if (start) slots[index].start else slots[index].end
                    TimePickerDialog(ctx, { _, hour, minute ->
                        if (index < slots.size) {
                            val current = slots[index]
                            slots[index] = if (start) current.copy(start = hour * 60 + minute)
                            else current.copy(end = hour * 60 + minute)
                            renderSlots()
                        }
                    }, value / 60, value % 60, true).show()
                }
            }
            row.addView(timeButton(true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(ctx).apply { text = " 至 " })
            row.addView(timeButton(false), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(ctx).apply {
                text = "删除"
                setOnClickListener {
                    if (index < slots.size) {
                        slots.removeAt(index)
                        renderSlots()
                    }
                }
            })
            slotList.addView(row)
        }
    }

    private fun readSchedule(): Schedule? {
        val selected = days.mapIndexedNotNull { i, check -> if (check.isChecked) i + 1 else null }.toSet()
        if (selected.isEmpty() || slots.isEmpty()) {
            status.text = "请至少选择一天，并添加一个时段"
            return null
        }
        val intervalValue = intervals.getOrElse(interval.selectedItemPosition) { 30 }
        return Schedule(selected, slots.toList(), mode.selectedItemPosition, intervalValue)
    }

    private fun showStatus(message: String, schedule: Schedule) {
        val now = ZonedDateTime.now()
        val next = if (Settings.enabled(this)) ClockAlarms.next(this) else schedule.next(now, now)
        val formatted = next?.format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) ?: "没有可用时间"
        val modeIndex = schedule.mode.coerceIn(0, Schedule.modes.size - 1)
        status.text = "$message\n${Schedule.modes[modeIndex]}\n下一次计划：$formatted"
    }

    override fun onInit(result: Int) {
        speechInitialized = true
        try {
            if (result == TextToSpeech.SUCCESS) {
                val language = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                speechReady = language != null && language >= TextToSpeech.LANG_AVAILABLE &&
                    tts?.let { TimeSpeech.configure(it, packageName, focus) } == true
            }
        } catch (_: Throwable) {
            speechReady = false
        }
        if (!speechReady && ::status.isInitialized) {
            status.text = "中文语音不可用，请在系统文字转语音设置中安装中文语音"
        }
    }

    private fun preview(text: String, source: String) {
        if (!speechReady) {
            status.text = if (speechInitialized) "中文语音不可用，请检查系统文字转语音设置" else "正在准备中文语音…"
            return
        }
        val now = ZonedDateTime.now()
        previewTracker.clear()
        // 试听也遵循音频设置：duck 关时完全不干预其他应用。
        focus.request(Settings.extras(this).duckOthers)
        val id = previewTracker.begin(SpeechRecord(now, text, source))
        val result = try {
            TimeSpeech.speak(tts, text, id)
        } catch (_: Throwable) {
            -1
        }
        if (result != TextToSpeech.SUCCESS) {
            previewTracker.fail(id)
            focus.release()
        }
        status.text = if (result == TextToSpeech.SUCCESS) "正在试听：$text" else "试听失败，请检查系统语音设置"
    }

    private fun openAlarmPermission() {
        if (Build.VERSION.SDK_INT >= 31 && !ClockAlarms.permitted(this)) {
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (error: Exception) {
                status.text = "无法打开权限设置：${error.message}"
            }
        } else {
            status.text = "已具备锁屏播报所需闹钟权限"
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::status.isInitialized) return
        updateToggleButton()
        if (Settings.enabled(this)) {
            if (ClockAlarms.permitted(this)) {
                try {
                    startForegroundService(Intent(this, ClockService::class.java).setAction(ClockService.RESTORE))
                } catch (_: Exception) {
                    // 后台限制可能导致启动失败，不影响界面。
                }
            } else {
                status.text = "报时未能运行：请允许“闹钟和提醒”权限"
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) refreshPreview()
    }

    override fun onDestroy() {
        previewTracker.clear()
        previewFocus?.release()
        tts?.stop()
        tts?.shutdown()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
