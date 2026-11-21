package com.example.voiceclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 校验播报标识分类与前导停顿：辅助音频不能计入正式记录。 */
class TimeSpeechIdTest {

    private val id = "abc-123"
    private val fullWidthComma = '\uFF0C'

    @Test fun leadIsAuxiliary() {
        assertTrue(TimeSpeech.isAuxiliary(id + ":lead"))
    }

    @Test fun mainSpeechIsNotAuxiliary() {
        assertFalse(TimeSpeech.isAuxiliary(id))
        assertFalse(TimeSpeech.isAuxiliary(id + ":something"))
    }

    @Test fun speechIdStripsLeadSuffix() {
        assertEquals(id, TimeSpeech.speechId(id + ":lead"))
        assertEquals(id, TimeSpeech.speechId(id))
    }

    @Test fun leadTextIsOnlyFullWidthCommas() {
        // 前导文本必须只含全角逗号，引擎才会产生停顿而不发音
        assertTrue("前导文本不能为空", TimeSpeech.LEAD_TEXT.isNotEmpty())
        assertTrue(
            "前导文本只能包含全角逗号，实际: ${TimeSpeech.LEAD_TEXT}",
            TimeSpeech.LEAD_TEXT.all { it == fullWidthComma }
        )
        assertTrue("前导时长应为正数", TimeSpeech.LEAD_IN_MS > 0)
    }
}
