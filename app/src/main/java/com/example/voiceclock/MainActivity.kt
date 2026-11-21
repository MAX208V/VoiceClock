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
import android.util.TypedValue
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

/**
 * 设置界面。
 *
 * 启动健壮性说明：
 * 早期版本在字段初始化阶段就构造了 AudioFocusHelper，并大量使用 lambda 闭包，
 * 在部分机型上会导致「一点就闪退」。本版本：
 * 1. onCreate 全程包在 try/catch 中，任何异常都转为界面文字展示，不会闪退；
 * 2. 移除 WindowInsets lambda（改用 fitSystemWindows）；
 * 3. AudioFocusHelper / TextToSpeech 均延后到真正需要时才创建；
 * 4. 定位、天气、节气全部在后台线程，主线程只做 UI。
 */
class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private var statusView: TextView? = null
    private var previewView: TextView? = null
    private var modeSpinner: Spinner? = null
    private var intervalSpinner: Spinner? = null
    private var slotContainer: LinearLayout? = null
    private var weatherSwitch: Switch? = null
    private var solarTermSwitch: Switch? = null
    private var autoLocateSwitch: Switch? = null
    private var duckSwitch: Switch? = null
    private var cityInput: EditText? = null
    private var toggleButton: Button? = null

    private val dayChecks = ArrayList<CheckBox>()
    private val slots = ArrayList<TimeSlot>()
    private val intervals = listOf(1, 5, 10, 15, 30, 60)

    private var tts: TextToSpeech? = null
    private var speechReady = false
    private var speechInitialized = false
    private val previewTracker = SpeechTracker()
    private var executor: java.util.concurrent.ExecutorService? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 延迟创建，避免在 Activity 构造/早期阶段拿不到系统服务。 */
    private var focusHelper: AudioFocusHelper? = null

    @Volatile private var lastWeatherSpeech: String? = null

    @Volatile private var lastCrash: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashGuard()
        try {
            executor = Executors.newSingleThreadExecutor()
            buildUi()
        } catch (error: Throwable) {
            showFatal("界面初始化失败", error)
        }
        lastCrash?.let { /* 上次崩溃已记录，界面正常则忽略 */ }
    }

    /** 捕获本 Activity 生命周期内的未处理异常，转为可见提示。 */
    private fun installCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            lastCrash = error.stackTraceToString().take(1500)
            try {
                val target = statusView
                if (target != null) {
                    mainHandler.post {
                        target.text = "发生异常：\n" + (error.message ?: error.javaClass.simpleName)
                    }
                }
            } catch (_: Throwable) {
                // 忽略
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun focus(): AudioFocusHelper =
        focusHelper ?: AudioFocusHelper(applicationContext).also { focusHelper = it }

    private fun io(): java.util.concurrent.ExecutorService? = executor

    private fun status(): TextView? = statusView

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** 把崩溃信息直接显示在界面上，方便排查，而不是闪退。 */
    private fun showFatal(title: String, error: Throwable) {
        val detail = buildString {
            append(title).append('\n')
            append(error.javaClass.name).append(": ").append(error.message ?: "").append("\n\n")
            val cause = error.cause
            if (cause != null) {
                append("Caused by: ").append(cause.javaClass.name).append(": ").append(cause.message ?: "")
                append("\n\n")
            }
            append(error.stackTraceToString().take(1200))
        }
        try {
            val view = TextView(this).apply {
                text = detail
                textSize = 12f
                setPadding(dp(16), dp(48), dp(16), dp(16))
            }
            setContentView(ScrollView(this).apply { addView(view) })
        } catch (_: Throwable) {
            // 连兜底界面都创建失败时无能为力
        }
    }

    private fun buildUi() {
        SpeechHistory(this).use { it.clearExpired() }
        val saved = Settings.load(this)
        val extras = Settings.extras(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(16), dp(20), dp(24))

        val scroll = ScrollView(this)
        scroll.addView(root)
        // 不使用 WindowInsets lambda，避免部分机型上的兼容问题。
        scroll.isFillViewport = true

        addLabel(root, "语音报时播报器", 28f)
        addLabel(root, "定时播报：时间 + 天气（自动定位）+ 节气。播报前响三声由弱到强的滴，然后以半速播报。开启后仅在选定星期和时段内播报。", 14f)

        addLabel(root, "播报内容", 20f)
        weatherSwitch = Switch(this).also {
            it.text = "播报天气（Open-Meteo，免密钥）"
            it.isChecked = extras.weatherEnabled
            root.addView(it)
        }
        autoLocateSwitch = Switch(this).also {
            it.text = "自动定位（前台粗定位）"
            it.isChecked = extras.autoLocate
            root.addView(it)
        }
        cityInput = EditText(this).also {
            it.hint = "城市名（仅用于播报前缀，可留空）"
            it.setText(extras.placeName)
            root.addView(it)
        }
        solarTermSwitch = Switch(this).also {
            it.text = "播报二十四节气"
            it.isChecked = extras.solarTermEnabled
            root.addView(it)
        }

        addLabel(root, "音频", 20f)
        duckSwitch = Switch(this).also {
            it.text = "播报时压低其他应用音量（关闭则完全混音）"
            it.isChecked = extras.duckOthers
            root.addView(it)
        }
        addLabel(root, "播报会自动跟随耳机、蓝牙音箱等当前输出设备；不要手动切换。", 14f)

        previewView = TextView(this).also {
            it.textSize = 14f
            it.setPadding(dp(12), dp(10), dp(12), dp(10))
            it.text = "正在获取天气与节气…"
            root.addView(it)
        }

        addButton(root, "刷新定位与天气预览") { refreshPreview() }
        addButton(root, "试听完整播报（时间+天气+节气）") { preview(buildPreviewText(), "试听") }
        addButton(root, "试听当前时间") { preview(BroadcastText.shortTime(ZonedDateTime.now()), "试听") }
        addButton(root, "查看今日报时记录") {
            try {
                startActivity(Intent(this, HistoryActivity::class.java))
            } catch (error: Throwable) {
                statusView?.text = "打开记录失败：${error.message}"
            }
        }

        addLabel(root, "锁屏播报使用系统闹钟唤醒；系统可能显示下一次闹钟标记。", 14f)
        addButton(root, "设置锁屏报时权限") { openAlarmPermission() }
        addButton(root, "设置后台电池用量") {
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (error: Throwable) {
                statusView?.text = "打开设置失败：${error.message}"
            }
        }

        addLabel(root, "报时模式", 20f)
        modeSpinner = Spinner(this).also {
            it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Schedule.modes)
            it.setSelection(saved.mode.coerceIn(0, Schedule.modes.size - 1))
            root.addView(it)
        }
        intervalSpinner = Spinner(this).also {
            it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, intervals.map { n -> "每 $n 分钟" })
            it.setSelection(intervals.indexOf(saved.interval).coerceAtLeast(0))
            root.addView(it)
        }
        modeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                intervalSpinner?.visibility = if (position == 0) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        addLabel(root, "每周哪些天", 20f)
        val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        var index = 0
        while (index < dayNames.size) {
            val row = LinearLayout(this)
            var column = 0
            while (column < 4 && index < dayNames.size) {
                val day = index + 1
                val check = CheckBox(this)
                check.text = dayNames[index]
                check.isChecked = day in saved.days
                dayChecks.add(check)
                row.addView(check, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                index++
                column++
            }
            root.addView(row)
        }

        addLabel(root, "每天的报时时段", 20f)
        addLabel(root, "包含开始时间，不包含结束时间。开始和结束相同表示全天；结束早于开始表示跨午夜，凌晨部分属于前一天。", 13f)
        slots.addAll(saved.slots)
        slotContainer = LinearLayout(this).also {
            it.orientation = LinearLayout.VERTICAL
            root.addView(it)
        }
        renderSlots()
        addButton(root, "＋ 添加时段") {
            slots.add(TimeSlot(8 * 60, 22 * 60))
            renderSlots()
        }

        addButton(root, "保存设置") {
            val schedule = readSchedule()
            if (schedule == null) {
                statusView?.text = "请至少选择一天，并添加一个时段"
                return@addButton
            }
            try {
                Settings.save(this, schedule)
                saveExtras()
                enableBroadcast(schedule)
            } catch (error: Throwable) {
                statusView?.text = "保存失败：${error.message}"
            }
        }

        toggleButton = Button(this).also { button ->
            button.setOnClickListener {
                try {
                    if (Settings.enabled(this)) {
                        disableBroadcast()
                    } else {
                        val schedule = readSchedule()
                        if (schedule == null) {
                            statusView?.text = "请至少选择一天，并添加一个时段"
                            return@setOnClickListener
                        }
                        Settings.save(this, schedule)
                        saveExtras()
                        enableBroadcast(schedule)
                    }
                } catch (error: Throwable) {
                    statusView?.text = "操作失败：${error.message}"
                }
            }
            root.addView(button)
        }

        statusView = TextView(this).also {
            it.textSize = 14f
            it.setPadding(0, dp(16), 0, dp(16))
            root.addView(it)
        }

        setContentView(scroll)

        updateToggleButton()
        showStatus(if (Settings.enabled(this)) "报时已开启" else "当前未开启", saved)

        // TTS 延后到最后创建，不阻塞界面显示
        try {
            tts = TextToSpeech(this, this)
            tts?.setOnUtteranceProgressListener(createUtteranceListener())
        } catch (error: Throwable) {
            speechReady = false
            statusView?.text = "语音初始化失败：${error.message}（界面仍可正常使用）"
        }

        requestNeededPermissions()
        refreshPreview()
    }

    private fun createUtteranceListener(): UtteranceProgressListener =
        object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) {
                if (TimeSpeech.isAuxiliary(utteranceId)) return
                previewTracker.finish(utteranceId)?.let { record ->
                    try {
                        SpeechHistory(this@MainActivity).use { it.add(record) }
                    } catch (_: Throwable) {
                        // 忽略历史写入错误
                    }
                    focusHelper?.release()
                    mainHandler.post { statusView?.text = "试听播放完成，已保存报时记录" }
                }
            }

            override fun onError(utteranceId: String) {
                if (utteranceId.endsWith(":lead")) return
                previewTracker.fail(TimeSpeech.speechId(utteranceId))
                focusHelper?.release()
                mainHandler.post { statusView?.text = "试听失败，请检查系统语音和提示音播放" }
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                if (TimeSpeech.isAuxiliary(utteranceId)) return
                previewTracker.fail(TimeSpeech.speechId(utteranceId))
                focusHelper?.release()
            }
        }

    private fun addLabel(parent: LinearLayout, text: String, size: Float) {
        val view = TextView(this)
        view.text = text
        view.textSize = size
        view.setPadding(0, dp(12), 0, dp(6))
        parent.addView(view)
    }

    private fun addButton(parent: LinearLayout, text: String, action: () -> Unit): Button {
        val button = Button(this)
        button.text = text
        button.setOnClickListener {
            try {
                action()
            } catch (error: Throwable) {
                statusView?.text = "操作失败：${error.message ?: error.javaClass.simpleName}"
            }
        }
        parent.addView(button)
        return button
    }

    private fun requestNeededPermissions() {
        try {
            val wanted = ArrayList<String>()
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                wanted.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                wanted.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 10)
        } catch (_: Throwable) {
            // 权限请求失败不影响界面
        }
    }

    private fun enableBroadcast(schedule: Schedule) {
        if (!ClockAlarms.permitted(this)) {
            statusView?.text = "请允许“闹钟和提醒”，返回后再次点击，才能在锁屏时准时报播"
            openAlarmPermission()
            return
        }
        Settings.setEnabled(this, true)
        try {
            startForegroundService(Intent(this, ClockService::class.java).setAction(ClockService.START))
        } catch (error: Throwable) {
            statusView?.text = "开启失败：${error.message}"
            return
        }
        updateToggleButton()
        showStatus(
            if (schedule.allows(ZonedDateTime.now())) "已保存并开启，将立即播报" else "已保存并开启，当前不在播报时段",
            schedule
        )
    }

    private fun disableBroadcast() {
        Settings.setEnabled(this, false)
        ClockAlarms.cancel(this)
        try {
            stopService(Intent(this, ClockService::class.java))
        } catch (_: Throwable) {
            // 忽略
        }
        updateToggleButton()
        statusView?.text = "已关闭报时，设置已保留"
    }

    private fun updateToggleButton() {
        toggleButton?.text = if (Settings.enabled(this)) "关闭报时" else "开启报时"
    }

    private fun saveExtras() {
        val current = Settings.extras(this)
        Settings.saveExtras(
            this,
            current.copy(
                weatherEnabled = weatherSwitch?.isChecked ?: current.weatherEnabled,
                autoLocate = autoLocateSwitch?.isChecked ?: current.autoLocate,
                solarTermEnabled = solarTermSwitch?.isChecked ?: current.solarTermEnabled,
                duckOthers = duckSwitch?.isChecked ?: current.duckOthers,
                placeName = cityInput?.text?.toString()?.trim() ?: current.placeName
            )
        )
    }

    private fun buildPreviewText(): String {
        val now = ZonedDateTime.now()
        val extras = Settings.extras(this)
        val term = try {
            if (extras.solarTermEnabled) SolarTerms.infoFor(now.toLocalDate(), now.zone).speech else null
        } catch (_: Throwable) {
            null
        }
        return BroadcastText.build(BroadcastText.Parts(now, lastWeatherSpeech, term))
    }

    /** 后台计算节气与天气，主线程只更新文字。 */
    private fun refreshPreview() {
        val target = previewView ?: return
        target.text = "正在获取天气与节气…"
        val now = ZonedDateTime.now()
        val extras = Settings.extras(this)
        val runner = io()
        if (runner == null) {
            target.text = "预览不可用"
            return
        }
        runner.execute {
            val termInfo = try {
                if (extras.solarTermEnabled) SolarTerms.infoFor(now.toLocalDate(), now.zone) else null
            } catch (_: Throwable) {
                null
            }

            var weatherLine = ""
            if (extras.weatherEnabled) {
                weatherLine = try {
                    fetchWeatherLine(extras)
                } catch (error: Throwable) {
                    "天气获取失败：" + (error.message ?: "未知错误")
                }
            }

            val finalWeather = weatherLine
            mainHandler.post {
                try {
                    val isReal = finalWeather.isNotEmpty() &&
                        !finalWeather.contains("获取失败") &&
                        !finalWeather.contains("定位")
                    lastWeatherSpeech = if (isReal) finalWeather else null
                    val termLine = if (termInfo != null) {
                        "今日节气：${termInfo.title}（${termInfo.speech}）"
                    } else {
                        "今日节气：未开启"
                    }
                    val timeText = now.format(
                        DateTimeFormatter.ofPattern("M月d日 EEEE HH:mm", Locale.SIMPLIFIED_CHINESE)
                    )
                    val weatherText = if (finalWeather.isEmpty()) "未开启" else finalWeather
                    val coords = Settings.extras(this@MainActivity)
                    val coordsLine = if (coords.hasLocation)
                        "\n坐标：" + String.format(Locale.US, "%.4f, %.4f", coords.latitude, coords.longitude)
                    else ""
                    previewView?.text = "时间：$timeText\n天气：$weatherText\n$termLine$coordsLine"
                } catch (_: Throwable) {
                    previewView?.text = "预览刷新失败"
                }
            }
        }
    }

    private fun fetchWeatherLine(extras: Extras): String {
        val context = this
        var lat = extras.latitude
        var lon = extras.longitude

        if (extras.autoLocate && LocationHelper.hasPermission(context)) {
            val latch = CountDownLatch(1)
            val holder = AtomicReference<LocationHelper.Point?>(null)
            mainHandler.post {
                try {
                    LocationHelper.request(context, 6_000L) { point ->
                        holder.set(point)
                        latch.countDown()
                    }
                } catch (_: Throwable) {
                    latch.countDown()
                }
            }
            try {
                latch.await(7, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val point = holder.get()
            if (point != null) {
                lat = point.latitude
                lon = point.longitude
                Settings.saveLocation(context, lat, lon)
            }
        }

        if (lat.isNaN() || lon.isNaN()) {
            return Settings.cachedWeather(context).takeIf { it.isNotBlank() }
                ?: "未开启定位或未授权，且无缓存坐标"
        }

        val result = WeatherClient.fetch(lat, lon)
        val weather = result.getOrNull()
        if (weather == null) {
            val cached = Settings.cachedWeather(context)
            return if (cached.isNotBlank()) cached
            else "天气获取失败：" + (result.exceptionOrNull()?.message ?: "未知错误")
        }
        val speech = weather.speech(extras.placeName.takeIf { it.isNotBlank() })
        Settings.saveCachedWeather(context, speech)
        return speech
    }

    private fun renderSlots() {
        val container = slotContainer ?: return
        container.removeAllViews()
        var i = 0
        while (i < slots.size) {
            val index = i
            val slot = slots[i]
            val row = LinearLayout(this)

            val startButton = Button(this)
            startButton.text = Schedule.clock(slot.start)
            startButton.setOnClickListener {
                pickTime(slot.start) { hour, minute ->
                    if (index < slots.size) {
                        slots[index] = slots[index].copy(start = hour * 60 + minute)
                        renderSlots()
                    }
                }
            }
            row.addView(startButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val divider = TextView(this)
            divider.text = " 至 "
            row.addView(divider)

            val endButton = Button(this)
            endButton.text = Schedule.clock(slot.end)
            endButton.setOnClickListener {
                pickTime(slot.end) { hour, minute ->
                    if (index < slots.size) {
                        slots[index] = slots[index].copy(end = hour * 60 + minute)
                        renderSlots()
                    }
                }
            }
            row.addView(endButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val deleteButton = Button(this)
            deleteButton.text = "删除"
            deleteButton.setOnClickListener {
                if (index < slots.size) {
                    slots.removeAt(index)
                    renderSlots()
                }
            }
            row.addView(deleteButton)

            container.addView(row)
            i++
        }
    }

    private fun pickTime(current: Int, onPicked: (Int, Int) -> Unit) {
        try {
            TimePickerDialog(this, { _, hour, minute -> onPicked(hour, minute) }, current / 60, current % 60, true).show()
        } catch (error: Throwable) {
            statusView?.text = "选择时间失败：${error.message}"
        }
    }

    private fun readSchedule(): Schedule? {
        val selected = HashSet<Int>()
        var i = 0
        while (i < dayChecks.size) {
            if (dayChecks[i].isChecked) selected.add(i + 1)
            i++
        }
        if (selected.isEmpty() || slots.isEmpty()) return null
        val intervalValue = intervals.getOrElse(intervalSpinner?.selectedItemPosition ?: 4) { 30 }
        val modeValue = modeSpinner?.selectedItemPosition ?: 0
        return Schedule(selected, ArrayList(slots), modeValue, intervalValue)
    }

    private fun showStatus(message: String, schedule: Schedule) {
        val now = ZonedDateTime.now()
        val next = if (Settings.enabled(this)) ClockAlarms.next(this) else schedule.next(now, now)
        val formatted = next?.format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) ?: "没有可用时间"
        val modeIndex = schedule.mode.coerceIn(0, Schedule.modes.size - 1)
        statusView?.text = "$message\n${Schedule.modes[modeIndex]}\n下一次计划：$formatted"
    }

    override fun onInit(result: Int) {
        speechInitialized = true
        try {
            if (result == TextToSpeech.SUCCESS) {
                val language = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                speechReady = language != null && language >= TextToSpeech.LANG_AVAILABLE &&
                    tts?.let { TimeSpeech.configure(it, packageName, focus()) } == true
            }
        } catch (_: Throwable) {
            speechReady = false
        }
        if (!speechReady) {
            mainHandler.post {
                statusView?.text = "中文语音不可用，请在系统文字转语音设置中安装中文语音（界面仍可用）"
            }
        }
    }

    private fun preview(text: String, source: String) {
        if (!speechReady) {
            statusView?.text = if (speechInitialized) "中文语音不可用，请检查系统文字转语音设置" else "正在准备中文语音…"
            return
        }
        val now = ZonedDateTime.now()
        previewTracker.clear()
        focus().request(Settings.extras(this).duckOthers)
        val id = previewTracker.begin(SpeechRecord(now, text, source))
        val result = try {
            TimeSpeech.speak(tts, text, id)
        } catch (_: Throwable) {
            -1
        }
        if (result != TextToSpeech.SUCCESS) {
            previewTracker.fail(id)
            focusHelper?.release()
        }
        statusView?.text = if (result == TextToSpeech.SUCCESS) "正在试听：$text" else "试听失败，请检查系统语音设置"
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
            } catch (error: Throwable) {
                statusView?.text = "无法打开权限设置：${error.message}"
            }
        } else {
            statusView?.text = "已具备锁屏播报所需闹钟权限"
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            updateToggleButton()
            if (Settings.enabled(this) && ClockAlarms.permitted(this)) {
                startForegroundService(Intent(this, ClockService::class.java).setAction(ClockService.RESTORE))
            }
        } catch (_: Throwable) {
            // 恢复失败不影响界面
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) refreshPreview()
    }

    override fun onDestroy() {
        previewTracker.clear()
        focusHelper?.release()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {
            // 忽略
        }
        tts = null
        executor?.shutdownNow()
        executor = null
        super.onDestroy()
    }
}
