package com.example.voiceclock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ClockService : Service(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initialized = false
    private var pendingSpeech: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var schedule = Schedule()
    private var next: ZonedDateTime? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val tracker = SpeechTracker()
    private var activeId: String? = null
    private var problem: String? = null
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var buildToken = 0
    private var workTimeout: Runnable? = null
    private var audioFocus: AudioFocusHelper? = null
    private var chime: ChimePlayer? = null

    private val speechTimeout = Runnable {
        val current = activeId
        if (current != null) {
            tracker.fail(current)
            activeId = null
        }
        pendingSpeech = null
        chime?.release()
        tts?.stop()
        problem = "语音播放超时，请检查中文语音和后台运行设置"
        releaseWakeLock()
        updateNotification()
    }

    override fun onCreate() {
        super.onCreate()
        audioFocus = AudioFocusHelper(applicationContext)
        chime = ChimePlayer(applicationContext)
        try {
            SpeechHistory(this).use { it.clearExpired() }
        } catch (_: Throwable) {
            // 历史库异常不得阻止服务启动
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("voice_clock", "语音报时", NotificationManager.IMPORTANCE_LOW)
            )
        } catch (_: Throwable) {
            // 通知渠道创建失败不致命
        }
        tts = TextToSpeech(this, this)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) {
                // 前导停顿不记录，也不触发结束逻辑。
                if (TimeSpeech.isAuxiliary(utteranceId)) return
                tracker.finish(utteranceId)?.let { record ->
                    try {
                        SpeechHistory(this@ClockService).use { it.add(record) }
                    } catch (_: Throwable) {
                        // 写入历史失败不影响播报流程
                    }
                }
                handler.post { finished(utteranceId, null) }
            }

            override fun onError(utteranceId: String) {
                // 前导停顿失败不影响正式播报。
                if (TimeSpeech.isAuxiliary(utteranceId)) return
                val id = TimeSpeech.speechId(utteranceId)
                tracker.fail(id)
                handler.post { finished(id, "报时失败，请检查系统语音设置") }
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                if (TimeSpeech.isAuxiliary(utteranceId)) return
                val id = TimeSpeech.speechId(utteranceId)
                tracker.fail(id)
                handler.post { finished(id, "报时被中断") }
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.enabled(this)) { ClockAlarms.cancel(this); stopSelf(); return START_NOT_STICKY }
        return try {
            schedule = Settings.load(this)
            next = ClockAlarms.next(this)
            startForeground(1, notification())
            val now = ZonedDateTime.now()
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val reset = intent?.action == START || intent?.action == RELOAD
            val savedAnchor = prefs.getLong("anchor", 0)
            val anchor = if (reset || savedAnchor == 0L) now
            else Instant.ofEpochMilli(savedAnchor).atZone(ZoneId.systemDefault())
            if (reset || savedAnchor == 0L) {
                prefs.edit().putLong("anchor", anchor.toInstant().toEpochMilli()).apply()
            }
            val alarmDue = intent?.getLongExtra("due", 0) ?: 0
            val knownNext = next
            val validAlarm = intent?.action == ALARM && alarmDue != 0L && knownNext != null &&
                alarmDue == knownNext.toInstant().toEpochMilli() && !now.isBefore(knownNext)
            val speak = (intent?.action == START || validAlarm) && schedule.allows(now)
            // Ignore old alarm deliveries after stop/reload and consume a due alarm only once.
            val nextRun = next
            if (reset || validAlarm || nextRun == null || !nextRun.isAfter(now) || intent?.action == RESCHEDULE) {
                next = schedule.next(now, anchor)
            }
            // Re-register even a future persisted alarm: package updates or OS cleanup may have removed it.
            if (!ClockAlarms.schedule(this, next)) {
                next = null
                problem = "请打开应用，允许“闹钟和提醒”权限后恢复报时"
            } else {
                problem = null
            }
            if (speak) {
                holdForSpeech()
                pendingSpeech = if (intent?.action == START) "开启报时" else "自动报时"
                if (ready) {
                    announce()
                } else if (initialized) {
                    pendingSpeech = null
                    problem = "中文语音不可用，请检查系统语音设置"
                    releaseWakeLock()
                }
            }
            updateNotification()
            START_STICKY
        } catch (error: Throwable) {
            problem = "服务启动异常：${error.message}"
            updateNotification()
            START_STICKY
        }
    }

    override fun onInit(status: Int) {
        initialized = true
        try {
            if (status == TextToSpeech.SUCCESS) {
                val language = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                val helper = audioFocus ?: AudioFocusHelper(applicationContext).also { audioFocus = it }
                ready = language != null && language >= TextToSpeech.LANG_AVAILABLE &&
                    tts?.let { TimeSpeech.configure(it, helper) } == true
            }
        } catch (_: Throwable) {
            ready = false
        }
        if (pendingSpeech != null) {
            if (ready) {
                announce()
            } else {
                pendingSpeech = null
                problem = "中文语音不可用，请检查系统语音设置"
                releaseWakeLock()
            }
        }
        updateNotification()
    }

    /**
     * 播报：先在后台线程取天气（带超时），再拼装「时间 + 天气 + 节气」，
     * 然后先播提示音，提示音结束后才送入 TTS。
     */
    private fun announce() {
        val source = pendingSpeech ?: return
        pendingSpeech = null
        val now = ZonedDateTime.now()
        if (!schedule.allows(now)) { releaseWakeLock(); return }
        activeId?.let(tracker::fail)
        activeId = null
        val token = ++buildToken
        val extras = Settings.extras(this)

        // 整体超时兜底：定位 + 网络不应无限等待。
        val timeout = Runnable {
            if (token == buildToken && activeId == null) {
                beginPlayback(BroadcastText.build(BroadcastText.Parts(now, null, solarTerm(extras, now))), source, now, extras)
            }
        }
        workTimeout = timeout
        handler.postDelayed(timeout, 12_000)

        io.execute {
            val weather = if (extras.weatherEnabled) resolveWeather(extras) else null
            handler.post {
                if (token != buildToken) return@post
                workTimeout?.let { handler.removeCallbacks(it) }
                if (activeId != null) return@post
                val text = BroadcastText.build(BroadcastText.Parts(now, weather, solarTerm(extras, now)))
                beginPlayback(text, source, now, extras)
            }
        }
    }

    /** 先播提示音，播完后才开始 TTS 正文。 */
    private fun beginPlayback(text: String, source: String, now: ZonedDateTime, extras: Extras) {
        if (activeId != null) return
        // 申请 duck 焦点：duck=false 时完全不干预其他应用。
        audioFocus?.request(extras.duckOthers)
        val player = chime
        if (player == null) {
            speakText(text, source, now)
            return
        }
        player.play(extras) {
            handler.post {
                if (buildToken != buildToken) return@post
                speakText(text, source, now)
            }
        }
    }

    private fun solarTerm(extras: Extras, now: ZonedDateTime): String? {
        if (!extras.solarTermEnabled) return null
        return try {
            SolarTerms.infoFor(now.toLocalDate(), now.zone).speech
        } catch (_: Throwable) {
            null
        }
    }

    /** 在后台线程调用。返回天气播报文本，失败时回退到缓存。 */
    private fun resolveWeather(extras: Extras): String? {
        return try {
            var lat = extras.latitude
            var lon = extras.longitude

            // 自动定位：缓存超过 3 小时或从未定位时重新定位
            val stale = System.currentTimeMillis() - Settings.locationTimestamp(this) > 3 * 3600_000L
            if (extras.autoLocate && LocationHelper.hasPermission(this) && (stale || !extras.hasLocation)) {
                val latch = CountDownLatch(1)
                val holder = AtomicReference<LocationHelper.Point?>(null)
                handler.post {
                    LocationHelper.request(this, 6_000L) { p ->
                        holder.set(p)
                        latch.countDown()
                    }
                }
                try { latch.await(7, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
                val point = holder.get()
                if (point != null) {
                    lat = point.latitude
                    lon = point.longitude
                    Settings.saveLocation(this, lat, lon)
                }
            }

            if (lat.isNaN() || lon.isNaN()) {
                return Settings.cachedWeather(this).takeIf { it.isNotBlank() }
            }

            val result = WeatherClient.fetch(lat, lon)
            result.getOrNull()?.let { weather ->
                val speech = weather.speech(extras.placeName.takeIf { it.isNotBlank() })
                Settings.saveCachedWeather(this, speech)
                speech
            } ?: Settings.cachedWeather(this).takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            Settings.cachedWeather(this).takeIf { it.isNotBlank() }
        }
    }

    private fun speakText(text: String, source: String, now: ZonedDateTime) {
        if (activeId != null) return
        val id = tracker.begin(SpeechRecord(now, text, source))
        activeId = id
        val result = try {
            TimeSpeech.speak(tts, text, id)
        } catch (_: Throwable) {
            -1
        }
        if (result != TextToSpeech.SUCCESS) {
            tracker.fail(id)
            finished(id, "报时失败，请检查系统中文语音设置")
        }
    }

    private fun holdForSpeech() {
        releaseWakeLock()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voiceclock:speech")
            .apply { acquire(90_000) }
        handler.postDelayed(speechTimeout, 80_000)
    }

    private fun releaseWakeLock() {
        handler.removeCallbacks(speechTimeout)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun finished(id: String, error: String?) {
        if (id != activeId) return
        activeId = null
        problem = error
        workTimeout?.let { handler.removeCallbacks(it) }
        // 播报结束立即释放焦点，其他应用音量恢复。
        audioFocus?.release()
        releaseWakeLock()
        updateNotification()
    }

    private fun updateNotification() {
        try {
            if (Settings.enabled(this)) getSystemService(NotificationManager::class.java).notify(1, notification())
        } catch (_: Throwable) {
            // 通知更新失败不影响播报
        }
    }

    private fun notification(): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val nextText = next?.format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) ?: "没有可用时间"
        val detail = problem ?: if (!ready) "正在准备中文语音；如无声音请检查系统语音设置"
        else "下次：$nextText"
        val modeIndex = schedule.mode.coerceIn(0, Schedule.modes.size - 1)
        return Notification.Builder(this, "voice_clock")
            .setContentTitle("语音报时已开启")
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText("${Schedule.modes[modeIndex]} · $detail"))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        tracker.clear()
        handler.removeCallbacksAndMessages(null)
        audioFocus?.release()
        chime?.release()
        chime = null
        releaseWakeLock()
        io.shutdownNow()
        tts?.stop()
        tts?.shutdown()
        tts = null
        // Keep the system alarm when Android reclaims the service; explicit stop cancels it in the UI.
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val START = "com.example.voiceclock.START"
        const val RELOAD = "com.example.voiceclock.RELOAD"
        const val RESTORE = "com.example.voiceclock.RESTORE"
        const val RESCHEDULE = "com.example.voiceclock.RESCHEDULE"
        const val ALARM = "com.example.voiceclock.ALARM"
    }
}
