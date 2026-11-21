package com.example.voiceclock

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * 音频焦点管理：决定播报时是否压低其他应用。
 *
 * - 不压低（duck = false）：不申请焦点，完全混音，与音乐共存
 * - 压低（duck = true）：申请 TRANSIENT_MAY_DUCK，音乐自动降音量，播完立即释放
 *
 * 选择此方式而不是 AUDIOFOCUS_GAIN_TRANSIENT，
 * 后者会让音乐一直暂停，不符合「同时播放」的要求。
 */
class AudioFocusHelper(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var legacyListener: AudioManager.OnAudioFocusChangeListener? = null
    private var holding = false

    companion object {
        /** 播报用音频属性：辅助类用途，不强制走扬声器，跟随当前输出设备（耳机/蓝牙）。 */
        fun speechAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    /** 申请音频焦点。duck=false 时不申请，返回 true 表示可直接播放。 */
    fun request(duck: Boolean): Boolean {
        release()
        if (!duck) {
            holding = false
            return true
        }
        val listener = AudioManager.OnAudioFocusChangeListener { /* 播报很短，忽略变化 */ }
        legacyListener = listener
        val attributes = speechAttributes()
        val result: Int = if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(listener)
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        holding = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        // 即使被拒绝也仍然播报：宁可混音也不要静默失败。
        return true
    }

    /** 释放焦点，让其他应用恢复音量。 */
    fun release() {
        if (!holding) {
            focusRequest = null
            legacyListener = null
            return
        }
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            legacyListener?.let {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(it)
            }
        }
        focusRequest = null
        legacyListener = null
        holding = false
    }
}
