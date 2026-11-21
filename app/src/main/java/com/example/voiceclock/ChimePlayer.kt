package com.example.voiceclock

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

/**
 * 提示音播放器。
 *
 * 支持三种来源：
 * - DEFAULT：内置三声「滴」（res/raw/time_chime.wav）
 * - SYSTEM：用户从系统铃声选择器选定的铃声（URI）
 * - SILENT：不播提示音
 *
 * 使用 MediaPlayer 而不是 Ringtone，因为 MediaPlayer 能通过
 * setOnCompletionListener 精确得知「播完了」，避免与 TTS 叠音。
 * 音频属性与播报一致，因此同样会跟随耳机/蓝牙路由。
 */
class ChimePlayer(context: Context) {

    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null

    companion object {
        private const val TAG = "ChimePlayer"
        /** 提示音最长播放时长，防止异常文件卡住播报流程。 */
        private const val MAX_DURATION_MS = 5_000L
    }

    private fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * 播放提示音。
     * onDone 一定会被调用（成功播完、失败、或来源为静音），保证后续 TTS 能继续。
     * 必须在主线程调用。
     */
    fun play(extras: Extras, onDone: () -> Unit) {
        release()
        when (extras.chimeSource) {
            ChimeSource.SILENT -> onDone()
            ChimeSource.DEFAULT -> playRaw(R.raw.time_chime, onDone)
            ChimeSource.SYSTEM -> {
                val uri = extras.chimeUri
                if (uri.isBlank()) playRaw(R.raw.time_chime, onDone)
                else playUri(uri, onDone)
            }
        }
    }

    private fun playRaw(resId: Int, onDone: () -> Unit) {
        try {
            val created = MediaPlayer.create(appContext, resId)
            if (created == null) {
                onDone()
                return
            }
            start(created, onDone)
        } catch (error: Throwable) {
            Log.w(TAG, "播放内置提示音失败", error)
            onDone()
        }
    }

    private fun playUri(uriString: String, onDone: () -> Unit) {
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(audioAttributes())
            mp.setDataSource(appContext, Uri.parse(uriString))
            mp.setOnPreparedListener { start(it, onDone) }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "系统铃声播放失败 what=$what extra=$extra")
                // 回退到内置提示音，避免静默
                playRaw(R.raw.time_chime, onDone)
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (error: Throwable) {
            Log.w(TAG, "无法加载系统铃声，回退内置提示音", error)
            playRaw(R.raw.time_chime, onDone)
        }
    }

    private fun start(mp: MediaPlayer, onDone: () -> Unit) {
        player = mp
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                onDone()
            }
        }
        mp.setOnCompletionListener { media ->
            finish()
            media.release()
            if (player === media) player = null
        }
        // 超时兜底：提示音文件异常时也要继续播报
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!finished) {
                Log.w(TAG, "提示音超时，跳过")
                finish()
                release()
            }
        }, MAX_DURATION_MS)
        try {
            mp.start()
            val duration = mp.duration
            if (duration in 1..MAX_DURATION_MS.toInt()) {
                // 时长正常，交给 onCompletion
            }
        } catch (error: Throwable) {
            Log.w(TAG, "提示音启动失败", error)
            finish()
            release()
        }
    }

    /** 停止并释放当前提示音。 */
    fun release() {
        try {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Throwable) {
            // 释放失败忽略
        }
        player = null
    }
}
