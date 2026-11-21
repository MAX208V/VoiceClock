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
 * 安全性：
 * AudioManager 延迟到首次使用时获取，且不用 `as` 强转。
 * 本类可能在 Activity 字段初始化阶段被构造，那时 getSystemService 可能返回 null，
 * 早期版本因此直接闪退。现在拿不到 AudioManager 时仅返回 false，不崩。
 */
class AudioFocusHelper(private val context: Context) {

    private fun audioManager(): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var legacyListener: AudioManager.OnAudioFocusChangeListener? = null
    private var holding = false

    private var cachedAttributes: AudioAttributes? = null

    /** 播报用音频属性：辅助类用途，不强制走扬声器，跟随当前输出设备（耳机/蓝牙）。 */
    fun speechAttributes(): AudioAttributes {
        cachedAttributes?.let { return it }
        val built = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        cachedAttributes = built
        return built
    }

    /** 申请音频焦点。duck=false 时不申请；任何异常都不影响播报。 */
    fun request(duck: Boolean): Boolean {
        release()
        if (!duck) {
            holding = false
            return true
        }
        val manager = audioManager() ?: return true
        return try {
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
                manager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
            holding = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            true
        } catch (_: Exception) {
            // 宁可混音，也不要因音频焦点报错而中断播报。
            holding = false
            true
        }
    }

    /** 释放焦点，让其他应用恢复音量。 */
    fun release() {
        val manager = audioManager()
        if (holding && manager != null) {
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    focusRequest?.let { manager.abandonAudioFocusRequest(it) }
                } else {
                    legacyListener?.let {
                        @Suppress("DEPRECATION")
                        manager.abandonAudioFocus(it)
                    }
                }
            } catch (_: Exception) {
                // 释放失败不影响后续使用。
            }
        }
        focusRequest = null
        legacyListener = null
        holding = false
    }
}
