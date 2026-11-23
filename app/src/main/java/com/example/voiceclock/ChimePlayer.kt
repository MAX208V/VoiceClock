package com.example.voiceclock

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 提示音播放器。
 *
 * 支持三种来源：
 * - DEFAULT：内置三声「滴」（res/raw/time_chime.wav）
 * - SYSTEM：用户从系统铃声选择器选定的铃声（URI）
 * - SILENT：不播提示音
 *
 * 使用 MediaPlayer 而不是 Ringtone，因为 MediaPlayer 能精确得知「播完了」，
 * 并且可以通过 stop() 实现按时长截断。
 *
 * 音频属性与播报一致，因此同样会跟随耳机 / 蓝牙路由。
 */
class ChimePlayer(context: Context) {

    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var cutRunnable: Runnable? = null

    private var progressCallback: ((Float) -> Unit)? = null
    private var progressTick: Runnable? = null

    /** 设置播放进度回调（0f..1f），用于界面波形动画。传入 null 停止回调。 */
    fun setProgressCallback(callback: ((Float) -> Unit)?) {
        progressCallback = callback
        stopProgressLoop()
        if (callback != null) startProgressLoop()
    }

    companion object {
        private const val TAG = "ChimePlayer"
        /** 绝对上限，防止异常文件卡住播报流程。 */
        private const val HARD_LIMIT_MS = 15_000L
        private const val PROGRESS_INTERVAL_MS = 50L
    }

    private fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * 播放提示音。
     * onDone 一定会被调用（播完、被截断、失败、静音），保证后续 TTS 能继续。
     * 必须在主线程调用。
     */
    fun play(extras: Extras, onDone: () -> Unit) {
        release()
        when (extras.chimeSource) {
            ChimeSource.SILENT -> onDone()
            ChimeSource.DEFAULT -> playRaw(R.raw.time_chime, extras.chimeMaxSeconds, onDone)
            ChimeSource.SYSTEM -> {
                val uri = extras.chimeUri
                if (uri.isBlank()) {
                    playRaw(R.raw.time_chime, extras.chimeMaxSeconds, onDone)
                } else {
                    playUri(uri, extras.chimeMaxSeconds, onDone)
                }
            }
        }
    }

    private fun playRaw(resId: Int, maxSeconds: Int, onDone: () -> Unit) {
        try {
            val created = MediaPlayer.create(appContext, resId)
            if (created == null) {
                onDone()
                return
            }
            start(created, maxSeconds, onDone)
        } catch (error: Throwable) {
            Log.w(TAG, "播放内置提示音失败", error)
            onDone()
        }
    }

    private fun playUri(uriString: String, maxSeconds: Int, onDone: () -> Unit) {
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(audioAttributes())
            mp.setDataSource(appContext, Uri.parse(uriString))
            mp.setOnPreparedListener { start(it, maxSeconds, onDone) }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "系统铃声播放失败 what=$what extra=$extra")
                // 回退到内置提示音，避免静默
                playRaw(R.raw.time_chime, maxSeconds, onDone)
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (error: Throwable) {
            Log.w(TAG, "无法加载系统铃声，回退内置提示音", error)
            playRaw(R.raw.time_chime, maxSeconds, onDone)
        }
    }

    /**
     * 开始播放并按需截断。
     *
     * 时长策略：
     * - maxSeconds <= 0 → 完整播放
     * - 否则在 min(maxSeconds, 实际时长) 后停止，然后继续播报
     */
    private fun start(mp: MediaPlayer, maxSeconds: Int, onDone: () -> Unit) {
        player = mp
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                cancelCut()
                onDone()
            }
        }

        mp.setOnCompletionListener { media ->
            finish()
            media.release()
            if (player === media) player = null
        }

        val actualDuration = try {
            mp.duration.coerceAtLeast(1)
        } catch (_: Throwable) {
            0
        }

        // 计算截断时间
        val cutMillis: Long = when {
            maxSeconds <= 0 -> HARD_LIMIT_MS
            actualDuration > 0 -> minOf(maxSeconds * 1000L, actualDuration.toLong()).coerceAtMost(HARD_LIMIT_MS)
            else -> maxSeconds * 1000L
        }

        try {
            mp.start()
        } catch (error: Throwable) {
            Log.w(TAG, "提示音启动失败", error)
            finish()
            release()
            return
        }

        // 到时截断：停止播放并结束
        val cut = Runnable {
            if (!finished) {
                Log.d(TAG, "提示音达到设定时长 ${cutMillis}ms，截断")
                try {
                    mp.stop()
                } catch (_: Throwable) {
                    // 忽略
                }
                finish()
                try {
                    mp.release()
                } catch (_: Throwable) {
                    // 忽略
                }
                if (player === mp) player = null
            }
        }
        cutRunnable = cut
        handler.postDelayed(cut, cutMillis)
    }

    /** 清理截断任务。 */
    private fun cancelCut() {
        cutRunnable?.let { handler.removeCallbacks(it) }
        cutRunnable = null
    }

    /** 播放进度轮询，驱动界面波形动画。 */
    private fun startProgressLoop() {
        val tick = object : Runnable {
            override fun run() {
                val current = player
                val callback = progressCallback
                if (current == null || callback == null) return
                try {
                    val duration = current.duration
                    if (duration > 0) {
                        callback((current.currentPosition.toFloat() / duration).coerceIn(0f, 1f))
                    }
                } catch (_: Throwable) {
                    // 忽略
                }
                progressTick = this
                handler.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }
        progressTick = tick
        handler.post(tick)
    }

    private fun stopProgressLoop() {
        progressTick?.let { handler.removeCallbacks(it) }
        progressTick = null
    }

    /** 当前是否正在播放。 */
    fun isPlaying(): Boolean = try {
        player?.isPlaying == true
    } catch (_: Throwable) {
        false
    }

    /** 停止并释放当前提示音。 */
    fun release() {
        cancelCut()
        try {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Throwable) {
            // 释放失败忽略
        }
        player = null
        try {
            progressCallback?.invoke(0f)
        } catch (_: Throwable) {
            // 忽略
        }
    }
}
