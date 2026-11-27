package com.example.voiceclock

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.TextWatcher
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
 * 交互约定：
 * - 所有设置项改动即自动保存，没有「保存设置」按钮；
 * - 若报时已开启，改动会通知服务重新排程立即生效；
 * - 提示音可调用系统铃声选择器更换。
 */
class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private var statusView: TextView? = null
    private var previewView: TextView? = null
    private var modeSpinner: Spinner? = null
    private var intervalSpinner: Spinner? = null
    private var slotContainer: LinearLayout? = null
    private var weatherSwitch: Switch? = null
    private var solarTermSwitch: Switch? = null
    // 上下班提醒
    private var offWorkEnabledSwitch: Switch? = null
    private var offWorkOnlyHeadsetSwitch: Switch? = null
    private var offWorkVibrateSwitch: Switch? = null
    private var offWorkVibrateModeSpinner: Spinner? = null
    private var offWorkRangeContainer: LinearLayout? = null
    private val offWorkDayChecks = ArrayList<CheckBox>()
    // 时间总开关 + 细粒度
    private var timeSwitch: Switch? = null
    private var speakClockSwitch: Switch? = null
    private var speakDateSwitch: Switch? = null
    private var speakWeekdaySwitch: Switch? = null
    private var speakPeriodSwitch: Switch? = null
    // 天气细粒度
    private var speakWeatherTextSwitch: Switch? = null
    private var speakTemperatureSwitch: Switch? = null
    private var speakApparentSwitch: Switch? = null
    private var speakPrecipitationSwitch: Switch? = null
    private var autoLocateSwitch: Switch? = null
    private var duckSwitch: Switch? = null
    private var cityInput: EditText? = null
    private var chimeButton: Button? = null
    private var waveformView: WaveformView? = null
    private var chimeLengthHint: TextView? = null
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
    private var focusHelper: AudioFocusHelper? = null
    private var chimePlayer: ChimePlayer? = null

    /** 程序化设置控件时抑制自动保存。 */
    private var suppressAutoSave = false

    /** 输入框防抖保存。 */
    private val citySaveRunnable = Runnable { persistAll() }

    @Volatile private var lastWeatherSpeech: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            executor = Executors.newSingleThreadExecutor()
            buildUi()
        } catch (error: Throwable) {
            showFatal("界面初始化失败", error)
        }
    }

    private fun focus(): AudioFocusHelper =
        focusHelper ?: AudioFocusHelper(applicationContext).also { focusHelper = it }

    private fun chime(): ChimePlayer =
        chimePlayer ?: ChimePlayer(applicationContext).also { chimePlayer = it }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun showFatal(title: String, error: Throwable) {
        val detail = buildString {
            append(title).append('\n')
            append(error.javaClass.name).append(": ").append(error.message ?: "").append("\n\n")
            error.cause?.let {
                append("Caused by: ").append(it.javaClass.name).append(": ").append(it.message ?: "").append("\n\n")
            }
            append(error.stackTraceToString().take(1200))
        }
        try {
            val view = TextView(this)
            view.text = detail
            view.textSize = 12f
            view.setPadding(dp(16), dp(48), dp(16), dp(16))
            val scroll = ScrollView(this)
            scroll.addView(view)
            setContentView(scroll)
        } catch (_: Throwable) {
            // 无能为力
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
        scroll.isFillViewport = true

        addLabel(root, "语音报时播报器", 28f)
        addLabel(root, "定时播报：时间 + 天气（自动定位）+ 节气。所有设置改动自动保存。", 14f)

        // ---------- 播报内容 ----------
        addLabel(root, "播报内容", 20f)
        suppressAutoSave = true

        // 时间总开关 + 细粒度
        addLabel(root, "时间", 16f)
        timeSwitch = makeSwitch(root, "播报时间内容（总开关，关闭后不念时间/日期/星期）", extras.timeEnabled)
        speakClockSwitch = makeSwitch(root, "  时间（时:分）", extras.speakClock)
        speakDateSwitch = makeSwitch(root, "  日期（月日）", extras.speakDate)
        speakWeekdaySwitch = makeSwitch(root, "  星期", extras.speakWeekday)
        speakPeriodSwitch = makeSwitch(root, "  时段词（凌晨/上午/下午…）", extras.speakPeriod)
        // 总开关关闭时，子项置灰，避免误以为还能生效
        timeSwitch?.setOnCheckedChangeListener { _, checked ->
            setTimeDetailEnabled(checked)
            onSettingChanged()
        }
        setTimeDetailEnabled(extras.timeEnabled)

        // 天气总开关
        addLabel(root, "天气", 16f)

        weatherSwitch = makeSwitch(root, "播报天气内容（总开关，关闭后不定位、不请求天气）", extras.weatherEnabled)
        speakWeatherTextSwitch = makeSwitch(root, "  天气现象（多云/小雨）", extras.speakWeatherText)
        speakTemperatureSwitch = makeSwitch(root, "  当前气温", extras.speakTemperature)
        speakApparentSwitch = makeSwitch(root, "  体感温度", extras.speakApparent)
        speakPrecipitationSwitch = makeSwitch(root, "  降水量", extras.speakPrecipitation)

        autoLocateSwitch = Switch(this)
        autoLocateSwitch?.text = "自动定位（前台粗定位）"
        autoLocateSwitch?.isChecked = extras.autoLocate
        autoLocateSwitch?.setOnCheckedChangeListener { _, _ -> onSettingChanged() }
        root.addView(autoLocateSwitch)

        cityInput = EditText(this)
        cityInput?.hint = "城市名（仅用于播报前缀，可留空）"
        cityInput?.setText(extras.placeName)
        cityInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressAutoSave) return
                mainHandler.removeCallbacks(citySaveRunnable)
                mainHandler.postDelayed(citySaveRunnable, 600)
            }
        })
        root.addView(cityInput)

        // 天气总开关与子项联动：关闭后子项与定位置灰
        weatherSwitch?.setOnCheckedChangeListener { _, checked ->
            setWeatherDetailEnabled(checked)
            if (checked) requestLocationPermissionIfNeeded()
            onSettingChanged()
        }
        setWeatherDetailEnabled(extras.weatherEnabled)

        addLabel(root, "节气", 16f)
        solarTermSwitch = makeSwitch(root, "播报二十四节气", extras.solarTermEnabled)

        // ---------- 下班提醒 ----------
        addLabel(root, "上下班提醒（独立于定时报时）", 20f)
        addLabel(root, "可设置多个时段；相邻时段之间的空档会自动当作休息时间。", 13f)

        offWorkEnabledSwitch = makeSwitch(root, "启用上下班提醒", OffWorkSettingsStorage.load(this).enabled)

        // 时段列表的容器，增删时段后整体重建
        offWorkRangeContainer = LinearLayout(this)
        offWorkRangeContainer?.orientation = LinearLayout.VERTICAL
        root.addView(offWorkRangeContainer)

        addButton(root, "＋ 添加时段") { addOffWorkRange() }

        addLabel(root, "生效星期（所有时段共用）", 15f)
        val offWorkDayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val offWorkWeekContainer = LinearLayout(this)
        offWorkWeekContainer.orientation = LinearLayout.HORIZONTAL
        for (i in 1..7) {
            val cb = CheckBox(this)
            cb.text = offWorkDayNames[i - 1]
            cb.isChecked = OffWorkSettingsStorage.load(this).weekdays.contains(i)
            cb.setOnCheckedChangeListener { view, checked ->
                val cur = OffWorkSettingsStorage.load(this@MainActivity)
                val newSet = if (checked) cur.weekdays + i else cur.weekdays - i
                if (newSet.isEmpty()) {
                    (view as CheckBox).isChecked = true
                    statusView?.text = "至少要选一天，否则提醒不会触发"
                    return@setOnCheckedChangeListener
                }
                OffWorkSettingsStorage.save(this@MainActivity, cur.copy(weekdays = newSet))
                onSettingChanged()
            }
            offWorkDayChecks.add(cb)
            offWorkWeekContainer.addView(cb)
        }
        root.addView(offWorkWeekContainer)

        addLabel(root, "默认振动；关闭「播报时振动」后只播报不振动。", 13f)
        offWorkOnlyHeadsetSwitch = makeSwitch(
            root, "只在耳机/蓝牙连接时播报", OffWorkSettingsStorage.load(this).onlyHeadset
        )
        offWorkVibrateSwitch = makeSwitch(
            root, "播报时振动", OffWorkSettingsStorage.load(this).vibrate
        )
        addLabel(root, "振动模式", 15f)
        offWorkVibrateModeSpinner = Spinner(this)
        val vibrateLabels = listOf("短振", "长振", "两次短振")
        val vibrateValues = VibrateMode.values()
        offWorkVibrateModeSpinner?.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, vibrateLabels
        )
        offWorkVibrateModeSpinner?.setSelection(
            vibrateValues.indexOf(OffWorkSettingsStorage.load(this).vibrateMode).coerceAtLeast(0)
        )
        offWorkVibrateModeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val cur = OffWorkSettingsStorage.load(this@MainActivity)
                OffWorkSettingsStorage.save(this@MainActivity, cur.copy(vibrateMode = vibrateValues[position]))
                onSettingChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        root.addView(offWorkVibrateModeSpinner)

        addLabel(root, "试听", 15f)
        addButton(root, "试听上班到点") { previewReminder(ReminderKind.ON_START) }
        addButton(root, "试听下班到点") { previewReminder(ReminderKind.ON_END) }
        addButton(root, "试听休息开始") { previewReminder(ReminderKind.BREAK_START) }
        addButton(root, "试听休息结束") { previewReminder(ReminderKind.BREAK_END) }

        // 首次渲染时段列表
        rebuildOffWorkRanges()

        // ---------- 音频 ----------
        addLabel(root, "音频", 20f)

        duckSwitch = Switch(this)
        duckSwitch?.text = "播报时压低其他应用音量（关闭则完全混音）"
        duckSwitch?.isChecked = extras.duckOthers
        duckSwitch?.setOnCheckedChangeListener { _, _ -> onSettingChanged() }
        root.addView(duckSwitch)

        chimeButton = Button(this)
        chimeButton?.text = chimeButtonText(extras)
        chimeButton?.setOnClickListener { chooseChime() }
        root.addView(chimeButton)

        // 提示音波形：点击/拖动选择播放时长
        addLabel(root, "提示音播放长度（点击或拖动波形选择）", 15f)
        chimeLengthHint = TextView(this)
        chimeLengthHint?.textSize = 13f
        chimeLengthHint?.setPadding(0, dp(4), 0, dp(4))
        root.addView(chimeLengthHint)

        waveformView = WaveformView(this)
        waveformView?.interactive = true
        waveformView?.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(96)
        )
        waveformView?.onCutChanged = { selectedMs, totalMs ->
            onChimeLengthPicked(selectedMs, totalMs)
        }
        root.addView(waveformView)
        refreshWaveform()

        addButton(root, "试听提示音") { playChimePreview() }

        addButton(root, "不播提示音") {
            val current = Settings.extras(this)
            Settings.saveExtras(
                this,
                current.copy(chimeSource = ChimeSource.SILENT, chimeUri = "", chimeLabel = "")
            )
            chimeButton?.text = chimeButtonText(Settings.extras(this))
            refreshWaveform()
            notifyServiceReload()
        }

        addButton(root, "恢复内置提示音") {
            val current = Settings.extras(this)
            Settings.saveExtras(
                this,
                current.copy(chimeSource = ChimeSource.DEFAULT, chimeUri = "", chimeLabel = "")
            )
            chimeButton?.text = chimeButtonText(Settings.extras(this))
            refreshWaveform()
            notifyServiceReload()
        }

        suppressAutoSave = false
        addLabel(root, "播报会自动跟随耳机、蓝牙音箱等当前输出设备。", 14f)

        previewView = TextView(this)
        previewView?.textSize = 14f
        previewView?.setPadding(dp(12), dp(10), dp(12), dp(10))
        previewView?.text = "正在获取天气与节气…"
        root.addView(previewView)

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

        // ---------- 排程 ----------
        addLabel(root, "报时模式", 20f)
        suppressAutoSave = true

        modeSpinner = Spinner(this)
        modeSpinner?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Schedule.modes)
        modeSpinner?.setSelection(saved.mode.coerceIn(0, Schedule.modes.size - 1))
        root.addView(modeSpinner)

        intervalSpinner = Spinner(this)
        intervalSpinner?.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            intervals.map { n -> "每 $n 分钟" }
        )
        intervalSpinner?.setSelection(intervals.indexOf(saved.interval).coerceAtLeast(0))
        root.addView(intervalSpinner)

        modeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                intervalSpinner?.visibility = if (position == 0) View.VISIBLE else View.GONE
                if (!suppressAutoSave) onSettingChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        intervalSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!suppressAutoSave) onSettingChanged()
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
                check.setOnCheckedChangeListener { _, _ -> onSettingChanged() }
                dayChecks.add(check)
                row.addView(check, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                index++
                column++
            }
            root.addView(row)
        }

        addLabel(root, "每天的报时时段", 20f)
        addLabel(root, "包含开始时间，不包含结束时间。开始和结束相同表示全天；结束早于开始表示跨午夜。", 13f)
        slots.addAll(saved.slots)
        slotContainer = LinearLayout(this)
        slotContainer?.orientation = LinearLayout.VERTICAL
        root.addView(slotContainer)
        renderSlots()

        addButton(root, "＋ 添加时段") {
            slots.add(TimeSlot(8 * 60, 22 * 60))
            renderSlots()
            onSettingChanged()
        }
        suppressAutoSave = false

        // ---------- 开关 ----------
        toggleButton = Button(this)
        toggleButton?.setOnClickListener {
            try {
                if (Settings.enabled(this)) {
                    disableBroadcast()
                } else {
                    if (readSchedule() == null) {
                        statusView?.text = "请至少选择一天，并添加一个时段"
                        return@setOnClickListener
                    }
                    enableBroadcast()
                }
            } catch (error: Throwable) {
                statusView?.text = "操作失败：${error.message}"
            }
        }
        root.addView(toggleButton)

        statusView = TextView(this)
        statusView?.textSize = 14f
        statusView?.setPadding(0, dp(16), 0, dp(16))
        root.addView(statusView)

        setContentView(scroll)

        updateToggleButton()
        showStatus(if (Settings.enabled(this)) "报时已开启" else "当前未开启")

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

    private fun chimeButtonText(extras: Extras): String = when (extras.chimeSource) {
        ChimeSource.DEFAULT -> "提示音：内置三声滴（点击选择系统铃声）"
        ChimeSource.SILENT -> "提示音：不播放（点击选择系统铃声）"
        ChimeSource.SYSTEM -> "提示音：${extras.chimeLabel.ifBlank { "系统铃声" }}（点击更换）"
    }

    /** 根据当前提示音设置刷新波形图，并同步截断标记。 */
    private fun refreshWaveform() {
        val view = waveformView ?: return
        try {
            val extras = Settings.extras(this)
            when (extras.chimeSource) {
                ChimeSource.SILENT -> view.reset()
                ChimeSource.DEFAULT -> view.loadResource(R.raw.time_chime)
                ChimeSource.SYSTEM -> {
                    if (extras.chimeUri.isBlank()) view.reset()
                    else view.load(android.net.Uri.parse(extras.chimeUri))
                }
            }
            applyCutRatio(extras, view)
        } catch (_: Throwable) {
            view.reset()
        }
    }

    /**
     * 把已保存的时长还原到波形上。
     * 波形加载是异步的，时长要到解码完成后才有，因此延迟重试。
     */
    private fun applyCutRatio(extras: Extras, view: WaveformView, attempt: Int = 0) {
        val duration = view.audioDurationMs
        if (duration <= 0L) {
            if (attempt < 20) {
                mainHandler.postDelayed({ applyCutRatio(extras, view, attempt + 1) }, 100)
            } else {
                view.setCutRatio(1f)
                updateChimeLengthHint(0L, 0L)
            }
            return
        }
        val maxSeconds = extras.chimeMaxSeconds
        if (maxSeconds <= 0) {
            view.setSelectedPlayMs(duration)
        } else {
            view.setSelectedPlayMs(maxSeconds * 1000L)
        }
        updateChimeLengthHint(view.selectedPlayMs(), duration)
    }

    /** 用户在波形上选定时长后调用。 */
    private fun onChimeLengthPicked(selectedMs: Long, totalMs: Long) {
        if (suppressAutoSave) return
        try {
            val current = Settings.extras(this)
            // 选中整段时记为 0（完整播放），避免总时长略有差异导致行为不一致
            val seconds = if (selectedMs >= totalMs) 0 else (selectedMs / 1000L).toInt().coerceAtLeast(1)
            Settings.saveExtras(this, current.copy(chimeMaxSeconds = seconds))
            updateChimeLengthHint(selectedMs, totalMs)
            notifyServiceReload()
        } catch (error: Throwable) {
            statusView?.text = "保存播放长度失败：${error.message}"
        }
    }

    /** 更新「播放至 x.x 秒 / 共 y.y 秒」提示。 */
    private fun updateChimeLengthHint(selectedMs: Long, totalMs: Long) {
        val hint = chimeLengthHint ?: return
        hint.text = if (totalMs <= 0L) {
            "波形加载中…"
        } else if (selectedMs >= totalMs) {
            String.format(Locale.US, "完整播放（共 %.1f 秒）", totalMs / 1000f)
        } else {
            String.format(
                Locale.US,
                "播放至 %.1f 秒 / 共 %.1f 秒",
                selectedMs / 1000f,
                totalMs / 1000f
            )
        }
    }

    /** 调用系统铃声选择器。 */
    private fun chooseChime() {
        try {
            val current = Settings.extras(this)
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            intent.putExtra(
                RingtoneManager.EXTRA_RINGTONE_TYPE,
                RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE
            )
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择播报提示音")
            if (current.chimeSource == ChimeSource.SYSTEM && current.chimeUri.isNotBlank()) {
                intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current.chimeUri))
            }
            startActivityForResult(intent, REQUEST_PICK_CHIME)
        } catch (error: Throwable) {
            statusView?.text = "无法打开铃声选择器：${error.message}"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_CHIME || resultCode != RESULT_OK) return
        try {
            val uri: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            // 部分 ROM 在选择「默认」时返回 Uri.EMPTY 而不是 null，
            // 若直接保存会得到空 URI，播放时静默回退成内置提示音。
            if (uri == null || uri == Uri.EMPTY || uri.toString().isBlank()) {
                statusView?.text = "未选择有效提示音，保持原设置"
                return
            }
            val label = try {
                RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "系统铃声"
            } catch (_: Throwable) {
                "系统铃声"
            }
            val current = Settings.extras(this)
            Settings.saveExtras(
                this,
                current.copy(chimeSource = ChimeSource.SYSTEM, chimeUri = uri.toString(), chimeLabel = label)
            )
            chimeButton?.text = chimeButtonText(Settings.extras(this))
            refreshWaveform()
            notifyServiceReload()
            statusView?.text = "提示音已设为：$label"
            // 读取系统铃声需要音频权限，缺失会导致播放回退成内置音
            ensureAudioReadPermission()
        } catch (error: Throwable) {
            statusView?.text = "保存提示音失败：${error.message}"
        }
    }

    /** 只试听提示音。 */
    private fun playChimePreview() {
        try {
            val extras = Settings.extras(this)
            if (extras.chimeSource == ChimeSource.SILENT) {
                statusView?.text = "当前为「不播提示音」，不会发出声音"
                return
            }
            if (extras.chimeSource == ChimeSource.SYSTEM && extras.chimeUri.isBlank()) {
                statusView?.text = "已选「系统铃声」但没有有效铃声，请重新点击上方提示音按钮选择"
                return
            }
            focus().request(extras.duckOthers)
            statusView?.text = "正在试听提示音…"
            // 波形跟随播放进度动画
            chime().setProgressCallback { progress ->
                mainHandler.post { waveformView?.setProgress(progress) }
            }
            chime().play(extras) {
                mainHandler.post {
                    chime().setProgressCallback(null)
                    waveformView?.setProgress(0f)
                    focusHelper?.release()
                    val problem = chime().lastProblem
                    statusView?.text = if (problem.isNullOrBlank()) {
                        "提示音试听完成"
                    } else {
                        "已用内置提示音试听：$problem"
                    }
                }
            }
        } catch (error: Throwable) {
            statusView?.text = "试听提示音失败：${error.message}"
        }
    }

    /** 任意设置项变动：自动保存 + 通知服务重新排程。 */
    private fun onSettingChanged() {
        if (suppressAutoSave) return
        persistAll()
        notifyServiceReload()
        showStatus(if (Settings.enabled(this)) "设置已自动保存" else "设置已自动保存")
    }

    /**
     * 把界面上的全部设置写入本地。
     *
     * 每天/星期/时刻/提前分钟等下班提醒项在各自的控件回调里已经落盘，
     * 这里只同步「总开关」「只耳机」「振动」三个开关，避免用旧值覆盖新选择。
     */
    private fun persistAll() {
        try {
            readScheduleForSave()?.let { Settings.save(this, it) }
            val current = Settings.extras(this)
            Settings.saveExtras(
                this,
                current.copy(
                    timeEnabled = timeSwitch?.isChecked ?: current.timeEnabled,
                    speakClock = speakClockSwitch?.isChecked ?: current.speakClock,
                    speakDate = speakDateSwitch?.isChecked ?: current.speakDate,
                    speakWeekday = speakWeekdaySwitch?.isChecked ?: current.speakWeekday,
                    speakPeriod = speakPeriodSwitch?.isChecked ?: current.speakPeriod,
                    weatherEnabled = weatherSwitch?.isChecked ?: current.weatherEnabled,
                    speakWeatherText = speakWeatherTextSwitch?.isChecked ?: current.speakWeatherText,
                    speakTemperature = speakTemperatureSwitch?.isChecked ?: current.speakTemperature,
                    speakApparent = speakApparentSwitch?.isChecked ?: current.speakApparent,
                    speakPrecipitation = speakPrecipitationSwitch?.isChecked ?: current.speakPrecipitation,
                    solarTermEnabled = solarTermSwitch?.isChecked ?: current.solarTermEnabled,
                    autoLocate = autoLocateSwitch?.isChecked ?: current.autoLocate,
                    duckOthers = duckSwitch?.isChecked ?: current.duckOthers,
                    placeName = cityInput?.text?.toString()?.trim() ?: current.placeName
                )
            )
            // 时段、星期、振动模式在各自控件回调里已落盘，
            // 这里只同步总开关与两个布尔开关，避免用旧值覆盖新选择
            val off = OffWorkSettingsStorage.load(this)
            val updated = off.copy(
                enabled = offWorkEnabledSwitch?.isChecked ?: off.enabled,
                onlyHeadset = offWorkOnlyHeadsetSwitch?.isChecked ?: off.onlyHeadset,
                vibrate = offWorkVibrateSwitch?.isChecked ?: off.vibrate
            )
            if (updated != off) OffWorkSettingsStorage.save(this, updated)
            OffWorkScheduler.schedule(this)
        } catch (error: Throwable) {
            statusView?.text = "自动保存失败：${error.message}"
        }
    }

    /** 已开启时让服务重新计算下一次计划。 */
    private fun notifyServiceReload() {
        try {
            if (Settings.enabled(this)) {
                startService(Intent(this, ClockService::class.java).setAction(ClockService.RELOAD))
            }
        } catch (_: Throwable) {
            // 后台限制可能失败，不影响已保存的设置
        }
        updateToggleButton()
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
                        // 忽略
                    }
                    focusHelper?.release()
                    mainHandler.post { statusView?.text = "试听播放完成，已保存报时记录" }
                }
            }

            override fun onError(utteranceId: String) {
                if (TimeSpeech.isAuxiliary(utteranceId)) return
                previewTracker.fail(TimeSpeech.speechId(utteranceId))
                focusHelper?.release()
                mainHandler.post { statusView?.text = "试听失败，请检查系统语音设置" }
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

    /** 时间总开关联动：关闭时把子项置灰。 */
    private fun setTimeDetailEnabled(enabled: Boolean) {        for (view in listOf(
            speakClockSwitch, speakDateSwitch, speakWeekdaySwitch, speakPeriodSwitch
        )) {
            view?.isEnabled = enabled
            view?.alpha = if (enabled) 1f else 0.45f
        }
    }

    /** 天气总开关联动：关闭时把子项与定位置灰。 */
    private fun setWeatherDetailEnabled(enabled: Boolean) {
        for (view in listOf(
            speakWeatherTextSwitch, speakTemperatureSwitch,
            speakApparentSwitch, speakPrecipitationSwitch,
            autoLocateSwitch, cityInput
        )) {
            view?.isEnabled = enabled
            view?.alpha = if (enabled) 1f else 0.45f
        }
    }

    // ---------- 上下班提醒：时段列表 ----------

    /** 时间段有变化后重新渲染整个列表（表单逻辑简单，重建比局部更新更可靠）。 */
    private fun rebuildOffWorkRanges() {
        val container = offWorkRangeContainer ?: return
        container.removeAllViews()
        val settings = OffWorkSettingsStorage.load(this)
        if (settings.ranges.isEmpty()) {
            val hint = TextView(this)
            hint.text = "还没有时段，点下方「＋ 添加时段」"
            hint.textSize = 13f
            container.addView(hint)
            return
        }
        settings.ranges.forEachIndexed { index, range ->
            container.addView(buildRangeRow(index, range))
        }
    }

    /**
     * 单个时段的编辑卡片。
     *
     * 交互：上班/下班/休息三个总开关控制各自细则的显示，
     * 收起时只留一行标题，避免一屏塞满复选框。
     */
    private fun buildRangeRow(index: Int, range: TimeRange): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(8), dp(8), dp(8), dp(8))

        val titleRow = LinearLayout(this)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = android.view.Gravity.CENTER_VERTICAL

        val enable = CheckBox(this)
        enable.text = "第 ${index + 1} 段 ${range.label()}"
        enable.isChecked = range.enabled
        enable.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        enable.setOnCheckedChangeListener { _, checked ->
            updateRange(index) { it.copy(enabled = checked) }
        }
        titleRow.addView(enable)

        val delete = Button(this)
        delete.text = "删除"
        delete.setOnClickListener { deleteOffWorkRange(index) }
        titleRow.addView(delete)
        card.addView(titleRow)

        // ---- 上班 ----
        val workStartMaster = CheckBox(this)
        workStartMaster.text = "上班提醒"
        workStartMaster.isChecked = range.remindWorkStart
        card.addView(workStartMaster)

        val startDetail = LinearLayout(this)
        startDetail.orientation = LinearLayout.VERTICAL
        startDetail.visibility = if (range.remindWorkStart) View.VISIBLE else View.GONE

        val startButton = Button(this)
        startButton.text = "上班时刻 ${String.format(Locale.US, "%02d:%02d", range.startHour, range.startMinute)}"
        startButton.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                updateRange(index) { it.copy(startHour = h, startMinute = m) }
            }, range.startHour, range.startMinute, true).show()
        }
        startDetail.addView(reminderCheck("上班前提醒（提前 ${range.advanceStartMinutes} 分钟）",
            range.remindBeforeStart) { checked -> updateRange(index) { it.copy(remindBeforeStart = checked) } })
        startDetail.addView(reminderCheck("上班到点提醒", range.remindOnStart) { checked ->
            updateRange(index) { it.copy(remindOnStart = checked) }
        })
        // 时刻按钮放在详情最前，收起时也同样可见，保证随时能改时间
        startDetail.addView(startButton, 0)
        card.addView(startDetail)

        workStartMaster.setOnCheckedChangeListener { _, checked ->
            updateRange(index) { it.copy(remindWorkStart = checked) }
        }

        // ---- 下班 ----
        val workEndMaster = CheckBox(this)
        workEndMaster.text = "下班提醒"
        workEndMaster.isChecked = range.remindWorkEnd
        card.addView(workEndMaster)

        val endDetail = LinearLayout(this)
        endDetail.orientation = LinearLayout.VERTICAL
        endDetail.visibility = if (range.remindWorkEnd) View.VISIBLE else View.GONE

        val endButton = Button(this)
        endButton.text = "下班时刻 ${String.format(Locale.US, "%02d:%02d", range.endHour, range.endMinute)}"
        endButton.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                updateRange(index) { it.copy(endHour = h, endMinute = m) }
            }, range.endHour, range.endMinute, true).show()
        }
        endDetail.addView(reminderCheck("下班前提醒（提前 ${range.advanceEndMinutes} 分钟）",
            range.remindBeforeEnd) { checked -> updateRange(index) { it.copy(remindBeforeEnd = checked) } })
        endDetail.addView(reminderCheck("下班到点提醒", range.remindOnEnd) { checked ->
            updateRange(index) { it.copy(remindOnEnd = checked) }
        })
        endDetail.addView(endButton, 0)
        card.addView(endDetail)

        workEndMaster.setOnCheckedChangeListener { _, checked ->
            updateRange(index) { it.copy(remindWorkEnd = checked) }
        }

        // ---- 休息 ----
        val breakMaster = CheckBox(this)
        breakMaster.text = "休息提醒"
        // 休息没有单独的「总开关」字段，用「开始或结束任一开启」表示
        breakMaster.isChecked = range.remindBreakStart || range.remindBreakEnd
        card.addView(breakMaster)

        val breakDetail = LinearLayout(this)
        breakDetail.orientation = LinearLayout.VERTICAL
        breakDetail.visibility = if (breakMaster.isChecked) View.VISIBLE else View.GONE

        breakDetail.addView(reminderCheck("休息开始提醒", range.remindBreakStart) { checked ->
            updateRange(index) { it.copy(remindBreakStart = checked) }
        })
        breakDetail.addView(reminderCheck("休息结束提醒", range.remindBreakEnd) { checked ->
            updateRange(index) { it.copy(remindBreakEnd = checked) }
        })

        // 自定义休息时间
        val customBreak = reminderCheck("自定义休息时间（默认用相邻时段间隙）", range.hasCustomBreak) { checked ->
            updateRange(index) {
                if (checked) it.copy(breakStartMinuteOfDay = 12 * 60, breakEndMinuteOfDay = 13 * 60 + 30)
                else it.copy(breakStartMinuteOfDay = -1, breakEndMinuteOfDay = -1)
            }
        }
        breakDetail.addView(customBreak)

        if (range.hasCustomBreak) {
            val breakRow = LinearLayout(this)
            breakRow.orientation = LinearLayout.HORIZONTAL

            val sLabel = Button(this)
            sLabel.text = "休息开始 " + minuteLabel(range.breakStartMinuteOfDay)
            sLabel.setOnClickListener {
                TimePickerDialog(this, { _, h, m ->
                    updateRange(index) { it.copy(breakStartMinuteOfDay = h * 60 + m) }
                }, range.breakStartMinuteOfDay / 60, range.breakStartMinuteOfDay % 60, true).show()
            }
            breakRow.addView(sLabel)

            val eLabel = Button(this)
            eLabel.text = "休息结束 " + minuteLabel(range.breakEndMinuteOfDay)
            eLabel.setOnClickListener {
                TimePickerDialog(this, { _, h, m ->
                    updateRange(index) { it.copy(breakEndMinuteOfDay = h * 60 + m) }
                }, range.breakEndMinuteOfDay / 60, range.breakEndMinuteOfDay % 60, true).show()
            }
            breakRow.addView(eLabel)
            breakDetail.addView(breakRow)
        }

        card.addView(breakDetail)

        breakMaster.setOnCheckedChangeListener { _, checked ->
            updateRange(index) { it.copy(remindBreakStart = checked, remindBreakEnd = checked) }
        }

        val divider = View(this)
        divider.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        divider.setBackgroundColor(0x22000000)
        card.addView(divider)

        return card
    }

    /** 生成一个带回调的复选框。 */
    private fun reminderCheck(text: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox {
        val box = CheckBox(this)
        box.text = text
        box.isChecked = checked
        box.setOnCheckedChangeListener { _, value -> onChange(value) }
        return box
    }

    /** 分钟数（0 点起算）转 HH:mm。 */
    private fun minuteLabel(minuteOfDay: Int): String =
        String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)

    /** 更新指定下标的时段并重新渲染。 */
    private fun updateRange(index: Int, transform: (TimeRange) -> TimeRange) {
        val current = OffWorkSettingsStorage.load(this)
        val ranges = current.ranges.toMutableList()
        val existing = ranges.getOrNull(index) ?: return
        ranges[index] = transform(existing)
        OffWorkSettingsStorage.save(this, current.copy(ranges = ranges))
        rebuildOffWorkRanges()
        onSettingChanged()
    }

    private fun addOffWorkRange() {
        val current = OffWorkSettingsStorage.load(this)
        OffWorkSettingsStorage.save(
            this, current.copy(ranges = current.ranges + TimeRange(startHour = 13, startMinute = 0,
                endHour = 18, endMinute = 0))
        )
        rebuildOffWorkRanges()
        onSettingChanged()
    }

    private fun deleteOffWorkRange(index: Int) {
        val current = OffWorkSettingsStorage.load(this)
        if (current.ranges.size <= 1) {
            statusView?.text = "至少保留一个时段"
            return
        }
        val ranges = current.ranges.toMutableList()
        ranges.removeAt(index)
        OffWorkSettingsStorage.save(this, current.copy(ranges = ranges))
        rebuildOffWorkRanges()
        onSettingChanged()
    }

    /** 试听某类提醒。 */
    private fun previewReminder(kind: ReminderKind) {
        try {
            val minutes = when (kind) {
                ReminderKind.BEFORE_START, ReminderKind.BEFORE_END -> 5
                else -> 0
            }
            val intent = Intent(this, OffWorkService::class.java).apply {
                action = "SPEAK"
                // preview=true：服务端据此跳过「总开关必须开启」的检查
                putExtra("preview", true)
                putExtra("kind", kind.name)
                putExtra("minutes", minutes)
            }
            // Android 8+ 用 startForegroundService；更低版本回退 startService
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            statusView?.text = "正在试听：${kind.text(minutes)}"
        } catch (error: Throwable) {
            statusView?.text = "试听失败：${error.message}"
        }
    }

    /** 创建一个带自动保存的开关，并添加到指定容器。 */
    private fun makeSwitch(parent: LinearLayout, text: String, checked: Boolean): Switch {
        val view = Switch(this)
        view.text = text
        view.isChecked = checked
        view.setOnCheckedChangeListener { _, _ -> onSettingChanged() }
        parent.addView(view)
        return view
    }

    private fun requestNeededPermissions() {
        try {
            val wanted = ArrayList<String>()
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                wanted.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val extras = Settings.extras(this)
            // 只有确实要播天气时才申请定位
            if (extras.hasWeatherContent &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
            ) {
                wanted.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            // 只有选了系统铃声时才需要音频读取权限
            if (extras.chimeSource == ChimeSource.SYSTEM && extras.chimeUri.isNotBlank() &&
                checkSelfPermission(audioReadPermission()) != PackageManager.PERMISSION_GRANTED
            ) {
                wanted.add(audioReadPermission())
            }
            if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 10)
        } catch (_: Throwable) {
            // 忽略
        }
    }

    /** 读取系统铃声所需的音频权限：Android 13 起按媒体类型细分。 */
    private fun audioReadPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun ensureAudioReadPermission() {
        try {
            if (checkSelfPermission(audioReadPermission()) == PackageManager.PERMISSION_GRANTED) return
            requestPermissions(arrayOf(audioReadPermission()), 11)
        } catch (_: Throwable) {
            // 忽略
        }
    }

    /** 重新打开天气总开关时补申请定位权限（启动时可能因天气关闭而未申请）。 */
    private fun requestLocationPermissionIfNeeded() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                return
            }
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 12)
        } catch (_: Throwable) {
            // 忽略
        }
    }

    private fun enableBroadcast() {
        if (!ClockAlarms.permitted(this)) {
            statusView?.text = "请允许“闹钟和提醒”，返回后再次点击，才能在锁屏时准时报播"
            openAlarmPermission()
            return
        }
        persistAll()
        Settings.setEnabled(this, true)
        try {
            startForegroundService(Intent(this, ClockService::class.java).setAction(ClockService.START))
        } catch (error: Throwable) {
            statusView?.text = "开启失败：${error.message}"
            return
        }
        updateToggleButton()
        showStatus("已开启报时")
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

    /** 用界面上的天气快照组装试听文本，与正式播报一致的开关规则。 */
    private fun buildPreviewText(): String {
        val now = ZonedDateTime.now()
        val extras = Settings.extras(this)
        val timeSentence = BroadcastText.timeSentence(now, extras)
        // 试听时没有天气对象，用已缓存的文本拼接（缓存本身是完整描述）
        val weatherSentence = if (extras.hasWeatherContent) {
            val cached = Settings.cachedWeather(this)
            if (cached.isNotBlank()) BroadcastText.simpleWeatherSentence(cached)
            else lastWeatherSpeech?.let { BroadcastText.simpleWeatherSentence(it) }
        } else {
            null
        }
        val termSentence = try {
            if (extras.solarTermEnabled) SolarTerms.infoFor(now.toLocalDate(), now.zone).speech else null
        } catch (_: Throwable) {
            null
        }
        return BroadcastText.build(timeSentence, weatherSentence, termSentence)
    }

    private fun refreshPreview() {
        val target = previewView ?: return
        target.text = "正在获取天气与节气…"
        val now = ZonedDateTime.now()
        val extras = Settings.extras(this)
        val runner = executor
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
            if (extras.hasWeatherContent) {
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
                    val timeText = if (extras.hasTimeContent) {
                        now.format(
                            DateTimeFormatter.ofPattern("M月d日 EEEE HH:mm", Locale.SIMPLIFIED_CHINESE)
                        )
                    } else {
                        "未开启"
                    }
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
                        onSettingChanged()
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
                        onSettingChanged()
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
                    onSettingChanged()
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

    /** 读取当前界面排程；未选天或无时段时返回 null（不覆盖已存值）。 */
    private fun readScheduleForSave(): Schedule? {
        if (dayChecks.isEmpty() || slots.isEmpty()) return null
        val selected = HashSet<Int>()
        var i = 0
        while (i < dayChecks.size) {
            if (dayChecks[i].isChecked) selected.add(i + 1)
            i++
        }
        if (selected.isEmpty()) return null
        val intervalValue = intervals.getOrElse(intervalSpinner?.selectedItemPosition ?: 4) { 30 }
        val modeValue = modeSpinner?.selectedItemPosition ?: 0
        return Schedule(selected, ArrayList(slots), modeValue, intervalValue)
    }

    private fun readSchedule(): Schedule? = readScheduleForSave()

    private fun showStatus(message: String) {
        val schedule = readScheduleForSave() ?: Settings.load(this)
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
                    tts?.let { TimeSpeech.configure(it, focus()) } == true
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

    /** 试听完整播报：先提示音，后正文，与正式播报一致。 */
    private fun preview(text: String, source: String) {
        if (!speechReady) {
            statusView?.text = if (speechInitialized) "中文语音不可用，请检查系统文字转语音设置" else "正在准备中文语音…"
            return
        }
        val extras = Settings.extras(this)
        focus().request(extras.duckOthers)
        statusView?.text = "正在试听…"
        chime().setProgressCallback { progress ->
            mainHandler.post { waveformView?.setProgress(progress) }
        }
        chime().play(extras) {
            mainHandler.post {
                chime().setProgressCallback(null)
                waveformView?.setProgress(0f)
                val now = ZonedDateTime.now()
                previewTracker.clear()
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
        }
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
            // 忽略
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) refreshPreview()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(citySaveRunnable)
        persistAll()
    }

    override fun onDestroy() {
        previewTracker.clear()
        focusHelper?.release()
        chimePlayer?.release()
        chimePlayer = null
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

    companion object {
        private const val REQUEST_PICK_CHIME = 21
    }
}
