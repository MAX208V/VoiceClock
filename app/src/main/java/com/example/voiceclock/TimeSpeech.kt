package com.example.voiceclock

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.UUID

/**
 * TTS 播报：提示音与语音共用一条队列，保证先响三声再念内容。
 *
 * 音频设计：
 * - 使用「辅助」用途，不强制走扬声器，自动跟随系统路由（耳机 / 蓝牙 / 扬声器）
 * - 前端插入短静音，抵消蓝牙设备建立链路的延迟，避免开头被吞
 * - 按设置决定是否申请 duck 焦点（压低其他应用）
 */
object TimeSpeech {
    private const val CHIME = "voice-clock-chime"
    private const val CHIME_SUFFIX = ":chime"

    /** 蓝牙唤醒前导静音时长（毫秒）。 */
    const val LEAD_IN_MS = 800L

    fun configure(tts: TextToSpeech, packageName: String): Boolean =
        tts.setAudioAttributes(AudioFocusHelper.speechAttributes()) == TextToSpeech.SUCCESS &&
            tts.setSpeechRate(0.5f) == TextToSpeech.SUCCESS &&
            tts.addEarcon(CHIME, packageName, R.raw.time_chime) == TextToSpeech.SUCCESS

    /**
     * 播报。先插 LEAD_IN_MS 静音（抵消蓝牙延迟），再放提示音，最后念文本。
     * 静音用一段空 utterance 实现，其完成回调会作为正常语音 id 被忽略，
     * 不影响 SpeechHistory 记录（只在正式语音 onDone 时记录）。
     */
    fun speak(tts: TextToSpeech?, text: String, id: String): Int {
        if (tts == null) return TextToSpeech.ERROR
        // 1) 前导静音：给蓝牙/耳机链路建立时间
        val lead = "[s]" + "\u200B".repeat(1)
        tts.speak(lead, TextToSpeech.QUEUE_FLUSH, null, id + LEAD_SUFFIX)
        // 2) 三声提示音
        if (tts.playEarcon(CHIME, TextToSpeech.QUEUE_ADD, null, id + CHIME_SUFFIX) != TextToSpeech.SUCCESS)
            return TextToSpeech.ERROR
        // 3) 正式内容
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (result != TextToSpeech.SUCCESS) tts.stop()
        return result
    }

    private const val LEAD_SUFFIX = ":lead"

    fun isChime(id: String) = id.endsWith(CHIME_SUFFIX)

    /** 前导静音与提示音都不计入正式播报记录。 */
    fun isAuxiliary(id: String) = id.endsWith(CHIME_SUFFIX) || id.endsWith(LEAD_SUFFIX)

    fun speechId(id: String) = id.removeSuffix(CHIME_SUFFIX).removeSuffix(LEAD_SUFFIX)
}
