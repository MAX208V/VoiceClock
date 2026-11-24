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

    /** 本次实际播放时长（毫秒），用于波形进度归一化。0 表示按音频全长。 */
    private var effectiveMs: Long = 0L

    /**
     * 播放会话令牌。
     * 每次 play() 递增；异步回调（prepareAsync 的 onPrepared、onError）
     * 都要校验令牌，过期回调一律丢弃，避免上一首迟到的回调又把音频播出来。
     */
    private var session: Int = 0

    /** 设置播放进度回调（0f..1f），用于界面波形动画。传入 null 停止回调。 */
    fun setProgressCallback(callback: ((Float) -> Unit)?) {
        progressCallback = callback
        stopProgressLoop()
        if (callback != null) startProgressLoop()
    }

    companion object {
        private const val TAG = "ChimePlayer"
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
        // 开启新会话，之前所有在途的异步回调都会失效
        val token = ++session
        when (extras.chimeSource) {
            ChimeSource.SILENT -> onDone()
            ChimeSource.DEFAULT -> playRaw(R.raw.time_chime, extras.chimeMaxSeconds, token, onDone)
            ChimeSource.SYSTEM -> {
                val uri = extras.chimeUri
                if (uri.isBlank()) {
                    playRaw(R.raw.time_chime, extras.chimeMaxSeconds, token, onDone)
                } else {
                    playUri(uri, extras.chimeMaxSeconds, token, onDone)
                }
            }
        }
    }

    private fun playRaw(resId: Int, maxSeconds: Int, token: Int, onDone: () -> Unit) {
        if (token != session) {
            onDone()
            return
        }
        try {
            val created = MediaPlayer.create(appContext, resId)
            if (created == null) {
                onDone()
                return
            }
            start(created, maxSeconds, token, onDone)
        } catch (error: Throwable) {
            Log.w(TAG, "播放内置提示音失败", error)
            onDone()
        }
    }

    private fun playUri(uriString: String, maxSeconds: Int, token: Int, onDone: () -> Unit) {
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(audioAttributes())
            mp.setDataSource(appContext, Uri.parse(uriString))
            mp.setOnPreparedListener { prepared ->
                // 关键：迟到的 prepared 回调（用户已重新点试听）必须丢弃，
                // 否则会与新的播放器同时出声，造成重叠。
                if (token != session) {
                    Log.d(TAG, "丢弃过期的 prepared 回调")
                    try {
                        prepared.release()
                    } catch (_: Throwable) {
                        // 忽略
                    }
                    return@setOnPreparedListener
                }
                start(prepared, maxSeconds, token, onDone)
            }
            mp.setOnErrorListener { errored, what, extra ->
                Log.w(TAG, "系统铃声播放失败 what=$what extra=$extra")
                if (token != session) {
                    return@setOnErrorListener true
                }
                // 回退前先释放这个失败的实例，避免与内置提示音叠加
                try {
                    errored.release()
                } catch (_: Throwable) {
                    // 忽略
                }
                if (player === errored) player = null
                playRaw(R.raw.time_chime, maxSeconds, token, onDone)
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (error: Throwable) {
            Log.w(TAG, "无法加载系统铃声，回退内置提示音", error)
            try {
                mp.release()
            } catch (_: Throwable) {
                // 忽略
            }
            if (player === mp) player = null
            playRaw(R.raw.time_chime, maxSeconds, token, onDone)
        }
    }

    /**
     * 开始播放并按需截断。
     *
     * 时长策略：
     * - maxSeconds <= 0 → 完整播放
     * - 否则在 min(maxSeconds, 实际时长) 后停止，然后继续播报
     */
    private fun start(mp: MediaPlayer, maxSeconds: Int, token: Int, onDone: () -> Unit) {
        // 过期会话直接丢弃，绝不再出声
        if (token != session) {
            try {
                mp.release()
            } catch (_: Throwable) {
                // 忽略
            }
            return
        }

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
            try {
                media.release()
            } catch (_: Throwable) {
                // 忽略
            }
            if (player === media) player = null
        }

        val actualDuration = try {
            mp.duration.coerceAtLeast(0)
        } catch (_: Throwable) {
            0
        }

        // 实际会播放的时长（0 表示按音频全长）
        effectiveMs = when {
            maxSeconds <= 0 -> actualDuration.toLong()
            actualDuration > 0 -> minOf(maxSeconds * 1000L, actualDuration.toLong())
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

        // 只有显式设置了较短时长时才安装截断定时器。
        // 完整播放交给 onCompletion，不再用 15 秒硬上限误切长铃声。
        if (maxSeconds > 0 && actualDuration > 0 && maxSeconds * 1000L < actualDuration) {
            val cutMillis = maxSeconds * 1000L
            val cut = Runnable {
                if (!finished && token == session) {
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
        } else if (maxSeconds > 0) {
            // 时长未知（actualDuration <= 0）时仍按设定秒数兜底截断
            val cutMillis = maxSeconds * 1000L
            val cut = Runnable {
                if (!finished && token == session) {
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
                    // 进度基准是「实际会播放的时长」：若设置了截断，则用截断值做分母，
                    // 这样选 1 秒时，波形会在 1 秒内走完对应比例，而不是按整首曲子算。
                    val basis = if (effectiveMs > 0) effectiveMs else duration.toLong()
                    if (basis > 0) {
                        callback((current.currentPosition.toFloat() / basis).coerceIn(0f, 1f))
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
        // 使所有在途的异步回调失效（例如 prepareAsync 尚未返回）
        session++
        try {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Throwable) {
            // 释放失败忽略
        }
        player = null
        effectiveMs = 0L
        try {
            progressCallback?.invoke(0f)
        } catch (_: Throwable) {
            // 忽略
        }
    }
}
