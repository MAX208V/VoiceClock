package com.example.voiceclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 校验播报 utterance 标识分类：辅助音频不能计入正式记录。 */
class TimeSpeechIdTest {

    private val id = "abc-123"

    @Test fun chimeAndLeadAreAuxiliary() {
        assertTrue(TimeSpeech.isAuxiliary(id + ":chime"))
        assertTrue(TimeSpeech.isAuxiliary(id + ":lead"))
    }

    @Test fun mainSpeechIsNotAuxiliary() {
        assertFalse(TimeSpeech.isAuxiliary(id))
    }

    @Test fun speechIdStripsBothSuffixes() {
        assertEquals(id, TimeSpeech.speechId(id + ":chime"))
        assertEquals(id, TimeSpeech.speechId(id + ":lead"))
        assertEquals(id, TimeSpeech.speechId(id))
    }

    @Test fun chimeIsRecognisedButLeadIsNotChime() {
        assertTrue(TimeSpeech.isChime(id + ":chime"))
        assertFalse(TimeSpeech.isChime(id + ":lead"))
        assertNotEquals(id + ":lead", TimeSpeech.speechId(id + ":lead"))
    }

    @Test fun leadTextProducesNoAudibleSyllable() {
        // 前导文本必须只有标点，避免被引擎念出内容
        assertTrue(TimeSpeech.LEAD_TEXT.all { it == '，' || it == '，' })
        assertTrue(TimeSpeech.LEAD_IN_MS > 0)
    }
}
