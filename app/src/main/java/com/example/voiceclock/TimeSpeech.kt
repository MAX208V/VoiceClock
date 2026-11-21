package com.example.voiceclock

import android.speech.tts.TextToSpeech

/**
 * TTS 播报。
 *
 * 音频设计：
 * - 使用「辅助」用途，不强制走扬声器，自动跟随系统路由（耳机 / 蓝牙 / 扬声器）
 * - 提示音由 ChimePlayer 单独播放，播完后才调 speak()，避免叠音
 * - 前导停顿用于抵退蓝牙链路延迟
 * - 是否压低其他应用由 AudioFocusHelper 负责
 */
object TimeSpeech {
    private const val LEAD_SUFFIX = ":lead"

    /**
     * 前导停顿时长（毫秒）。
     * TTS 无法直接播静音，这里用一串逗号让引擎产生足够长的自然停顿；
     * 以半速（0.5x）播报时，6 个逗号的停顿约 800ms。
     */
    const val LEAD_IN_MS = 800L

    /** 前导停顿文本：逗号会产生自然静默，且不会发音。 */
    internal const val LEAD_TEXT = "，，，，，"

    /** 配置 TTS：语音、语速。提示音已改由 ChimePlayer 负责，不再注册 earcon。 */
    fun configure(tts: TextToSpeech, helper: AudioFocusHelper): Boolean =
        tts.setAudioAttributes(helper.speechAttributes()) == TextToSpeech.SUCCESS &&
            tts.setSpeechRate(0.5f) == TextToSpeech.SUCCESS

    /**
     * 播报正文。前导停顿 → 正式内容。
     * 提示音不在这里播放，调用方应在提示音播完后才调用本方法。
     */
    fun speak(tts: TextToSpeech?, text: String, id: String): Int {
        if (tts == null) return TextToSpeech.ERROR
        tts.speak(LEAD_TEXT, TextToSpeech.QUEUE_FLUSH, null, id + LEAD_SUFFIX)
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (result != TextToSpeech.SUCCESS) tts.stop()
        return result
    }

    /** 前导停顿不计入正式播报记录。 */
    fun isAuxiliary(id: String) = id.endsWith(LEAD_SUFFIX)

    fun speechId(id: String) = id.removeSuffix(LEAD_SUFFIX)
}
