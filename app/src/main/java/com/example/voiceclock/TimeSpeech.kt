package com.example.voiceclock

import android.speech.tts.TextToSpeech

/**
 * TTS 播报：提示音与语音共用一条队列，保证先响三声再念内容。
 *
 * 音频设计：
 * - 使用「辅助」用途，不强制走扬声器，自动跟随系统路由（耳机 / 蓝牙 / 扬声器）
 * - 前端插入停顿，抵消蓝牙链路延迟，避免开头被吞
 * - 是否压低其他应用由 AudioFocusHelper 负责
 */
object TimeSpeech {
    private const val CHIME = "voice-clock-chime"
    private const val CHIME_SUFFIX = ":chime"
    private const val LEAD_SUFFIX = ":lead"

    /**
     * 前导停顿时长（毫秒）。
     * TTS 无法直接播静音，这里用一串逗号让引擎产生足够长的自然停顿；
     * 以半速（0.5x）播报时，6 个逗号的停顿约 800ms，足以覆盖蓝牙唤醒延迟。
     */
    const val LEAD_IN_MS = 800L

    /** 前导停顿文本：逗号会产生自然静默，且不会发音。 */
    internal const val LEAD_TEXT = "，，，，，，"

    /**
     * 配置 TTS。音频属性由调用方通过 AudioFocusHelper 提供，
     * 这里只做语音、语速与提示音的准备，避免静态依赖持有 Context。
     */
    fun configure(tts: TextToSpeech, packageName: String, helper: AudioFocusHelper): Boolean =
        tts.setAudioAttributes(helper.speechAttributes()) == TextToSpeech.SUCCESS &&
            tts.setSpeechRate(0.5f) == TextToSpeech.SUCCESS &&
            tts.addEarcon(CHIME, packageName, R.raw.time_chime) == TextToSpeech.SUCCESS

    /**
     * 播报。顺序：前导停顿 → 三声提示音 → 正式内容。
     */
    fun speak(tts: TextToSpeech?, text: String, id: String): Int {
        if (tts == null) return TextToSpeech.ERROR
        // 1) 前导停顿：给蓝牙/耳机链路建立时间
        tts.speak(LEAD_TEXT, TextToSpeech.QUEUE_FLUSH, null, id + LEAD_SUFFIX)
        // 2) 三声提示音
        if (tts.playEarcon(CHIME, TextToSpeech.QUEUE_ADD, null, id + CHIME_SUFFIX) != TextToSpeech.SUCCESS) {
            return TextToSpeech.ERROR
        }
        // 3) 正式内容
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (result != TextToSpeech.SUCCESS) tts.stop()
        return result
    }

    fun isChime(id: String) = id.endsWith(CHIME_SUFFIX)

    /** 前导停顿与提示音都不计入正式播报记录。 */
    fun isAuxiliary(id: String) = id.endsWith(CHIME_SUFFIX) || id.endsWith(LEAD_SUFFIX)

    fun speechId(id: String) = id.removeSuffix(CHIME_SUFFIX).removeSuffix(LEAD_SUFFIX)
}
