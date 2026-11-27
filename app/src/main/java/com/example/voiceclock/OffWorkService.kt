package com.example.voiceclock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 下班提醒播报服务。
 *
 * 与 ClockService（定时报时）完全独立：
 * - 由 OffWorkReceiver 在闹钟到点时拉起
 * - 文案只有两句：「还有 N 分钟下班」/「下班时间到了」
 * - 可选「只在耳机时播报」和振动
 */
class OffWorkService : Service(), TextToSpeech.OnInitListener {
    companion object {
        private const val TAG = "OffWorkService"
        private const val CHANNEL = "off_work"
        private const val NOTIFICATION_ID = 2
        /** 服务最长存活时间，兜底防止 TTS 卡死导致通知常驻。 */
        private const val MAX_ALIVE_MS = 30_000L
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var stopped = false
    private var speechText: String? = null
    private var speechStarted = false
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "下班提醒", NotificationManager.IMPORTANCE_LOW)
            )
        } catch (_: Throwable) {
            // 渠道已存在时忽略
        }
        vibrator = obtainVibrator()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = OffWorkSettingsStorage.load(this)
        if (!settings.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 前台服务必须先起通知，否则 Android 会抛 ANR 级异常
        startForeground(NOTIFICATION_ID, notification("下班提醒"))

        val kindName = intent?.getStringExtra("kind")
        val kind = runCatching { ReminderKind.valueOf(kindName ?: "") }.getOrNull() ?: ReminderKind.ON_END
        val minutes = intent?.getIntExtra("minutes", 0) ?: 0
        speechText = kind.text(minutes)
        Log.i(TAG, "触发提醒 kind=$kind text=$speechText")

        // 只耳机模式：没有耳机/蓝牙输出就静默跳过（不播报也不振动）
        if (settings.onlyHeadset && !isHeadsetConnected()) {
            Log.i(TAG, "仅耳机模式，但未检测到耳机/蓝牙设备，跳过本次提醒")
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        holdFor(MAX_ALIVE_MS)

        if (settings.vibrate) vibrate(settings.vibrateMode)

        // TTS 可能还在初始化；onInit 里会再调用一次 speakNow
        if (ready) speakNow()
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        try {
            if (status == TextToSpeech.SUCCESS) {
                val language = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                ready = language != null && language >= TextToSpeech.LANG_AVAILABLE
            }
        } catch (_: Throwable) {
            ready = false
        }
        Log.i(TAG, "TTS 初始化 status=$status ready=$ready")
        if (ready) speakNow() else stopSelf()
    }

    /** 播报正文（只播一次）。 */
    private fun speakNow() {
        if (stopped || speechStarted) return
        val text = speechText ?: return
        speechStarted = true
        try {
            // 前导停顿抵消蓝牙链路延迟
            tts?.speak(TimeSpeech.LEAD_TEXT, TextToSpeech.QUEUE_FLUSH, null, "offwork:lead")
            val result = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "offwork:text")
            Log.i(TAG, "播报「$text」result=$result")
        } catch (error: Throwable) {
            Log.w(TAG, "播报失败", error)
            stopSelf()
        }
    }

    private fun vibrate(mode: VibrateMode) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        try {
            when (mode) {
                VibrateMode.SHORT -> device.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                VibrateMode.LONG -> device.vibrate(VibrationEffect.createOneShot(1500, VibrationEffect.DEFAULT_AMPLITUDE))
                VibrateMode.DOUBLE -> device.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 300, 250, 300), -1)
                )
            }
        } catch (error: Throwable) {
            Log.w(TAG, "振动失败", error)
        }
    }

    /** 是否连接了有线耳机或蓝牙音频设备。 */
    private fun isHeadsetConnected(): Boolean {
        val manager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return try {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                when (device.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> true
                    else -> false
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "检测耳机失败", error)
            false
        }
    }

    private fun obtainVibrator(): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Throwable) {
        null
    }

    private fun acquireWakeLock() {
        try {
            val manager = getSystemService(PowerManager::class.java) ?: return
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voiceclock:offwork")
                .apply { acquire(MAX_ALIVE_MS) }
        } catch (_: Throwable) {
            // 拿不到锁也能尝试播报
        }
    }

    private fun holdFor(millis: Long) {
        handler.postDelayed({ stopSelf() }, millis)
    }

    private fun notification(title: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(speechText ?: "正在播报下班提醒")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pending)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopped = true
        // 注意：这里不重排下一次。重排由 OffWorkReceiver 在拉起服务之前完成，
        // 因为服务可能因「仅耳机模式跳过」或系统回收而提前结束，
        // 放在这里会造成「跳过→重排→再次触发」的反复循环。
        handler.removeCallbacksAndMessages(null)
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {
            // 忽略
        }
        tts = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
            // 忽略
        }
        wakeLock = null
        super.onDestroy()
    }
}
